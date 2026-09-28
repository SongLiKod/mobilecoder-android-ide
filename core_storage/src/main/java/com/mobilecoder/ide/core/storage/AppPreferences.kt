package com.mobilecoder.ide.core.storage

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray

/**
 * 全局配置（编辑器/终端/Git/CLI/构建等模块的用户设置）。
 * 所有方法均为 suspend，读写走同一个 DataStore 单例，天然原子。
 */
class AppPreferences(
    private val dataStore: DataStore<Preferences>,
    private val crypto: CryptoBox,
) {

    /** 任何配置变更流（用于 UI 订阅整体变化）。 */
    val changes: Flow<Preferences> = dataStore.data

    // ---------------- 项目 ----------------

    suspend fun lastProjectPath(): String = get(KEY_LAST_PROJECT) ?: ""
    suspend fun setLastProjectPath(path: String) = set(KEY_LAST_PROJECT, path)

    // ---------------- Git 身份 ----------------

    suspend fun gitUserName(): String = get(KEY_GIT_NAME) ?: "MobileCoder"
    suspend fun setGitUserName(value: String) = set(KEY_GIT_NAME, value)

    suspend fun gitUserEmail(): String = get(KEY_GIT_EMAIL) ?: "mobilecoder@local"
    suspend fun setGitUserEmail(value: String) = set(KEY_GIT_EMAIL, value)

    // ---------------- HTTPS 凭据（加密存储） ----------------

    suspend fun httpsUser(): String = get(KEY_HTTPS_USER) ?: ""
    suspend fun setHttpsUser(value: String) = set(KEY_HTTPS_USER, value)

    suspend fun httpsPassword(): String = decryptOrEmpty(get(KEY_HTTPS_PASS))
    suspend fun setHttpsPassword(value: String) =
        set(KEY_HTTPS_PASS, if (value.isEmpty()) "" else crypto.encryptToString(value))

    // ---------------- 编辑器 ----------------

    suspend fun editorFontSize(): Int = getInt(KEY_EDITOR_FONT, 14)
    suspend fun setEditorFontSize(value: Int) = setInt(KEY_EDITOR_FONT, value.coerceIn(10, 28))

    suspend fun editorLineNumbers(): Boolean = get(KEY_EDITOR_LINENUM) != "0"
    suspend fun setEditorLineNumbers(value: Boolean) = set(KEY_EDITOR_LINENUM, if (value) "1" else "0")

    suspend fun editorWordWrap(): Boolean = get(KEY_EDITOR_WRAP) == "1"
    suspend fun setEditorWordWrap(value: Boolean) = set(KEY_EDITOR_WRAP, if (value) "1" else "0")

    suspend fun editorAutoSave(): Boolean = get(KEY_EDITOR_AUTOSAVE) != "0"
    suspend fun setEditorAutoSave(value: Boolean) = set(KEY_EDITOR_AUTOSAVE, if (value) "1" else "0")

    // ---------------- 终端 ----------------

    suspend fun terminalFontSize(): Int = getInt(KEY_TERM_FONT, 13)
    suspend fun setTerminalFontSize(value: Int) = setInt(KEY_TERM_FONT, value.coerceIn(9, 24))

    suspend fun terminalHistory(): List<String> = jsonArray(KEY_TERM_HISTORY)
    suspend fun addTerminalHistory(command: String, limit: Int = 200) {
        if (command.isBlank()) return
        val current = jsonArray(KEY_TERM_HISTORY).toMutableList()
        current.remove(command)
        current.add(0, command)
        set(KEY_TERM_HISTORY, JSONArray(current.take(limit)).toString())
    }

    // ---------------- CLI ----------------

    suspend fun cliHistory(): List<String> = jsonArray(KEY_CLI_HISTORY)
    suspend fun addCliHistory(line: String, limit: Int = 100) {
        if (line.isBlank()) return
        val current = jsonArray(KEY_CLI_HISTORY).toMutableList()
        current.remove(line)
        current.add(0, line)
        set(KEY_CLI_HISTORY, JSONArray(current.take(limit)).toString())
    }

    // ---------------- 构建 ----------------

    suspend fun buildVariant(): String = get(KEY_BUILD_VARIANT) ?: "debug"
    suspend fun setBuildVariant(value: String) = set(KEY_BUILD_VARIANT, value)

    /** 构建内存上限（MB）：看门狗阈值 + Gradle 堆（-Xmx）。范围 512–8192，默认 2048。 */
    suspend fun buildMemoryLimitMb(): Int = getInt(KEY_BUILD_MEM, 2048)
    suspend fun setBuildMemoryLimitMb(value: Int) = setInt(KEY_BUILD_MEM, value.coerceIn(512, 8192))

    /** 构建环境在线下载源：official（官方）/ mirror（国内镜像）。 */
    suspend fun envDownloadSource(): String = get(KEY_ENV_SOURCE) ?: "official"
    suspend fun setEnvDownloadSource(value: String) = set(KEY_ENV_SOURCE, value)

    suspend fun jdkPath(): String = get(KEY_JDK_PATH) ?: ""
    suspend fun setJdkPath(value: String) = set(KEY_JDK_PATH, value)

    suspend fun gradlePath(): String = get(KEY_GRADLE_PATH) ?: ""
    suspend fun setGradlePath(value: String) = set(KEY_GRADLE_PATH, value)

    // ---------------- AI 助手（OpenAI 兼容接口：/chat/completions） ----------------

    suspend fun aiBaseUrl(): String = get(KEY_AI_BASE_URL) ?: "https://api.openai.com/v1"
    suspend fun setAiBaseUrl(value: String) = set(KEY_AI_BASE_URL, value.trim())

    suspend fun aiModel(): String = get(KEY_AI_MODEL) ?: "gpt-4o-mini"
    suspend fun setAiModel(value: String) = set(KEY_AI_MODEL, value.trim())

    /** API Key（加密存储，与 HTTPS 凭据同一把应用级密钥）。 */
    suspend fun aiApiKey(): String = decryptOrEmpty(get(KEY_AI_KEY))
    suspend fun setAiApiKey(value: String) =
        set(KEY_AI_KEY, if (value.isEmpty()) "" else crypto.encryptToString(value))

    // ---------------- 内部 ----------------

    private suspend fun get(key: Preferences.Key<String>): String? = dataStore.data.first()[key]

    private suspend fun set(key: Preferences.Key<String>, value: String) {
        dataStore.edit { it[key] = value }
    }

    private suspend fun getInt(key: Preferences.Key<Int>, default: Int): Int =
        dataStore.data.first()[key] ?: default

    private suspend fun setInt(key: Preferences.Key<Int>, value: Int) {
        dataStore.edit { it[key] = value }
    }

    private suspend fun jsonArray(key: Preferences.Key<String>): List<String> {
        val raw = get(key) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList { for (i in 0 until array.length()) add(array.getString(i)) }
        }.getOrDefault(emptyList())
    }

    private suspend fun decryptOrEmpty(encoded: String?): String {
        if (encoded.isNullOrEmpty()) return ""
        return runCatching { crypto.decryptToString(encoded) }.getOrDefault("")
    }

    companion object {
        private val KEY_LAST_PROJECT = stringPreferencesKey("last_project_path")
        private val KEY_GIT_NAME = stringPreferencesKey("git_user_name")
        private val KEY_GIT_EMAIL = stringPreferencesKey("git_user_email")
        private val KEY_HTTPS_USER = stringPreferencesKey("https_user")
        private val KEY_HTTPS_PASS = stringPreferencesKey("https_pass_enc")
        private val KEY_EDITOR_FONT = intPreferencesKey("editor_font_size")
        private val KEY_EDITOR_LINENUM = stringPreferencesKey("editor_line_numbers")
        private val KEY_EDITOR_WRAP = stringPreferencesKey("editor_word_wrap")
        private val KEY_EDITOR_AUTOSAVE = stringPreferencesKey("editor_autosave")
        private val KEY_TERM_FONT = intPreferencesKey("terminal_font_size")
        private val KEY_TERM_HISTORY = stringPreferencesKey("terminal_history")
        private val KEY_CLI_HISTORY = stringPreferencesKey("cli_history")
        private val KEY_BUILD_VARIANT = stringPreferencesKey("build_variant")
        private val KEY_BUILD_MEM = intPreferencesKey("build_memory_limit_mb")
        private val KEY_ENV_SOURCE = stringPreferencesKey("env_download_source")
        private val KEY_JDK_PATH = stringPreferencesKey("jdk_path")
        private val KEY_GRADLE_PATH = stringPreferencesKey("gradle_path")
        private val KEY_AI_BASE_URL = stringPreferencesKey("ai_base_url")
        private val KEY_AI_MODEL = stringPreferencesKey("ai_model")
        private val KEY_AI_KEY = stringPreferencesKey("ai_api_key_enc")
    }
}
