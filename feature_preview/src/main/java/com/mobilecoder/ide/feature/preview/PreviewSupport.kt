package com.mobilecoder.ide.feature.preview

import java.io.File

/**
 * 静态 HTML / Web 项目**实时预览**（Issue 13）的纯逻辑部分。
 *
 * 这里刻意只依赖 `java.io.File` 与字符串处理，不碰 Android / WebView / 子进程，
 * 便于 JVM 单测直接覆盖（见 `PreviewSupportTest`）。状态与进程编排在
 * [PreviewController]，界面在 `PreviewPane.kt`。
 */

/** 预览形态：决定窗格里加载什么、以及保存文件后怎么刷新。 */
enum class PreviewKind(val label: String) {
    /** 项目里没有可预览的入口（既无 `index.html` 也无 `dev` 脚本）。 */
    NONE("不可预览"),

    /** 静态站点：直接用 WebView 加载项目根的 `index.html`（`file://` 协议）。 */
    STATIC_HTML("静态 HTML"),

    /** 有 `dev` 脚本的 Web 工程（Vue 3 + Vite / React …）：先起 dev server 再加载其地址。 */
    DEV_SERVER("开发服务器"),
}

/** 预览会读到的文件扩展名（小写、不带点）——保存这些文件才需要刷新窗格。 */
private val PREVIEW_EXTS = setOf(
    "html", "htm", "css", "scss", "sass", "less",
    "js", "mjs", "cjs", "jsx", "ts", "tsx", "vue", "json", "svg",
)

/**
 * 按项目根目录判定预览形态。
 *
 * 优先级与「建议方案」一致：**`package.json` 里有 `dev` 脚本 → 开发服务器**
 * （Vite 的 `index.html` 依赖 `/src/main.js` 之类的模块脚本，`file://` 打不开，
 * 必须走 dev server）；否则有 `index.html` → 静态 HTML；两者都没有 → 不可预览。
 *
 * 读盘失败一律按「不可预览」兜底，绝不抛异常。
 */
fun detectPreviewKind(projectDir: File): PreviewKind {
    if (!projectDir.isDirectory) return PreviewKind.NONE
    val packageJson = File(projectDir, "package.json")
    if (packageJson.isFile) {
        val text = runCatching { packageJson.readText(Charsets.UTF_8) }.getOrDefault("")
        if (hasNpmDevScript(text)) return PreviewKind.DEV_SERVER
    }
    return if (File(projectDir, "index.html").isFile) {
        PreviewKind.STATIC_HTML
    } else {
        PreviewKind.NONE
    }
}

/**
 * `package.json` 的 `scripts` 对象里是否定义了 `dev`（决定要不要起 dev server）。
 *
 * 与 feature_build 的 `hasNpmBuildScript` 同一套口径：纯正则匹配 `scripts` 对象内的
 * `"dev":`（scripts 不会嵌套花括号），不引入 JSON 解析器，保证 JVM 单测可直接跑。
 */
internal fun hasNpmDevScript(packageJsonText: String): Boolean =
    RE_NPM_DEV_SCRIPT.containsMatchIn(packageJsonText)

private val RE_NPM_DEV_SCRIPT = Regex("""(?s)"scripts"\s*:\s*\{[^{}]*"dev"\s*:""")

/**
 * `package.json` 是否为 Vite 工程：是则启动时给 `npm run dev` 追加
 * `--host 127.0.0.1`（Vite 默认只绑 `localhost`、可能落到 IPv6 字面量，
 * 钉死回环保证设备上的 WebView 必然可达）。
 */
internal fun usesVite(packageJsonText: String): Boolean = packageJsonText.contains("\"vite\"")

/**
 * 从 dev server 的累计输出里识别**本机访问地址**（Vite / webpack-dev-server /
 * react-scripts 打印的 `Local: http://localhost:5173/` 之类）。
 *
 * 规则：
 *  1. 只认回环地址（`localhost` / `127.0.0.1` / `0.0.0.0` / `::1`），
 *     这样 `npm install` 刷出的 `registry.npmjs.org`、`github.com` 之类外链不会被误认；
 *  2. 全部归一成 `127.0.0.1`（服务监听全网卡 / IPv6 时，WebView 走回环即可达，
 *     也避开 IPv6 字面量必须加方括号的坑）；
 *  3. 必须带端口，端口为 0 的占位地址直接丢弃。
 *
 * @return 归一化后的 `http://…` 地址；输出里还没有可用地址时返回 null
 */
fun findDevServerUrl(output: String): String? {
    val candidates = RE_LOCAL_URL.findAll(output)
        .map { it.value.trimEnd('.', ',', ';', ')', ']', '}', '"', '\'') }
        .mapNotNull { normalizeLoopbackUrl(it) }
        .toList()
    if (candidates.isEmpty()) return null
    // 0.0.0.0 是「监听全部网卡」的占位符，优先取明确的回环地址
    return candidates.firstOrNull { "0.0.0.0" !in it } ?: candidates.first()
}

/** 只放行回环 host 且**必须带端口**的 `http(s)` 地址；任意回环写法统一到 `127.0.0.1`。 */
private fun normalizeLoopbackUrl(raw: String): String? {
    val match = RE_LOOPBACK_URL.find(raw) ?: return null
    val scheme = match.groups["scheme"]?.value ?: return null
    val port = match.groups["port"]?.value ?: return null
    if (port == "0") return null
    return "$scheme://127.0.0.1:$port"
}

private val RE_LOCAL_URL = Regex("""https?://[^\s"'<>]+""")

private val RE_LOOPBACK_URL = Regex(
    """^(?<scheme>https?)://(?:localhost|127\.0\.0\.1|0\.0\.0\.0|\[::1\]|::1)(?::(?<port>\d+))?""",
)

/**
 * 保存这个文件后，预览窗格是否需要刷新。
 *
 * 只认 [PREVIEW_EXTS] 里的资源，且排除 `node_modules` / `dist` 这类依赖与构建产物目录
 * （否则一次 `npm install` 或打包会把预览刷屏）。
 */
fun affectsPreview(path: String): Boolean {
    val normalized = "/${path.replace('\\', '/')}"
    if ("/node_modules/" in normalized || "/dist/" in normalized) return false
    val ext = normalized.substringAfterLast('.', "").lowercase()
    return ext in PREVIEW_EXTS
}
