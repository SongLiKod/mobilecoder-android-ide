package com.mobilecoder.ide.feature.build

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** [EnvDownloader.candidateSources]：官方源 / 国内镜像的排序与回退候选。 */
class EnvDownloaderSourceTest {

    @Test
    fun officialSource_putsOfficialFirst() {
        val sources = EnvDownloader.candidateSources(EnvKind.JDK, EnvSource.OFFICIAL, "aarch64")
        assertEquals(2, sources.size)
        assertEquals("Adoptium 官方", sources[0].label)
        assertNotEquals(sources[0].url, sources[1].url)
    }

    @Test
    fun mirrorSource_putsMirrorFirst() {
        val sources = EnvDownloader.candidateSources(EnvKind.JDK, EnvSource.MIRROR, "aarch64")
        assertEquals(2, sources.size)
        assertEquals("清华 TUNA", sources[0].label)
    }

    @Test
    fun sdk_hasSingleOfficialCandidate() {
        // SDK 仅 dl.google.com 一个来源 → 去重后只剩 1 个候选
        val sources = EnvDownloader.candidateSources(EnvKind.SDK, EnvSource.MIRROR, "x64")
        assertEquals(1, sources.size)
        assertEquals("dl.google.com", sources[0].label)
    }

    @Test
    fun gradle_andNode_carryArchSpecificUrls() {
        val gradle = EnvDownloader.candidateSources(EnvKind.GRADLE, EnvSource.OFFICIAL, "aarch64")
        assertTrue(gradle.all { it.url.endsWith("gradle-8.9-bin.zip") })

        val nodeArm = EnvDownloader.candidateSources(EnvKind.NODE, EnvSource.OFFICIAL, "aarch64")
        val nodeX64 = EnvDownloader.candidateSources(EnvKind.NODE, EnvSource.OFFICIAL, "x64")
        assertTrue(nodeArm.first().url.contains("arm64"))
        assertTrue(nodeX64.first().url.contains("x64"))
    }

    @Test
    fun archToken_abiToDownloadArch() {
        // 通过候选 URL 反映 ABI 映射（aarch64 的 node 包为 arm64）
        val jdkAarch64 = EnvDownloader.candidateSources(EnvKind.JDK, EnvSource.OFFICIAL, "aarch64")
        assertTrue(jdkAarch64.first().url.contains("/aarch64/"))
        val jdkX64 = EnvDownloader.candidateSources(EnvKind.JDK, EnvSource.OFFICIAL, "x64")
        assertTrue(jdkX64.first().url.contains("/x64/"))
    }

    // ---- glibc：多内置镜像 + 首选源 + 手动输入自定义源 ----

    @Test
    fun glibc_hasMultipleDistinctDefaultMirrors() {
        // 内置 ≥4 个源、URL 互不相同 → 下载失败时逐个自动回退
        val sources = EnvDownloader.candidateSources(EnvKind.GLIBC, EnvSource.OFFICIAL, "aarch64")
        assertTrue(sources.size >= 4)
        assertEquals(sources.size, sources.map { it.url }.distinct().size)
        assertTrue(sources.all { it.url.endsWith("glibc-2.39-aarch64.tar.gz") })
    }

    @Test
    fun glibc_mirrorSource_putsDomesticMirrorsFirst() {
        val sources = EnvDownloader.candidateSources(EnvKind.GLIBC, EnvSource.MIRROR, "aarch64")
        val domestic = EnvDownloader.GLIBC_MIRRORS.filter { it.domestic }.map { it.label }
        assertEquals(domestic, sources.take(domestic.size).map { it.label })
    }

    @Test
    fun glibc_preferredSource_isTriedFirst_withoutDuplication() {
        val sources = EnvDownloader.candidateSources(
            kind = EnvKind.GLIBC,
            source = EnvSource.OFFICIAL,
            arch = "aarch64",
            custom = null,
            preferredId = "tencent",
        )
        assertEquals("腾讯云镜像", sources[0].label)
        assertEquals(1, sources.count { it.label == "腾讯云镜像" })
    }

    @Test
    fun glibc_customSource_isFirst_baseUrlGetsFileAppended() {
        val sources = EnvDownloader.candidateSources(
            kind = EnvKind.GLIBC,
            source = EnvSource.OFFICIAL,
            arch = "aarch64",
            custom = "https://my.host/glibc/",
            preferredId = null,
        )
        assertEquals("自定义源", sources[0].label)
        assertEquals("https://my.host/glibc/glibc-2.39-aarch64.tar.gz", sources[0].url)
        // 自定义源在最前，内置源仍全部保留兜底
        assertTrue(sources.size >= 5)
    }

    @Test
    fun glibc_customSource_fullArchiveUrl_usedAsIs_thenPreferredFollows() {
        val sources = EnvDownloader.candidateSources(
            kind = EnvKind.GLIBC,
            source = EnvSource.MIRROR,
            arch = "x64",
            custom = "https://my.host/pkg/glibc-2.39-x64.tar.gz",
            preferredId = "official",
        )
        assertEquals("https://my.host/pkg/glibc-2.39-x64.tar.gz", sources[0].url)
        assertEquals("MobileCoder 官方", sources[1].label)
    }

    @Test
    fun glibc_customSourceDuplicateOfBuiltIn_deduplicated() {
        val sources = EnvDownloader.candidateSources(
            kind = EnvKind.GLIBC,
            source = EnvSource.OFFICIAL,
            arch = "aarch64",
            custom = EnvDownloader.GLIBC_MIRRORS.first().base,
            preferredId = null,
        )
        assertEquals(sources.size, sources.map { it.url }.distinct().size)
    }

    @Test
    fun resolveCustomUrl_baseVsFullArchive() {
        assertEquals(
            "https://x/g/glibc-2.39-aarch64.tar.gz",
            EnvDownloader.resolveCustomUrl("  https://x/g/  ", "glibc-2.39-aarch64"),
        )
        assertEquals(
            "https://x/f/glibc-2.39-x64.zip",
            EnvDownloader.resolveCustomUrl("https://x/f/glibc-2.39-x64.zip", "glibc-2.39-x64"),
        )
    }

    private fun assertTrue(condition: Boolean) {
        org.junit.Assert.assertTrue(condition)
    }
}
