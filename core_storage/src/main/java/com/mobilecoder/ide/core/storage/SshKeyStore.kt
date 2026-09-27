package com.mobilecoder.ide.core.storage

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject

/** 密钥类型（PRD 2.6：RSA / ED25519）。 */
enum class SshKeyType(val label: String, val defaultBits: Int) {
    RSA("RSA", 4096),
    ED25519("Ed25519", 256),
    ;

    companion object {
        fun fromName(name: String?): SshKeyType =
            entries.firstOrNull { it.name.equals(name, true) } ?: RSA
    }
}

/** 密钥元信息（不含私钥）。 */
data class SshKeyMeta(
    val id: String,
    val name: String,
    val type: SshKeyType,
    val bits: Int,
    /** 公钥注释（OpenSSH 行尾 comment）。 */
    val comment: String,
    /** 该密钥对应的 SSH 用户名（GitHub 固定为 git）。 */
    val username: String,
    /** OpenSSH 单行公钥：`ssh-rsa AAAA... comment`。 */
    val publicKey: String,
    /** OpenSSH 风格指纹 `SHA256:...`。 */
    val fingerprint: String,
    val createdAt: Long,
    val hasPassphrase: Boolean,
    /** 加密后的口令（AES-GCM Base64，空串表示无口令）。 */
    val passphraseBlob: String = "",
) {
    /** 是否为当前激活密钥（Git / 连接测试默认使用）。 */
    var isActive: Boolean = false
        internal set
}

/** 解密后的密钥材料（仅内存持有，不写日志）。 */
data class SshKeyMaterial(
    val meta: SshKeyMeta,
    val privateKeyPem: String,
    val passphrase: String,
    val publicKey: String,
    val username: String,
)

/**
 * SSH 密钥库（PRD 2.6）：
 *  - 私钥 AES-GCM 加密后存入 `files/ssh_keys/<id>.key`，**不上传、不落明文**
 *  - 口令同样加密后随元信息保存（便于 Git 免密自动匹配）
 *  - 支持多密钥、激活密钥切换（多账号）
 */
class SshKeyStore(
    private val paths: StoragePaths,
    private val dataStore: DataStore<Preferences>,
    private val crypto: CryptoBox,
) {

    private val _keys = MutableStateFlow<List<SshKeyMeta>>(emptyList())
    val keys: StateFlow<List<SshKeyMeta>> = _keys.asStateFlow()

    suspend fun refresh() {
        _keys.value = loadAll()
    }

    suspend fun activeId(): String = dataStore.data.first()[KEY_ACTIVE] ?: ""

    suspend fun setSshUsername(id: String, username: String) = update(id) {
        it.copy(username = username.ifBlank { "git" })
    }

    suspend fun setActive(id: String) {
        dataStore.edit { it[KEY_ACTIVE] = id }
        refresh()
    }

    /** 写入（或覆盖）一把密钥：私钥加密落盘，元信息进 DataStore。 */
    suspend fun save(
        meta: SshKeyMeta,
        privateKeyPem: String,
        passphrase: String = "",
    ) {
        paths.sshKeys.mkdirs()
        val blob = crypto.encrypt(privateKeyPem.toByteArray(Charsets.UTF_8))
        File(paths.sshKeys, "${meta.id}.key").writeBytes(blob)

        val all = loadAll().filterNot { it.id == meta.id } + meta
        persist(all)
        if (activeId().isBlank() || activeId() == meta.id) {
            dataStore.edit { it[KEY_ACTIVE] = meta.id }
        }
        refresh()
    }

    suspend fun delete(id: String) {
        File(paths.sshKeys, "$id.key").delete()
        persist(loadAll().filterNot { it.id == id })
        if (activeId() == id) {
            dataStore.edit { it[KEY_ACTIVE] = loadAll().firstOrNull()?.id ?: "" }
        }
        refresh()
    }

    /** 取出解密后的密钥材料（Git 凭据 / 连接测试使用）。 */
    suspend fun material(id: String): SshKeyMaterial? {
        val meta = loadAll().firstOrNull { it.id == id } ?: return null
        val file = File(paths.sshKeys, "$id.key")
        if (!file.exists()) return null
        val pem = runCatching {
            String(crypto.decrypt(file.readBytes()), Charsets.UTF_8)
        }.getOrNull() ?: return null
        val passphrase = if (meta.hasPassphrase) {
            runCatching { crypto.decryptToString(meta.passphraseBlob) }.getOrDefault("")
        } else ""
        return SshKeyMaterial(
            meta = meta,
            privateKeyPem = pem,
            passphrase = passphrase,
            publicKey = meta.publicKey,
            username = meta.username.ifBlank { "git" },
        )
    }

    suspend fun activeMaterial(): SshKeyMaterial? = material(activeId())

    suspend fun passphraseOf(id: String): String {
        val meta = loadAll().firstOrNull { it.id == id } ?: return ""
        if (!meta.hasPassphrase) return ""
        return runCatching { crypto.decryptToString(meta.passphraseBlob) }.getOrDefault("")
    }

    suspend fun setPassphrase(id: String, passphrase: String) = update(id) {
        it.copy(
            hasPassphrase = passphrase.isNotEmpty(),
            passphraseBlob = if (passphrase.isEmpty()) "" else crypto.encryptToString(passphrase),
        )
    }

    // ---------------- 内部 ----------------

    private suspend fun update(id: String, transform: suspend (SshKeyMeta) -> SshKeyMeta) {
        val all = loadAll()
        val next = all.map { if (it.id == id) transform(it) else it }
        persist(next)
        refresh()
    }

    private suspend fun loadAll(): List<SshKeyMeta> {
        val active = activeId()
        val raw = dataStore.data.first()[KEY_KEYS] ?: return emptyList()
        val parsed = runCatching { parse(raw, active) }.getOrDefault(emptyList())
        return parsed
    }

    private suspend fun persist(metas: List<SshKeyMeta>) {
        val array = JSONArray()
        metas.forEach { meta ->
            array.put(
                JSONObject()
                    .put("id", meta.id)
                    .put("name", meta.name)
                    .put("type", meta.type.name)
                    .put("bits", meta.bits)
                    .put("comment", meta.comment)
                    .put("username", meta.username)
                    .put("publicKey", meta.publicKey)
                    .put("fingerprint", meta.fingerprint)
                    .put("createdAt", meta.createdAt)
                    .put("hasPassphrase", meta.hasPassphrase)
                    .put("passphraseBlob", meta.passphraseBlob),
            )
        }
        dataStore.edit { it[KEY_KEYS] = array.toString() }
    }

    private fun parse(raw: String, activeId: String): List<SshKeyMeta> {
        val array = JSONArray(raw)
        return buildList {
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val id = obj.getString("id")
                add(
                    SshKeyMeta(
                        id = id,
                        name = obj.getString("name"),
                        type = SshKeyType.fromName(obj.getString("type")),
                        bits = obj.optInt("bits", 4096),
                        comment = obj.optString("comment"),
                        username = obj.optString("username", "git"),
                        publicKey = obj.optString("publicKey"),
                        fingerprint = obj.optString("fingerprint"),
                        createdAt = obj.optLong("createdAt"),
                        hasPassphrase = obj.optBoolean("hasPassphrase"),
                        passphraseBlob = obj.optString("passphraseBlob"),
                    ).apply { isActive = id == activeId },
                )
            }
        }
    }

    companion object {
        private val KEY_KEYS = stringPreferencesKey("ssh_keys_index")
        private val KEY_ACTIVE = stringPreferencesKey("ssh_active_key")

        fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(16)

        /**
         * OpenSSH 指纹：对公钥 base64 blob 做 SHA-256 再 base64（去掉 padding）。
         * `SHA256:xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx`
         */
        fun fingerprintOf(openSshPublicKey: String): String {
            val parts = openSshPublicKey.trim().split(Regex("\\s+"))
            if (parts.size < 2) return ""
            return runCatching {
                val blob = android.util.Base64.decode(parts[1], android.util.Base64.DEFAULT)
                val digest = MessageDigest.getInstance("SHA-256").digest(blob)
                "SHA256:" + android.util.Base64.encodeToString(
                    digest,
                    android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING,
                )
            }.getOrDefault("")
        }
    }
}
