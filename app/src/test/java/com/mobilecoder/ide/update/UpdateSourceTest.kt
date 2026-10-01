package com.mobilecoder.ide.update

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UpdateSource 单测：releases/latest 解析、APK 资产选择、版本比较、SHA-256。
 * fixture 取自仓库真实响应的结构（tag v2.3.0 + 双 APK 资产 + digest）。
 */
class UpdateSourceTest {

    /** 真实响应结构的精简样本（含 assets_url 同前缀键、release 级 name、嵌套 uploader）。 */
    private val fixture = """
        {
          "url": "https://api.github.com/repos/SongLiKod/mobilecoder-android-ide/releases/401161606",
          "assets_url": "https://api.github.com/repos/SongLiKod/mobilecoder-android-ide/releases/401161606/assets",
          "tag_name": "v2.3.0",
          "name": "v2.3.0",
          "draft": false,
          "assets": [
            {
              "url": "https://api.github.com/repos/SongLiKod/mobilecoder-android-ide/releases/assets/603682807",
              "id": 603682807,
              "name": "MobileCoder-2.3.0-debug.apk",
              "label": "",
              "content_type": "application/vnd.android.package-archive",
              "size": 82633502,
              "digest": "sha256:39cbd28abae5cc97127b07a04cf223c992d9863505ebae2a948ddbd921fd7b4b",
              "browser_download_url": "https://github.com/SongLiKod/mobilecoder-android-ide/releases/download/v2.3.0/MobileCoder-2.3.0-debug.apk",
              "uploader": {"login": "github-actions[bot]", "type": "Bot"}
            },
            {
              "url": "https://api.github.com/repos/SongLiKod/mobilecoder-android-ide/releases/assets/603682803",
              "id": 603682803,
              "name": "MobileCoder-2.3.0.apk",
              "label": "",
              "content_type": "application/vnd.android.package-archive",
              "size": 66306360,
              "digest": "sha256:5115be30b0c8adb08a8c54a06863cf8d94a11b1f06ab20ae3f4ae4f1cea93015",
              "browser_download_url": "https://github.com/SongLiKod/mobilecoder-android-ide/releases/download/v2.3.0/MobileCoder-2.3.0.apk"
            }
          ],
          "body": "release notes"
        }
    """.trimIndent()

    // ---- parseLatest ----

    @Test
    fun parseLatest_extractsTagAndBothApkAssets() {
        val release = UpdateSource.parseLatest(fixture)
        assertNotNull("应能解析出最新发布", release)
        assertEquals("v2.3.0", release!!.tag)
        assertEquals(2, release.apks.size)
        assertEquals("MobileCoder-2.3.0-debug.apk", release.apks[0].name)
        assertEquals(
            "https://github.com/SongLiKod/mobilecoder-android-ide/releases/download/v2.3.0/MobileCoder-2.3.0-debug.apk",
            release.apks[0].url,
        )
        assertEquals(
            "sha256:39cbd28abae5cc97127b07a04cf223c992d9863505ebae2a948ddbd921fd7b4b",
            release.apks[0].digest,
        )
        assertEquals("MobileCoder-2.3.0.apk", release.apks[1].name)
    }

    @Test
    fun parseLatest_assetsUrlPrefixKeyIsNotMisreadAsAssets() {
        // 只有 assets_url、没有 assets 数组 → 解析失败返回 null（不能误把 url 当数组）
        val json = """{"tag_name": "v1.0.0", "assets_url": "https://api.github.com/x/assets"}"""
        assertNull(UpdateSource.parseLatest(json))
    }

    @Test
    fun parseLatest_noApkAssetReturnsNull() {
        val json = """
            {
              "tag_name": "v9.9.9",
              "assets": [
                {"name": "sources.zip", "browser_download_url": "https://x/sources.zip"}
              ]
            }
        """.trimIndent()
        assertNull(UpdateSource.parseLatest(json))
    }

    @Test
    fun parseLatest_missingTagReturnsNull() {
        val json = """{"name": "v1", "assets": [{"name": "a.apk", "browser_download_url": "https://x/a.apk"}]}"""
        assertNull(UpdateSource.parseLatest(json))
    }

    // ---- selectApk ----

    @Test
    fun selectApk_debugBuildPrefersDebugAsset() {
        val release = UpdateSource.parseLatest(fixture)!!
        val apk = UpdateSource.selectApk(release, debugBuild = true)
        assertNotNull(apk)
        assertTrue("debug 包应选中带 debug 的资产", apk!!.name.contains("debug"))
    }

    @Test
    fun selectApk_releaseBuildPrefersNonDebugAsset() {
        val release = UpdateSource.parseLatest(fixture)!!
        val apk = UpdateSource.selectApk(release, debugBuild = false)
        assertNotNull(apk)
        assertFalse("release 包不能选中 debug 资产", apk!!.name.contains("debug"))
        assertEquals("MobileCoder-2.3.0.apk", apk.name)
    }

    @Test
    fun selectApk_fallsBackToFirstAssetWhenVariantMissing() {
        // 只有 release 资产、但已装的是 debug 包 → 退化为第一个（后续由系统判定签名）
        val json = """
            {"tag_name": "v1.2.0", "assets": [
              {"name": "App-1.2.0.apk", "browser_download_url": "https://x/App-1.2.0.apk"}
            ]}
        """.trimIndent()
        val release = UpdateSource.parseLatest(json)!!
        val apk = UpdateSource.selectApk(release, debugBuild = true)
        assertEquals("App-1.2.0.apk", apk!!.name)
    }

    // ---- isNewer / normalizeVersion ----

    @Test
    fun isNewer_matrix() {
        assertTrue("2.4.0 应新于 2.3.0", UpdateSource.isNewer("v2.4.0", "2.3.0"))
        assertTrue("3.10.0 应新于 3.9.0（逐段数值比较，非字典序）", UpdateSource.isNewer("3.10.0", "3.9.0"))
        assertFalse("同版本不算新", UpdateSource.isNewer("2.3.0", "v2.3.0"))
        assertFalse("旧版本不算新", UpdateSource.isNewer("2.2.0", "2.3.0"))
        // 当前真实场景：远端最新 v2.3.0、本地 3.0.0 → 不提示更新
        assertFalse("远端 2.3.0 不新于本地 3.0.0", UpdateSource.isNewer("2.3.0", "3.0.0"))
        assertTrue("补丁号 3.0.1 应新于 3.0.0", UpdateSource.isNewer("3.0.1", "3.0.0"))
    }

    @Test
    fun normalizeVersion_stripsPrefixAndWhitespace() {
        assertEquals("2.3.0", UpdateSource.normalizeVersion("v2.3.0"))
        assertEquals("2.3.0", UpdateSource.normalizeVersion("V2.3.0"))
        assertEquals("2.3.0", UpdateSource.normalizeVersion("  2.3.0 "))
        assertEquals("2.3.0", UpdateSource.normalizeVersion("2.3.0"))
    }

    // ---- strField 转义 ----

    @Test
    fun strField_handlesEscapes() {
        val json = """{"name": "a\"b.apk", "path": "C:\\tmp\\x"}"""
        assertEquals("a\"b.apk", UpdateSource.strField(json, "name"))
        assertEquals("C:\\tmp\\x", UpdateSource.strField(json, "path"))
        assertEquals(
            "v1\n2",
            UpdateSource.strField("""{"tag_name": "v1\n2"}""", "tag_name"),
        )
        assertNull("不存在的键返回 null", UpdateSource.strField(json, "missing"))
    }

    // ---- sha256Hex ----

    @Test
    fun sha256Hex_matchesKnownVector() {
        val hex = ByteArrayInputStream("abc".toByteArray()).use { UpdateSource.sha256Hex(it) }
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            hex,
        )
    }

    @Test
    fun apiUrl_pointsAtGithubReleasesLatest() {
        assertEquals(
            "https://api.github.com/repos/SongLiKod/mobilecoder-android-ide/releases/latest",
            UpdateSource.apiUrl(),
        )
    }
}
