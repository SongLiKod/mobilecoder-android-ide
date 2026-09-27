package com.mobilecoder.ide.feature.git

import android.content.Context
import com.mobilecoder.ide.core.nativebridge.GitNative
import com.mobilecoder.ide.core.nativebridge.GitProgressCallback
import com.mobilecoder.ide.core.nativebridge.NativeRuntime
import com.mobilecoder.ide.core.storage.AppStorage
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 冲突某一侧的内容（side: 1=祖先 2=我方 3=对方）。 */
data class ConflictSide(
    val path: String,
    val side: Int,
    /** 文本内容（binary 时为空串）。 */
    val text: String,
    val binary: Boolean,
    val bytes: Int,
) {
    val label: String
        get() = when (side) {
            1 -> "祖先"
            3 -> "对方"
            else -> "我方"
        }
}

/**
 * Git 会话控制器（PRD 2.5 全功能可视化的数据源，进程级单例）。
 *
 * 约定：
 *  - 所有 GitNative/JNI 调用都在 [Dispatchers.IO] + 互斥锁内串行执行（native 全局只有一个仓库句柄）
 *  - UI 通过 `collectAsStateWithLifecycle` 订阅各 StateFlow（回调线程 → StateFlow → 主线程重组）
 *  - 网络操作前注入凭据（HTTPS 账号口令 / SSH 内存私钥），结束后 `clearCredentials()` 清除
 *  - 任何失败都转成中文提示写入 [message]，绝不向调用方抛异常
 */
object GitController {

    private const val MAX_PROGRESS_LOG = 120
    private const val LOG_STEP = 50

    /** scp 风格 SSH 地址：`git@host:path`。 */
    private val SCP_URL = Regex("""^[A-Za-z0-9._-]+@[A-Za-z0-9._-]+:.+""")

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var started = false

    @Volatile
    private var logLimit = LOG_STEP

    // ---------------- 对外状态 ----------------

    private val _repo = MutableStateFlow(GitRepoUiState())
    val repo: StateFlow<GitRepoUiState> = _repo.asStateFlow()

    private val _status = MutableStateFlow<List<GitStatusEntry>>(emptyList())
    val status: StateFlow<List<GitStatusEntry>> = _status.asStateFlow()

    private val _commits = MutableStateFlow<List<GitCommit>>(emptyList())
    val commits: StateFlow<List<GitCommit>> = _commits.asStateFlow()

    private val _logHasMore = MutableStateFlow(false)
    val logHasMore: StateFlow<Boolean> = _logHasMore.asStateFlow()

    private val _branches = MutableStateFlow<List<GitBranch>>(emptyList())
    val branches: StateFlow<List<GitBranch>> = _branches.asStateFlow()

    private val _head = MutableStateFlow<GitHead?>(null)
    val head: StateFlow<GitHead?> = _head.asStateFlow()

    private val _tags = MutableStateFlow<List<GitTag>>(emptyList())
    val tags: StateFlow<List<GitTag>> = _tags.asStateFlow()

    private val _remotes = MutableStateFlow<List<GitRemote>>(emptyList())
    val remotes: StateFlow<List<GitRemote>> = _remotes.asStateFlow()

    private val _conflicts = MutableStateFlow<List<GitConflict>>(emptyList())
    val conflicts: StateFlow<List<GitConflict>> = _conflicts.asStateFlow()

    private val _diffStats = MutableStateFlow<GitDiffStats?>(null)
    val diffStats: StateFlow<GitDiffStats?> = _diffStats.asStateFlow()

    private val _identity = MutableStateFlow(GitIdentity())
    val identity: StateFlow<GitIdentity> = _identity.asStateFlow()

    private val _message = MutableStateFlow<GitMessage?>(null)
    val message: StateFlow<GitMessage?> = _message.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _merging = MutableStateFlow(false)
    val merging: StateFlow<Boolean> = _merging.asStateFlow()

    private val _progress = MutableStateFlow<GitProgress?>(null)
    val progress: StateFlow<GitProgress?> = _progress.asStateFlow()

    private val _progressLog = MutableStateFlow<List<String>>(emptyList())
    val progressLog: StateFlow<List<String>> = _progressLog.asStateFlow()

    private val _ignoreRules = MutableStateFlow<List<String>>(emptyList())
    val ignoreRules: StateFlow<List<String>> = _ignoreRules.asStateFlow()

    // ---------------- 生命周期 ----------------

    /** native 进度回调（可能在任意线程；仅写 StateFlow，由 UI 在主线程订阅）。 */
    private val progressCallback = GitProgressCallback { stage, message, current, total ->
        try {
            val msg = message.orEmpty()
            _progress.value = GitProgress(stage.orEmpty(), msg.trim(), current, total)
            val line = buildString {
                append(stageLabel(stage.orEmpty()))
                if (msg.isNotBlank()) {
                    append("：")
                    append(msg.trim())
                }
                if (total > 0) {
                    append("（").append(current).append('/').append(total).append('）')
                }
            }
            val next = _progressLog.value.toMutableList()
            next.add(line)
            if (next.size > MAX_PROGRESS_LOG) {
                next.removeAt(0)
            }
            _progressLog.value = next
        } catch (_: Throwable) {
            // 进度回调绝不允许影响宿主
        }
    }

    /**
     * 进程启动时调用（app 的 Application.onCreate）：幂等、非阻塞、绝不崩溃。
     * 真正的 JNI 初始化在 IO 协程里完成。
     */
    fun init(context: Context) {
        try {
            if (started) return
            synchronized(this) {
                if (started) return
                appContext = context.applicationContext
                started = true
            }
            ioScope.launch {
                runCatching {
                    val ctx = appContext ?: return@runCatching
                    NativeRuntime.ensureGitReady(ctx)
                    GitNative.registerProgressCallback(progressCallback)
                }
            }
        } catch (_: Throwable) {
            // 契约：init 不允许抛出任何异常
        }
    }

    /** 清除顶部提示。 */
    fun clearMessage() {
        _message.value = null
    }

    // ---------------- 打开 / 刷新 ----------------

    /** 绑定项目路径：关闭旧句柄 → 识别并打开仓库 → 刷新全部数据。 */
    suspend fun bind(projectPath: String) {
        if (projectPath.isBlank()) return
        withRepo(Unit) { bindLocked(projectPath) }
    }

    /** 顶部刷新按钮：已打开则刷新数据，未打开则重新识别目录。 */
    suspend fun refresh() {
        withRepo(Unit) {
            if (!ensureEngine()) return@withRepo
            val path = _repo.value.path
            if (_repo.value.opened) {
                refreshLocked(all = true)
            } else if (path.isNotBlank()) {
                bindLocked(path)
            }
        }
    }

    private suspend fun bindLocked(projectPath: String) {
        _loading.value = true
        try {
            clearRepoData()
            _repo.value = GitRepoUiState(path = projectPath, opening = true)
            if (!ensureEngine()) return
            GitNative.closeRepo()
            if (!openRepoLocked(projectPath)) {
                _repo.value = GitRepoUiState(path = projectPath, isRepo = false, opened = false)
                return
            }
            _repo.value = GitRepoUiState(
                path = projectPath,
                isRepo = true,
                opened = true,
                workdir = runCatching { GitNative.workdirPath() }.getOrDefault(""),
            )
            refreshLocked(all = true)
        } finally {
            _loading.value = false
        }
    }

    /**
     * 打开仓库：优先 `<path>/.git`（避免 libgit2 向上发现到父级仓库），
     * 否则要求打开结果的工作区就是 path 本身。
     */
    private fun openRepoLocked(path: String): Boolean {
        runCatching { GitNative.closeRepo() }
        val dotGit = File(path, ".git")
        val hasDotGit = runCatching { dotGit.exists() }.getOrDefault(false)
        return try {
            if (hasDotGit) {
                if (GitNative.openRepository(dotGit.path)) {
                    true
                } else {
                    GitNative.openRepository(path)
                }
            } else if (GitNative.isRepository(path)) {
                if (GitNative.openRepository(path)) {
                    val wd = runCatching { GitNative.workdirPath() }.getOrDefault("")
                    val same = wd.isBlank() || File(wd).canonicalPath == File(path).canonicalPath
                    if (!same) {
                        runCatching { GitNative.closeRepo() }
                    }
                    same
                } else {
                    false
                }
            } else {
                false
            }
        } catch (_: Throwable) {
            false
        }
    }

    private suspend fun refreshLocked(all: Boolean) {
        _head.value = parseHead(runCatching { GitNative.headInfo() }.getOrDefault(""))
        _status.value = parseStatus(runCatching { GitNative.statusList() }.getOrDefault(""))
        _diffStats.value = parseDiffStats(runCatching { GitNative.diffStats() }.getOrDefault(""))
        _conflicts.value = parseConflicts(runCatching { GitNative.conflicts() }.getOrDefault(""))
        if (_conflicts.value.isNotEmpty()) {
            _merging.value = true
        }
        val wd = runCatching { GitNative.workdirPath() }.getOrDefault("")
        _repo.value = _repo.value.copy(
            head = _head.value,
            workdir = wd.ifBlank { _repo.value.workdir },
            isRepo = _repo.value.isRepo,
            opened = _repo.value.opened,
        )
        if (!all) return

        _commits.value = parseLog(runCatching { GitNative.log(logLimit) }.getOrDefault(""))
        _logHasMore.value = _commits.value.size >= logLimit
        _branches.value = parseBranches(runCatching { GitNative.branches() }.getOrDefault(""))
        _tags.value = parseTags(runCatching { GitNative.tags() }.getOrDefault(""))
        _remotes.value = parseRemotes(runCatching { GitNative.remotes() }.getOrDefault(""))
        _identity.value = loadIdentity()
    }

    private fun clearRepoData() {
        _status.value = emptyList()
        _commits.value = emptyList()
        _branches.value = emptyList()
        _tags.value = emptyList()
        _remotes.value = emptyList()
        _conflicts.value = emptyList()
        _diffStats.value = null
        _head.value = null
        _logHasMore.value = false
        _merging.value = false
        _ignoreRules.value = emptyList()
    }

    // ---------------- 仓库：初始化 / 克隆 ----------------

    /** 初始化当前目录为 Git 仓库。 */
    suspend fun initRepository(path: String): Boolean = withRepo(false) {
        if (!ensureEngine()) return@withRepo false
        if (path.isBlank()) {
            postError("缺少仓库路径")
            return@withRepo false
        }
        GitNative.closeRepo()
        val ok = runCatching { GitNative.initRepository(path) }.getOrDefault(false)
        if (!ok) {
            postError("初始化仓库失败：" + nativeError("未知错误"))
            return@withRepo false
        }
        val opened = openRepoLocked(path)
        _repo.value = GitRepoUiState(
            path = path,
            isRepo = opened,
            opened = opened,
            workdir = if (opened) runCatching { GitNative.workdirPath() }.getOrDefault("") else "",
        )
        if (opened) {
            refreshLocked(all = true)
            postInfo("仓库初始化完成，可以开始提交了")
        }
        opened
    }

    /** 克隆仓库（带凭据与进度），成功后自动打开。 */
    suspend fun clone(url: String, targetPath: String, branch: String): Boolean =
        withRepo(false) {
            withBusy(false) {
                if (!ensureEngine()) return@withBusy false
                if (url.isBlank() || targetPath.isBlank()) {
                    postError("克隆地址与目标目录不能为空")
                    return@withBusy false
                }
                _progressLog.value = emptyList()
                _progress.value = GitProgress("clone", url, -1, -1)
                if (!prepareCredentials(url)) return@withBusy false
                val rc = try {
                    GitNative.clone(url.trim(), targetPath.trim(), branch.trim())
                } finally {
                    runCatching { GitNative.clearCredentials() }
                    _progress.value = null
                }
                if (rc != 0) {
                    postError("克隆失败：" + nativeError("请检查地址与网络"))
                    false
                } else {
                    val opened = openRepoLocked(targetPath)
                    _repo.value = GitRepoUiState(
                        path = targetPath,
                        isRepo = opened,
                        opened = opened,
                        workdir = if (opened) {
                            runCatching { GitNative.workdirPath() }.getOrDefault("")
                        } else {
                            ""
                        },
                    )
                    if (opened) {
                        refreshLocked(all = true)
                        postInfo("克隆完成：" + targetPath)
                    } else {
                        postError("克隆完成，但打开仓库失败")
                    }
                    opened
                }
            }
        }

    // ---------------- 变更：状态 / 暂存 / 提交 ----------------

    suspend fun stage(path: String): Boolean = withRepo(false) {
        if (!ensureOpen()) return@withRepo false
        val ok = runCatching { GitNative.stage(path) }.getOrDefault(false)
        if (ok) {
            refreshLocked(all = false)
        } else {
            postError("暂存失败：" + nativeError("未知错误"))
        }
        ok
    }

    suspend fun stageAll(): Boolean = withRepo(false) {
        if (!ensureOpen()) return@withRepo false
        val ok = runCatching { GitNative.stageAll() }.getOrDefault(false)
        if (ok) {
            refreshLocked(all = false)
            postInfo("已暂存全部变更")
        } else {
            postError("暂存失败：" + nativeError("未知错误"))
        }
        ok
    }

    suspend fun unstage(path: String): Boolean = withRepo(false) {
        if (!ensureOpen()) return@withRepo false
        val ok = runCatching { GitNative.unstage(path) }.getOrDefault(false)
        if (ok) {
            refreshLocked(all = false)
        } else {
            postError("取消暂存失败：" + nativeError("未知错误"))
        }
        ok
    }

    suspend fun unstageAll(): Boolean = withRepo(false) {
        if (!ensureOpen()) return@withRepo false
        val ok = runCatching { GitNative.unstageAll() }.getOrDefault(false)
        if (ok) {
            refreshLocked(all = false)
            postInfo("已取消全部暂存")
        } else {
            postError("取消暂存失败：" + nativeError("未知错误"))
        }
        ok
    }

    /**
     * 提交暂存区。
     *
     * @return 0 成功 / 2 没有已暂存的变更 / -1 失败
     */
    suspend fun commit(message: String): Int = withRepo(-1) {
        if (!ensureOpen()) return@withRepo -1
        if (message.isBlank()) {
            postError("提交信息不能为空")
            return@withRepo -1
        }
        val identity = loadIdentity()
        val rc = try {
            GitNative.commit(message.trim(), identity.name, identity.email)
        } catch (t: Throwable) {
            postError("提交失败：" + (t.message ?: "未知错误"))
            return@withRepo -1
        }
        when (rc) {
            0 -> {
                postInfo("提交成功")
                refreshLocked(all = true)
            }
            2 -> postInfo("没有已暂存的变更，请先暂存文件")
            else -> postError("提交失败：" + nativeError("未知错误"))
        }
        rc
    }

    /** 读取指定文件的差异（已解析）。 */
    suspend fun fileDiff(path: String, staged: Boolean): DiffFilePatch? = withRepo(null) {
        if (!ensureOpen()) return@withRepo null
        val patch = runCatching { GitNative.diffPatch(staged) }.getOrDefault("")
        if (patch.isBlank()) return@withRepo null
        DiffParser.forPath(patch, path)
    }

    // ---------------- 历史 ----------------

    /** 加载更多提交。 */
    suspend fun loadMoreLog() {
        withRepo(Unit) {
            if (!ensureOpen()) return@withRepo
            logLimit += LOG_STEP
            _commits.value = parseLog(runCatching { GitNative.log(logLimit) }.getOrDefault(""))
            _logHasMore.value = _commits.value.size >= logLimit
        }
    }

    // ---------------- 分支 ----------------

    suspend fun createBranch(name: String): Boolean = withRepo(false) {
        if (!ensureOpen()) return@withRepo false
        if (name.isBlank()) {
            postError("分支名不能为空")
            return@withRepo false
        }
        val ok = runCatching { GitNative.createBranch(name.trim()) }.getOrDefault(false)
        if (ok) {
            refreshLocked(all = true)
            postInfo("已创建分支 " + name.trim())
        } else {
            postError("创建分支失败：" + nativeError("分支可能已存在"))
        }
        ok
    }

    /** @return 0 成功 / 2 已在该分支 / -1 失败 */
    suspend fun checkoutBranch(name: String): Int = withRepo(-1) {
        if (!ensureOpen()) return@withRepo -1
        val rc = try {
            GitNative.checkoutBranch(name)
        } catch (t: Throwable) {
            postError("切换分支失败：" + (t.message ?: "未知错误"))
            return@withRepo -1
        }
        when (rc) {
            0 -> {
                postInfo("已切换到分支 $name")
                refreshLocked(all = true)
            }
            2 -> postInfo("已经在分支 $name")
            else -> postError("切换分支失败：" + nativeError("工作区可能有冲突改动"))
        }
        rc
    }

    suspend fun deleteBranch(name: String): Boolean = withRepo(false) {
        if (!ensureOpen()) return@withRepo false
        val ok = runCatching { GitNative.deleteBranch(name) }.getOrDefault(false)
        if (ok) {
            refreshLocked(all = true)
            postInfo("已删除分支 $name")
        } else {
            postError(nativeError("删除分支失败（当前分支不可删除）"))
        }
        ok
    }

    /**
     * 合并分支。
     *
     * @return 0 成功 / 1 有冲突 / 2 已是最新 / -1 失败
     */
    suspend fun merge(name: String): Int = withRepo(-1) {
        if (!ensureOpen()) return@withRepo -1
        mergeLocked(name)
    }

    /** 放弃合并。 @return 0 成功 / -1 失败 */
    suspend fun mergeAbort(): Int = withRepo(-1) {
        if (!ensureOpen()) return@withRepo -1
        val rc = try {
            GitNative.mergeAbort()
        } catch (t: Throwable) {
            postError("中止合并失败：" + (t.message ?: "未知错误"))
            return@withRepo -1
        }
        if (rc == 0) {
            _merging.value = false
            postInfo("已中止合并")
            refreshLocked(all = true)
        } else {
            postError("中止合并失败：" + nativeError("未知错误"))
        }
        rc
    }

    /** 冲突全部解决后生成合并提交。 @return 0 成功 / -1 失败 */
    suspend fun finishMerge(): Int = withRepo(-1) {
        if (!ensureOpen()) return@withRepo -1
        val rc = try {
            GitNative.finishMerge()
        } catch (t: Throwable) {
            postError("完成合并失败：" + (t.message ?: "未知错误"))
            return@withRepo -1
        }
        if (rc == 0) {
            _merging.value = false
            postInfo("合并完成，已生成合并提交")
            refreshLocked(all = true)
        } else {
            postError("完成合并失败：" + nativeError("可能仍有未解决的冲突"))
        }
        rc
    }

    // ---------------- 冲突 ----------------

    /** 读取冲突三方内容（字节 → 文本，二进制识别）。 */
    suspend fun conflictSide(path: String, side: Int): ConflictSide? = withRepo(null) {
        if (!ensureOpen()) return@withRepo null
        val bytes = try {
            GitNative.conflictContent(path, side)
        } catch (t: Throwable) {
            postError("读取冲突内容失败：" + (t.message ?: "未知错误"))
            null
        } ?: return@withRepo null

        val probe = if (bytes.size > 4096) bytes.copyOf(4096) else bytes
        val binary = probe.any { it == 0.toByte() }
        ConflictSide(
            path = path,
            side = side,
            text = if (binary) "" else String(bytes, Charsets.UTF_8),
            binary = binary,
            bytes = bytes.size,
        )
    }

    /** 采用某一侧（祖先 / 我方 / 对方）作为解决方案。 */
    suspend fun resolveSide(path: String, side: Int): Boolean = withRepo(false) {
        if (!ensureOpen()) return@withRepo false
        val ok = runCatching { GitNative.resolveConflict(path, side) }.getOrDefault(false)
        afterResolved(ok, path)
        ok
    }

    /** 手动编辑后的解决内容。 */
    suspend fun resolveText(path: String, text: String): Boolean = withRepo(false) {
        if (!ensureOpen()) return@withRepo false
        val bytes = text.toByteArray(Charsets.UTF_8)
        val ok = runCatching { GitNative.resolveConflictContent(path, bytes) }
            .getOrDefault(false)
        afterResolved(ok, path)
        ok
    }

    private suspend fun afterResolved(ok: Boolean, path: String) {
        if (ok) {
            refreshLocked(all = true)
            _conflicts.value = parseConflicts(runCatching { GitNative.conflicts() }.getOrDefault(""))
            postInfo(
                if (_conflicts.value.isEmpty()) {
                    "冲突已全部解决，可以点「完成合并」"
                } else {
                    "已解决：$path（剩余 ${_conflicts.value.size} 个）"
                },
            )
        } else {
            postError("解决冲突失败：" + nativeError("未知错误"))
        }
    }

    // ---------------- 标签 ----------------

    suspend fun createTag(name: String, message: String): Boolean = withRepo(false) {
        if (!ensureOpen()) return@withRepo false
        if (name.isBlank()) {
            postError("标签名不能为空")
            return@withRepo false
        }
        val ok = runCatching { GitNative.createTag(name.trim(), message.trim()) }
            .getOrDefault(false)
        if (ok) {
            refreshLocked(all = true)
            postInfo("已创建标签 " + name.trim())
        } else {
            postError("创建标签失败：" + nativeError("标签可能已存在"))
        }
        ok
    }

    suspend fun deleteTag(name: String): Boolean = withRepo(false) {
        if (!ensureOpen()) return@withRepo false
        val ok = runCatching { GitNative.deleteTag(name) }.getOrDefault(false)
        if (ok) {
            refreshLocked(all = true)
            postInfo("已删除标签 $name")
        } else {
            postError("删除标签失败：" + nativeError("未知错误"))
        }
        ok
    }

    // ---------------- 远程 ----------------

    suspend fun addRemote(name: String, url: String): Boolean = withRepo(false) {
        if (!ensureOpen()) return@withRepo false
        if (name.isBlank() || url.isBlank()) {
            postError("远程名称与地址不能为空")
            return@withRepo false
        }
        val ok = runCatching { GitNative.addRemote(name.trim(), url.trim()) }
            .getOrDefault(false)
        if (ok) {
            refreshLocked(all = true)
            postInfo("已添加远程仓库 " + name.trim())
        } else {
            postError("添加远程仓库失败：" + nativeError("名称可能已存在"))
        }
        ok
    }

    suspend fun removeRemote(name: String): Boolean = withRepo(false) {
        if (!ensureOpen()) return@withRepo false
        val ok = runCatching { GitNative.removeRemote(name) }.getOrDefault(false)
        if (ok) {
            refreshLocked(all = true)
            postInfo("已删除远程仓库 $name")
        } else {
            postError("删除远程仓库失败：" + nativeError("未知错误"))
        }
        ok
    }

    suspend fun setRemoteUrl(name: String, url: String): Boolean = withRepo(false) {
        if (!ensureOpen()) return@withRepo false
        if (url.isBlank()) {
            postError("地址不能为空")
            return@withRepo false
        }
        val ok = runCatching { GitNative.setRemoteUrl(name, url.trim()) }.getOrDefault(false)
        if (ok) {
            refreshLocked(all = true)
            postInfo("已修改远程地址：" + name.trim())
        } else {
            postError("修改远程地址失败：" + nativeError("未知错误"))
        }
        ok
    }

    /** @return 0 成功 / -1 失败 */
    suspend fun fetch(remote: String): Int = withRepo(-1) {
        withBusy(-1) { fetchLocked(remote) }
    }

    /** 拉取 = fetch + merge(当前分支)。 @return 0 成功 / 1 冲突 / 2 已是最新 / -1 失败 */
    suspend fun pull(remote: String): Int = withRepo(-1) {
        withBusy(-1) {
            if (!ensureEngine() || !ensureOpen()) return@withBusy -1
            val rc = fetchLocked(remote)
            if (rc != 0) return@withBusy rc
            val branchName = _head.value?.branch.orEmpty()
            if (branchName.isBlank()) {
                postInfo("拉取完成（当前没有可合并的分支）")
                refreshLocked(all = true)
                return@withBusy 0
            }
            val merged = mergeLocked(branchName)
            if (merged == 2) {
                postInfo("拉取完成：已是最新，本地分支没有需要合并的提交")
            }
            merged
        }
    }

    /** @return 0 成功 / -1 失败 */
    suspend fun push(remote: String, branch: String): Int = withRepo(-1) {
        withBusy(-1) {
            if (!ensureEngine() || !ensureOpen()) return@withBusy -1
            val url = remoteUrlLocked(remote) ?: run {
                postError("远程仓库不存在：$remote")
                return@withBusy -1
            }
            if (!prepareCredentials(url)) return@withBusy -1
            _progressLog.value = emptyList()
            val rc = try {
                GitNative.push(remote, branch)
            } finally {
                runCatching { GitNative.clearCredentials() }
                _progress.value = null
            }
            if (rc == 0) {
                refreshLocked(all = true)
                postInfo("推送成功：$branch → $remote")
            } else {
                postError("推送失败：" + nativeError("请检查凭据与权限"))
            }
            rc
        }
    }

    // ---------------- 身份 / 忽略规则 ----------------

    suspend fun saveIdentity(name: String, email: String) {
        withRepo(Unit) {
            val n = name.trim()
            val e = email.trim()
            if (n.isBlank() || e.isBlank()) {
                postError("姓名与邮箱不能为空")
                return@withRepo
            }
            runCatching { AppStorage.preferences.setGitUserName(n) }
            runCatching { AppStorage.preferences.setGitUserEmail(e) }
            _identity.value = GitIdentity(n, e)
            postInfo("Git 身份已保存（提交时使用）")
        }
    }

    /** 添加忽略规则（native 层为内存态，仅本次会话生效）。 */
    suspend fun addIgnoreRule(rule: String): Boolean = withRepo(false) {
        if (!ensureOpen()) return@withRepo false
        if (rule.isBlank()) {
            postError("忽略规则不能为空")
            return@withRepo false
        }
        val ok = runCatching { GitNative.addIgnoreRule(rule.trim()) }.getOrDefault(false)
        if (ok) {
            _ignoreRules.value = _ignoreRules.value + rule.trim()
            refreshLocked(all = false)
            postInfo("已添加忽略规则（仅本次会话生效）")
        } else {
            postError("添加忽略规则失败：" + nativeError("未知错误"))
        }
        ok
    }

    suspend fun clearIgnoreRules() {
        withRepo(Unit) {
            if (!ensureOpen()) return@withRepo
            runCatching { GitNative.clearIgnoreRules() }
            _ignoreRules.value = emptyList()
            refreshLocked(all = false)
            postInfo("已清空忽略规则")
        }
    }

    // ---------------- 内部工具 ----------------

    /** native 层只维护一个仓库句柄，所有调用串行化。 */
    private suspend fun <T> withRepo(fallback: T, block: suspend () -> T): T =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                try {
                    block()
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    postError("操作失败：" + (t.message ?: t.javaClass.simpleName))
                    fallback
                }
            }
        }

    /** 网络等耗时操作的 busy 标记（不加锁，须在 [withRepo] 内使用）。 */
    private suspend fun <T> withBusy(fallback: T, block: suspend () -> T): T {
        _busy.value = true
        try {
            return block()
        } finally {
            _busy.value = false
            _progress.value = null
        }
    }

    private fun ensureEngine(): Boolean {
        val ctx = appContext
        if (ctx == null) {
            postError("Git 引擎尚未初始化")
            return false
        }
        val ok = runCatching { NativeRuntime.ensureGitReady(ctx) }.getOrDefault(false)
        if (!ok) {
            postError("Git 引擎初始化失败：" + nativeError("未知错误"))
        }
        return ok
    }

    private fun ensureOpen(): Boolean {
        if (_repo.value.opened) return true
        postError("尚未打开仓库")
        return false
    }

    private suspend fun mergeLocked(name: String): Int {
        if (!ensureEngine()) return -1
        if (name.isBlank()) {
            postError("分支名不能为空")
            return -1
        }
        val rc = try {
            GitNative.merge(name)
        } catch (t: Throwable) {
            postError("合并失败：" + (t.message ?: "未知错误"))
            return -1
        }
        when (rc) {
            0 -> {
                _merging.value = false
                postInfo("合并完成：$name")
            }
            1 -> {
                _merging.value = true
                postInfo("合并产生冲突，请到「冲突」页处理")
            }
            2 -> postInfo("已是最新，无需合并")
            else -> postError("合并失败：" + nativeError("未知错误"))
        }
        refreshLocked(all = true)
        return rc
    }

    private suspend fun fetchLocked(remote: String): Int {
        if (!ensureEngine()) return -1
        if (!ensureOpen()) return -1
        if (remote.isBlank()) {
            postError("请先选择远程仓库")
            return -1
        }
        val url = remoteUrlLocked(remote) ?: run {
            postError("远程仓库不存在：$remote")
            return -1
        }
        if (!prepareCredentials(url)) return -1
        _progressLog.value = emptyList()
        val rc = try {
            GitNative.fetch(remote)
        } finally {
            runCatching { GitNative.clearCredentials() }
            _progress.value = null
        }
        if (rc == 0) {
            refreshLocked(all = true)
            postInfo("拉取完成：$remote")
        } else {
            postError("拉取失败：" + nativeError("请检查地址、网络与凭据"))
        }
        return rc
    }

    /** 网络操作前注入凭据；返回 false 表示中止（已给出中文提示）。 */
    private suspend fun prepareCredentials(url: String): Boolean {
        val lower = url.lowercase()
        return when {
            lower.startsWith("http://") || lower.startsWith("https://") -> {
                val prefs = AppStorage.preferences
                val user = runCatching { prefs.httpsUser() }.getOrDefault("")
                val pass = runCatching { prefs.httpsPassword() }.getOrDefault("")
                runCatching { GitNative.setCredentials(user, pass, "", "", "", "") }
                true
            }

            lower.startsWith("ssh://") || SCP_URL.matches(url.trim()) -> {
                val material = runCatching { AppStorage.sshKeys.activeMaterial() }.getOrNull()
                if (material == null) {
                    postError("尚未配置 SSH 密钥：请先到「SSH」页生成并激活密钥后重试")
                    false
                } else {
                    runCatching {
                        GitNative.setCredentials(
                            "",
                            "",
                            material.username,
                            material.privateKeyPem,
                            material.publicKey,
                            material.passphrase,
                        )
                    }
                    true
                }
            }

            else -> {
                // 本地路径 / file:// 等无需凭据
                runCatching { GitNative.setCredentials("", "", "", "", "", "") }
                true
            }
        }
    }

    private fun remoteUrlLocked(name: String): String? {
        _remotes.value.firstOrNull { it.name == name }?.let { return it.url }
        val list = parseRemotes(runCatching { GitNative.remotes() }.getOrDefault(""))
        _remotes.value = list
        return list.firstOrNull { it.name == name }?.url
    }

    private suspend fun loadIdentity(): GitIdentity = GitIdentity(
        name = runCatching { AppStorage.preferences.gitUserName() }
            .getOrDefault("MobileCoder"),
        email = runCatching { AppStorage.preferences.gitUserEmail() }
            .getOrDefault("mobilecoder@local"),
    )

    private fun nativeError(fallback: String): String = runCatching {
        GitNative.lastError().trim()
    }.getOrDefault("").ifBlank { fallback }

    private fun postError(text: String) {
        _message.value = GitMessage(text, true)
    }

    private fun postInfo(text: String) {
        _message.value = GitMessage(text, false)
    }
}
