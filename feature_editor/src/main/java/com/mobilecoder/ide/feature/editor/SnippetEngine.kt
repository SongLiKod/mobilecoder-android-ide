package com.mobilecoder.ide.feature.editor

import com.mobilecoder.ide.core.storage.CodeSnippet
import com.mobilecoder.ide.core.storage.SnippetStore

/**
 * 片段引擎（Issue 7）：触发词解析、正文占位符展开、跳位光标映射。
 *
 * 全部是纯函数（JVM 单测直接覆盖，见 SnippetEngineTest），不碰 Compose / IO。
 *
 * **占位符语法**（与 VS Code 同风格的子集）：
 *  - `$1` … `$9`：编号跳位。首次出现是跳位（插入后光标依次落过去），
 *    同编号的后续出现视为**镜像**，原地替换为空（不做编辑期同步，见类注释「刻意简化」）；
 *  - `$0`：最终光标位（没有 `$0` 时落回插入文本末尾）；
 *  - `\$`：字面量 `$`（shell / JS 里 `$` 很常见，必须能转义）；
 *  - 其余 `$` 后跟非数字（如 `$@`、`${x}`）原样保留，不吞字符。
 *
 * **刻意简化**：镜像不做「编辑一处、其余同步」——移动端逐处同步收益低、
 * 出错面大；同编号后续出现直接置空，用户想复用就手动粘贴。
 */
internal object SnippetEngine {

    /** 触发词命中：光标前的标识符词干（[start, cursor) 区间待被片段正文替换）。 */
    data class Trigger(val word: String, val start: Int)

    /** 展开结果：插入文本 + 各跳位在插入文本内的偏移 + 最终光标偏移。 */
    data class Expansion(
        val text: String,
        val stops: List<Int>,
        val cursor: Int,
    )

    /** 触发词字符集：字母 / 数字 / 下划线（与 SnippetStore.normalizeSnippet 同口径）。 */
    private fun isTriggerChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_'

    /**
     * 解析光标前的触发词：从 [cursor] 向前吃标识符字符。
     *
     * 词干为空（光标前不是标识符字符）、或光标不在 [text] 范围内时返回 null ——
     * 面板据此收起，不会在标点 / 空格后误弹。
     */
    fun triggerAt(text: String, cursor: Int): Trigger? {
        val at = cursor.coerceIn(0, text.length)
        var start = at
        while (start > 0 && isTriggerChar(text[start - 1])) start--
        if (start == at) return null
        return Trigger(text.substring(start, at), start)
    }

    /**
     * 展开片段正文并按 [indent] 续行缩进。
     *
     * 首行不加缩进（它接在触发词起点处，前缀缩进由调用方所在行提供），
     * 其余每行前置 [indent]，使多行片段落在正确的嵌套层级里。
     */
    fun expand(body: String, indent: String = ""): Expansion {
        val normalized = body.replace("\r\n", "\n").replace('\r', '\n')
        val source = normalized.trimEnd('\n')
        val text = source.lineSequence()
            .mapIndexed { index, line -> if (index == 0) line else indent + line }
            .joinToString("\n")

        val stops = ArrayList<Int>()
        val seen = HashSet<Int>()
        val out = StringBuilder(text.length)
        var finalCursor: Int? = null
        var i = 0
        while (i < text.length) {
            val c = text[i]
            // `\$` → 字面量 $（转义斜杠被吞掉；见类注释的占位符语法）
            if (c == '\\' && i + 1 < text.length && text[i + 1] == '$') {
                out.append('$')
                i += 2
                continue
            }
            if (c != '$' || i + 1 >= text.length) {
                out.append(c)
                i++
                continue
            }
            val next = text[i + 1]
            when {
                // $0：最终光标位（首个生效）
                next == '0' -> {
                    if (finalCursor == null) finalCursor = out.length
                    i += 2
                }
                // $1..$9：跳位（首次）/ 镜像置空（其后）
                next.isDigit() -> {
                    val n = next - '0'
                    if (n != 0 && seen.add(n)) stops += out.length
                    i += 2
                }
                // 其余 `$` 原样保留（$@、$#、${x}…）
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        val result = out.toString()
        return Expansion(
            text = result,
            stops = stops.toList(),
            cursor = (finalCursor ?: result.length).coerceIn(0, result.length),
        )
    }

    /**
     * 编辑发生后平移跳位偏移（纯函数，SnippetsTest 覆盖）。
     *
     * 找出 [prev] → [cur] 的编辑区间（公共前缀 / 公共后缀之外的部分）：
     *  - 跳位在编辑区间之前 → 不动；
     *  - 跳位在编辑区间之后 → 整体平移 delta；
     *  - 跳位落在编辑区间之内 → 夹到区间起点（内容已被改写，原偏移失去意义）。
     */
    fun adjustStops(prev: String, cur: String, stops: List<Int>): List<Int> {
        if (prev == cur || stops.isEmpty()) return stops
        val prefix = commonPrefixLength(prev, cur)
        val suffix = commonSuffixLength(prev, cur, prefix)
        val delta = cur.length - prev.length
        val prevEditEnd = prev.length - suffix
        return stops.map { stop ->
            when {
                stop <= prefix -> stop
                stop >= prevEditEnd -> stop + delta
                else -> prefix
            }
        }
    }

    /**
     * 当前文件应展示哪些语言的片段（内置/自定义共用同一套语言键）。
     *
     * 识别不出的类型（JSON / Markdown / 属性文件…）返回 null = 不按语言过滤，
     * 只展示「通用」+ 全部语言的片段——宁可多给，别让面板空着。
     */
    fun languageKeyOf(language: Language): String? = when (language) {
        Language.KOTLIN, Language.GRADLE -> SnippetStore.LANG_KOTLIN
        Language.JAVA -> SnippetStore.LANG_JAVA
        Language.JAVASCRIPT -> SnippetStore.LANG_JS
        Language.TYPESCRIPT -> SnippetStore.LANG_TS
        // .html 由 Language.of 归到 XML
        Language.XML -> SnippetStore.LANG_HTML
        Language.SHELL -> SnippetStore.LANG_SHELL
        else -> null
    }

    /** 面板候选：触发词前缀匹配 + 语言过滤（[languageKey] 为 null 时不过滤）。 */
    fun candidates(snippets: List<CodeSnippet>, word: String, languageKey: String?): List<CodeSnippet> =
        SnippetStore.filterByPrefix(snippets, word, languageKey)

    private fun commonPrefixLength(a: String, b: String): Int {
        val max = minOf(a.length, b.length)
        var i = 0
        while (i < max && a[i] == b[i]) i++
        return i
    }

    /** 公共后缀长度（与前缀不重叠：`[prefix, len)` 内才允许继续比对）。 */
    private fun commonSuffixLength(a: String, b: String, prefix: Int): Int {
        val max = minOf(a.length, b.length) - prefix
        var i = 0
        while (i < max && a[a.length - 1 - i] == b[b.length - 1 - i]) i++
        return i
    }
}
