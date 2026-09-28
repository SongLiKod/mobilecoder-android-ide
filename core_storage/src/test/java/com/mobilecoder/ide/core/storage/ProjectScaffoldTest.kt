package com.mobilecoder.ide.core.storage

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 新模板脚手架（Vue / HTML / Node / Flutter）落盘结果。 */
class ProjectScaffoldTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** 仅 scaffold 需要的仓库实例：不触碰 DataStore 的读写方法。 */
    private fun repository(): ProjectRepository {
        val dataStore = object : DataStore<Preferences> {
            private val state = MutableStateFlow(emptyPreferences())
            override val data: Flow<Preferences> get() = state
            override suspend fun updateData(
                transform: suspend (t: Preferences) -> Preferences,
            ): Preferences {
                val next = transform(state.value)
                state.value = next
                return next
            }
        }
        return ProjectRepository(
            paths = StoragePaths.from(File(tmp.root, "files"), File(tmp.root, "cache")),
            dataStore = dataStore,
        )
    }

    private fun projectDir(name: String): File = File(tmp.root, name).apply { mkdirs() }

    @Test
    fun `Vue 模板生成 vite 工程`() {
        val root = projectDir("vue-demo")
        val written = repository().scaffold(root, ProjectTemplate.VUE_VITE)

        assertTrue("应写出多个文件", written >= 6)
        val pkg = File(root, "package.json").readText()
        assertTrue(pkg.contains("\"vite\""))
        assertTrue(pkg.contains("\"vue\""))
        assertTrue(File(root, "vite.config.js").exists())
        assertTrue(File(root, "index.html").exists())
        assertTrue(File(root, "src/App.vue").readText().contains("<template>"))
        assertTrue(File(root, "src/main.js").readText().contains("createApp"))
        assertTrue(File(root, ".gitignore").readText().contains("node_modules/"))
        assertTrue(File(root, "README.md").readText().contains("npm install"))
    }

    @Test
    fun `静态 HTML 模板生成三件套`() {
        val root = projectDir("site")
        repository().scaffold(root, ProjectTemplate.STATIC_HTML)

        assertTrue(File(root, "index.html").readText().contains("css/style.css"))
        assertTrue(File(root, "css/style.css").exists())
        assertTrue(File(root, "js/main.js").readText().contains("DOMContentLoaded"))
    }

    @Test
    fun `Node 模板不带前端依赖`() {
        val root = projectDir("node-app")
        repository().scaffold(root, ProjectTemplate.NODE_APP)

        val pkg = File(root, "package.json").readText()
        assertTrue(pkg.contains("\"start\""))
        assertTrue("不应引入 vue/vite 依赖", !pkg.contains("vue"))
        assertTrue(File(root, "index.js").readText().contains("node:http"))
    }

    @Test
    fun `Flutter 模板生成 pubspec 与入口`() {
        val root = projectDir("My Flutter App")
        repository().scaffold(root, ProjectTemplate.FLUTTER)

        val pubspec = File(root, "pubspec.yaml").readText()
        assertTrue(pubspec.contains("flutter:"))
        assertTrue("包名应为 snake_case", pubspec.contains("name: my_flutter_app"))
        val main = File(root, "lib/main.dart").readText()
        assertTrue(main.contains("runApp"))
        assertTrue(main.contains("My Flutter App"))
        assertTrue(File(root, "analysis_options.yaml").exists())
        assertTrue(File(root, ".gitignore").readText().contains(".dart_tool/"))
    }

    @Test
    fun `安卓模板仍然可用且重复执行不覆盖`() {
        val root = projectDir("android-app")
        val repo = repository()
        val first = repo.scaffold(root, ProjectTemplate.ANDROID_APP)
        assertTrue(first > 0)
        assertTrue(File(root, "settings.gradle.kts").exists())
        assertTrue(File(root, "app/src/main/AndroidManifest.xml").exists())

        assertEquals("同名文件不应被覆盖", 0, repo.scaffold(root, ProjectTemplate.ANDROID_APP))
    }

    @Test
    fun `模板名反序列化兼容旧索引`() {
        assertEquals(ProjectTemplate.VUE_VITE, ProjectTemplate.fromName("VUE_VITE"))
        assertEquals(ProjectTemplate.FLUTTER, ProjectTemplate.fromName("flutter"))
        assertEquals("未知值回落到安卓应用", ProjectTemplate.ANDROID_APP, ProjectTemplate.fromName("老模板"))
        assertEquals(ProjectTemplate.ANDROID_APP, ProjectTemplate.fromName(null))
    }
}
