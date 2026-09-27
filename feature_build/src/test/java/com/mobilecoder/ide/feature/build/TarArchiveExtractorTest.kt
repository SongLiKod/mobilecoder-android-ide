package com.mobilecoder.ide.feature.build

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.util.zip.GZIPOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [ArchiveExtractor] 的 tar.gz 路径（手工构造 ustar 头，不依赖外部工具）：
 *  - 512 字节对齐填充必须被跳过，否则第二个条目起全部串位；
 *  - GNU 'L' 长文件名与目录条目。
 */
class TarArchiveExtractorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun tarGz_roundTripWithPaddedEntries() {
        // size 都不是 512 的倍数 → 验证填充跳过后第二个条目不串位
        val first = "gradle-8.9".toByteArray()
        val second = "second-content".toByteArray()
        val bytes = buildTarGz { gz ->
            gz.entry("gradle-8.9/NOTICE", first)
            gz.entry("gradle-8.9/THIRD-PARTY", second)
            gz.write(ByteArray(1024)) // 两个全零块 = 归档结束
        }

        val dest = tmp.newFolder("tar-out")
        val result = ArchiveExtractor.extract(ByteArrayInputStream(bytes), dest, bytes.size.toLong())

        assertEquals(2, result.entries)
        assertEquals("gradle-8.9", File(dest, "gradle-8.9/NOTICE").readText())
        assertEquals("second-content", File(dest, "gradle-8.9/THIRD-PARTY").readText())
    }

    @Test
    fun tarGz_gnuLongNameAndDirectoryEntries() {
        val longName = "platforms/android-35/data/" + "x".repeat(90) + ".xml" // >100 字节，需 'L' 头
        val content = "platform-data".toByteArray()
        val bytes = buildTarGz { gz ->
            gz.write(tarHeader("platforms/android-35/data", 0L, type = '5'))
            val namePayload = (longName + " ").toByteArray(Charsets.UTF_8)
            gz.write(tarHeader("././@LongLink", namePayload.size.toLong(), type = 'L', mode = 0L))
            gz.write(namePayload)
            gz.pad(namePayload.size.toLong())
            gz.write(tarHeader(longName, content.size.toLong()))
            gz.write(content)
            gz.pad(content.size.toLong())
            gz.write(ByteArray(1024))
        }

        val dest = tmp.newFolder("tar-long")
        val result = ArchiveExtractor.extract(ByteArrayInputStream(bytes), dest, bytes.size.toLong())

        // 'L' 是元数据、'5' 目录条目不计数（与 zip 路径一致）→ 仅文件计 1
        assertEquals(1, result.entries)
        assertEquals("platform-data", File(dest, longName).readText())
        assertTrue(File(dest, "platforms/android-35/data").isDirectory)
    }

    // ------------------------------------------------------------------
    // 构造工具
    // ------------------------------------------------------------------

    private fun buildTarGz(block: (GZIPOutputStream) -> Unit): ByteArray =
        ByteArrayOutputStream().also { bos ->
            GZIPOutputStream(bos).use { gz -> block(gz) }
        }.toByteArray()

    /** 写入「头 + 数据 + 512 对齐填充」。 */
    private fun GZIPOutputStream.entry(name: String, data: ByteArray) {
        write(tarHeader(name, data.size.toLong()))
        write(data)
        pad(data.size.toLong())
    }

    private fun GZIPOutputStream.pad(size: Long) {
        val rem = size % 512
        if (rem != 0L) write(ByteArray((512 - rem).toInt()))
    }

    /** 构造 512 字节 ustar 头（name/mode/size/typeflag + 校验和）。 */
    private fun tarHeader(
        name: String,
        size: Long,
        type: Char = '0',
        mode: Long = 420, // 0644 rw-r--r--
    ): ByteArray {
        val h = ByteArray(512)
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        System.arraycopy(nameBytes, 0, h, 0, minOf(nameBytes.size, 100))
        writeOctal(h, 100, 8, mode)
        writeOctal(h, 108, 8, 0)
        writeOctal(h, 116, 8, 0)
        writeOctal(h, 124, 12, size)
        writeOctal(h, 136, 12, 0)
        for (i in 148..155) h[i] = ' '.code.toByte()
        h[156] = type.code.toByte()
        val magic = "ustar ".toByteArray()
        System.arraycopy(magic, 0, h, 257, magic.size)
        var sum = 0
        for (b in h) sum += b.toInt() and 0xff
        val chk = sum.toString(8).padStart(6, '0').toByteArray()
        System.arraycopy(chk, 0, h, 148, 6)
        h[154] = 0
        h[155] = ' '.code.toByte()
        return h
    }

    /** 八进制数字字段（右对齐，NUL 结尾）。 */
    private fun writeOctal(buf: ByteArray, off: Int, len: Int, value: Long) {
        val digits = value.toString(8).padStart(len - 1, '0').toByteArray()
        System.arraycopy(digits, 0, buf, off, minOf(digits.size, len - 1))
        buf[off + len - 1] = 0
    }
}
