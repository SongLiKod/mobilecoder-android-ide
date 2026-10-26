package com.mobilecoder.ide.feature.ai

import kotlin.coroutines.cancellation.CancellationException

/**
 * 编辑器**行内补全**（inline completion，类 Copilot）：
 * 取光标前 N 行 + 光标后的代码 + 文件语言，发一个 `max_tokens` 很小的
 * [AiClient.complete] 请求，拿回一段「幽灵文本」由编辑器展示，Tab 接受 / Esc 拒绝。
 *
 * 设计要点：
 *  - 高频小请求：超时短（15s/60s）、输出上限 [MAX_TOKENS]，失败一律静默降级为「无补全」，
 *    绝不打断输入、不弹错误（[suggest] 返回 null）；
 *  - 与会话解耦：不进 [AiController] 会话、不占 busy、不写快照；
 *  - 纯函数 [systemPrompt] / [userPrompt] / [clean] 便于单测覆盖各种模型「怪输出」。
 */
object AiCompletion {

    /** 单次补全的输出 token 上限（几行即可，给大了反而拖慢首字）。 */
    const val MAX_TOKENS = 64

    /** 最多接受的补全行数（超出按行截断，避免一次吞掉半个多文件）。 */
    const val MAX_LINES = 6

    /** 补全文本上限（字符）。 */
    const val MAX_CHARS = 600

    /** 发给模型的上下文长度上限（防把整文件塞进补全请求）。 */
    private const val MAX_PREFIX_CHARS = 4000
    private const val MAX_SUFFIX_CHARS = 1000

    /**
     * 系统提示：把模型约束成「只吐续写文本」的补全引擎。
     *
     * @param language 文件语言标签（Kotlin / JavaScript / 纯文本…）
     */
    fun systemPrompt(language: String): String = """
        你是 ${language.ifBlank { "代码" }} 代码补全引擎，根据光标处的上下文续写少量代码（通常 1~3 行）。
        严格要求：
        1. 只输出续写内容本身：不要解释、不要 Markdown 代码围栏、不要任何前后缀说明。
        2. 直接从光标位置接着写：不要重复光标前已有的字符，不要输出光标后已经存在的内容。
        3. 没有合适补全时输出空内容（不要输出任何文字）。
        4. 语言要求：说明性文字用简体中文，但续写内容只能是代码。
    """.trimIndent()

    /**
     * 用户消息：光标前 / 光标后两段原文。
     *
     * 用显式分隔行而不是特殊标记，兼容不支持 FIM 记号的国产模型。
     */
    fun userPrompt(prefix: String, suffix: String): String = buildString {
        append("【光标前】\n")
        append(prefix)
        append("\n\n【光标后】\n")
        append(suffix)
    }

    /**
     * 把模型输出清洗成可直接插入光标的补全文本（空串 = 无补全）。
     *
     * 处理这些常见「怪输出」：
     *  - 整段包在 ``` 围栏里 → 取围栏正文；被 max_tokens 截断的半截围栏 → 丢弃；
     *  - 开头的空行 / 结尾的空白 → 去掉（补全不该产生空行）；
     *  - 光标处的不完整词干被模型整词重写（`resu` + `result = …`）→ 去掉重复词干；
     *  - 光标停在缩进位、模型又把缩进写一遍 → 首行去掉重复缩进。
     *
     * @param prefix 发起请求时光标前的完整正文（用来算词干与缩进）
     */
    fun clean(raw: String, prefix: String): String {
        if (raw.isBlank()) return ""
        var text = raw.replace("\r\n", "\n").replace('\r', '\n')
        text = stripFence(text)

        val lineStart = (prefix.lastIndexOf('\n') + 1).coerceIn(0, prefix.length)
        val linePrefix = prefix.substring(lineStart)
        // 光标在行首（可能已带缩进）：模型的前导空行是多余的，缩进也可能重复
        if (linePrefix.isBlank()) {
            text = text.trimStart('\n')
            if (linePrefix.isNotEmpty() && text.startsWith(linePrefix)) {
                text = text.substring(linePrefix.length)
            }
        }
        text = text.trimEnd()
        if (text.isEmpty()) return ""

        // 后半段 / 半截围栏（模型被 max_tokens 截断）：从该行起全部丢弃
        val lines = text.split('\n')
        val fenceIdx = lines.indexOfFirst { it.trimStart().startsWith("```") }
        if (fenceIdx >= 0) {
            text = lines.take(fenceIdx).joinToString("\n").trimEnd()
            if (text.isEmpty()) return ""
        }

        // 行数 / 长度上限
        val kept = text.split('\n').take(MAX_LINES)
        var out = kept.joinToString("\n")
        if (out.length > MAX_CHARS) out = out.take(MAX_CHARS).trimEnd()

        out = stripTokenEcho(prefix, out)
        return if (out.isBlank()) "" else out
    }

    /**
     * 发起一次补全请求。
     *
     * @param prefix 光标前正文
     * @param suffix 光标后正文（模型据此避免重复输出已有内容）
     * @param language 文件语言标签
     * @return 清洗后的补全文本；任何失败（未配置接口 / 超时 / 限流 / 空回复）返回 null
     */
    suspend fun suggest(
        prefix: String,
        suffix: String,
        language: String,
    ): String? {
        if (prefix.isEmpty()) return null
        return try {
            val raw = AiOneShot.ask(
                system = systemPrompt(language),
                user = userPrompt(
                    if (prefix.length > MAX_PREFIX_CHARS) prefix.takeLast(MAX_PREFIX_CHARS) else prefix,
                    suffix.take(MAX_SUFFIX_CHARS),
                ),
                maxTokens = MAX_TOKENS,
                temperature = 0.0,
                isCompletion = true,
            )
            clean(raw, prefix).ifEmpty { null }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            // 补全只是锦上添花：任何异常都静默降级，绝不打断输入
            null
        }
    }

    // ------------------------------------------------------------------
    // 纯函数辅助（单测覆盖）
    // ------------------------------------------------------------------

    /** 去掉包裹整段输出的 Markdown 代码围栏（无围栏原样返回）。 */
    internal fun stripFence(text: String): String {
        val lines = text.split('\n')
        val open = lines.indexOfFirst { it.trimStart().startsWith("```") }
        if (open < 0) return text
        val inner = lines.drop(open + 1)
        val close = inner.indexOfFirst { it.trimStart().startsWith("```") }
        return (if (close >= 0) inner.take(close) else inner).joinToString("\n")
    }

    /**
     * 去掉与光标处不完整词干重复的前缀：光标停在 `resu` 而模型按整词输出
     * `result = …` 时，直接插入会变成 `resuresult = …`，这里只保留后半段。
     */
    internal fun stripTokenEcho(prefix: String, suggestion: String): String {
        val stem = prefix.takeLastWhile { it.isLetterOrDigit() || it == '_' }
        if (stem.length < 2) return suggestion
        return if (suggestion.startsWith(stem)) suggestion.substring(stem.length) else suggestion
    }
}
