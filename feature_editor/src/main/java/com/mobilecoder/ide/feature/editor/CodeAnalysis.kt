package com.mobilecoder.ide.feature.editor

/**
 * 实时代码检查问题（PRD 2.2「实时代码报错」，纯静态扫描，不依赖外部编译器）。
 *
 * @param line 1 起始行号
 * @param column 1 起始列号
 * @param isError true=错误（红），false=警告（黄）
 */
data class EditorIssue(
    val line: Int,
    val column: Int,
    val message: String,
    val isError: Boolean,
)

/** 可折叠区域（1 起始行号，[endLine] > [startLine]，两端行保留可见）。 */
data class FoldRegion(val startLine: Int, val endLine: Int)

/** 结构分析结果：报错列表 + 可折叠区域。 */
data class CodeAnalysisResult(
    val issues: List<EditorIssue>,
    val folds: List<FoldRegion>,
) {
    val issueLines: Set<Int> get() = issues.mapTo(HashSet()) { it.line }
}

/** 已生效的折叠（非重叠、外层优先，并算出原文删减区间与折叠标记文案）。 */
data class AppliedFold(
    val region: FoldRegion,
    val removedStart: Int,
    val removedEnd: Int,
    val markerText: String,
)

private const val MAX_ISSUES = 200
private const val MAX_ANALYZE_CHARS = 500_000

private const val NONE = '\u0000'

/**
 * 静态结构扫描：
 * - 括号 / 花括号配对错误（多余、缺失、不匹配）
 * - 未闭合字符串、未闭合块注释
 * - Git 合并冲突标记（<<<<<<< / ======= / >>>>>>>）
 * - 折叠区域：成对花括号与跨行块注释
 */
fun analyzeCode(text: String, language: Language): CodeAnalysisResult {
    if (text.isEmpty()) return CodeAnalysisResult(emptyList(), emptyList())
    if (text.length > MAX_ANALYZE_CHARS) return CodeAnalysisResult(emptyList(), emptyList())

    val issues = ArrayList<EditorIssue>()
    val folds = ArrayList<FoldRegion>()
    val hasConflict = text.contains("<<<<<<<")
    val lineComments = SyntaxHighlighter.lineCommentsOf(language)
    val blockOpen: String?
    val blockClose: String?
    if (language == Language.XML) {
        blockOpen = "<!--"
        blockClose = "-->"
    } else if (language == Language.JSON || language == Language.PROPERTIES) {
        blockOpen = null
        blockClose = null
    } else {
        blockOpen = "/*"
        blockClose = "*/"
    }
    val triple = language == Language.KOTLIN
    val charLiterals = language in setOf(
        Language.KOTLIN, Language.JAVA, Language.JAVASCRIPT, Language.TYPESCRIPT, Language.DART,
    )

    val n = text.length
    var i = 0
    var line = 1
    var col = 1
    var lineHasContent = false

    class Bracket(val ch: Char, val line: Int, val col: Int)

    val stack = ArrayList<Bracket>()
    var stringQuote = NONE
    var stringLine = 0
    var stringCol = 0
    var inTriple = false
    var inBlock = false
    var blockLine = 0
    var blockCol = 0
    var commentOpenLine = -1

    fun pushIssue(issue: EditorIssue) {
        if (issues.size < MAX_ISSUES) issues.add(issue)
    }

    while (i < n) {
        val c = text[i]

        if (c == '\n') {
            if (stringQuote != NONE && !inTriple) {
                pushIssue(EditorIssue(stringLine, stringCol, "字符串未闭合", true))
                stringQuote = NONE
            }
            line++
            col = 1
            lineHasContent = false
            i++
            continue
        }

        // 行首：合并冲突标记
        if (!lineHasContent && col == 1 && hasConflict) {
            val marker = when {
                text.startsWith("<<<<<<<", i) -> "冲突开始标记 <<<<<<<"
                text.startsWith(">>>>>>>", i) -> "冲突结束标记 >>>>>>>"
                text.startsWith("=======", i) &&
                    (i + 7 >= n || text[i + 7] == '\n' || text[i + 7] == ' ') ->
                    "冲突分隔标记 ======="
                else -> null
            }
            if (marker != null) {
                pushIssue(EditorIssue(line, 1, "检测到合并冲突标记（$marker）", false))
                lineHasContent = true
                while (i < n && text[i] != '\n') {
                    i++
                    col++
                }
                continue
            }
        }

        if (inBlock) {
            if (blockClose != null && text.startsWith(blockClose, i)) {
                if (commentOpenLine in 1 until line) {
                    folds.add(FoldRegion(commentOpenLine, line))
                }
                inBlock = false
                commentOpenLine = -1
                val step = blockClose.length
                i += step
                col += step
                lineHasContent = true
            } else {
                i++
                col++
            }
            continue
        }

        if (stringQuote != NONE) {
            if (c == '\\') {
                i += 2
                col += 2
                lineHasContent = true
                continue
            }
            if (inTriple && text.startsWith("\"\"\"", i)) {
                stringQuote = NONE
                inTriple = false
                i += 3
                col += 3
                lineHasContent = true
                continue
            }
            if (!inTriple && c == stringQuote) {
                stringQuote = NONE
                i++
                col++
                lineHasContent = true
                continue
            }
            i++
            col++
            lineHasContent = true
            continue
        }

        // 注释开始
        if (blockOpen != null && text.startsWith(blockOpen, i)) {
            inBlock = true
            commentOpenLine = line
            blockLine = line
            blockCol = col
            val step = blockOpen.length
            i += step
            col += step
            lineHasContent = true
            continue
        }
        if (lineComments.any { text.startsWith(it, i) }) {
            lineHasContent = true
            while (i < n && text[i] != '\n') {
                i++
                col++
            }
            continue
        }

        when {
            triple && text.startsWith("\"\"\"", i) -> {
                stringQuote = '"'
                inTriple = true
                i += 3
                col += 3
                lineHasContent = true
            }
            c == '"' || (c == '\'' && (charLiterals || language == Language.SHELL)) -> {
                stringQuote = c
                stringLine = line
                stringCol = col
                i++
                col++
                lineHasContent = true
            }
            c == '(' || c == '{' || c == '[' -> {
                stack.add(Bracket(c, line, col))
                i++
                col++
                lineHasContent = true
            }
            c == ')' || c == '}' || c == ']' -> {
                val expected = matchingOpen(c)
                val top = if (stack.isEmpty()) null else stack[stack.size - 1]
                if (top != null && top.ch == expected) {
                    stack.removeAt(stack.size - 1)
                    if (c == '}' && top.line < line) {
                        folds.add(FoldRegion(top.line, line))
                    }
                } else {
                    pushIssue(
                        if (top == null) {
                            EditorIssue(line, col, "多余的「$c」，没有匹配的开符号", true)
                        } else {
                            EditorIssue(
                                line, col,
                                "括号不匹配：期望「${closingOf(top.ch)}」，实际「$c」", true,
                            )
                        },
                    )
                    if (top != null) stack.removeAt(stack.size - 1)
                }
                i++
                col++
                lineHasContent = true
            }
            else -> {
                if (!c.isWhitespace()) lineHasContent = true
                i++
                col++
            }
        }
    }

    if (stringQuote != NONE) {
        pushIssue(EditorIssue(stringLine, stringCol, "字符串未闭合", true))
    }
    if (inBlock) {
        pushIssue(EditorIssue(blockLine, blockCol, "块注释未闭合", true))
    }
    for (bracket in stack.takeLast(20)) {
        pushIssue(
            EditorIssue(bracket.line, bracket.col, "缺少「${closingOf(bracket.ch)}」", true),
        )
    }

    folds.sortWith(compareBy({ it.startLine }, { -(it.endLine - it.startLine) }))
    return CodeAnalysisResult(issues, folds)
}

/** 计算行首下标数组（第 k 行 = 下标 k-1，末尾追加 text.length）。 */
fun lineStartsOf(text: String): IntArray {
    val count = text.count { it == '\n' } + 1
    val starts = IntArray(count + 1)
    var index = 0
    for (line in 0 until count) {
        starts[line] = index
        val next = text.indexOf('\n', index)
        if (next < 0) break
        index = next + 1
    }
    starts[count] = text.length
    return starts
}

/**
 * 依据折叠开关生成实际生效的折叠列表（非重叠、外层优先），
 * 并算出原文删减区间。折叠后隐藏 start+1 ~ end 行，在 start 行后插入标记行。
 */
fun buildAppliedFolds(
    text: String,
    regions: List<FoldRegion>,
    collapsed: Set<Int>,
): List<AppliedFold> {
    if (collapsed.isEmpty() || regions.isEmpty() || text.isEmpty()) return emptyList()
    val starts = lineStartsOf(text)
    val lineCount = starts.size - 1
    val out = ArrayList<AppliedFold>()
    var lastEnd = 0
    for (region in regions) {
        if (region.startLine !in collapsed) continue
        if (region.startLine <= lastEnd) continue
        if (region.startLine + 1 > lineCount) continue
        val removedStart = starts[region.startLine + 1]
        val removedEnd = if (region.endLine < lineCount) {
            starts[region.endLine + 1] - 1
        } else {
            text.length
        }
        if (removedEnd <= removedStart) continue
        val hidden = region.endLine - region.startLine
        out.add(
            AppliedFold(
                region = region,
                removedStart = removedStart,
                removedEnd = removedEnd,
                markerText = "… $hidden 行",
            ),
        )
        lastEnd = region.endLine
    }
    return out
}

/**
 * 行号槽单元格（与折叠后的可视行一一对应）。
 * [line] 为 0 表示这是折叠标记行。
 */
data class GutterCell(
    val line: Int,
    val numberText: String,
    val content: String,
    val foldable: Boolean,
    val markerFold: FoldRegion?,
    val hiddenLines: Int,
)

/** 折叠后生成可视行单元格；无生效折叠时返回 null（调用方按原行直接映射）。 */
fun buildGutterCells(
    text: String,
    analysis: CodeAnalysisResult,
    appliedFolds: List<AppliedFold>,
): List<GutterCell>? {
    if (appliedFolds.isEmpty()) return null
    val lines = text.split('\n')
    val byStart = appliedFolds.associateBy { it.region.startLine }
    val foldableStarts = analysis.folds.mapTo(HashSet()) { it.startLine }
    val out = ArrayList<GutterCell>(lines.size + appliedFolds.size)
    var i = 0
    while (i < lines.size) {
        val number = i + 1
        val fold = byStart[number]
        if (fold != null) {
            out.add(
                GutterCell(
                    line = number,
                    numberText = number.toString(),
                    content = lines[i],
                    foldable = true,
                    markerFold = null,
                    hiddenLines = 0,
                ),
            )
            out.add(
                GutterCell(
                    line = 0,
                    numberText = "…",
                    content = fold.markerText,
                    foldable = false,
                    markerFold = fold.region,
                    hiddenLines = fold.region.endLine - fold.region.startLine,
                ),
            )
            i = fold.region.endLine
        } else {
            out.add(
                GutterCell(
                    line = number,
                    numberText = number.toString(),
                    content = lines[i],
                    foldable = number in foldableStarts,
                    markerFold = null,
                    hiddenLines = 0,
                ),
            )
            i++
        }
    }
    return out
}

internal fun matchingOpen(close: Char): Char = when (close) {
    ')' -> '('
    '}' -> '{'
    else -> '['
}

internal fun closingOf(open: Char): Char = when (open) {
    '(' -> ')'
    '{' -> '}'
    else -> ']'
}
