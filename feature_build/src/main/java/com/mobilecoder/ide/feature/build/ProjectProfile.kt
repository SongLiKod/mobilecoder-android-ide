package com.mobilecoder.ide.feature.build

import java.io.File

/**
 * 构建页按**项目结构**动态识别的类型（语言 / 工具链匹配）。
 *
 * 每种类型对应一条构建路径，见 [BuildRunner]：
 *  - [ANDROID_APP]   Gradle `assembleDebug/Release` → APK；
 *  - [JVM_GRADLE]    Gradle `assemble` → JAR（无 Android 插件的纯 JVM/Kotlin 工程）；
 *  - [NODE]          `npm install + npm run build` → dist 打包为 zip；
 *  - [ZIP_PACKAGE]   无需（或暂不能）编译的工程 → 进程内打包源码 zip，
 *                    产物同样可 下载 / 分享（Flutter、Python、静态 HTML、未识别类型）。
 */
enum class ProjectKind(val label: String) {
    ANDROID_APP("安卓应用"),
    JVM_GRADLE("JVM / Kotlin"),
    NODE("Node.js"),
    ZIP_PACKAGE("源码打包"),
}

/**
 * 项目画像：类型 + 展示名 + 所需环境 + 产物描述 + 可选补充引导。
 *
 * @param requiredEnv 所需环境组件（**不含** LINUX；需要子进程的类型由
 *   [BuildEnvironment.withLinuxIfNeeded] 追加，纯打包类为空 = 无需任何工具链）
 * @param artifactLabel 构建产物描述（"APK" / "JAR" / "dist 压缩包" / "源码压缩包"）
 * @param hint 完整构建的补充引导（如 Flutter 需要 SDK），null = 无
 */
data class ProjectProfile(
    val kind: ProjectKind,
    val displayName: String,
    val requiredEnv: List<EnvKind> = emptyList(),
    val artifactLabel: String = "",
    val hint: String? = null,
) {
    /** 是否需要子进程（Gradle / npm）构建；false = 进程内打包。 */
    val usesShell: Boolean
        get() = kind == ProjectKind.ANDROID_APP ||
            kind == ProjectKind.JVM_GRADLE ||
            kind == ProjectKind.NODE

    /** 是否使用 Gradle（决定变体选择 / Clean / 附加任务等控件是否显示）。 */
    val usesGradle: Boolean
        get() = kind == ProjectKind.ANDROID_APP || kind == ProjectKind.JVM_GRADLE
}

/**
 * 按目录结构识别项目类型（构建页与构建环境需求共用的唯一入口）。
 *
 * 判定优先级：Gradle（安卓插件 → JVM）→ package.json（有 build 脚本 → Node，
 * 否则源码打包）→ Flutter pubspec → 静态 HTML → Python 标记 → 兜底源码打包。
 * **任何项目都能在此页产出可分享的产物**，识别不出的只影响是否能编译。
 */
fun detectProject(dir: File): ProjectProfile {
    val hasGradle = listOf(
        "settings.gradle",
        "settings.gradle.kts",
        "build.gradle",
        "build.gradle.kts",
        "gradlew",
    ).any { File(dir, it).exists() }
    if (hasGradle) {
        return if (BuildEnvironment.isAndroidProject(dir)) {
            ProjectProfile(
                kind = ProjectKind.ANDROID_APP,
                displayName = "安卓应用（Gradle）",
                requiredEnv = listOf(EnvKind.JDK, EnvKind.GRADLE, EnvKind.SDK),
                artifactLabel = "APK",
            )
        } else {
            ProjectProfile(
                kind = ProjectKind.JVM_GRADLE,
                displayName = "JVM / Kotlin（Gradle）",
                requiredEnv = listOf(EnvKind.JDK, EnvKind.GRADLE),
                artifactLabel = "JAR",
            )
        }
    }

    val packageJson = File(dir, "package.json")
    if (packageJson.exists()) {
        val text = runCatching { packageJson.readText(Charsets.UTF_8) }.getOrDefault("")
        val display = when {
            text.contains("\"vite\"") -> "Vue / Vite（Node.js）"
            text.contains("\"react\"") -> "React（Node.js）"
            else -> "Node.js"
        }
        return if (hasNpmBuildScript(text)) {
            ProjectProfile(
                kind = ProjectKind.NODE,
                displayName = display,
                requiredEnv = listOf(EnvKind.NODE),
                artifactLabel = "dist 压缩包",
            )
        } else {
            ProjectProfile(
                kind = ProjectKind.ZIP_PACKAGE,
                displayName = display,
                artifactLabel = "源码压缩包",
                hint = "package.json 未定义 build 脚本：已按源码打包分享。" +
                    "如需运行，可在「终端」执行 npm install 后 npm start",
            )
        }
    }

    val pubspec = File(dir, "pubspec.yaml")
    if (pubspec.exists()) {
        val text = runCatching { pubspec.readText(Charsets.UTF_8) }.getOrDefault("")
        if (text.contains("flutter:")) {
            return ProjectProfile(
                kind = ProjectKind.ZIP_PACKAGE,
                displayName = "Flutter 应用",
                artifactLabel = "源码压缩包",
                hint = "完整构建需要 Flutter SDK：可在「终端」安装 Flutter 后执行 flutter build apk。" +
                    "当前已按源码打包，可先下载 / 分享。",
            )
        }
    }

    if (File(dir, "index.html").exists()) {
        return ProjectProfile(
            kind = ProjectKind.ZIP_PACKAGE,
            displayName = "静态 HTML",
            artifactLabel = "源码压缩包",
        )
    }

    val isPython = listOf("requirements.txt", "pyproject.toml", "main.py", "manage.py", "setup.py")
        .any { File(dir, it).exists() }
    if (isPython) {
        return ProjectProfile(
            kind = ProjectKind.ZIP_PACKAGE,
            displayName = "Python",
            artifactLabel = "源码压缩包",
            hint = "Python 无需编译：已按源码打包。可在「软件市场」安装 Python 3 后于「终端」运行。",
        )
    }

    return ProjectProfile(
        kind = ProjectKind.ZIP_PACKAGE,
        displayName = "未识别类型",
        artifactLabel = "源码压缩包",
        hint = "未在项目根目录发现构建文件（settings.gradle / package.json 等），" +
            "已按源码打包分享；如需编译请确认构建文件位于项目根目录。",
    )
}

/**
 * package.json 的 `scripts` 里是否定义了 `build`（决定走 npm 构建还是源码打包）。
 *
 * 纯文本正则匹配 `scripts` 对象内的 `"build":`（scripts 不会嵌套花括号），
 * 不引入 JSON 解析器，保证 JVM 单测可直接跑。
 */
internal fun hasNpmBuildScript(packageJsonText: String): Boolean =
    RE_NPM_SCRIPTS.containsMatchIn(packageJsonText)

private val RE_NPM_SCRIPTS = Regex("""(?s)"scripts"\s*:\s*\{[^{}]*"build"\s*:""")
