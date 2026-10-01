package com.mobilecoder.ide.core.common.cli

import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AptCli.onExecuted]：命令执行完成 → 操作历史的唯一回传通道
 *（原 CLI 面板废弃后，历史页靠这个钩子拿到「命令 + 退出码 + 耗时」）。
 *
 * 注意 core_common 不能依赖 core_storage，所以落盘由 App 启动时注入的钩子完成。
 */
class AptCliExecutedHookTest {

    private data class Event(val line: String, val exitCode: Int, val durationMs: Long)

    @After
    fun tearDown() {
        AptCli.onExecuted = null
    }

    @Test
    fun `执行结束后回传命令行、退出码与耗时`() = runBlocking {
        val events = CopyOnWriteArrayList<Event>()
        AptCli.register(
            CliCommand(name = "historytest-ok", summary = "测试用成功命令") { _, _, _ -> 0 },
        )
        AptCli.register(
            CliCommand(name = "historytest-fail", summary = "测试用失败命令") { _, _, _ -> 7 },
        )
        AptCli.onExecuted = { line, _, exitCode, durationMs ->
            events.add(Event(line, exitCode, durationMs))
        }

        val ok = AptCli.run("historytest-ok", File(".")) { }
        val fail = AptCli.run("apt historytest-fail", File(".")) { }

        assertEquals(0, ok)
        assertEquals(7, fail)
        assertEquals(2, events.size)
        assertEquals("historytest-ok", events[0].line)
        assertEquals(0, events[0].exitCode)
        assertEquals("apt historytest-fail", events[1].line)
        assertEquals(7, events[1].exitCode)
        assertTrue("耗时不应为负", events.all { it.durationMs >= 0L })
        assertTrue("执行后引擎必须空闲", AptCli.isIdle)
    }

    @Test
    fun `未注册命令不产生历史记录`() = runBlocking {
        val events = CopyOnWriteArrayList<Event>()
        AptCli.onExecuted = { line, _, exitCode, durationMs ->
            events.add(Event(line, exitCode, durationMs))
        }

        val rc = AptCli.run("historytest-not-a-command", File(".")) { }

        assertEquals(127, rc)
        assertTrue("没有真正执行的命令不该进历史", events.isEmpty())
    }
}
