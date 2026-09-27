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
    KOTLIN_CLI("Kotlin 命令行");

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

    suspend fun rename(relativePath: String, newName: String): Boolean {
        val metas = list()
        val target = metas.firstOrNull { it.relativePath == relativePath } ?: return false
        val newRelative = sanitize(newName)
        if (newRelative.isBlank() || metas.any { it.relativePath == newRelative }) return false
        val from = File(paths.projects, relativePath)
        val to = File(paths.projects, newRelative)
        if (!from.renameTo(to)) return false
        persist(
            metas.map {
                if (it.relativePath == relativePath) {
                    it.copy(name = newName.trim(), relativePath = newRelative)
                } else it
            },
        )
        return true
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
     * CLI 的 `opencode init` 与新建项目对话框共用此方法。
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

        put(".gitignore", DEFAULT_GITIGNORE)
        put("README.md", "# ${root.name}\n\n由 MobileCoder 移动码匠创建。\n")

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
        }
        return written
    }

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
    }
}
