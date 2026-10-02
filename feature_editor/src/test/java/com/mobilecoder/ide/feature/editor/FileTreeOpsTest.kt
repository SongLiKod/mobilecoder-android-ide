package com.mobilecoder.ide.feature.editor

import com.mobilecoder.ide.core.storage.FileNode
import com.mobilecoder.ide.core.storage.FileRepository
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 文件树纯逻辑：祖先展开链、懒补载 DFS 插入、全量目录收集。
 *
 * 其中 [end to end deep directory is locatable after backfill] 是
 * 「目录树里显示文件不完整（ui 展开是空的）」的回归锁：eager 树在第 8 层截断，
 * 展开补载 + DFS 插入后，第 9 层文件必须出现在可见行里。
 */
class FileTreeOpsTest {

    // ------------------------------------------------------------------
    // ancestorDirPaths：打开文件时要展开的祖先目录链
    // ------------------------------------------------------------------

    @Test
    fun `祖先链自浅入深且不含 root 自身`() {
        assertEquals(
            listOf("/p/a", "/p/a/b"),
            ancestorDirPaths("/p", "/p/a/b/F.kt"),
        )
    }

    @Test
    fun `root 直下文件无祖先目录`() {
        assertEquals(emptyList<String>(), ancestorDirPaths("/p", "/p/F.kt"))
        assertEquals(emptyList<String>(), ancestorDirPaths("/p", "/p"))
    }

    @Test
    fun `树外路径与空 root 返回空链 - 不误伤同前缀目录`() {
        // root=/a/b 时 /a/bc/d 不在树内（目录边界）
        assertEquals(emptyList<String>(), ancestorDirPaths("/a/b", "/a/bc/d/F.kt"))
        assertEquals(emptyList<String>(), ancestorDirPaths("/p", "/q/a/F.kt"))
        assertEquals(emptyList<String>(), ancestorDirPaths(null, "/p/a/F.kt"))
        assertEquals(emptyList<String>(), ancestorDirPaths("", "/p/a/F.kt"))
    }

    @Test
    fun `root 尾部斜杠被归一化`() {
        assertEquals(listOf("/p/a", "/p/a/b"), ancestorDirPaths("/p/", "/p/a/b/F.kt"))
    }

    // ------------------------------------------------------------------
    // hasChildrenLoaded / withChildrenInserted：懒补载的 DFS 不变式
    // ------------------------------------------------------------------

    private fun node(path: String, depth: Int, isDir: Boolean = false): FileNode =
        FileNode(File(path).name, File(path), isDir, 0L, depth)

    @Test
    fun `紧随更深节点即已物化子级 - 末尾目录与文件节点不算`() {
        val dirPath = File("/r/b").path
        val tree = listOf(
            node("/r/a", 1, isDir = true),
            node("/r/b", 2, isDir = true),
            node("/r/b/F.kt", 3),
        )
        assertTrue(hasChildrenLoaded(tree, tree.indexOfFirst { it.file.path == dirPath }))
        // 无子级的末尾目录
        val bare = listOf(node("/r/a", 1, isDir = true))
        assertFalse(hasChildrenLoaded(bare, 0))
        // 文件节点没有子级
        assertFalse(hasChildrenLoaded(tree, 2))
        assertFalse(hasChildrenLoaded(tree, -1))
    }

    @Test
    fun `补载插入挂在所属目录之后并保持 DFS 序`() {
        val dirPath = File("/r/b").path
        val tree = listOf(
            node("/r/a", 1, isDir = true),
            node("/r/b", 2, isDir = true),
            node("/r/c", 1, isDir = true),
        )
        val children = listOf(
            node("/r/b/F.kt", 3),
            node("/r/b/G.kt", 3),
        )

        val out = withChildrenInserted(tree, dirPath, children)

        assertEquals(
            listOf("/r/a", "/r/b", "/r/b/F.kt", "/r/b/G.kt", "/r/c").map { File(it).path },
            out.map { it.file.path },
        )
        // 插入后行构建正常：子文件归到 b 下（depth 3），折叠 b 即隐藏
        val rows = buildTreeRows(out, collapsed = emptySet())
        assertEquals(5, rows.size)
        val collapsedRows = buildTreeRows(out, collapsed = setOf(dirPath))
        assertEquals(
            listOf("/r/a", "/r/b", "/r/c").map { File(it).path },
            collapsedRows.map { it.path },
        )
    }

    @Test
    fun `补载幂等 - 已物化、目录不存在、空目录均原样返回`() {
        val dirPath = File("/r/b").path
        val loaded = listOf(
            node("/r/b", 1, isDir = true),
            node("/r/b/F.kt", 2),
        )
        assertEquals(loaded, withChildrenInserted(loaded, dirPath, listOf(node("/r/b/H.kt", 2))))
        // 目录不在树内
        assertEquals(loaded, withChildrenInserted(loaded, File("/r/nope").path, listOf(node("/r/x.kt", 2))))
        // 空目录不落占位（否则每次展开都误判未物化）
        val bare = listOf(node("/r/b", 1, isDir = true))
        assertEquals(bare, withChildrenInserted(bare, dirPath, emptyList()))
    }

    @Test
    fun `directoryPathsOf 只收目录且与默认折叠渲染一致`() {
        val tree = listOf(
            node("/r/a", 1, isDir = true),
            node("/r/a/F.kt", 2),
            node("/r/b", 1, isDir = true),
            node("/r/G.kt", 1),
        )
        val dirs = directoryPathsOf(tree)
        assertEquals(LinkedHashSet(listOf(File("/r/a").path, File("/r/b").path)), dirs)
        // 全部折叠 = 折叠集合为全部目录 → 只剩顶层行
        val rows = buildTreeRows(tree, collapsed = dirs)
        assertEquals(listOf(File("/r/a").path, File("/r/b").path, File("/r/G.kt").path), rows.map { it.path })
        assertEquals(emptySet<String>(), directoryPathsOf(emptyList()))
    }

    // ------------------------------------------------------------------
    // 端到端：第 9 层文件（ui 展开为空的原始 bug）补载后可见
    // ------------------------------------------------------------------

    private lateinit var root: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("file-tree-ops").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun `截断目录补载后第 9 层文件出现在可见行`() {
        val uiFile = File(root, "app/src/main/java/com/mobilecoder/ide/ui/AboutContent.kt")
        uiFile.parentFile?.mkdirs()
        uiFile.writeText("package ui")

        // 1) eager 树（与 EditorController.refreshTree 同参）在第 8 层截断
        val eager = FileRepository.tree(root, maxDepth = 8)
        val ui = eager.first { it.file.path == File(root, "app/src/main/java/com/mobilecoder/ide/ui").path }
        assertTrue(eager.none { it.name == "AboutContent.kt" })

        // 2) 展开 ui → 补载子级 → 按 DFS 插入
        val children = FileRepository.listTreeChildren(ui.file).map { it.copy(depth = ui.depth + 1) }
        val merged = withChildrenInserted(eager, ui.file.path, children)

        // 3) 可见行里能看到文件：定位 = 展开祖先链 + ui 自身（与 locateInTree 同口径）
        val chain = ancestorDirPaths(root.path, uiFile.path) + ui.file.path
        val open = buildTreeRows(merged, collapsed = directoryPathsOf(merged) - chain)
        assertTrue(open.any { it.path == uiFile.path && it.depth == 9 })
        val closed = buildTreeRows(merged, collapsed = directoryPathsOf(merged))
        assertTrue(closed.none { it.path == uiFile.path })
        assertEquals(listOf(File(root, "app").path), closed.map { it.path })
    }
}
