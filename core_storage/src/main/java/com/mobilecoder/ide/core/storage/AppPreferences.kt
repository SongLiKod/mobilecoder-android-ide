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
import org.json.JSONObject

/**
 * 全局配置（编辑器/终端/Git/历史记录/构建等模块的用户设置）。
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

    /** 文件树是否显示以 `.` 开头的文件 / 目录（.gitignore、.github、.env …），默认显示。 */
    suspend fun editorShowHiddenFiles(): Boolean = get(KEY_EDITOR_SHOW_HIDDEN) != "0"
    suspend fun setEditorShowHiddenFiles(value: Boolean) =
        set(KEY_EDITOR_SHOW_HIDDEN, if (value) "1" else "0")

    /**
     * 「不显示」的文件 / 目录名（按名称匹配任意层级，默认构建产物三件套）。
     * 键不存在 = 用默认值；用户清空后写入 `[]`，读出空列表（= 全部显示）。
     */
    suspend fun editorHiddenNames(): List<String> {
        val raw = get(KEY_EDITOR_HIDDEN_NAMES) ?: return DEFAULT_HIDDEN_NAMES
        return runCatching {
            val array = JSONArray(raw)
            buildList { for (i in 0 until array.length()) add(array.getString(i)) }
        }.getOrDefault(DEFAULT_HIDDEN_NAMES)
    }

    suspend fun setEditorHiddenNames(names: List<String>) {
        val array = JSONArray()
        names.forEach { array.put(it) }
        set(KEY_EDITOR_HIDDEN_NAMES, array.toString())
    }

    /**
     * 「不支持在编辑器里打开」的扩展名（小写、不带点，默认 [DEFAULT_UNOPENABLE_EXTS]）。
     * 键不存在 = 用默认值；用户清空后写入 `[]`，读出空列表（= 全部尝试打开，
     * 仍受大小 / 二进制嗅探兜底）。
     */
    suspend fun editorUnopenableExts(): List<String> {
        val raw = get(KEY_EDITOR_UNOPENABLE_EXTS) ?: return DEFAULT_UNOPENABLE_EXTS
        return runCatching {
            val array = JSONArray(raw)
            buildList { for (i in 0 until array.length()) add(array.getString(i)) }
        }.getOrDefault(DEFAULT_UNOPENABLE_EXTS)
    }

    suspend fun setEditorUnopenableExts(exts: List<String>) {
        val array = JSONArray()
        exts.forEach { array.put(it) }
        set(KEY_EDITOR_UNOPENABLE_EXTS, array.toString())
    }

    /** 单个文件可打开的大小上限（MB），默认 10，范围 1–1024。超过即提示不支持预览/编辑。 */
    suspend fun editorMaxOpenMb(): Int = getInt(KEY_EDITOR_MAX_OPEN_MB, 10)
    suspend fun setEditorMaxOpenMb(value: Int) = setInt(KEY_EDITOR_MAX_OPEN_MB, value.coerceIn(1, 1024))

    // ---------------- 终端 ----------------

    suspend fun terminalFontSize(): Int = getInt(KEY_TERM_FONT, 13)
    suspend fun setTerminalFontSize(value: Int) = setInt(KEY_TERM_FONT, value.coerceIn(9, 24))

    /** 终端「常亮屏幕」：开启后终端页不熄屏、不进锁屏（跑长任务时用），默认关。 */
    suspend fun terminalScreenOn(): Boolean = get(KEY_TERM_SCREEN_ON) == "1"
    suspend fun setTerminalScreenOn(value: Boolean) = set(KEY_TERM_SCREEN_ON, if (value) "1" else "0")

    /** 旧版终端命令历史（仅用于迁移进 [HistoryStore]，新写入已停用）。 */
    suspend fun terminalHistory(): List<String> = jsonArray(KEY_TERM_HISTORY)

    suspend fun clearTerminalHistory() = set(KEY_TERM_HISTORY, "[]")

    // ---------------- 历史记录（「记录」页） ----------------

    /** 全部历史记录（JSON 对象数组，按时间倒序持久化）。 */
    suspend fun historyRecords(): List<HistoryRecord> {
        val raw = get(KEY_HISTORY) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    add(
                        HistoryRecord(
                            id = obj.optString("id"),
                            text = obj.optString("text"),
                            source = obj.optString("source"),
                            time = obj.optLong("time"),
                            favorite = obj.optBoolean("favorite"),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    suspend fun setHistoryRecords(records: List<HistoryRecord>) {
        val array = JSONArray()
        records.forEach { record ->
            array.put(
                JSONObject()
                    .put("id", record.id)
                    .put("text", record.text)
                    .put("source", record.source)
                    .put("time", record.time)
                    .put("favorite", record.favorite),
            )
        }
        set(KEY_HISTORY, array.toString())
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

    /**
     * Linux 环境 rootfs**自定义镜像源**（手动输入）：基址或完整 `.tar.gz` 地址。
     * 非空时作为第 1 优先候选，失败自动回退内置默认源；空 = 只用内置源。
     * 旧的 `glibc_*` 键作废（rootfs 与旧运行时布局不兼容，不迁移）。
     */
    suspend fun linuxCustomSource(): String = get(KEY_LINUX_CUSTOM_SOURCE) ?: ""
    suspend fun setLinuxCustomSource(value: String) = set(KEY_LINUX_CUSTOM_SOURCE, value.trim())

    /** Linux 环境 rootfs **首选内置源**（`RootfsManager.ROOTFS_MIRRORS` 的 id；空 = 按官方/国内镜像偏好排序）。 */
    suspend fun linuxPreferredSource(): String = get(KEY_LINUX_PREFERRED_SOURCE) ?: ""
    suspend fun setLinuxPreferredSource(value: String) = set(KEY_LINUX_PREFERRED_SOURCE, value)

    suspend fun jdkPath(): String = get(KEY_JDK_PATH) ?: ""
    suspend fun setJdkPath(value: String) = set(KEY_JDK_PATH, value)

    suspend fun gradlePath(): String = get(KEY_GRADLE_PATH) ?: ""
    suspend fun setGradlePath(value: String) = set(KEY_GRADLE_PATH, value)

    // ---------------- AI 助手（OpenAI 兼容接口：/chat/completions） ----------------

    /**
     * 多接口配置 JSON（原样读写；结构与加密由 feature_ai 的 AiProviderStore 负责，
     * 其中 apiKey 字段以 CryptoBox 密文保存）。null = 从未配置过（读取时触发旧单配置迁移）。
     */
    suspend fun aiProvidersJson(): String? = get(KEY_AI_PROVIDERS)
    suspend fun setAiProvidersJson(value: String) = set(KEY_AI_PROVIDERS, value)

    /** 旧版单接口配置（仅用于迁移进多接口结构，UI 不再读取）。 */
    suspend fun aiBaseUrl(): String = get(KEY_AI_BASE_URL) ?: "https://api.openai.com/v1"
    suspend fun setAiBaseUrl(value: String) = set(KEY_AI_BASE_URL, value.trim())

    suspend fun aiModel(): String = get(KEY_AI_MODEL) ?: "gpt-4o-mini"
    suspend fun setAiModel(value: String) = set(KEY_AI_MODEL, value.trim())

    /** API Key（加密存储，与 HTTPS 凭据同一把应用级密钥）。 */
    suspend fun aiApiKey(): String = decryptOrEmpty(get(KEY_AI_KEY))
    suspend fun setAiApiKey(value: String) =
        set(KEY_AI_KEY, if (value.isEmpty()) "" else crypto.encryptToString(value))

    /**
     * AI 每轮写入前弹确认（默认关；`delete_path` 永远确认，与本开关无关）。
     * 确认拒绝后仅跳过本轮写类操作，其余读/列操作照常执行。
     */
    suspend fun aiConfirmWrites(): Boolean = get(KEY_AI_CONFIRM_WRITES) == "1"
    suspend fun setAiConfirmWrites(value: Boolean) =
        set(KEY_AI_CONFIRM_WRITES, if (value) "1" else "0")

    /**
     * 自定义 system prompt（"" = 使用内置默认）。支持 `{project}`（项目根绝对路径）与
     * `{projectName}`（项目目录名）占位符，发送前由 AiController 替换。
     */
    suspend fun aiSystemPrompt(): String = get(KEY_AI_SYSTEM_PROMPT) ?: ""
    suspend fun setAiSystemPrompt(value: String) = set(KEY_AI_SYSTEM_PROMPT, value)

    /** 单次发送最大工具调用轮数（0 = 不限制），默认 100000。 */
    suspend fun aiRoundLimit(): Int = getInt(KEY_AI_ROUND_LIMIT, 100_000)
    suspend fun setAiRoundLimit(value: Int) = setInt(KEY_AI_ROUND_LIMIT, value.coerceIn(0, 1_000_000))

    // ---------------- 悬浮导航圆点 ----------------

    /** 悬浮导航圆点（点击弹出扇形导航菜单），默认开启；设置页可关。 */
    suspend fun floatingDotEnabled(): Boolean = get(KEY_FLOATING_DOT) != "0"
    suspend fun setFloatingDotEnabled(value: Boolean) =
        set(KEY_FLOATING_DOT, if (value) "1" else "0")

    /** 悬浮圆点位置：相对容器的千分比 `"x,y"`；null = 未拖动过（用默认位）。 */
    suspend fun floatingDotPos(): String? = get(KEY_FLOATING_DOT_POS)
    suspend fun setFloatingDotPos(x: Int, y: Int) =
        set(KEY_FLOATING_DOT_POS, "$x,$y")

    /** 悬浮圆点菜单是否显示图标名称（外环标签），默认显示。 */
    suspend fun floatingDotLabels(): Boolean = get(KEY_FLOATING_DOT_LABELS) != "0"
    suspend fun setFloatingDotLabels(value: Boolean) =
        set(KEY_FLOATING_DOT_LABELS, if (value) "1" else "0")

    /** 悬浮圆点菜单显示哪些项：route 逗号分隔；null/空 = 全部显示。 */
    suspend fun floatingDotMenus(): String? = get(KEY_FLOATING_DOT_MENUS)
    suspend fun setFloatingDotMenus(value: String) = set(KEY_FLOATING_DOT_MENUS, value)

    /** 各菜单项所在环：`route=0/1` 逗号分隔（0=内圈、1=外圈）；null/空 = 默认（底栏内圈、二级页外圈）。 */
    suspend fun floatingDotRings(): String? = get(KEY_FLOATING_DOT_RINGS)
    suspend fun setFloatingDotRings(value: String) = set(KEY_FLOATING_DOT_RINGS, value)

    /** 底部导航栏（底部胶囊标签栏）是否显示，默认开启；与悬浮圆点至少保留一个。 */
    suspend fun bottomBarEnabled(): Boolean = get(KEY_BOTTOM_BAR) != "0"
    suspend fun setBottomBarEnabled(value: Boolean) =
        set(KEY_BOTTOM_BAR, if (value) "1" else "0")

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
        /** 「不显示」名称的内置默认（用户可改，见 [editorHiddenNames]）。 */
        val DEFAULT_HIDDEN_NAMES = FileRepository.DEFAULT_HIDDEN_NAMES.toList()

        /** 「不支持打开」扩展名的内置默认（用户可改，见 [editorUnopenableExts]）。 */
        val DEFAULT_UNOPENABLE_EXTS = FileRepository.DEFAULT_UNOPENABLE_EXTS.toList()

        private val KEY_LAST_PROJECT = stringPreferencesKey("last_project_path")
        private val KEY_GIT_NAME = stringPreferencesKey("git_user_name")
        private val KEY_GIT_EMAIL = stringPreferencesKey("git_user_email")
        private val KEY_HTTPS_USER = stringPreferencesKey("https_user")
        private val KEY_HTTPS_PASS = stringPreferencesKey("https_pass_enc")
        private val KEY_EDITOR_FONT = intPreferencesKey("editor_font_size")
        private val KEY_EDITOR_LINENUM = stringPreferencesKey("editor_line_numbers")
        private val KEY_EDITOR_WRAP = stringPreferencesKey("editor_word_wrap")
        private val KEY_EDITOR_AUTOSAVE = stringPreferencesKey("editor_autosave")
        private val KEY_EDITOR_SHOW_HIDDEN = stringPreferencesKey("editor_show_hidden_files")
        private val KEY_EDITOR_HIDDEN_NAMES = stringPreferencesKey("editor_hidden_names")
        private val KEY_EDITOR_UNOPENABLE_EXTS = stringPreferencesKey("editor_unopenable_exts")
        private val KEY_EDITOR_MAX_OPEN_MB = intPreferencesKey("editor_max_open_mb")
        private val KEY_TERM_FONT = intPreferencesKey("terminal_font_size")
        private val KEY_TERM_SCREEN_ON = stringPreferencesKey("terminal_screen_on")
        private val KEY_TERM_HISTORY = stringPreferencesKey("terminal_history")
        private val KEY_HISTORY = stringPreferencesKey("history_records")
        private val KEY_BUILD_VARIANT = stringPreferencesKey("build_variant")
        private val KEY_BUILD_MEM = intPreferencesKey("build_memory_limit_mb")
        private val KEY_ENV_SOURCE = stringPreferencesKey("env_download_source")
        private val KEY_LINUX_CUSTOM_SOURCE = stringPreferencesKey("linux_custom_source")
        private val KEY_LINUX_PREFERRED_SOURCE = stringPreferencesKey("linux_preferred_source")
        private val KEY_JDK_PATH = stringPreferencesKey("jdk_path")
        private val KEY_GRADLE_PATH = stringPreferencesKey("gradle_path")
        private val KEY_AI_BASE_URL = stringPreferencesKey("ai_base_url")
        private val KEY_AI_MODEL = stringPreferencesKey("ai_model")
        private val KEY_AI_KEY = stringPreferencesKey("ai_api_key_enc")
        private val KEY_AI_PROVIDERS = stringPreferencesKey("ai_providers_json")
        private val KEY_AI_CONFIRM_WRITES = stringPreferencesKey("ai_confirm_writes")
        private val KEY_AI_SYSTEM_PROMPT = stringPreferencesKey("ai_system_prompt")
        private val KEY_AI_ROUND_LIMIT = intPreferencesKey("ai_round_limit")
        private val KEY_FLOATING_DOT = stringPreferencesKey("floating_dot_enabled")
        private val KEY_FLOATING_DOT_POS = stringPreferencesKey("floating_dot_pos")
        private val KEY_FLOATING_DOT_LABELS = stringPreferencesKey("floating_dot_labels")
        private val KEY_FLOATING_DOT_MENUS = stringPreferencesKey("floating_dot_menus")
        private val KEY_FLOATING_DOT_RINGS = stringPreferencesKey("floating_dot_rings")
        private val KEY_BOTTOM_BAR = stringPreferencesKey("bottom_bar_enabled")
    }
}
