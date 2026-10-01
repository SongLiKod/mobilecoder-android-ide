package com.mobilecoder.ide.core.common.cli

import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CLI「停止」能力（PRD 2.4：命令执行中可随时停止）。
 *
 * 之前命令跑起来后没有任何取消入口：`_running` 只有等 handler 返回才复位，
 * 网络命令卡住时终端一直转圈、Ctrl+C 也无效。[AptCli.cancelCurrent]
 * 是唯一的出口，这里验证：
 *  - 取消后 handler 不再往下走、回显「命令已取消」；
 *  - 取消钩子（feature_git 中止 native 传输）被调用；
 *  - 执行状态复位，后续命令可继续排队执行。
 */
class AptCliCancelTest {

    @Test
    fun `取消执行中的命令——停止 handler 并复位状态`() = runBlocking {
        val outputs = CopyOnWriteArrayList<String>()
        var reachedEnd = false
        AptCli.register(
            CliCommand(
                name = "canceltest-slow",
                summary = "测试用长任务",
            ) { _, _, emit ->
                emit("started")
                delay(30_000) // 模拟卡住的命令（如网络请求）
                reachedEnd = true
                emit("finished")
                0
            },
        )

        var hookCalled = false
        AptCli.onCancel = { hookCalled = true }
        try {
            // 没有任务在跑时不应误报「已停止」
            assertFalse(AptCli.cancelCurrent())

            val job = launch(Dispatchers.Default) {
                runCatching { AptCli.run("canceltest-slow", File(".")) { outputs.add(it) } }
            }
            // 等命令真正开始执行（_running 已置位、handler 已回显首行）
            withTimeout(5_000) { while (!outputs.contains("started")) delay(10) }
            assertTrue(AptCli.cancelCurrent())

            withTimeout(5_000) { job.join() }

            assertTrue("取消钩子应被调用", hookCalled)
            assertTrue("应输出取消提示", outputs.contains("命令已取消"))
            assertFalse("取消后不应继续执行到结尾", outputs.contains("finished"))
            assertFalse("取消后不应执行到结尾", reachedEnd)
            assertTrue("执行状态必须复位，否则后续命令永远排队", AptCli.isIdle)
        } finally {
            AptCli.onCancel = null
        }
    }

    @Test
    fun `命令正常结束后不再接受取消`() = runBlocking {
        val outputs = CopyOnWriteArrayList<String>()
        AptCli.register(
            CliCommand(
                name = "canceltest-quick",
                summary = "测试用短命令",
            ) { _, _, _ -> 0 },
        )

        val rc = AptCli.run("canceltest-quick", File(".")) { outputs.add(it) }
        assertTrue(rc == 0)
        assertTrue(AptCli.isIdle)
        assertFalse(AptCli.cancelCurrent())
    }
}
