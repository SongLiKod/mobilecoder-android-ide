package com.mobilecoder.ide.feature.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 查找 / 替换纯函数（[findAll] / [replaceAllIn] / [replaceRange] / [remapOffset]）
 * 与诊断标注折算（[issueRangesOf]）的口径回归：
 *
 *  1. **大小写不敏感 + 命中互不重叠**（与编辑器 FindBar 行为一致）；
 *  2. 替换文本是**字面量**（不走正则 / 不做变量展开）；
 *  3. 全部替换后光标按前面命中的位移重映射；
 *  4. 诊断从「行 : 列」折算成正文偏移区间，越界裁剪 / 跳过。
 */
class FindReplaceTest {

    // ---- findAll ----

    @Test
    fun `查找大小写不敏感且命中互不重叠`() {
        assertEquals(listOf(0, 3, 6), findAll("Aa aa aAa", "aa"))
        // "aaaa" 里 "aa" 从 0 命中后跳过 query 长度 → 只剩 2
        assertEquals(listOf(0, 2), findAll("aaaa", "aa"))
    }

    @Test
    fun `查找上限截断 - 超出 limit 即停`() {
        assertEquals(3, findAll("aaaaaaaa", "a", limit = 3).size)
        assertEquals(8, findAll("aaaaaaaa", "a").size)
    }

    @Test
    fun `空查询或空正文返回空`() {
        assertEquals(emptyList<Int>(), findAll("abc", ""))
        assertEquals(emptyList<Int>(), findAll("", "a"))
        assertEquals(emptyList<Int>(), findAll("abc", "zzz"))
    }

    // ---- replaceAllIn ----

    @Test
    fun `全部替换命中处并计数 - 大小写按原文保留`() {
        val out = replaceAllIn("Hello hello HELLO", "hello", "hi")
        assertEquals("hi hi hi", out.text)
        assertEquals(3, out.count)
    }

    @Test
    fun `替换文本是字面量 - 不做正则与变量展开`() {
        // "." 若走正则会命中每一个字符；"$" 若被展开会丢失
        val out = replaceAllIn("a.b", ".", "X\$Y")
        assertEquals("aX\$Yb", out.text)
        assertEquals(1, out.count)
    }

    @Test
    fun `无命中或空查询时原样返回`() {
        assertEquals(ReplaceOutcome("abc", 0), replaceAllIn("abc", "zz", "x"))
        assertEquals(ReplaceOutcome("abc", 0), replaceAllIn("abc", "", "x"))
    }

    // ---- replaceRange ----

    @Test
    fun `单处替换按下标区间切分`() {
        assertEquals("hello there", replaceRange("hello world", 6, 5, "there"))
        assertEquals("goodbye world", replaceRange("hello world", 0, 5, "goodbye"))
    }

    @Test
    fun `单处替换越界自动裁剪`() {
        assertEquals("abcX", replaceRange("abc", 5, 3, "X"))
        assertEquals("Xabc", replaceRange("abc", -2, 1, "X"))
        assertEquals("a", replaceRange("abc", 1, 99, ""))
    }

    // ---- remapOffset ----

    @Test
    fun `全部替换后光标按前缀命中的位移重映射`() {
        val matches = listOf(0, 3, 6) // query 长 2，replacement 长 4 → 每处 +2
        assertEquals(0, remapOffset(matches, 2, "xxxx", 0))
        // offset 4 之前有 0 与 3 两处命中 → +4
        assertEquals(8, remapOffset(matches, 2, "xxxx", 4))
        // 三处命中都在前面 → +6
        assertEquals(16, remapOffset(matches, 2, "xxxx", 10))
        // 缩短替换同样成立（每处 -2，前面两处 → -4）
        assertEquals(4, remapOffset(matches, 2, "x", 6))
        // 负偏移不会把光标推到负数
        assertEquals(0, remapOffset(matches, 2, "xxxx", -1))
    }

    // ---- issueRangesOf（诊断 → 正文标注区间）----

    private val text = "aaaa\nbbbb\ncccc" // 长度 14，行起点 0 / 5 / 10

    @Test
    fun `诊断折算成从问题列到行尾的区间`() {
        val ranges = issueRangesOf(
            text,
            listOf(EditorIssue(line = 2, column = 2, message = "boom", isError = true)),
        )
        assertEquals(listOf(IssueRange(6, 9, isError = true)), ranges)
    }

    @Test
    fun `列越界裁剪到行尾 - 行号越界直接丢弃`() {
        val ranges = issueRangesOf(
            text,
            listOf(
                EditorIssue(line = 1, column = 100, message = "col", isError = false),
                EditorIssue(line = 99, column = 1, message = "line", isError = true),
            ),
        )
        // 第 1 行行尾是 4，列越界收在 4..5；第 99 行没有内容 → 不产出区间
        assertEquals(listOf(IssueRange(4, 5, isError = false)), ranges)
    }

    @Test
    fun `多处诊断按起始下标排序 - 空输入返回空`() {
        val ranges = issueRangesOf(
            text,
            listOf(
                EditorIssue(line = 3, column = 1, message = "c", isError = true),
                EditorIssue(line = 1, column = 3, message = "a", isError = false),
                EditorIssue(line = 2, column = 1, message = "b", isError = true),
            ),
        )
        assertEquals(listOf(2, 5, 10), ranges.map { it.start })
        assertEquals(listOf(false, true, true), ranges.map { it.isError })
        assertEquals(emptyList<IssueRange>(), issueRangesOf(text, emptyList()))
        assertEquals(emptyList<IssueRange>(), issueRangesOf("", listOf(EditorIssue(1, 1, "x", true))))
    }

    @Test
    fun `标注区间覆盖完整行且不越出正文`() {
        val ranges = issueRangesOf(text, listOf(EditorIssue(line = 3, column = 1, message = "x", isError = true)))
        assertEquals(1, ranges.size)
        val range = ranges.single()
        assertTrue(range.start >= 10 && range.end <= text.length && range.end > range.start)
    }
}
