package com.mobilecoder.ide.feature.build

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [NativeToolchain] 纯逻辑：wrapper dist 哈希（与真机 .gradle 实际目录比对过）、
 * gradle 版本反推、CMakeLists 浅层探测、NDK 需求推断。
 */
class NativeToolchainTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `wrapperDistHash matches gradle PathAssembler for 8_9`() {
        // 真机与开发机 ~/.gradle/wrapper/dists/gradle-8.9-bin/<hash> 实测值
        assertEquals(
            "90cnw93cvbtalezasaz0blq0a",
            NativeToolchain.wrapperDistHash(
                "https://services.gradle.org/distributions/gradle-8.9-bin.zip",
            ),
        )
    }

    @Test
    fun `gradleVersionOfDir reads launcher jar name`() {
        val dir = tmp.newFolder("gradle")
        File(dir, "lib").mkdirs()
        File(dir, "lib/gradle-launcher-8.9.jar").createNewFile()
        File(dir, "lib/kotlin-stdlib.jar").createNewFile()
        assertEquals("8.9", NativeToolchain.gradleVersionOfDir(dir))
    }

    @Test
    fun `gradleVersionOfDir returns null when absent`() {
        val dir = tmp.newFolder("empty-gradle")
        File(dir, "lib").mkdirs()
        assertNull(NativeToolchain.gradleVersionOfDir(dir))
    }

    @Test
    fun `hasCMakeLists finds root app and module layouts`() {
        val root = tmp.newFolder("proj")
        assertFalse(NativeToolchain.hasCMakeLists(root))

        File(root, "src/main/cpp").mkdirs()
        File(root, "src/main/cpp/CMakeLists.txt").createNewFile()
        assertTrue(NativeToolchain.hasCMakeLists(root))
    }

    @Test
    fun `hasCMakeLists finds app module`() {
        val root = tmp.newFolder("proj2")
        File(root, "app/src/main/cpp").mkdirs()
        File(root, "app/src/main/cpp/CMakeLists.txt").createNewFile()
        assertTrue(NativeToolchain.hasCMakeLists(root))
    }

    @Test
    fun `hasCMakeLists finds library module`() {
        val root = tmp.newFolder("proj3")
        File(root, "core_native/src/main/cpp").mkdirs()
        File(root, "core_native/src/main/cpp/CMakeLists.txt").createNewFile()
        assertTrue(NativeToolchain.hasCMakeLists(root))
    }

    @Test
    fun `ninja candidates order follows source preference`() {
        val mirrorFirst = NativeToolchain.ndkCandidates(EnvSource.MIRROR)
        assertTrue(mirrorFirst.first().url.contains("mirrors.cloud.tencent.com"))
        val officialFirst = NativeToolchain.ndkCandidates(EnvSource.OFFICIAL)
        assertTrue(officialFirst.first().url.startsWith("https://dl.google.com/"))
        assertEquals(2, mirrorFirst.size)
    }
}
