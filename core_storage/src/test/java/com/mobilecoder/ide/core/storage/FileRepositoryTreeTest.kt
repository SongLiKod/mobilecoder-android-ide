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
}
