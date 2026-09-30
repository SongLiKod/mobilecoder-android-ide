package com.mobilecoder.ide.core.common.linux

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [Proot] 的纯文件逻辑：就绪判定、bind 列表、proot 命令行组装、PATH 构造。
 *
 * 依赖 Android Context 的部分（[Proot.envMap] / [Proot.wrap] / [Proot.terminalArgv]）
 * 走真机链路验证；这里覆盖所有可在 JVM 上确定性断言的约定。
 */
class ProotTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun linuxDir(): File = tmp.newFolder("linux")

    /** 铺出就绪骨架：rootfs/bin/bash + bin/proot + bin/loader。 */
    private fun skeleton(linux: File) {
        File(linux, "rootfs/bin").mkdirs()
        File(linux, "rootfs/bin/bash").writeText("")
        File(linux, "bin").mkdirs()
        File(linux, "bin/proot").writeText("")
        File(linux, "bin/loader").writeText("")
    }

    private fun marker(linux: File, content: String) {
        File(linux, ".rootfs").writeText(content)
    }

    // ------------------------------------------------------------------
    // isReady
    // ------------------------------------------------------------------

    @Test
    fun isReady_falseWithoutMarker_orWithStaleVersion() {
        val linux = linuxDir()
        skeleton(linux)
        assertFalse("有文件但没 marker = 上次装到一半 / 从未装完", Proot.isReady(linux))

        marker(linux, "ubuntu-20.04.1-arm64")
        assertFalse("旧版 marker → 触发整体重装", Proot.isReady(linux))
    }

    @Test
    fun isReady_trueWhenMarkerMatches_andFilesPresent() {
        val linux = linuxDir()
        skeleton(linux)
        // marker 内容 = ROOTFS_ID + 架构（RootfsManager.markerContent），允许尾随换行
        marker(linux, "${Proot.ROOTFS_ID}-arm64\n")
        assertTrue(Proot.isReady(linux))
    }

    @Test
    fun isReady_falseWhenAnyKeyFileMissing() {
        val linux = linuxDir()
        marker(linux, "${Proot.ROOTFS_ID}-arm64")

        File(linux, "bin").mkdirs()
        File(linux, "bin/proot").writeText("")
        File(linux, "bin/loader").writeText("")
        assertFalse("缺 rootfs/bin/bash", Proot.isReady(linux))

        File(linux, "rootfs/bin").mkdirs()
        File(linux, "rootfs/bin/bash").writeText("")
        File(linux, "bin/proot").delete()
        assertFalse("缺 bin/proot", Proot.isReady(linux))

        File(linux, "bin/proot").writeText("")
        File(linux, "bin/loader").delete()
        assertFalse("缺 bin/loader", Proot.isReady(linux))
    }

    // ------------------------------------------------------------------
    // binds
    // ------------------------------------------------------------------

    @Test
    fun binds_containsDataDirAndCwd_neverRoot() {
        val data = tmp.newFolder("data")
        val project = tmp.newFolder("project")

        val binds = Proot.binds(data, project.absolutePath)
        assertTrue(binds.contains(data.absolutePath))
        assertTrue(binds.contains(project.absolutePath))
        assertFalse("绝不 bind /（会把 host 根盖到 guest 根上）", binds.contains("/"))
        assertEquals("去重后不应重复", binds.size, binds.distinct().size)
        assertTrue(binds.none { it.isBlank() })
    }

    @Test
    fun binds_ignoresMissingCwd_slash_andBlank() {
        val data = tmp.newFolder("data2")

        val missing = File(tmp.root, "no-such-dir").absolutePath
        assertFalse(Proot.binds(data, missing).contains(missing))

        assertFalse(Proot.binds(data, "/").contains("/"))
        assertTrue(Proot.binds(data, "   ").none { it.isBlank() })

        // cwd 恰好等于 dataDir → 去重只留一份
        val binds = Proot.binds(data, data.absolutePath)
        assertEquals(binds.size, binds.distinct().size)
    }

    // ------------------------------------------------------------------
    // prootArgs
    // ------------------------------------------------------------------

    @Test
    fun prootArgs_coreFlagsInOrder_commandAfterDoubleDash() {
        val config = Proot.Config(
            proot = "/data/user/0/x/files/linux/bin/proot",
            rootfs = "/data/user/0/x/files/linux/rootfs",
            workdir = "/data/user/0/x/files/project",
            binds = listOf("/dev", "/data/user/0/x"),
        )
        val args = Proot.prootArgs(config).toList()
        assertEquals(
            listOf(
                "/data/user/0/x/files/linux/bin/proot",
                "-r", "/data/user/0/x/files/linux/rootfs",
                "--root-id",
                "--link2symlink",
                "--kill-on-exit",
                "-w", "/data/user/0/x/files/project",
                "-b", "/dev",
                "-b", "/data/user/0/x",
                "--",
            ),
            args,
        )
        // 关键约定：被包裹的命令必须跟在 `--` 之后；长名 --root-id（apt/dpkg 写
        // /var/lib/dpkg 的前提）与 --kill-on-exit（回收 guest 子进程）一个都不能少
        assertEquals("--", args.last())
        assertTrue(args.contains("--root-id"))
        assertTrue(args.contains("--link2symlink"))
        assertTrue(args.contains("--kill-on-exit"))
    }

    // ------------------------------------------------------------------
    // pathFor
    // ------------------------------------------------------------------

    @Test
    fun pathFor_guestStandardPathsBeforeBionic() {
        val path = Proot.pathFor("/data/user/0/x/files/bin")
        assertTrue("files/bin 必须最靠前（node / npm 入口）", path.startsWith("/data/user/0/x/files/bin:"))

        val guest = path.indexOf(":/usr/bin:")
        val system = path.indexOf(":/system/bin:")
        assertTrue("guest /usr/bin 缺失", guest > 0)
        assertTrue("/system/bin 必须排在 guest 路径之后", system > guest)
        assertTrue("bionic 兜底不能丢（rootfs 未装时靠它）", system > 0)
    }
}
