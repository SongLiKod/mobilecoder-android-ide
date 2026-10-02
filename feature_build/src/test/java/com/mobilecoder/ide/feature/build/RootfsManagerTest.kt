package com.mobilecoder.ide.feature.build

import com.mobilecoder.ide.core.common.linux.Proot
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [RootfsManager] 的纯文件逻辑：架构映射、候选 URL、rootfs 落位、
 * proot 三件套归位、首启配置（DNS / apt 源 / 主机名）。
 *
 * 下载 / 取消等 IO 管线由 [LiveRootfsVerificationTest] 与真机链路覆盖。
 */
class RootfsManagerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ------------------------------------------------------------------
    // 常量与 URL
    // ------------------------------------------------------------------

    @Test
    fun archMapping_ubuntuAndTermuxTokens() {
        assertEquals("arm64", RootfsManager.ubuntuArch("aarch64"))
        assertEquals("amd64", RootfsManager.ubuntuArch("x64"))
        assertEquals("ubuntu-base-24.04.5-base-arm64.tar.gz", RootfsManager.rootfsFileName("aarch64"))
        assertEquals("ubuntu-base-24.04.5-base-amd64.tar.gz", RootfsManager.rootfsFileName("x64"))
        // marker 与包名同源：ROOTFS_ID 是唯一版本事实
        assertEquals("${Proot.ROOTFS_ID}-arm64", RootfsManager.markerContent("aarch64"))
        assertEquals("${Proot.ROOTFS_ID}-amd64", RootfsManager.markerContent("x64"))
    }

    @Test
    fun rootfsMirrors_shareIdenticalPackagePath() {
        // 两个内置镜像仅域名不同，包路径必须完全一致（候选生成按同一拼接规则）
        assertTrue(RootfsManager.ROOTFS_MIRRORS.size >= 2)
        RootfsManager.ROOTFS_MIRRORS.forEach { mirror ->
            assertTrue(mirror.base.startsWith("https://"))
            val url = "${mirror.base}/releases/24.04/release/${RootfsManager.rootfsFileName("aarch64")}"
            assertTrue(url.endsWith("/releases/24.04/release/ubuntu-base-24.04.5-base-arm64.tar.gz"))
        }
        // 一个国内镜像（MIRROR 排序用）+ id 唯一
        assertTrue(RootfsManager.ROOTFS_MIRRORS.any { it.domestic })
        assertEquals(
            RootfsManager.ROOTFS_MIRRORS.size,
            RootfsManager.ROOTFS_MIRRORS.map { it.id }.distinct().size,
        )
    }

    @Test
    fun prootDebUrls_areThreeTermuxDebsPerArch() {
        val arm = RootfsManager.prootDebUrls("aarch64")
        assertEquals(3, arm.size)
        assertTrue(arm.all { it.startsWith("https://packages.termux.dev/") })
        assertTrue(arm.all { it.endsWith(".deb") })
        assertTrue(arm.all { it.contains("_aarch64.deb") })
        assertTrue(arm.any { it.contains("/p/proot/proot_") })
        assertTrue(arm.any { it.contains("/libt/libtalloc/libtalloc_") })
        assertTrue(arm.any { it.contains("/liba/libandroid-shmem/libandroid-shmem_") })

        val x64 = RootfsManager.prootDebUrls("x64")
        assertTrue(x64.all { it.contains("_x86_64.deb") })
        assertEquals(3, x64.map { it.substringAfterLast('/') }.distinct().size)
    }

    // ------------------------------------------------------------------
    // rootfs 落位
    // ------------------------------------------------------------------

    @Test
    fun rootfsComplete_requiresBashAndApt() {
        val rootfs = File(tmp.root, "rootfs").apply { mkdirs() }
        assertFalse(RootfsManager.rootfsComplete(rootfs))

        File(rootfs, "bin").mkdirs()
        File(rootfs, "bin/bash").writeText("")
        assertFalse("只有 bash 还不算（缺 apt = 结构不是 ubuntu-base）", RootfsManager.rootfsComplete(rootfs))

        File(rootfs, "usr/bin").mkdirs()
        File(rootfs, "usr/bin/apt").writeText("")
        assertTrue(RootfsManager.rootfsComplete(rootfs))
    }

    @Test
    fun placeRootfs_movesTopLevelExtractDir() {
        // ubuntu-base 条目就在压缩包顶层：解压目录整体成为 rootfs
        // （真实包里 bin → usr/bin 是相对软链；JVM 测试用真实目录等价模拟）
        val extract = File(tmp.root, "extract1").apply { mkdirs() }
        File(extract, "usr/bin").mkdirs()
        File(extract, "usr/bin/apt").writeText("")
        File(extract, "bin").mkdirs()
        File(extract, "bin/bash").writeText("")

        val dest = File(tmp.root, "rootfs-dest")
        RootfsManager.placeRootfs(extract, dest)

        assertTrue(File(dest, "bin/bash").isFile)
        assertTrue(File(dest, "usr/bin/apt").isFile)
        assertFalse("rename 落位后源目录不应残留", extract.exists())
    }

    @Test
    fun placeRootfs_findsWrappedRoot() {
        // 防御：万一发行包带一层包裹目录，按 bin/bash 向下找（≤3 层）
        val extract = File(tmp.root, "extract2/ubuntu-base-24.04.5-base-arm64").apply { mkdirs() }
        File(extract, "usr/bin").mkdirs()
        File(extract, "usr/bin/apt").writeText("")
        File(extract, "bin").mkdirs()
        File(extract, "bin/bash").writeText("")
        // 无关文件留在外层
        File(tmp.root, "extract2/README.txt").writeText("junk")

        val dest = File(tmp.root, "rootfs-dest2")
        RootfsManager.placeRootfs(extract, dest)

        assertTrue(File(dest, "bin/bash").isFile)
        assertTrue(File(dest, "usr/bin/apt").isFile)
        assertTrue("外层无关文件留在原地", File(tmp.root, "extract2/README.txt").isFile)
    }

    @Test
    fun placeRootfs_rejectsForeignArchive() {
        val extract = File(tmp.root, "extract3").apply { mkdirs() }
        File(extract, "random.txt").writeText("not a rootfs")
        try {
            RootfsManager.placeRootfs(extract, File(tmp.root, "rootfs-dest3"))
            fail("结构不是 ubuntu-base 时必须抛 IOException")
        } catch (e: java.io.IOException) {
            assertTrue(e.message!!.contains("bin/bash"))
        }
    }

    // ------------------------------------------------------------------
    // proot 三件套归位
    // ------------------------------------------------------------------

    /** 铺出一个 proot deb 的解包布局（data.tar 成员路径）。 */
    private fun prootStaging(): File {
        val staging = File(tmp.root, "staging").apply { mkdirs() }
        File(staging, "usr/bin").mkdirs()
        File(staging, "usr/bin/proot").writeText("proot-bin")
        File(staging, "usr/libexec/proot").mkdirs()
        File(staging, "usr/libexec/proot/loader").writeText("loader64")
        File(staging, "usr/lib").mkdirs()
        File(staging, "usr/lib/libtalloc.so.2").writeText("talloc")
        File(staging, "usr/lib/libtalloc.so.2.5.0").writeText("talloc-real")
        // 应被忽略的目录
        File(staging, "usr/share/doc/proot").mkdirs()
        File(staging, "usr/share/doc/proot/copyright").writeText("license")
        File(staging, "usr/include").mkdirs()
        File(staging, "usr/include/talloc.h").writeText("header")
        return staging
    }

    @Test
    fun placeProotFiles_mapsTermuxLayoutIntoLinuxDir() {
        val linux = File(tmp.root, "linux")
        val copied = RootfsManager.placeProotFiles(prootStaging(), linux)

        assertEquals("usr/bin/proot + loader + 两个 talloc（usr/share、usr/include 跳过）", 4, copied)
        assertEquals("proot-bin", File(linux, "bin/proot").readText())
        assertEquals("loader64", File(linux, "bin/loader").readText())
        assertEquals("talloc", File(linux, "lib/libtalloc.so.2").readText())
        assertEquals("talloc-real", File(linux, "lib/libtalloc.so.2.5.0").readText())
        assertFalse("usr/share 不应归位", File(linux, "lib/copyright").exists())
        assertFalse("usr/include 不应归位", File(linux, "lib/talloc.h").exists())
    }

    @Test
    fun placeProotFiles_isIdempotentOverwrite() {
        val linux = File(tmp.root, "linux2")
        RootfsManager.placeProotFiles(prootStaging(), linux)
        // 第二次（补装 / 重装）必须覆盖旧文件而不是抛错
        File(linux, "bin/proot").writeText("stale")
        val copied = RootfsManager.placeProotFiles(prootStaging(), linux)
        assertTrue(copied > 0)
        assertEquals("proot-bin", File(linux, "bin/proot").readText())
    }

    @Test
    fun placeProotFiles_handlesTermuxPrefixedPaths() {
        // 真实 Termux deb 的 data.tar 带 `data/data/com.termux/files/usr/…` 前缀
        // （2026-09 三个 .deb 实测）：归位按路径后缀匹配，前缀必须不影响结果
        val staging = File(tmp.root, "staging-prefix").apply { mkdirs() }
        val prefix = "data/data/com.termux/files"
        File(staging, "$prefix/usr/bin").mkdirs()
        File(staging, "$prefix/usr/bin/proot").writeText("proot-bin")
        File(staging, "$prefix/usr/libexec/proot").mkdirs()
        File(staging, "$prefix/usr/libexec/proot/loader").writeText("loader64")
        File(staging, "$prefix/usr/lib").mkdirs()
        File(staging, "$prefix/usr/lib/libandroid-shmem.so").writeText("shmem")
        // 前缀路径下的 include 也必须被跳过
        File(staging, "$prefix/usr/include").mkdirs()
        File(staging, "$prefix/usr/include/sys").mkdirs()
        File(staging, "$prefix/usr/include/sys/shm.h").writeText("header")

        val linux = File(tmp.root, "linux-prefix")
        val copied = RootfsManager.placeProotFiles(staging, linux)
        assertEquals(3, copied)
        assertEquals("proot-bin", File(linux, "bin/proot").readText())
        assertEquals("loader64", File(linux, "bin/loader").readText())
        assertEquals("shmem", File(linux, "lib/libandroid-shmem.so").readText())
        assertFalse(File(linux, "lib/shm.h").exists())
    }

    // ------------------------------------------------------------------
    // 首启配置
    // ------------------------------------------------------------------

    private fun rootfsSkeleton(name: String): File {
        val rootfs = File(tmp.root, name).apply { mkdirs() }
        // ubuntu-base 自带的 deb822 双源（必须被清掉）
        val sourcesD = File(rootfs, "etc/apt/sources.list.d").apply { mkdirs() }
        File(sourcesD, "ubuntu.sources").writeText("Types: deb\n")
        File(sourcesD, "vendor.sources").writeText("Types: deb\n")
        File(sourcesD, "keep.list").writeText("deb http://example stable main\n")
        // 旧 resolv.conf（镜像里可能是软链，这里用普通文件模拟「删旧写新」）
        File(rootfs, "etc/resolv.conf").writeText("nameserver 1.1.1.1\n")
        return rootfs
    }

    @Test
    fun setupRootfs_officialAmd64_writesDnsHostsAndArchiveSources() {
        val rootfs = rootfsSkeleton("rootfs-official")
        RootfsManager.setupRootfs(rootfs, domestic = false, ubuntuArch = "amd64")

        val etc = File(rootfs, "etc")
        val resolv = File(etc, "resolv.conf").readText()
        assertTrue(resolv.contains("223.5.5.5"))
        assertTrue(resolv.contains("119.29.29.29"))
        assertTrue(resolv.contains("8.8.8.8"))
        assertFalse("旧 DNS 必须被替换", resolv.contains("1.1.1.1"))

        assertEquals("mobilecoder\n", File(etc, "hostname").readText())
        assertTrue(File(etc, "hosts").readText().contains("127.0.0.1\tlocalhost"))

        val sources = File(etc, "apt/sources.list").readText()
        assertTrue(sources.contains("deb http://archive.ubuntu.com/ubuntu noble "))
        assertTrue(sources.contains("deb http://archive.ubuntu.com/ubuntu noble-updates "))
        assertTrue(sources.contains("deb http://security.ubuntu.com/ubuntu noble-security "))
        assertTrue(sources.contains("# deb-src http://archive.ubuntu.com/ubuntu noble "))
        assertFalse("amd64 不该用 ports 源", sources.contains("ports.ubuntu.com"))
        assertEquals(3, sources.lineSequence().count { it.startsWith("deb ") })

        // deb822 双源被清，无关文件保留
        assertFalse(File(etc, "apt/sources.list.d/ubuntu.sources").exists())
        assertFalse(File(etc, "apt/sources.list.d/vendor.sources").exists())
        assertTrue(File(etc, "apt/sources.list.d/keep.list").exists())
    }

    @Test
    fun setupRootfs_domesticArm64_usesTunaPortsAndDropsSecuritySeparately() {
        val rootfs = rootfsSkeleton("rootfs-domestic")
        RootfsManager.setupRootfs(rootfs, domestic = true, ubuntuArch = "arm64")

        val sources = File(rootfs, "etc/apt/sources.list").readText()
        assertTrue(sources.contains("deb https://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports noble "))
        assertTrue(sources.contains("deb https://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports noble-updates "))
        assertTrue(sources.contains("deb https://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports noble-security "))
        assertFalse("国内源不再依赖 ports.ubuntu.com", sources.contains("ports.ubuntu.com"))
        assertFalse("国内源不该出现 archive.ubuntu.com", sources.contains("archive.ubuntu.com"))
        // 三个 suite 各一行 deb
        assertEquals(3, sources.lineSequence().count { it.startsWith("deb ") })
    }

    @Test
    fun setupRootfs_isIdempotentAndCreatesMissingEtc() {
        val rootfs = File(tmp.root, "rootfs-fresh").apply { mkdirs() }
        // 完全没 etc/ 也能跑（forceReplace 自建目录）
        RootfsManager.setupRootfs(rootfs, domestic = false, ubuntuArch = "arm64")
        val first = File(rootfs, "etc/apt/sources.list").readText()
        RootfsManager.setupRootfs(rootfs, domestic = true, ubuntuArch = "arm64")
        val second = File(rootfs, "etc/apt/sources.list").readText()

        assertFalse("重复执行必须覆盖成新源", second == first)
        assertTrue(second.contains("mirrors.tuna.tsinghua.edu.cn"))
        assertEquals("mobilecoder\n", File(rootfs, "etc/hostname").readText())
    }
}
