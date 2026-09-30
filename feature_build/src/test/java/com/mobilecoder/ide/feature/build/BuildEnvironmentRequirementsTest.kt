package com.mobilecoder.ide.feature.build

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** [BuildEnvironment.requirementsFor] / [BuildEnvironment.isAndroidProject] 的动态需求推导。 */
class BuildEnvironmentRequirementsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun dir(name: String): File = tmp.newFolder(name)

    private fun File.touch(name: String, text: String = ""): File =
        File(this, name).apply { parentFile?.mkdirs(); writeText(text) }

    @Test
    fun nullProject_requiresAllThree() {
        assertEquals(
            listOf(EnvKind.JDK, EnvKind.GRADLE, EnvKind.SDK),
            BuildEnvironment.requirementsFor(null),
        )
    }

    @Test
    fun androidGradleProject_requiresJdkGradleSdk() {
        val root = dir("android")
        root.touch("settings.gradle")
        root.touch("app/src/main/AndroidManifest.xml", "<manifest package=\"x\"/>")

        assertEquals(
            listOf(EnvKind.JDK, EnvKind.GRADLE, EnvKind.SDK),
            BuildEnvironment.requirementsFor(root),
        )
        assertTrue(BuildEnvironment.isAndroidProject(root))
    }

    @Test
    fun plainGradleProject_doesNotRequireSdk() {
        val root = dir("jvm-lib")
        root.touch("settings.gradle.kts", "rootProject.name = \"jvm-lib\"")

        assertEquals(
            listOf(EnvKind.JDK, EnvKind.GRADLE),
            BuildEnvironment.requirementsFor(root),
        )
        assertFalse(BuildEnvironment.isAndroidProject(root))
    }

    @Test
    fun androidPluginReference_countsAsAndroidProject() {
        val root = dir("plugin-only")
        root.touch("build.gradle", "plugins { id 'com.android.application' }")

        assertTrue(BuildEnvironment.isAndroidProject(root))
        assertEquals(
            listOf(EnvKind.JDK, EnvKind.GRADLE, EnvKind.SDK),
            BuildEnvironment.requirementsFor(root),
        )
    }

    @Test
    fun nodeProject_requiresOnlyNode() {
        val root = dir("web")
        root.touch("package.json", "{\"scripts\":{\"build\":\"vite build\"}}")

        assertEquals(listOf(EnvKind.NODE), BuildEnvironment.requirementsFor(root))
    }

    @Test
    fun unrecognizedProject_defaultsToAndroidToolchain() {
        val root = dir("empty")
        assertEquals(
            listOf(EnvKind.JDK, EnvKind.GRADLE, EnvKind.SDK),
            BuildEnvironment.requirementsFor(root),
        )
    }

    @Test
    fun withLinuxIfNeeded_appendsLinuxExactlyOnce() {
        // LINUX 不由 requirementsFor 决定，而是在体检/下载入口恒并入（终端、npm、
        // sdkmanager、构建里的 java 全部从 proot 走 → 所有项目的硬依赖）
        val base = BuildEnvironment.requirementsFor(null)
        assertFalse(base.contains(EnvKind.LINUX))

        val withLinux = BuildEnvironment.withLinuxIfNeeded(base)
        assertEquals(EnvKind.LINUX, withLinux.last())
        assertEquals(base, withLinux.dropLast(1))

        // 幂等：已有 LINUX 时原样返回，不重复追加
        assertEquals(withLinux, BuildEnvironment.withLinuxIfNeeded(withLinux))
        // 只要 LINUX 的场景（纯终端）也不再追加第二份
        assertEquals(listOf(EnvKind.LINUX), BuildEnvironment.withLinuxIfNeeded(listOf(EnvKind.LINUX)))
    }
}
