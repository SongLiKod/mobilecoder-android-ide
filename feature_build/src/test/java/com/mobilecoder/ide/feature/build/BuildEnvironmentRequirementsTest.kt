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
    fun unrecognizedProject_requiresNoToolchainForPackaging() {
        // 未识别类型 → 源码打包即可，不再兜底要求安卓工具链（动态匹配环境）
        val root = dir("empty")
        assertEquals(emptyList<EnvKind>(), BuildEnvironment.requirementsFor(root))
    }

    @Test
    fun staticHtmlProject_requiresNoToolchain() {
        val root = dir("site")
        root.touch("index.html", "<!doctype html>")
        assertEquals(emptyList<EnvKind>(), BuildEnvironment.requirementsFor(root))
    }

    @Test
    fun nodeProjectWithoutBuildScript_requiresNoToolchain() {
        // 无 build 脚本的 Node 工程按源码打包处理，不需要 Node 运行时
        val root = dir("node-app")
        root.touch("package.json", "{\"scripts\":{\"start\":\"node index.js\"}}")
        assertEquals(emptyList<EnvKind>(), BuildEnvironment.requirementsFor(root))
    }

    @Test
    fun withLinuxIfNeeded_appendsLinuxExactlyOnce() {
        // LINUX 不由 requirementsFor 决定，而是在体检入口并入（终端、npm、sdkmanager、
        // 构建里的 java 全部从 proot 走 → 需要子进程的项目的硬依赖）
        val base = BuildEnvironment.requirementsFor(null)
        assertFalse(base.contains(EnvKind.LINUX))

        val withLinux = BuildEnvironment.withLinuxIfNeeded(base)
        assertEquals(EnvKind.LINUX, withLinux.last())
        assertEquals(base, withLinux.dropLast(1))

        // 幂等：已有 LINUX 时原样返回，不重复追加
        assertEquals(withLinux, BuildEnvironment.withLinuxIfNeeded(withLinux))
        // 只要 LINUX 的场景（纯终端）也不再追加第二份
        assertEquals(listOf(EnvKind.LINUX), BuildEnvironment.withLinuxIfNeeded(listOf(EnvKind.LINUX)))
        // 纯打包类项目（无需求）不追加 Linux —— 进程内 zip 不需要任何子进程环境
        assertEquals(emptyList<EnvKind>(), BuildEnvironment.withLinuxIfNeeded(emptyList()))
    }
}
