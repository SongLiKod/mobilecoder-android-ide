package com.mobilecoder.ide.update

import java.io.InputStream
import java.security.MessageDigest

/**
 * 更新源与版本比较（纯函数，JVM 单测覆盖）。
 *
 * 数据来自 GitHub Releases 的 `releases/latest` 接口（实测可达：
 * 最新 tag v2.3.0，双 APK 资产，各带 `digest` SHA-256）。手工解析 JSON，
 * 避免为一个接口引入 JSON 依赖。
 */
object UpdateSource {

    /** 发布仓库（检查更新与 APK 下载都来自它的 GitHub Releases）。 */
    const val REPO = "SongLiKod/mobilecoder-android-ide"

    /** releases/latest 接口地址。 */
    fun apiUrl(): String = "https://api.github.com/repos/$REPO/releases/latest"

    /** Releases 里的一个 APK 资产。 */
    data class ApkAsset(val name: String, val url: String, val digest: String?)

    /** 最新版本信息：tag + 全部 APK 资产。 */
    data class LatestRelease(val tag: String, val apks: List<ApkAsset>)

    // ------------------------------------------------------------------
    // 解析
    // ------------------------------------------------------------------

    /** 解析 releases/latest 响应；tag 或可用 APK 资产缺失时返回 null。 */
    fun parseLatest(json: String): LatestRelease? {
        val tag = strField(json, "tag_name")?.trim().orEmpty()
        if (tag.isEmpty()) return null
        val assets = extractArray(json, "assets") ?: return null
        val apks = splitObjects(assets).mapNotNull { obj ->
            val name = strField(obj, "name") ?: return@mapNotNull null
            val url = strField(obj, "browser_download_url") ?: return@mapNotNull null
            if (name.endsWith(".apk", ignoreCase = true)) {
                ApkAsset(name, url, strField(obj, "digest"))
            } else {
                null
            }
        }
        if (apks.isEmpty()) return null
        return LatestRelease(tag, apks)
    }

    /**
     * 按已安装变体挑资产：debug 应用优先带 debug 的 APK、release 应用优先不带的
     * （签名不同装不上，变体必须对齐）；无匹配时退化为第一个 APK。
     */
    fun selectApk(release: LatestRelease, debugBuild: Boolean): ApkAsset? =
        release.apks
            .firstOrNull { it.name.contains("debug", ignoreCase = true) == debugBuild }
            ?: release.apks.firstOrNull()

    // ------------------------------------------------------------------
    // 版本比较
    // ------------------------------------------------------------------

    /** tag → 版本号（去 v/V 前缀、去空白）。 */
    fun normalizeVersion(tag: String): String =
        tag.trim().removePrefix("v").removePrefix("V")

    /** 语义化逐段比较：[latest] 是否严格新于 [current]（3.10.0 > 3.9.0，非字典序）。 */
    fun isNewer(latest: String, current: String): Boolean {
        val a = normalizeVersion(latest).split('.').map { it.toIntOrNull() ?: 0 }
        val b = normalizeVersion(current).split('.').map { it.toIntOrNull() ?: 0 }
        val n = maxOf(a.size, b.size)
        for (i in 0 until n) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    // ------------------------------------------------------------------
    // 校验
    // ------------------------------------------------------------------

    /** 流式 SHA-256（小写十六进制），用于校验下载资产的 `digest` 字段。 */
    fun sha256Hex(input: InputStream): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    // ------------------------------------------------------------------
    // JSON 小工具（字符串感知：能处理 \" 转义与嵌套对象）
    // ------------------------------------------------------------------

    /** 取 `"key": "value"` 字符串字段（处理转义），不存在返回 null。 */
    internal fun strField(json: String, key: String): String? {
        val marker = "\"" + Regex.escape(key) + "\"\\s*:\\s*\""
        val m = Regex(marker).find(json) ?: return null
        val start = m.range.last + 1
        val sb = StringBuilder()
        var i = start
        while (i < json.length) {
            when (val c = json[i]) {
                '\\' -> {
                    val n = json.getOrNull(i + 1) ?: return null
                    when (n) {
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'b' -> sb.append('\b')
                        'u' -> {
                            if (i + 6 > json.length) return null
                            val hex = json.substring(i + 2, i + 6)
                            val code = hex.toIntOrNull(16) ?: return null
                            sb.append(code.toChar())
                            i += 6
                            continue
                        }
                        else -> sb.append(n)
                    }
                    i += 2
                }
                '"' -> return sb.toString()
                else -> {
                    sb.append(c)
                    i++
                }
            }
        }
        return null
    }

    /** 取 `"key": [ ... ]` 数组原文（`assets_url` 这类同前缀键不会误命中）。 */
    internal fun extractArray(json: String, key: String): String? {
        val marker = "\"" + Regex.escape(key) + "\"\\s*:"
        val m = Regex(marker).find(json) ?: return null
        var i = m.range.last + 1
        while (i < json.length && json[i].isWhitespace()) i++
        if (i >= json.length || json[i] != '[') return null
        var depth = 0
        var inString = false
        var escaped = false
        for (j in i until json.length) {
            val c = json[j]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '[' -> depth++
                ']' -> {
                    depth--
                    if (depth == 0) return json.substring(i + 1, j)
                }
            }
        }
        return null
    }

    /** 把数组内容切成顶层 `{...}` 对象（嵌套对象整体保留在所属对象内）。 */
    internal fun splitObjects(arrayBody: String): List<String> {
        val out = mutableListOf<String>()
        var depth = 0
        var start = -1
        var inString = false
        var escaped = false
        for (i in arrayBody.indices) {
            val c = arrayBody[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> {
                    if (depth == 0) start = i
                    depth++
                }
                '}' -> {
                    depth--
                    if (depth == 0 && start >= 0) {
                        out += arrayBody.substring(start, i + 1)
                        start = -1
                    }
                }
            }
        }
        return out
    }
}
