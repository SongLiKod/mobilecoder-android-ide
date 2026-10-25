package com.mobilecoder.ide.feature.editor

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration

/**
 * 正文里的一段诊断标注（原坐标系，即未折叠 / 未变换的字符偏移）。
 *
 * @param start 起始偏移（含）
 * @param end 结束偏移（不含）
 * @param isError true=错误（红底），false=警告（黄底）
 */
data class IssueRange(val start: Int, val end: Int, val isError: Boolean)

/**
 * 编辑器文本变换：语法高亮 + 文件内查找命中高亮 + 诊断标注 + 代码折叠。
 *
 * 折叠通过删减原文区间实现（区间内文本在渲染时隐藏，替换为「… n 行」标记行），
 * 并提供原文 ↔ 渲染文本的光标映射，保证选区 / 光标 / 点击定位正确。
 */
class EditorVisualTransformation(
    private val highlighter: SyntaxHighlighter,
    private val language: Language,
    private val colors: HighlightColors,
    private val folds: List<AppliedFold>,
    private val findMatches: List<Int>,
    private val findCurrent: Int,
    private val findQueryLength: Int,
    /** 诊断区间（编译错误 / 警告 + 结构级 lint，原坐标系），下划线 + 底色标注。 */
    private val diagnostics: List<IssueRange> = emptyList(),
) : VisualTransformation {

    override fun filter(text: AnnotatedString): TransformedText {
        var annotated = highlighter.highlight(text.text, language)

        if (findMatches.isNotEmpty() && findQueryLength > 0 && findMatches.size <= MAX_FIND_PAINT) {
            val builder = AnnotatedString.Builder(annotated)
            findMatches.forEachIndexed { index, start ->
                val end = (start + findQueryLength).coerceAtMost(annotated.length)
                if (start in 0 until end) {
                    builder.addStyle(
                        SpanStyle(
                            background = if (index == findCurrent) {
                                colors.findCurrent
                            } else {
                                colors.findMatch
                            },
                        ),
                        start,
                        end,
                    )
                }
            }
            annotated = builder.toAnnotatedString()
        }

        // 诊断标注：错误 / 警告各按底色区分，配下划线模拟 IDE 的波浪线
        if (diagnostics.isNotEmpty() && diagnostics.size <= MAX_DIAGNOSTIC_PAINT) {
            val builder = AnnotatedString.Builder(annotated)
            for (range in diagnostics) {
                val start = range.start.coerceIn(0, annotated.length)
                val end = range.end.coerceIn(start, annotated.length)
                if (end <= start) continue
                builder.addStyle(
                    SpanStyle(
                        textDecoration = TextDecoration.Underline,
                        background = if (range.isError) {
                            colors.error.copy(alpha = 0.18f)
                        } else {
                            colors.warning.copy(alpha = 0.20f)
                        },
                    ),
                    start,
                    end,
                )
            }
            annotated = builder.toAnnotatedString()
        }

        if (folds.isEmpty()) return TransformedText(annotated, OffsetMapping.Identity)

        val builder = AnnotatedString.Builder()
        val ranges = ArrayList<Triple<Int, Int, Int>>(folds.size)
        var cursor = 0
        for (fold in folds) {
            if (fold.removedStart < cursor || fold.removedEnd <= fold.removedStart) continue
            builder.append(annotated.subSequence(cursor, fold.removedStart))
            builder.append(markerAnnotated(fold.markerText))
            ranges.add(Triple(fold.removedStart, fold.removedEnd, fold.markerText.length))
            cursor = fold.removedEnd
        }
        builder.append(annotated.subSequence(cursor, annotated.length))
        return TransformedText(builder.toAnnotatedString(), FoldOffsetMapping(ranges))
    }

    private fun markerAnnotated(text: String): AnnotatedString {
        val b = AnnotatedString.Builder()
        b.append(text)
        b.addStyle(
            SpanStyle(color = colors.foldMarker, background = colors.foldMarkerBackground),
            0,
            text.length,
        )
        return b.toAnnotatedString()
    }

    companion object {
        private const val MAX_FIND_PAINT = 2000

        /** 诊断条数上限（[CodeAnalysis] 的 MAX_ISSUES 也是 200，这里留足余量）。 */
        private const val MAX_DIAGNOSTIC_PAINT = 400
    }
}

/**
 * 折叠区间的双向光标映射。
 * 每个区间 [origStart, origEnd) 被长度为 [markerLen] 的标记文本替换。
 */
private class FoldOffsetMapping(
    private val ranges: List<Triple<Int, Int, Int>>,
) : OffsetMapping {

    override fun originalToTransformed(offset: Int): Int {
        var delta = 0
        for ((start, end, marker) in ranges) {
            if (offset <= start) break
            if (offset < end) return start + delta
            delta += marker - (end - start)
        }
        return offset + delta
    }

    override fun transformedToOriginal(offset: Int): Int {
        var delta = 0
        for ((start, end, marker) in ranges) {
            val transformedStart = start + delta
            val transformedEnd = transformedStart + marker
            if (offset <= transformedStart) break
            if (offset < transformedEnd) return start
            delta += marker - (end - start)
        }
        return offset - delta
    }
}
