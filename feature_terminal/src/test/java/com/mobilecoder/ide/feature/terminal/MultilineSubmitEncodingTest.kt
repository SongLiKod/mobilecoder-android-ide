package com.mobilecoder.ide.feature.terminal

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 多行提交块 → PTY 文本编码：行终止符统一单个 `\r`（ICRNL→NL 提交一行）、
 * 末行补 CR、末尾已有换行不重复补（避免多出空行）。
 *
 * 关键回归：绝不能编码成 `\r\n` —— CR 已提交一行，紧跟的 LF 在 canonical
 * 模式下会再提交一个空命令，实机表现为每两行之间冒出多余提示符。
 */
class MultilineSubmitEncodingTest {

    @Test
    fun `两行命令各自以单 CR 终止`() {
        assertEquals("echo a\recho b\r", encodeMultilineSubmit("echo a\necho b"))
    }

    @Test
    fun `已有 CRLF 归一为单 CR 不重复提交`() {
        assertEquals("echo a\recho b\r", encodeMultilineSubmit("echo a\r\necho b"))
    }

    @Test
    fun `末尾已有换行不补 CR 不多执行空行`() {
        assertEquals("echo a\r", encodeMultilineSubmit("echo a\n"))
    }

    @Test
    fun `中间空行保留为一次空命令`() {
        assertEquals("a\r\rb\r", encodeMultilineSubmit("a\n\nb"))
    }

    @Test
    fun `连续多个换行原样传达`() {
        assertEquals("a\r\r\rb\r", encodeMultilineSubmit("a\n\n\nb"))
    }
}
