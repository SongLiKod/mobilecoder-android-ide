package com.mobilecoder.ide.feature.build

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [MarketInstaller] 纯函数层：安装脚本生成、阶段 / 进度映射、
 * curl 进度解析、日志行装配、已安装探测。
 *
 * 全部在 JVM 内运行（不触 Android / proot / native），覆盖脚本的
 * 关键契约：单文件自包含、镜像回退、架构替换、阶段标记齐全。
 */
class MarketInstallerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ---- ABI → 发布包架构 ----

    @Test
    fun nodeArch_aarch64_mapsToArm64() {
        assertEquals("arm64", MarketInstaller.nodeArch("aarch64"))
    }

    @Test
    fun nodeArch_otherAbis_fallBackToX64() {
        assertEquals("x64", MarketInstaller.nodeArch("x86_64"))
        assertEquals("x64", MarketInstaller.nodeArch("armeabi-v7a"))
        assertEquals("x64", MarketInstaller.nodeArch("unknown"))
    }

    // ---- 安装脚本生成 ----

    @Test
    fun script_isSingleSelfContainedBashWithFailFast() {
        val script = MarketInstaller.nodeScript("/d/tmp", "/d/work", "22.2.0", "arm64")
        assertTrue(script.startsWith("set -e"))
        assertTrue(script.contains("cd '/d/work'"))
        // 目录与粘滞位（proot PROOT_TMP_DIR 约定）
        assertTrue(script.contains("mkdir -p '/d/tmp' '/d/work'"))
        assertTrue(script.contains("chmod 1777 '/d/tmp'"))
        // 依赖：证书链 + 下载器（ubuntu-base 不带 curl）
        assertTrue(script.contains("apt install -y ca-certificates curl"))
    }

    @Test
    fun script_downloadUrl_mirrorWithOfficialFallback_archSubstituted() {
        val script = MarketInstaller.nodeScript("/d/tmp", "/d/work", "22.2.0", "arm64")
        // 发布包文件名带 v 前缀（node-v22.2.0-…，实测官方源与 npmmirror 均如此）
        assertTrue(
            script.contains(
                "https://registry.npmmirror.com/-/binary/node/v22.2.0/node-v22.2.0-linux-arm64.tar.gz",
            ),
        )
        assertTrue(
            script.contains("https://nodejs.org/dist/v22.2.0/node-v22.2.0-linux-arm64.tar.gz"),
        )
        // 镜像失败回退官方源（|| 串联）
        assertTrue(script.contains(".tar.gz' -o node.tar.gz || curl "))
        // x64 包
        val x64 = MarketInstaller.nodeScript("/d/tmp", "/d/work", "22.2.0", "x64")
        assertTrue(x64.contains("node-v22.2.0-linux-x64.tar.gz"))
        assertFalse(x64.contains("linux-arm64"))
    }

    @Test
    fun script_versionVPrefix_accepted() {
        // 兼容 "v22.2.0" 写法：去 v 后拼 URL，产物一致
        val withV = MarketInstaller.nodeScript("/d/tmp", "/d/work", "v22.2.0", "arm64")
        val withoutV = MarketInstaller.nodeScript("/d/tmp", "/d/work", "22.2.0", "arm64")
        assertEquals(withoutV, withV)
    }

    @Test
    fun script_extractInstallVerifyCleanupOrder() {
        val script = MarketInstaller.nodeScript("/d/tmp", "/d/work", "22.2.0", "arm64")
        val tar = script.indexOf("tar -zxf node.tar.gz")
        val cp = script.indexOf("cp -r ")
        val verify = script.indexOf("/usr/local/bin/node --version")
        // 清理行按完整路径定位：解压前还有一次 rm -rf 旧解压目录，不能撞上
        val cleanup = script.indexOf("rm -rf '/d/work/node.tar.gz'")
        val done = script.indexOf("完成'")
        assertTrue("解压必须在落位前", tar in 0 until cp)
        assertTrue("落位必须在校验前", cp in 0 until verify)
        assertTrue("校验必须在清理前", verify in 0 until cleanup)
        assertTrue("清理必须在完成前", cleanup in 0 until done)
        // 落位目标：bin/include/lib/share → /usr/local/
        assertTrue(script.contains("bin' '/d/work/node-v22.2.0-linux-arm64/include"))
        assertTrue(script.contains("share' /usr/local/"))
        // 清理下载物与解压目录
        assertTrue(script.contains("rm -rf '/d/work/node.tar.gz' '/d/work/node-v22.2.0-linux-arm64'"))
    }

    @Test
    fun script_carryAllSevenStageMarkers() {
        val script = MarketInstaller.nodeScript("/d/tmp", "/d/work", "22.2.0", "arm64")
        // 脚本源码里标记包在 echo 中；运行时输出才是裸标记（handleLine 侧由 parseStage 解析）
        val markers = script.lines()
            .filter { it.startsWith("echo '") }
            .map { it.removePrefix("echo '").removeSuffix("'").removePrefix(MarketInstaller.STAGE_PREFIX) }
        assertEquals(
            listOf(
                "准备目录",
                "安装依赖（apt：ca-certificates / curl）",
                "下载 node-v22.2.0-linux-arm64（npmmirror，失败回退官方源）",
                "解压并安装到 /usr/local",
                "校验安装",
                "清理临时文件",
                "完成",
            ),
            markers,
        )
        // 每个标记都要能解析出进度（安装依赖阶段为不确定进度 -1）
        val progresses = markers.map { MarketInstaller.stageProgress(it) }
        assertEquals(
            listOf(0.30f, -1f, 0.35f, 0.92f, 0.96f, 0.98f, 1f),
            progresses,
        )
    }

    // ---- 阶段标记解析 / 进度映射 ----

    @Test
    fun parseStage_markerPrefixStripped_othersNull() {
        assertEquals("准备目录", MarketInstaller.parseStage("###MC:准备目录"))
        assertEquals("完成", MarketInstaller.parseStage("###MC:完成  "))
        assertNull(MarketInstaller.parseStage("Get:1 http://ports.ubuntu.com"))
        assertNull(MarketInstaller.parseStage(""))
        // 前缀必须在行首
        assertNull(MarketInstaller.parseStage("x ###MC:完成"))
    }

    @Test
    fun stageProgress_knownStages_mappedToAscentBasis() {
        assertEquals(0.30f, MarketInstaller.stageProgress("准备目录"), EPS)
        assertEquals(0.15f, MarketInstaller.stageProgress("更新软件源"), EPS)
        assertEquals(0.10f, MarketInstaller.stageProgress("检查 Node.js 运行环境"), EPS)
        assertEquals(-1f, MarketInstaller.stageProgress("安装依赖（apt）"), EPS)
        assertEquals(-1f, MarketInstaller.stageProgress("安装 @opencode/cli（npm install -g）"), EPS)
        assertEquals(0.35f, MarketInstaller.stageProgress("下载 node-linux-arm64"), EPS)
        assertEquals(0.92f, MarketInstaller.stageProgress("解压并安装到 /usr/local"), EPS)
        assertEquals(0.96f, MarketInstaller.stageProgress("校验安装"), EPS)
        assertEquals(0.98f, MarketInstaller.stageProgress("清理临时文件"), EPS)
        assertEquals(1f, MarketInstaller.stageProgress("完成"), EPS)
        assertEquals(-1f, MarketInstaller.stageProgress("未知阶段"), EPS)
    }

    // ---- curl 进度解析 ----

    @Test
    fun downloadPercent_barFramesParsed() {
        assertEquals(0.624f, MarketInstaller.downloadPercent("#################### 62.4%")!!, EPS)
        assertEquals(0f, MarketInstaller.downloadPercent("0.0%")!!, EPS)
        assertEquals(1f, MarketInstaller.downloadPercent("100%")!!, EPS)
        // 多个百分比取最后一个（curl 行尾是当前进度）
        assertEquals(0.75f, MarketInstaller.downloadPercent("up 50% 75%")!!, EPS)
    }

    @Test
    fun downloadPercent_nonProgressLines_null() {
        assertNull(MarketInstaller.downloadPercent("  % Total    % Received"))
        assertNull(MarketInstaller.downloadPercent("100 24.5M  100 24.5M  50.0M/s  0:00"))
        // 越界百分比不参与进度（真实含百分比但非法的行交由 isProgressFrame 把关）
        assertNull(MarketInstaller.downloadPercent("150%"))
    }

    @Test
    fun isProgressFrame_barOnly_headerAndMeterRejected() {
        assertTrue(MarketInstaller.isProgressFrame("#################### 62.4%"))
        assertTrue(MarketInstaller.isProgressFrame("   #  0.0%"))
        assertFalse(MarketInstaller.isProgressFrame("  % Total    % Received % Xferd"))
        assertFalse(MarketInstaller.isProgressFrame("100 24.5M  100 24.5M"))
        assertFalse(MarketInstaller.isProgressFrame("progress: 62.4% remaining"))
        assertFalse(MarketInstaller.isProgressFrame("E: 50% search failed"))
    }

    @Test
    fun isProgressFrame_bareAnimationFrames_accepted() {
        // curl -# 起步阶段（尚无百分比）的纯动画帧——真机日志里刷过这些行
        assertTrue(MarketInstaller.isProgressFrame("#=#=#"))
        assertTrue(MarketInstaller.isProgressFrame("##O#-#"))
        assertTrue(MarketInstaller.isProgressFrame("-=O#-#   #   #"))
        assertTrue(MarketInstaller.isProgressFrame("   #  =   -"))
        assertFalse(MarketInstaller.isProgressFrame(""))
        assertFalse(MarketInstaller.isProgressFrame("   "))
    }

    // ---- 日志行装配（\n / \r / \r\n / 半截块） ----

    @Test
    fun lineAssembler_splitsOnNewline() {
        val assembler = MarketInstaller.LineAssembler()
        assertEquals(listOf("a", "b"), assembler.feed("a\nb\n"))
        assertEquals(emptyList<String>(), assembler.flush())
    }

    @Test
    fun lineAssembler_splitsOnCarriageReturn_curlOverwriteFrames() {
        val assembler = MarketInstaller.LineAssembler()
        assertEquals(listOf("62.4%", "63.0%"), assembler.feed("62.4%\r63.0%\r"))
        assertEquals(listOf("next"), assembler.feed("next\n"))
    }

    @Test
    fun lineAssembler_crlfMergedIntoSingleSplit() {
        val assembler = MarketInstaller.LineAssembler()
        // \r\n 只切一次；b 无终止符留在缓冲区
        assertEquals(listOf("a"), assembler.feed("a\r\nb"))
        assertEquals(listOf("b"), assembler.flush())
    }

    @Test
    fun lineAssembler_partialChunkCarriedAndTrimmed() {
        val assembler = MarketInstaller.LineAssembler()
        assertEquals(emptyList<String>(), assembler.feed("hel"))
        assertEquals(listOf("hello"), assembler.feed("lo\n"))
        // 行内容去首尾空白；纯空白行被丢弃
        assertEquals(listOf("padded"), assembler.feed("  padded  \n"))
        assertEquals(emptyList<String>(), assembler.feed(" \n"))
        assertEquals(emptyList<String>(), assembler.flush())
    }

    @Test
    fun lineAssembler_flushReturnsTrailingLineWithoutNewline() {
        val assembler = MarketInstaller.LineAssembler()
        assertEquals(emptyList<String>(), assembler.feed("tail"))
        assertEquals(listOf("tail"), assembler.flush())
        assertEquals(emptyList<String>(), assembler.flush())
    }

    // ---- 已安装探测 / 状态模型 ----

    @Test
    fun probeInstalled_detectsNodeUnderUsrLocalBin() {
        val item = MarketInstaller.items.first { it.id == "nodejs" }
        assertEquals("usr/local/bin/node", item.probe)

        val rootfs = tmp.newFolder("rootfs")
        assertFalse(MarketInstaller.probeInstalled(rootfs, item))

        val node = File(rootfs, "usr/local/bin/node")
        File(rootfs, "usr/local/bin").mkdirs()
        node.writeText("#!/bin/sh")
        assertTrue(MarketInstaller.probeInstalled(rootfs, item))
    }

    @Test
    fun catalog_nodeOpencodeAndNineAptTools() {
        assertEquals(
            listOf("nodejs", "opencode", "git", "python", "buildtools", "tmux", "ripgrep", "fzf", "jq", "vim", "fd"),
            MarketInstaller.items.map { it.id },
        )
        val node = MarketInstaller.items.first { it.id == "nodejs" }
        assertEquals("22.2.0", node.version)
        assertTrue(node.name.contains("Node"))
        assertEquals("v22.2.0", MarketInstaller.versionLabel(node.version))

        val opencode = MarketInstaller.items.first { it.id == "opencode" }
        assertEquals("latest", opencode.version)
        assertEquals("usr/local/bin/opencode", opencode.probe)
        assertEquals("latest", MarketInstaller.versionLabel(opencode.version))

        // 探测点均为 rootfs 下真实路径约定；文案非空；apt 条目徽标显示 apt（版本随源）
        MarketInstaller.items.forEach { item ->
            assertTrue("${item.id} probe=${item.probe}", item.probe.startsWith("usr/"))
            assertTrue(item.id, item.summary.isNotBlank() && item.detail.isNotBlank())
        }
        assertEquals("apt", MarketInstaller.versionLabel("apt"))
    }

    @Test
    fun installTitle_rendersPerVersionToken() {
        val node = MarketInstaller.items.first { it.id == "nodejs" }
        assertEquals("Node.js v22.2.0", MarketInstaller.installTitle(node))
        val opencode = MarketInstaller.items.first { it.id == "opencode" }
        assertEquals("opencode latest", MarketInstaller.installTitle(opencode))
        val git = MarketInstaller.items.first { it.id == "git" }
        assertEquals("Git", MarketInstaller.installTitle(git))
    }

    // ---- apt 类：规格表 + 脚本 ----

    @Test
    fun aptSpecs_matchCatalog_andEveryAptItemHasSpec() {
        // 无孤儿规格
        MarketInstaller.aptSpecs.keys.forEach { id ->
            assertTrue("orphan apt spec: $id", MarketInstaller.items.any { it.id == id })
        }
        // 除 nodejs/opencode 外全部走 apt 路由（不会误落到 node 下载脚本）
        MarketInstaller.items
            .filter { it.id != "nodejs" && it.id != "opencode" }
            .forEach { item ->
                val spec = MarketInstaller.aptSpecs[item.id] ?: error("missing apt spec: ${item.id}")
                val script = MarketInstaller.scriptFor(item, "/d/tmp", "/d/work", "arm64")
                assertTrue(item.id, script.startsWith("set -e"))
                assertTrue(item.id, script.contains("apt update"))
                assertTrue(
                    item.id,
                    script.contains("apt install -y ${spec.packages.joinToString(" ")}"),
                )
                spec.verify.forEach { assertTrue("${item.id} missing $it", script.contains(it)) }
                assertFalse(item.id, script.contains("nodejs.org"))
                assertFalse(item.id, script.contains("@opencode/cli"))
            }
    }

    @Test
    fun aptScript_stageMarkers_mapToProgress() {
        val script = MarketInstaller.aptScript(
            MarketInstaller.AptSpec(listOf("git"), listOf("git --version")),
        )
        assertTrue(script.contains("apt install -y git"))
        val markers = script.lines()
            .filter { it.startsWith("echo '") }
            .map { it.removePrefix("echo '").removeSuffix("'").removePrefix(MarketInstaller.STAGE_PREFIX) }
        assertEquals(listOf("更新软件源", "安装 git", "校验安装", "完成"), markers)
        assertEquals(listOf(0.15f, -1f, 0.96f, 1f), markers.map { MarketInstaller.stageProgress(it) })
        // 校验命令夹在校验标记之后
        val lines = script.lines()
        assertTrue(
            lines.indexOf("git --version") >
                lines.indexOf("echo '${MarketInstaller.STAGE_PREFIX}校验安装'"),
        )
    }

    // ---- opencode：npm 全局安装脚本 ----

    @Test
    fun opencodeScript_isUserNpmCommand_withPrerequisiteAndMirrorFallback() {
        val script = MarketInstaller.opencodeScript()
        assertTrue(script.startsWith("set -e"))
        // 用户给定的原命令（市场 npm 分支 + PATH 兜底分支各一次，均带回退）
        assertTrue(script.contains("/usr/local/bin/npm install -g @opencode/cli ||"))
        assertTrue(script.contains("    npm install -g @opencode/cli ||"))
        assertTrue(script.contains("npm install -g @opencode/cli --registry=https://registry.npmmirror.com"))
        // npm 缺失 → 明确提示并失败（引导先装 Node.js）
        assertTrue(script.contains("未找到 npm：请先在软件市场安装 Node.js"))
        assertTrue(script.contains("exit 3"))
        // 校验落点与 /usr/local 探测点一致
        assertTrue(script.contains("/usr/local/bin/opencode --version"))
    }

    @Test
    fun opencodeScript_markers_mapToProgress() {
        val markers = MarketInstaller.opencodeScript().lines()
            .filter { it.startsWith("echo '") }
            .map { it.removePrefix("echo '").removeSuffix("'").removePrefix(MarketInstaller.STAGE_PREFIX) }
        assertEquals(
            listOf("检查 Node.js 运行环境", "安装 @opencode/cli（npm install -g）", "校验安装", "完成"),
            markers,
        )
        assertEquals(listOf(0.10f, -1f, 0.96f, 1f), markers.map { MarketInstaller.stageProgress(it) })
    }

    @Test
    fun scriptFor_routesEachItemToItsScript() {
        val node = MarketInstaller.items.first { it.id == "nodejs" }
        val opencode = MarketInstaller.items.first { it.id == "opencode" }
        val nodeScript = MarketInstaller.scriptFor(node, "/d/tmp", "/d/work", "arm64")
        assertTrue(nodeScript.contains("nodejs.org/dist"))
        assertFalse(nodeScript.contains("@opencode/cli"))

        val opencodeScript = MarketInstaller.scriptFor(opencode, "/d/tmp", "/d/work", "arm64")
        assertTrue(opencodeScript.contains("install -g @opencode/cli"))
        assertFalse(opencodeScript.contains("nodejs.org"))
    }

    @Test
    fun marketState_busyOnlyWhileActive() {
        assertFalse(MarketState().busy)
        assertFalse(MarketState(activeId = null, lastId = "nodejs").busy)
        assertTrue(MarketState(activeId = "nodejs").busy)
        // 初始：无任务、无日志、进度不确定
        val fresh = MarketState()
        assertEquals(-1f, fresh.progress, EPS)
        assertEquals(emptyList<String>(), fresh.logs)
    }

    @Test
    fun stagePrefix_isStableMarkerContract() {
        // 脚本 echo 与运行时解析共享同一前缀（改一处必改另一处的契约锚点）
        assertEquals("###MC:", MarketInstaller.STAGE_PREFIX)
        assertTrue(
            MarketInstaller.nodeScript("/d/tmp", "/d/work", "22.2.0", "arm64")
                .contains("echo '${MarketInstaller.STAGE_PREFIX}完成'"),
        )
    }

    private companion object {
        const val EPS = 0.0001f
    }
}
