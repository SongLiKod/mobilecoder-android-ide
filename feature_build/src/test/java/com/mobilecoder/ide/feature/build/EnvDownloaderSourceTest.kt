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

    private fun assertTrue(condition: Boolean) {
        org.junit.Assert.assertTrue(condition)
    }
}
