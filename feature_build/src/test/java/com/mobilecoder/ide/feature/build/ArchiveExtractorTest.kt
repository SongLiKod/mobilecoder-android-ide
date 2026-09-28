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
}
