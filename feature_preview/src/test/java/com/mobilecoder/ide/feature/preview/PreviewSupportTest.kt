package com.mobilecoder.ide.feature.preview

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** [detectPreviewKind] / [findDevServerUrl] / [affectsPreview]：预览形态与刷新判定。 */
class PreviewSupportTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun dir(name: String): File = tmp.newFolder(name)

    private fun File.write(name: String, text: String): File =
        File(this, name).apply { parentFile?.mkdirs(); writeText(text) }

    // ---------------- detectPreviewKind ----------------

    @Test
    fun staticHtml_withoutPackageJson_isStaticHtml() {
        val root = dir("site")
        root.write("index.html", "<h1>hi</h1>")

        assertEquals(PreviewKind.STATIC_HTML, detectPreviewKind(root))
    }

    @Test
    fun viteProject_withDevScript_isDevServer_evenWithIndexHtml() {
        // Vite 的 index.html 依赖 /src/main.js 之类的模块脚本，file:// 打不开 → 必须 dev server
        val root = dir("vue")
        root.write("index.html", "<script type=\"module\" src=\"/src/main.js\"></script>")
        root.write(
            "package.json",
            """
            {
              "scripts": { "dev": "vite", "build": "vite build" },
              "dependencies": { "vue": "^3.4.0" }
            }
            """.trimIndent(),
        )

        assertEquals(PreviewKind.DEV_SERVER, detectPreviewKind(root))
    }

    @Test
    fun nodeCli_withoutDevScript_andWithoutIndexHtml_isNotPreviewable() {
        val root = dir("cli")
        root.write("package.json", """{ "scripts": { "start": "node index.js" } }""")
        root.write("index.js", "console.log('hi')")

        assertEquals(PreviewKind.NONE, detectPreviewKind(root))
    }

    @Test
    fun packageJsonWithoutDevScript_butWithIndexHtml_isStaticHtml() {
        val root = dir("docs")
        root.write("package.json", """{ "name": "docs" }""")
        root.write("index.html", "<h1>docs</h1>")

        assertEquals(PreviewKind.STATIC_HTML, detectPreviewKind(root))
    }

    @Test
    fun emptyOrMissingDir_isNotPreviewable() {
        assertEquals(PreviewKind.NONE, detectPreviewKind(dir("empty")))
        assertEquals(PreviewKind.NONE, detectPreviewKind(File(tmp.root, "does-not-exist")))
    }

    // ---------------- hasNpmDevScript ----------------

    @Test
    fun devScript_detectedInsideScriptsObject() {
        assertTrue(hasNpmDevScript("""{ "scripts": { "dev": "vite" } }"""))
        assertTrue(hasNpmDevScript("""{ "name": "x", "scripts": { "build": "vite build", "dev": "vite" } }"""))
        // devDependencies 里出现 dev 不能算
        assertFalse(hasNpmDevScript("""{ "devDependencies": { "vite": "^5" } }"""))
        assertFalse(hasNpmDevScript("""{ "scripts": { "build": "vite build" } }"""))
    }

    // ---------------- findDevServerUrl ----------------

    @Test
    fun viteLocalLine_yieldsNormalizedLoopbackUrl() {
        val output = """
            VITE v5.2.8  ready in 413 ms

            ➜  Local:   http://localhost:5173/
            ➜  Network: use --host to expose
        """.trimIndent()

        assertEquals("http://127.0.0.1:5173", findDevServerUrl(output))
    }

    @Test
    fun wildcardOrIpv6Host_isRewrittenToLoopback() {
        assertEquals(
            "http://127.0.0.1:3000",
            findDevServerUrl("Local: http://0.0.0.0:3000/"),
        )
        assertEquals(
            "http://127.0.0.1:5173",
            findDevServerUrl("Local: http://[::1]:5173/"),
        )
        assertEquals(
            "https://127.0.0.1:8443",
            findDevServerUrl("Server: https://127.0.0.1:8443"),
        )
    }

    @Test
    fun externalLinks_inNpmInstallOutput_areIgnored() {
        val output = """
            npm warn deprecated foo@1.0.0: see https://registry.npmjs.org/foo
            added 214 packages in 12s
        """.trimIndent()

        assertNull(findDevServerUrl(output))
    }

    @Test
    fun portlessUrl_andPortZero_areRejected() {
        assertNull(findDevServerUrl("Local: http://localhost/"))
        assertNull(findDevServerUrl("Local: http://localhost:0/"))
        assertNull(findDevServerUrl(""))
    }

    @Test
    fun wildcardHostPrecededByExplicitLoopback_prefersExplicitOne() {
        val output = """
            Local: http://127.0.0.1:5173/
            Network: http://0.0.0.0:5173/
        """.trimIndent()

        assertEquals("http://127.0.0.1:5173", findDevServerUrl(output))
    }

    // ---------------- affectsPreview ----------------

    @Test
    fun webAssets_triggerRefresh_butBuildOutputDoesNot() {
        assertTrue(affectsPreview("/data/projects/site/index.html"))
        assertTrue(affectsPreview("src\\assets\\app.css"))
        assertTrue(affectsPreview("src/components/App.vue"))
        assertTrue(affectsPreview("src/main.tsx"))

        assertFalse(affectsPreview("/data/projects/site/README.md"))
        assertFalse(affectsPreview("/data/projects/site/pubspec.yaml"))
        // 依赖安装 / 打包产物不该把预览刷屏
        assertFalse(affectsPreview("/data/projects/site/node_modules/vue/index.js"))
        assertFalse(affectsPreview("/data/projects/site/dist/assets/index.js"))
    }
}
