package com.mobilecoder.ide.feature.ssh

import android.os.Build
import android.util.Base64
import com.mobilecoder.ide.core.storage.SshKeyStore
import com.mobilecoder.ide.core.storage.SshKeyType
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Security
import java.security.interfaces.RSAPublicKey
import java.security.spec.RSAPrivateCrtKeySpec
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.ASN1OctetString
import org.bouncycastle.asn1.ASN1Primitive
import org.bouncycastle.asn1.ASN1Sequence
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.jce.provider.BouncyCastleProvider

/**
 * SSH 密钥生成 / 导入（PRD 2.6 可视化一键生成 RSA / ED25519）。
 *
 * 安全约束（PRD 3.1）：
 *  - 私钥仅在内存中构造，导出为 PKCS#8 PEM 后立即交给 [com.mobilecoder.ide.core.storage.SshKeyStore]
 *    加密落盘；本类不打印、不缓存任何私钥内容
 *  - 公钥导出为 OpenSSH 单行格式（手工构造 wire format）
 *
 * 说明：本模块打包的 bcprov-jdk18on 以 `BC` 提供者注册（insertProviderAt 幂等）。
 */
internal class SshKeyException(message: String) : Exception(message)

/** 生成 / 导入的产物（私钥 PEM 仅内存持有）。 */
internal data class SshPreparedKey(
    val type: SshKeyType,
    val bits: Int,
    val privateKeyPem: String,
    val publicKey: String,
    val fingerprint: String,
)

internal object SshKeyFactory {

    private const val OID_RSA = "1.2.840.113549.1.1.1"
    private const val OID_ED25519 = "1.3.101.112"
    private const val OID_PBES2 = "1.2.840.113549.1.5.13"
    private const val OID_PBKDF2 = "1.2.840.113549.1.5.12"

    /** 默认公钥注释：`mobilecoder@设备名`。 */
    fun defaultComment(): String {
        val device = Build.MODEL?.trim().orEmpty().ifBlank { "device" }
        return "mobilecoder@$device"
    }

    // ------------------------------------------------------------------
    // 生成
    // ------------------------------------------------------------------

    /** 生成密钥对并导出（必须在 Dispatchers.IO 中调用：RSA 4096 可能耗时数秒）。 */
    fun generate(type: SshKeyType, bits: Int, comment: String): SshPreparedKey {
        val keyPair: KeyPair = when (type) {
            SshKeyType.RSA -> {
                val kpg = keyPairGenerator("RSA")
                kpg.initialize(if (bits >= 4096) 4096 else 2048, SecureRandom())
                kpg.generateKeyPair()
            }

            SshKeyType.ED25519 -> keyPairGenerator("Ed25519").generateKeyPair()
        }
        val publicKey = keyPair.public ?: throw SshKeyException("密钥对生成失败")
        return prepare(publicKey, keyPair.private.encoded, type, comment)
    }

    private fun prepare(
        publicKey: PublicKey,
        pkcs8Der: ByteArray,
        type: SshKeyType,
        comment: String,
    ): SshPreparedKey {
        val line = openSshPublicKey(publicKey, type, comment)
        val bits = when (type) {
            SshKeyType.RSA -> (publicKey as? RSAPublicKey)?.modulus?.bitLength()
                ?: throw SshKeyException("无法读取 RSA 公钥参数")

            SshKeyType.ED25519 -> 256
        }
        return SshPreparedKey(
            type = type,
            bits = bits,
            privateKeyPem = pkcs8Pem(pkcs8Der),
            publicKey = line,
            fingerprint = SshKeyStore.fingerprintOf(line),
        )
    }

    /** OpenSSH 单行公钥（含可选 comment）。 */
    fun openSshPublicKey(publicKey: PublicKey, type: SshKeyType, comment: String): String =
        when (type) {
            SshKeyType.RSA -> {
                val rsa = publicKey as? RSAPublicKey
                    ?: throw SshKeyException("RSA 公钥参数缺失")
                rsaPublicKeyLine(rsa.modulus, rsa.publicExponent, comment)
            }

            SshKeyType.ED25519 -> {
                val raw = ed25519PublicFromSpki(publicKey.encoded ?: throw SshKeyException("公钥数据为空"))
                ed25519PublicKeyLine(raw, comment)
            }
        }

    /** `ssh-rsa` + Base64( string("ssh-rsa") + mpint(e) + mpint(n) ) + comment。 */
    fun rsaPublicKeyLine(n: BigInteger, e: BigInteger, comment: String): String {
        val payload = concat(
            sshString("ssh-rsa".toByteArray(Charsets.US_ASCII)),
            sshMpint(e),
            sshMpint(n),
        )
        return withComment("ssh-rsa " + b64(payload), comment)
    }

    /** `ssh-ed25519` + Base64( string("ssh-ed25519") + string(32 字节公钥) ) + comment。 */
    fun ed25519PublicKeyLine(raw: ByteArray, comment: String): String {
        if (raw.size != 32) throw SshKeyException("Ed25519 公钥长度异常：${raw.size}")
        val payload = concat(
            sshString("ssh-ed25519".toByteArray(Charsets.US_ASCII)),
            sshString(raw),
        )
        return withComment("ssh-ed25519 " + b64(payload), comment)
    }

    // ------------------------------------------------------------------
    // 导入
    // ------------------------------------------------------------------

    /** 解析 PEM 私钥（可选口令）并推导 OpenSSH 公钥。 */
    fun importPrivateKey(pemText: String, passphrase: String, comment: String): SshPreparedKey {
        ensureProvider()
        val block = parsePem(pemText)
            ?: throw SshKeyException(
                "未找到私钥 PEM 块（-----BEGIN ...-----）。请粘贴私钥文本；" +
                    "若粘贴的是公钥，请改用详情页的「复制公钥」。",
            )
        val label = block.label.uppercase(Locale.ROOT)
        if (label.contains("OPENSSH")) {
            throw SshKeyException(
                "暂不支持 OpenSSH 新格式私钥（BEGIN OPENSSH PRIVATE KEY）。" +
                    "请先转换为 PEM 再导入：ssh-keygen -p -m PEM，" +
                    "或 openssl pkey -in key.pem -out plain.pem。",
            )
        }
        if (label.contains("PUBLIC")) {
            throw SshKeyException("这是公钥而不是私钥。请粘贴私钥文本（-----BEGIN PRIVATE KEY-----）。")
        }
        val der = decodeDer(block, passphrase)
        return importDer(der, comment)
    }

    private fun importDer(der: ByteArray, comment: String): SshPreparedKey {
        val root = parseSequence(der)
        if (root.size() < 2) throw SshKeyException("私钥结构异常（元素不足）")
        val first = root.getObjectAt(0)
        val second = root.getObjectAt(1)
        return when {
            // PKCS#8：SEQ { 版本, AlgorithmIdentifier, OCTET STRING }
            first is ASN1Integer && second is ASN1Sequence -> {
                val oid = (second.getObjectAt(0) as? ASN1ObjectIdentifier)?.id
                    ?: throw SshKeyException("私钥算法标识异常")
                val inner = (root.getObjectAt(2) as? ASN1OctetString)?.octets
                    ?: throw SshKeyException("私钥数据段缺失")
                when (oid) {
                    OID_RSA -> rsaFromPkcs1(inner, comment, der)
                    OID_ED25519 -> ed25519FromSeed(ed25519Seed(inner), comment, der)
                    else -> throw SshKeyException("暂不支持的私钥算法（OID $oid），仅支持 RSA 与 Ed25519。")
                }
            }

            // PKCS#1：SEQ { 版本, n, e, d, p, q, dp, dq, qinv }
            first is ASN1Integer && second is ASN1Integer ->
                rsaFromPkcs1(der, comment, wrapPkcs1ToPkcs8(der))

            else -> throw SshKeyException(
                "无法识别的私钥格式，仅支持 RSA（PKCS#1 / PKCS#8）与 Ed25519 私钥。",
            )
        }
    }

    private fun rsaFromPkcs1(pkcs1: ByteArray, comment: String, pkcs8: ByteArray): SshPreparedKey {
        val root = parseSequence(pkcs1)
        if (root.size() < 9) {
            throw SshKeyException("RSA 私钥缺少 CRT 参数（仅 ${root.size()} 项），无法推导公钥。")
        }
        fun intAt(i: Int): BigInteger =
            (root.getObjectAt(i) as? ASN1Integer)?.value
                ?: throw SshKeyException("RSA 私钥第 ${i + 1} 项不是整数，格式无效。")

        val n = intAt(1)
        val e = intAt(2)
        val d = intAt(3)
        val p = intAt(4)
        val q = intAt(5)
        val dp = intAt(6)
        val dq = intAt(7)
        val qinv = intAt(8)
        try {
            rsaKeyFactory().generatePrivate(RSAPrivateCrtKeySpec(n, e, d, p, q, dp, dq, qinv))
        } catch (t: Throwable) {
            throw SshKeyException("RSA 私钥校验失败：${t.message ?: "数据无效"}")
        }
        val line = rsaPublicKeyLine(n, e, comment)
        return SshPreparedKey(
            type = SshKeyType.RSA,
            bits = n.bitLength(),
            privateKeyPem = pkcs8Pem(pkcs8),
            publicKey = line,
            fingerprint = SshKeyStore.fingerprintOf(line),
        )
    }

    private fun ed25519FromSeed(seed: ByteArray, comment: String, pkcs8: ByteArray): SshPreparedKey {
        if (seed.size != 32) throw SshKeyException("Ed25519 私钥种子长度异常：${seed.size}")
        val raw = try {
            Ed25519PrivateKeyParameters(seed).generatePublicKey().encoded
        } catch (t: Throwable) {
            throw SshKeyException("Ed25519 公钥推导失败：${t.message ?: "密钥无效"}")
        }
        if (raw.size != 32) throw SshKeyException("Ed25519 公钥长度异常：${raw.size}")
        val line = ed25519PublicKeyLine(raw, comment)
        return SshPreparedKey(
            type = SshKeyType.ED25519,
            bits = 256,
            privateKeyPem = pkcs8Pem(pkcs8),
            publicKey = line,
            fingerprint = SshKeyStore.fingerprintOf(line),
        )
    }

    // ------------------------------------------------------------------
    // 提供者 / 生成器
    // ------------------------------------------------------------------

    /**
     * 注册本模块打包的 BouncyCastle。
     * 使用 `insertProviderAt`（同名已存在时仅返回 -1），不会因重复注册抛异常。
     */
    private fun ensureProvider() {
        try {
            Security.insertProviderAt(BouncyCastleProvider(), 1)
        } catch (_: Throwable) {
            // 忽略：系统已内置同名提供者
        }
    }

    private fun keyPairGenerator(algorithm: String): KeyPairGenerator {
        ensureProvider()
        val names = if (algorithm.equals("ed25519", ignoreCase = true)) {
            listOf("Ed25519", "ED25519")
        } else {
            listOf(algorithm)
        }
        var lastError: Throwable? = null
        for (name in names) {
            runCatching { KeyPairGenerator.getInstance(name, BouncyCastleProvider.PROVIDER_NAME) }
                .onSuccess { return it }
                .onFailure { lastError = it }
            // 系统可能预置了同名旧版 BC：顶替为本模块 bcprov 后重试
            runCatching {
                Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
                Security.insertProviderAt(BouncyCastleProvider(), 1)
            }
            runCatching { KeyPairGenerator.getInstance(name, BouncyCastleProvider.PROVIDER_NAME) }
                .onSuccess { return it }
                .onFailure { lastError = it }
            runCatching { KeyPairGenerator.getInstance(name) }
                .onSuccess { return it }
                .onFailure { lastError = it }
        }
        throw SshKeyException("无法创建 $algorithm 密钥生成器：${lastError?.message ?: "未知错误"}")
    }

    private fun rsaKeyFactory(): KeyFactory = try {
        ensureProvider()
        KeyFactory.getInstance("RSA", BouncyCastleProvider.PROVIDER_NAME)
    } catch (t: Throwable) {
        try {
            KeyFactory.getInstance("RSA")
        } catch (t2: Throwable) {
            throw SshKeyException("RSA 引擎不可用：${t2.message ?: t.message ?: "未知错误"}")
        }
    }

    // ------------------------------------------------------------------
    // PEM 编解码
    // ------------------------------------------------------------------

    /** PKCS#8 DER → `BEGIN PRIVATE KEY` PEM（每行 64 字符）。 */
    fun pkcs8Pem(der: ByteArray): String {
        val body = b64(der).chunked(64).joinToString("\n")
        return "-----BEGIN PRIVATE KEY-----\n$body\n-----END PRIVATE KEY-----\n"
    }

    private data class PemBlock(val label: String, val headers: List<String>, val body: ByteArray)

    private fun parsePem(text: String): PemBlock? {
        val regex = Regex(
            "-----BEGIN ([A-Za-z0-9 ]+)-----(.*?)-----END [A-Za-z0-9 ]+-----",
            RegexOption.DOT_MATCHES_ALL,
        )
        val match = regex.find(text) ?: return null
        val label = match.groupValues[1].trim()
        val lines = match.groupValues[2].lines().map { it.trim() }.filter { it.isNotEmpty() }
        val headers = lines.filter { it.contains(':') }
        val bodyText = lines.filter { !it.contains(':') }.joinToString("")
        val body = try {
            Base64.decode(bodyText, Base64.NO_WRAP)
        } catch (t: Throwable) {
            throw SshKeyException("私钥 Base64 解码失败，请确认粘贴内容完整。")
        }
        if (body.isEmpty()) throw SshKeyException("私钥内容为空，请确认粘贴完整。")
        return PemBlock(label, headers, body)
    }

    /** 取出明文 DER（必要时按口令解密）。 */
    private fun decodeDer(block: PemBlock, passphrase: String): ByteArray {
        val legacyEncrypted = block.headers.any {
            it.startsWith("Proc-Type", ignoreCase = true) && it.contains("ENCRYPTED", ignoreCase = true)
        }
        if (legacyEncrypted) return decryptLegacy(block, passphrase)
        if (block.label.equals("ENCRYPTED PRIVATE KEY", ignoreCase = true)) {
            return decryptPkcs8(block, passphrase)
        }
        return block.body
    }

    /** 旧版 OpenSSL 加密 PEM（DEK-Info + EVP_BytesToKey/MD5）。 */
    private fun decryptLegacy(block: PemBlock, passphrase: String): ByteArray {
        if (passphrase.isEmpty()) throw SshKeyException("该私钥已加密，请填写口令。")
        val dek = block.headers.firstOrNull { it.startsWith("DEK-Info", ignoreCase = true) }
            ?: throw SshKeyException("缺少 DEK-Info 头，无法解密该私钥。")
        val segments = dek.substringAfter(':').split(',')
        val cipherName = segments.getOrNull(0)?.trim().orEmpty()
        val ivHex = segments.getOrNull(1)?.trim().orEmpty()
        if (cipherName.isEmpty() || ivHex.isEmpty()) throw SshKeyException("DEK-Info 头格式错误。")
        val iv = hexToBytes(ivHex) ?: throw SshKeyException("DEK-Info 中的 IV 不是有效十六进制。")

        val (transform, keyAlg, keyLen) = when (cipherName.uppercase(Locale.ROOT)) {
            "DES-EDE3-CBC" -> Triple("DESede/CBC/PKCS5Padding", "DESede", 24)
            "AES-128-CBC" -> Triple("AES/CBC/PKCS5Padding", "AES", 16)
            "AES-192-CBC" -> Triple("AES/CBC/PKCS5Padding", "AES", 24)
            "AES-256-CBC" -> Triple("AES/CBC/PKCS5Padding", "AES", 32)
            else -> throw SshKeyException("暂不支持的私钥加密算法：$cipherName")
        }
        val pw = passphrase.toByteArray(Charsets.UTF_8)
        val salt = if (iv.size >= 8) iv.copyOfRange(0, 8) else iv
        // 两种常见派生：IV 前 8 字节作 salt（OpenSSH/OpenSSL PEM 惯例） / 不带 salt
        for (candidate in listOf(salt, null)) {
            val key = evpBytesToKey(pw, candidate, keyLen)
            val plain = runCatching { cbcDecrypt(transform, keyAlg, key, iv, block.body) }
                .getOrNull() ?: continue
            if (plain.isNotEmpty() && plain[0].toInt() == 0x30) return plain
        }
        throw SshKeyException("口令错误，或该私钥的加密方式暂不支持（可先用 openssl 去除口令后重试）。")
    }

    /** PKCS#8 v2 加密私钥（PBES2 + PBKDF2）。 */
    private fun decryptPkcs8(block: PemBlock, passphrase: String): ByteArray {
        if (passphrase.isEmpty()) throw SshKeyException("该私钥已加密，请填写口令。")
        val outer = parseSequence(block.body)
        if (outer.size() < 2) throw SshKeyException("加密私钥结构异常。")
        val schemeOid = (outer.getObjectAt(0) as? ASN1ObjectIdentifier)?.id
            ?: throw SshKeyException("私钥加密方案标识异常。")
        if (schemeOid != OID_PBES2) throw SshKeyException("暂不支持的私钥加密方案（$schemeOid）。")
        val params = outer.getObjectAt(1) as? ASN1Sequence
            ?: throw SshKeyException("私钥加密参数缺失。")
        if (params.size() < 2) throw SshKeyException("私钥加密参数不完整。")

        val kdfAlg = params.getObjectAt(0) as? ASN1Sequence
            ?: throw SshKeyException("密钥派生参数缺失。")
        val kdfOid = (kdfAlg.getObjectAt(0) as? ASN1ObjectIdentifier)?.id
            ?: throw SshKeyException("密钥派生算法标识异常。")
        if (kdfOid != OID_PBKDF2) throw SshKeyException("暂不支持的密钥派生算法（$kdfOid）。")
        val kdfParams = kdfAlg.getObjectAt(1) as? ASN1Sequence
            ?: throw SshKeyException("PBKDF2 参数缺失。")
        if (kdfParams.size() < 2) throw SshKeyException("PBKDF2 参数不完整。")
        val salt = (kdfParams.getObjectAt(0) as? ASN1OctetString)?.octets
            ?: throw SshKeyException("PBKDF2 salt 缺失。")
        val iterations = (kdfParams.getObjectAt(1) as? ASN1Integer)?.value?.toInt()
            ?: throw SshKeyException("PBKDF2 迭代次数缺失。")
        if (iterations !in 1..10_000_000) throw SshKeyException("PBKDF2 迭代次数异常。")

        var keyLen = 32
        var prfOid = "1.2.840.113549.2.7"
        for (i in 2 until kdfParams.size()) {
            when (val element = kdfParams.getObjectAt(i)) {
                is ASN1Integer -> keyLen = element.value.toInt()
                is ASN1Sequence -> prfOid = (element.getObjectAt(0) as? ASN1ObjectIdentifier)?.id ?: prfOid
                else -> Unit
            }
        }

        val encScheme = params.getObjectAt(1) as? ASN1Sequence
            ?: throw SshKeyException("私钥加密算法参数缺失。")
        val encOid = (encScheme.getObjectAt(0) as? ASN1ObjectIdentifier)?.id
            ?: throw SshKeyException("私钥加密算法标识异常。")
        val (transform, keyAlg) = when (encOid) {
            "2.16.840.1.101.3.4.1.2",
            "2.16.840.1.101.3.4.1.22",
            "2.16.840.1.101.3.4.1.42",
            -> "AES/CBC/PKCS5Padding" to "AES"

            "1.2.840.113549.3.7" -> "DESede/CBC/PKCS5Padding" to "DESede"
            else -> throw SshKeyException("暂不支持的私钥加密算法（$encOid）。")
        }
        val iv = (encScheme.getObjectAt(1) as? ASN1OctetString)?.octets
            ?: throw SshKeyException("私钥加密 IV 缺失。")
        val kdfName = when (prfOid) {
            "1.2.840.113549.2.7" -> "PBKDF2WithHmacSHA1"
            "1.2.840.113549.2.9",
            "2.16.840.1.101.3.4.2.1",
            -> "PBKDF2WithHmacSHA256"

            else -> throw SshKeyException("暂不支持的 PRF 算法（$prfOid）。")
        }

        val key = try {
            val factory = SecretKeyFactory.getInstance(kdfName)
            val spec = PBEKeySpec(passphrase.toCharArray(), salt, iterations, keyLen * 8)
            try {
                factory.generateSecret(spec).encoded
            } finally {
                spec.clearPassword()
            }
        } catch (t: Throwable) {
            throw SshKeyException("PBKDF2 派生失败：${t.message ?: "未知错误"}")
        }
        val plain = try {
            cbcDecrypt(transform, keyAlg, key, iv, block.body)
        } catch (t: Throwable) {
            throw SshKeyException("口令错误或解密失败：${t.message ?: "未知错误"}")
        }
        if (plain.isEmpty() || plain[0].toInt() != 0x30) {
            throw SshKeyException("口令错误：解密结果不是有效的私钥数据。")
        }
        return plain
    }

    private fun cbcDecrypt(transform: String, keyAlg: String, key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(transform)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, keyAlg), IvParameterSpec(iv))
        return cipher.doFinal(data)
    }

    /** OpenSSL EVP_BytesToKey（MD5，单轮）。 */
    private fun evpBytesToKey(password: ByteArray, salt: ByteArray?, need: Int): ByteArray {
        val out = ByteArrayOutputStream()
        var prev = ByteArray(0)
        while (out.size() < need) {
            val md = MessageDigest.getInstance("MD5")
            md.update(prev)
            md.update(password)
            if (salt != null) md.update(salt)
            prev = md.digest()
            out.write(prev)
        }
        return out.toByteArray().copyOf(need)
    }

    private fun hexToBytes(text: String): ByteArray? {
        val s = text.trim()
        if (s.isEmpty() || s.length % 2 != 0) return null
        val out = ByteArray(s.length / 2)
        for (i in out.indices) {
            val hi = s[i * 2].digitToIntOrNull(16) ?: return null
            val lo = s[i * 2 + 1].digitToIntOrNull(16) ?: return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    // ------------------------------------------------------------------
    // DER / wire format 工具
    // ------------------------------------------------------------------

    private fun parseSequence(der: ByteArray): ASN1Sequence = try {
        ASN1Sequence.getInstance(ASN1Primitive.fromByteArray(der))
            ?: throw SshKeyException("私钥 DER 解析失败：内容为空。")
    } catch (t: SshKeyException) {
        throw t
    } catch (t: Throwable) {
        throw SshKeyException("私钥 DER 解析失败：${t.message ?: "格式无效"}。")
    }

    /** Ed25519：PKCS#8 privateKey OCTET STRING 内再嵌套一层 OCTET STRING。 */
    private fun ed25519Seed(inner: ByteArray): ByteArray {
        if (inner.size == 32) return inner
        if (inner.size > 2 && inner[0].toInt() == 0x04) {
            val (len, start) = derLength(inner, 1)
            if (len == 32 && start + len == inner.size) return inner.copyOfRange(start, start + len)
        }
        throw SshKeyException("Ed25519 私钥结构异常（长度 ${inner.size}）。")
    }

    /** X.509 SPKI → Ed25519 32 字节公钥（手工解析，避免依赖 ASN.1 位串细节）。 */
    private fun ed25519PublicFromSpki(spki: ByteArray): ByteArray {
        if (spki.isEmpty() || spki[0].toInt() != 0x30) throw SshKeyException("公钥 DER 格式无效。")
        val (_, seqStart) = derLength(spki, 1)
        if (seqStart >= spki.size || spki[seqStart].toInt() != 0x30) {
            throw SshKeyException("公钥算法标识无效。")
        }
        val (algLen, algStart) = derLength(spki, seqStart + 1)
        val bitPos = algStart + algLen
        if (bitPos >= spki.size || spki[bitPos].toInt() != 0x03) {
            throw SshKeyException("公钥数据段无效。")
        }
        val (bitLen, bitStart) = derLength(spki, bitPos + 1)
        if (bitLen < 2 || bitStart + bitLen > spki.size) throw SshKeyException("公钥数据被截断。")
        if (spki[bitStart].toInt() != 0) throw SshKeyException("公钥数据对齐异常。")
        val raw = spki.copyOfRange(bitStart + 1, bitStart + bitLen)
        if (raw.size != 32) throw SshKeyException("Ed25519 公钥长度异常：${raw.size}")
        return raw
    }

    private fun derLength(buf: ByteArray, pos: Int): Pair<Int, Int> {
        if (pos >= buf.size) throw SshKeyException("DER 数据被截断。")
        val first = buf[pos].toInt() and 0xFF
        if (first < 0x80) return first to (pos + 1)
        val count = first and 0x7F
        if (count == 0 || count > 4 || pos + 1 + count > buf.size) throw SshKeyException("DER 长度字段无效。")
        var len = 0
        for (i in 0 until count) {
            len = (len shl 8) or (buf[pos + 1 + i].toInt() and 0xFF)
        }
        return len to (pos + 1 + count)
    }

    /** PKCS#1 → PKCS#8（补齐 rsaEncryption 算法标识）。 */
    private fun wrapPkcs1ToPkcs8(pkcs1: ByteArray): ByteArray {
        val algId = byteArrayOf(
            0x30, 0x0D, 0x06, 0x09, 0x2A, 0x86.toByte(), 0x48, 0x86.toByte(),
            0xF7.toByte(), 0x0D, 0x01, 0x01, 0x01, 0x05, 0x00,
        )
        val inner = concat(
            byteArrayOf(0x02, 0x01, 0x00),
            algId,
            octetString(pkcs1),
        )
        return concat(byteArrayOf(0x30.toByte()), derLengthBytes(inner.size), inner)
    }

    private fun octetString(data: ByteArray): ByteArray =
        concat(byteArrayOf(0x04), derLengthBytes(data.size), data)

    private fun derLengthBytes(len: Int): ByteArray = when {
        len < 0x80 -> byteArrayOf(len.toByte())
        len < 0x100 -> byteArrayOf(0x81.toByte(), len.toByte())
        len < 0x10000 -> byteArrayOf(0x82.toByte(), (len shr 8).toByte(), len.toByte())
        len < 0x1000000 -> byteArrayOf(
            0x83.toByte(), (len shr 16).toByte(), (len shr 8).toByte(), len.toByte(),
        )

        else -> throw SshKeyException("密钥长度超出支持范围。")
    }

    private fun sshString(data: ByteArray): ByteArray {
        val out = ByteArray(4 + data.size)
        out[0] = (data.size ushr 24).toByte()
        out[1] = (data.size ushr 16).toByte()
        out[2] = (data.size ushr 8).toByte()
        out[3] = data.size.toByte()
        System.arraycopy(data, 0, out, 4, data.size)
        return out
    }

    private fun sshMpint(value: BigInteger): ByteArray = sshString(value.toByteArray())

    private fun concat(vararg parts: ByteArray): ByteArray {
        val total = parts.sumOf { it.size }
        val out = ByteArray(total)
        var offset = 0
        for (part in parts) {
            System.arraycopy(part, 0, out, offset, part.size)
            offset += part.size
        }
        return out
    }

    private fun withComment(line: String, comment: String): String {
        val trimmed = comment.trim()
        return if (trimmed.isEmpty()) line else "$line $trimmed"
    }

    private fun b64(data: ByteArray): String = Base64.encodeToString(data, Base64.NO_WRAP)
}
