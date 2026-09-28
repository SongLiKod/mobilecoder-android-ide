package com.mobilecoder.ide.feature.git

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitPorcelainTest {

    @Test
    fun `日常命令参考包含 Main Porcelain 核心项`() {
        val names = GitPorcelain.all.map { it.name }.toSet()
        listOf(
            "init", "clone", "status", "add", "commit", "diff", "log",
            "branch", "switch", "checkout", "merge", "pull", "push", "fetch", "tag",
        ).forEach { assertTrue("$it 应在参考中", names.contains(it)) }
        assertTrue(GitPorcelain.groups.size >= 4)
    }

    @Test
    fun `终端已实现命令与参考标记一致`() {
        assertTrue(GitPorcelain.implementedNames.contains("status"))
        assertTrue(GitPorcelain.implementedNames.contains("pull"))
        assertFalse(GitPorcelain.implementedNames.contains("rebase"))
        assertTrue(GitPorcelain.referenceNames.contains("stash"))
        assertEquals(GitPorcelain.all.size, GitPorcelain.implementedNames.size + GitPorcelain.referenceNames.size)
    }

    @Test
    fun `参考文本可供终端输出`() {
        val lines = GitPorcelain.lines()
        assertTrue(lines.any { it.contains("Main Porcelain") })
        assertTrue(lines.any { it.contains("git help porcelain") || it.contains("日常命令") })
        assertTrue(lines.any { it.contains("status") })
    }
}
