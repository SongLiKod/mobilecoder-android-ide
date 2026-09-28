package com.mobilecoder.ide.feature.git

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [parseBranches] 对 native 分支帧的解析：
 * `name \x01 isHead \x01 upstream \x01 ahead \x01 behind \x01 tipOid \x01 tipSummary`。
 * 旧数据（5 帧）与远程项（remotes/ 前缀、/HEAD 箭头行）都要兼容。
 */
class GitBranchFramesTest {

    private val fs = "\u0001" // MC_FIELD_SEP

    @Test
    fun `解析 7 帧记录含顶端提交`() {
        val raw = "main${fs}1${fs}origin/main${fs}1${fs}0${fs}4bbdc8a${fs}Initial commit\n"
        val branches = parseBranches(raw)
        assertEquals(1, branches.size)
        val b = branches[0]
        assertEquals("main", b.name)
        assertTrue(b.isHead)
        assertEquals("origin/main", b.upstream)
        assertEquals(1, b.ahead)
        assertEquals(0, b.behind)
        assertEquals("4bbdc8a", b.tipOid)
        assertEquals("Initial commit", b.tipSummary)
        assertFalse(b.isRemote)
    }

    @Test
    fun `旧 5 帧记录兼容 tip 字段为空`() {
        val raw = "dev${fs}0${fs}${fs}0${fs}0\n"
        val b = parseBranches(raw).single()
        assertEquals("dev", b.name)
        assertFalse(b.isHead)
        assertEquals("", b.upstream)
        assertEquals("", b.tipOid)
        assertEquals("", b.tipSummary)
    }

    @Test
    fun `远程跟踪分支带 remotes 前缀`() {
        val raw = "remotes/origin/main${fs}0${fs}${fs}0${fs}0${fs}89f0a2c${fs}wip\n"
        val b = parseBranches(raw).single()
        assertTrue(b.isRemote)
        assertEquals("remotes/origin/main", b.name)
    }

    @Test
    fun `origin HEAD 箭头行的 upstream 存放目标`() {
        // C 层帧：name \x01 0 \x01 目标 \x01 0 \x01 0 \x01 (tip 空帧)
        val raw = "remotes/origin/HEAD${fs}0${fs}origin/main${fs}0${fs}0${fs}\n"
        val b = parseBranches(raw).single()
        assertTrue(b.isRemote)
        assertEquals("origin/HEAD".let { "remotes/$it" }, b.name)
        assertEquals("origin/main", b.upstream)
        assertEquals("", b.tipOid)
    }

    @Test
    fun `字段数不足或空名的行被跳过`() {
        val raw = "main${fs}1${fs}${fs}0${fs}0\nbroken-record\n${fs}1${fs}${fs}0${fs}0\n"
        assertEquals(listOf("main"), parseBranches(raw).map { it.name })
    }

    @Test
    fun `空输入返回空列表`() {
        assertTrue(parseBranches(null).isEmpty())
        assertTrue(parseBranches("").isEmpty())
    }
}
