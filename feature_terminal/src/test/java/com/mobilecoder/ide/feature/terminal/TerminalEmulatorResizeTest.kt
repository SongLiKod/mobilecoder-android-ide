package com.mobilecoder.ide.feature.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 窗口尺寸变化测试：缩列不丢行尾字符、缩行不丢光标行。
 *
 * 背景：终端渲染区高度会随软键盘 / 顶底栏动画实时变化，宽度会随字号、旋转变化，
 * 旧实现缩行时「保留顶部、丢底部」会把光标行（提示符）一起丢掉，缩列时
 * `TerminalRow.resize` 会直接截断行尾 —— 表现都是「显示不完整、缺字符」。
 */
class TerminalEmulatorResizeTest {

    /** 行文本（光标所在行会带出光标那一格的空格，去掉便于断言）。 */
    private fun lineText(e: TerminalEmulator, index: Int): String =
        e.lineAt(index).runs.joinToString("") { it.text }.trimEnd()

    private fun allText(e: TerminalEmulator): String =
        (0 until e.lineCount()).joinToString("") { lineText(e, it) }.replace(" ", "")

    // ---------------------------------------------------------------- 缩行

    @Test
    fun `行数收缩时光标行留在屏内且顶部行进 scrollback`() {
        val e = TerminalEmulator(10, 6)
        e.feed("aaa\r\nbbb\r\nccc\r\nddd\r\neee\r\nfff") // 光标停在最后一行

        e.resize(10, 3)

        // 光标行（提示符 fff）必须还在屏上，前面的行只是挪进 scrollback，不丢
        assertEquals(6, e.lineCount())
        assertEquals("aaa", lineText(e, 0))
        assertEquals("ccc", lineText(e, 2))
        assertEquals("ddd", lineText(e, 3))
        assertEquals("fff", lineText(e, 5))
        assertEquals(3 + 2, e.cursorLineIndex())
        assertEquals(3, e.lineAt(e.cursorLineIndex()).cursorCol)
        assertEquals("aaabbbcccdddeeefff", allText(e))
    }

    @Test
    fun `行数收缩时光标行未到底部则只丢底部空行`() {
        val e = TerminalEmulator(10, 6)
        e.feed("aaa\r\nbbb") // 光标在第 2 行

        e.resize(10, 3)

        assertEquals("aaabbb", allText(e))
        assertEquals(1, e.cursorLineIndex())
        assertEquals(3, e.lineAt(e.cursorLineIndex()).cursorCol)
    }

    @Test
    fun `行数放大追加空行且已有内容不变`() {
        val e = TerminalEmulator(10, 3)
        e.feed("aaa\r\nbbb")

        e.resize(10, 6)

        assertEquals(6, e.lineCount())
        assertEquals("aaa", lineText(e, 0))
        assertEquals("bbb", lineText(e, 1))
        assertEquals("", lineText(e, 5))
        assertEquals(1, e.cursorLineIndex())
    }

    // ---------------------------------------------------------------- 缩列

    @Test
    fun `列数收缩把满行拆成多行而不是截断`() {
        val e = TerminalEmulator(10, 4)
        e.feed("0123456789ABCDEFGHIJ") // 两行各 10 字符，正好占满

        e.resize(4, 4)

        // 20 个字符一个都不能少：scrollback 2 行 + 屏内 4 行
        assertEquals(6, e.lineCount())
        assertEquals("0123", lineText(e, 0))
        assertEquals("4567", lineText(e, 1))
        assertEquals("89", lineText(e, 2))
        assertEquals("ABCD", lineText(e, 3))
        assertEquals("EFGH", lineText(e, 4))
        assertEquals("IJ", lineText(e, 5))
        assertEquals("0123456789ABCDEFGHIJ", allText(e))
    }

    @Test
    fun `列数收缩后光标落在正确行与列`() {
        val e = TerminalEmulator(10, 4)
        e.feed("0123456789ABCDEFGHIJ") // 光标在 'J' 上（row1 col9，待换行）

        e.resize(4, 4)

        assertEquals(2 + 3, e.cursorLineIndex())
        assertEquals("IJ", lineText(e, e.cursorLineIndex()))
        assertEquals(1, e.lineAt(e.cursorLineIndex()).cursorCol)
    }

    @Test
    fun `列数收缩时行尾只有空格则不拆出空行`() {
        val e = TerminalEmulator(10, 3)
        e.feed("abc")

        e.resize(4, 3)

        assertEquals(3, e.lineCount())
        assertEquals("abc", lineText(e, 0))
        assertEquals(0, e.cursorLineIndex())
    }

    @Test
    fun `列数放大不改变已有内容`() {
        val e = TerminalEmulator(10, 3)
        e.feed("0123456789ABCDEFGHIJ")

        e.resize(20, 3)

        assertEquals(3, e.lineCount())
        assertEquals("0123456789", lineText(e, 0))
        assertEquals("ABCDEFGHIJ", lineText(e, 1))
        assertEquals("0123456789ABCDEFGHIJ", allText(e))
    }

    // ---------------------------------------------------------------- 连续变化

    @Test
    fun `键盘反复弹出收起不会累积丢失内容`() {
        val e = TerminalEmulator(10, 6)
        e.feed("aaa\r\nbbb\r\nccc\r\nddd\r\neee\r\nfff")
        val before = allText(e)

        // 模拟软键盘弹出（行数变少）→ 收起（行数还原）
        e.resize(10, 3)
        e.resize(10, 6)

        val after = allText(e)
        assertTrue("收缩后再放大不应丢内容：$before -> $after", after.contains("fff"))
        assertTrue(after.contains("aaa"))
        // 收缩挪进 scrollback 的行不会被拉回来，但内容一条不少
        assertEquals(9, e.lineCount())
        assertEquals("aaabbbcccdddeeefff", after)
        // 光标行仍停在提示符上
        assertEquals("fff", lineText(e, e.cursorLineIndex()))
        assertEquals(3, e.lineAt(e.cursorLineIndex()).cursorCol)
    }
}
