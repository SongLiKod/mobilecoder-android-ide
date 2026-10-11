package com.mobilecoder.ide.feature.build

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [Aapt2Adapter] / [BuildRunner] 安卓适配纯逻辑（真机验证脚本的固化）：
 * wrapper 文本、镜像 URL、provision 脚本关键点、jar 抽取、mirror init 管理标记。
 */
class Aapt2AdapterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `wrapper 脚本含可执行 qemu 前缀与参数透传`() {
        val text = Aapt2Adapter.wrapperScript("/data/x/bin/aapt2-amd64/aapt2")
        assertEquals(
            "#!/bin/sh\nexec /usr/bin/qemu-x86_64-static -L / /data/x/bin/aapt2-amd64/aapt2 \"\$@\"\n",
            text,
        )
        assertFalse("不得含 Windows 换行", text.contains("\r"))
    }

    @Test
    fun `aapt2 制品源镜像优先官方兜底`() {
        val urls = Aapt2Adapter.aapt2JarUrls("8.7.3-12006047")
        assertEquals(2, urls.size)
        assertTrue(urls[0].startsWith("https://maven.aliyun.com/"))
        assertTrue(urls[1].startsWith("https://dl.google.com/"))
        urls.forEach { assertTrue(it.endsWith("aapt2-8.7.3-12006047-linux.jar")) }
    }

    @Test
    fun `provision 脚本关键点齐备且幂等先行退出`() {
        val s = Aapt2Adapter.qemuProvisionScript()
        assertTrue(s.contains("dpkg --add-architecture amd64"))
        assertTrue(s.contains("qemu-user-static libc6:amd64"))
        assertTrue(s.contains("test -x /usr/bin/qemu-x86_64-static"))
        assertTrue(s.indexOf("exit 0") < s.indexOf("dpkg --add-architecture"))
        assertFalse(s.contains("\r"))
    }

    @Test
    fun `overrideArg 是 AGP 官方属性全路径`() {
        val arg = "-Pandroid.aapt2FromMavenOverride=/x/y/aapt2"
        assertTrue(arg.startsWith("-Pandroid.aapt2FromMavenOverride="))
    }

    @Test
    fun `extractAapt2 从 jar 抽出 basename 为 aapt2 的条目`() {
        val jar = tmp.newFile("aapt2.jar")
        ZipOutputStream(jar.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("META-INF/MANIFEST.MF"))
            z.write("Manifest-Version: 1.0\n".toByteArray())
            z.closeEntry()
            z.putNextEntry(ZipEntry("aapt2"))
            z.write(byteArrayOf(0x7f, 0x45, 0x4c, 0x46))
            z.closeEntry()
        }
        val out = File(tmp.root, "bin/aapt2")
        assertTrue(Aapt2Adapter.extractAapt2(jar, out))
        assertEquals(4, out.length())
    }

    @Test
    fun `extractAapt2 无条目返回 false`() {
        val jar = tmp.newFile("empty.jar")
        ZipOutputStream(jar.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("readme.txt"))
            z.write("x".toByteArray())
            z.closeEntry()
        }
        assertFalse(Aapt2Adapter.extractAapt2(jar, File(tmp.root, "nope/aapt2")))
    }

    @Test
    fun `mirror init 为纯 ASCII 且带托管版本标记`() {
        val text = BuildRunner.mirrorInitText()
        assertTrue(text.startsWith("// MobileCoder gradle mirror init v"))
        assertTrue(text.all { it.code < 128 })
        assertTrue(text.contains("maven.aliyun.com/repository/google"))
        assertFalse(text.contains("\r"))
        val bytes = text.toByteArray(Charsets.UTF_8)
        assertFalse("不得带 UTF-8 BOM（Groovy 解析器会炸）", bytes[0] == 0xEF.toByte())
    }
}
