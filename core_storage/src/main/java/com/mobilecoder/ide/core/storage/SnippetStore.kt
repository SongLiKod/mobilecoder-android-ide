package com.mobilecoder.ide.core.storage

import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/**
 * 一条代码片段（Issue 7：可自定义代码片段与常用模板）。
 *
 * @param id 稳定 id（编辑 / 删除定位用）；内置片段 id 形如 `builtin:kotlin:fori`
 * @param prefix 触发词（编辑器里敲到它就弹出本片段），只允许字母 / 数字 / 下划线
 * @param description 一句话说明（面板副标题）
 * @param language 归属语言键：`kotlin` / `java` / `js` / `ts` / `html` / `shell` / `any`
 * @param body 片段正文，支持 `$1` `$2` 跳位与 `$0` 最终光标、`\$` 转义字面量美元符
 * @param builtin 是否内置（内置不可删改，自定义同语言同触发词可覆盖它）
 */
data class CodeSnippet(
    val id: String,
    val prefix: String,
    val description: String,
    val language: String,
    val body: String,
    val builtin: Boolean = false,
) {
    /** 覆盖判定键：内置与自定义按「语言 + 触发词」一一对应（自定义覆盖内置）。 */
    val key: String get() = "${language.lowercase()}|${prefix.lowercase()}"
}

/**
 * 片段库（内置 + 自定义）与自定义片段持久化。
 *
 * - 内置库由 [builtin] 在代码里给出，随版本演进，永不落盘；
 * - 自定义片段落盘到 `files/snippets/snippets.json`（DataStore 之外的独立文件，
 *   方便用户备份 / 手工编辑；结构见 [exportJson]）；
 * - 合并规则：自定义与内置同「语言 + 触发词」时**自定义生效**（覆盖内置，
 *   用户可借内置模板改出自己的版本；删掉自定义即恢复内置）；
 * - 全部写操作走 [Mutex]，与 HistoryStore 同风格。
 */
object SnippetStore {

    /** 片段语言键（UI 语言筛选、导入校验同源）。 */
    const val LANG_KOTLIN = "kotlin"
    const val LANG_JAVA = "java"
    const val LANG_JS = "js"
    const val LANG_TS = "ts"
    const val LANG_HTML = "html"
    const val LANG_SHELL = "shell"
    const val LANG_ANY = "any"

    val LANGUAGES = listOf(LANG_KOTLIN, LANG_JAVA, LANG_JS, LANG_TS, LANG_HTML, LANG_SHELL, LANG_ANY)

    /** 语言键中文名（面板筛选标签）。 */
    val LANGUAGE_LABELS = mapOf(
        LANG_KOTLIN to "Kotlin",
        LANG_JAVA to "Java",
        LANG_JS to "JavaScript",
        LANG_TS to "TypeScript",
        LANG_HTML to "HTML",
        LANG_SHELL to "Shell",
        LANG_ANY to "通用",
    )

    /** 自定义片段总量上限（超出拒绝新增，防误操作刷爆文件）。 */
    private const val MAX_CUSTOM = 500

    private const val FILE_NAME = "snippets.json"

    private val _snippets = MutableStateFlow<List<CodeSnippet>>(emptyList())

    /** 全部片段：内置在前（按定义序）、自定义随后（按创建倒序），已按覆盖规则去重。 */
    val snippets: StateFlow<List<CodeSnippet>> = _snippets.asStateFlow()

    private val mutex = Mutex()

    @Volatile
    private var loaded = false

    /** 自定义片段（不含内置）。 */
    fun custom(): List<CodeSnippet> = _snippets.value.filterNot { it.builtin }

    /** 首次访问时加载自定义片段（幂等、可并发）。 */
    suspend fun ensureLoaded() {
        if (loaded) return
        mutex.withLock {
            ensureLoadedLocked()
        }
    }

    /** [ensureLoaded] 的锁内版本（调用方必须已持有 [mutex]）。 */
    private fun ensureLoadedLocked() {
        if (loaded) return
        _snippets.value = mergeCustom(builtin(), readCustom())
        loaded = true
    }

    /**
     * 新增 / 编辑一条自定义片段。
     *
     * @return null = 成功；非 null = 中文错误提示（触发词非法、正文为空、超限…）
     */
    suspend fun upsert(
        id: String? = null,
        prefix: String,
        description: String,
        language: String,
        body: String,
    ): String? {
        val normalized = normalizeSnippet(prefix, description, language, body)
            ?: return "触发词只能是字母 / 数字 / 下划线，正文不能为空"
        mutex.withLock {
            ensureLoadedLocked()
            val current = _snippets.value
            val existingCustom = current.filterNot { it.builtin }
            // 新增（而非编辑已有）时才校验总量上限
            val editing = id != null && existingCustom.any { it.id == id }
            if (!editing && existingCustom.size >= MAX_CUSTOM) {
                return "自定义片段已达上限（$MAX_CUSTOM 条）"
            }
            val stored = normalized.copy(id = id ?: newId())
            val rest = existingCustom.filterNot { it.id == stored.id }
            _snippets.value = mergeCustom(builtin(), rest + stored)
            writeCustom()
        }
        return null
    }

    /** 删除一条自定义片段（内置不可删；删除同 key 自定义后内置自动恢复）。 */
    suspend fun remove(id: String): Boolean {
        mutex.withLock {
            val current = _snippets.value
            val target = current.firstOrNull { it.id == id && !it.builtin } ?: return false
            val rest = current.filterNot { it.id == target.id }
            _snippets.value = mergeCustom(builtin(), rest.filterNot { it.builtin })
            writeCustom()
            return true
        }
    }

    /** 导出全部自定义片段为 JSON 文本（与 [importJson] 互为反向）。 */
    suspend fun exportJson(): String {
        ensureLoaded()
        val array = JSONArray()
        custom().sortedBy { it.prefix }.forEach { snippet ->
            array.put(
                JSONObject()
                    .put("prefix", snippet.prefix)
                    .put("description", snippet.description)
                    .put("language", snippet.language)
                    .put("body", snippet.body),
            )
        }
        return JSONObject().put("version", 1).put("snippets", array).toString(2)
    }

    /**
     * 导入 JSON 文本（[exportJson] 产出的格式；也容忍裸数组）。
     *
     * @param replace true = 先清空全部自定义片段再导入；false = 与现有合并（同 key 覆盖）
     * @return 导入统计；文本非法时抛出 [IllegalArgumentException]（调用方给中文提示）
     */
    suspend fun importJson(text: String, replace: Boolean): ImportResult {
        val incoming = parseJson(text)
            ?: throw IllegalArgumentException("不是有效的片段 JSON")
        mutex.withLock {
            ensureLoadedLocked()
            val base = if (replace) emptyList() else _snippets.value.filterNot { it.builtin }
            val incomingKeys = incoming.mapTo(HashSet()) { it.key }
            // 覆盖导入：被同 key 命中的旧自定义先摘掉，避免残留下两条同触发词
            val kept = base.filterNot { it.key in incomingKeys }
            val merged = (kept + incoming.take(MAX_CUSTOM)).distinctBy { it.key }
            _snippets.value = mergeCustom(builtin(), merged)
            writeCustom()
            return ImportResult(added = merged.size - kept.size, updated = base.size - kept.size)
        }
    }

    // ------------------------------------------------------------------
    // 纯逻辑（JVM 单测直接覆盖）
    // ------------------------------------------------------------------

    /** 内置片段库（按语言分组；id 稳定，勿随意改名以免自定义覆盖关系错位）。 */
    fun builtin(): List<CodeSnippet> = BUILTIN

    /**
     * 合并「内置 + 自定义」：同 key 自定义覆盖内置（占据内置的位置，保持展示顺序），
     * 其余自定义追加在后。入参乱序也可得到稳定结果。
     */
    internal fun mergeCustom(builtin: List<CodeSnippet>, custom: List<CodeSnippet>): List<CodeSnippet> {
        val distinct = custom.filter { it.prefix.isNotBlank() }.distinctBy { it.key }
        val customByKey = distinct.associateBy { it.key }
        val builtinKeys = builtin.mapTo(HashSet()) { it.key }
        val head = builtin.map { base -> customByKey[base.key]?.copy(builtin = false) ?: base }
        val tail = distinct.filterNot { it.key in builtinKeys }
        return head + tail
    }

    /**
     * 归一化用户输入：去首尾空白、语言键回落到「通用」、校验触发词字符集与正文非空。
     * 非法返回 null（不静默改写触发词——它决定面板何时弹出，必须所见即所得）。
     */
    internal fun normalizeSnippet(
        prefix: String,
        description: String,
        language: String,
        body: String,
    ): CodeSnippet? {
        val p = prefix.trim()
        val b = body.trimEnd('\n')
        if (p.isEmpty() || b.isEmpty()) return null
        if (!p.all { it.isLetterOrDigit() || it == '_' }) return null
        val lang = language.trim().lowercase().takeIf { it in LANGUAGES } ?: LANG_ANY
        return CodeSnippet(
            id = "",
            prefix = p,
            description = description.trim().ifEmpty { p },
            language = lang,
            body = b,
        )
    }

    /**
     * 片段面板候选：触发词前缀匹配（大小写不敏感），内置在前、短触发词优先，最多 [limit] 条。
     *
     * 公开给 feature_editor 的片段引擎复用（跨模块可见性；纯函数，JVM 单测直接覆盖）。
     */
    fun filterByPrefix(
        snippets: List<CodeSnippet>,
        word: String,
        language: String?,
        limit: Int = 8,
    ): List<CodeSnippet> {
        val w = word.trim().lowercase()
        if (w.isEmpty()) return emptyList()
        return snippets
            .filter { it.prefix.lowercase().startsWith(w) }
            .filter { language == null || it.language == LANG_ANY || it.language == language }
            .sortedWith(
                compareBy(
                    { it.prefix.length },
                    { !it.builtin },
                    { it.prefix.lowercase() },
                ),
            )
            .take(limit)
    }

    private fun newId(): String = "cus-" + UUID.randomUUID().toString().take(8)

    // ------------------------------------------------------------------
    // 持久化（org.json 仅运行时可用；解析失败一律回落空库，不抛给用户）
    // ------------------------------------------------------------------

    private fun storageFile(): File? = runCatching { File(AppStorage.paths.snippets, FILE_NAME) }.getOrNull()

    private fun readCustom(): List<CodeSnippet> {
        val file = storageFile() ?: return emptyList()
        if (!file.isFile) return emptyList()
        val text = runCatching { file.readText() }.getOrDefault("")
        return parseJson(text).orEmpty()
    }

    private fun writeCustom() {
        val file = storageFile() ?: return
        runCatching {
            val dir = file.parentFile
            if (dir != null && !dir.exists()) dir.mkdirs()
            file.writeText(exportJsonBlocking())
        }
    }

    /** [exportJson] 的同步版（仅供落盘；外部一律走 suspend 版）。 */
    private fun exportJsonBlocking(): String {
        val array = JSONArray()
        custom().sortedBy { it.prefix }.forEach { snippet ->
            array.put(
                JSONObject()
                    .put("prefix", snippet.prefix)
                    .put("description", snippet.description)
                    .put("language", snippet.language)
                    .put("body", snippet.body),
            )
        }
        return JSONObject().put("version", 1).put("snippets", array).toString(2)
    }

    /** 解析导入文本：兼容 `{"snippets":[…]}` 与裸数组；任一条非法即整体失败（返回 null）。 */
    private fun parseJson(text: String): List<CodeSnippet>? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        val array = runCatching {
            when {
                trimmed.startsWith("[") -> JSONArray(trimmed)
                else -> JSONObject(trimmed).optJSONArray("snippets") ?: return null
            }
        }.getOrNull() ?: return null
        val out = ArrayList<CodeSnippet>()
        for (i in 0 until array.length()) {
            val obj = runCatching { array.getJSONObject(i) }.getOrNull() ?: return null
            val normalized = normalizeSnippet(
                prefix = obj.optString("prefix"),
                description = obj.optString("description"),
                language = obj.optString("language"),
                body = obj.optString("body"),
            ) ?: return null
            out += normalized
        }
        return out.distinctBy { it.key }
    }

    /** 导入结果（导入条数统计，UI 直接提示）。 */
    data class ImportResult(val added: Int, val updated: Int)

    // ------------------------------------------------------------------
    // 内置库
    // ------------------------------------------------------------------

    private fun s(prefix: String, description: String, language: String, body: String) = CodeSnippet(
        id = "builtin:$language:$prefix",
        prefix = prefix,
        description = description,
        language = language,
        body = body.trimIndent(),
        builtin = true,
    )

    private val BUILTIN: List<CodeSnippet> = listOf(
        // ---------------- Kotlin ----------------
        s("fori", "for 下标循环", LANG_KOTLIN, """
            for (i in 0 until ${'$'}1) {
                ${'$'}2
            }
        """),
        s("foreach", "for 遍历", LANG_KOTLIN, """
            for (${'$'}1 in ${'$'}2) {
                ${'$'}3
            }
        """),
        s("ifel", "if / else", LANG_KOTLIN, """
            if (${'$'}1) {
                ${'$'}2
            } else {
                ${'$'}3
            }
        """),
        s("when", "when 表达式", LANG_KOTLIN, """
            when (${'$'}1) {
                ${'$'}2 -> ${'$'}3
                else -> ${'$'}4
            }
        """),
        s("fun", "函数", LANG_KOTLIN, """
            fun ${'$'}1(${'$'}2) {
                ${'$'}3
            }
        """),
        s("main", "main 入口", LANG_KOTLIN, """
            fun main() {
                ${'$'}1
            }
        """),
        s("class", "类", LANG_KOTLIN, """
            class ${'$'}1 {
                ${'$'}2
            }
        """),
        s("data", "data class", LANG_KOTLIN, """
            data class ${'$'}1(
                val ${'$'}2: ${'$'}3,
            )
        """),
        s("composable", "Composable 函数", LANG_KOTLIN, """
            @Composable
            fun ${'$'}1() {
                ${'$'}2
            }
        """),
        s("try", "try / catch", LANG_KOTLIN, """
            try {
                ${'$'}1
            } catch (e: Exception) {
                ${'$'}2
            }
        """),
        s("launch", "协程 launch", LANG_KOTLIN, """
            scope.launch {
                ${'$'}1
            }
        """),
        s("lazy", "by lazy 惰性初始化", LANG_KOTLIN, """
            val ${'$'}1 by lazy {
                ${'$'}2
            }
        """),
        // ---------------- Java ----------------
        s("fori", "for 下标循环", LANG_JAVA, """
            for (int i = 0; i < ${'$'}1; i++) {
                ${'$'}2
            }
        """),
        s("foreach", "for-each 遍历", LANG_JAVA, """
            for (${'$'}1 : ${'$'}2) {
                ${'$'}3
            }
        """),
        s("ifel", "if / else", LANG_JAVA, """
            if (${'$'}1) {
                ${'$'}2
            } else {
                ${'$'}3
            }
        """),
        s("main", "main 方法", LANG_JAVA, """
            public static void main(String[] args) {
                ${'$'}1
            }
        """),
        s("sout", "System.out.println", LANG_JAVA, """
            System.out.println(${'$'}1);
        """),
        s("class", "类", LANG_JAVA, """
            public class ${'$'}1 {
                ${'$'}2
            }
        """),
        s("method", "方法", LANG_JAVA, """
            public ${'$'}1 ${'$'}2(${'$'}3) {
                ${'$'}4
            }
        """),
        s("try", "try / catch", LANG_JAVA, """
            try {
                ${'$'}1
            } catch (Exception e) {
                ${'$'}2
            }
        """),
        // ---------------- JavaScript ----------------
        s("for", "for 循环", LANG_JS, """
            for (let i = 0; i < ${'$'}1; i++) {
                ${'$'}2
            }
        """),
        s("forof", "for…of 遍历", LANG_JS, """
            for (const ${'$'}1 of ${'$'}2) {
                ${'$'}3
            }
        """),
        s("ifel", "if / else", LANG_JS, """
            if (${'$'}1) {
                ${'$'}2
            } else {
                ${'$'}3
            }
        """),
        s("fn", "函数声明", LANG_JS, """
            function ${'$'}1(${'$'}2) {
                ${'$'}3
            }
        """),
        s("arrow", "箭头函数", LANG_JS, """
            const ${'$'}1 = (${'$'}2) => {
                ${'$'}3
            };
        """),
        s("log", "console.log", LANG_JS, """
            console.log(${'$'}1);
        """),
        s("async", "async 函数", LANG_JS, """
            async function ${'$'}1(${'$'}2) {
                ${'$'}3
            }
        """),
        s("dom", "querySelector", LANG_JS, """
            document.querySelector('${'$'}1')
        """),
        // ---------------- TypeScript ----------------
        s("fn", "函数（含类型）", LANG_TS, """
            function ${'$'}1(${'$'}2: ${'$'}3): ${'$'}4 {
                ${'$'}5
            }
        """),
        s("arrow", "箭头函数（含类型）", LANG_TS, """
            const ${'$'}1 = (${'$'}2: ${'$'}3): ${'$'}4 => {
                ${'$'}5
            };
        """),
        s("iface", "interface", LANG_TS, """
            interface ${'$'}1 {
                ${'$'}2
            }
        """),
        s("type", "type 别名", LANG_TS, """
            type ${'$'}1 = ${'$'}2;
        """),
        s("class", "class（含修饰符）", LANG_TS, """
            class ${'$'}1 {
                ${'$'}2
            }
        """),
        s("import", "具名导入", LANG_TS, """
            import { ${'$'}1 } from '${'$'}2';
        """),
        s("promise", "Promise 封装", LANG_TS, """
            const ${'$'}1 = new Promise<${'$'}2>((resolve, reject) => {
                ${'$'}3
            });
        """),
        // ---------------- HTML ----------------
        s("html5", "HTML5 骨架", LANG_HTML, """
            <!DOCTYPE html>
            <html lang="zh-CN">
            <head>
                <meta charset="UTF-8" />
                <meta name="viewport" content="width=device-width, initial-scale=1.0" />
                <title>${'$'}1</title>
            </head>
            <body>
                ${'$'}2
            </body>
            </html>
        """),
        s("div", "div 容器", LANG_HTML, """
            <div class="${'$'}1">
                ${'$'}2
            </div>
        """),
        s("a", "链接", LANG_HTML, """
            <a href="${'$'}1">${'$'}2</a>
        """),
        s("img", "图片", LANG_HTML, """
            <img src="${'$'}1" alt="${'$'}2" />
        """),
        s("input", "输入框", LANG_HTML, """
            <input type="${'$'}1" name="${'$'}2" placeholder="${'$'}3" />
        """),
        s("form", "表单", LANG_HTML, """
            <form action="${'$'}1" method="post">
                ${'$'}2
            </form>
        """),
        s("script", "script 引入", LANG_HTML, """
            <script src="${'$'}1"></script>
        """),
        // ---------------- Shell ----------------
        s("shebang", "bash shebang", LANG_SHELL, """
            #!/usr/bin/env bash
            set -euo pipefail

            ${'$'}1
        """),
        s("for", "for 循环", LANG_SHELL, """
            for ${'$'}1 in ${'$'}2; do
                ${'$'}3
            done
        """),
        s("if", "if 判断", LANG_SHELL, """
            if [ ${'$'}1 ]; then
                ${'$'}2
            fi
        """),
        s("func", "函数定义", LANG_SHELL, """
            ${'$'}1() {
                ${'$'}2
            }
        """),
        s("while", "while 循环", LANG_SHELL, """
            while [ ${'$'}1 ]; do
                ${'$'}2
            done
        """),
        s("param", "位置参数校验", LANG_SHELL, """
            if [ -z "${'$'}1" ]; then
                echo "missing argument: ${'$'}1" >&2
                exit 1
            fi
        """),
        // ---------------- 通用 ----------------
        s("todo", "TODO 注释", LANG_ANY, "// TODO: ${'$'}1"),
        s("fixme", "FIXME 注释", LANG_ANY, "// FIXME: ${'$'}1"),
        s("date", "当前日期", LANG_ANY, "${'$'}(date '+%Y-%m-%d %H:%M:%S')"),
        s("uuid", "随机 UUID", LANG_ANY, "${'$'}(uuidgen)"),
    )
}
