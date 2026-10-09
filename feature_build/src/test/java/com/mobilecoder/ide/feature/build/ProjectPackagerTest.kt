package com.mobilecoder.ide.feature.build

import java.io.File
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** [ProjectPackager]：源码 / dist 打包的排除规则与产物落位。 */
class ProjectPackagerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun File.put(name: String, text: String = "x"): File =
        File(this, name).apply { parentFile?.mkdirs(); writeText(text) }

    private fun entries(zip: File): Set<String> = ZipFile(zip).use { z ->
        z.entries().asSequence().map { it.name }.toSet()
    }

    @Test
    fun packageProject_includesSources_excludesVcsDepsAndOutputs() {
        val root = tmp.newFolder("site")
        root.put("index.html", "<html></html>")
        root.put("README.md", "# site")
        root.put("src/main.js", "console.log(1)")
        root.put(".git/config", "[core]")
        root.put(".gitignore", "node_modules/")
        root.put("node_modules/vue/index.js", "module.exports={}")
        root.put("build/leftover.txt", "stale")          // 根 build = 产物目录，排除
        root.put("app/build/out.txt", "module output")   // 模块 build（深度 2），排除
        root.put("feature/build/pkg/Real.kt", "class Real") // build 在深度 2，同树规则排除
        root.put("src/main/kotlin/demo/build/Util.kt", "fun util()") // 深层 build = 源码包，保留
        root.put("dist/bundle.js", "bundled")            // 构建产物，排除

        val zip = ProjectPackager.packageProject(root)
        assertTrue(zip.absolutePath.contains("build${File.separator}outputs${File.separator}package"))
        assertEquals("site.zip", zip.name)

        val names = entries(zip)
        assertTrue(names.contains("index.html"))
        assertTrue(names.contains("README.md"))
        assertTrue(names.contains("src/main.js"))
        assertTrue(names.contains("src/main/kotlin/demo/build/Util.kt"))
        assertFalse(names.contains(".git/config"))
        assertFalse(names.contains(".gitignore"))
        assertFalse(names.contains("node_modules/vue/index.js"))
        assertFalse(names.contains("build/leftover.txt"))
        assertFalse(names.contains("app/build/out.txt"))
        assertFalse(names.contains("feature/build/pkg/Real.kt"))
        assertFalse(names.contains("dist/bundle.js"))
    }

    @Test
    fun packageProject_sameNameOverwritesPreviousArtifact() {
        val root = tmp.newFolder("once")
        root.put("a.txt", "1")
        val first = ProjectPackager.packageProject(root)
        root.put("b.txt", "2")
        val second = ProjectPackager.packageProject(root)

        assertEquals(first.absolutePath, second.absolutePath)
        val names = entries(second)
        assertTrue(names.contains("a.txt"))
        assertTrue(names.contains("b.txt"))
        assertEquals(2, names.size)
    }

    @Test
    fun packageProject_cancelRemovesPartialOutput() {
        val root = tmp.newFolder("cancelled")
        repeat(300) { root.put("src/f$it.kt", "val v$it = $it") }

        val thrown = runCatching {
            ProjectPackager.packageProject(root, shouldCancel = { true })
        }.exceptionOrNull()
        assertTrue(thrown is PackagingCancelledException)
        assertFalse(File(ProjectPackager.outputDir(root), "cancelled.zip").exists())
    }

    @Test
    fun packageNodeDist_zipsDistDirectory() {
        val root = tmp.newFolder("vue-app")
        root.put("package.json", """{"scripts":{"build":"vite build"}}""")
        root.put("dist/index.html", "<html>app</html>")
        root.put("dist/assets/app.js", "js")

        val zip = ProjectPackager.packageNodeDist(root)
        assertNotNull(zip)
        assertEquals("vue-app-dist.zip", zip!!.name)
        val names = entries(zip)
        assertTrue(names.contains("index.html"))
        assertTrue(names.contains("assets/app.js"))
        assertFalse(names.contains("package.json"))
    }

    @Test
    fun packageNodeDist_withoutOutputReturnsNull() {
        val root = tmp.newFolder("no-out")
        root.put("package.json", """{"scripts":{"build":"vite build"}}""")
        assertNull(ProjectPackager.packageNodeDist(root))
    }

    @Test
    fun shouldSkip_rulesMatchEditorTreeConvention() {
        // 以 . 开头 / 依赖与产物目录：任意层级排除
        assertTrue(ProjectPackager.shouldSkip(".git", isDir = true))
        assertTrue(ProjectPackager.shouldSkip("src/.idea", isDir = true))
        assertTrue(ProjectPackager.shouldSkip("pkg/node_modules", isDir = true))
        assertTrue(ProjectPackager.shouldSkip("dist", isDir = true))
        // build：与 FileRepository.isArtifact 同口径 —— 深度 ≤2 是产物目录，深层是源码包
        assertTrue(ProjectPackager.shouldSkip("build", isDir = true))
        assertTrue(ProjectPackager.shouldSkip("app/build", isDir = true))
        assertTrue(ProjectPackager.shouldSkip("feature/build/pkg/Real.kt", isDir = false))
        assertFalse(ProjectPackager.shouldSkip("src/main/kotlin/demo/build/Util.kt", isDir = false))
        // 普通源码不排除
        assertFalse(ProjectPackager.shouldSkip("src/main.kt", isDir = false))
        assertFalse(ProjectPackager.shouldSkip("README.md", isDir = false))
    }
}
