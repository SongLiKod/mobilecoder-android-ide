package com.mobilecoder.ide.feature.ai

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/**
 * 轻量 Markdown 渲染（P1：AI 回复可读性）。
 *
 * 零第三方依赖，只覆盖对话高频块：
 *  - 代码块 ```lang（等宽 + 顶栏 + 一键复制）
 *  - 标题 `#`~`###`、无序/有序列表
 *  - 段落（保留硬换行）
 *  - 行内 `code` / **粗体** / *斜体*
 * 解析失败一律按纯文本回落，绝不抛异常。
 */

// ---------------------------------------------------------------------------
// 解析模型
// ---------------------------------------------------------------------------

internal sealed class MdSpan {
    data class Plain(val text: String) : MdSpan()
    data class Code(val text: String) : MdSpan()
    data class Bold(val text: String) : MdSpan()
    data class Italic(val text: String) : MdSpan()
}

internal sealed class MdBlock {
    data class Para(val spans: List<MdSpan>) : MdBlock()
    data class Heading(val level: Int, val spans: List<MdSpan>) : MdBlock()
    data class Bullet(val ordered: Boolean, val items: List<List<MdSpan>>) : MdBlock()
    data class Code(val lang: String, val code: String) : MdBlock()
}

private val HEADING = Regex("""^(#{1,6})\s+(.*)$""")
private val BULLET = Regex("""^\s*[-*]\s+(.*)$""")
private val ORDERED = Regex("""^\s*\d+[.)]\s+(.*)$""")

/** 解析 Markdown 为块列表（容错：无法识别的内容按段落保留）。 */
internal fun parseMarkdown(text: String): List<MdBlock> {
    val lines = text.replace("\r\n", "\n").split('\n')
    val blocks = ArrayList<MdBlock>()
    var i = 0
    while (i < lines.size) {
        val line = lines[i]

        // 代码块
        if (line.trimStart().startsWith("```")) {
            val lang = line.trim().removePrefix("```").trim()
            val body = StringBuilder()
            i++
            while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                body.append(lines[i]).append('\n')
                i++
            }
            if (i < lines.size) i++ // 收尾围栏
            blocks += MdBlock.Code(lang, body.toString().trimEnd('\n'))
            continue
        }

        if (line.isBlank()) {
            i++
            continue
        }

        // 标题
        val heading = HEADING.find(line)
        if (heading != null) {
            blocks += MdBlock.Heading(
                heading.groupValues[1].length,
                parseInline(heading.groupValues[2].trim()),
            )
            i++
            continue
        }

        // 列表（连续行聚合）
        if (BULLET.matches(line) || ORDERED.matches(line)) {
            val ordered = ORDERED.matches(line)
            val items = ArrayList<List<MdSpan>>()
            while (i < lines.size) {
                val matcher = if (ordered) ORDERED else BULLET
                val m = matcher.find(lines[i]) ?: break
                items += parseInline(m.groupValues[1].trim())
                i++
            }
            blocks += MdBlock.Bullet(ordered, items)
            continue
        }

        // 段落：吃掉到空行 / 围栏 / 标题 / 列表为止
        val para = StringBuilder()
        while (i < lines.size) {
            val cur = lines[i]
            if (cur.isBlank() || cur.trimStart().startsWith("```") ||
                HEADING.matches(cur) || BULLET.matches(cur) || ORDERED.matches(cur)
            ) {
                break
            }
            if (para.isNotEmpty()) para.append('\n')
            para.append(cur)
            i++
        }
        blocks += MdBlock.Para(parseInline(para.toString()))
    }
    return blocks
}

/** 行内解析：`code` / **bold** / *italic*。 */
internal fun parseInline(line: String): List<MdSpan> {
    val out = ArrayList<MdSpan>()
    val plain = StringBuilder()
    var i = 0
    fun flush() {
        if (plain.isNotEmpty()) {
            out += MdSpan.Plain(plain.toString())
            plain.clear()
        }
    }
    while (i < line.length) {
        when {
            line.startsWith("**", i) -> {
                val end = line.indexOf("**", i + 2)
                if (end > i + 2) {
                    flush()
                    out += MdSpan.Bold(line.substring(i + 2, end))
                    i = end + 2
                } else {
                    plain.append(line[i])
                    i++
                }
            }

            line[i] == '`' -> {
                val end = line.indexOf('`', i + 1)
                if (end > i + 1) {
                    flush()
                    out += MdSpan.Code(line.substring(i + 1, end))
                    i = end + 1
                } else {
                    plain.append(line[i])
                    i++
                }
            }

            line.startsWith("*", i) && !line.startsWith("**", i) -> {
                val end = line.indexOf('*', i + 1)
                if (end > i + 1 && line[end - 1] != ' ') {
                    flush()
                    out += MdSpan.Italic(line.substring(i + 1, end))
                    i = end + 1
                } else {
                    plain.append(line[i])
                    i++
                }
            }

            else -> {
                plain.append(line[i])
                i++
            }
        }
    }
    flush()
    return out
}

// ---------------------------------------------------------------------------
// 渲染
// ---------------------------------------------------------------------------

/** AI 气泡正文的 Markdown 渲染（段落/标题/列表/代码块，行内样式着色）。 */
@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
) {
    val blocks = remember(text) { parseMarkdown(text) }
    val codeColor = MaterialTheme.colorScheme.onSurface
    val codeBg = MaterialTheme.colorScheme.surface

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Para -> SelectionContainer {
                    Text(
                        text = annotated(block.spans, codeColor, codeBg),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }

                is MdBlock.Heading -> {
                    val style = when (block.level) {
                        1 -> MaterialTheme.typography.titleMedium
                        2 -> MaterialTheme.typography.titleSmall
                        else -> MaterialTheme.typography.bodyMedium
                    }
                    SelectionContainer {
                        Text(
                            text = annotated(block.spans, codeColor, codeBg),
                            style = style,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }

                is MdBlock.Bullet -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    block.items.forEachIndexed { index, spans ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = if (block.ordered) "${index + 1}." else "•",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            SelectionContainer {
                                Text(
                                    text = annotated(spans, codeColor, codeBg),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                            }
                        }
                    }
                }

                is MdBlock.Code -> CodeBlock(lang = block.lang, code = block.code)
            }
        }
    }
}

/** 代码块：顶栏（语言 + 复制）+ 等宽正文（横向滚动）。 */
@Composable
private fun CodeBlock(lang: String, code: String) {
    val clipboard = LocalClipboardManager.current
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 4.dp, top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = lang.ifBlank { "代码" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = { clipboard.setText(AnnotatedString(code)) },
                    modifier = Modifier.padding(0.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = "复制代码",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(4.dp),
                    )
                }
            }
            SelectionContainer {
                Text(
                    text = code,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
    }
}

private fun annotated(
    spans: List<MdSpan>,
    codeColor: androidx.compose.ui.graphics.Color,
    codeBg: androidx.compose.ui.graphics.Color,
): AnnotatedString = buildAnnotatedString {
    spans.forEach { span ->
        when (span) {
            is MdSpan.Plain -> append(span.text)
            is MdSpan.Bold -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(span.text) }
            is MdSpan.Italic -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(span.text) }
            is MdSpan.Code -> withStyle(
                SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    background = codeBg,
                    color = codeColor,
                ),
            ) { append(span.text) }
        }
    }
}
