package com.mobilecoder.ide.core.common.cli

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AptCli.isInProcessLine]：终端 Enter 时的**最终分流**判定
 * （[AptInterceptor] 的 `accept` 钩子，区别于前缀缓冲阶段的 [AptCli.isCliLine]）。
 *
 * 语义：
 *  - `apt` + 已注册子命令（core_common 内置 help / version，其余由 feature 模块注册），
 *    或裸 `apt`（用法输出）→ 进程内执行；
 *  - `apt install curl` 这类**未注册**子命令 → 交回 shell，由 rootfs 里的真 apt 接管
 *    （终端就是完整 Linux 的关键）；
 *  - 其余目标按注册表的 `terminalIntercept` 判定。
 */
class AptCliAcceptTest {

    @Test
    fun registeredSubcommands_stayInProcess() {
        // core_common bootstrap 只注册 help / version（其余子命令在 feature 模块注册，
        // 运行时同样走这里，本测试用内置命令覆盖同一代码路径）
        assertTrue(AptCli.isInProcessLine("apt help"))
        assertTrue(AptCli.isInProcessLine("apt version"))
        assertTrue(AptCli.isInProcessLine("  apt   help  ")) // 多余空白不敏感
        assertTrue(AptCli.isInProcessLine("apt help \"some topic\"")) // 引号被 tokenize 消化
    }

    @Test
    fun bareApt_showsUsageInProcess() {
        // 裸 `apt` = 用法输出，仍是进程内命令（sub == null → true）
        assertTrue(AptCli.isInProcessLine("apt"))
        assertTrue(AptCli.isInProcessLine("   apt   "))
    }

    @Test
    fun unregisteredSubcommands_goToRealApt() {
        // 这些必须交给 rootfs 里的真 apt / apt-get —— 拦下来会既装不了包又误导用户
        assertFalse(AptCli.isInProcessLine("apt install curl"))
        assertFalse(AptCli.isInProcessLine("apt update"))
        assertFalse(AptCli.isInProcessLine("apt upgrade -y"))
        assertFalse(AptCli.isInProcessLine("apt list --installed"))
        // apt-get 不是本前缀（连前缀缓冲都不该拦，这里同样放行）
        assertFalse(AptCli.isInProcessLine("apt-get install curl"))
    }

    @Test
    fun nonCliLines_goToShell() {
        assertFalse(AptCli.isInProcessLine(""))
        assertFalse(AptCli.isInProcessLine("   "))
        assertFalse(AptCli.isInProcessLine("ls -la"))
        assertFalse(AptCli.isInProcessLine("npm install"))
    }

    @Test
    fun prefixBuffer_vs_finalSplit() {
        // 核心契约：前缀缓冲阶段照旧吃进本地回显（命中 apt 前缀），
        // 但 Enter 分流时未注册子命令必须交回 shell —— 两者故意不一致
        assertTrue("缓冲阶段拦截 apt 前缀", AptCli.isCliLine("apt install curl"))
        assertFalse("分流阶段放行真 apt", AptCli.isInProcessLine("apt install curl"))

        assertTrue(AptCli.isCliLine("apt help"))
        assertTrue(AptCli.isInProcessLine("apt help"))
    }
}
