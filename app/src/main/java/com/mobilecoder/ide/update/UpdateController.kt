package com.mobilecoder.ide.update

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.provider.Settings
import com.mobilecoder.ide.BuildConfig
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 更新流程阶段。 */
enum class UpdatePhase {
    /** 未检查。 */
    IDLE,

    /** 检查中。 */
    CHECKING,

    /** 已是最新。 */
    UP_TO_DATE,

    /** 发现新版本（待下载）。 */
    AVAILABLE,

    /** 下载中。 */
    DOWNLOADING,

    /** 已提交系统安装（等待系统确认框 / 安装中）。 */
    INSTALLING,

    /** 出错（[UpdateState.message] 有详情）。 */
    ERROR,
}

/** 更新流程状态。 */
data class UpdateState(
    val phase: UpdatePhase = UpdatePhase.IDLE,
    val latestTag: String? = null,

    /** [UpdatePhase.DOWNLOADING] 进度 0..1；null = 不确定进度。 */
    val progress: Float? = null,
    val message: String? = null,
)

/**
 * 应用内更新执行器（单例，与 GitController / MarketInstaller 同风格）：
 *  1. **检查**：GET GitHub `releases/latest` → [UpdateSource.parseLatest] → 与
 *     [BuildConfig.VERSION_NAME] 逐段比较
 *  2. **下载**：按已安装变体选 APK（见 [UpdateSource.selectApk]），缓存目录流式
 *     下载 + 进度回调，完成后按资产 `digest` 做 SHA-256 校验
 *  3. **安装**：[ApkInstaller.install] 提交 PackageInstaller 会话 —— 系统确认框
 *     覆盖在本应用之上，不跳转任何其他界面；结果由 [UpdateReceiver] 回传
 */
object UpdateController {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(UpdateState())
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    /** 最近一次检查到的发布（下载时用）。 */
    @Volatile
    private var release: UpdateSource.LatestRelease? = null

    private var downloadJob: Job? = null

    // ------------------------------------------------------------------
    // 检查
    // ------------------------------------------------------------------

    /** 检查更新（进行中或下载/安装中时忽略重复点击）。 */
    fun check() {
        val phase = _state.value.phase
        if (phase == UpdatePhase.CHECKING ||
            phase == UpdatePhase.DOWNLOADING ||
            phase == UpdatePhase.INSTALLING
        ) {
            return
        }
        _state.value = UpdateState(phase = UpdatePhase.CHECKING)
        scope.launch {
            runCatching { fetchLatest() }.fold(
                onSuccess = { rel ->
                    release = rel
                    _state.value = if (UpdateSource.isNewer(rel.tag, BuildConfig.VERSION_NAME)) {
                        UpdateState(
                            phase = UpdatePhase.AVAILABLE,
                            latestTag = rel.tag,
                            message = "发现新版本",
                        )
                    } else {
                        UpdateState(
                            phase = UpdatePhase.UP_TO_DATE,
                            latestTag = rel.tag,
                            message = "已是最新版本（当前 ${BuildConfig.VERSION_NAME}）",
                        )
                    }
                },
                onFailure = { e ->
                    _state.value = UpdateState(
                        phase = UpdatePhase.ERROR,
                        message = e.message ?: "检查更新失败",
                    )
                },
            )
        }
    }

    private fun fetchLatest(): UpdateSource.LatestRelease {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(UpdateSource.apiUrl()).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 20_000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "MobileCoder-IDE")
            }
            when (val code = conn.responseCode) {
                200 -> Unit
                403 -> throw IOException("GitHub 接口限流（每小时 60 次/IP），请稍后再试")
                404 -> throw IOException("发布仓库没有 Release")
                else -> throw IOException("检查更新失败：HTTP $code")
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            return UpdateSource.parseLatest(body)
                ?: throw IOException("更新信息解析失败（tag 或 APK 资产缺失）")
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    // ------------------------------------------------------------------
    // 下载 + 安装
    // ------------------------------------------------------------------

    /** 下载并安装（仅 [UpdatePhase.AVAILABLE] 可触发）。 */
    fun startDownload(context: Context) {
        val rel = release ?: return
        if (_state.value.phase != UpdatePhase.AVAILABLE) return
        val app = context.applicationContext

        // 平台强制（仅首次）：未授权「安装未知应用」时先跳系统授权页，避免白下 60MB+ 后卡在安装
        if (!hasInstallPermission(app)) {
            openInstallPermissionPage(app)
            _state.value = UpdateState(
                phase = UpdatePhase.AVAILABLE,
                latestTag = rel.tag,
                message = "需先授予「安装未知应用」权限：请在设置页允许后返回，再点「下载并安装」（仅首次）",
            )
            return
        }
        val debugBuild =
            app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        val apk = UpdateSource.selectApk(rel, debugBuild) ?: run {
            _state.value = UpdateState(
                phase = UpdatePhase.ERROR,
                latestTag = rel.tag,
                message = "Release 里没有 APK 资产",
            )
            return
        }
        _state.value = UpdateState(
            phase = UpdatePhase.DOWNLOADING,
            latestTag = rel.tag,
            progress = 0f,
        )
        downloadJob = scope.launch {
            val dir = File(app.cacheDir, "update").apply { mkdirs() }
            val file = File(dir, "update.apk")
            try {
                runCatching { file.delete() }
                download(apk.url, file) { received, total ->
                    _state.value = UpdateState(
                        phase = UpdatePhase.DOWNLOADING,
                        latestTag = rel.tag,
                        progress = if (total > 0) received.toFloat() / total else null,
                    )
                }
                if (!verifyDigest(file, apk.digest)) {
                    runCatching { file.delete() }
                    _state.value = UpdateState(
                        phase = UpdatePhase.ERROR,
                        latestTag = rel.tag,
                        message = "下载校验失败（SHA-256 不匹配），已删除文件",
                    )
                    return@launch
                }
                _state.value = UpdateState(
                    phase = UpdatePhase.INSTALLING,
                    latestTag = rel.tag,
                    message = "已下载完成，等待系统安装确认",
                )
                ApkInstaller.install(app, file)
            } catch (e: CancellationException) {
                runCatching { file.delete() }
                _state.value = UpdateState(
                    phase = UpdatePhase.AVAILABLE,
                    latestTag = rel.tag,
                    message = "已取消下载",
                )
                throw e
            } catch (e: Exception) {
                runCatching { file.delete() }
                _state.value = UpdateState(
                    phase = UpdatePhase.ERROR,
                    latestTag = rel.tag,
                    message = "下载失败：${e.message ?: e::class.simpleName}",
                )
            }
        }
    }

    /** 取消下载（仅 [UpdatePhase.DOWNLOADING] 有效）。 */
    fun cancelDownload() {
        if (_state.value.phase == UpdatePhase.DOWNLOADING) downloadJob?.cancel()
    }

    /** 是否已获「安装未知应用」授权（与 ApkActions.install 同判据）。 */
    private fun hasInstallPermission(context: Context): Boolean = runCatching {
        context.packageManager.canRequestPackageInstalls()
    }.getOrDefault(false)

    /** 打开本应用的「安装未知应用」授权页（系统页，一次性授权，平台强制）。 */
    private fun openInstallPermissionPage(context: Context) {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    /** 资产带 `digest` 时做 SHA-256 校验；无 digest 视为通过。 */
    private fun verifyDigest(file: File, digest: String?): Boolean {
        val expected = digest?.takeIf { it.startsWith("sha256:", ignoreCase = true) }
            ?.removePrefix("sha256:")
            ?.removePrefix("SHA256:")
            ?: return true
        val actual = file.inputStream().use { UpdateSource.sha256Hex(it) }
        return actual.equals(expected, ignoreCase = true)
    }

    /** 流式下载到 [dest]，按已收字节回调（≥100ms 节流；协程取消即中断）。 */
    private suspend fun download(
        url: String,
        dest: File,
        onBytes: (received: Long, total: Long) -> Unit,
    ) {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 60_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "MobileCoder-IDE")
            }
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            val total = conn.contentLengthLong
            var received = 0L
            var lastEmit = 0L
            conn.inputStream.use { input ->
                dest.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        received += n
                        val now = System.currentTimeMillis()
                        if (now - lastEmit >= 100 || (total > 0 && received >= total)) {
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
    // 安装结果回传（UpdateReceiver 调用）
    // ------------------------------------------------------------------

    /** 系统已把确认框交给我们拉起（[ApkInstaller] 提交后必经此步）。 */
    fun onAwaitingConfirm() {
        if (_state.value.phase == UpdatePhase.INSTALLING) return
        _state.value = _state.value.copy(phase = UpdatePhase.INSTALLING)
    }

    /** 安装结果分类（与 PackageInstaller 状态对应）。 */
    enum class InstallResult { SUCCESS, ABORTED, FAILED }

    /** 安装会话终态：成功 / 用户取消 / 失败。 */
    fun onInstallResult(result: InstallResult, message: String) {
        val tag = _state.value.latestTag
        _state.value = when (result) {
            InstallResult.SUCCESS -> UpdateState(
                phase = UpdatePhase.UP_TO_DATE,
                latestTag = tag,
                message = message,
            )
            InstallResult.ABORTED -> UpdateState(
                phase = UpdatePhase.AVAILABLE,
                latestTag = tag,
                message = message,
            )
            InstallResult.FAILED -> UpdateState(
                phase = UpdatePhase.ERROR,
                latestTag = tag,
                message = message,
            )
        }
    }
}
