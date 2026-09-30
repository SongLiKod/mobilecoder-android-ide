package com.mobilecoder.ide.core.common.linux

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [Proot] 的纯文件逻辑：就绪判定、bind 列表、proot 命令行组装、PATH 构造、账号映射。
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
    fun prootArgs_coreFlagsInOrder_noDoubleDashSeparator() {
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
            ),
            args,
        )
        // 长名 --root-id（apt/dpkg 写 /var/lib/dpkg 的前提）与
        // --kill-on-exit（回收 guest 子进程）一个都不能少
        assertTrue(args.contains("--root-id"))
        assertTrue(args.contains("--link2symlink"))
        assertTrue(args.contains("--kill-on-exit"))
        // 防回归（真机踩坑）：proot 的参数表里没有 `--`，加了会直接
        // `proot error: unknown option '--'` 拒绝启动——proot 以第一个
        // 不以 `-` 开头的参数为命令起点，分界靠 argv[0] 是绝对路径保证
        assertFalse("proot 不认识 `--` 分隔符", args.contains("--"))
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

    // ------------------------------------------------------------------
    // applyAccounts（运行时 uid/补充组 → guest passwd/group）
    // ------------------------------------------------------------------

    @Test
    fun applyAccounts_writesPasswdAndGroup_keepingSystemLines() {
        val etc = tmp.newFolder("etc-mixed")
        File(etc, "passwd").writeText(
            "root:x:0:0:root:/root:/bin/bash\nnobody:x:65534:65534:nobody:/nonexistent:/usr/sbin/nologin\n",
        )
        File(etc, "group").writeText("root:x:0:\ndialout:x:20:\n")

        Proot.applyAccounts(
            etc = etc,
            uid = 10359,
            gid = 10359,
            gids = intArrayOf(3003, 9997, 99909997, 0, -1),
            home = "/data/user/0/x/files",
        )

        val passwd = File(etc, "passwd").readText()
        assertTrue(
            "运行时账号行",
            passwd.contains("mobilecoder:x:10359:10359:MobileCoder app:/data/user/0/x/files:/bin/bash"),
        )
        assertTrue("系统行必须保留", passwd.contains("root:x:0:0:"))
        assertTrue(passwd.contains("nobody:x:65534:"))

        val group = File(etc, "group").readText()
        assertTrue("真机补充组逐一落位", group.contains("android3003:x:3003:"))
        assertTrue(group.contains("android9997:x:9997:"))
        assertTrue(group.contains("android99909997:x:99909997:"))
        assertFalse("0/负数不写（root 已存在）", group.contains("android0:"))
        assertFalse(group.contains("android-1:"))
        assertTrue(group.contains("root:x:0:"))
        assertTrue("group 系统行保留", group.contains("dialout:x:20:"))
    }

    @Test
    fun applyAccounts_idempotent_replacesStaleUid_keepsExistingGidNames() {
        val etc = tmp.newFolder("etc-idem")
        // 同 gid 已有条目（名字不冲突）→ 保留原名、不再追加
        File(etc, "group").writeText("dialout:x:3003:\n")

        Proot.applyAccounts(etc, uid = 11000, gid = 11000, gids = intArrayOf(3003, 9997), home = "/a")
        Proot.applyAccounts(etc, uid = 12000, gid = 12000, gids = intArrayOf(3003, 9997), home = "/a")

        val passwd = File(etc, "passwd").readText()
        assertFalse("旧 uid 行必须被替换（重装换 uid）", passwd.contains("11000"))
        assertTrue(passwd.contains("mobilecoder:x:12000:12000:MobileCoder app:/a:/bin/bash"))
        assertEquals(1, passwd.lineSequence().count { it.startsWith("mobilecoder:") })

        val group = File(etc, "group").readText()
        assertTrue("同 gid 已有条目保留原名", group.contains("dialout:x:3003:"))
        assertEquals(0, group.lineSequence().count { it.startsWith("android3003:") })
        assertEquals(1, group.lineSequence().count { it.startsWith("android9997:") })

        // 第三次（App 每次启动都会跑）→ 仍无重复
        Proot.applyAccounts(etc, uid = 12000, gid = 12000, gids = intArrayOf(3003, 9997), home = "/a")
        assertEquals(1, File(etc, "passwd").readText().lineSequence().count { it.startsWith("mobilecoder:") })
        assertEquals(1, File(etc, "group").readText().lineSequence().count { it.startsWith("android9997:") })
    }

    @Test
    fun applyAccounts_createsMissingEtcAndFiles() {
        val etc = File(tmp.root, "fresh-etc")
        assertFalse(etc.exists())

        Proot.applyAccounts(etc, uid = 10001, gid = 10001, gids = intArrayOf(3003), home = "/h")

        assertTrue("passwd 自动创建", File(etc, "passwd").isFile)
        assertTrue("group 自动创建", File(etc, "group").isFile)
        assertTrue(File(etc, "passwd").readText().startsWith("mobilecoder:"))
        assertTrue(File(etc, "group").readText().contains("android3003:x:3003:"))
    }

    // ------------------------------------------------------------------
    // parseProcStatus（/proc/self/status → 运行时 uid/gid/补充组）
    // ------------------------------------------------------------------

    @Test
    fun parseProcStatus_extractsRealIdsFromProcFormat() {
        val text = """
            Name:	bash
            Umask:	0022
            State:	S (sleeping)
            Uid:	10359	10359	10359	10359
            Gid:	10359	10359	10359	10359
            FDSize:	64
            Groups:	3003 9997 20632 50632 99909997
            NStgid:	10359
        """.trimIndent()

        val ids = Proot.parseProcStatus(text)
        // 真机报错原文里的那5个补充组必须被解析出来（proot 不伪装 getgroups）
        assertEquals(10359, ids?.uid)
        assertEquals(10359, ids?.gid)
        assertEquals(listOf(3003, 9997, 20632, 50632, 99909997), ids?.gids)
    }

    @Test
    fun parseProcStatus_groupsLineOptional_returnsNullWithoutUidOrGid() {
        val noGroups = """
            Name:	sh
            Uid:	10001	10001	10001	10001
            Gid:	10001	10001	10001	10001
        """.trimIndent()
        val ids = Proot.parseProcStatus(noGroups)
        assertEquals(10001, ids?.uid)
        assertEquals(emptyList<Int>(), ids?.gids)

        // Uid 缺失 / 垃圾内容 → null（调用方回退 Process.myUid()/Os.getegid()）
        assertNull(Proot.parseProcStatus("Gid:\t10001\t10001\t10001\t10001"))
        assertNull(Proot.parseProcStatus(""))
        assertNull(Proot.parseProcStatus("Uid:\tnotanumber\nGid:\talso-bad"))
    }
}
