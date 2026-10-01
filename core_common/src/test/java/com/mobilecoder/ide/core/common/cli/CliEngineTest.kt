package com.mobilecoder.ide.core.common.cli

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [CliEngine.isInProcessLine]：终端 Enter 时的**最终分流**判定
 * （命令拦截器的 `accept` 钩子，区别于前缀缓冲阶段的 [CliEngine.interceptTargets]）。
 *
 * 契约：
 *  - 注册表里标记 `terminalIntercept` 的命令（`git`，Android 无此可执行文件）→ 进程内执行；
 *  - **`apt …` 一律交回 shell**（apt CLI 已移除，Linux 环境就绪时由 rootfs 里的真 apt 接管）；
 *  - 其余普通 shell 命令 → 交回 shell。
 */
class CliEngineTest {

    @Before
    fun registerFakeGit() {
        // 模拟 feature_git 的注册（JVM 单测无法依赖 feature 模块）
        CliEngine.register(
            CliCommand(
                name = "git",
                terminalIntercept = true,
            ) { _, _, emit -> emit("ok"); 0 },
        )
    }

    @Test
    fun `注册过的拦截命令走进程内`() {
        assertTrue(CliEngine.isInProcessLine("git status"))
        assertTrue(CliEngine.isInProcessLine("  git   status  ")) // 多余空白不敏感
        assertTrue(CliEngine.isInProcessLine("git commit -m \"hello world\"")) // 引号被 tokenize 消化
        assertTrue(CliEngine.isInProcessLine("git")) // 裸命令本身
    }

    @Test
    fun `apt 行一律交回 shell——apt CLI 已移除`() {
        assertFalse(CliEngine.isInProcessLine("apt"))
        assertFalse(CliEngine.isInProcessLine("apt help"))
        assertFalse(CliEngine.isInProcessLine("apt install curl"))
        assertFalse(CliEngine.isInProcessLine("apt update"))
        assertFalse(CliEngine.isInProcessLine("apt-get install curl")) // 连前缀缓冲都不该拦
    }

    @Test
    fun `普通 shell 命令交回 shell`() {
        assertFalse(CliEngine.isInProcessLine(""))
        assertFalse(CliEngine.isInProcessLine("   "))
        assertFalse(CliEngine.isInProcessLine("ls -la"))
        assertFalse(CliEngine.isInProcessLine("npm install"))
        assertFalse(CliEngine.isInProcessLine("grep foo"))
    }

    @Test
    fun `拦截目标只含注册的命令——不再有 apt 前缀`() {
        val targets = CliEngine.interceptTargets()
        assertTrue("interceptTargets 应包含 git", targets.contains("git"))
        assertFalse("apt 不应再被拦截", targets.contains("apt"))
    }

    @Test
    fun `run 按首词分发并切分引号参数`() {
        val file = File(".")
        val got = mutableListOf<List<String>>()
        CliEngine.register(
            CliCommand(name = "enginetest-args") { args, cwd, emit ->
                got += args
                assertEquals("工作目录应透传", file.canonicalFile, cwd.canonicalFile)
                emit("done")
                0
            },
        )
        var output = ""
        val rc = runBlocking {
            CliEngine.run("enginetest-args commit -m \"hello world\"", file) { output += it }
        }
        assertEquals(0, rc)
        assertEquals(listOf("commit", "-m", "hello world"), got.single())
        assertTrue(output.contains("done"))
    }

    @Test
    fun `未注册命令返回 127`() {
        var output = ""
        val rc = runBlocking {
            CliEngine.run("no-such-cmd", File(".")) { output = it }
        }
        assertEquals(127, rc)
        assertTrue(output.contains("command not found"))
    }

    @Test
    fun `空行直接返回 0`() {
        val rc = runBlocking { CliEngine.run("   ", File(".")) { } }
        assertEquals(0, rc)
    }
}
