package com.mobilecoder.ide.feature.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 鼠标报告：DECSET ?1000/1002/1003/1006 状态解析 + 滚轮/点击序列编码。
 *
 * 背景：opencode 等 TUI 开启鼠标捕获后期望收到滚轮事件自行滚动；
 * 状态记错（如退出备用屏被误清、RIS 后残留）会让触摸手势发不出去或乱发。
 */
class TerminalMouseTest {

    /** ESC（0x1B）：源码不写字面转义序列。 */
    private val esc = 27.toChar().toString()

    @Test
    fun `1000 开关切换鼠标报告状态`() {
        val e = TerminalEmulator(10, 5)
        assertFalse(e.mouseMode.value.tracking)

        e.feed(esc + "[?1000h")
        assertTrue(e.mouseMode.value.tracking)
        assertFalse(e.mouseMode.value.sgr)

        e.feed(esc + "[?1000l")
        assertFalse(e.mouseMode.value.tracking)
    }

    @Test
    fun `多参数一次开启且 1006 独立开关`() {
        val e = TerminalEmulator(10, 5)
        e.feed(esc + "[?1000;1002;1006h")
        assertTrue(e.mouseMode.value.tracking)
        assertTrue(e.mouseMode.value.sgr)

        // 只关 1000，1002 仍开 → 继续报告
        e.feed(esc + "[?1000l")
        assertTrue(e.mouseMode.value.tracking)

        e.feed(esc + "[?1002l" + esc + "[?1006l")
        assertFalse(e.mouseMode.value.tracking)
        assertFalse(e.mouseMode.value.sgr)
    }

    @Test
    fun `退出备用屏保留鼠标模式且 RIS 全复位清掉`() {
        val e = TerminalEmulator(10, 5)
        e.feed(esc + "[?1000;1006h" + esc + "[?1049h")
        assertTrue(e.mouseMode.value.tracking)

        // 常规退出 TUI（?1049l）：模式归程序自己关，备用屏切换不牵连鼠标状态
        e.feed(esc + "[?1049l")
        assertTrue(e.mouseMode.value.tracking)
        assertTrue(e.mouseMode.value.sgr)

        e.feed(esc + "c") // RIS 全复位
        assertFalse(e.mouseMode.value.tracking)
        assertFalse(e.mouseMode.value.sgr)
    }

    @Test
    fun `SGR 滚轮与释放序列`() {
        assertEquals(
            esc + "[<64;3;5M",
            TerminalMouse.encode(
                sgr = true,
                button = TerminalMouse.WHEEL_UP,
                col = 3,
                row = 5,
                press = true,
            ).decodeToString(),
        )
        assertEquals(
            esc + "[<0;10;20m",
            TerminalMouse.encode(
                sgr = true,
                button = TerminalMouse.LEFT,
                col = 10,
                row = 20,
                press = false,
            ).decodeToString(),
        )
        // SGR 坐标下限 1（0 或负数夹回 1）
        assertEquals(
            esc + "[<65;1;1M",
            TerminalMouse.encode(
                sgr = true,
                button = TerminalMouse.WHEEL_DOWN,
                col = 0,
                row = -3,
                press = true,
            ).decodeToString(),
        )
    }

    @Test
    fun `X10 滚轮序列与坐标夹取`() {
        // CSI M (b+32)(x+32)(y+32)：65+32=97、1+32=33、2+32=34
        val bytes = TerminalMouse.encode(
            sgr = false,
            button = TerminalMouse.WHEEL_DOWN,
            col = 1,
            row = 2,
            press = true,
        )
        assertEquals(6, bytes.size)
        assertEquals(0x1B, bytes[0].toInt())
        assertEquals('['.code, bytes[1].toInt())
        assertEquals('M'.code, bytes[2].toInt())
        assertEquals(97, bytes[3].toInt())
        assertEquals(33, bytes[4].toInt())
        assertEquals(34, bytes[5].toInt())

        // 坐标上限 223（+32 <= 255），越界夹到边界（byte 有符号，按无符号读）
        val big = TerminalMouse.encode(
            sgr = false,
            button = TerminalMouse.LEFT,
            col = 9999,
            row = -5,
            press = true,
        )
        assertEquals(255, big[4].toInt() and 0xFF)
        assertEquals(33, big[5].toInt() and 0xFF)
    }

    @Test
    fun `X10 不支持释放返回空序列`() {
        assertEquals(
            0,
            TerminalMouse.encode(
                sgr = false,
                button = TerminalMouse.LEFT,
                col = 1,
                row = 1,
                press = false,
            ).size,
        )
    }
}
