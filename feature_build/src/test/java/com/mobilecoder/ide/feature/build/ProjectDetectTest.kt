package com.mobilecoder.ide.feature.build

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** [detectProject]：构建页按项目结构动态识别类型 / 语言 / 所需环境。 */
class ProjectDetectTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun dir(name: String): File = tmp.newFolder(name)

    private fun File.touch(name: String, text: String = ""): File =
        File(this, name).apply { parentFile?.mkdirs(); writeText(text) }

    @Test
    fun androidGradleProject_detectedAsAndroidApp() {
        val root = dir("android")
        root.touch("settings.gradle.kts", "rootProject.name = \"android\"")
        root.touch("app/src/main/AndroidManifest.xml", "<manifest package=\"x\"/>")

        val profile = detectProject(root)
        assertEquals(ProjectKind.ANDROID_APP, profile.kind)
        assertEquals(listOf(EnvKind.JDK, EnvKind.GRADLE, EnvKind.SDK), profile.requiredEnv)
        assertEquals("APK", profile.artifactLabel)
        assertTrue(profile.usesGradle)
        assertTrue(profile.usesShell)
        assertNull(profile.hint)
    }

    @Test
    fun plainGradleProject_detectedAsJvmGradle() {
        val root = dir("kotlin-cli")
        root.touch("settings.gradle.kts", "rootProject.name = \"kotlin-cli\"")

        val profile = detectProject(root)
        assertEquals(ProjectKind.JVM_GRADLE, profile.kind)
        assertEquals(listOf(EnvKind.JDK, EnvKind.GRADLE), profile.requiredEnv)
        assertEquals("JAR", profile.artifactLabel)
        assertTrue(profile.usesGradle)
    }

    @Test
    fun vitePackageJson_detectedAsNodeWithNodeEnv() {
        val root = dir("vue-app")
        root.touch(
            "package.json",
            """{"name":"vue-app","scripts":{"dev":"vite","build":"vite build"},"devDependencies":{"vite":"^5.0.0"}}""",
        )

        val profile = detectProject(root)
        assertEquals(ProjectKind.NODE, profile.kind)
        assertEquals("Vue / Vite（Node.js）", profile.displayName)
        assertEquals(listOf(EnvKind.NODE), profile.requiredEnv)
        assertEquals("dist 压缩包", profile.artifactLabel)
        assertTrue(profile.usesShell)
    }

    @Test
    fun packageJsonWithoutBuildScript_fallsBackToPackaging() {
        val root = dir("node-app")
        root.touch("package.json", """{"scripts":{"start":"node index.js"}}""")

        val profile = detectProject(root)
        assertEquals(ProjectKind.ZIP_PACKAGE, profile.kind)
        assertEquals("Node.js", profile.displayName)
        assertEquals(emptyList<EnvKind>(), profile.requiredEnv)
        assertNotNull(profile.hint)
    }

    @Test
    fun flutterProject_packagingWithHint() {
        val root = dir("flutter-app")
        root.touch("pubspec.yaml", "name: app\ndependencies:\n  flutter:\n    sdk: flutter\n")

        val profile = detectProject(root)
        assertEquals(ProjectKind.ZIP_PACKAGE, profile.kind)
        assertEquals("Flutter 应用", profile.displayName)
        assertNotNull(profile.hint)
        assertTrue(profile.hint!!.contains("Flutter"))
    }

    @Test
    fun staticHtml_detectedAsPackagingWithoutEnv() {
        val root = dir("site")
        root.touch("index.html", "<!doctype html>")

        val profile = detectProject(root)
        assertEquals(ProjectKind.ZIP_PACKAGE, profile.kind)
        assertEquals("静态 HTML", profile.displayName)
        assertEquals(emptyList<EnvKind>(), profile.requiredEnv)
        assertNull(profile.hint)
    }

    @Test
    fun pythonMarkers_packagingWithPythonHint() {
        val root = dir("crawler")
        root.touch("requirements.txt", "requests\n")

        val profile = detectProject(root)
        assertEquals(ProjectKind.ZIP_PACKAGE, profile.kind)
        assertEquals("Python", profile.displayName)
        assertNotNull(profile.hint)
        assertTrue(profile.hint!!.contains("Python"))
    }

    @Test
    fun unknownProject_packagingWithGuidanceHint() {
        val root = dir("mystery")

        val profile = detectProject(root)
        assertEquals(ProjectKind.ZIP_PACKAGE, profile.kind)
        assertEquals("未识别类型", profile.displayName)
        assertNotNull(profile.hint)
    }

    @Test
    fun gradleBeatsPackageJson_androidCheckFirst() {
        // 同根下同时有 Gradle 与 package.json → 以 Gradle（安卓/JVM）优先
        val root = dir("hybrid")
        root.touch("build.gradle", "plugins { id 'com.android.application' }")
        root.touch("package.json", """{"scripts":{"build":"vite build"}}""")

        assertEquals(ProjectKind.ANDROID_APP, detectProject(root).kind)
    }

    @Test
    fun hasNpmBuildScript_matchesOnlyScriptsSection() {
        assertTrue(hasNpmBuildScript("""{"scripts":{"build":"vite build"}}"""))
        assertTrue(hasNpmBuildScript("{\n  \"scripts\": {\n    \"build\": \"tsc\"\n  }\n}"))
        // build 出现在 dependencies 不算
        assertFalse(hasNpmBuildScript("""{"dependencies":{"webpack-build-tool":"1.0"}}"""))
        assertFalse(hasNpmBuildScript("""{"scripts":{"start":"node index.js"}}"""))
    }
}
