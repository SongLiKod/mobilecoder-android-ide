package com.mobilecoder.ide.feature.build

import android.content.Context
import android.os.Build
import com.mobilecoder.ide.core.nativebridge.CliCallback
import com.mobilecoder.ide.core.nativebridge.CliNative
import com.mobilecoder.ide.core.storage.AppStorage
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 在线下载源（「构建环境」页可切换并持久化）。 */
enum class EnvSource(val title: String, val subtitle: String) {
    OFFICIAL("官方源", "Temurin · Gradle · Node.js · Debian 官方镜像 · Google 官方 CDN"),
    MIRROR("国内镜像", "清华 TUNA · npmmirror · 腾讯云 · 阿里云 Debian 镜像"),
}

/** 用户取消了下载。 */
class DownloadCancelled(message: String) : Exception(message)

/**
 * 构建环境在线下载器：下载 → 解压 → 静默落位 `files/sdk/`，完成即可用。
 *
 * - 每类组件给出**官方源 + 国内镜像**两个候选，按所选源排序、失败自动回退；
 *   glibc 运行时内置 **4 个真实 Debian 仓库**（[GLIBC_MIRRORS]，按序回退，在线下载
 *   `.deb` 并组装成运行时，见 `GlibcDebRuntime`），并支持在「构建环境」页点选
 *   **首选源**、手动输入**自定义源**（完整 tar.gz 地址，优先于全部内置源）；
 * - glibc 还有最后一级**APK 内置包**（`assets/glibc/`，见 [bundledGlibcAsset]）：
 *   在线之前零网络直接落位，断网也能完成安装；
 * - 进度按下载/解压字节回报，支持取消（[cancel]）。
 */
object EnvDownloader {

    /** 候选下载源的包格式（glibc 内置源为 Debian 仓库，需按索引组装；其余为单文件包）。 */
    enum class SourceFormat {
        /** 单文件压缩包（`.tar.gz` / `.zip`），[Source.url] 即包地址。 */
        TARBALL,

        /** Debian 仓库基址，[Source.url] 为镜像根（安装时再拼 `dists/.../Packages.gz` 与 `pool/...` 路径）。 */
        DEB,
    }

    /** 一个候选下载源。 */
    data class Source(
        val label: String,
        val url: String,
        val format: SourceFormat = SourceFormat.TARBALL,
    )

    @Volatile
    private var cancelled = false

    /** 请求取消当前下载/安装（网络读与解压循环均检查）。 */
    fun cancel() {
        cancelled = true
    }

    /** 设备 ABI → aarch64 / x64（下载包架构 token）。 */
    fun primaryArch(): String {
        val abi = runCatching { Build.SUPPORTED_ABIS?.firstOrNull() }.getOrNull() ?: "arm64-v8a"
        return if (abi.startsWith("arm64") || abi.startsWith("aarch64")) "aarch64" else "x64"
    }

    /**
     * glibc 运行时包版本（必须与 `tools/glibc-runtime/build.sh` 的 `GLIBC_VERSION` 一致）。
     *
     * 仅用于 **tar.gz 包名**（自定义源 / APK 内置包）；内置 Debian 镜像走索引动态解析，
     * 不依赖此常量。trixie 实测当前 libc6 为 2.41 系（2026-09：`libc6_2.41-12+deb13u4`）。
     */
    private const val GLIBC_VERSION = "2.41"

    /** APK 内置 glibc 运行时包所在 assets 目录（`app/build.gradle.kts` 的 `copyGlibcAssets` 拷入）。 */
    private const val BUNDLED_GLIBC_DIR = "glibc"

    /**
     * 一个内置 glibc 运行时镜像源。
     *
     * @param id 稳定标识（持久化「首选源」用，改 label 不能改 id）
     * @param domestic true = 国内可达（EnvSource.MIRROR 时排前），false = 海外
     * @param format [SourceFormat.DEB] = Debian 仓库（安装时解析索引 + 下载 `.deb` 组装），
     *   [SourceFormat.TARBALL] = 单文件 tar.gz
     */
    data class MirrorSource(
        val id: String,
        val label: String,
        val base: String,
        val domestic: Boolean,
        val format: SourceFormat = SourceFormat.TARBALL,
    )

    /**
     * glibc 运行时**内置默认镜像（4 个，全部为真实 Debian 仓库）**：按顺序逐个回退，全失败才报错。
     *
     * 每个源按 [SourceFormat.DEB] 安装：`dists/<suite>/main/binary-<arch>/Packages.gz` 动态
     * 解析 `libc6` / `libgcc-s1` / `libstdc++6` → 下载 `.deb` → ar+xz 解包 → 扁平化组装 `lib/`
     * （`GlibcDebRuntime`，与 `tools/glibc-runtime/build.sh` 同流程）。
     *
     * 四个地址均为公开、持续可达的 Debian 镜像，**2026-09 实测「索引 + 三个 .deb 均 HTTP 200」**，
     * 不再依赖任何自建占位域名（旧的 cdn.mobilecoder.dev / mobilecoder-glibc / myqcloud 占位
     * 域名均无法解析或 404，已全部移除）。
     *
     * 用户仍可在「构建环境」页点选首选源，或手动输入**自托管完整 tar.gz**（含 DNS/exec 钩子，
     * 见 `tools/glibc-runtime/README.md`）作为自定义源优先尝试。
     */
    val GLIBC_MIRRORS = listOf(
        MirrorSource("official", "Debian 官方", DEBIAN_OFFICIAL_BASE, domestic = false, format = SourceFormat.DEB),
        MirrorSource("tuna", "清华 TUNA", DEBIAN_TUNA_BASE, domestic = true, format = SourceFormat.DEB),
        MirrorSource("aliyun", "阿里云镜像", DEBIAN_ALIYUN_BASE, domestic = true, format = SourceFormat.DEB),
        MirrorSource("tencent", "腾讯云镜像", DEBIAN_TENCENT_BASE, domestic = true, format = SourceFormat.DEB),
    )

    /** Debian 官方镜像（pool 全量；实测索引与 .deb 均 200）。 */
    private const val DEBIAN_OFFICIAL_BASE = "https://deb.debian.org/debian"

    /** 清华 TUNA 的 Debian 镜像（国内）。 */
    private const val DEBIAN_TUNA_BASE = "https://mirrors.tuna.tsinghua.edu.cn/debian"

    /** 阿里云的 Debian 镜像（国内）。 */
    private const val DEBIAN_ALIYUN_BASE = "https://mirrors.aliyun.com/debian"

    /** 腾讯云的 Debian 镜像（国内）。 */
    private const val DEBIAN_TENCENT_BASE = "https://mirrors.cloud.tencent.com/debian"

    /** 候选地址（含 glibc 的首选源 / 自定义源）。
     *
     * 排序规则（glibc）：自定义源 → 首选内置源 → 其余内置源（按 [EnvSource] 的
     * 官方/国内偏好分组，组内保持 [GLIBC_MIRRORS] 顺序），按 URL 去重。
     * 其余组件仍是「官方 + 国内镜像」两候选，只按 [EnvSource] 排序。
     *
     * @param custom 手动输入的自定义源（基址或完整 `.tar.gz/.zip` 地址；null/空忽略）
     * @param preferredId 首选内置源 id（[MirrorSource.id]；不匹配任何内置源时忽略）
     */
    fun candidateSources(
        kind: EnvKind,
        source: EnvSource,
        arch: String,
        custom: String?,
        preferredId: String?,
    ): List<Source> {
        if (kind == EnvKind.GLIBC) {
            return glibcCandidates(source, arch, custom, preferredId)
        }
        return pairCandidateSources(kind, source, arch)
    }

    /**
     * glibc 运行时候选：自定义源 → 首选内置源 → 其余内置源（按 [EnvSource] 的官方/国内
     * 偏好分组，组内保持 [GLIBC_MIRRORS] 顺序），最后按 URL 去重。
     */
    private fun glibcCandidates(
        source: EnvSource,
        arch: String,
        custom: String?,
        preferredId: String?,
    ): List<Source> {
        val stem = "glibc-$GLIBC_VERSION-$arch"
        val list = mutableListOf<Source>()
        custom?.takeIf { it.isNotBlank() }?.let { input ->
            // 自定义源 = 自托管单文件 tar.gz（含钩子），优先于全部内置源
            list += Source("自定义源", resolveCustomUrl(input, stem), SourceFormat.TARBALL)
        }
        val domesticFirst = source == EnvSource.MIRROR
        val preferred = GLIBC_MIRRORS.firstOrNull { it.id == preferredId }
        val rest = GLIBC_MIRRORS
            .filter { it.id != preferredId }
            .sortedBy { it.domestic != domesticFirst } // sortedBy 稳定：组内保原序
        (listOfNotNull(preferred) + rest).forEach { mirror ->
            // 内置源 = Debian 仓库基址（DEB 格式：安装时拼索引 / pool 路径，非单文件地址）
            list += Source(mirror.label, mirror.base, mirror.format)
        }
        return list.distinctBy { it.url }
    }

    /**
     * 自定义源解析：完整包地址（`.tar.gz` / `.zip` 结尾）原样用；
     * 否则当作基址，补上 `/<包文件名>`（与内置源同一拼接规则）。
     */
    fun resolveCustomUrl(input: String, fileStem: String): String {
        val trimmed = input.trim().trimEnd('/')
        return if (trimmed.endsWith(".tar.gz") || trimmed.endsWith(".zip")) {
            trimmed
        } else {
            "$trimmed/$fileStem.tar.gz"
        }
    }

    /**
     * APK 内置 glibc 运行时包名（`assets/glibc/` 下）：精确版本优先，否则在
     * `glibc-*-<arch>.tar.gz` 里取字典序最大者。
     *
     * 产物由 `tools/glibc-runtime/build.sh` 生成、`app/build.gradle.kts` 的
     * `copyGlibcAssets` 任务拷入（缺位时该任务跳过）。
     *
     * @return null = 没有内置包（回退在线镜像 / 用户手动导入）
     */
    fun bundledGlibcAsset(assetNames: Array<String>?, arch: String): String? {
        val names = assetNames ?: return null
        val exact = "glibc-$GLIBC_VERSION-$arch.tar.gz"
        if (exact in names) return exact
        return names
            .filter { it.startsWith("glibc-") && it.endsWith("-$arch.tar.gz") }
            .maxOrNull()
    }

    /**
     * 候选地址：所选源在前、另一源兜底（URL 相同则去重）。
     *
     * glibc 组件会给出**全部内置镜像**（多源自动回退），首选源 / 手动输入的自定义源
     * 走 5 参重载；本方法等价于「不指定首选与自定义」的调用。
     */
    fun candidateSources(kind: EnvKind, source: EnvSource, arch: String): List<Source> =
        candidateSources(kind, source, arch, custom = null, preferredId = null)

    /** 「官方 + 国内镜像」两候选的排序（[EnvKind.GLIBC] 已在入口分流，不走这里）。 */
    private fun pairCandidateSources(kind: EnvKind, source: EnvSource, arch: String): List<Source> {
        val nodeArch = if (arch == "aarch64") "arm64" else "x64"
        val (official, mirror) = when (kind) {
            EnvKind.JDK -> {
                val file = "OpenJDK17U-jdk_${arch}_linux_hotspot_17.0.20.1_1.tar.gz"
                Source("Adoptium 官方", "https://api.adoptium.net/v3/binary/latest/17/ga/linux/$arch/jdk/hotspot/normal/eclipse") to
                    Source("清华 TUNA", "https://mirrors.tuna.tsinghua.edu.cn/Adoptium/17/jdk/$arch/linux/$file")
            }
            EnvKind.GRADLE ->
                Source("Gradle 官方", "https://services.gradle.org/distributions/gradle-8.9-bin.zip") to
                    Source("腾讯云镜像", "https://mirrors.cloud.tencent.com/gradle/gradle-8.9-bin.zip")
            EnvKind.NODE ->
                Source("nodejs.org 官方", "https://nodejs.org/dist/v20.18.0/node-v20.18.0-linux-$nodeArch.tar.gz") to
                    Source("npmmirror", "https://registry.npmmirror.com/-/binary/node/v20.18.0/node-v20.18.0-linux-$nodeArch.tar.gz")
            EnvKind.SDK -> {
                val url = "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
                Source("dl.google.com", url) to Source("dl.google.com", url)
            }
            EnvKind.GLIBC -> error("GLIBC 已在 candidateSources 入口分流到 glibcCandidates")
        }
        val ordered = if (source == EnvSource.MIRROR) listOf(mirror, official) else listOf(official, mirror)
        return ordered.distinctBy { it.url }
    }

    /**
     * 下载并安装 [kind] 组件（Android SDK 请用 [installSdk]），完成后即可用。
     *
     * 顺序（glibc）：**APK 内置包（[bundledGlibcAsset]）→ 自定义源（tar.gz）→ 内置 Debian
     * 镜像（在线组装 .deb）**，逐级兜底；其余组件直接走在线两候选。
     *
     * @param onStage 阶段文案（下载源 / 解压 …）
     * @param onProgress 0..1；-1 表示不确定进度
     * @return 最终落地目录绝对路径
     */
    suspend fun install(
        context: Context,
        kind: EnvKind,
        source: EnvSource,
        onStage: (String) -> Unit = {},
        onProgress: (Float) -> Unit = {},
    ): String = withContext(Dispatchers.IO) {
        cancelled = false
        val cache = File(context.cacheDir, "envdl").apply { mkdirs() }
        // glibc：首选内置源与手动输入的自定义源（其余组件忽略这两项，读失败不阻断下载）
        val custom = runCatching { AppStorage.preferences.glibcCustomSource() }.getOrDefault("")
        val preferredId = runCatching { AppStorage.preferences.glibcPreferredSource() }.getOrDefault("")
        val candidates = candidateSources(kind, source, primaryArch(), custom, preferredId)
        val failures = mutableListOf<String>()

        // 1) glibc：优先 APK 内置包（零网络、零托管；产物见 tools/glibc-runtime/README.md）。
        //    内置包缺位/损坏 → 回退第 2 步在线镜像（自定义源 + 内置多源），
        //    再全失败由用户从「构建环境」页手动导入，三级兜底。
        if (kind == EnvKind.GLIBC) {
            val names = runCatching { context.assets.list(BUNDLED_GLIBC_DIR) }.getOrNull()
            val asset = bundledGlibcAsset(names, primaryArch())
            if (asset != null) {
                try {
                    onStage("使用内置 glibc 运行时（$asset）…")
                    onProgress(-1f)
                    return@withContext extractAndPlace(
                        context,
                        kind,
                        context.assets.open("$BUNDLED_GLIBC_DIR/$asset"),
                        totalBytes = 0L,
                        onProgress = onProgress,
                    )
                } catch (c: DownloadCancelled) {
                    throw c
                } catch (t: Throwable) {
                    failures += "内置包 $asset：${t.message ?: t::class.java.simpleName}"
                }
            }
        }

        // 2) 在线镜像：按序逐个回退（glibc 内置源 = Debian 仓库在线组装，自定义源 = tar.gz 单文件）
        candidates.forEachIndexed { index, cand ->
            if (cancelled) throw DownloadCancelled("已取消下载")
            if (cand.format == SourceFormat.DEB) {
                try {
                    return@withContext installFromDebRepo(
                        context = context,
                        base = cand.url,
                        label = cand.label,
                        arch = primaryArch(),
                        cache = cache,
                        onStage = onStage,
                        onProgress = onProgress,
                    )
                } catch (c: DownloadCancelled) {
                    throw c
                } catch (t: Throwable) {
                    failures += "${cand.label}：${t.message ?: t::class.java.simpleName}"
                }
                return@forEachIndexed
            }
            val suffix = if (cand.url.endsWith(".zip")) ".zip" else ".tar.gz"
            val archive = File(cache, "${kind.name.lowercase()}_$index$suffix")
            try {
                onStage("正在下载 ${kind.title}（${cand.label}）…")
                download(cand.url, archive) { received, total ->
                    onProgress(if (total > 0) (received.toFloat() / total).coerceIn(0f, 0.99f) else -1f)
                }
                if (cancelled) throw DownloadCancelled("已取消下载")
                onStage("正在解压 ${kind.title} …")
                onProgress(-1f)
                return@withContext extractAndPlace(
                    context,
                    kind,
                    archive.inputStream(),
                    archive.length(),
                    onProgress,
                )
            } catch (c: DownloadCancelled) {
                throw c
            } catch (t: Throwable) {
                failures += "${cand.label}：${t.message ?: t::class.java.simpleName}"
            } finally {
                runCatching { archive.delete() }
            }
        }
        throw IllegalStateException("已尝试 ${failures.size} 个来源均失败：${failures.joinToString("；")}")
    }

    /**
     * 从 **Debian 仓库** 在线组装 glibc 运行时（[SourceFormat.DEB] 候选专用）：
     * `Packages.gz` 动态解析包路径 → 下载 `libc6` / `libgcc-s1` / `libstdc++6` 三个 `.deb`
     * → ar+xz 解包 → 扁平化 `lib/` → 落位 `files/sdk/glibc`。
     *
     * 全程与 `tools/glibc-runtime/build.sh` 同流程（**不含 DNS/exec 钩子**：钩子须在 Linux
     * 交叉编译，完整包见 [bundledGlibcAsset] 或自定义源）。任一步失败抛异常，由调用方
     * 记入 failures 并换下一个内置源（四个 Debian 镜像全量同步 pool，其一可达即可）。
     *
     * @param base Debian 镜像根（如 `https://deb.debian.org/debian`）
     * @return 落地目录绝对路径
     */
    private suspend fun installFromDebRepo(
        context: Context,
        base: String,
        label: String,
        arch: String,
        cache: File,
        onStage: (String) -> Unit,
        onProgress: (Float) -> Unit,
    ): String {
        val mirror = base.trimEnd('/')
        val debArch = GlibcDebRuntime.debArch(arch)
        val wanted = GlibcDebRuntime.REQUIRED_PACKAGES
        val n = wanted.size
        val index = File(cache, "packages-$debArch.gz")
        val root = File(cache, "debroot-${System.nanoTime()}")

        try {
            // 1) 软件包索引：动态解析包路径（版本随 Debian 点版本滚动，绝不写死文件名）
            onStage("解析 Debian 软件包索引（$label，${GlibcDebRuntime.DEFAULT_SUITE} $debArch）…")
            onProgress(0f)
            download(
                "$mirror/dists/${GlibcDebRuntime.DEFAULT_SUITE}/main/binary-$debArch/Packages.gz",
                index,
            ) { received, total ->
                onProgress(if (total > 0) (received.toFloat() / total * 0.12f).coerceIn(0f, 0.12f) else -1f)
            }
            if (cancelled) throw DownloadCancelled("已取消下载")
            val paths = index.inputStream().use { GlibcDebRuntime.parseIndex(it, wanted.toSet()) }
            val missing = wanted.filterNot { it in paths }
            if (missing.isNotEmpty()) throw IOException("索引里找不到包：${missing.joinToString("、")}")

            // 2) 逐个下载 .deb 并解包到同一根（build.sh 同款：三包共同铺出完整运行时）
            if (!root.mkdirs() && !root.isDirectory) throw IOException("无法创建临时目录：${root.absolutePath}")
            wanted.forEachIndexed { i, pkg ->
                if (cancelled) throw DownloadCancelled("已取消下载")
                val rel = paths.getValue(pkg)
                val slice = 0.12f + i * (0.66f / n)
                val deb = File(cache, rel.substringAfterLast('/'))
                try {
                    onStage("下载 $pkg（$label）…")
                    download("$mirror/$rel", deb) { received, total ->
                        val frac = if (total > 0) received.toFloat() / total else -1f
                        onProgress(if (frac >= 0) (slice + frac * 0.66f / n * 0.7f).coerceAtMost(0.99f) else -1f)
                    }
                    if (cancelled) throw DownloadCancelled("已取消下载")
                    onStage("解包 $pkg …")
                    ArchiveExtractor.extractDeb(deb.inputStream(), root, deb.length()) { p ->
                        onProgress((slice + 0.66f / n * 0.7f + p * 0.66f / n * 0.3f).coerceAtMost(0.99f))
                    }
                } finally {
                    runCatching { deb.delete() }
                }
            }

            // 3) 扁平化组装 lib/ 并落位（没有 ld-linux* = 包结构变了，视为该源失败）
            onStage("组装 glibc 运行时（lib/）…")
            onProgress(0.95f)
            val sdk = BuildEnvironment.sdkDir(context)
            val tmp = File(sdk, ".dl_glibc_${System.nanoTime()}")
            if (!tmp.mkdirs() && !tmp.isDirectory) throw IOException("无法创建临时目录：${tmp.absolutePath}")
            try {
                val flat = GlibcDebRuntime.flatten(root, File(tmp, "lib"))
                if (flat.loaderName == null || flat.copied == 0) {
                    throw IOException("解包后没有 ld-linux* loader（复制 ${flat.copied} 个）")
                }
                val target = BuildEnvironment.placeExtracted(context, EnvKind.GLIBC, tmp, requireReady = true)
                onProgress(1f)
                return target
            } finally {
                runCatching { tmp.deleteRecursively() }
            }
        } finally {
            runCatching { root.deleteRecursively() }
            runCatching { index.delete() }
        }
    }

    /**
     * 解压 [input] 到临时目录并落位 `files/sdk/<kind>`（在线下载与 APK 内置包共用）。
     *
     * 临时目录无论成败都会清理；进度按 0.05..0.99 换算，落位完成置 1。
     *
     * @param totalBytes 压缩包字节数（内置包按流式读取传 0，进度只到条目粒度）
     */
    private suspend fun extractAndPlace(
        context: Context,
        kind: EnvKind,
        input: InputStream,
        totalBytes: Long,
        onProgress: (Float) -> Unit,
    ): String {
        val sdk = BuildEnvironment.sdkDir(context)
        if (!sdk.isDirectory && !sdk.mkdirs()) throw IOException("无法创建目录：${sdk.absolutePath}")
        val tmp = File(sdk, ".dl_${kind.name.lowercase()}_${System.nanoTime()}")
        if (!tmp.mkdirs() && !tmp.isDirectory) throw IOException("无法创建临时目录：${tmp.absolutePath}")
        try {
            input.use { stream ->
                ArchiveExtractor.extract(stream, tmp, totalBytes) { p ->
                    onProgress((p * 0.9f + 0.05f).coerceIn(0f, 0.99f))
                }
            }
            onProgress(0.99f)
            // SDK 先只并入 cmdline-tools（此时结构尚不完整），由 installSdk 补齐平台组件
            val target = BuildEnvironment.placeExtracted(context, kind, tmp, requireReady = kind != EnvKind.SDK)
            onProgress(1f)
            return target
        } finally {
            runCatching { tmp.deleteRecursively() }
        }
    }

    // ------------------------------------------------------------------
    // 下载
    // ------------------------------------------------------------------

    /** 流式下载到 [dest]，按已收字节回调进度（≥120ms 节流）。 */
    private fun download(url: String, dest: File, onBytes: (Long, Long) -> Unit) {
        var conn: HttpURLConnection? = null
        try {
            val connection = URL(url).openConnection() as HttpURLConnection
            conn = connection
            connection.connectTimeout = 20_000
            connection.readTimeout = 60_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (MobileCoder-IDE)")
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            val total = connection.contentLengthLong
            var received = 0L
            var lastEmit = 0L
            connection.inputStream.use { input ->
                dest.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        if (cancelled) throw DownloadCancelled("已取消下载")
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        received += n
                        val now = System.currentTimeMillis()
                        if (now - lastEmit >= 120 || (total > 0 && received >= total)) {
                            lastEmit = now
                            onBytes(received, total)
                        }
                    }
                }
            }
            if (dest.length() <= 0L) throw IOException("下载内容为空")
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    // ------------------------------------------------------------------
    // Android SDK 在线安装（cmdline-tools → sdkmanager 平台组件）
    // ------------------------------------------------------------------

    /**
     * SDK 在线安装：下载 cmdline-tools 并入 `files/sdk` → 预置 license →
     * sdkmanager 拉取 platform-tools / platforms;android-35 / build-tools;35.0.0。
     * 前置：JDK 已就绪（sdkmanager 依赖 Java 运行时）。
     *
     * @param onProgress 恒为 -1（sdkmanager 无可靠总进度），百分比经 [onStage] 文案呈现
     */
    suspend fun installSdk(
        context: Context,
        source: EnvSource,
        onStage: (String) -> Unit = {},
        onProgress: (Float) -> Unit = {},
    ): String = withContext(Dispatchers.IO) {
        val jdk = BuildEnvironment.status(context).item(EnvKind.JDK)?.takeIf { it.ready }?.path
            ?: throw IllegalStateException("请先在线安装 JDK（sdkmanager 依赖 Java 运行时）")

        // 1) 下载 cmdline-tools 并并入 files/sdk（平台组件尚未就绪，跳过结构校验）
        install(context, EnvKind.SDK, source, onStage, onProgress)
        if (cancelled) throw DownloadCancelled("已取消下载")

        val sdk = BuildEnvironment.sdkDir(context)
        val sdkmanager = findSdkManager(sdk)
            ?: throw IllegalStateException("未找到 sdkmanager（cmdline-tools 结构异常）")
        writeLicenses(File(sdk, "licenses"))

        // 2) sdkmanager 在线安装平台组件；向 stdin 持续写 y 兜底交互确认
        onStage("正在安装 platform-tools / android-35 / build-tools …")
        onProgress(-1f)
        val pkgs = "\"platform-tools\" \"platforms;android-35\" \"build-tools;35.0.0\""
        val cmd = "chmod +x '${sdkmanager.absolutePath}' 2>/dev/null; " +
            "'${sdkmanager.absolutePath}' --sdk_root='${sdk.absolutePath}' $pkgs"
        val env = arrayOf(
            "PATH=${BuildEnvironment.binDir(context).absolutePath}:/system/bin:/system/xbin:/vendor/bin",
            "HOME=${context.filesDir.absolutePath}",
            "TMPDIR=${BuildEnvironment.tmpDir(context).absolutePath}",
            "JAVA_HOME=$jdk",
            "ANDROID_HOME=${sdk.absolutePath}",
            "ANDROID_SDK_ROOT=${sdk.absolutePath}",
            "LANG=C.UTF-8",
            "SHELL=/system/bin/sh",
        ) + BuildEnvironment.glibcEnv(context)
        val exit = CompletableDeferred<Int>()
        val tail = StringBuilder()
        val percent = Regex("""(\d+)%""")
        val pid = CliNative.exec(arrayOf("sh", "-c", cmd), sdk.absolutePath, env, object : CliCallback {
            override fun onOutput(pid: Int, stream: Int, data: ByteArray?) {
                if (data == null) return
                val text = String(data, Charsets.UTF_8)
                synchronized(tail) {
                    tail.append(text)
                    if (tail.length > 4000) tail.delete(0, tail.length - 4000)
                }
                percent.find(text)?.let { onStage("正在安装 SDK 组件（${it.groupValues[1]}%）…") }
                text.lineSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && !it.startsWith("[") && !it.contains('%') }
                    .lastOrNull()
                    ?.let { onStage("正在安装 SDK 组件：$it") }
            }

            override fun onExit(pid: Int, code: Int) {
                exit.complete(code)
            }
        })
        if (pid < 0) throw IllegalStateException("无法启动 sdkmanager：并发进程数已达上限")
        val feeder = launch {
            while (isActive && !exit.isCompleted) {
                delay(400)
                if (cancelled) {
                    // 「取消」按钮 → 结束 sdkmanager，否则 await 会一直等到安装完成
                    runCatching { CliNative.killProcess(pid, 15) }
                    break
                }
                runCatching { CliNative.writeStdin(pid, "y\n".toByteArray()) }
            }
        }
        val code = exit.await()
        feeder.cancel()
        if (cancelled) throw DownloadCancelled("已取消下载")
        if (code != 0) {
            val last = synchronized(tail) {
                tail.toString().lines().map { it.trim() }.filter { it.isNotEmpty() }.lastOrNull().orEmpty()
            }
            throw IllegalStateException("sdkmanager 失败（退出码 $code）：$last")
        }
        if (!BuildEnvironment.sdkLooksReady(sdk)) {
            throw IllegalStateException("sdkmanager 已结束，但 SDK 仍缺少 platforms/build-tools")
        }
        onStage("Android SDK 安装完成")
        onProgress(1f)
        BuildEnvironment.refresh(context)
        sdk.absolutePath
    }

    /** 定位 sdkmanager（cmdline-tools 的不同解压结构均兼容）。 */
    private fun findSdkManager(sdk: File): File? {
        listOf(
            File(sdk, "cmdline-tools/latest/bin/sdkmanager"),
            File(sdk, "cmdline-tools/bin/sdkmanager"),
            File(sdk, "tools/bin/sdkmanager"),
        ).firstOrNull { it.exists() }?.let { return it }
        fun walk(dir: File, depth: Int): File? {
            if (depth < 0 || !dir.isDirectory) return null
            dir.listFiles()?.forEach { child ->
                if (child.isDirectory) {
                    walk(child, depth - 1)?.let { return it }
                } else if (child.name == "sdkmanager") {
                    return child
                }
            }
            return null
        }
        return walk(sdk, 5)
    }

    /** 预置 Google 官方 license 哈希，使 sdkmanager 免交互。 */
    private fun writeLicenses(dir: File) {
        val licenses = mapOf(
            "android-sdk-license" to listOf(
                "24333f8a63b6825ea9c5514f83c2829b004d1fee",
                "d56f5187479451eabf01fb78af6dfcb131a6481e",
                "8933bad161af4178b1185d1a37fbf41ea5269c55",
            ),
            "android-sdk-preview-license" to listOf("84831b9409646a918e30573bab4c9c91346d8abd"),
            "android-sdk-arm-dbt-license" to listOf("859f317696f67ef3d7f30a50a5560e7834b43903"),
            "google-gdk-license" to listOf("33b6a2b64607f11b759f320ef9dff4ae5c47d97a"),
            "android-googletv-license" to listOf("601085b94cd77f0b54ff86406957099ebe79c4d6"),
        )
        runCatching {
            dir.mkdirs()
            licenses.forEach { (name, hashes) ->
                val file = File(dir, name)
                val old = if (file.exists()) file.readText() else ""
                val missing = hashes.filter { !old.contains(it) }
                if (missing.isNotEmpty()) {
                    file.appendText(missing.joinToString("\n", prefix = if (old.isBlank()) "" else "\n") + "\n")
                }
            }
        }
    }
}
