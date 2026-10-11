package com.mobilecoder.ide.feature.build

import android.content.Context
import com.mobilecoder.ide.core.common.linux.Proot
import com.mobilecoder.ide.core.nativebridge.CliCallback
import com.mobilecoder.ide.core.nativebridge.CliNative
import java.io.File
import java.math.BigInteger
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 安卓 Native 工具链一键装配（真机验证过的手动步骤全部产品化，幂等分步）：
 *
 *  1. apt 装配（guest 内，assets/toolchain/apt-provision.sh）：qemu-user-static +
 *     amd64 multiarch 运行库（NDK clang / aapt2 是 x86_64 ELF，经 qemu 执行）+
 *     原生 cmake / ninja；
 *  2. Gradle wrapper 发行包预置：`files/sdk/gradle` 复制进
 *     `GRADLE_USER_HOME/wrapper/dists/gradle-<v>-bin/<base36(md5(url))>/`，
 *     工程 gradlew 免下载直用（哈希算法与 org.gradle.wrapper.PathAssembler 一致）；
 *  3. NDK r26 下载 → **guest unzip 解压**（符号链接必须正确落盘）→ 工具链 bin
 *     下 x86_64 ELF 移 `.bin` + 生成 `qemu -0 <绝对路径>` 包装脚本
 *     （lld 靠 argv0 认调用模式、clang 靠 argv0 定位 InstalledDir，均真机验证）；
 *  4. `sdk/cmake/3.22.1/bin/{cmake,ninja}` 包装器：转调 guest 原生实现，ninja
 *     额外把 `-j` 钳到 2（qemu 下高并发打爆内存）；
 *  5. aapt2 适配（[Aapt2Adapter]）+ maven 镜像 init 预置。
 *
 * AGP 只认 `sdk/cmake/<ver>/bin/cmake` 与 `sdk/ndk/<revision>` 标准布局，全部
 * 按官方结构伪装，用户工程零改动即可 assembleDebug 出含 native 的 APK。
 * bash 脚本一律放 assets/toolchain/，避免 Kotlin 模板转义地狱。
 */
object NativeToolchain {

    /** 真机验证过的 NDK revision（目录名 = source.properties 里的 revision）。 */
    const val NDK_REVISION = "26.3.11579264"
    const val NDK_ZIP_FILE = "android-ndk-r26d-linux.zip"
    const val CMAKE_VERSION = "3.22.1"
    const val NINJA_MAX_JOBS = 2

    private const val ASSET_DIR = "toolchain"

    // ------------------------------------------------------------------
    // 目录与就绪探测
    // ------------------------------------------------------------------

    fun ndkParent(context: Context): File = File(BuildEnvironment.sdkDir(context), "ndk")
    fun ndkDir(context: Context): File = File(ndkParent(context), NDK_REVISION)
    private fun ndkToolBin(context: Context): File =
        File(ndkDir(context), "toolchains/llvm/prebuilt/linux-x86_64/bin")

    fun cmakeBinDir(context: Context): File =
        File(BuildEnvironment.sdkDir(context), "cmake/$CMAKE_VERSION/bin")

    /** NDK 已装配且包装成功：clang 是脚本（#! 开头）而非裸 ELF。 */
    fun isNdkWrapped(context: Context): Boolean {
        val clang = File(ndkToolBin(context), "clang")
        return clang.isFile && clang.canExecute() &&
            runCatching { clang.inputStream().use { it.read() == '#'.code && it.read() == '!'.code } }
                .getOrDefault(false)
    }

    fun aptComponentsInstalled(context: Context): Boolean {
        val rootfs = Proot.rootfsDir(context)
        return File(rootfs, "usr/bin/qemu-x86_64-static").isFile &&
            File(rootfs, "usr/lib/x86_64-linux-gnu/libz.so.1").isFile &&
            File(rootfs, "usr/bin/cmake").isFile &&
            File(rootfs, "usr/bin/ninja").isFile
    }

    fun cmakeWrappersReady(context: Context): Boolean {
        val dir = cmakeBinDir(context)
        return File(dir, "cmake").canExecute() &&
            runCatching { File(dir, "ninja").readText().contains("/usr/bin/ninja") }.getOrDefault(false)
    }

    fun wrapperDistSeeded(context: Context): Boolean {
        val version = gradleVersion(context) ?: return false
        return markerOf(context, version).isFile
    }

    /** 全链路就绪（环境卡片 / 构建前检查共用）。 */
    fun isReady(context: Context): Boolean =
        aptComponentsInstalled(context) && isNdkWrapped(context) &&
            cmakeWrappersReady(context) && Aapt2Adapter.isReady(context)

    /** 缺项摘要（环境卡片副标题）。 */
    fun missingSummary(context: Context): String {
        val miss = mutableListOf<String>()
        if (!aptComponentsInstalled(context)) miss += "qemu/cmake/ninja"
        if (!isNdkWrapped(context)) miss += "NDK r26"
        if (!cmakeWrappersReady(context)) miss += "cmake 包装器"
        if (!Aapt2Adapter.isReady(context)) miss += "aapt2"
        return if (miss.isEmpty()) "全部就绪" else "缺少：" + miss.joinToString("、")
    }

    /** 工程是否需要 NDK（安卓工程且存在 CMakeLists.txt）。 */
    fun projectNeedsNdk(root: File): Boolean {
        if (!hasCMakeLists(root)) return false
        return BuildEnvironment.isAndroidProject(root)
    }

    /** 浅层扫描（根 / app / 一级子模块 / 一级子模块 app）的 cpp 构建文件。 */
    internal fun hasCMakeLists(root: File): Boolean {
        val candidates = listOf(
            File(root, "src/main/cpp/CMakeLists.txt"),
            File(root, "app/src/main/cpp/CMakeLists.txt"),
        )
        if (candidates.any { it.isFile }) return true
        return runCatching {
            root.listFiles()?.any { module ->
                module.isDirectory && !module.name.startsWith(".") &&
                    File(module, "src/main/cpp/CMakeLists.txt").isFile
            } == true
        }.getOrDefault(false)
    }

    // ------------------------------------------------------------------
    // 一键安装
    // ------------------------------------------------------------------

    /**
     * 装配缺失项（已就绪的步骤自动跳过）。
     * 前提：Linux 环境就绪（调用方保证 / [EnvDownloader.install] LINUX）。
     * @throws IllegalStateException 任一步失败（消息含自救建议）
     */
    suspend fun install(
        context: Context,
        source: EnvSource,
        onStage: (String) -> Unit = {},
        onLog: (String) -> Unit = {},
    ) {
        check(Proot.isReady(context)) { "需要先安装 Linux 环境（rootfs + proot）" }

        if (!aptComponentsInstalled(context)) {
            onStage("安装 qemu-user / amd64 运行库 / cmake / ninja（约 80MB）…")
            val rc = runGuestScript(context, assetText(context, "apt-provision.sh"), onLog)
            check(rc == 0 && aptComponentsInstalled(context)) {
                "apt 组件安装失败（退出码 $rc），请检查网络后重试"
            }
        }

        if (!wrapperDistSeeded(context)) {
            onStage("预置 Gradle wrapper 发行包…")
            seedWrapperDist(context, null, onLog)
        }

        if (!isNdkWrapped(context)) {
            installNdk(context, source, onStage, onLog)
        }

        onStage("生成 cmake / ninja 包装器…")
        writeCmakeWrappers(context)

        onStage("准备 aapt2（x86_64，经 qemu 执行）…")
        check(Aapt2Adapter.prepare(context, onLog)) {
            "aapt2 适配未就绪（下载或 qemu 安装失败），请查看日志"
        }

        BuildRunner.ensureMirrorInit(context)
        onStage("Native 工具链装配完成")
    }

    private suspend fun installNdk(
        context: Context,
        source: EnvSource,
        onStage: (String) -> Unit,
        onLog: (String) -> Unit,
    ) {
        val cache = File(context.cacheDir, "envdl").apply { mkdirs() }
        val zip = File(cache, NDK_ZIP_FILE)
        val failures = mutableListOf<String>()
        var downloaded = false
        for (cand in ndkCandidates(source)) {
            try {
                onStage("正在下载 NDK r26（${cand.label}，约 640MB）…")
                var lastMark = 0L
                EnvDownloader.download(cand.url, zip) { received, total ->
                    if (total > 0 && received - lastMark >= 64L * 1024 * 1024) {
                        lastMark = received
                        onLog("NDK 下载 ${received / 1024 / 1024}MB / ${total / 1024 / 1024}MB")
                    }
                }
                downloaded = true
                break
            } catch (c: DownloadCancelled) {
                runCatching { zip.delete() }
                throw c
            } catch (t: Throwable) {
                failures += "${cand.label}：${t.message ?: t::class.java.simpleName}"
                runCatching { zip.delete() }
            }
        }
        check(downloaded) { "NDK 下载失败：${failures.joinToString("；")}" }

        onStage("解压并包装 NDK（guest unzip + qemu 包装，约 3-6 分钟）…")
        val script = assetText(context, "ndk-wrap.sh")
            .replace("__ZIP__", zip.absolutePath)
            .replace("__NDK_TARGET__", ndkDir(context).absolutePath)
            .replace("__TOOL_BIN__", ndkToolBin(context).absolutePath)
            .replace("__NDK_ROOT__", ndkParent(context).absolutePath)
        val rc = runGuestScript(context, script, onLog)
        runCatching { zip.delete() }
        check(rc == 0 && isNdkWrapped(context)) { "NDK 解压 / 包装失败（退出码 $rc）" }
    }

    /** NDK 包候选源（镜像序按偏好）。 */
    fun ndkCandidates(source: EnvSource): List<EnvDownloader.Source> {
        val official = EnvDownloader.Source(
            "Google 官方",
            "https://dl.google.com/android/repository/$NDK_ZIP_FILE",
        )
        val mirror = EnvDownloader.Source(
            "腾讯云镜像",
            "https://mirrors.cloud.tencent.com/ndk/$NDK_ZIP_FILE",
        )
        return if (source == EnvSource.MIRROR) listOf(mirror, official) else listOf(official, mirror)
    }

    // ------------------------------------------------------------------
    // Gradle wrapper dist 预置
    // ------------------------------------------------------------------

    /** 与 org.gradle.wrapper.PathAssembler 一致：base36(md5(distributionUrl))。 */
    internal fun wrapperDistHash(distributionUrl: String): String {
        val md5 = MessageDigest.getInstance("MD5")
            .digest(distributionUrl.toByteArray(Charsets.UTF_8))
        return BigInteger(1, md5).toString(36)
    }

    /** 从 `files/sdk/gradle/lib/gradle-launcher-<v>.jar` 反推版本号。 */
    internal fun gradleVersionOfDir(gradleDir: File): String? =
        runCatching {
            File(gradleDir, "lib").listFiles()?.firstNotNullOfOrNull { f ->
                Regex("gradle-launcher-(.+)\\.jar").matchEntire(f.name)?.groupValues?.get(1)
            }
        }.getOrNull()

    private fun gradleVersion(context: Context): String? =
        gradleVersionOfDir(File(BuildEnvironment.sdkDir(context), "gradle"))

    private fun wrapperDistUrl(version: String): String =
        "https://services.gradle.org/distributions/gradle-$version-bin.zip"

    private fun markerOf(context: Context, version: String): File =
        File(
            BuildEnvironment.gradleUserHome(context),
            "wrapper/dists/gradle-$version-bin/${wrapperDistHash(wrapperDistUrl(version))}/" +
                "gradle-$version-bin.zip.ok",
        )

    /**
     * 把 `files/sdk/gradle` 复制进 wrapper dists 标准布局 + `.ok` 标记
     * （真机验证组合：解包目录 + 标记；[zipOverride] 为安装现场还留着的发行 zip）。
     * @return 预置的 gradle 版本；没有 gradle 发行版时 null
     */
    suspend fun seedWrapperDist(
        context: Context,
        zipOverride: File? = null,
        onLog: (String) -> Unit = {},
    ): String? = withContext(Dispatchers.IO) {
        val gradleDir = File(BuildEnvironment.sdkDir(context), "gradle")
        if (!File(gradleDir, "bin/gradle").isFile) return@withContext null
        val version = gradleVersionOfDir(gradleDir) ?: return@withContext null
        val marker = markerOf(context, version)
        if (marker.isFile) return@withContext version
        val distDir = marker.parentFile ?: return@withContext null
        runCatching {
            distDir.mkdirs()
            val unzipped = File(distDir, "gradle-$version")
            if (!unzipped.isDirectory) {
                gradleDir.copyRecursively(unzipped, overwrite = true)
            }
            zipOverride?.takeIf { it.isFile }?.let {
                runCatching { it.copyTo(File(distDir, it.name), overwrite = true) }
            }
            marker.createNewFile()
            onLog("已预置 Gradle $version wrapper 发行包")
        }.onFailure { onLog("wrapper 预置失败（构建时将在线下载）：${it.message}") }
        version
    }

    // ------------------------------------------------------------------
    // cmake / ninja 包装器
    // ------------------------------------------------------------------

    internal fun writeCmakeWrappers(context: Context) {
        val dir = cmakeBinDir(context).apply { mkdirs() }
        File(dir, "cmake").writeText("#!/bin/sh\nexec /usr/bin/cmake \"\$@\"\n")
        File(dir, "ninja")
            .writeText(assetText(context, "ninja-wrap.sh").replace("__MAXJOBS__", NINJA_MAX_JOBS.toString()))
        runCatching { File(dir, "cmake").setExecutable(true, false) }
        runCatching { File(dir, "ninja").setExecutable(true, false) }
    }

    // ------------------------------------------------------------------
    // assets 脚本读取
    // ------------------------------------------------------------------

    /** 读 assets/toolchain/[name]（LF 统一）。 */
    internal fun assetText(context: Context, name: String): String =
        context.assets.open("$ASSET_DIR/$name").use { stream ->
            stream.readBytes().toString(Charsets.UTF_8).replace("\r\n", "\n")
        }

    // ------------------------------------------------------------------
    // guest 执行通道
    // ------------------------------------------------------------------

    /** 在 proot guest 里跑一段 bash 脚本（临时文件落在 files/bin），退出码返回。 */
    suspend fun runGuestScript(
        context: Context,
        script: String,
        onLine: (String) -> Unit = {},
    ): Int = withContext(Dispatchers.IO) {
        val exit = CompletableFuture<Int>()
        val lineBuf = StringBuilder()
        val callback = object : CliCallback {
            override fun onOutput(pid: Int, stream: Int, data: ByteArray?) {
                if (data == null || data.isEmpty()) return
                synchronized(lineBuf) {
                    lineBuf.append(String(data, Charsets.UTF_8))
                    while (true) {
                        val idx = lineBuf.indexOf('\n')
                        if (idx < 0) break
                        val line = lineBuf.substring(0, idx).trim()
                        lineBuf.delete(0, idx + 1)
                        if (line.isNotEmpty()) onLine(line)
                    }
                }
            }

            override fun onExit(pid: Int, code: Int) {
                synchronized(lineBuf) {
                    val tail = lineBuf.toString().trim()
                    if (tail.isNotEmpty()) onLine(tail)
                    lineBuf.setLength(0)
                }
                exit.complete(code)
            }
        }
        val env = arrayOf(
            "PATH=${Proot.pathFor(BuildEnvironment.binDir(context).absolutePath)}",
            "HOME=${context.filesDir.absolutePath}",
            "TMPDIR=${BuildEnvironment.tmpDir(context).absolutePath}",
            "LANG=C.UTF-8",
            "DEBIAN_FRONTEND=noninteractive",
        ) + Proot.envEntries(context)
        val binDir = BuildEnvironment.binDir(context).apply { mkdirs() }
        val scriptFile = File(binDir, ".native_toolchain_${System.nanoTime()}.sh")
        try {
            scriptFile.writeText(script)
            runCatching { scriptFile.setExecutable(true, false) }
            val argv = Proot.wrap(
                context,
                arrayOf("/bin/bash", scriptFile.absolutePath),
                context.filesDir.absolutePath,
            )
            val pid = CliNative.exec(argv, context.filesDir.absolutePath, env, callback)
            if (pid < 0) -1 else runCatching { exit.get() }.getOrDefault(-1)
        } finally {
            runCatching { scriptFile.delete() }
        }
    }
}
