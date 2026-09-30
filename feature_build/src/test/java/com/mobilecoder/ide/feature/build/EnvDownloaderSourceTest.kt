package com.mobilecoder.ide.feature.build

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * [EnvDownloader.candidateSources]：官方源 / 国内镜像的排序与回退候选。
 *
 * Linux 环境（[EnvKind.LINUX]）走 5 参入口：自定义源 → 首选内置源 → 其余内置源，
 * 按 URL 去重；其余组件仍是「官方 + 国内镜像」两候选。
 */
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

    // ---- Linux rootfs：多内置镜像 + 首选源 + 手动输入自定义源 ----

    @Test
    fun linux_builtinMirrors_areUbuntuBaseReleaseUrls() {
        // 两个内置镜像包路径结构完全相同（仅域名不同），包名 = ubuntu-base-<版本>-base-<arch>.tar.gz；
        // 下列地址 2026-09 实测「两个架构的包」均 HTTP 200，绝非占位域名。
        val sources = EnvDownloader.candidateSources(EnvKind.LINUX, EnvSource.OFFICIAL, "aarch64")
        assertEquals(RootfsManager.ROOTFS_MIRRORS.size, sources.size)
        assertEquals(sources.size, sources.map { it.url }.distinct().size)
        assertEquals(
            RootfsManager.ROOTFS_MIRRORS.map { it.label },
            sources.map { it.label },
        )
        sources.forEach { source ->
            assertTrue(source.url.startsWith("https://"))
            assertTrue(
                source.url.endsWith("/releases/24.04/release/ubuntu-base-24.04.5-base-arm64.tar.gz"),
            )
        }
    }

    @Test
    fun linux_mirrorSource_putsDomesticFirst() {
        val mirror = EnvDownloader.candidateSources(EnvKind.LINUX, EnvSource.MIRROR, "aarch64")
        assertEquals("清华 TUNA", mirror.first().label)

        val official = EnvDownloader.candidateSources(EnvKind.LINUX, EnvSource.OFFICIAL, "aarch64")
        assertEquals("Ubuntu 官方", official.first().label)
    }

    @Test
    fun linux_preferredSource_isTriedFirst_withoutDuplication() {
        val sources = EnvDownloader.candidateSources(
            kind = EnvKind.LINUX,
            source = EnvSource.OFFICIAL,
            arch = "aarch64",
            custom = null,
            preferredId = "tuna",
        )
        assertEquals("清华 TUNA", sources[0].label)
        assertEquals(1, sources.count { it.label == "清华 TUNA" })
        assertEquals(sources.size, sources.map { it.url }.distinct().size)
    }

    @Test
    fun linux_preferredSource_unknownId_isIgnored() {
        // 首选 id 不存在（旧值 / 手改偏好）→ 按所选源正常排序，不炸不丢候选
        val sources = EnvDownloader.candidateSources(
            kind = EnvKind.LINUX,
            source = EnvSource.OFFICIAL,
            arch = "aarch64",
            custom = null,
            preferredId = "does-not-exist",
        )
        assertEquals("Ubuntu 官方", sources[0].label)
        assertEquals(RootfsManager.ROOTFS_MIRRORS.size, sources.size)
    }

    @Test
    fun linux_customSource_isFirst_baseUrlGetsFileAppended() {
        val sources = EnvDownloader.candidateSources(
            kind = EnvKind.LINUX,
            source = EnvSource.OFFICIAL,
            arch = "aarch64",
            custom = "https://my.host/rootfs/",
            preferredId = null,
        )
        assertEquals("自定义源", sources[0].label)
        assertEquals(
            "https://my.host/rootfs/ubuntu-base-24.04.5-base-arm64.tar.gz",
            sources[0].url,
        )
        // 自定义源 = 单文件 tar.gz，内置镜像仍全部保留兜底
        assertTrue(sources.size >= RootfsManager.ROOTFS_MIRRORS.size + 1)
        assertEquals(sources.size, sources.map { it.url }.distinct().size)
    }

    @Test
    fun linux_customSource_fullArchiveUrl_usedAsIs_thenPreferredFollows() {
        val sources = EnvDownloader.candidateSources(
            kind = EnvKind.LINUX,
            source = EnvSource.MIRROR,
            arch = "x64",
            custom = "https://my.host/pkg/ubuntu-base-24.04.5-base-amd64.tar.gz",
            preferredId = "official",
        )
        assertEquals(
            "https://my.host/pkg/ubuntu-base-24.04.5-base-amd64.tar.gz",
            sources[0].url,
        )
        // 首选内置源其次；MIRROR 偏好下国内镜像整体靠前，但首选源优先级更高
        assertEquals("Ubuntu 官方", sources[1].label)
        assertEquals("清华 TUNA", sources[2].label)
    }

    @Test
    fun linux_archToken_x64GetsAmd64Package() {
        val arm = EnvDownloader.candidateSources(EnvKind.LINUX, EnvSource.OFFICIAL, "aarch64")
        val x64 = EnvDownloader.candidateSources(EnvKind.LINUX, EnvSource.OFFICIAL, "x64")
        assertTrue(arm.all { it.url.endsWith("-arm64.tar.gz") })
        assertTrue(x64.all { it.url.endsWith("-amd64.tar.gz") })
    }

    @Test
    fun resolveCustomUrl_baseVsFullArchive() {
        assertEquals(
            "https://x/g/ubuntu-base-24.04.5-base-arm64.tar.gz",
            EnvDownloader.resolveCustomUrl("  https://x/g/  ", "ubuntu-base-24.04.5-base-arm64"),
        )
        assertEquals(
            "https://x/f/custom-rootfs.zip",
            EnvDownloader.resolveCustomUrl("https://x/f/custom-rootfs.zip", "custom-rootfs"),
        )
    }

    private fun assertTrue(condition: Boolean) {
        org.junit.Assert.assertTrue(condition)
    }
}
