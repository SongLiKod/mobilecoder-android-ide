package com.mobilecoder.ide.feature.build

import android.content.Context
import android.net.Uri
import com.mobilecoder.ide.core.common.linux.Proot
import com.mobilecoder.ide.core.storage.AppStorage
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * **Linux 环境**（Ubuntu 24.04 rootfs + proot）的安装与首启配置。
 *
 * 产物布局（与 [Proot] 的执行侧约定一一对应）：
 * ```
 * files/linux/
 *   .rootfs            安装标记（`ubuntu-24.04.5-<arch>`）
 *   rootfs/            Ubuntu Base 根文件系统（proot -r）
 *   bin/proot          Termux proot 可执行文件
 *   bin/loader[32]     proot 自带 loader（PROOT_LOADER / PROOT_LOADER_32）
 *   lib/               libtalloc.so.2 / libandroid-shmem.so（LD_LIBRARY_PATH）
 * ```
 *
 * 安装管线（三段，逐级进度回报、可取消）：
 *  1. **rootfs**：按候选源下载 `ubuntu-base-<ver>-base-<arch>.tar.gz` → 解压落位
 *     （结构校验：必须含 `bin/bash`）；
 *  2. **proot 三件套**：固定下载 Termux 官方仓库的 `.deb`（proot / libtalloc /
 *     libandroid-shmem，合计约 140KB），[ArchiveExtractor.extractDeb] 解包后
 *     按 `usr/bin|usr/libexec|usr/lib` 归位；
 *  3. **首启配置**：写 `etc/resolv.conf`（DNS）、`etc/apt/sources.list`（按所选源
 *     写 Ubuntu 官方或清华 TUNA 镜像）、`etc/hosts`、`etc/hostname`，最后落 marker。
 *
 * 重复安装幂等：marker 匹配且文件齐全直接返回；rootfs 已解出但 proot 缺失（上次
 * 中断）会跳过 30MB 下载只补第 2/3 步；marker 版本不匹配则整体重装。
 */
object RootfsManager {

    private const val TAG = "RootfsManager"

    /** Ubuntu rootfs 版本（包名用；marker 的 [Proot.ROOTFS_ID] 与之保持一致）。 */
    val rootfsVersion: String get() = Proot.ROOTFS_ID.removePrefix("ubuntu-")

    /** 一个内置 rootfs 镜像（包路径结构完全相同，仅域名不同）。 */
    data class Mirror(
        /** 稳定标识（「首选源」持久化用）。 */
        val id: String,
        val label: String,
        /** 站点基址（拼 `/releases/24.04/release/<包名>`）。 */
        val base: String,
        /** true = 国内可达（EnvSource.MIRROR 时排前）。 */
        val domestic: Boolean,
    )

    /**
     * 内置 rootfs 镜像（2 个，**2026-09 实测包均 HTTP 200**，约 30MB）：
     * cdimage.ubuntu.com（官方）与清华 TUNA（`ubuntu-cdimage/ubuntu-base` 镜像目录，
     * 国内）——两者包路径结构完全相同，仅域名不同。
     */
    val ROOTFS_MIRRORS = listOf(
        Mirror("official", "Ubuntu 官方", "https://cdimage.ubuntu.com/ubuntu-base", domestic = false),
        Mirror(
            "tuna", "清华 TUNA",
            "https://mirrors.tuna.tsinghua.edu.cn/ubuntu-cdimage/ubuntu-base", domestic = true,
        ),
    )

    /** ABI token → Ubuntu 架构 token（aarch64→arm64 / x64→amd64）。 */
    fun ubuntuArch(arch: String): String = if (arch == "aarch64") "arm64" else "amd64"

    /** rootfs 包文件名（内置源拼接与自定义源补全共用）。 */
    fun rootfsFileName(arch: String): String =
        "ubuntu-base-$rootfsVersion-base-${ubuntuArch(arch)}.tar.gz"

    /**
     * Termux 官方仓库的 proot 三件套（固定地址，随 [EnvSource] 不变，
     * **2026-09 实测三个 .deb 均 HTTP 200**）。
     */
    fun prootDebUrls(arch: String): List<String> {
        val tuxArch = if (arch == "aarch64") "aarch64" else "x86_64"
        val base = "https://packages.termux.dev/apt/termux-main/pool/main"
        return listOf(
            "$base/p/proot/proot_5.1.107.95_$tuxArch.deb",
            "$base/libt/libtalloc/libtalloc_2.4.3_$tuxArch.deb",
            "$base/liba/libandroid-shmem/libandroid-shmem_0.7_$tuxArch.deb",
        )
    }

    // ------------------------------------------------------------------
    // 安装
    // ------------------------------------------------------------------

    /**
     * 完整安装（EnvDownloader.install(EnvKind.LINUX) 的落地实现）。
     *
     * @param onStage  阶段文案（下载源 / 解压 …）
     * @param onProgress 0..1；-1 表示不确定进度
     * @return rootfs 目录绝对路径
     */
    suspend fun install(
        context: Context,
        source: EnvSource,
        onStage: (String) -> Unit = {},
        onProgress: (Float) -> Unit = {},
    ): String = withContext(Dispatchers.IO) {
        val linux = Proot.linuxDir(context)
        val marker = Proot.markerFile(context)
        val expected = markerContent(EnvDownloader.primaryArch())
        val markerText = runCatching { marker.takeIf { it.isFile }?.readText() }.getOrNull()
        if (markerText != null && markerText.trim() != expected) {
            // 版本变更：整体重装，避免新旧 rootfs 混装
            runCatching { linux.deleteRecursively() }
        }
        if (markerText != null && markerText.trim() == expected && Proot.isReady(context)) {
            return@withContext Proot.rootfsDir(context).absolutePath
        }
        if (!linux.isDirectory && !linux.mkdirs()) {
            throw IOException("无法创建目录：${linux.absolutePath}")
        }

        // 1) rootfs（已解出且结构完整则跳过，续装不再下 30MB）
        val rootfsDone = rootfsComplete(Proot.rootfsDir(context))
        val debsBase: Float
        if (rootfsDone) {
            debsBase = 0.35f
        } else {
            downloadAndPlaceRootfs(context, source, onStage) { p -> onProgress(p * 0.80f) }
            debsBase = 0.80f
        }
        EnvDownloader.throwIfCancelled()

        // 2) proot 三件套
        if (!prootInstalled(context)) {
            installProotDebs(context, onStage) { p ->
                onProgress(debsBase + p * (0.96f - debsBase))
            }
        }
        EnvDownloader.throwIfCancelled()

        // 3) 首启配置 + marker
        onStage("配置 Linux 环境（DNS / apt 源 / 主机名）…")
        setupRootfs(
            rootfs = Proot.rootfsDir(context),
            domestic = source == EnvSource.MIRROR,
            ubuntuArch = ubuntuArch(EnvDownloader.primaryArch()),
        )
        marker.writeText(expected)
        onStage("Linux 环境安装完成")
        onProgress(1f)
        Proot.rootfsDir(context).absolutePath
    }

    /**
     * 从用户选择的本地 `ubuntu-base-*.tar.gz`（SAF 导入）完成安装：
     * 解压落位 rootfs → 补装 proot 三件套（仍需网络，地址固定）→ 首启配置。
     */
    suspend fun installFromArchive(
        context: Context,
        uri: Uri,
        onStage: (String) -> Unit = {},
        onProgress: (Float) -> Unit = {},
    ): String = withContext(Dispatchers.IO) {
        onStage("正在解压 Ubuntu rootfs …")
        val linux = Proot.linuxDir(context)
        if (!linux.isDirectory && !linux.mkdirs()) {
            throw IOException("无法创建目录：${linux.absolutePath}")
        }
        val total = runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L
        }.getOrDefault(0L)
        val tmp = File(linux, ".dl_rootfs_${System.nanoTime()}")
        if (!tmp.mkdirs() && !tmp.isDirectory) throw IOException("无法创建临时目录：${tmp.absolutePath}")
        try {
            val raw = context.contentResolver.openInputStream(uri)
                ?: throw IllegalStateException("无法读取所选文件，请重新选择")
            raw.use { input ->
                ArchiveExtractor.extract(input, tmp, total.coerceAtLeast(1L)) { p -> onProgress(p * 0.6f) }
            }
            placeRootfs(tmp, Proot.rootfsDir(context))
        } finally {
            runCatching { tmp.deleteRecursively() }
        }
        EnvDownloader.throwIfCancelled()

        if (!prootInstalled(context)) {
            installProotDebs(context, onStage) { p -> onProgress(0.6f + p * 0.35f) }
        }
        EnvDownloader.throwIfCancelled()

        val domestic = runCatching {
            AppStorage.preferences.envDownloadSource() == "mirror"
        }.getOrDefault(false)
        onStage("配置 Linux 环境（DNS / apt 源 / 主机名）…")
        setupRootfs(
            rootfs = Proot.rootfsDir(context),
            domestic = domestic,
            ubuntuArch = ubuntuArch(EnvDownloader.primaryArch()),
        )
        Proot.markerFile(context).writeText(markerContent(EnvDownloader.primaryArch()))
        onStage("Linux 环境安装完成")
        onProgress(1f)
        Proot.rootfsDir(context).absolutePath
    }

    // ------------------------------------------------------------------
    // rootfs 下载与落位
    // ------------------------------------------------------------------

    /** 下载 rootfs tar.gz 并落位 `files/linux/rootfs`（进度 0..0.8）。 */
    private suspend fun downloadAndPlaceRootfs(
        context: Context,
        source: EnvSource,
        onStage: (String) -> Unit,
        onProgress: (Float) -> Unit,
    ) {
        val arch = EnvDownloader.primaryArch()
        val custom = runCatching { AppStorage.preferences.linuxCustomSource() }.getOrDefault("")
        val preferred = runCatching { AppStorage.preferences.linuxPreferredSource() }.getOrDefault("")
        val candidates = EnvDownloader.candidateSources(
            kind = EnvKind.LINUX,
            source = source,
            arch = arch,
            custom = custom,
            preferredId = preferred,
        )
        val cache = File(context.cacheDir, "envdl").apply { mkdirs() }
        val failures = mutableListOf<String>()
        candidates.forEachIndexed { index, cand ->
            EnvDownloader.throwIfCancelled()
            val archive = File(cache, "rootfs_$index.tar.gz")
            try {
                onStage("正在下载 Ubuntu rootfs（${cand.label}）…")
                EnvDownloader.download(cand.url, archive) { received, total ->
                    onProgress(
                        if (total > 0) (received.toFloat() / total * 0.60f).coerceAtMost(0.60f) else -1f,
                    )
                }
                EnvDownloader.throwIfCancelled()
                onStage("正在解压 Ubuntu rootfs …")
                val tmp = File(Proot.linuxDir(context), ".dl_rootfs_${System.nanoTime()}")
                if (!tmp.mkdirs() && !tmp.isDirectory) {
                    throw IOException("无法创建临时目录：${tmp.absolutePath}")
                }
                try {
                    archive.inputStream().use { ins ->
                        ArchiveExtractor.extract(ins, tmp, archive.length()) { p ->
                            onProgress(0.60f + p * 0.18f)
                        }
                    }
                    placeRootfs(tmp, Proot.rootfsDir(context))
                } finally {
                    runCatching { tmp.deleteRecursively() }
                }
                onProgress(0.80f)
                return
            } catch (c: DownloadCancelled) {
                throw c
            } catch (t: Throwable) {
                failures += "${cand.label}：${t.message ?: t::class.java.simpleName}"
            } finally {
                runCatching { archive.delete() }
            }
        }
        throw IOException("已尝试 ${failures.size} 个 rootfs 来源均失败：${failures.joinToString("；")}")
    }

    /** rootfs 是否已解出且结构完整（`bin/bash` + `usr/bin/apt`）。 */
    fun rootfsComplete(rootfs: File): Boolean =
        File(rootfs, "bin/bash").exists() && File(rootfs, "usr/bin/apt").exists()

    /**
     * rootfs 落位：解压产物里找到含 `bin/bash` 的根（ubuntu-base 条目就在顶层，
     * `bin` 是指向 `usr/bin` 的相对软链，`File.exists` 会跟随解析）→ 整体移到 [dest]。
     */
    internal fun placeRootfs(tmp: File, dest: File) {
        val root = findContaining(tmp, "bin/bash", 3)
            ?: throw IOException(
                "压缩包结构不匹配：未找到 bin/bash，请确认选择的是 ubuntu-base-*.tar.gz",
            )
        if (root.canonicalPath == dest.canonicalPath) return
        if (dest.exists()) dest.deleteRecursively()
        dest.parentFile?.mkdirs()
        if (!root.renameTo(dest)) {
            root.copyRecursively(dest, overwrite = true)
        }
    }

    private fun findContaining(dir: File, marker: String, maxDepth: Int): File? {
        if (maxDepth < 0) return null
        if (File(dir, marker).exists()) return dir
        dir.listFiles()?.forEach { child ->
            if (child.isDirectory) findContaining(child, marker, maxDepth - 1)?.let { return it }
        }
        return null
    }

    // ------------------------------------------------------------------
    // proot 三件套
    // ------------------------------------------------------------------

    /** proot 可执行文件 + loader + 两个运行库是否齐全。 */
    fun prootInstalled(context: Context): Boolean {
        val linux = Proot.linuxDir(context)
        val lib = File(linux, "lib")
        return File(linux, "bin/proot").isFile &&
            File(linux, "bin/loader").isFile &&
            File(linux, "lib/libtalloc.so.2").exists() &&
            File(lib, "libandroid-shmem.so").exists()
    }

    /** 逐个下载 `.deb` 并解包归位（进度 0..1，由调用方换算到总区间）。 */
    private suspend fun installProotDebs(
        context: Context,
        onStage: (String) -> Unit,
        onProgress: (Float) -> Unit,
    ) {
        val arch = EnvDownloader.primaryArch()
        val urls = prootDebUrls(arch)
        val cache = File(context.cacheDir, "envdl").apply { mkdirs() }
        val linux = Proot.linuxDir(context)
        urls.forEachIndexed { i, url ->
            EnvDownloader.throwIfCancelled()
            val name = url.substringAfterLast('/')
            val deb = File(cache, name)
            val staging = File(cache, "deb_$i-${System.nanoTime()}")
            val slice = i.toFloat() / urls.size
            val share = 1f / urls.size
            try {
                onStage("正在下载 proot 组件（$name）…")
                EnvDownloader.download(url, deb) { received, total ->
                    onProgress(
                        if (total > 0) {
                            (slice + received.toFloat() / total * share * 0.7f).coerceAtMost(1f)
                        } else -1f,
                    )
                }
                EnvDownloader.throwIfCancelled()
                onStage("正在安装 proot 组件（$name）…")
                if (!staging.mkdirs() && !staging.isDirectory) {
                    throw IOException("无法创建临时目录：${staging.absolutePath}")
                }
                try {
                    ArchiveExtractor.extractDeb(deb.inputStream(), staging, deb.length())
                    val copied = placeProotFiles(staging, linux)
                    if (copied == 0) throw IOException("解包后没有找到 proot 文件（$name 结构异常）")
                } finally {
                    runCatching { staging.deleteRecursively() }
                }
                onProgress(((slice + share).coerceAtMost(1f)))
            } catch (c: DownloadCancelled) {
                throw c
            } catch (t: Throwable) {
                throw IOException("安装 proot 组件失败（$name）：${t.message}", t)
            } finally {
                runCatching { deb.delete() }
            }
        }
        if (!prootInstalled(context)) {
            throw IOException("proot 组件安装不完整（缺 bin/proot 或 loader 或运行库）")
        }
    }

    /**
     * 把 Termux deb 的解包产物归位到 `files/linux`：
     * `usr/bin/proot` → `bin/proot`，`usr/libexec/proot/loader[32]` → `bin/loader[32]`，
     * `usr/lib` 下的库 → `lib/`（只取库文件，跳过 include / share / pkgconfig）。
     * 软链（`libtalloc.so.2 → libtalloc.so.2.4.3`）按目标内容复制成普通文件——
     * soname 文件名保留，动态链接器照常工作。
     *
     * @return 归位的文件数（0 = 包结构不对）
     */
    internal fun placeProotFiles(staging: File, linuxDir: File): Int {
        val bin = File(linuxDir, "bin")
        val lib = File(linuxDir, "lib")
        bin.mkdirs()
        lib.mkdirs()
        var copied = 0
        staging.walkTopDown().forEach { src ->
            if (!src.isFile) return@forEach // 目录 / 悬空软链跳过
            val path = src.absolutePath.replace('\\', '/')
            val dest = when {
                path.endsWith("/usr/bin/proot") -> File(bin, "proot")
                path.endsWith("/usr/libexec/proot/loader") -> File(bin, "loader")
                path.endsWith("/usr/libexec/proot/loader32") -> File(bin, "loader32")
                path.contains("/usr/lib/") -> File(lib, src.name)
                else -> null
            } ?: return@forEach
            runCatching {
                if (dest.exists()) dest.delete()
                src.copyTo(dest, overwrite = true)
                copied++
                if (dest.parentFile?.name == "bin") dest.setExecutable(true, false)
            }
        }
        // 软链拷贝失败（如宿主不支持）时目标可能仍是链接本身，再强制一次执行位
        runCatching { File(bin, "proot").setExecutable(true, false) }
        runCatching { File(bin, "loader").setExecutable(true, false) }
        runCatching { File(bin, "loader32").setExecutable(true, false) }
        return copied
    }

    // ------------------------------------------------------------------
    // 首启配置
    // ------------------------------------------------------------------

    /**
     * rootfs 首启配置（幂等，全部是「删旧写新」）：
     *
     * - `etc/resolv.conf`：DNS（写成普通文件，宿主 `/etc` **不会**被 bind 覆盖）；
     * - `etc/apt/sources.list`：按 [domestic] 写 Ubuntu 官方或清华 TUNA 源
     *   （arm64 用 `ports.ubuntu.com` / `ubuntu-ports`，amd64 用 `archive` / `ubuntu`），
     *   并删除 24.04 的 deb822 `sources.list.d/ubuntu.sources` 避免双源重复；
     * - `etc/hosts` / `etc/hostname`。
     *
     * 纯文件操作（JVM 单测可直接调用）。
     *
     * @param rootfs   rootfs 根目录
     * @param domestic true = 用清华 TUNA 镜像
     * @param ubuntuArch `arm64` / `amd64`
     */
    fun setupRootfs(rootfs: File, domestic: Boolean, ubuntuArch: String) {
        val etc = File(rootfs, "etc")
        if (!etc.isDirectory && !etc.mkdirs()) {
            throw IOException("无法创建目录：${etc.absolutePath}")
        }

        // DNS：可能是镜像里的软链（如 ../run/systemd/resolve/stub.conf）→ 删除重写
        forceReplace(File(etc, "resolv.conf")).writeText(
            "nameserver 223.5.5.5\n" + // AliDNS
                "nameserver 119.29.29.29\n" + // DNSPod
                "nameserver 8.8.8.8\n",
        )

        forceReplace(File(etc, "hosts")).writeText(
            "127.0.0.1\tlocalhost\n" +
                "127.0.1.1\tmobilecoder\n" +
                "::1\tlocalhost ip6-localhost ip6-loopback\n",
        )
        forceReplace(File(etc, "hostname")).writeText("mobilecoder\n")

        // apt 源（见 KDoc）
        val ports = ubuntuArch == "arm64"
        val archive: String
        val security: String
        when {
            domestic && ports -> {
                archive = "https://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports"
                security = archive
            }
            domestic -> {
                archive = "https://mirrors.tuna.tsinghua.edu.cn/ubuntu"
                security = archive
            }
            ports -> {
                archive = "http://ports.ubuntu.com/ubuntu-ports"
                security = archive
            }
            else -> {
                archive = "http://archive.ubuntu.com/ubuntu"
                security = "http://security.ubuntu.com/ubuntu"
            }
        }
        val suites = listOf("noble", "noble-updates", "noble-security")
        val components = "main restricted universe multiverse"
        val sources = buildString {
            suites.forEachIndexed { i, suite ->
                val base = if (suite == "noble-security") security else archive
                append("deb $base $suite $components\n")
                if (i == 0) append("# deb-src $archive $suite $components\n")
            }
        }
        runCatching {
            File(etc, "apt/sources.list.d/ubuntu.sources").delete()
            File(etc, "apt/sources.list.d").listFiles()
                ?.filter { it.name.endsWith(".sources") }
                ?.forEach { it.delete() }
        }
        forceReplace(File(etc, "apt/sources.list")).also { it.parentFile?.mkdirs() }.writeText(sources)
    }

    /** 目标是软链或已存在则先删除，返回可直接写入的普通文件句柄。 */
    private fun forceReplace(file: File): File {
        runCatching {
            if (Files.isSymbolicLink(file.toPath())) {
                Files.delete(file.toPath())
            } else if (file.exists()) {
                file.delete()
            }
        }
        file.parentFile?.mkdirs()
        return file
    }

    /** marker 文件内容（[Proot.ROOTFS_ID] + 架构，安装与就绪判定共用）。 */
    fun markerContent(arch: String): String = "${Proot.ROOTFS_ID}-${ubuntuArch(arch)}"
}
