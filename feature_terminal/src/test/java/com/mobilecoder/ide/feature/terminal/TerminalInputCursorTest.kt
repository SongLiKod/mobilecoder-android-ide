package com.mobilecoder.ide.feature.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 程序化改写输入（粘贴 / 历史回填 / 换行键）的光标位置回归锁。
 *
 * 真机反馈：BasicTextField 改 String 值时内部光标停在原位（多为 0），
 * 粘贴或从历史选择后接着打字会插到文本最前面。修复 = 改用 TextFieldValue，
 * 由 [programmaticInput] 显式把 selection 钉在末尾。
 */
class TerminalInputCursorTest {

    @Test
    fun `历史回填后光标在文本末尾`() {
        val v = programmaticInput("git status")
        assertEquals("git status", v.text)
        assertEquals(v.text.length, v.selection.start)
        assertEquals(v.text.length, v.selection.end)
    }

    @Test
    fun `粘贴多行内容光标在末尾且保留换行剥回车`() {
        val v = programmaticInput("echo a\r\necho b\n")
        assertEquals("echo a\necho b\n", v.text)
        assertEquals(v.text.length, v.selection.start)
        assertTrue(v.selection.collapsed)
    }

    @Test
    fun `超长输入截断后光标仍钉在截断末尾`() {
        val v = programmaticInput("x".repeat(4001))
        assertEquals(4000, v.text.length)
        assertEquals(4000, v.selection.start)
    }

    @Test
    fun `清空输入光标归零`() {
        val v = programmaticInput("")
        assertEquals("", v.text)
        assertEquals(0, v.selection.start)
        assertEquals(0, v.selection.end)
    }
}
