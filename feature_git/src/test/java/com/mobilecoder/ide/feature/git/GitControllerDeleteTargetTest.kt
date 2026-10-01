package com.mobilecoder.ide.feature.git

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [GitController.deleteTarget]：删除未跟踪文件前的路径解析与越界防护。
 *
 * 删除是物理操作（文件不在索引/对象库中，没有 git 写操作兜底），
 * 因此解析必须严格锁死在工作区内：`..` 逃逸、空路径、指向根自身一律拒绝。
 */
class GitControllerDeleteTargetTest {

    @Test
    fun nestedPath_resolvedUnderWorkdir() {
        val expected = File(File("/repo/root").canonicalFile, "app/src/New.kt").canonicalFile
        assertEquals(expected, GitController.deleteTarget("/repo/root", "app/src/New.kt"))
    }

    @Test
    fun trailingSlash_directoryEntry_resolved() {
        // git status 中未跟踪目录显示为 `dir/`，解析后指向目录本身
        val expected = File(File("/repo/root").canonicalFile, "build").canonicalFile
        assertEquals(expected, GitController.deleteTarget("/repo/root", "build/"))
    }

    @Test
    fun dotDotEscape_rejected() {
        assertNull(GitController.deleteTarget("/repo/root", "../evil.txt"))
        assertNull(GitController.deleteTarget("/repo/root", "sub/../../evil.txt"))
        assertNull(GitController.deleteTarget("/repo/root", "../../etc/passwd"))
    }

    @Test
    fun rootItself_rejected() {
        // "." 归一化到根自身——仓库根目录绝不能是删除目标
        assertNull(GitController.deleteTarget("/repo/root", "."))
    }

    @Test
    fun blankInputs_rejected() {
        assertNull(GitController.deleteTarget("", "a.txt"))
        assertNull(GitController.deleteTarget("   ", "a.txt"))
        assertNull(GitController.deleteTarget("/repo/root", ""))
        assertNull(GitController.deleteTarget("/repo/root", "   "))
    }
}
