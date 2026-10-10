package com.mobilecoder.ide.feature.git

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 分支列表自动关联上游候选（[GitController.autoTrackCandidates]）：
 * 未配上游的本地分支，远程恰有唯一同名分支才关联；多远程同名 / 无同名不关联。
 */
class GitAutoTrackTest {

    private fun local(name: String, upstream: String = "") =
        GitBranch(name, isHead = false, upstream = upstream, ahead = 0, behind = 0)

    private fun remote(name: String) =
        GitBranch("remotes/$name", isHead = false, upstream = "", ahead = 0, behind = 0)

    @Test
    fun `唯一同名远程分支的本地分支列为候选`() {
        val list = listOf(
            local("main", upstream = "origin/main"),
            local("dev"),
            remote("origin/main"),
            remote("origin/dev"),
        )
        assertEquals(listOf("dev" to "origin"), GitController.autoTrackCandidates(list))
    }

    @Test
    fun `多远程同名时不自动关联`() {
        val list = listOf(
            local("dev"),
            remote("origin/dev"),
            remote("upstream/dev"),
        )
        assertEquals(emptyList<Pair<String, String>>(), GitController.autoTrackCandidates(list))
    }

    @Test
    fun `远程无同名分支时不关联`() {
        val list = listOf(local("dev"), remote("origin/main"))
        assertEquals(emptyList<Pair<String, String>>(), GitController.autoTrackCandidates(list))
    }

    @Test
    fun `已配置上游的分支跳过`() {
        val list = listOf(local("dev", upstream = "fork/dev"), remote("origin/dev"))
        assertEquals(emptyList<Pair<String, String>>(), GitController.autoTrackCandidates(list))
    }

    @Test
    fun `HEAD 符号引用行不参与匹配`() {
        val list = listOf(local("release"), remote("origin/HEAD"))
        assertEquals(emptyList<Pair<String, String>>(), GitController.autoTrackCandidates(list))
    }

    @Test
    fun `带斜杠分支名按完整尾部匹配`() {
        val list = listOf(
            local("feature/x"),
            remote("origin/feature/x"),
            remote("origin/feature"),
        )
        assertEquals(
            listOf("feature/x" to "origin"),
            GitController.autoTrackCandidates(list),
        )
    }

    @Test
    fun `远程行名解析为远程与本地分支名`() {
        assertEquals("origin" to "dev", GitController.remoteTrackingName("remotes/origin/dev"))
        assertEquals(
            "origin" to "feature/x",
            GitController.remoteTrackingName("remotes/origin/feature/x"),
        )
    }

    @Test
    fun `本地分支与 HEAD 符号引用行走普通切换路径`() {
        assertEquals(null, GitController.remoteTrackingName("dev"))
        assertEquals(null, GitController.remoteTrackingName("remotes/origin/HEAD"))
        assertEquals(null, GitController.remoteTrackingName("remotes/origin"))
        assertEquals(null, GitController.remoteTrackingName("remotes/"))
    }
}
