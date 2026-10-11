package com.mobilecoder.ide.core.common.cli

import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CliEngine.runCaptured]：AI 工具（`feature_ai` 的 `run_command` / `git_*`）取回
 * 命令结果的通道。
 *
 * 契约：
 *  - 输出逐行回显进缓冲、非 0 退出码原样返回（失败信息一并回传模型）；
 *  - 超时按 GNU `timeout` 惯例返回 124，且**必须释放引擎锁**（否则终端与 AI 互等死锁）；
 *  - 输出超长时截断并标注丢弃量，不把内存打爆。
 */
class CliEngineCaptureTest {

    @Test
    fun `捕获输出与退出码`() = runBlocking {
        CliEngine.register(
            CliCommand(name = "capturetest-out") { _, _, emit ->
                emit("line1")
                emit("line2")
                7
            },
        )
        val capture = CliEngine.runCaptured("capturetest-out", File("."))
        assertEquals(7, capture.exitCode)
        assertFalse(capture.timedOut)
        assertTrue(capture.output.contains("line1"))
        assertTrue(capture.output.contains("line2"))
    }

    @Test
    fun `超时按 124 返回——且释放引擎锁不留死锁`() = runBlocking {
        CliEngine.register(
            CliCommand(name = "capturetest-slow") { _, _, emit ->
                emit("start")
                delay(30_000) // 模拟卡住的命令
                emit("end")
                0
            },
        )
        val capture = CliEngine.runCaptured("capturetest-slow", File("."), timeoutMs = 200)
        assertTrue("应标记超时", capture.timedOut)
        assertEquals(124, capture.exitCode)
        assertFalse("超时后 handler 不应继续执行", capture.output.contains("end"))
        assertTrue("超时必须释放引擎锁，否则终端/AI 后续命令永远排队", CliEngine.isIdle)

        // 锁已释放：后续命令仍可正常执行
        CliEngine.register(
            CliCommand(name = "capturetest-quick") { _, _, emit ->
                emit("ok")
                0
            },
        )
        val next = CliEngine.runCaptured("capturetest-quick", File("."), timeoutMs = 5_000)
        assertEquals(0, next.exitCode)
        assertTrue(next.output.contains("ok"))
    }

    @Test
    fun `输出超长截断并标注丢弃量`() = runBlocking {
        CliEngine.register(
            CliCommand(name = "capturetest-big") { _, _, emit ->
                repeat(100) { emit("x".repeat(100)) }
                0
            },
        )
        val capture = CliEngine.runCaptured("capturetest-big", File("."), maxChars = 500)
        assertTrue("截断后长度受控，实际=${capture.output.length}", capture.output.length < 1_000)
        assertTrue(capture.output.contains("已截断"))
    }

    @Test
    fun `未注册的命令返回 127`() = runBlocking {
        val capture = CliEngine.runCaptured("capturetest-none", File("."))
        assertEquals(127, capture.exitCode)
        assertTrue(capture.output.contains("command not found"))
    }
}
