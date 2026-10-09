package com.mobilecoder.ide.feature.build

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import com.mobilecoder.ide.core.common.linux.Proot
import com.mobilecoder.ide.core.nativebridge.CliCallback
import com.mobilecoder.ide.core.nativebridge.CliNative
import com.mobilecoder.ide.core.nativebridge.NativeRuntime
import com.mobilecoder.ide.core.storage.AppStorage
import com.mobilecoder.ide.core.storage.HistoryStore
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 构建执行器（PRD 2.7 编译打包 / TECH 4.6 编译模块技术方案）—— **多项目类型**通用。
 *
 * 职责：
 *  1. 状态机 [state]：Idle / Preparing / Running / Success(产物) / Failed(错误)；
 *  2. 实时日志 [logs]（按行切分、`\r` 进度刷新处理、级别着色）与错误定位 [errors]
 *     （Kotlin / Java / Gradle / JS·TS·Vue）；
 *  3. 按 [detectProject] 识别的类型分派构建路径：
 *     Gradle（安卓 APK / 纯 JVM JAR）、npm（dist → zip）、进程内源码打包；
 *  4. 内存监控 [memory]：超限或系统已用 >90% 自动 kill，避免 OOM 崩溃；
 *  5. 前台服务保活 + 完成通知（[BuildForegroundService]）。
 *
 * **单入口**：构建统一走 [start]（进程级单入口），同一时刻只会有一个任务。
 *
 * 线程模型：JNI 回调发生在子进程读线程，只更新 StateFlow（快照不可变），
 * Compose 在主线程收集 —— UI 更新天然回到主线程。
 */
object BuildRunner {

    private const val TAG = "BuildRunner"
    private const val MAX_LOG_LINES = 5000
    private const val MAX_ERRORS = 200
    private const val HISTORY_LIMIT = 10
    private const val MB = 1024L * 1024L
    private const val HISTORY_FILE = "build_history.json"

    // ------------------------------------------------------------------
    // 对外状态
    // ------------------------------------------------------------------

    /** 构建状态机。 */
    private val _state = MutableStateFlow<BuildState>(BuildState.Idle)
    val state: StateFlow<BuildState> = _state.asStateFlow()

    /** 实时日志（行 + 级别，级别用于随主题着色）。 */
    private val _logs = MutableStateFlow<List<BuildLogLine>>(emptyList())
    val logs: StateFlow<List<BuildLogLine>> = _logs.asStateFlow()

    /** 当前阶段文案（准备环境 / Gradle 构建中 / 收集产物…）。 */
    private val _phase = MutableStateFlow("待命")
    val phase: StateFlow<String> = _phase.asStateFlow()

    /** 阶段进度 0..1（构建中为近似值，配合不确定态进度条展示）。 */
    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress.asStateFlow()

    /** 内存采样（当前 / 限制）。 */
    private val _memory = MutableStateFlow<MemorySample?>(null)
    val memory: StateFlow<MemorySample?> = _memory.asStateFlow()

    /** 解析出的编译错误列表（可点击定位）。 */
    private val _errors = MutableStateFlow<List<BuildError>>(emptyList())
    val errors: StateFlow<List<BuildError>> = _errors.asStateFlow()

    /** 最近点击的错误（跳转目标，供后续编辑器联动）。 */
    private val _jumpTarget = MutableStateFlow<BuildError?>(null)
    val jumpTarget: StateFlow<BuildError?> = _jumpTarget.asStateFlow()

    /** 本次构建产物。 */
    private val _artifacts = MutableStateFlow<List<BuildArtifact>>(emptyList())
    val artifacts: StateFlow<List<BuildArtifact>> = _artifacts.asStateFlow()

    /** 构建历史（最近 10 条）。 */
    private val _history = MutableStateFlow<List<BuildHistoryEntry>>(emptyList())
    val history: StateFlow<List<BuildHistoryEntry>> = _history.asStateFlow()

    // ------------------------------------------------------------------
    // 内部状态
    // ------------------------------------------------------------------

    private lateinit var appContext: Context

    @Volatile
    private var initialized = false

    @Volatile
    private var currentPid = -1

    @Volatile
    private var cancelRequested = false

    @Volatile
    private var memoryAborted = false

    @Volatile
    private var currentProject: File? = null

    private val stateLock = Any()
    private val logLock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lineSeq = AtomicLong(0)

    private var activeExit: CompletableDeferred<Int>? = null
    private var memoryJob: Job? = null

    // 每个流一个行缓冲（JNI 回调线程内使用，受 logLock 保护）
    private val stdoutBuf = StringBuilder()
    private val stderrBuf = StringBuilder()
    private val pendingCr = BooleanArray(2) // index 0 = stdout, 1 = stderr

    /** Gradle「* What went wrong:」区块状态（用于把 `> …` 记为错误详情）。 */
    private var inWhatWentWrong = false

    private var baselineUsedMb = 0L

    // ------------------------------------------------------------------
    // 初始化（Application.onCreate 调用：幂等、非阻塞、绝不崩溃）
    // ------------------------------------------------------------------

    fun init(context: Context) {
        if (initialized) return
        synchronized(stateLock) {
            if (initialized) return
            try {
                appContext = context.applicationContext
                AppStorage.init(appContext)
                NativeRuntime.ensureProcessEnvironment(appContext)
                initialized = true
                scope.launch {
                    runCatching {
                        BuildEnvironment.refresh(appContext)
                        _history.value = readHistory()
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "BuildRunner 初始化失败：${t.message}", t)
            }
        }
    }

    // ------------------------------------------------------------------
    // 构建入口
    // ------------------------------------------------------------------

    /** 是否为可构建的 Gradle 工程。 */
    fun isGradleProject(dir: File): Boolean =
        listOf("settings.gradle", "settings.gradle.kts", "build.gradle", "build.gradle.kts", "gradlew")
            .any { File(dir, it).exists() }

    /**
     * 启动一次构建（构建页唯一入口）。
     *
     * @return 退出码的 [CompletableDeferred]；已有构建在跑或未初始化时返回 null
     */
    fun start(request: BuildRequest): CompletableDeferred<Int>? {
        if (!initialized) return null
        synchronized(stateLock) {
            if (activeExit != null) return null
            val deferred = CompletableDeferred<Int>()
            activeExit = deferred
            scope.launch {
                val code = try {
                    runBuild(request)
                } catch (t: Throwable) {
                    Log.w(TAG, "构建异常：${t.message}", t)
                    append("构建异常中止：${t.message ?: t::class.java.simpleName}", BuildLogLevel.ERROR)
                    _state.value = BuildState.Failed("构建异常中止：${t.message ?: t::class.java.simpleName}")
                    BuildForegroundService.finishBuild(
                        appContext, false, "构建失败",
                        "构建异常中止：${t.message ?: t::class.java.simpleName}",
                    )
                    1
                }
                deferred.complete(code)
                synchronized(stateLock) {
                    if (activeExit === deferred) activeExit = null
                }
            }
            return deferred
        }
    }

    /** UI 便捷入口：是否已成功排队。 */
    fun startBuild(request: BuildRequest): Boolean = start(request) != null

    /** 当前是否有构建在进行。 */
    val isRunning: Boolean get() = activeExit != null

    /** 取消构建：子进程 SIGKILL 整个进程组；进程内打包置取消位后中止。 */
    fun cancel() {
        cancelRequested = true
        val pid = currentPid
        if (pid <= 0) {
            append("已请求取消（打包或准备阶段将尽快停止）", BuildLogLevel.WARN)
            return
        }
        append("已请求取消构建（SIGKILL）", BuildLogLevel.WARN)
        BuildForegroundService.update(appContext, "正在取消构建…")
        runCatching { CliNative.killProcess(pid, 9) }
    }

    /** 追加一条自定义日志（环境提示、产物操作反馈等）。 */
    fun append(text: String, level: BuildLogLevel = BuildLogLevel.INFO) {
        appendLine(text, level, 1)
    }

    /** 环境体检：打印路径与可用性到日志流。 */
    fun runHealthCheck() {
        scope.launch {
            runCatching {
                val status = BuildEnvironment.refresh(appContext)
                BuildEnvironment.healthLines(appContext, status).forEach { line ->
                    val level = if (line.startsWith("结论") && !status.ready) {
                        BuildLogLevel.WARN
                    } else if (line.startsWith("结论")) {
                        BuildLogLevel.SUCCESS
                    } else {
                        BuildLogLevel.INFO
                    }
                    appendLine(line, level, 1)
                }
            }
        }
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }

    fun clearErrors() {
        _errors.value = emptyList()
    }

    /** 记录跳转目标（错误行点击时调用，供编辑器联动）。 */
    fun requestJump(error: BuildError) {
        _jumpTarget.value = error
        if (error.file != null && error.inProject) {
            append("跳转目标：${error.location}", BuildLogLevel.INFO)
        }
    }

    /** 重新扫描产物（按当前项目类型）。 */
    fun refreshArtifacts() {
        val project = currentProject ?: return
        scope.launch {
            val kind = detectProject(project).kind
            _artifacts.value = withContext(Dispatchers.IO) {
                scanArtifacts(project, kind, defaultVariantOf(kind))
            }
        }
    }

    /**
     * 切换到 [dir] 项目（构建页进入 / 换项目时调用）：
     * 重置展示态（状态 / 阶段 / 日志 / 错误 / 产物）并按新项目类型重扫产物，
     * 避免上一个项目的构建结果串到当前项目；**构建进行中不打断**（后台继续）。
     */
    fun openProject(dir: File) {
        currentProject = dir
        val busy = _state.value is BuildState.Running || _state.value is BuildState.Preparing
        if (busy) return
        _state.value = BuildState.Idle
        _phase.value = "待命"
        _progress.value = 0f
        _logs.value = emptyList()
        _errors.value = emptyList()
        _memory.value = null
        refreshArtifacts()
    }

    /** 各类型的默认扫描变体（打开项目即重扫时用，与 [variantFor] 同口径）。 */
    private fun defaultVariantOf(kind: ProjectKind): String = when (kind) {
        ProjectKind.ANDROID_APP -> "debug"
        ProjectKind.JVM_GRADLE -> "assemble"
        ProjectKind.NODE -> "build"
        ProjectKind.ZIP_PACKAGE -> "package"
    }

    // ------------------------------------------------------------------
    // 构建主流程
    // ------------------------------------------------------------------

    private suspend fun runBuild(request: BuildRequest): Int {
        val startedAt = System.currentTimeMillis()
        val profile = detectProject(request.projectDir)
        val task = BuildTask(
            projectPath = request.projectDir.absolutePath,
            variant = variantFor(profile, request),
            clean = request.clean,
            extraTasks = request.extraTasks,
            startedAt = startedAt,
            label = labelFor(profile, request),
        )
        currentProject = request.projectDir
        cancelRequested = false
        memoryAborted = false
        inWhatWentWrong = false
        synchronized(logLock) {
            stdoutBuf.setLength(0)
            stderrBuf.setLength(0)
            pendingCr[0] = false
            pendingCr[1] = false
        }
        _errors.value = emptyList()
        _artifacts.value = emptyList()
        _memory.value = null
        _state.value = BuildState.Preparing("准备构建环境")
        _phase.value = "准备环境"
        _progress.value = 0.06f
        append("开始构建：${describe(task)}", BuildLogLevel.INPUT)
        append("检测到项目类型：${profile.displayName}（${profile.kind.label} · 产物：${profile.artifactLabel}）", BuildLogLevel.INFO)

        // ---- 1) 构建环境校验（按项目类型动态；纯打包类无需任何工具链） ----
        _phase.value = "检查构建环境"
        var jdk: String? = null
        var gradleHome: String? = null
        var heapMb = 0
        if (profile.kind == ProjectKind.ZIP_PACKAGE) {
            // 进程内打包：不跑子进程、不依赖 JDK/SDK/Node，顺带刷新环境状态即可
            runCatching { BuildEnvironment.refresh(appContext, request.projectDir) }
        } else {
            val status = BuildEnvironment.refresh(appContext, request.projectDir)
            jdk = status.item(EnvKind.JDK)?.takeIf { it.ready }?.path
            gradleHome = status.item(EnvKind.GRADLE)?.takeIf { it.ready }?.path
            val sdk = BuildEnvironment.sdkDir(appContext)
            if (EnvKind.JDK in profile.requiredEnv && jdk == null) {
                return fail(
                    task, startedAt,
                    "构建环境未就绪：缺少 JDK。请点顶部「环境状态条」进入「环境中心」在线下载，或从本地导入",
                )
            }
            if (EnvKind.SDK in profile.requiredEnv && !BuildEnvironment.sdkLooksReady(sdk)) {
                return fail(
                    task, startedAt,
                    "构建环境未就绪：缺少 Android SDK（需要 platforms / build-tools）。请在「环境中心」中在线安装 SDK，或导入 SDK 压缩包",
                )
            }
            if (EnvKind.GRADLE in profile.requiredEnv) {
                val gradlew = File(request.projectDir, "gradlew")
                val wrapperJar = File(request.projectDir, "gradle/wrapper/gradle-wrapper.jar")
                if (gradleHome == null && !(gradlew.exists() && wrapperJar.exists())) {
                    return fail(
                        task, startedAt,
                        "构建环境未就绪：缺少 Gradle 发行版。请在「环境中心」中在线下载，或导入 gradle-x.x-bin.zip",
                    )
                }
            }
            if (EnvKind.NODE in profile.requiredEnv && status.item(EnvKind.NODE)?.ready != true) {
                return fail(
                    task, startedAt,
                    "构建环境未就绪：缺少 Node.js（含 npm）。请在「环境中心」在线下载 Node.js，或导入 node-v*-linux-*.tar.gz",
                )
            }
            // Linux 环境（Ubuntu rootfs + proot）：JDK（Temurin）/ Gradle / node 全是 glibc
            // ELF，Android 没有 /lib/ld-linux-aarch64.so.1 → 不先装，java 会在 exec 阶段 127。
            // 与 apt 链同一条安装路径：在线下载 rootfs + proot，装不上给自救提示。
            if (!Proot.isReady(appContext)) {
                _phase.value = "安装 Linux 环境"
                append("Linux 环境未就绪 → 安装（Ubuntu rootfs + proot，约 35MB）…", BuildLogLevel.INFO)
                val source = runCatching {
                    if (AppStorage.preferences.envDownloadSource() == "mirror") {
                        EnvSource.MIRROR
                    } else {
                        EnvSource.OFFICIAL
                    }
                }.getOrDefault(EnvSource.OFFICIAL)
                val ok = ToolInstaller.ensureLinux(appContext, source) {
                    append(it, BuildLogLevel.INFO)
                }
                if (!ok) {
                    return fail(
                        task, startedAt,
                        "构建环境未就绪：Linux 环境缺失（proot + Ubuntu rootfs，JDK / Gradle 在其中运行）。" +
                            "请在「环境中心」页在线下载，或导入 ubuntu-base-*.tar.gz 后重试",
                    )
                }
            }
            if (cancelRequested) return fail(task, startedAt, "已取消构建")
            // 内存上限落地：写入项目 gradle.properties（org.gradle.jvmargs），返回 MB 值
            if (profile.usesGradle) heapMb = syncGradleHeap(request.projectDir)
        }

        // ---- 2) 进程内源码打包（无需子进程与工具链） ----
        if (profile.kind == ProjectKind.ZIP_PACKAGE) {
            return runPackaging(request, task, profile, startedAt)
        }

        // ---- 3) 命令组装（Gradle / npm） ----
        val cmd = if (profile.kind == ProjectKind.NODE) {
            "npm install --no-audit --no-fund --loglevel=error && npm run build"
        } else {
            val tasks = buildList {
                if (task.clean) add("clean")
                add(task.label)
                addAll(task.extraTasks)
            }
            val gradlew = File(request.projectDir, "gradlew")
            val base = if (gradlew.exists()) {
                "chmod +x ./gradlew 2>/dev/null; sh ./gradlew"
            } else {
                val bin = File(gradleHome!!, "bin/gradle")
                "chmod +x '${bin.absolutePath}' 2>/dev/null; '${bin.absolutePath}'"
            }
            "$base ${tasks.joinToString(" ")} --no-daemon --stacktrace"
        }
        val envArray = buildEnvArray(jdk, gradleHome, heapMb)
        BuildEnvironment.tmpDir(appContext).mkdirs()
        BuildEnvironment.gradleUserHome(appContext).mkdirs()

        append("执行：$cmd", BuildLogLevel.INFO)
        _phase.value = "启动构建进程"
        _progress.value = 0.15f

        // ---- 4) 前台服务保活 + 内存基线 ----------------------------------
        BuildForegroundService.startBuild(appContext, "${task.label} · ${task.projectPath.substringAfterLast('/')}")
        baselineUsedMb = systemUsedMb(appContext)

        // ---- 5) 启动子进程（Linux 环境就绪 → 整条命令在 proot 内执行）----
        val exit = CompletableDeferred<Int>()
        val execArgv = Proot.wrap(
            appContext,
            arrayOf("/bin/sh", "-c", cmd),
            request.projectDir.absolutePath,
        )
        val pid = CliNative.exec(
            execArgv,
            request.projectDir.absolutePath,
            envArray,
            buildCallback(exit),
        )
        if (pid < 0) {
            BuildForegroundService.finishBuild(appContext, false, "构建失败", "无法启动构建进程")
            return fail(task, startedAt, "无法启动构建进程：并发进程数已达上限或系统拒绝 fork，请稍后重试")
        }
        currentPid = pid
        _state.value = BuildState.Running(task)
        _phase.value = if (profile.kind == ProjectKind.NODE) "npm 构建中" else "Gradle 构建中"
        _progress.value = 0.45f
        startMemoryWatch(pid)

        val code = exit.await()

        // ---- 6) 收尾 -----------------------------------------------------
        currentPid = -1
        memoryJob?.cancel()
        memoryJob = null
        flushPending()

        if (code == 0 && !cancelRequested && !memoryAborted) {
            _phase.value = "收集产物"
            _progress.value = 0.92f
            val artifacts = collectArtifacts(request, profile, task)
            return completeSuccess(task, profile, startedAt, artifacts)
        }

        val message = when {
            memoryAborted -> "内存不足，已中止构建"
            cancelRequested -> "已取消构建"
            else -> {
                val detail = lastErrorLine()
                if (detail != null) "构建失败（退出码 $code）：$detail" else "构建失败（退出码 $code）"
            }
        }
        _phase.value = "构建失败"
        _progress.value = 1f
        _state.value = BuildState.Failed(message, task, System.currentTimeMillis() - startedAt)
        append(message, BuildLogLevel.ERROR)
        recordHistory(task, System.currentTimeMillis() - startedAt, false, "", message)
        BuildForegroundService.finishBuild(appContext, false, "构建失败", message)
        return 1
    }

    /**
     * 进程内源码打包（[ProjectKind.ZIP_PACKAGE]）：无需子进程 / 工具链，
     * 状态机照常走 Running → Success / Failed，产物同样可下载 / 分享。
     */
    private suspend fun runPackaging(
        request: BuildRequest,
        task: BuildTask,
        profile: ProjectProfile,
        startedAt: Long,
    ): Int {
        BuildForegroundService.startBuild(appContext, "打包 · ${task.projectPath.substringAfterLast('/')}")
        _state.value = BuildState.Running(task)
        _phase.value = "打包中"
        _progress.value = 0.35f
        append("无需编译环境：进程内打包为 zip（完成后可下载 / 分享）", BuildLogLevel.INFO)
        val result = withContext(Dispatchers.IO) {
            runCatching {
                ProjectPackager.packageProject(
                    projectDir = request.projectDir,
                    shouldCancel = { cancelRequested },
                    onProgress = { n -> append("已打包 $n 个文件…", BuildLogLevel.INFO) },
                )
            }
        }
        return result.fold(
            onSuccess = { file ->
                completeSuccess(
                    task = task,
                    profile = profile,
                    startedAt = startedAt,
                    artifacts = listOf(artifactOf(request.projectDir, file, task.variant)),
                    verb = "打包完成",
                )
            },
            onFailure = { t ->
                if (t is PackagingCancelledException || cancelRequested) {
                    fail(task, startedAt, "已取消打包")
                } else {
                    fail(task, startedAt, "打包失败：${t.message ?: t::class.java.simpleName}")
                }
            },
        )
    }

    /** 构建成功收尾：状态 / 日志 / 产物 / 历史 / 前台通知统一走这里。 */
    private suspend fun completeSuccess(
        task: BuildTask,
        profile: ProjectProfile,
        startedAt: Long,
        artifacts: List<BuildArtifact>,
        verb: String = "构建成功",
    ): Int {
        val duration = System.currentTimeMillis() - startedAt
        _phase.value = verb
        _progress.value = 1f
        _artifacts.value = artifacts
        _state.value = BuildState.Success(task, artifacts.map { it.path }, duration)
        append("$verb，耗时 ${formatDuration(duration)}，产出 ${artifacts.size} 个产物", BuildLogLevel.SUCCESS)
        artifacts.forEach {
            append("  ${it.path}（${humanSize(it.size)}）", BuildLogLevel.SUCCESS)
        }
        if (artifacts.any { it.unsigned }) {
            append("提示：检测到未签名 APK，安装前请配置签名或改用 Debug 构建", BuildLogLevel.WARN)
        }
        recordHistory(task, duration, true, artifacts.firstOrNull()?.path.orEmpty(), "成功")
        BuildForegroundService.finishBuild(
            appContext, true, verb,
            "${task.label} · ${artifacts.size} 个产物 · 耗时 ${formatDuration(duration)}",
        )
        return 0
    }

    /**
     * 构建成功后的产物收集：
     *  - NODE：把 `dist / out / build` 目录重新压成 `<项目名>-dist.zip`（每次构建产出最新一份）；
     *  - 其它类型：按产物规则扫描（APK / JAR / 打包 zip）。
     */
    private suspend fun collectArtifacts(
        request: BuildRequest,
        profile: ProjectProfile,
        task: BuildTask,
    ): List<BuildArtifact> {
        if (profile.kind == ProjectKind.NODE) {
            val dist = withContext(Dispatchers.IO) {
                runCatching {
                    ProjectPackager.packageNodeDist(request.projectDir) { n ->
                        if (n > 0 && n % 512 == 0) append("已打包 $n 个产物文件…", BuildLogLevel.INFO)
                    }
                }
            }
            dist.fold(
                onSuccess = { file ->
                    if (file != null) return listOf(artifactOf(request.projectDir, file, task.variant))
                },
                onFailure = {
                    if (it is PackagingCancelledException) return emptyList()
                    append("dist 打包失败：${it.message ?: it::class.java.simpleName}", BuildLogLevel.WARN)
                },
            )
            append("构建命令已成功，但未发现产物目录（dist / out / build）", BuildLogLevel.WARN)
            return emptyList()
        }
        return withContext(Dispatchers.IO) {
            scanArtifacts(request.projectDir, profile.kind, task.variant)
        }
    }

    /** 展示用变体：`debug` / `release`（安卓）、`assemble` / `build` / `package`（其它）。 */
    private fun variantFor(profile: ProjectProfile, request: BuildRequest): String = when (profile.kind) {
        ProjectKind.ANDROID_APP -> if (request.variant.equals("release", true)) "release" else "debug"
        ProjectKind.JVM_GRADLE -> "assemble"
        ProjectKind.NODE -> "build"
        ProjectKind.ZIP_PACKAGE -> "package"
    }

    /** 展示用任务名（日志 / 历史 / 前台通知）。 */
    private fun labelFor(profile: ProjectProfile, request: BuildRequest): String = when (profile.kind) {
        ProjectKind.ANDROID_APP ->
            if (request.variant.equals("release", true)) "assembleRelease" else "assembleDebug"
        ProjectKind.JVM_GRADLE -> "assemble"
        ProjectKind.NODE -> "npm run build"
        ProjectKind.ZIP_PACKAGE -> "打包源码"
    }

    private fun fail(task: BuildTask, startedAt: Long, message: String): Int {
        val duration = System.currentTimeMillis() - startedAt
        _phase.value = "构建失败"
        _progress.value = 1f
        _state.value = BuildState.Failed(message, task, duration)
        append(message, BuildLogLevel.ERROR)
        recordHistory(task, duration, false, "", message)
        BuildForegroundService.finishBuild(appContext, false, "构建失败", message)
        return 1
    }

    private fun describe(task: BuildTask): String = buildString {
        append(task.label)
        if (task.clean) append("（先 clean）")
        if (task.extraTasks.isNotEmpty()) append(" + ${task.extraTasks.joinToString(" ")}")
        append(" · ${task.projectPath}")
    }

    // ------------------------------------------------------------------
    // 子进程环境（CliNative envArray 会整体替换子进程环境，必须给全）
    // ------------------------------------------------------------------

    private fun buildEnvArray(jdk: String?, gradleHome: String?, heapMb: Int): Array<String> {
        val files = appContext.filesDir
        val sdk = BuildEnvironment.sdkDir(appContext)
        return buildList {
            add("HOME=${files.absolutePath}")
            add("TMPDIR=${BuildEnvironment.tmpDir(appContext).absolutePath}")
            // guest 标准路径（/usr/bin 等）排在 bionic 前：proot 内拿到 Linux 工具链，
            // rootfs 未装时这些目录不存在、PATH 自动跳过 → 仍是 /system/bin 兜底
            add("PATH=${Proot.pathFor(BuildEnvironment.binDir(appContext).absolutePath)}")
            add("LANG=C.UTF-8")
            add("LC_ALL=C.UTF-8")
            add("SHELL=/system/bin/sh")
            jdk?.let { add("JAVA_HOME=$it") }
            add("ANDROID_HOME=${sdk.absolutePath}")
            add("ANDROID_SDK_ROOT=${sdk.absolutePath}")
            add("GRADLE_USER_HOME=${BuildEnvironment.gradleUserHome(appContext).absolutePath}")
            add("MOBILECODER_HOME=${files.absolutePath}")
            add("MOBILECODER_GRADLE_HOME=${gradleHome.orEmpty()}")
            // 仅 Gradle 构建注入堆参数（npm / 打包不需要，避免 -Xmx0m 之类无效值）
            if (heapMb > 0) add("GRADLE_OPTS=-Xmx${heapMb}m -Dorg.gradle.daemon=false")
            add("JAVA_OPTS=-Dfile.encoding=UTF-8")
            // proot 执行通道所需变量（LD_LIBRARY_PATH / PROOT_LOADER / PROOT_TMP_DIR），
            // Linux 环境未就绪时为空数组，构建流程不依赖任何额外机制
        }.toTypedArray<String>() + Proot.envEntries(appContext)
    }

    /**
     * 让项目 `gradle.properties` 的 `org.gradle.jvmargs` 与用户设定的内存上限一致
     * （[com.mobilecoder.ide.core.storage.AppPreferences.buildMemoryLimitMb]），每次构建前执行，
     * Slider 调整后下一次构建即生效。
     *
     * @return 当前内存上限（MB）
     */
    private suspend fun syncGradleHeap(projectDir: File): Int {
        val heapMb = runCatching { AppStorage.preferences.buildMemoryLimitMb() }.getOrDefault(2048)
        runCatching {
            val file = File(projectDir, "gradle.properties")
            val line = "org.gradle.jvmargs=-Xmx${heapMb}m -XX:MaxMetaspaceSize=512m -Dfile.encoding=UTF-8"
            val old = if (file.exists()) file.readText() else ""
            val kept = old.lines().dropLastWhile { it.isBlank() }
                .filter { !it.startsWith("org.gradle.jvmargs") }
            val newText = (kept + line).joinToString("\n") + "\n"
            if (newText != old) file.writeText(newText)
        }
        Log.i(TAG, "构建内存上限 → -Xmx${heapMb}m")
        return heapMb
    }

    // ------------------------------------------------------------------
    // JNI 回调（子进程读线程）
    // ------------------------------------------------------------------

    private fun buildCallback(exit: CompletableDeferred<Int>): CliCallback = object : CliCallback {

        override fun onOutput(pid: Int, stream: Int, data: ByteArray?) {
            if (data == null || data.isEmpty()) return
            val text = String(data, Charsets.UTF_8)
            val completed = ArrayList<String>(8)
            synchronized(logLock) {
                val index = if (stream == 2) 1 else 0
                val buf = if (index == 1) stderrBuf else stdoutBuf
                for (ch in text) {
                    if (pendingCr[index]) {
                        pendingCr[index] = false
                        if (ch == '\n') {
                            // \r\n → 普通换行
                            val line = buf.toString().trimEnd('\r')
                            buf.setLength(0)
                            if (line.isNotEmpty()) completed.add(line)
                            continue
                        }
                        // \r 覆盖：丢弃进度条的中间态
                        buf.setLength(0)
                    }
                    when (ch) {
                        '\n' -> {
                            val line = buf.toString().trimEnd('\r')
                            buf.setLength(0)
                            if (line.isNotEmpty()) completed.add(line)
                        }
                        '\r' -> pendingCr[index] = true
                        else -> buf.append(ch)
                    }
                }
            }
            completed.forEach { handleLine(it, stream) }
        }

        override fun onExit(pid: Int, code: Int) {
            flushPending()
            exit.complete(code)
        }
    }

    /** 退出时把未换行的残留内容补进日志。 */
    private fun flushPending() {
        val rest = ArrayList<Pair<String, Int>>(2)
        synchronized(logLock) {
            rest.add(stdoutBuf.toString() to 1)
            rest.add(stderrBuf.toString() to 2)
            stdoutBuf.setLength(0)
            stderrBuf.setLength(0)
            pendingCr[0] = false
            pendingCr[1] = false
        }
        rest.forEach { (text, stream) ->
            if (text.isNotBlank()) handleLine(text.trimEnd('\r'), stream)
        }
    }

    // ------------------------------------------------------------------
    // 日志与错误解析
    // ------------------------------------------------------------------

    private fun handleLine(line: String, stream: Int) {
        parseErrors(line)
        appendLine(line, classify(line, stream), stream)
    }

    private fun classify(line: String, stream: Int): BuildLogLevel {
        val trimmed = line.trim()
        return when {
            trimmed.startsWith("BUILD SUCCESSFUL") -> BuildLogLevel.SUCCESS
            trimmed.startsWith("BUILD FAILED") -> BuildLogLevel.ERROR
            trimmed.startsWith("e: ") || trimmed.startsWith("E: ") -> BuildLogLevel.ERROR
            trimmed.startsWith("w: ") || trimmed.startsWith("W: ") -> BuildLogLevel.WARN
            // npm / vite / esbuild / webpack
            trimmed.startsWith("npm error") || trimmed.startsWith("ERR!") ||
                trimmed.contains("ERROR in") || trimmed.startsWith("✘") ->
                BuildLogLevel.ERROR
            trimmed.startsWith("npm warn") -> BuildLogLevel.WARN
            trimmed.contains("* What went wrong:") -> BuildLogLevel.ERROR
            trimmed.contains("Execution failed for task") -> BuildLogLevel.ERROR
            trimmed.contains("FAILURE: Build failed") -> BuildLogLevel.ERROR
            trimmed.contains("错误:") || trimmed.contains("error:") -> BuildLogLevel.ERROR
            trimmed.contains("FAILED") -> BuildLogLevel.ERROR
            trimmed.contains("Exception") || trimmed.contains("Caused by:") -> BuildLogLevel.ERROR
            trimmed.startsWith("> ") && inWhatWentWrong -> BuildLogLevel.ERROR
            trimmed.contains("警告") || trimmed.contains("warning:") -> BuildLogLevel.WARN
            trimmed.contains("Deprecated") -> BuildLogLevel.WARN
            stream == 2 -> BuildLogLevel.WARN
            else -> BuildLogLevel.INFO
        }
    }

    private fun appendLine(text: String, level: BuildLogLevel, stream: Int) {
        val line = BuildLogLine(lineSeq.incrementAndGet(), text, level, stream)
        synchronized(logLock) {
            val next = _logs.value.toMutableList()
            next.add(line)
            _logs.value = if (next.size > MAX_LOG_LINES) next.takeLast(MAX_LOG_LINES) else next
        }
    }

    /**
     * 错误定位（TECH 4.6 编译错误高亮）：
     *  - Kotlin：`e: file:///path/File.kt:12:5 message`、`w: file://…`
     *  - Java：`path/File.java:12: 错误: …`
     *  - Gradle：`* What went wrong:` 之后的 `> …`、`Execution failed for task ':app:xxx'`
     */
    private fun parseErrors(line: String) {
        val trimmed = line.trim()
        if (trimmed.contains("* What went wrong:")) {
            inWhatWentWrong = true
            return
        }
        if (inWhatWentWrong &&
            (trimmed.contains("* Try:") || trimmed.contains("* Get more help") ||
                trimmed.startsWith("BUILD FAILED") || trimmed.contains("FAILURE:")
            )
        ) {
            inWhatWentWrong = false
        }

        val found = ArrayList<BuildError>(2)

        RE_KOTLIN.find(trimmed)?.let { m ->
            found.add(
                BuildError(
                    message = m.groupValues[4].ifBlank { trimmed },
                    file = m.groupValues[1],
                    line = m.groupValues[2].toIntOrNull(),
                    column = m.groupValues[3].toIntOrNull(),
                    raw = trimmed,
                ),
            )
        }
        if (found.isEmpty()) {
            RE_KOTLIN_NO_COL.find(trimmed)?.let { m ->
                found.add(
                    BuildError(
                        message = m.groupValues[3].ifBlank { trimmed },
                        file = m.groupValues[1],
                        line = m.groupValues[2].toIntOrNull(),
                        raw = trimmed,
                    ),
                )
            }
        }
        if (found.isEmpty()) {
            (RE_JAVA.find(trimmed) ?: RE_JAVA_REL.find(trimmed))?.let { m ->
                found.add(
                    BuildError(
                        message = m.groupValues[3].ifBlank { trimmed },
                        file = m.groupValues[1],
                        line = m.groupValues[2].toIntOrNull(),
                        raw = trimmed,
                    ),
                )
            }
        }
        if (found.isEmpty()) {
            RE_JS.find(trimmed)?.let { m ->
                val rest = m.groupValues[4].trim().trim(':', ' ').trim()
                found.add(
                    BuildError(
                        message = rest.ifBlank { "编译错误：${m.groupValues[1]}:${m.groupValues[2]}" },
                        file = m.groupValues[1],
                        line = m.groupValues[2].toIntOrNull(),
                        column = m.groupValues[3].toIntOrNull(),
                        raw = trimmed,
                    ),
                )
            }
        }
        if (found.isEmpty() && inWhatWentWrong && trimmed.startsWith(">")) {
            found.add(BuildError(message = trimmed.trimStart('>', ' '), raw = trimmed))
        }
        if (found.isEmpty()) {
            RE_TASK.find(trimmed)?.let { m ->
                found.add(BuildError(message = "任务失败：${m.groupValues[1]}", raw = trimmed))
            }
        }

        found.forEach { addError(resolve(it)) }
    }

    /** 相对路径补全为绝对路径，并判断是否位于工程内（可跳转）。 */
    private fun resolve(error: BuildError): BuildError {
        val rawPath = error.file ?: return error
        if (rawPath.isBlank()) return error.copy(file = null)
        val file = if (rawPath.startsWith("/")) File(rawPath) else File(currentProject ?: File("."), rawPath)
        val canonical = runCatching { file.canonicalFile }.getOrDefault(file)
        val project = currentProject
        val inProject = project != null && runCatching {
            canonical.path.startsWith(project.canonicalPath + File.separator)
        }.getOrDefault(false)
        return error.copy(file = canonical.path, inProject = inProject)
    }

    private fun addError(error: BuildError) {
        if (error.file == null && error.message.isBlank()) return
        synchronized(logLock) {
            val list = _errors.value
            if (list.any {
                    it.file == error.file && it.line == error.line && it.message == error.message
                }
            ) return
            val next = list.toMutableList()
            next.add(error)
            _errors.value = if (next.size > MAX_ERRORS) next.takeLast(MAX_ERRORS) else next
        }
    }

    private fun lastErrorLine(): String? {
        val explicit = _logs.value.lastOrNull { it.level == BuildLogLevel.ERROR }?.text
        if (explicit != null) return explicit.take(160)
        return _errors.value.lastOrNull()?.let { "${it.location} ${it.message}" }?.take(160)
    }

    // ------------------------------------------------------------------
    // 内存管理（PRD：内存管控，超出停止保护）
    // ------------------------------------------------------------------

    private fun systemUsedMb(context: Context): Long {
        val am = runCatching { context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager }
            .getOrNull() ?: return 0L
        val info = ActivityManager.MemoryInfo()
        return runCatching {
            am.getMemoryInfo(info)
            (info.totalMem - info.availMem) / MB
        }.getOrDefault(0L)
    }

    private fun startMemoryWatch(pid: Int) {
        memoryJob?.cancel()
        memoryJob = scope.launch {
            val am = runCatching {
                appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            }.getOrNull() ?: return@launch
            val info = ActivityManager.MemoryInfo()
            var warned = false
            while (isActive && currentPid == pid && !cancelRequested) {
                delay(1500)
                runCatching {
                    am.getMemoryInfo(info)
                    val totalMb = info.totalMem / MB
                    val usedMb = (info.totalMem - info.availMem) / MB
                    val growth = (usedMb - baselineUsedMb).coerceAtLeast(0L)
                    val ratio = if (info.totalMem > 0) {
                        (info.totalMem - info.availMem).toFloat() / info.totalMem.toFloat()
                    } else {
                        0f
                    }
                    val limit = runCatching { AppStorage.preferences.buildMemoryLimitMb() }
                        .getOrDefault(1024)
                    _memory.value = MemorySample(
                        buildUsedMb = growth,
                        systemUsedMb = usedMb,
                        totalMb = totalMb,
                        usedRatio = ratio,
                        limitMb = limit,
                    )
                    when {
                        ratio >= 0.90f || growth > limit -> abortForMemory(pid)
                        !warned && growth > (limit * 4 / 5) -> {
                            warned = true
                            append(
                                "内存接近上限：已增长 ${growth}MB / 限制 ${limit}MB",
                                BuildLogLevel.WARN,
                            )
                        }
                    }
                }
            }
        }
    }

    private fun abortForMemory(pid: Int) {
        if (memoryAborted) return
        memoryAborted = true
        append("内存不足，已中止构建", BuildLogLevel.ERROR)
        BuildForegroundService.update(appContext, "内存不足，已中止构建")
        runCatching { CliNative.killProcess(pid, 9) }
    }

    // ------------------------------------------------------------------
    // 产物扫描
    // ------------------------------------------------------------------

    /**
     * 按项目类型扫描产物（`internal` 供单测）：
     *  - [ProjectKind.ANDROID_APP] → `build/outputs/apk` 下的 `.apk`（按变体过滤）；
     *  - [ProjectKind.JVM_GRADLE] → `build/libs` 下的 `.jar`（排除 sources / javadoc）；
     *  - [ProjectKind.NODE] / [ProjectKind.ZIP_PACKAGE] → `build/outputs/package` 下的 `.zip`。
     */
    internal fun scanArtifacts(
        projectDir: File,
        kind: ProjectKind,
        variant: String,
    ): List<BuildArtifact> = when (kind) {
        ProjectKind.ANDROID_APP -> scanApks(projectDir, variant)
        ProjectKind.JVM_GRADLE -> scanByPattern(projectDir, "build/libs", variant) { file ->
            file.extension.equals("jar", ignoreCase = true) &&
                !file.name.contains("sources") &&
                !file.name.contains("javadoc")
        }
        ProjectKind.NODE, ProjectKind.ZIP_PACKAGE ->
            scanByPattern(projectDir, "build/outputs/package", variant) { file ->
                file.extension.equals("zip", ignoreCase = true)
            }
    }

    /** 扫描 `projectDir/**/build/outputs/apk/**/*.apk`。 */
    internal fun scanApks(projectDir: File, variant: String): List<BuildArtifact> {
        val found = ArrayList<BuildArtifact>()
        runCatching {
            projectDir.walkTopDown()
                .onFail { _, _ -> null }
                .filter { it.isFile && it.extension.equals("apk", ignoreCase = true) }
                .forEach { file ->
                    val path = file.invariantSeparatorsPath
                    if (!path.contains("build/outputs/apk")) return@forEach
                    val v = when {
                        path.contains("/release/") || file.name.contains("release") -> "release"
                        path.contains("/debug/") || file.name.contains("debug") -> "debug"
                        else -> "unknown"
                    }
                    found.add(artifactOf(projectDir, file, v))
                }
        }
        val matching = found.filter { it.variant == variant }
        val list = if (matching.isNotEmpty()) matching else found
        return list.sortedByDescending { it.modifiedAt }
    }

    /** 按路径标记 + 扩展名扫描产物（JAR / 打包 zip 共用）。 */
    private fun scanByPattern(
        projectDir: File,
        pathMarker: String,
        variant: String,
        accept: (File) -> Boolean,
    ): List<BuildArtifact> {
        val found = ArrayList<BuildArtifact>()
        runCatching {
            projectDir.walkTopDown()
                .onFail { _, _ -> null }
                .filter { it.isFile && accept(it) && it.invariantSeparatorsPath.contains(pathMarker) }
                .forEach { file -> found.add(artifactOf(projectDir, file, variant)) }
        }
        return found.sortedByDescending { it.modifiedAt }
    }

    /** 文件 → 产物模型。 */
    private fun artifactOf(projectDir: File, file: File, variant: String): BuildArtifact =
        BuildArtifact(
            name = file.name,
            module = relativeModule(projectDir, file),
            variant = variant,
            size = runCatching { file.length() }.getOrDefault(0L),
            modifiedAt = runCatching { file.lastModified() }.getOrDefault(0L),
            path = file.absolutePath,
        )

    /** 产物所属模块（相对工程根；根 `build/` 下的产物返回空串 = 根模块）。 */
    internal fun relativeModule(projectDir: File, apk: File): String {
        val rel = runCatching {
            apk.absolutePath.removePrefix(projectDir.absolutePath).trimStart(File.separatorChar)
        }.getOrDefault(apk.name)
        val marker = "${File.separator}build${File.separator}"
        val index = rel.indexOf(marker)
        return when {
            index > 0 -> rel.substring(0, index)
            rel.startsWith("build${File.separator}") -> ""
            else -> rel.substringBefore(File.separator)
        }
    }

    private fun humanSize(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
        bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }

    // ------------------------------------------------------------------
    // 构建历史（files/build_history.json，最近 10 条）
    // ------------------------------------------------------------------

    private fun historyFile(): File = File(appContext.filesDir, HISTORY_FILE)

    private fun recordHistory(
        task: BuildTask,
        durationMs: Long,
        success: Boolean,
        apkPath: String,
        message: String,
    ) {
        val entry = BuildHistoryEntry(
            time = System.currentTimeMillis(),
            projectPath = task.projectPath,
            // 展示任务名（assembleDebug / assemble / npm run build / 打包源码）
            variant = task.label,
            durationMs = durationMs,
            success = success,
            apkPath = apkPath,
            message = message,
        )
        val next = (listOf(entry) + _history.value).take(HISTORY_LIMIT)
        _history.value = next
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { writeHistory(next) }
            }
            // 同步写入「记录」页：构建属于命令之外的「其它操作」流水
            runCatching {
                val taskName = task.label
                val extras = buildString {
                    if (task.clean) append(" clean")
                    task.extraTasks.forEach { append(" ").append(it) }
                }
                val projectName = File(task.projectPath).name
                val status = if (success) "成功" else "失败"
                HistoryStore.add(
                    text = "$taskName$extras · $projectName · $status（${durationMs / 1000}s）",
                    source = HistoryStore.SOURCE_BUILD,
                )
            }
        }
    }

    private fun writeHistory(list: List<BuildHistoryEntry>) {
        val array = JSONArray()
        list.forEach { entry ->
            array.put(
                JSONObject()
                    .put("time", entry.time)
                    .put("projectPath", entry.projectPath)
                    .put("variant", entry.variant)
                    .put("durationMs", entry.durationMs)
                    .put("success", entry.success)
                    .put("apkPath", entry.apkPath)
                    .put("message", entry.message),
            )
        }
        historyFile().writeText(array.toString(), Charsets.UTF_8)
    }

    private fun readHistory(): List<BuildHistoryEntry> {
        val file = historyFile()
        if (!file.exists()) return emptyList()
        return runCatching {
            val array = JSONArray(file.readText(Charsets.UTF_8))
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    add(
                        BuildHistoryEntry(
                            time = obj.optLong("time"),
                            projectPath = obj.optString("projectPath"),
                            variant = obj.optString("variant", "debug"),
                            durationMs = obj.optLong("durationMs"),
                            success = obj.optBoolean("success"),
                            apkPath = obj.optString("apkPath"),
                            message = obj.optString("message"),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    // ------------------------------------------------------------------
    // 正则
    // ------------------------------------------------------------------

    /** Kotlin：`e: file:///path/File.kt:12:5 message` */
    private val RE_KOTLIN =
        Regex("""^[eEwW]:\s*file://(/[^\s:]+):(\d+):(\d+)\s*(.*)$""")

    /** Kotlin（无列号）：`e: file:///path/File.kt:12 message` */
    private val RE_KOTLIN_NO_COL =
        Regex("""^[eEwW]:\s*file://(/[^\s:]+):(\d+)\s*(.*)$""")

    /** Java/资源：`/path/File.java:12: 错误: xxx` */
    private val RE_JAVA =
        Regex("""^(/[^\s:]+\.(?:java|kt|kts|xml|gradle|properties)):(\d+):\s*(?:错误|error|警告|warning)\s*[:：]?\s*(.*)$""")

    /** 相对路径：`app/src/main/java/Foo.java:12: 错误: xxx` */
    private val RE_JAVA_REL =
        Regex("""^([\w][\w./\-]*\.(?:java|kt|kts|xml|gradle|properties)):(\d+):\s*(?:错误|error|警告|warning)\s*[:：]?\s*(.*)$""")

    /** Gradle 任务失败：`Execution failed for task ':app:compileDebugKotlin'` */
    private val RE_TASK =
        Regex("""Execution failed for task '([^']+)'""")

    /**
     * JS / TS / Vue / CSS 错误定位：`src/App.vue:12:5 message`、
     * `./src/main.ts:3:1: ERROR: …`、`at fn (/path/index.js:1:1)`（vite / esbuild / webpack / Node 栈）。
     * 扩展名白名单保证不会误吃 URL（`x.com:8080`）与 Windows 盘符。
     */
    private val RE_JS =
        Regex("""(?:^|\s)((?:\./)?[\w./\\-]+\.(?:mjs|cjs|jsx|tsx|ts|js|vue|scss|less|css|html|json)):(\d+):(\d+)(.*)$""")

    /** 附加 Gradle 任务名校验（默认无附加任务，只有用户显式输入时才生效）。 */
    private fun isSafeTaskName(value: String): Boolean {
        if (value.isBlank() || value.startsWith("-")) return false
        return value.matches(Regex("""^[A-Za-z0-9_.:\-]+$"""))
    }

    /**
     * 解析「高级选项」里的附加 Gradle 任务（空格分隔）。
     * 非法字符/参数（以 `-` 开头的开关）一律丢弃，保证命令行安全。
     */
    fun parseExtraTasks(text: String): List<String> =
        text.split(Regex("""\s+"""))
            .map { it.trim() }
            .filter { isSafeTaskName(it) }
            .distinct()
            .take(5)
}
