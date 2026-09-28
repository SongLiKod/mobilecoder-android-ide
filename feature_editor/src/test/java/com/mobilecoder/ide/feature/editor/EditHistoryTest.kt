package com.mobilecoder.ide.feature.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [EditHistory] 撤销 / 重做与打字合并（coalescing）行为。 */
class EditHistoryTest {

    @Test
    fun `连续单字符输入合并为一步`() {
        val history = EditHistory<String>()
        history.record("", delta = 1, now = 1_000)
        history.record("a", delta = 1, now = 1_100)
        history.record("ab", delta = 1, now = 1_200)

        assertTrue(history.canUndo)
        val undone = history.undo("abc")
        assertEquals("", undone)

        // 重做回到整段输入之后
        assertEquals("abc", history.redo(undone!!))
        assertTrue(history.canUndo) // 仍可再次撤销回 ""
        assertEquals("", history.undo("abc"))
    }

    @Test
    fun `超过合并窗口的输入分成多步`() {
        val history = EditHistory<String>()
        history.record("", delta = 1, now = 1_000)
        history.record("a", delta = 1, now = 1_900) // 900ms > 800ms，独立成步

        assertEquals("a", history.undo("ab"))
        assertEquals("", history.undo("a"))
        assertNull(history.undo(""))
    }

    @Test
    fun `粘贴等大改动独立成步`() {
        val history = EditHistory<String>()
        history.record("", delta = 5, now = 1_000) // 粘贴 "hello"

        assertEquals("", history.undo("hello"))
        assertFalse(history.canUndo)
    }

    @Test
    fun `新改动清空重做栈`() {
        val history = EditHistory<String>()
        history.record("", delta = 1, now = 1_000)
        val undone = history.undo("a")
        assertTrue(history.canRedo)

        history.record(undone!!, delta = 3, now = 5_000)
        assertFalse(history.canRedo)
        assertNull(history.redo("new"))
    }

    @Test
    fun `撤销打断合并窗口，随后输入独立成步`() {
        val history = EditHistory<String>()
        history.record("", delta = 1, now = 1_000)
        history.record("a", delta = 1, now = 5_000)

        val target = history.undo("ab")
        assertEquals("a", target)

        // 撤销后 100ms 内继续输入：不应与历史里的键入合并
        history.record(target!!, delta = 1, now = 5_100)
        assertEquals("a", history.undo("ax"))
        assertEquals("", history.undo("a"))
    }

    @Test
    fun `超出容量丢弃最早一步`() {
        val history = EditHistory<String>(limit = 3)
        for (i in 0..5) {
            history.record("s$i", delta = 5, now = 1_000L * (i + 1))
        }

        assertEquals("s5", history.undo("current"))
        assertEquals("s4", history.undo("s5"))
        assertEquals("s3", history.undo("s4"))
        assertNull(history.undo("s3")) // 最早的 s0~s2 已被挤出
    }

    @Test
    fun `clear 清空全部历史`() {
        val history = EditHistory<String>()
        history.record("", delta = 1, now = 1_000)
        history.undo("a")

        history.clear()
        assertFalse(history.canUndo)
        assertFalse(history.canRedo)
        assertNull(history.undo("x"))
    }
}
