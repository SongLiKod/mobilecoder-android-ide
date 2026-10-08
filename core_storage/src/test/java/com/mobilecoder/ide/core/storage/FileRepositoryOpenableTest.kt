package com.mobilecoder.ide.core.storage

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [FileRepository.unopenableReason] —— 编辑器打开前的三道拦截：
 * 扩展名名单（默认含 apk / 用户可自定义）→ 大小上限 → 二进制嗅探兜底。
 * 回归目标：点开 .apk 不再整读进 BasicTextField 把 UI 卡死，只提示不支持预览/编辑。
 */
class FileRepositoryOpenableTest {

    private lateinit var root: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("file-repo-openable").toFile()
    }

    @After
    fun tearDown() {
        FileRepository.customUnopenableExts = null // 不让用例间的进程级注入互相污染
        FileRepository.customMaxOpenBytes = null
        root.deleteRecursively()
    }

    private fun newFile(vararg segments: String, bytes: ByteArray = "x".toByteArray()): File {
        val f = File(root, segments.joinToString("/"))
        f.parentFile.mkdirs()
        f.writeBytes(bytes)
        return f
    }

    @Test
    fun `默认名单拦截 apk - 不读盘直接给出不支持预览编辑的提示`() {
        val reason = FileRepository.unopenableReason(newFile("app", "build", "demo.apk"))

        assertNotNull("apk 应在读盘前被拦截", reason)
        assertTrue("提示语需说明不支持在线预览或编辑", reason!!.contains("不支持在线预览或编辑"))
    }

    @Test
    fun `已知文本扩展名不受拦截`() {
        assertNull(FileRepository.unopenableReason(newFile("src", "Main.kt")))
        assertNull(FileRepository.unopenableReason(newFile("res", "layout.xml")))
    }

    @Test
    fun `用户自定义名单生效 - 注入后按新口径拦截`() {
        FileRepository.customUnopenableExts = setOf("log")

        assertNotNull(FileRepository.unopenableReason(newFile("run.log")))
        assertNull("名单外的扩展名不应被拦", FileRepository.unopenableReason(newFile("notes.tmp")))
    }

    @Test
    fun `用户清空名单后二进制嗅探兜底 - 未知扩展名含 NUL 仍拦截`() {
        FileRepository.customUnopenableExts = emptySet()
        val binary = ByteArray(512) { 0x7f }
        binary[100] = 0 // ZIP / ELF 等二进制头部常见 NUL

        assertNotNull(
            "清空名单也必须拦住二进制，否则整读会卡死 UI",
            FileRepository.unopenableReason(newFile("mystery.xyz", bytes = binary)),
        )
        assertNull(
            "清空名单后普通文本（无 NUL）应可打开",
            FileRepository.unopenableReason(newFile("mystery2.xyz", bytes = "hello".toByteArray())),
        )
    }

    @Test
    fun `超过默认 10MB 上限的文件被拦截 - 整读必卡 UI`() {
        val big = File(root, "huge.txt")
        big.writeBytes(ByteArray(10 * 1024 * 1024 + 1))

        val reason = FileRepository.unopenableReason(big)

        assertNotNull("超过默认上限的文件应被拦", reason)
        assertTrue("提示语应写明 10MB 上限，实际：$reason", reason!!.contains("10MB"))
    }

    @Test
    fun `自定义大小上限生效 - 注入 1MB 后按新口径拦截`() {
        FileRepository.customMaxOpenBytes = 1L * 1024 * 1024
        val overLimit = File(root, "over.txt")
        overLimit.writeBytes(ByteArray(1024 * 1024 + 1))
        val underLimit = File(root, "under.txt")
        underLimit.writeBytes(ByteArray(1024 * 1024 - 1))

        assertTrue(
            "超过自定义上限应被拦",
            FileRepository.unopenableReason(overLimit)!!.contains("1MB"),
        )
        assertNull("未超上限的文本应可打开", FileRepository.unopenableReason(underLimit))
    }

    @Test
    fun `不存在的文件给出不可读提示`() {
        assertNotNull(FileRepository.unopenableReason(File(root, "ghost.txt")))
    }
}
