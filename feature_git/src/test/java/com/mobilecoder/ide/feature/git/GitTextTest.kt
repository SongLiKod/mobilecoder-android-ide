package com.mobilecoder.ide.feature.git

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * git 输出对齐（[GitText] 纯函数层）：
 *  - `git branch [-a|-r] [-v|-vv] [--list <pattern>]` 行格式
 *  - `git fetch` 的 ` * [new branch] …` / `   a..b …` 更新行
 *  - `git checkout origin/x` 分离检出提示块
 *  - `git pull` 无上游配置的提示块 / 合并目标选择
 */
class GitTextTest {

    private fun branch(
        name: String,
        isHead: Boolean = false,
        upstream: String = "",
        ahead: Int = 0,
        behind: Int = 0,
        tipOid: String = "",
        tipSummary: String = "",
    ) = GitBranch(name, isHead, upstream, ahead, behind, tipOid, tipSummary)

    /* ---------------- git branch ---------------- */

    @Test
    fun `默认只列本地分支并用星号标记当前分支`() {
        val lines = renderBranchLines(
            listOf(
                branch("main", isHead = true),
                branch("dev"),
                branch("remotes/origin/main"),
            ),
        )
        assertEquals(listOf("* main", "  dev"), lines)
    }

    @Test
    fun `-a 连同远程分支列出 origin HEAD 用箭头形式`() {
        val lines = renderBranchLines(
            listOf(
                branch("main", isHead = true),
                branch("remotes/origin/HEAD", upstream = "origin/main"),
                branch("remotes/origin/main"),
            ),
            all = true,
        )
        assertEquals(
            listOf(
                "* main",
                "  remotes/origin/HEAD -> origin/main",
                "  remotes/origin/main",
            ),
            lines,
        )
    }

    @Test
    fun `-r 只列远程分支`() {
        val lines = renderBranchLines(
            listOf(branch("main", isHead = true), branch("remotes/origin/dev")),
            remoteOnly = true,
        )
        assertEquals(listOf("  remotes/origin/dev"), lines)
    }

    @Test
    fun `-v 输出顶端提交号与标题且列对齐`() {
        val lines = renderBranchLines(
            listOf(
                branch("main", isHead = true, tipOid = "4bbdc8a", tipSummary = "initial commit"),
                branch("dev", tipOid = "89f0a2c", tipSummary = "wip"),
            ),
            verbose = 1,
        )
        // 最长名 main(4)+1 = 5：dev 补两个空格与 main 后内容列对齐
        assertEquals(
            listOf(
                "* main 4bbdc8a initial commit",
                "  dev  89f0a2c wip",
            ),
            lines,
        )
    }

    @Test
    fun `-vv 本地分支带上游括号且含领先落后`() {
        val lines = renderBranchLines(
            listOf(
                branch(
                    "main",
                    isHead = true,
                    upstream = "origin/main",
                    ahead = 1,
                    behind = 2,
                    tipOid = "4bbdc8a",
                    tipSummary = "initial commit",
                ),
                branch("remotes/origin/main", tipOid = "89f0a2c", tipSummary = "initial commit"),
            ),
            all = true,
            verbose = 2,
        )
        /* 列宽 = 最长可见名（含远程行）+ 1：远程项也参与对齐 */
        val width = "remotes/origin/main".length + 1
        assertEquals(
            "* " + "main".padEnd(width) + "4bbdc8a [origin/main: ahead 1, behind 2] initial commit",
            lines[0],
        )
        // 远程行不带上游括号
        assertEquals(
            "  " + "remotes/origin/main".padEnd(width) + "89f0a2c initial commit",
            lines[1],
        )
    }

    @Test
    fun `上游括号无领先落后时只显示上游名`() {
        assertEquals("[origin/main]", upstreamBracket(branch("main", upstream = "origin/main")))
        assertEquals(
            "[origin/main: ahead 3]",
            upstreamBracket(branch("main", upstream = "origin/main", ahead = 3)),
        )
        assertEquals(
            "[origin/main: behind 2]",
            upstreamBracket(branch("main", upstream = "origin/main", behind = 2)),
        )
    }

    @Test
    fun `list 模式按 glob 过滤且远程行按简写匹配`() {
        val branches = listOf(
            branch("main"),
            branch("remotes/origin/HEAD", upstream = "origin/main"),
            branch("remotes/origin/main"),
        )
        assertEquals(
            listOf("  remotes/origin/main"),
            renderBranchLines(branches, all = true, patterns = listOf("origin/m*")),
        )
        assertEquals(
            listOf("  main"),
            renderBranchLines(branches, patterns = listOf("ma?n")),
        )
        assertEquals(
            emptyList<String>(),
            renderBranchLines(branches, patterns = listOf("feature/*")),
        )
    }

    /* ---------------- git fetch ---------------- */

    @Test
    fun `fetch 输出新增分支行`() {
        val oid = "89f0a2c" + "0".repeat(33)
        val lines = formatFetchRefLines(
            remote = "origin",
            before = emptyMap(),
            after = mapOf("origin/dev" to oid),
        )
        assertEquals(
            listOf(" * " + "[new branch]".padEnd(16) + "dev".padEnd(12) + "-> origin/dev"),
            lines,
        )
    }

    @Test
    fun `fetch 输出分支更新行用短 oid 范围`() {
        val oldOid = "5b7d6e5" + "a".repeat(33)
        val newOid = "89f0a2c" + "b".repeat(33)
        val lines = formatFetchRefLines(
            remote = "origin",
            before = mapOf("origin/main" to oldOid),
            after = mapOf("origin/main" to newOid),
        )
        assertEquals(
            listOf("   " + "5b7d6e5..89f0a2c".padEnd(16) + "main".padEnd(12) + "-> origin/main"),
            lines,
        )
    }

    @Test
    fun `fetch 输出新标签与删除行`() {
        val tagOid = "0f1e2d3" + "c".repeat(33)
        val lines = formatFetchRefLines(
            remote = "origin",
            before = mapOf("origin/gone" to tagOid),
            after = emptyMap(),
            tagsBefore = emptyMap(),
            tagsAfter = mapOf("v1.0" to tagOid),
        )
        assertEquals(
            listOf(
                " - " + "[deleted]".padEnd(16) + "gone".padEnd(12) + "-> origin/gone",
                " * " + "[new tag]".padEnd(16) + "v1.0".padEnd(12) + "-> v1.0",
            ),
            lines,
        )
    }

    @Test
    fun `fetch 无变化时没有任何更新行`() {
        val oid = "89f0a2c" + "0".repeat(33)
        assertEquals(
            emptyList<String>(),
            formatFetchRefLines("origin", mapOf("origin/main" to oid), mapOf("origin/main" to oid)),
        )
    }

    /* ---------------- checkout / pull 文案 ---------------- */

    @Test
    fun `分离检出提示块含 Note 与 HEAD 行`() {
        val lines = detachedHeadLines("origin/dev", "4bbdc8a", "Initial commit")
        assertEquals("Note: switching to 'origin/dev'.", lines.first())
        assertEquals("HEAD is now at 4bbdc8a Initial commit", lines.last())
        assertTrue(lines.any { it.contains("detached HEAD state") })
        assertTrue(lines.any { it.contains("git switch -c") })
    }

    @Test
    fun `pull 无上游配置提示块给出配置命令`() {
        val lines = pullNoTrackingLines("dev", "origin")
        assertEquals("There is no tracking information for the current branch.", lines.first())
        assertTrue(lines.contains("    git config branch.dev.remote origin"))
        assertTrue(lines.contains("    git config branch.dev.merge refs/heads/dev"))
    }

    @Test
    fun `pull 合并目标优先级：参数 大于 配置 大于 当前分支`() {
        assertEquals("origin/dev", pullMergeRef("origin", "dev", null, "main"))
        assertEquals("origin/dev", pullMergeRef("origin", null, "refs/heads/dev", "main"))
        assertEquals("origin/main", pullMergeRef("origin", null, null, "main"))
        assertNull(pullMergeRef("origin", null, null, null))
    }
}
