package com.mobilecoder.ide.feature.build

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** [ArchiveExtractor] 的 zip 路径：往返解压、路径穿越（zip slip）拒绝、未知格式拒绝。 */
class ArchiveExtractorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun zip_roundTripWithDirectories() {
        val zip = ByteArrayOutputStream().use { bos ->
            ZipOutputStream(bos).use { zos ->
                zos.putNextEntry(ZipEntry("readme.txt"))
                zos.write("hello-zip".toByteArray())
                zos.closeEntry()
                zos.putNextEntry(ZipEntry("gradle/wrapper/gradle-wrapper.jar"))
                zos.write("jar-bytes".toByteArray())
                zos.closeEntry()
            }
            bos.toByteArray()
        }
        val dest = tmp.newFolder("zip-out")
        val result = ArchiveExtractor.extract(ByteArrayInputStream(zip), dest, zip.size.toLong())

        assertEquals(2, result.entries)
        assertEquals("hello-zip", File(dest, "readme.txt").readText())
        assertEquals("jar-bytes", File(dest, "gradle/wrapper/gradle-wrapper.jar").readText())
        assertTrue(result.readBytes > 0)
    }

    @Test
    fun zip_rejectsPathTraversal() {
        val zip = ByteArrayOutputStream().use { bos ->
            ZipOutputStream(bos).use { zos ->
                zos.putNextEntry(ZipEntry("../evil.txt"))
                zos.write("bad".toByteArray())
                zos.closeEntry()
            }
            bos.toByteArray()
        }
        val dest = tmp.newFolder("zip-slip")
        val error = runCatching {
            ArchiveExtractor.extract(ByteArrayInputStream(zip), dest, 0L)
        }.exceptionOrNull()

        assertTrue("应拒绝路径穿越条目", error is SecurityException)
        assertTrue(error!!.message!!.contains("路径穿越"))
        assertFalse(File(dest.parentFile, "evil.txt").exists())
    }

    @Test
    fun zip_rejectsUnknownFormat() {
        val dest = tmp.newFolder("unknown")
        val error = runCatching {
            ArchiveExtractor.extract(ByteArrayInputStream("not-an-archive".toByteArray()), dest, 0L)
        }.exceptionOrNull()

        assertTrue(error is IOException)
    }

    // ------------------------------------------------------------------
    // .deb（ar + data.tar[.xz|.gz]）—— glibc 运行时在线组装走 Debian 镜像
    // ------------------------------------------------------------------

    @Test
    fun deb_extractUncompressedDataTar_skipsControlAndKeepsAlignment() {
        // debian-binary + 奇数字节的 control.tar.xz（验证 ar 偶数对齐跳过）+ data.tar
        val tar = tarOf("usr/lib/ld-linux-aarch64.so.1" to "loader-bytes".toByteArray())
        val deb = arOf(
            "debian-binary" to "2.0\n".toByteArray(),
            "control.tar.xz" to byteArrayOf(0x01), // 奇数 → 后面补 1 字节
            "data.tar" to tar,
        )
        val dest = tmp.newFolder("deb-plain")
        val result = ArchiveExtractor.extractDeb(ByteArrayInputStream(deb), dest, deb.size.toLong())

        assertEquals(1, result.entries)
        assertEquals("loader-bytes", File(dest, "usr/lib/ld-linux-aarch64.so.1").readText())
        assertTrue(result.readBytes > 0)
    }

    @Test
    fun deb_extractXzDataTar() {
        val tar = tarOf("usr/lib/libc.so.6" to "libc-bytes".toByteArray())
        val xz = ByteArrayOutputStream().use { bos ->
            org.tukaani.xz.XZOutputStream(bos, org.tukaani.xz.LZMA2Options(0)).use { it.write(tar) }
            bos.toByteArray()
        }
        val deb = arOf(
            "debian-binary" to "2.0\n".toByteArray(),
            "control.tar.xz" to byteArrayOf(0x09, 0x09), // 偶数 → 无补位
            "data.tar.xz" to xz,
        )
        val dest = tmp.newFolder("deb-xz")
        val result = ArchiveExtractor.extractDeb(ByteArrayInputStream(deb), dest, deb.size.toLong())

        assertEquals(1, result.entries)
        assertEquals("libc-bytes", File(dest, "usr/lib/libc.so.6").readText())
    }

    @Test
    fun deb_rejectsNonArInput() {
        val dest = tmp.newFolder("deb-bad")
        val error = runCatching {
            ArchiveExtractor.extractDeb(ByteArrayInputStream("not-a-deb".toByteArray()), dest, 0L)
        }.exceptionOrNull()

        assertTrue(error is IOException)
        assertTrue(error!!.message!!.contains(".deb"))
    }

    @Test
    fun deb_dotPrefixedEntries_andRootEntry_fromRealDataTar() {
        // 真实 Debian data.tar 条目形如 "./usr/…"，且首条目是根目录 "./"（曾误触发路径穿越）
        val tar = tarOf(
            "./" to ByteArray(0),
            "./usr/lib/ld-linux-aarch64.so.1" to "loader".toByteArray(),
        )
        val deb = arOf("debian-binary" to "2.0\n".toByteArray(), "data.tar" to tar)
        val dest = tmp.newFolder("deb-dot")
        val result = ArchiveExtractor.extractDeb(ByteArrayInputStream(deb), dest, deb.size.toLong())

        assertEquals(1, result.entries)
        assertEquals("loader", File(dest, "usr/lib/ld-linux-aarch64.so.1").readText())
        assertTrue("根目录条目不应落成文件", dest.isDirectory)
    }

    // ---- 合成 ar / tar 字节（最小可用结构，覆盖 extractDeb 的头解析）----

    /** 单个 tar 条目：512B 头（ustar 兼容字段 + 校验和省略，本解析器不校验）+ 数据 + 512B 对齐。 */
    private fun tarEntry(name: String, content: ByteArray): ByteArray {
        val header = ByteArray(512)
        val nameBytes = name.toByteArray(Charsets.US_ASCII)
        System.arraycopy(nameBytes, 0, header, 0, minOf(100, nameBytes.size))
        writeOctal(header, 124, 12, content.size.toLong()) // size
        writeOctal(header, 136, 12, 0L) // mtime
        header[156] = '0'.code.toByte() // typeflag = 普通文件
        val padded = (content.size + 511) / 512 * 512
        val out = ByteArray(512 + padded)
        System.arraycopy(header, 0, out, 0, 512)
        System.arraycopy(content, 0, out, 512, content.size)
        return out
    }

    /** 完整 tar：若干条目 + 1024B 全零结束块。 */
    private fun tarOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        var body = ByteArray(0)
        entries.forEach { (name, content) -> body += tarEntry(name, content) }
        return body + ByteArray(1024)
    }

    /** 合成 ar 归档（.deb 容器）：`!<arch>` 魔数 + 60B 头 + 数据（奇数补 1 字节对齐）。 */
    private fun arOf(vararg members: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("!<arch>\n".toByteArray(Charsets.US_ASCII))
        members.forEach { (name, data) ->
            val header = buildString {
                append(name.padEnd(16))
                append("0".padEnd(12))
                append("0".padEnd(6))
                append("0".padEnd(6))
                append("100644".padEnd(8))
                append(data.size.toString().padEnd(10))
                append('`')
                append('\n')
            }.toByteArray(Charsets.US_ASCII)
            check(header.size == 60) { "ar 头必须 60 字节，实际 ${header.size}" }
            out.write(header)
            out.write(data)
            if (data.size % 2 == 1) out.write(0)
        }
        return out.toByteArray()
    }

    /** 按 tar 规范写八进制字段（len-1 位补零，末字节留 NUL）。 */
    private fun writeOctal(buf: ByteArray, off: Int, len: Int, value: Long) {
        val digits = value.toString(8).padStart(len - 1, '0')
        digits.forEachIndexed { i, ch -> buf[off + i] = ch.code.toByte() }
    }
}
