package com.mobilecoder.ide.core.storage

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [FileRepository.tree] / [FileRepository.listTreeChildren] 的项目树口径：
 *
 *  1. 深度截断：`app/src/main/java/com/.../ui` 的子文件在第 9 层，
 *     `maxDepth = 8` 的 eager 树里 ui 在、文件不在 —— 这是
 *     「目录树里显示文件不完整（ui 展开是空的）」的根因；
 *  2. [listTreeChildren] 是展开时补载的读盘原语，与 tree() 同口径
 *     （过滤隐藏 + 构建产物），保证补载出来的内容与 eager 段一致。
 */
class FileRepositoryTreeTest {

    private lateinit var root: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("file-repo-tree").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun newFile(vararg segments: String): File {
        val f = File(root, segments.joinToString("/"))
        f.parentFile.mkdirs()
        f.writeText("x")
        return f
    }

    private fun newDir(vararg segments: String): File {
        val f = File(root, segments.joinToString("/"))
        f.mkdirs()
        return f
    }

    @Test
    fun `深度超过 maxDepth 的文件不进树 - 截断边界即 ui 为空的根因`() {
        // app(1)/src(2)/main(3)/java(4)/com(5)/mobilecoder(6)/ide(7)/ui(8)/X.kt(9)
        newFile("app", "src", "main", "java", "com", "mobilecoder", "ide", "ui", "X.kt")

        val nodes = FileRepository.tree(root, maxDepth = 8)

        val ui = nodes.firstOrNull { it.file.path == File(root, "app/src/main/java/com/mobilecoder/ide/ui").path }
        assertNotNull("第 8 层目录 ui 应在树内", ui)
        assertEquals(8, ui!!.depth)
        assertTrue("第 9 层子文件被 maxDepth=8 截断（旧行为，展开即空）", nodes.none { it.name == "X.kt" })
    }

    @Test
    fun `listTreeChildren 补出被截断的子级 - 展开补载修复原语`() {
        newFile("app", "src", "main", "java", "com", "mobilecoder", "ide", "ui", "About.kt")
        newFile("app", "src", "main", "java", "com", "mobilecoder", "ide", "ui", "App.kt")

        val ui = File(root, "app/src/main/java/com/mobilecoder/ide/ui")
        val children = FileRepository.listTreeChildren(ui)

        assertEquals(listOf("About.kt", "App.kt"), children.map { it.name })
        assertTrue(children.none { it.isDirectory })
    }

    @Test
    fun `listTreeChildren 与 tree 同口径 - 过滤构建产物与隐藏项`() {
        newDir("app", "build", "intermediates")
        newDir("app", "node_modules", "lodash")
        newDir("app", "src")
        newFile("app", ".gitignore")

        val children = FileRepository.listTreeChildren(File(root, "app"))

        assertEquals(listOf("src"), children.map { it.name })

        val nodes = FileRepository.tree(root, maxDepth = 16)
        assertTrue("tree() 不应出现 build", nodes.none { it.name == "build" })
        assertTrue("tree() 不应出现 node_modules", nodes.none { it.name == "node_modules" })
        assertTrue("tree() 不应出现隐藏文件", nodes.none { it.name.startsWith(".") })
    }

    @Test
    fun `showHidden 模式保留点开头目录`() {
        newDir(".circleci")
        newDir("src")

        val shown = FileRepository.listTreeChildren(root, showHidden = true)

        assertEquals(setOf(".circleci", "src"), shown.mapTo(HashSet()) { it.name })
        assertFalse(FileRepository.listTreeChildren(root).any { it.name == ".circleci" })
    }

    @Test
    fun `listOpenableFiles 列全部文本文件 - 跳过构建产物与二进制`() {
        newFile("app", "Main.kt") // ✓
        newFile("README.md") // ✓ 根直下
        newFile("build", "output.txt") // 构建产物目录 → 过滤
        newFile(".git", "config") // 隐藏目录 → 过滤
        newFile("app", "res", "icon.png") // 二进制扩展名 → 过滤

        val rels = FileRepository.listOpenableFiles(root).map {
            it.path.removePrefix(root.path).trimStart('/', '\\').replace('\\', '/')
        }

        assertEquals(setOf("README.md", "app/Main.kt"), rels.toSet())
        // 字典序（大小写不敏感），面板默认序可预期
        assertEquals(rels.sortedBy { it.lowercase() }, rels)
    }

    @Test
    fun `listOpenableFiles 有上限 - 超大项目不卡死`() {
        repeat(30) { newFile("pkg", "F$it.kt") }

        val files = FileRepository.listOpenableFiles(root, limit = 10)

        assertEquals(10, files.size)
    }

    @Test
    fun `listOpenableFiles 默认上限不截断后序模块 - DFS 早截断吃掉整个模块的回归锁`() {
        // 真机回归：本仓库 third_party(mbedtls) 2400+ 文件排在 feature_* 之前，
        // 旧默认上限 2000 在此截断 → 快速打开里 feature_editor 的文件全搜不到
        repeat(2500) { newFile("aaa_vendored", "v$it.c") }
        newFile("zzz_module", "Screen.kt")

        val files = FileRepository.listOpenableFiles(root)

        assertEquals(2501, files.size)
        assertTrue(files.any { it.name == "Screen.kt" })
    }

    @Test
    fun `同名 build 源码包不被误杀 - 仅浅层 build 算构建产物`() {
        newFile("app", "build", "R.txt") // 模块产物（深度 2）→ 必须隐藏
        val src = newFile(
            "feature_build", "src", "main", "java", "com", "mobilecoder",
            "ide", "feature", "build", "BuildScreen.kt",
        ) // 源码包 build（深度 9）→ 曾被整包过滤，feature_build 模块源码全部消失

        val paths = FileRepository.tree(root, maxDepth = 16).map { it.file.path }
        assertFalse("模块 build 产物应隐藏", paths.contains(File(root, "app/build/R.txt").path))
        assertTrue("源码包 build 下的文件必须在树里", paths.contains(src.path))

        // 快速打开与全文检索同口径
        assertTrue(FileRepository.listOpenableFiles(root).any { it.path == src.path })
        assertTrue(FileRepository.search(root, "BuildScreen").any { it.file.path == src.path })
    }
}
