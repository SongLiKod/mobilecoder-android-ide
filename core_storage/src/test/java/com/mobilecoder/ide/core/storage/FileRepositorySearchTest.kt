package com.mobilecoder.ide.core.storage

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [FileRepository.walkFiles] / [FileRepository.search] 的遍历口径回归。
 *
 * `walkFiles` 是**检索与跨文件替换共用**的唯一遍历入口，口径一变两处同时变，
 * 故把隐藏项 / 构建产物 / 深度上限 / 提前停止这四条锁在这里。
 */
class FileRepositorySearchTest {

    private lateinit var root: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("file-repo-search").toFile()
    }

    @After
    fun tearDown() {
        FileRepository.customHiddenNames = null // 不让用例间的进程级注入互相污染
        root.deleteRecursively()
    }

    private fun newFile(vararg segments: String, content: String = "x"): File {
        val file = File(root, segments.joinToString("/"))
        file.parentFile.mkdirs()
        file.writeText(content)
        return file
    }

    /** 收集 root 下全部文件的相对路径。 */
    private fun walk(): List<String> {
        val seen = ArrayList<String>()
        FileRepository.walkFiles(root) { file ->
            seen.add(file.relativeTo(root).path)
            true
        }
        return seen
    }

    @Test
    fun `walkFiles 跳过隐藏项与浅层构建产物 - 深层同名目录保留`() {
        newFile("src", "Main.kt")
        newFile(".git", "config")
        newFile(".hidden.txt")
        newFile("build", "output.txt") // root/build：深度 1 → 产物
        newFile("app", "build", "intermediates.txt") // 模块根 build：深度 2 → 产物
        newFile("node_modules", "pkg", "index.js")
        newFile("com", "mobilecoder", "ide", "feature", "build", "Runner.kt") // 深层 build = 包名

        val seen = walk()

        assertTrue(seen.contains("src/Main.kt"))
        assertTrue(
            "深层 build 是合法源码包名，不能被产物名单误杀",
            seen.contains("com/mobilecoder/ide/feature/build/Runner.kt"),
        )
        assertFalse(seen.any { it.startsWith(".git") })
        assertFalse(seen.contains(".hidden.txt"))
        assertFalse(seen.contains("build/output.txt"))
        assertFalse(seen.contains("app/build/intermediates.txt"))
        assertFalse(seen.any { it.startsWith("node_modules") })
    }

    @Test
    fun `walkFiles 返回 false 即整体停止`() {
        repeat(5) { newFile("f$it.txt") }

        var visited = 0
        FileRepository.walkFiles(root) {
            visited++
            visited < 2
        }

        assertEquals(2, visited)
    }

    @Test
    fun `walkFiles 深度上限 10 层 - 第 11 层的文件不再进遍历`() {
        // 根直下文件深度 1，故 9 层目录 + 文件 = 深度 10（在内），10 层目录 + 文件 = 深度 11（在外）
        val inner = Array(9) { "d$it" }
        val outer = Array(10) { "d$it" }
        newFile(*inner, "inner.txt")
        newFile(*outer, "outer.txt")

        val seen = walk()

        assertTrue(seen.any { it.endsWith("/inner.txt") })
        assertFalse(seen.any { it.endsWith("/outer.txt") })
    }

    @Test
    fun `search 命中文件名与内容 - 内容给出行号与预览`() {
        newFile("Main.kt", content = "line one\nHello World\nline three")

        val hello = FileRepository.search(root, "hello")
        assertEquals(1, hello.size)
        assertEquals(2, hello[0].line)
        assertEquals("Hello World", hello[0].preview)

        val byName = FileRepository.search(root, "main")
        assertTrue("文件名命中也要返回", byName.any { it.line == 0 && it.preview == "Main.kt" })
    }

    @Test
    fun `search 大小写不敏感 - 空白查询返回空`() {
        newFile("a.kt", content = "val message = 1")

        assertEquals(1, FileRepository.search(root, "MESSAGE").size)
        assertEquals(emptyList<FileRepository.SearchHit>(), FileRepository.search(root, "   "))
        assertEquals(emptyList<FileRepository.SearchHit>(), FileRepository.search(root, "nope"))
    }

    @Test
    fun `search 命中数受 limit 截断 - 二进制文件内容不参与`() {
        repeat(5) { index ->
            newFile("f$index.txt", content = "needle in a haystack")
        }
        val binary = File(root, "image.png")
        binary.writeBytes(byteArrayOf(110, 101, 0, 100)) // "ne\0d"

        val hits = FileRepository.search(root, "needle", limit = 2)

        assertEquals(2, hits.size)
        assertTrue(FileRepository.search(root, "image").none { it.line > 0 })
    }

    @Test
    fun `search 跨子目录命中 - 行号从 1 开始`() {
        newFile("app", "src", "Main.kt", content = "first\nsecond\nneedle")

        val hits = FileRepository.search(root, "needle")

        assertEquals(1, hits.size)
        assertEquals(3, hits[0].line)
        assertEquals("app/src/Main.kt", hits[0].file.relativeTo(root).path)
    }
}
