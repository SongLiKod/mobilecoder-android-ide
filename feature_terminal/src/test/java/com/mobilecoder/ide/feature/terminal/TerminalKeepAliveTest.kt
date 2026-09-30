package com.mobilecoder.ide.feature.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * [TerminalForegroundService] 的纯逻辑：通知正文计数与「不撞车」常量约定。
 *
 * 服务自身的生命周期（startForeground/stopSelf、渠道创建）依赖 Android 框架，
 * 走真机链路验证；这里只覆盖可在 JVM 上确定性断言的部分。
 */
class TerminalKeepAliveTest {

    // ------------------------------------------------------------------
    // keepAliveText（通知正文）
    // ------------------------------------------------------------------

    @Test
    fun keepAliveText_carriesSessionCount() {
        assertEquals("1 个会话运行中", keepAliveText(1))
        assertEquals("3 个会话运行中", keepAliveText(3))
    }

    @Test
    fun keepAliveText_zeroIsClampedToOne() {
        // 防御性下限：0 不该出现在正文中（会话数为 0 时调用方走 stop，不发正文）
        assertEquals("1 个会话运行中", keepAliveText(0))
        assertEquals("1 个会话运行中", keepAliveText(-2))
    }

    // ------------------------------------------------------------------
    // 与构建前台服务（BuildForegroundService）互不干扰
    // ------------------------------------------------------------------

    @Test
    fun channelAndNotificationId_doNotCollideWithBuildService() {
        // BuildForegroundService: 渠道 mobilecoder_build / 通知 ID 4207。
        // 两者可同时存在（构建 + 终端各自常驻），渠道或 ID 相同会互相顶掉通知。
        assertNotEquals("mobilecoder_build", TerminalForegroundService.CHANNEL_ID)
        assertNotEquals(4207, TerminalForegroundService.NOTIFICATION_ID)
    }
}
