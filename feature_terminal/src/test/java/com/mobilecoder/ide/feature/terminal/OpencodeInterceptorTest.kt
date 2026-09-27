package com.mobilecoder.ide.feature.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 终端命令拦截器状态机测试：`git` / `opencode` 被进程内接管，其余命令直通 shell。
 */
class OpencodeInterceptorTest {

    private val pty = StringBuilder()
    private val echo = StringBuilder()
    private var rollbacks = 0

    private lateinit var interceptor: OpencodeInterceptor

    @Before
    fun setUp() {
        pty.setLength(0)
        echo.setLength(0)
        rollbacks = 0
        interceptor = OpencodeInterceptor(
            targets = { listOf("opencode", "git") },
            onWritePty = { bytes -> pty.append(String(bytes, Charsets.UTF_8)) },
            onLocalEcho = { text -> echo.append(text) },
            onRollback = { count -> rollbacks += count },
        )
    }

    @Test
    fun `git 命令被完整拦截`() {
        val line = "git status"
        interceptor.feed(line)
        // 拦截期间不得把任何字节交给 shell
        assertEquals("", pty.toString())
        assertEquals(line, echo.toString())
        assertEquals(line, interceptor.enter())
    }

    @Test
    fun `git 单词本身也被拦截`() {
        interceptor.feed("git")
        assertEquals("git", interceptor.enter())
    }

    @Test
    fun `opencode 命令仍被拦截`() {
        val line = "opencode help"
        interceptor.feed(line)
        assertEquals(line, interceptor.enter())
        assertEquals("", pty.toString())
    }

    @Test
    fun `前缀失配的命令回滚后直通 shell`() {
        // 'g' 被暂存，'r' 发现不可能匹配 → 回滚 + 整行交给 PTY
        interceptor.feed("grep foo")
        assertTrue(rollbacks >= 1)
        assertEquals("grep foo", pty.toString())
        assertNull(interceptor.enter())
        assertTrue(pty.contains("\r")) // 回车已转发给 shell
    }

    @Test
    fun `非目标命令首个字符即直通`() {
        interceptor.feed("ls -la")
        assertEquals("ls -la", pty.toString())
        assertEquals(0, rollbacks)
        assertNull(interceptor.enter())
    }

    @Test
    fun `行首空格不破坏拦截`() {
        interceptor.feed("  git log")
        val intercepted = interceptor.enter()
        assertEquals("git log", intercepted)
        assertEquals("", pty.toString())
    }

    @Test
    fun `同前缀非目标命令交回 shell`() {
        // "gitx" 曾完整匹配过 "git"，但词边界不成立 → 必须连回车一起交回 shell
        interceptor.feed("gitx foo")
        assertNull(interceptor.enter())
        assertEquals("gitx foo\r", pty.toString())
    }

    @Test
    fun `拦截态退格后再回车按失配处理`() {
        interceptor.feed("git")      // 拦截
        interceptor.backspace()      // 退成 "gi"
        val result = interceptor.enter()
        assertNull(result)            // 不再是完整目标 → 交回 shell（含回车）
        assertEquals("gi\r", pty.toString())
    }

    @Test
    fun `CtrlC 复位拦截态`() {
        interceptor.feed("git sta") // 7 个字符被暂存
        interceptor.interrupt()
        assertEquals(7, rollbacks)   // 本地回显全部回滚
        assertEquals("", pty.toString())
        interceptor.feed("git status")
        assertEquals("git status", interceptor.enter())
    }

    @Test
    fun `多段输入与逐字输入等价`() {
        val line = "git commit -m \"hello world\""
        line.forEach { interceptor.feed(it.toString()) }
        assertEquals(line, interceptor.enter())
        assertEquals("", pty.toString())
    }
}
