package com.mobilecoder.ide.core.storage

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.security.KeyStore
import kotlinx.coroutines.flow.first
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 本地对称加密（AES-256/GCM），用于 SSH 私钥与 HTTPS 凭据的静态加密（PRD 3.1：本地加密、不上云）。
 *
 * 首选 Android Keystore（密钥不可导出）；Keystore 不可用时回退到随机密钥 + DataStore 落盘。
 * 加解密均为 suspend，内部保证密钥已初始化。
 */
class CryptoBox(private val dataStore: androidx.datastore.core.DataStore<Preferences>) {

    @Volatile
    private var key: SecretKey? = null

    /** 预热（App 启动时调用一次，失败不阻塞）。 */
    suspend fun ensureKey() {
        resolveKey()
    }

    /** 加密：返回 `0x01 | iv(12) | ciphertext`。 */
    suspend fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, resolveKey())
        val iv = cipher.iv
        val body = cipher.doFinal(plain)
        return ByteArray(1 + iv.size + body.size).also { out ->
            out[0] = 1
            iv.copyInto(out, 1)
            body.copyInto(out, 1 + iv.size)
        }
    }

    /** 解密 [encrypt] 的产物。 */
    suspend fun decrypt(blob: ByteArray): ByteArray {
        require(blob.size > 13 && blob[0] == V1) { "密文格式不合法" }
        val iv = blob.copyOfRange(1, 13)
        val body = blob.copyOfRange(13, blob.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, resolveKey(), GCMParameterSpec(128, iv))
        return cipher.doFinal(body)
    }

    suspend fun encryptToString(plain: String): String =
        Base64.encodeToString(encrypt(plain.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)

    suspend fun decryptToString(value: String): String =
        String(Base64.decode(value, Base64.NO_WRAP).let { decrypt(it) }, Charsets.UTF_8)

    // ------------------------------------------------------------------

    private suspend fun resolveKey(): SecretKey {
        key?.let { return it }
        val resolved = keystoreKey() ?: fallbackKey()
        key = resolved
        return resolved
    }

    private fun keystoreKey(): SecretKey? = try {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(ALIAS, null) as? SecretKey) ?: generateKeystoreKey()
    } catch (_: Throwable) {
        null
    }

    private fun generateKeystoreKey(): SecretKey? = try {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        generator.generateKey()
    } catch (_: Throwable) {
        null
    }

    private suspend fun fallbackKey(): SecretKey {
        dataStore.data.first()[FALLBACK_KEY]?.let { stored ->
            return SecretKeySpec(Base64.decode(stored, Base64.NO_WRAP), "AES")
        }
        val generator = KeyGenerator.getInstance("AES")
        generator.init(256)
        val generated = generator.generateKey()
        val encoded = Base64.encodeToString(generated.encoded, Base64.NO_WRAP)
        dataStore.edit { it[FALLBACK_KEY] = encoded }
        return SecretKeySpec(generated.encoded, "AES")
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "mobilecoder_master_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val V1: Byte = 1
        private val FALLBACK_KEY = stringPreferencesKey("crypto_fallback_key")
    }
}

private suspend fun <T> androidx.datastore.core.DataStore<Preferences>.firstOrNull(): T? =
    @Suppress("UNCHECKED_CAST")
    (data.first() as T)
