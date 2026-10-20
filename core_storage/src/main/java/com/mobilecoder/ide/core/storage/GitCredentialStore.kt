package com.mobilecoder.ide.core.storage

import org.json.JSONArray
import org.json.JSONObject

/**
 * 凭据库里的一条记录（不含密码，仅用于列表展示）。
 *
 * @param origin `协议://主机[:端口]`（小写）；同一主机的所有仓库共用这一条。
 */
data class GitCredentialEntry(
    val origin: String,
    val username: String,
    /** 最近一次保存时间（Unix 毫秒）。 */
    val updatedAt: Long,
)

/**
 * HTTP(S) 服务器登录凭据库（按 origin 存，密码用 [CryptoBox] 加密）。
 *
 * 写入的两个入口：
 *  1. 地址里内嵌 `协议://用户名:密码@主机/仓库.git` —— 克隆前由 GitController
 *     用 [GitUrlCreds] 摘出来存好，并把干净地址交给 libgit2（密码不进 `.git/config`）；
 *  2. 服务器要求登录（401）时弹窗输入，保存后自动重试。
 *
 * 读取：每次网络操作前按 origin 取出，注入 libgit2 的凭据回调。
 * 管理：Git 页 →「仓库设置」→ 服务器凭据（单条删除 / 全部清除）。
 */
class GitCredentialStore(
    private val preferences: AppPreferences,
    private val crypto: CryptoBox,
) {

    /** 全部凭据（按保存时间倒序）。 */
    suspend fun list(): List<GitCredentialEntry> =
        readAll()
            .sortedByDescending { it.optLong(KEY_TIME) }
            .mapNotNull { item ->
                val origin = item.optString(KEY_ORIGIN)
                if (origin.isEmpty()) null else GitCredentialEntry(
                    origin = origin,
                    username = item.optString(KEY_USER),
                    updatedAt = item.optLong(KEY_TIME),
                )
            }

    /** 取 [url] 所属 origin 的 (用户名, 密码)；没有记录或密码解不开返回 null。 */
    suspend fun credentialsFor(url: String): Pair<String, String>? {
        val origin = GitUrlCreds.originOf(url)
        if (origin.isEmpty()) return null
        val item = readAll().firstOrNull { it.optString(KEY_ORIGIN) == origin } ?: return null
        val encoded = item.optString(KEY_PASS)
        if (encoded.isEmpty()) return null
        val password = runCatching { crypto.decryptToString(encoded) }.getOrDefault("")
        return item.optString(KEY_USER) to password
    }

    /** 保存（同 origin 覆盖旧记录）。@return 保存后的条目；origin 解析不出来返回 null */
    suspend fun save(url: String, username: String, password: String): GitCredentialEntry? {
        val origin = GitUrlCreds.originOf(url)
        if (origin.isEmpty()) return null
        val now = System.currentTimeMillis()
        val items = readAll().filterNot { it.optString(KEY_ORIGIN) == origin }.toMutableList()
        items.add(
            JSONObject().apply {
                put(KEY_ORIGIN, origin)
                put(KEY_USER, username)
                put(KEY_PASS, runCatching { crypto.encryptToString(password) }.getOrDefault(""))
                put(KEY_TIME, now)
            },
        )
        writeAll(items)
        return GitCredentialEntry(origin, username, now)
    }

    /** 删除一条凭据。@return 是否确实删掉 */
    suspend fun remove(origin: String): Boolean {
        val items = readAll()
        val kept = items.filterNot { it.optString(KEY_ORIGIN) == origin }
        if (kept.size == items.size) return false
        writeAll(kept)
        return true
    }

    /** 清空全部凭据。 */
    suspend fun clear() {
        writeAll(emptyList())
    }

    // ---------------- 内部 ----------------

    private suspend fun readAll(): List<JSONObject> {
        val raw = runCatching { preferences.gitCredentialsJson() }.getOrDefault("")
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    array.optJSONObject(i)?.let { add(it) }
                }
            }
        }.getOrDefault(emptyList())
    }

    private suspend fun writeAll(items: List<JSONObject>) {
        val array = JSONArray()
        items.forEach { array.put(it) }
        runCatching { preferences.setGitCredentialsJson(array.toString()) }
    }

    private companion object {
        const val KEY_ORIGIN = "origin"
        const val KEY_USER = "user"
        const val KEY_PASS = "pass"
        const val KEY_TIME = "time"
    }
}
