package com.mobilecoder.ide.feature.git

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 分支页两处纯函数文案（真机反馈回归锁）：
 * 1. [headTitle] —— 游离 HEAD 时不能裸显十六进制串，要标出「分离 HEAD」；
 * 2. [branchSourceLabel] —— 新建分支起始点选项，当前分支带「·当前」标记。
 */
class GitBranchUiTest {

    private fun head(
        branch: String,
        detached: Boolean = false,
        unborn: Boolean = false,
        ahead: Int = 0,
        behind: Int = 0,
    ) = GitHead(branch, detached, unborn, ahead, behind)

    @Test
    fun `附着 HEAD 标题显示分支名`() {
        assertEquals("main", headTitle(head("main")))
    }

    @Test
    fun `游离 HEAD 标题显示分离与短提交号`() {
        assertEquals("分离 HEAD · 4bbdc8a1e2", headTitle(head("4bbdc8a1e2", detached = true)))
    }

    @Test
    fun `游离但提交号缺失时兜底未知提交`() {
        assertEquals("分离 HEAD · 未知提交", headTitle(head("", detached = true)))
    }

    @Test
    fun `未出生 HEAD 提示尚未指向分支`() {
        assertEquals("（HEAD 尚未指向分支）", headTitle(head("")))
    }

    @Test
    fun `无 HEAD 时提示无 HEAD`() {
        assertEquals("（无 HEAD）", headTitle(null))
    }

    @Test
    fun `起始点选项当前分支带当前标记`() {
        val current = GitBranch("master", isHead = true, upstream = "", ahead = 0, behind = 0)
        val other = GitBranch("dev", isHead = false, upstream = "", ahead = 0, behind = 0)
        assertEquals("master · 当前", branchSourceLabel(current))
        assertEquals("dev", branchSourceLabel(other))
    }
}
