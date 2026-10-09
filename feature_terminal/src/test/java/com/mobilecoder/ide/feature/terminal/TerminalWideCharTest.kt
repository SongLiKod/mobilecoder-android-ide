package com.mobilecoder.ide.feature.terminal

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 双宽字符（中文 / emoji）栅格对齐测试。
 *
 * 背景：opencode 等 TUI 按 wcwidth 把中文算 2 列、emoji 算 2 列。旧实现每字符只
 * 推进 1 格，程序用绝对定位（CSI H）重绘时列号与网格错位，旧字符残留在缝隙里 ——
 * 真机表现就是「中英交错的文字重叠」（导出日志里可见 `第 一t步s` 这类网格错乱）。
 *
 * 修复后的约定：双宽字占 2 格（字符 + 续格占位符 CELL_CONTINUATION）、代理对两
 * Char 各占一格、零宽字符跳过不占格。这些测试锁住该约定与相关边角。
 */
class TerminalWideCharTest {

    /** 续格占位符（与模拟器约定一致）。 */
    private val cont = "\u0000"

    private val esc = "\u001b"

    /** 行原始文本（含续格占位符），直接断言栅格布局用。 */
    private fun rawLine(e: TerminalEmulator, index: Int): String =
        e.lineAt(index).runs.joinToString("") { it.text }.trimEnd()

    /** 行显示文本（剔除续格占位符；选中复制拿到的也是这份文本）。 */
    private fun lineText(e: TerminalEmulator, index: Int): String =
        rawLine(e, index).replace(cont, "")

    // ---------------------------------------------------------------- 基本占格

    @Test
    fun `中文占两格且光标推进两格`() {
        val e = TerminalEmulator(10, 3)
        e.feed("中文")

        assertEquals("中${cont}文$cont", rawLine(e, 0))
        assertEquals("中文", lineText(e, 0))
        assertEquals(4, e.cursorCol)
    }

    @Test
    fun `中英混排栅格列号与 TUI 的 wcwidth 一致`() {
        val e = TerminalEmulator(10, 3)
        e.feed("中a文b") // 中(0,1) a(2) 文(3,4) b(5)

        assertEquals("中${cont}a文${cont}b", rawLine(e, 0))
        assertEquals(6, e.cursorCol)
    }

    @Test
    fun `绝对定位重绘落在正确列不留残影`() {
        // 真实故障形态：TUI 按「每个字占 2 列」逐段绝对定位重绘（项目/分析 …）
        val e = TerminalEmulator(20, 3)
        e.feed("项")
        e.feed("$esc[1;3H目") // TUI 认为「项」占 2 列 → 从 col2 写「目」
        e.feed("$esc[1;5H分析") // 再从 col4 写「分析」

        assertEquals("项${cont}目${cont}分${cont}析$cont", rawLine(e, 0))
        assertEquals("项目分析", lineText(e, 0))
        assertEquals(8, e.cursorCol)
    }

    // ---------------------------------------------------------------- 覆盖清理

    @Test
    fun `窄字覆盖双宽字首格时连带清掉续格`() {
        val e = TerminalEmulator(10, 3)
        e.feed("中文") // 中(0,1) 文(2,3)
        e.feed("$esc[1;3HX") // 落在「文」首格

        assertEquals("中${cont}X", rawLine(e, 0))
        assertEquals("中X", lineText(e, 0))
        assertEquals(3, e.cursorCol)
    }

    @Test
    fun `窄字覆盖双宽字续格时连带清掉首格`() {
        val e = TerminalEmulator(10, 3)
        e.feed("中文")
        e.feed("$esc[1;2HX") // 落在「中」续格

        assertEquals(" X文$cont", rawLine(e, 0))
        assertEquals(" X文", lineText(e, 0))
        assertEquals(2, e.cursorCol)
    }

    // ---------------------------------------------------------------- 行尾换行

    @Test
    fun `行尾放不下双宽字时整字换行不留半截`() {
        val e = TerminalEmulator(4, 3)
        e.feed("ab中文") // 中正好占满 (2,3)，文换到下一行

        assertEquals("ab中$cont", rawLine(e, 0))
        assertEquals("文$cont", rawLine(e, 1))
        assertEquals(1, e.cursorRow)
        assertEquals(2, e.cursorCol)
    }

    @Test
    fun `行尾只剩一格时双宽字整体绕行`() {
        val e = TerminalEmulator(5, 3)
        e.feed("abcd中") // col4 只剩 1 格，放不下 2 格

        assertEquals("abcd", rawLine(e, 0))
        assertEquals("中$cont", rawLine(e, 1))
        assertEquals(1, e.cursorRow)
        assertEquals(2, e.cursorCol)
    }

    // ---------------------------------------------------------------- 回滚

    @Test
    fun `erasePrinted 回滚中文按整字擦除`() {
        val e = TerminalEmulator(10, 3)
        e.feed("a中b") // a(0) 中(1,2) b(3)
        assertEquals(4, e.cursorCol)

        // 拦截器对中文回滚计 1 个字符：连同续格整字擦掉，不留空格
        e.erasePrinted(2)
        assertEquals("a", lineText(e, 0))
        assertEquals(1, e.cursorCol)

        e.erasePrinted(1)
        assertEquals("", lineText(e, 0))
        assertEquals(0, e.cursorCol)
    }

    @Test
    fun `emoji 代理对占两格且按两字符回滚`() {
        val e = TerminalEmulator(10, 3)
        e.feed("a\uD83D\uDE00") // a(0) emoji 两 Char 各占一格 (1,2)

        assertEquals(3, e.cursorCol)
        assertEquals("a\uD83D\uDE00", rawLine(e, 0))

        // 拦截器按 Char 计数 → emoji 回滚 2
        e.erasePrinted(3)
        assertEquals("", lineText(e, 0))
        assertEquals(0, e.cursorCol)
    }

    // ---------------------------------------------------------------- 零宽 / 插入

    @Test
    fun `零宽组合字符跳过不占格不破坏列对齐`() {
        val e = TerminalEmulator(10, 3)
        e.feed("a\u0301") // a + 组合尖音符 (Mn，wcwidth=0)

        assertEquals(1, e.cursorCol)
        assertEquals("a", lineText(e, 0))
    }

    @Test
    fun `插入模式下双宽字保持完整`() {
        val e = TerminalEmulator(10, 3)
        e.feed("ab")
        e.feed("$esc[4h") // SM4：插入模式
        e.feed("$esc[1;2H中") // 在 col1 插入 2 格再落字

        assertEquals("a中${cont}b", rawLine(e, 0))
        assertEquals(3, e.cursorCol)
    }

    // ---------------------------------------------------------------- 缩列

    @Test
    fun `缩列拆行不把双宽字劈成两半`() {
        val e = TerminalEmulator(6, 3)
        e.feed("abcd中文") // 行 1：abcd中(4,5)；行 2：文(0,1)

        e.resize(5, 3)

        assertEquals("abcd", lineText(e, 0))
        assertEquals("中", lineText(e, 1))
        assertEquals("文", lineText(e, 2))
        // 光标行仍是「文」那行，且停在其后
        assertEquals("文", lineText(e, e.cursorLineIndex()))
        assertEquals(2, e.lineAt(e.cursorLineIndex()).cursorCol)
    }
}
