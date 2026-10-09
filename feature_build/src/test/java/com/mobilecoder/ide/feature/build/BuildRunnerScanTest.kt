package com.mobilecoder.ide.feature.build

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** [BuildRunner.scanArtifacts] / [BuildRunner.scanApks] / [BuildRunner.relativeModule]。 */
class BuildRunnerScanTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun File.put(name: String, size: Int = 4): File =
        File(this, name).apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(size) { 1 })
        }

    @Test
    fun androidScan_filtersByVariant_fallsBackToAll() {
        val root = tmp.newFolder("android")
        root.put("app/build/outputs/apk/debug/app-debug.apk", 10)
        root.put("app/build/outputs/apk/release/app-release.apk", 20)

        val debug = BuildRunner.scanArtifacts(root, ProjectKind.ANDROID_APP, "debug")
        assertEquals(listOf("app-debug.apk"), debug.map { it.name })
        assertEquals("app", debug.single().module)
        assertEquals("debug", debug.single().variant)

        val release = BuildRunner.scanArtifacts(root, ProjectKind.ANDROID_APP, "release")
        assertEquals(listOf("app-release.apk"), release.map { it.name })
    }

    @Test
    fun androidScan_ignoresApkOutsideOutputsDirectory() {
        val root = tmp.newFolder("android2")
        root.put("random/place/app.apk")
        assertTrue(BuildRunner.scanArtifacts(root, ProjectKind.ANDROID_APP, "debug").isEmpty())
    }

    @Test
    fun jvmScan_collectsJar_excludesSourcesAndJavadoc() {
        val root = tmp.newFolder("jvm")
        root.put("build/libs/my-lib.jar")
        root.put("build/libs/my-lib-sources.jar")
        root.put("build/libs/my-lib-javadoc.jar")
        // 根 build/libs → 根模块（module 为空串）
        root.put("other/build/libs/other.jar")

        val jars = BuildRunner.scanArtifacts(root, ProjectKind.JVM_GRADLE, "assemble")
        assertEquals(setOf("my-lib.jar", "other.jar"), jars.map { it.name }.toSet())
        assertTrue(jars.all { it.variant == "assemble" })
        val myLib = jars.first { it.name == "my-lib.jar" }
        assertEquals("", myLib.module)
    }

    @Test
    fun packagingScan_collectsZipsUnderOutputsPackage() {
        val root = tmp.newFolder("site")
        root.put("build/outputs/package/site.zip")
        root.put("build/outputs/package/site-dist.zip")
        root.put("build/outputs/other/ignore.txt")

        val zips = BuildRunner.scanArtifacts(root, ProjectKind.ZIP_PACKAGE, "package")
        assertEquals(setOf("site.zip", "site-dist.zip"), zips.map { it.name }.toSet())
        assertTrue(zips.all { it.variant == "package" })
        assertTrue(zips.all { it.module.isEmpty() })
    }

    @Test
    fun relativeModule_rootBuildIsBlank_moduleBuildIsPrefix() {
        val root = tmp.newFolder("mod")
        val nested = root.put("app/build/outputs/apk/debug/app-debug.apk")
        val rootLevel = root.put("build/libs/lib.jar")

        assertEquals("app", BuildRunner.relativeModule(root, nested))
        assertEquals("", BuildRunner.relativeModule(root, rootLevel))
    }
}
