package com.mobilecoder.ide.core.storage

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject

/** 项目模板（PRD 2.2 项目目录、PRD 2.7 编译打包的输入）。 */
enum class ProjectTemplate(val label: String) {
    EMPTY("空目录"),
    ANDROID_APP("安卓应用"),
    KOTLIN_CLI("Kotlin 命令行"),
    VUE_VITE("Vue 3 + Vite"),
    STATIC_HTML("静态 HTML"),
    NODE_APP("Node.js"),
    FLUTTER("Flutter 应用");

    companion object {
        fun fromName(name: String?): ProjectTemplate =
            entries.firstOrNull { it.name.equals(name, true) } ?: ANDROID_APP
    }
}

/** 项目元信息。 */
data class ProjectMeta(
    val name: String,
    /** 相对 `files/projects/` 的路径（唯一键）。 */
    val relativePath: String,
    val template: ProjectTemplate,
    val createdAt: Long,
    val lastOpenedAt: Long,
) {
    fun absolutePathOf(root: File): File = File(root, relativePath)
}

/**
 * 项目仓库：负责项目的创建 / 模板脚手架 / 列表 / 删除（PRD 2.2「项目目录」）。
 *
 * 元信息保存在 DataStore（索引稳定、启动即得），源码落盘在 `files/projects/`。
 */
class ProjectRepository(
    private val paths: StoragePaths,
    private val dataStore: DataStore<Preferences>,
) {

    val projectsRoot: File get() = paths.projects

    suspend fun list(): List<ProjectMeta> {
        val raw = dataStore.data.first()[KEY_PROJECTS] ?: return emptyList()
        val metas = runCatching { parse(raw) }.getOrDefault(emptyList())
        // 磁盘上已被删除的项目自动从索引里剔除
        val alive = metas.filter { File(paths.projects, it.relativePath).isDirectory }
        if (alive.size != metas.size) persist(alive)
        return alive.sortedByDescending { it.lastOpenedAt }
    }

    suspend fun find(relativePath: String): ProjectMeta? =
        list().firstOrNull { it.relativePath == relativePath }

    suspend fun findByAbsolutePath(absolutePath: String): ProjectMeta? =
        list().firstOrNull { it.absolutePathOf(paths.projects).path == absolutePath }

    /** 新建项目（重名自动加序号），返回元信息。 */
    suspend fun create(name: String, template: ProjectTemplate): ProjectMeta {
        val base = sanitize(name)
        val existing = list()
        var relative = base
        var index = 2
        while (existing.any { it.relativePath == relative }) {
            relative = "$base-$index"
            index++
        }
        val root = File(paths.projects, relative)
        root.mkdirs()
        scaffold(root, template)

        val meta = ProjectMeta(
            name = name.trim().ifBlank { relative },
            relativePath = relative,
            template = template,
            createdAt = System.currentTimeMillis(),
            lastOpenedAt = System.currentTimeMillis(),
        )
        persist(existing + meta)
        return meta
    }

    suspend fun markOpened(relativePath: String) {
        val metas = list()
        val updated = metas.map {
            if (it.relativePath == relativePath) it.copy(lastOpenedAt = System.currentTimeMillis()) else it
        }
        persist(updated)
    }

    /**
     * 把已存在的目录（如 `git clone` 的结果）登记为项目，不生成骨架。
     *
     * 克隆下来的目录本身就是一个完整项目，无需先新建项目再克隆；
     * 项目索引以 `files/projects/` 的相对路径为键，因此仅支持项目根下的目录。
     *
     * @param name 展示名（默认取目录名）
     * @return 成功返回元信息；目录不存在、已登记过、或不在项目根下时返回 null
     */
    suspend fun adopt(dir: File, name: String = dir.name): ProjectMeta? {
        if (!dir.isDirectory) return null
        val canonical = runCatching { dir.canonicalFile }.getOrDefault(dir)
        val relative = runCatching {
            canonical.relativeTo(runCatching { paths.projects.canonicalFile }
                .getOrDefault(paths.projects)).path
        }.getOrNull() ?: return null
        if (relative.isBlank()) return null
        list().firstOrNull {
            it.relativePath == relative ||
                runCatching { File(paths.projects, it.relativePath).canonicalPath }
                    .getOrDefault("") == canonical.path
        }?.let { existing ->
            // 预登记时目录还空（模板=空目录）：克隆完成后再登记时补上真实模板
            val detected = detectTemplate(canonical)
            if (existing.template == ProjectTemplate.EMPTY && detected != ProjectTemplate.EMPTY) {
                val upgraded = existing.copy(template = detected)
                persist(list().map { if (it.relativePath == existing.relativePath) upgraded else it })
                return upgraded
            }
            return existing
        }
        val meta = ProjectMeta(
            name = name.trim().ifBlank { canonical.name },
            relativePath = relative,
            template = detectTemplate(canonical),
            createdAt = System.currentTimeMillis(),
            lastOpenedAt = System.currentTimeMillis(),
        )
        persist(list() + meta)
        return meta
    }

    /** 克隆 / 导入目录的模板推断（供项目卡片与构建环境动态需求使用）。 */
    private fun detectTemplate(dir: File): ProjectTemplate {
        val gradle = listOf("settings.gradle.kts", "settings.gradle", "build.gradle.kts", "build.gradle")
            .any { File(dir, it).exists() }
        if (gradle) return ProjectTemplate.ANDROID_APP

        val pubspec = File(dir, "pubspec.yaml")
        if (pubspec.exists()) {
            val text = runCatching { pubspec.readText(Charsets.UTF_8) }.getOrDefault("")
            if (text.contains("flutter:")) return ProjectTemplate.FLUTTER
        }

        val packageJson = File(dir, "package.json")
        if (packageJson.exists()) {
            val text = runCatching { packageJson.readText(Charsets.UTF_8) }.getOrDefault("")
            return if (text.contains("\"vite\"")) ProjectTemplate.VUE_VITE else ProjectTemplate.NODE_APP
        }

        if (File(dir, "index.html").exists()) return ProjectTemplate.STATIC_HTML
        return ProjectTemplate.EMPTY
    }

    /** 重命名项目（目录 + 索引同步改），返回改后的元信息；失败返回 null。 */
    suspend fun rename(relativePath: String, newName: String): ProjectMeta? {
        val metas = list()
        val target = metas.firstOrNull { it.relativePath == relativePath } ?: return null
        val newRelative = sanitize(newName)
        if (newRelative.isBlank() || metas.any { it.relativePath == newRelative }) return null
        val from = File(paths.projects, relativePath)
        val to = File(paths.projects, newRelative)
        if (!from.renameTo(to)) return null
        val updated = target.copy(name = newName.trim(), relativePath = newRelative)
        persist(metas.map { if (it.relativePath == relativePath) updated else it })
        return updated
    }

    suspend fun delete(relativePath: String): Boolean {
        val metas = list()
        File(paths.projects, relativePath).deleteRecursively()
        persist(metas.filterNot { it.relativePath == relativePath })
        return true
    }

    /** 顶层目录（编辑器树根）。 */
    fun projectDir(relativePath: String): File = File(paths.projects, relativePath)

    // ---------------- 脚手架 ----------------

    /**
     * 生成项目骨架文件，返回写入成功的文件数。
     * 新建项目对话框（选择「安卓应用」模板）走这里。
     */
    fun scaffold(root: File, template: ProjectTemplate): Int {
        root.mkdirs()
        var written = 0
        fun put(relative: String, content: String) {
            val file = File(root, relative)
            file.parentFile?.mkdirs()
            if (file.exists()) return
            if (file.writeText2(content)) written++
        }

        put(".gitignore", DEFAULT_GITIGNORE + gitignoreExtraOf(template))
        put("README.md", readmeOf(root, template))

        when (template) {
            ProjectTemplate.EMPTY -> Unit

            ProjectTemplate.ANDROID_APP -> {
                val slug = root.name.lowercase().replace(Regex("[^a-z0-9]"), "").ifBlank { "app" }
                val pkg = "com.example.$slug"
                val pkgPath = pkg.replace('.', '/')
                put("settings.gradle.kts", SETTINGS_GRADLE)
                put("build.gradle.kts", ROOT_BUILD_GRADLE)
                put("gradle.properties", GRADLE_PROPERTIES)
                put("gradle/wrapper/gradle-wrapper.properties", WRAPPER_PROPERTIES)
                put("gradlew", GRADLEW_SH)
                put("app/build.gradle.kts", APP_BUILD_GRADLE.replace("%PACKAGE%", pkg))
                put("app/proguard-rules.pro", "-keep class * { public *; }\n")
                put("app/src/main/AndroidManifest.xml", APP_MANIFEST.replace("%PACKAGE%", pkg))
                put(
                    "app/src/main/java/$pkgPath/MainActivity.kt",
                    APP_MAIN_ACTIVITY.replace("%PACKAGE%", pkg),
                )
                put(
                    "app/src/main/res/values/strings.xml",
                    "<resources>\n    <string name=\"app_name\">${root.name}</string>\n</resources>\n",
                )
                put(
                    "app/src/main/res/values/themes.xml",
                    """
                    <resources>
                        <style name="Theme.App" parent="android:Theme.Material.Light.NoActionBar" />
                    </resources>
                    """.trimIndent() + "\n",
                )
            }

            ProjectTemplate.KOTLIN_CLI -> {
                put("settings.gradle.kts", "rootProject.name = \"${root.name}\"\n")
                put("build.gradle.kts", KOTLIN_CLI_BUILD_GRADLE)
                put("gradle.properties", GRADLE_PROPERTIES)
                put(
                    "src/main/kotlin/Main.kt",
                    """
                    fun main() {
                        println("Hello, ${root.name}!")
                    }
                    """.trimIndent() + "\n",
                )
            }

            ProjectTemplate.VUE_VITE -> {
                val title = titleOf(root)
                put("package.json", VUE_PACKAGE_JSON.replace("%SLUG%", slugOf(root)))
                put("vite.config.js", VITE_CONFIG_JS)
                put("index.html", VUE_INDEX_HTML.replace("%NAME%", title))
                put("src/main.js", VUE_MAIN_JS)
                put("src/App.vue", VUE_APP_VUE.replace("%NAME%", title))
                put("src/style.css", VUE_STYLE_CSS)
            }

            ProjectTemplate.STATIC_HTML -> {
                val title = titleOf(root)
                put("index.html", HTML_INDEX.replace("%NAME%", title))
                put("css/style.css", HTML_STYLE)
                put("js/main.js", HTML_SCRIPT)
            }

            ProjectTemplate.NODE_APP -> {
                put("package.json", NODE_PACKAGE_JSON.replace("%SLUG%", slugOf(root)))
                put("index.js", NODE_INDEX_JS.replace("%NAME%", titleOf(root)))
            }

            ProjectTemplate.FLUTTER -> {
                val title = titleOf(root)
                put("pubspec.yaml", FLUTTER_PUBSPEC.replace("%SLUG%", snakeOf(root)))
                put("lib/main.dart", FLUTTER_MAIN_DART.replace("%NAME%", title))
                put("analysis_options.yaml", FLUTTER_ANALYSIS)
            }
        }
        return written
    }

    /** 模板专属的 .gitignore 追加行（默认忽略 Gradle 产物）。 */
    private fun gitignoreExtraOf(template: ProjectTemplate): String = when (template) {
        ProjectTemplate.VUE_VITE,
        ProjectTemplate.STATIC_HTML,
        ProjectTemplate.NODE_APP,
        -> "node_modules/\ndist/\n.vite/\n.env.local\n"

        ProjectTemplate.FLUTTER ->
            ".dart_tool/\n.pub-cache/\n.pub/\n.flutter-plugins\n.flutter-plugins-dependencies\n"

        else -> ""
    }

    /** 每个模板的 README（含运行说明）。 */
    private fun readmeOf(root: File, template: ProjectTemplate): String {
        val header = "# ${root.name}\n\n由 MobileCoder 移动码匠创建。\n"
        return header + when (template) {
            ProjectTemplate.VUE_VITE -> """
                |## 运行
                |
                |```bash
                |npm install     # 首次安装依赖（需先安装 Node.js）
                |npm run dev     # 启动开发服务器（默认 http://localhost:5173）
                |npm run build   # 产物输出到 dist/
                |```
                |
            """.trimMargin()

            ProjectTemplate.NODE_APP -> """
                |## 运行
                |
                |```bash
                |npm start       # 等价于 node index.js（默认端口 3000）
                |```
                |
            """.trimMargin()

            ProjectTemplate.STATIC_HTML -> """
                |## 运行
                |
                |直接用浏览器打开 `index.html`；或启动静态服务器：
                |
                |```bash
                |npx serve .     # 需先安装 Node.js
                |```
                |
            """.trimMargin()

            ProjectTemplate.FLUTTER -> """
                |## 运行
                |
                |```bash
                |flutter create .   # 首次生成 android/ 等平台目录
                |flutter run        # 编译并运行
                |```
                |
            """.trimMargin()

            else -> ""
        }
    }

    /** 包名用的小写 slug。 */
    private fun slugOf(root: File): String =
        root.name.lowercase().replace(Regex("[^a-z0-9]+"), "").ifBlank { "app" }

    /** Dart 包名用的 snake_case。 */
    private fun snakeOf(root: File): String =
        root.name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').ifBlank { "my_app" }

    /** 嵌进源码字符串的展示名（剔除会破坏字面量的字符）。 */
    private fun titleOf(root: File): String =
        root.name.replace(Regex("[\"'$<>]"), " ").trim().ifBlank { "未命名项目" }

    // ---------------- 内部 ----------------

    private fun sanitize(name: String): String =
        name.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_").replace(Regex("\\s+"), "-").take(64)

    private suspend fun persist(metas: List<ProjectMeta>) {
        val array = JSONArray()
        metas.forEach { meta ->
            array.put(
                JSONObject()
                    .put("name", meta.name)
                    .put("relativePath", meta.relativePath)
                    .put("template", meta.template.name)
                    .put("createdAt", meta.createdAt)
                    .put("lastOpenedAt", meta.lastOpenedAt),
            )
        }
        dataStore.edit { it[KEY_PROJECTS] = array.toString() }
    }

    private fun parse(raw: String): List<ProjectMeta> {
        val array = JSONArray(raw)
        return buildList {
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                add(
                    ProjectMeta(
                        name = obj.getString("name"),
                        relativePath = obj.getString("relativePath"),
                        template = ProjectTemplate.fromName(obj.optString("template")),
                        createdAt = obj.optLong("createdAt"),
                        lastOpenedAt = obj.optLong("lastOpenedAt"),
                    ),
                )
            }
        }
    }

    private fun File.writeText2(content: String): Boolean = runCatching {
        writeText(content, Charsets.UTF_8)
        true
    }.getOrDefault(false)

    companion object {
        private val KEY_PROJECTS = stringPreferencesKey("projects_index")

        private val DEFAULT_GITIGNORE = """
            *.iml
            .gradle/
            .idea/
            build/
            captures/
            .externalNativeBuild
            .cxx
            local.properties
            *.apk
            *.aab
        """.trimIndent() + "\n"

        private val SETTINGS_GRADLE = """
            pluginManagement {
                repositories {
                    google()
                    mavenCentral()
                    gradlePluginPortal()
                }
            }
            dependencyResolutionManagement {
                repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
                repositories {
                    google()
                    mavenCentral()
                }
            }
            rootProject.name = "app"
            include(":app")
        """.trimIndent() + "\n"

        private val ROOT_BUILD_GRADLE = """
            plugins {
                id("com.android.application") version "8.7.3" apply false
                id("org.jetbrains.kotlin.android") version "2.0.21" apply false
            }
        """.trimIndent() + "\n"

        private val GRADLE_PROPERTIES = """
            org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
            android.useAndroidX=true
            kotlin.code.style=official
        """.trimIndent() + "\n"

        private val WRAPPER_PROPERTIES = """
            distributionBase=GRADLE_USER_HOME
            distributionPath=wrapper/dists
            distributionUrl=https\://services.gradle.org/distributions/gradle-8.9-bin.zip
            networkTimeout=10000
            zipStoreBase=GRADLE_USER_HOME
            zipStorePath=wrapper/dists
        """.trimIndent() + "\n"

        private val GRADLEW_SH = run {
            val d = '$'
            """
            #!/system/bin/sh
            # 由 MobileCoder 生成的 Gradle 入口：
            # 1) 优先使用「设置 → 构建环境」导入的 Gradle 发行版（MOBILECODER_GRADLE_HOME）
            # 2) 其次尝试标准 wrapper（gradle/wrapper/gradle-wrapper.jar 存在时）
            DIR=${d}(dirname "${d}0")
            if [ -n "${d}MOBILECODER_GRADLE_HOME" ] && [ -x "${d}MOBILECODER_GRADLE_HOME/bin/gradle" ]; then
                exec "${d}MOBILECODER_GRADLE_HOME/bin/gradle" "${d}@"
            fi
            if [ -f "${d}DIR/gradle/wrapper/gradle-wrapper.jar" ]; then
                if [ -z "${d}JAVA_HOME" ]; then
                    echo "gradlew: 缺少 JAVA_HOME，请先导入 JDK" >&2
                    exit 1
                fi
                exec "${d}JAVA_HOME/bin/java" -classpath "${d}DIR/gradle/wrapper/gradle-wrapper.jar" \
                    org.gradle.wrapper.GradleWrapperMain "${d}@"
            fi
            echo "gradlew: 未找到 Gradle 发行版，请在 MobileCoder「设置 → 构建环境」中导入" >&2
            exit 1
            """.trimIndent() + "\n"
        }

        private val APP_BUILD_GRADLE = """
            plugins {
                id("com.android.application")
                id("org.jetbrains.kotlin.android")
            }

            android {
                namespace = "%PACKAGE%"
                compileSdk = 35

                defaultConfig {
                    applicationId = "%PACKAGE%"
                    minSdk = 29
                    targetSdk = 35
                    versionCode = 1
                    versionName = "1.0"
                }

                buildTypes {
                    release {
                        isMinifyEnabled = false
                    }
                }

                compileOptions {
                    sourceCompatibility = JavaVersion.VERSION_17
                    targetCompatibility = JavaVersion.VERSION_17
                }
                kotlinOptions {
                    jvmTarget = "17"
                }
            }
        """.trimIndent() + "\n"

        private val APP_MANIFEST = """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                <application
                    android:label="@string/app_name"
                    android:theme="@style/Theme.App"
                    android:allowBackup="false">
                    <activity
                        android:name=".MainActivity"
                        android:exported="true">
                        <intent-filter>
                            <action android:name="android.intent.action.MAIN" />
                            <category android:name="android.intent.category.LAUNCHER" />
                        </intent-filter>
                    </activity>
                </application>
            </manifest>
        """.trimIndent() + "\n"

        private val APP_MAIN_ACTIVITY = """
            package %PACKAGE%

            import android.app.Activity
            import android.os.Bundle

            class MainActivity : Activity() {
                override fun onCreate(savedInstanceState: Bundle?) {
                    super.onCreate(savedInstanceState)
                }
            }
        """.trimIndent() + "\n"

        private val KOTLIN_CLI_BUILD_GRADLE = """
            plugins {
                kotlin("jvm") version "2.0.21"
            }

            repositories {
                mavenCentral()
            }
        """.trimIndent() + "\n"

        // ---------------- Vue 3 + Vite ----------------

        private val NODE_PACKAGE_JSON = """
            {
              "name": "%SLUG%",
              "private": true,
              "version": "0.1.0",
              "type": "module",
              "main": "index.js",
              "scripts": {
                "start": "node index.js"
              }
            }
        """.trimIndent() + "\n"

        private val VUE_PACKAGE_JSON = """
            {
              "name": "%SLUG%",
              "private": true,
              "version": "0.1.0",
              "type": "module",
              "scripts": {
                "dev": "vite",
                "build": "vite build",
                "preview": "vite preview"
              },
              "dependencies": {
                "vue": "^3.4.21"
              },
              "devDependencies": {
                "@vitejs/plugin-vue": "^5.0.4",
                "vite": "^5.2.8"
              }
            }
        """.trimIndent() + "\n"

        private val VITE_CONFIG_JS = """
            import { defineConfig } from 'vite'
            import vue from '@vitejs/plugin-vue'

            export default defineConfig({
              plugins: [vue()],
              server: {
                host: true,
                port: 5173,
              },
              build: {
                outDir: 'dist',
              },
            })
        """.trimIndent() + "\n"

        private val VUE_INDEX_HTML = """
            <!doctype html>
            <html lang="zh-CN">
              <head>
                <meta charset="UTF-8" />
                <meta name="viewport" content="width=device-width, initial-scale=1.0" />
                <title>%NAME%</title>
              </head>
              <body>
                <div id="app"></div>
                <script type="module" src="/src/main.js"></script>
              </body>
            </html>
        """.trimIndent() + "\n"

        private val VUE_MAIN_JS = """
            import { createApp } from 'vue'
            import App from './App.vue'
            import './style.css'

            createApp(App).mount('#app')
        """.trimIndent() + "\n"

        private val VUE_APP_VUE = """
            <script setup>
            import { ref } from 'vue'

            const title = '%NAME%'
            const count = ref(0)
            </script>

            <template>
              <main class="page">
                <h1>{{ title }}</h1>
                <button type="button" @click="count++">点击次数：{{ count }}</button>
                <p>修改 src/App.vue 并保存，开发服务器会即时热更新。</p>
              </main>
            </template>

            <style scoped>
            .page {
              max-width: 42rem;
              margin: 0 auto;
              padding: 2rem 1rem;
              font-family: system-ui, -apple-system, sans-serif;
            }

            button {
              padding: 0.6rem 1.2rem;
              font-size: 1rem;
              border: 0;
              border-radius: 8px;
              background: #42b883;
              color: #fff;
              cursor: pointer;
            }
            </style>
        """.trimIndent() + "\n"

        private val VUE_STYLE_CSS = """
            :root {
              color-scheme: light dark;
              font-family: system-ui, -apple-system, "Segoe UI", Roboto, sans-serif;
            }

            * {
              box-sizing: border-box;
            }

            body {
              margin: 0;
              min-height: 100vh;
              background: #f7fafc;
              color: #1f2d3d;
            }

            @media (prefers-color-scheme: dark) {
              body {
                background: #101418;
                color: #e6edf3;
              }
            }
        """.trimIndent() + "\n"

        // ---------------- 静态 HTML ----------------

        private val HTML_INDEX = """
            <!doctype html>
            <html lang="zh-CN">
              <head>
                <meta charset="utf-8" />
                <meta name="viewport" content="width=device-width, initial-scale=1" />
                <title>%NAME%</title>
                <link rel="stylesheet" href="css/style.css" />
              </head>
              <body>
                <header class="site-header">
                  <h1>%NAME%</h1>
                  <nav><a href="#about">关于</a></nav>
                </header>
                <main>
                  <section id="about">
                    <p>这是一个静态 HTML 项目，由 MobileCoder 创建。</p>
                    <button id="action" type="button">点我</button>
                    <p id="output"></p>
                  </section>
                </main>
                <script src="js/main.js"></script>
              </body>
            </html>
        """.trimIndent() + "\n"

        private val HTML_STYLE = """
            * {
              box-sizing: border-box;
            }

            body {
              margin: 0;
              font-family: system-ui, -apple-system, sans-serif;
              background: #fdfdfd;
              color: #222;
            }

            .site-header {
              display: flex;
              align-items: center;
              justify-content: space-between;
              padding: 1rem 1.5rem;
              background: #2563eb;
              color: #fff;
            }

            .site-header h1 {
              margin: 0;
              font-size: 1.25rem;
            }

            .site-header a {
              color: #fff;
              text-decoration: none;
            }

            main {
              max-width: 44rem;
              margin: 0 auto;
              padding: 2rem 1.5rem;
            }

            button {
              padding: 0.6rem 1.2rem;
              font-size: 1rem;
              border: 0;
              border-radius: 8px;
              background: #2563eb;
              color: #fff;
              cursor: pointer;
            }
        """.trimIndent() + "\n"

        private val HTML_SCRIPT = """
            document.addEventListener('DOMContentLoaded', function () {
              var button = document.getElementById('action')
              var output = document.getElementById('output')
              var clicks = 0
              if (button && output) {
                button.addEventListener('click', function () {
                  clicks += 1
                  output.textContent = '已点击 ' + clicks + ' 次'
                })
              }
            })
        """.trimIndent() + "\n"

        // ---------------- Node.js ----------------

        private val NODE_INDEX_JS = """
            import http from 'node:http'

            const port = process.env.PORT || 3000

            const server = http.createServer((req, res) => {
              res.writeHead(200, { 'Content-Type': 'text/plain; charset=utf-8' })
              res.end('Hello from %NAME%!\nrequest: ' + req.url + '\n')
            })

            server.listen(port, '0.0.0.0', () => {
              console.log('listening on http://localhost:' + port)
            })
        """.trimIndent() + "\n"

        // ---------------- Flutter ----------------

        private val FLUTTER_PUBSPEC = """
            name: %SLUG%
            description: A Flutter project created by MobileCoder.
            publish_to: 'none'
            version: 0.1.0

            environment:
              sdk: '>=3.0.0 <4.0.0'

            dependencies:
              flutter:
                sdk: flutter

            dev_dependencies:
              flutter_test:
                sdk: flutter
              flutter_lints: ^4.0.0

            flutter:
              uses-material-design: true
        """.trimIndent() + "\n"

        private val FLUTTER_ANALYSIS = """
            include: package:flutter_lints/flutter.yaml
        """.trimIndent() + "\n"

        private val FLUTTER_MAIN_DART = """
            import 'package:flutter/material.dart';

            void main() {
              runApp(const MyApp());
            }

            class MyApp extends StatelessWidget {
              const MyApp({super.key});

              @override
              Widget build(BuildContext context) {
                return MaterialApp(
                  title: '%NAME%',
                  debugShowCheckedModeBanner: false,
                  theme: ThemeData(
                    colorSchemeSeed: Colors.indigo,
                    useMaterial3: true,
                  ),
                  home: const HomePage(),
                );
              }
            }

            class HomePage extends StatefulWidget {
              const HomePage({super.key});

              @override
              State<HomePage> createState() => _HomePageState();
            }

            class _HomePageState extends State<HomePage> {
              int count = 0;

              @override
              Widget build(BuildContext context) {
                return Scaffold(
                  appBar: AppBar(title: const Text('%NAME%')),
                  body: Center(
                    child: Column(
                      mainAxisAlignment: MainAxisAlignment.center,
                      children: <Widget>[
                        const Text('Flutter 项目已就绪'),
                        const SizedBox(height: 12),
                        FilledButton(
                          onPressed: () {
                            setState(() {
                              count += 1;
                            });
                          },
                          child: Text('点击次数：' + count.toString()),
                        ),
                      ],
                    ),
                  ),
                );
              }
            }
        """.trimIndent() + "\n"
    }
}
