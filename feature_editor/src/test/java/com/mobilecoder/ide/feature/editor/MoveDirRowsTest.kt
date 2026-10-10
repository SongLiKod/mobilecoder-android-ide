package com.mobilecoder.ide.feature.editor

import com.mobilecoder.ide.core.storage.FileNode
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 移动弹层可见行的纯逻辑：默认折叠只列首层、逐层展开出箭头、
 * 子级未读盘时保留箭头、读完为空则箭头消失。
 */
class MoveDirRowsTest {

    private fun dir(path: String, depth: Int): FileNode =
        FileNode(File(path).name, File(path), true, 0L, depth)

    private val root = dir("/r", 0)
    private val rootPath = root.file.path
    private val aPath = File("/r/a").path
    private val bPath = File("/r/b").path
    private val a1Path = File("/r/a/a1").path

    @Test
    fun `默认折叠只显示根与首层`() {
        val childDirs = mapOf(rootPath to listOf(dir("/r/a", 1), dir("/r/b", 1)))

        val rows = buildMoveDirRows(root, expanded = emptySet(), childDirs)

        assertEquals(listOf(rootPath, aPath, bPath), rows.map { it.path })
        assertEquals(listOf(0, 1, 1), rows.map { it.depth })
        // 根恒展开，首层默认折叠
        assertEquals(listOf(true, false, false), rows.map { it.expanded })
    }

    @Test
    fun `展开目录列出子级并缩进一层 - 折叠即收回`() {
        val childDirs = mapOf(
            rootPath to listOf(dir("/r/a", 1)),
            aPath to listOf(dir("/r/a/a1", 2)),
        )

        val open = buildMoveDirRows(root, expanded = setOf(aPath), childDirs)
        assertEquals(listOf(rootPath, aPath, a1Path), open.map { it.path })
        assertEquals(listOf(0, 1, 2), open.map { it.depth })
        assertTrue(open[1].expanded)
        assertFalse(open[2].expanded)

        val closed = buildMoveDirRows(root, expanded = emptySet(), childDirs)
        assertEquals(listOf(rootPath, aPath), closed.map { it.path })
    }

    @Test
    fun `未读盘子级的目录保留箭头 - 读完为空则箭头消失`() {
        val childDirs = mapOf(
            rootPath to listOf(dir("/r/a", 1), dir("/r/b", 1)),
            aPath to emptyList(), // a 已读盘且无子目录
        )

        val rows = buildMoveDirRows(root, expanded = setOf(aPath), childDirs)

        assertFalse(rows.first { it.path == aPath }.hasChildren)
        // b 尚未读盘：先按「可能有子级」出箭头，点开加载后才定
        assertTrue(rows.first { it.path == bPath }.hasChildren)
    }

    @Test
    fun `深层链路沿展开路径逐层出现`() {
        val a2Path = File(a1Path, "a2").path
        val childDirs = mapOf(
            rootPath to listOf(dir("/r/a", 1)),
            aPath to listOf(dir(a1Path, 2)),
            a1Path to listOf(dir(a2Path, 3)),
        )

        val rows = buildMoveDirRows(root, expanded = setOf(aPath, a1Path), childDirs)

        assertEquals(listOf(rootPath, aPath, a1Path, a2Path), rows.map { it.path })
        // 只展开 a 时，a1 的子级不出现
        val partial = buildMoveDirRows(root, expanded = setOf(aPath), childDirs)
        assertEquals(listOf(rootPath, aPath, a1Path), partial.map { it.path })
    }
}
