package com.mobilecoder.ide.feature.build

import android.content.Context
import android.os.Build
import com.mobilecoder.ide.core.nativebridge.CliCallback
import com.mobilecoder.ide.core.nativebridge.CliNative
import java.io.File
import java.io.IOException
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
    OFFICIAL("官方源", "Temurin · Gradle · Node.js · glibc 运行时 · Google 官方 CDN"),
    MIRROR("国内镜像", "清华 TUNA · npmmirror · 腾讯云 · MobileCoder 国内镜像"),
}

/** 用户取消了下载。 */
class DownloadCancelled(message: String) : Exception(message)

/**
 * 构建环境在线下载器：下载 → 解压 → 静默落位 `files/sdk/`，完成即可用。
 *
 * - 每类组件给出**官方源 + 国内镜像**两个候选，按所选源排序、失败自动回退；
 * - 进度按下载/解压字节回报，支持取消（[cancel]）。
 */
object EnvDownloader {

    /** 一个候选下载源。 */
    data class Source(val label: String, val url: String)

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
     * glibc 运行时包版本（必须与 `tools/glibc-runtime/build.sh` 打出的包名一致）。
     */
    private const val GLIBC_VERSION = "2.39"

    /**
     * glibc 运行时**自托管**下载基址（官方源）。
     *
     * nodejs.org / Adoptium / gradle 官方包都依赖 glibc，但三家都不提供 Android 可用的
     * 运行时，所以这一份由仓库内 `tools/glibc-runtime/build.sh` 从 Debian `libc6` 解包、
     * 交叉编译钩子后打成 `glibc-<版本>-<arch>.tar.gz` 自行上传。
     *
     * **发布前必须把域名换成真实地址**，上传路径见 `tools/glibc-runtime/README.md`；
     * 未发布时下载会失败，`ToolInstaller.ensureGlibc` 会提示改从「构建环境」页导入。
     */
    private const val GLIBC_OFFICIAL_BASE = "https://cdn.mobilecoder.dev/glibc"

    /** glibc 运行时国内镜像基址（与 [GLIBC_OFFICIAL_BASE] 同一份文件）。 */
    private const val GLIBC_MIRROR_BASE = "https://cdn-mobilecoder.cn-shanghai.myqcloud.com/glibc"

    /** 候选地址：所选源在前、另一源兜底（URL 相同则去重）。 */
    fun candidateSources(kind: EnvKind, source: EnvSource, arch: String): List<Source> {
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
            EnvKind.GLIBC -> {
                val file = "glibc-$GLIBC_VERSION-$arch.tar.gz"
                Source("MobileCoder 官方", "$GLIBC_OFFICIAL_BASE/$file") to
                    Source("国内镜像", "$GLIBC_MIRROR_BASE/$file")
            }
        }
        val ordered = if (source == EnvSource.MIRROR) listOf(mirror, official) else listOf(official, mirror)
        return ordered.distinctBy { it.url }
    }

    /**
     * 下载并安装 [kind] 组件（Android SDK 请用 [installSdk]），完成后即可用。
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
        val sdk = BuildEnvironment.sdkDir(context)
        if (!sdk.isDirectory && !sdk.mkdirs()) throw IOException("无法创建目录：${sdk.absolutePath}")
        val cache = File(context.cacheDir, "envdl").apply { mkdirs() }
        val candidates = candidateSources(kind, source, primaryArch())
        val failures = mutableListOf<String>()
        candidates.forEachIndexed { index, cand ->
            if (cancelled) throw DownloadCancelled("已取消下载")
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
                val tmp = File(sdk, ".dl_${kind.name.lowercase()}_${System.nanoTime()}")
                if (!tmp.mkdirs() && !tmp.isDirectory) throw IOException("无法创建临时目录：${tmp.absolutePath}")
                try {
                    archive.inputStream().use { input ->
                        ArchiveExtractor.extract(input, tmp, archive.length()) { p ->
                            onProgress((p * 0.9f + 0.05f).coerceIn(0f, 0.99f))
                        }
                    }
                    onProgress(0.99f)
                    // SDK 先只并入 cmdline-tools（此时结构尚不完整），由 installSdk 补齐平台组件
                    val target = BuildEnvironment.placeExtracted(context, kind, tmp, requireReady = kind != EnvKind.SDK)
                    onProgress(1f)
                    return@withContext target
                } finally {
                    runCatching { tmp.deleteRecursively() }
                }
            } catch (c: DownloadCancelled) {
                throw c
            } catch (t: Throwable) {
                failures += "${cand.label}：${t.message ?: t::class.java.simpleName}"
            } finally {
                runCatching { archive.delete() }
            }
        }
        throw IllegalStateException("下载失败（已尝试 ${candidates.size} 个源）：${failures.joinToString("；")}")
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
