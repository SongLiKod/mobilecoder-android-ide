package com.mobilecoder.ide.feature.git

import com.mobilecoder.ide.core.common.cli.CliCommand
import com.mobilecoder.ide.core.common.cli.OpencodeCli
import com.mobilecoder.ide.core.nativebridge.GitNative
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/**
 * 终端 `git` 命令（PRD 2.3「内置完整终端」/ 2.5「全功能 Git」）。
 *
 * Android 设备上没有 `git` 可执行文件，这里用进程内 libgit2（[GitNative]）实现常用
 * 子命令，输出格式尽量对齐 git CLI；`feature_terminal` 的拦截器识别到 `git …` 后
 * 交给 CLI 引擎分发到本类。
 *
 * 约定：
 *  - 全部 native 调用经 [GitController.runExclusiveAt] / [GitController.runExclusive]
 *    与 Git 页共用同一把互斥锁（native 只有一个仓库句柄），结束时还原句柄；
 *  - 写操作完成后由 [repoOp] 在**锁外**调用 [GitController.syncUiIfSame] 让 Git 页
 *    数据保持同步（该方法内部会重新加锁，绝不能在 `runExclusiveAt` 持锁期间调用）；
 *  - 退出码对齐 git：0 成功 / 1 常规失败 / 128 fatal（不是仓库）。
 */
object GitCli {

    /** `git status` 在非仓库目录下的退出码（git 惯例 128）。 */
    private const val NOT_REPO = 128

    private const val VERSION =
        "git version 2.43.0-compat (MobileCoder 进程内 libgit2 实现，非独立 git 二进制)"

    /** 子命令 → 一句话说明（`git help` 输出用）。 */
    private val COMMANDS: LinkedHashMap<String, String> = linkedMapOf(
        "status" to "工作区与索引状态（-s 短格式）",
        "add" to "暂存变更（路径 / . / -A）",
        "restore" to "还原工作区为暂存区内容（--staged 取消暂存）",
        "reset" to "取消暂存（不支持 --hard）",
        "commit" to "提交暂存区（-m 信息，-a 先暂存全部）",
        "diff" to "差异（--staged 暂存区，--stat 统计）",
        "log" to "提交历史（-n 条数，--oneline）",
        "branch" to "分支：列出 / 新建 / -d 删除",
        "checkout" to "切换分支（-b 新建并切换）",
        "switch" to "同 checkout",
        "remote" to "远程：-v / add / remove / set-url",
        "fetch" to "抓取远程",
        "pull" to "抓取并合并当前分支",
        "push" to "推送当前分支",
        "merge" to "合并分支（--abort 放弃）",
        "tag" to "标签：列出 / 新建 / -d 删除",
        "init" to "初始化当前目录为仓库",
        "clone" to "克隆仓库（-b 分支，目标目录可选）",
        "rev-parse" to "--show-toplevel / --abbrev-ref / HEAD",
        "config" to "user.name / user.email 读写",
        "help" to "本帮助",
    )

    /** 终端未知子命令的兜底提示。 */
    private val UNSUPPORTED = setOf(
        "rebase", "stash", "cherry-pick", "bisect", "submodule", "blame", "show",
        "clean", "grep", "am", "revert", "worktree", "notes", "bundle",
    )

    /**
     * 注册进 CLI 引擎（`terminalIntercept = true`：终端输入 `git …` 由拦截器接管，
     * CLI 面板也会出现「版本控制」分组的 git 命令）。由 `GitController.init` 调用。
     */
    fun register() {
        OpencodeCli.register(
            CliCommand(
                name = "git",
                summary = "Git 子命令（进程内 libgit2，Android 无 git 二进制）",
                usage = "git <status|log|add|commit|...>",
                group = "版本控制",
                terminalIntercept = true,
            ) { args, cwd, emit -> execute(args, cwd, emit) },
        )
        // 用户点「停止」/ Ctrl+C 时，同时中止可能阻塞在 native 里的 fetch / push
        OpencodeCli.onCancel = { GitNative.cancelNetwork() }
    }

    // ------------------------------------------------------------------
    // 分发
    // ------------------------------------------------------------------

    /** 执行一行 git 命令（[args] 为 `git` 之后的参数，已完成引号切分）。 */
    suspend fun execute(args: List<String>, cwd: File, emit: (String) -> Unit): Int {
        val sub = args.firstOrNull()
        val rest = args.drop(1)
        return when (sub) {
            null -> {
                printHelp(emit)
                0
            }

            "--version", "version" -> {
                emit(VERSION)
                0
            }

            "--help", "-h", "help" -> {
                printHelp(emit)
                0
            }

            "status" -> cmdStatus(rest, cwd, emit)
            "add" -> cmdAdd(rest, cwd, emit)
            "restore" -> cmdRestore(rest, cwd, emit)
            "reset" -> cmdReset(rest, cwd, emit)
            "commit" -> cmdCommit(rest, cwd, emit)
            "diff" -> cmdDiff(rest, cwd, emit)
            "log" -> cmdLog(rest, cwd, emit)
            "branch" -> cmdBranch(rest, cwd, emit)
            "checkout" -> cmdCheckout(rest, cwd, emit)
            "switch" -> cmdCheckout(rest, cwd, emit)
            "remote" -> cmdRemote(rest, cwd, emit)
            "fetch" -> cmdFetch(rest, cwd, emit)
            "pull" -> cmdPull(rest, cwd, emit)
            "push" -> cmdPush(rest, cwd, emit)
            "merge" -> cmdMerge(rest, cwd, emit)
            "tag" -> cmdTag(rest, cwd, emit)
            "init" -> cmdInit(rest, cwd, emit)
            "clone" -> cmdClone(rest, cwd, emit)
            "rev-parse" -> cmdRevParse(rest, cwd, emit)
            "config" -> cmdConfig(rest, cwd, emit)

            else -> unknown(sub, emit)
        }
    }

    private fun printHelp(emit: (String) -> Unit) {
        emit("usage: git <command> [<args>]")
        emit("")
        emit("可用子命令（进程内 libgit2 实现）：")
        COMMANDS.forEach { (name, desc) -> emit("  %-9s %s".format(name, desc)) }
        emit("")
        emit("说明：设备上没有 git 可执行文件，输出与标准 git 对齐，子命令集有限；")
        emit("      未实现的子命令会给出提示。任意子命令后加 --help 等价于本帮助。")
    }

    private fun unknown(sub: String, emit: (String) -> Unit): Int {
        if (sub in UNSUPPORTED) {
            emit("git: '$sub' 本终端尚未实现（进程内 libgit2 子命令集有限）")
            emit("可视化操作请到「Git」页；可用子命令见 `git help`")
            return 1
        }
        emit("git: '$sub' is not a git command. See 'git help'.")
        emit("已支持：" + COMMANDS.keys.joinToString(" "))
        return 1
    }

    // ------------------------------------------------------------------
    // 仓库内执行 / 通用工具
    // ------------------------------------------------------------------

    /**
     * 在 [cwd] 所属仓库（含父目录）内执行 [block]；非仓库时输出 fatal 并返回 128。
     *
     * @param syncAfter 写操作完成后是否刷新 Git 页数据。**刷新必须在锁外执行**：
     *   [GitController.syncUiIfSame] 内部会再次加锁，若在 [GitController.runExclusiveAt]
     *   持锁期间调用就是自己等自己（kotlinx `Mutex` 不可重入），会永久死锁——
     *   终端表现就是命令一直转圈且无法停止。
     */
    private suspend fun repoOp(
        cwd: File,
        emit: (String) -> Unit,
        syncAfter: Boolean = false,
        block: suspend (root: String) -> Int,
    ): Int {
        val root = repoRootOf(cwd)?.path
        val rc = GitController.runExclusiveAt(cwd.path, NOT_REPO) { opened ->
            when {
                root == null -> {
                    emit("fatal: not a git repository (or any of the parent directories): .git")
                    NOT_REPO
                }

                !opened -> {
                    val err = runCatching { GitNative.lastError() }.getOrDefault("").trim()
                    emit("fatal: 无法打开仓库：" + err.ifBlank { "Git 引擎未就绪" })
                    NOT_REPO
                }

                else -> block(root)
            }
        }
        // 仓库句柄已释放、互斥锁已归还，此时刷新 Git 页才是安全的
        if (syncAfter && rc != NOT_REPO && root != null) sync(root)
        return rc
    }

    /** 从 [start] 逐级向上找 `.git`，返回仓库根；找不到返回 null。 */
    private fun repoRootOf(start: File): File? {
        var dir: File? = runCatching { start.canonicalFile }.getOrNull() ?: start
        while (dir != null) {
            if (File(dir, ".git").exists()) return dir
            dir = dir.parentFile
        }
        return null
    }

    /** 写操作后同步 Git 页（同一仓库才刷新）。 */
    private suspend fun sync(root: String) {
        runCatching { GitController.syncUiIfSame(root) }
    }

    /** 路径：相对路径基于 [root] 解析。 */
    private fun resolve(root: String, path: String): File =
        if (path.startsWith("/")) File(path) else File(root, path)

    private fun lastError(): String =
        runCatching { GitNative.lastError() }.getOrDefault("").trim().ifBlank { "未知错误" }

    private fun positionals(args: List<String>): List<String> = args.filter { !it.startsWith("-") }

    /** 解析 `-m` 消息与 `-a` 标志（commit 用）。 */
    private fun parseCommitFlags(args: List<String>): Pair<String, Boolean> {
        var message = ""
        var all = false
        var i = 0
        while (i < args.size) {
            val a = args[i]
            when {
                a == "-m" || a == "--message" -> {
                    message = args.getOrNull(i + 1) ?: ""
                    i += 2
                }

                a == "-a" || a == "--all" -> {
                    all = true
                    i++
                }

                a == "-am" || a == "-ma" -> {
                    all = true
                    message = args.getOrNull(i + 1) ?: ""
                    i += 2
                }

                a.startsWith("-m") && a.length > 2 -> {
                    message = a.substring(2)
                    i++
                }

                a.startsWith("-a") && a.length > 2 -> {
                    all = true
                    message = a.substring(2).removePrefix("m")
                    i++
                }

                else -> i++
            }
        }
        return message to all
    }

    private fun formatDate(timeSeconds: Long): String =
        runCatching {
            SimpleDateFormat("EEE MMM dd HH:mm:ss yyyy Z", Locale.ENGLISH)
                .format(Date(timeSeconds * 1000))
        }.getOrDefault("")

    /** `new file:` / `modified:` / `deleted:` 标签（对齐 git 的 12 列宽度）。 */
    private fun changeLabel(kind: GitStatusKind): String = when (kind) {
        GitStatusKind.NEW -> "new file"
        GitStatusKind.MODIFIED -> "modified"
        GitStatusKind.DELETED -> "deleted"
        GitStatusKind.RENAMED -> "renamed"
        GitStatusKind.TYPECHANGE -> "typechange"
        GitStatusKind.CONFLICTED -> "conflicted"
        else -> kind.label
    }

    // ------------------------------------------------------------------
    // status / add / restore / reset / commit
    // ------------------------------------------------------------------

    private suspend fun cmdStatus(args: List<String>, cwd: File, emit: (String) -> Unit): Int =
        repoOp(cwd, emit) { root ->
            val short = args.any { it == "-s" || it == "--short" }
            val entries = parseStatus(GitNative.statusList())
            if (short) {
                entries.forEach { emit("${it.code} ${it.path}") }
                return@repoOp 0
            }

            val head = parseHead(GitNative.headInfo())
            when {
                head == null -> emit("Not a git repository?!")
                head.detached -> emit("HEAD detached at ${head.branch.ifBlank { "unknown" }}")
                head.unborn -> emit("On branch ${head.branch.ifBlank { "main" }}\nNo commits yet")
                else -> emit("On branch ${head.branch}")
            }

            val current = parseBranches(GitNative.branches()).firstOrNull { it.isHead }
            if (current != null && current.upstream.isNotBlank()) {
                emit("")
                when {
                    current.ahead > 0 && current.behind > 0 -> {
                        emit("Your branch and '${current.upstream}' have diverged,")
                        emit("and have ${current.ahead} and ${current.behind} different commits each, respectively.")
                    }

                    current.ahead > 0 ->
                        emit("Your branch is ahead of '${current.upstream}' by ${current.ahead} commits.")

                    current.behind > 0 ->
                        emit("Your branch is behind '${current.upstream}' by ${current.behind} commits.")

                    else -> emit("Your branch is up to date with '${current.upstream}'.")
                }
            }

            val conflicted = entries.filter { it.inGroup(GitChangeGroup.CONFLICTED) }
            val staged = entries.filter { it.inGroup(GitChangeGroup.STAGED) }
            val unstaged = entries.filter { it.inGroup(GitChangeGroup.UNSTAGED) }
            val untracked = entries.filter { it.inGroup(GitChangeGroup.UNTRACKED) }

            if (conflicted.isNotEmpty()) {
                emit("")
                emit("You have unmerged paths.")
                emit("  (use \"git merge <branch>\" to finish merging)")
                emit("")
                emit("Unmerged paths:")
                emit("  (use \"git add <file>...\" to mark resolution)")
                conflicted.forEach { emit("\t${changeLabel(it.worktree).padEnd(9)}   ${it.path}") }
            }
            if (staged.isNotEmpty()) {
                emit("")
                emit("Changes to be committed:")
                emit("  (use \"git restore --staged <file>...\" to unstage)")
                staged.forEach { emit("\t${changeLabel(it.index).padEnd(9)}   ${it.path}") }
            }
            if (unstaged.isNotEmpty()) {
                emit("")
                emit("Changes not staged for commit:")
                emit("  (use \"git add <file>...\" to update what will be committed)")
                emit("  (use \"git restore <file>...\" to discard changes in working directory)")
                unstaged.forEach { emit("\t${changeLabel(it.worktree).padEnd(9)}   ${it.path}") }
            }
            if (untracked.isNotEmpty()) {
                emit("")
                emit("Untracked files:")
                emit("  (use \"git add <file>...\" to include in what will be committed)")
                untracked.forEach { emit("\t${it.path}") }
            }

            when {
                conflicted.isEmpty() && staged.isEmpty() &&
                    unstaged.isEmpty() && untracked.isEmpty() ->
                    emit("nothing to commit, working tree clean")

                staged.isEmpty() ->
                    emit("no changes added to commit (use \"git add\" and/or \"git commit -a\")")

                else -> emit("nothing added to commit but untracked files present (use \"git add\" to track)")
            }
            0
        }

    private suspend fun cmdAdd(args: List<String>, cwd: File, emit: (String) -> Unit): Int =
        repoOp(cwd, emit, syncAfter = true) { root ->
            val paths = positionals(args)
            val all = args.any { it == "-A" || it == "--all" } ||
                paths.isEmpty() || paths.contains(".")
            if (all) {
                val ok = runCatching { GitNative.stageAll() }.getOrDefault(false)
                if (!ok) {
                    emit("error: 暂存失败：" + lastError())
                    return@repoOp 1
                }
                return@repoOp 0
            }
            var failed = 0
            paths.forEach { p ->
                val target = resolve(root, p)
                val files = if (target.isDirectory) {
                    target.walkTopDown()
                        .filter { it.isFile && it.name != ".git" && !it.path.contains("${File.separator}.git${File.separator}") }
                        .toList()
                } else {
                    listOf(target)
                }
                files.forEach { file ->
                    val rel = runCatching { file.relativeTo(File(root)).path }
                        .getOrElse {
                            emit("error: 路径不在仓库内：${file.path}")
                            failed++
                            return@forEach
                        }
                    val ok = runCatching { GitNative.stage(rel) }.getOrDefault(false)
                    if (!ok) {
                        emit("error: pathspec '$p' did not match any files known to git (${lastError()})")
                        failed++
                    }
                }
            }
            if (failed == 0) 0 else 1
        }

    private suspend fun cmdRestore(args: List<String>, cwd: File, emit: (String) -> Unit): Int {
        // `--staged` = 取消暂存（等价 `git reset HEAD -- <路径>`）
        if (args.any { it == "--staged" }) return cmdReset(args, cwd, emit)
        if (args.any { it == "--source" || it == "-S" || it.startsWith("--source=") }) {
            emit("error: 仅支持 `git restore <路径>…` 与 `git restore --staged <路径>…`")
            return 1
        }
        val paths = positionals(args)
        if (paths.isEmpty()) {
            emit("usage: git restore [--staged] <路径>…")
            return 1
        }
        return repoOp(cwd, emit, syncAfter = true) { root ->
            var failed = 0
            paths.forEach { p ->
                val rel = relPathOf(root, resolve(root, p))
                if (rel == null) {
                    emit("error: 路径不在仓库内：$p")
                    failed++
                    return@forEach
                }
                val ok = runCatching { GitNative.restoreWorktree(rel) }.getOrDefault(false)
                if (!ok) {
                    emit("error: 还原失败 $p：" + lastError())
                    failed++
                }
            }
            if (failed == 0) 0 else 1
        }
    }

    private suspend fun cmdReset(args: List<String>, cwd: File, emit: (String) -> Unit): Int {
        if (args.any { it == "--hard" || it == "--keep" }) {
            emit("error: 不支持 `git reset --hard`（会丢弃全部工作区改动）")
            emit("丢弃单个文件的改动请用 `git restore <路径>`，或到「Git」页逐文件还原")
            return 1
        }
        return repoOp(cwd, emit, syncAfter = true) { root ->
            val paths = positionals(args)
            val ok = if (paths.isEmpty()) {
                runCatching { GitNative.unstageAll() }.getOrDefault(false)
            } else {
                paths.all { runCatching { GitNative.unstage(it) }.getOrDefault(false) }
            }
            if (!ok) {
                emit("error: 取消暂存失败：" + lastError())
                return@repoOp 1
            }
            0
        }
    }

    private suspend fun cmdCommit(args: List<String>, cwd: File, emit: (String) -> Unit): Int =
        repoOp(cwd, emit, syncAfter = true) { root ->
            val (message, all) = parseCommitFlags(args)
            if (all) runCatching { GitNative.stageAll() }
            if (message.isBlank()) {
                emit("Aborting commit due to empty commit message.")
                return@repoOp 1
            }
            val identity = GitController.currentIdentity()
            val rc = try {
                GitNative.commit(message, identity.name, identity.email)
            } catch (t: Throwable) {
                emit("error: 提交失败：" + (t.message ?: "未知错误"))
                return@repoOp 1
            }
            when (rc) {
                0 -> {
                    val branch = parseHead(GitNative.headInfo())?.branch.orEmpty().ifBlank { "main" }
                    val head = parseLog(GitNative.log(1)).firstOrNull()
                    val short = head?.shortOid?.take(7) ?: ""
                    emit("[$branch${if (short.isEmpty()) "" else " $short"}] $message")
                    0
                }

                2 -> {
                    emit("nothing added to commit (use \"git add\")")
                    1
                }

                else -> {
                    emit("error: 提交失败：" + lastError())
                    1
                }
            }
        }

    // ------------------------------------------------------------------
    // diff / log
    // ------------------------------------------------------------------

    private suspend fun cmdDiff(args: List<String>, cwd: File, emit: (String) -> Unit): Int =
        repoOp(cwd, emit) {
            if (args.any { it == "--stat" }) {
                val stats = parseDiffStats(GitNative.diffStats())
                if (stats == null || stats.isEmpty) return@repoOp 0
                val files = stats.added + stats.deleted + stats.modified
                val tail = StringBuilder()
                if (stats.linesAdd > 0) tail.append(" +").append(stats.linesAdd).append(" insertions(+)")
                if (stats.linesDel > 0) tail.append(" -").append(stats.linesDel).append(" deletions(-)")
                emit("$files file(s) changed,$tail")
                return@repoOp 0
            }
            val staged = args.any { it == "--staged" || it == "--cached" }
            val patch = runCatching { GitNative.diffPatch(staged) }.getOrDefault("")
            if (patch.isBlank()) return@repoOp 0
            patch.lineSequence().forEach { emit(it) }
            0
        }

    private suspend fun cmdLog(args: List<String>, cwd: File, emit: (String) -> Unit): Int =
        repoOp(cwd, emit) {
            val oneline = args.any { it == "--oneline" || it == "-oneline" }
            var limit = 50
            args.forEachIndexed { index, a ->
                when {
                    a == "-n" -> args.getOrNull(index + 1)?.toIntOrNull()?.let { limit = it }
                    a.startsWith("-") && a.length > 1 && a.drop(1).toIntOrNull() != null ->
                        limit = a.drop(1).toIntOrNull() ?: limit
                }
            }
            limit = limit.coerceIn(1, 1000)
            val commits = parseLog(GitNative.log(limit))
            if (commits.isEmpty()) {
                emit("fatal: your current branch does not have any commits yet")
                return@repoOp 128
            }
            commits.forEach { c ->
                if (oneline) {
                    emit("${c.shortOid} ${c.summary}")
                } else {
                    emit("commit ${c.oid}")
                    emit("Author: ${c.author} <${c.email}>")
                    emit("Date:   ${formatDate(c.time)}")
                    emit("")
                    emit("    ${c.summary}")
                    emit("")
                }
            }
            0
        }

    // ------------------------------------------------------------------
    // branch / checkout / remote
    // ------------------------------------------------------------------

    private suspend fun cmdBranch(args: List<String>, cwd: File, emit: (String) -> Unit): Int =
        repoOp(cwd, emit, syncAfter = true) { root ->
            val names = positionals(args)
            val deleting = args.any { it == "-d" || it == "-D" || it == "--delete" }
            when {
                names.isEmpty() -> {
                    val head = parseHead(GitNative.headInfo())
                    parseBranches(GitNative.branches()).forEach { b ->
                        val mark = if (b.isHead) "*" else " "
                        val track = if (b.upstream.isNotBlank()) " ... ${b.upstream}" else ""
                        emit("$mark ${b.name}$track")
                    }
                    if (head?.detached == true) emit("* (HEAD detached at ${head.branch.ifBlank { "unknown" }})")
                    0
                }

                deleting -> {
                    val ok = runCatching { GitNative.deleteBranch(names[0]) }.getOrDefault(false)
                    if (ok) {
                        emit("Deleted branch ${names[0]}")
                        0
                    } else {
                        emit("error: " + lastError())
                        1
                    }
                }

                else -> {
                    val ok = runCatching { GitNative.createBranch(names[0]) }.getOrDefault(false)
                    if (ok) {
                        emit("已创建分支 ${names[0]}（用 `git checkout ${names[0]}` 切换）")
                        0
                    } else {
                        emit("error: 创建分支失败：" + lastError())
                        1
                    }
                }
            }
        }

    private suspend fun cmdCheckout(args: List<String>, cwd: File, emit: (String) -> Unit): Int =
        repoOp(cwd, emit, syncAfter = true) { root ->
            if (args.any { it == "--" } || (args.any { it == "-p" || it == "--patch" })) {
                emit("error: 不支持检出单个文件，请用 `git restore <路径>`")
                return@repoOp 1
            }
            val names = positionals(args)
            if (names.isEmpty()) {
                emit("error: 缺少分支名（用法：git checkout <分支> 或 git checkout -b <分支>）")
                return@repoOp 1
            }
            val create = args.any { it == "-b" }
            if (create) {
                val created = runCatching { GitNative.createBranch(names[0]) }.getOrDefault(false)
                if (!created) {
                    emit("error: 创建分支失败：" + lastError())
                    return@repoOp 1
                }
            }
            val rc = try {
                GitNative.checkoutBranch(names[0])
            } catch (t: Throwable) {
                emit("error: 切换分支失败：" + (t.message ?: "未知错误"))
                return@repoOp 1
            }
            when (rc) {
                0 -> {
                    emit(if (create) "Switched to a new branch '${names[0]}'" else "Switched to branch '${names[0]}'")
                    0
                }

                2 -> {
                    emit("Already on '${names[0]}'")
                    0
                }

                else -> {
                    emit("error: 切换分支失败：" + lastError())
                    1
                }
            }
        }

    private suspend fun cmdRemote(args: List<String>, cwd: File, emit: (String) -> Unit): Int =
        repoOp(cwd, emit, syncAfter = true) { root ->
            val remotes = parseRemotes(GitNative.remotes())
            val verb = args.firstOrNull { !it.startsWith("-") }
            when {
                verb == "add" -> {
                    val rest = positionals(args).drop(1)
                    if (rest.size < 2) {
                        emit("usage: git remote add <名称> <地址>")
                        return@repoOp 1
                    }
                    val ok = runCatching { GitNative.addRemote(rest[0], rest[1]) }.getOrDefault(false)
                    if (!ok) {
                        emit("error: 添加远程失败：" + lastError())
                        return@repoOp 1
                    }
                    0
                }

                verb == "remove" || verb == "rm" -> {
                    val name = positionals(args).getOrNull(1)
                    if (name.isNullOrBlank()) {
                        emit("usage: git remote remove <名称>")
                        return@repoOp 1
                    }
                    val ok = runCatching { GitNative.removeRemote(name) }.getOrDefault(false)
                    if (!ok) {
                        emit("error: 删除远程失败：" + lastError())
                        return@repoOp 1
                    }
                    0
                }

                verb == "set-url" -> {
                    val rest = positionals(args).drop(1)
                    if (rest.size < 2) {
                        emit("usage: git remote set-url <名称> <地址>")
                        return@repoOp 1
                    }
                    val ok = runCatching { GitNative.setRemoteUrl(rest[0], rest[1]) }.getOrDefault(false)
                    if (!ok) {
                        emit("error: 修改远程地址失败：" + lastError())
                        return@repoOp 1
                    }
                    0
                }

                args.any { it == "-v" || it == "--verbose" } -> {
                    remotes.forEach {
                        emit("${it.name}\t${it.url} (fetch)")
                        emit("${it.name}\t${it.url} (push)")
                    }
                    0
                }

                else -> {
                    remotes.forEach { emit(it.name) }
                    0
                }
            }
        }

    // ------------------------------------------------------------------
    // fetch / pull / push / merge
    // ------------------------------------------------------------------

    /** 选择远程：参数优先，其次 origin，再次第一个远程。 */
    private fun pickRemote(requested: String?): String? {
        val remotes = parseRemotes(runCatching { GitNative.remotes() }.getOrDefault(""))
        if (requested != null) return remotes.firstOrNull { it.name == requested }?.name
        return remotes.firstOrNull { it.name == "origin" }?.name ?: remotes.firstOrNull()?.name
    }

    private fun remoteUrlOf(name: String): String? =
        parseRemotes(runCatching { GitNative.remotes() }.getOrDefault(""))
            .firstOrNull { it.name == name }?.url

    /**
     * 抓取核心（须在锁内调用）。
     *
     * 网络传输放到 IO 协程执行、本协程回显进度：终端不会长时间空白，
     * 且用户 Ctrl+C / 点停止时可经 [GitNative.cancelNetwork] 立即中止传输。
     */
    private suspend fun doFetch(remote: String, emit: (String) -> Unit): Int {
        val url = remoteUrlOf(remote) ?: run {
            emit("fatal: '$remote' does not appear to be a git remote")
            return 1
        }
        emit("remote: 正在抓取 '$remote'（$url）…")
        val rc = GitController.withCredentials(url, -1) {
            runWithProgress(emit, FETCH_STAGES) {
                runCatching { GitNative.fetch(remote) }.getOrDefault(-1)
            }
        }
        return if (rc == 0) {
            emit("From $url")
            0
        } else {
            // 已被用户取消：直接抛出 CancellationException，不打印误导性的失败信息
            coroutineContext.ensureActive()
            emit("error: fetch 失败：" + lastError() + "（检查地址、网络与凭据）")
            1
        }
    }

    private suspend fun cmdFetch(args: List<String>, cwd: File, emit: (String) -> Unit): Int =
        repoOp(cwd, emit, syncAfter = true) { root ->
            val remote = pickRemote(positionals(args).firstOrNull()) ?: run {
                emit("fatal: No configured push destination.")
                return@repoOp 1
            }
            doFetch(remote, emit)
        }

    private suspend fun cmdPull(args: List<String>, cwd: File, emit: (String) -> Unit): Int =
        repoOp(cwd, emit, syncAfter = true) { root ->
            val remote = pickRemote(positionals(args).firstOrNull()) ?: run {
                emit("fatal: No configured push destination.")
                return@repoOp 1
            }
            val rc = doFetch(remote, emit)
            if (rc != 0) return@repoOp rc
            val branch = parseHead(GitNative.headInfo())?.branch.orEmpty()
            if (branch.isBlank()) {
                emit("Already up to date.")
                return@repoOp 0
            }
            emit("正在合并 '$remote/$branch' 到 '$branch'…")
            val mrc = try {
                GitNative.merge(branch)
            } catch (t: Throwable) {
                emit("error: 合并失败：" + (t.message ?: "未知错误"))
                return@repoOp 1
            }
            when (mrc) {
                0 -> {
                    emit("Merge made by the 'ort' strategy.")
                    0
                }

                1 -> {
                    emit("CONFLICT (content): 合并产生冲突，请到「Git」页「冲突」处理")
                    emit("Automatic merge failed; fix conflicts and then commit the result.")
                    1
                }

                2 -> {
                    emit("Already up to date.")
                    0
                }

                else -> {
                    emit("error: 合并失败：" + lastError())
                    1
                }
            }
        }

    private suspend fun cmdPush(args: List<String>, cwd: File, emit: (String) -> Unit): Int =
        repoOp(cwd, emit, syncAfter = true) { root ->
            val positional = positionals(args)
            val remote = pickRemote(positional.getOrNull(0)) ?: run {
                emit("fatal: No configured push destination.")
                return@repoOp 1
            }
            val branch = positional.getOrNull(1)
                ?: parseHead(GitNative.headInfo())?.branch.orEmpty()
            if (branch.isBlank()) {
                emit("error: 当前处于分离头指针状态，无法确定推送分支")
                return@repoOp 1
            }
            val url = remoteUrlOf(remote) ?: run {
                emit("fatal: '$remote' does not appear to be a git remote")
                return@repoOp 1
            }
            val current = parseBranches(GitNative.branches()).firstOrNull { it.isHead }
            if (current != null && current.name == branch && current.ahead == 0 && current.upstream.isNotBlank()) {
                emit("Everything up-to-date")
                return@repoOp 0
            }
            emit("remote: 正在推送 '$branch' 到 $url…")
            val rc = GitController.withCredentials(url, -1) {
                runWithProgress(emit, PUSH_STAGES) {
                    runCatching { GitNative.push(remote, branch) }.getOrDefault(-1)
                }
            }
            if (rc == 0) {
                emit("To $url")
                emit("   $branch -> $remote/$branch")
                0
            } else {
                coroutineContext.ensureActive()
                emit("error: push 失败：" + lastError() + "（检查凭据与权限）")
                1
            }
        }

    private suspend fun cmdMerge(args: List<String>, cwd: File, emit: (String) -> Unit): Int =
        repoOp(cwd, emit, syncAfter = true) { root ->
            if (args.any { it == "--abort" }) {
                val rc = runCatching { GitNative.mergeAbort() }.getOrDefault(-1)
                if (rc == 0) {
                    emit("Merge aborted")
                    return@repoOp 0
                }
                emit("error: 放弃合并失败：" + lastError())
                return@repoOp 1
            }
            val target = positionals(args).firstOrNull()
            if (target.isNullOrBlank()) {
                emit("usage: git merge <分支> | git merge --abort")
                return@repoOp 1
            }
            val rc = try {
                GitNative.merge(target)
            } catch (t: Throwable) {
                emit("error: 合并失败：" + (t.message ?: "未知错误"))
                return@repoOp 1
            }
            when (rc) {
                0 -> {
                    emit("Merge made by the 'ort' strategy: $target")
                    0
                }

                1 -> {
                    emit("CONFLICT (content): 合并产生冲突，请到「Git」页「冲突」处理")
                    1
                }

                2 -> {
                    emit("Already up to date.")
                    0
                }

                else -> {
                    emit("error: 合并失败：" + lastError())
                    1
                }
            }
        }

    // ------------------------------------------------------------------
    // tag / init / clone / rev-parse / config
    // ------------------------------------------------------------------

    private suspend fun cmdTag(args: List<String>, cwd: File, emit: (String) -> Unit): Int =
        repoOp(cwd, emit, syncAfter = true) { root ->
            val names = positionals(args)
            val deleting = args.any { it == "-d" || it == "--delete" }
            when {
                names.isEmpty() -> {
                    parseTags(GitNative.tags()).forEach { emit(it.name) }
                    0
                }

                deleting -> {
                    val ok = runCatching { GitNative.deleteTag(names[0]) }.getOrDefault(false)
                    if (ok) {
                        emit("Deleted tag '${names[0]}'")
                        0
                    } else {
                        emit("error: " + lastError())
                        1
                    }
                }

                else -> {
                    var message = ""
                    args.forEachIndexed { i, a ->
                        if (a == "-m" || a == "--message") message = args.getOrNull(i + 1) ?: ""
                    }
                    val ok = runCatching { GitNative.createTag(names[0], message) }.getOrDefault(false)
                    if (ok) {
                        emit("Created tag '${names[0]}'")
                        0
                    } else {
                        emit("error: 创建标签失败：" + lastError())
                        1
                    }
                }
            }
        }

    private suspend fun cmdInit(args: List<String>, cwd: File, emit: (String) -> Unit): Int {
        val path = positionals(args).firstOrNull()
        val target = if (path == null) cwd else resolve(cwd.path, path)
        if (!target.isDirectory && !target.mkdirs()) {
            emit("error: 无法创建目录 ${target.path}")
            return 1
        }
        var ok = false
        GitController.runExclusive(1) {
            ok = runCatching { GitNative.initRepository(target.path) }.getOrDefault(false)
        }
        return if (ok) {
            emit("Initialized empty Git repository in ${File(target, ".git").path}/")
            0
        } else {
            emit("error: 初始化失败：" + lastError())
            1
        }
    }

    private suspend fun cmdClone(args: List<String>, cwd: File, emit: (String) -> Unit): Int {
        var branch = ""
        val positional = ArrayList<String>()
        var i = 0
        while (i < args.size) {
            val a = args[i]
            if (a == "-b" || a == "--branch") {
                branch = args.getOrNull(i + 1) ?: ""
                i += 2
            } else {
                if (!a.startsWith("-")) positional.add(a)
                i++
            }
        }
        val url = positional.getOrNull(0)
        if (url.isNullOrBlank()) {
            emit("usage: git clone [-b <分支>] <地址> [目录]")
            return 1
        }
        val target = positional.getOrNull(1)?.let { resolve(cwd.path, it) }
            ?: File(cwd, repoNameOf(url))
        if (target.exists() && target.listFiles()?.isNotEmpty() == true) {
            emit("fatal: destination path '${target.path}' already exists and is not an empty directory.")
            return 1
        }
        emit("Cloning into '${target.name}'...")
        var rc = -1
        GitController.runExclusive(1) {
            rc = GitController.withCredentials(url, -1) {
                runWithProgress(emit, CLONE_STAGES) {
                    runCatching { GitNative.clone(url, target.path, branch) }.getOrDefault(-1)
                }
            }
        }
        return when (rc) {
            0 -> {
                emit("Clone completed: ${target.path}")
                0
            }

            else -> {
                emit("error: 克隆失败：" + lastError() + "（检查地址、网络与凭据）")
                1
            }
        }
    }

    /** fetch / pull 允许回显的进度阶段（native 会同时上报 fetch / transfer / remote）。 */
    private val FETCH_STAGES = setOf("fetch", "transfer", "remote")

    /** push 允许回显的进度阶段。 */
    private val PUSH_STAGES = setOf("push", "remote")

    /** clone 允许回显的进度阶段。 */
    private val CLONE_STAGES = setOf("clone", "transfer", "remote")

    /**
     * 在 IO 线程执行可能阻塞的 native 网络调用，本协程每 300ms 把 [GitController.progress]
     * 回显到终端（进度无变化时不输出）——避免 `git pull` 之类长时间"没有任何输出"。
     *
     * 取消（面板停止按钮 / 终端 Ctrl+C）时本协程在 [delay] 处抛出 CancellationException，
     * 阻塞线程由 [GitNative.cancelNetwork] 中止传输，随后 [block] 返回、子协程结束。
     */
    private suspend fun <T> runWithProgress(
        emit: (String) -> Unit,
        stages: Set<String>,
        block: () -> T,
    ): T = coroutineScope {
        val job = async(Dispatchers.IO) { block() }
        var last = ""
        while (job.isActive) {
            delay(300)
            val line = progressLine(stages)
            if (line.isNotBlank() && line != last) {
                emit(line)
                last = line
            }
        }
        job.await()
    }

    /**
     * 当前网络进度的一行文本（[stages] 非空时只取这些阶段的进度；无进度返回空串）。
     */
    private fun progressLine(stages: Set<String>? = null): String {
        val p = GitController.progress.value ?: return ""
        if (p.stage.isBlank()) return ""
        if (stages != null && p.stage !in stages) return ""
        return buildString {
            append(stageLabel(p.stage))
            if (p.message.isNotBlank()) append("：").append(p.message)
            if (p.total > 0 && p.current >= 0) {
                append("（").append(p.current).append('/').append(p.total).append('）')
            }
        }
    }

    /** 把路径转成相对仓库根的形式（native 层统一按仓库相对路径处理）。 */
    private fun relPathOf(root: String, file: File): String? =
        runCatching { file.relativeTo(File(root)).path }.getOrNull()

    /** 从仓库地址推导默认目录名（与 Git 页克隆弹窗一致）。 */
    private fun repoNameOf(url: String): String {
        val cleaned = url.trim().removeSuffix("/").removeSuffix(".git")
        val name = cleaned.substringAfterLast('/').substringAfterLast(':')
        return name.filter { it.isLetterOrDigit() || it == '_' || it == '-' || it == '.' }
            .ifBlank { "repository" }
    }

    private suspend fun cmdRevParse(args: List<String>, cwd: File, emit: (String) -> Unit): Int =
        repoOp(cwd, emit) { root ->
            val flags = positionals(args)
            when {
                "--show-toplevel" in flags -> {
                    emit(File(root).canonicalPath)
                    0
                }

                "--git-dir" in flags -> {
                    emit(File(root, ".git").canonicalPath)
                    0
                }

                "--abbrev-ref" in flags -> {
                    emit(parseHead(GitNative.headInfo())?.branch.orEmpty().ifBlank { "HEAD" })
                    0
                }

                flags.isEmpty() || flags.contains("HEAD") -> {
                    val head = parseLog(GitNative.log(1)).firstOrNull()
                    if (head == null) {
                        emit("fatal: your current branch does not have any commits yet")
                        return@repoOp 128
                    }
                    emit(head.oid)
                    0
                }

                else -> {
                    emit("error: 不支持的 rev-parse 参数：" + flags.joinToString(" "))
                    1
                }
            }
        }

    private suspend fun cmdConfig(args: List<String>, cwd: File, emit: (String) -> Unit): Int {
        val positional = positionals(args)
        val key = positional.firstOrNull()
        val value = positional.getOrNull(1)
        return when (key) {
            "user.name", "user.email" -> {
                val identity = GitController.currentIdentity()
                if (value == null) {
                    emit(if (key == "user.name") identity.name else identity.email)
                    0
                } else {
                    val name = if (key == "user.name") value else identity.name
                    val email = if (key == "user.email") value else identity.email
                    GitController.saveIdentity(name, email)
                    0
                }
            }

            else -> {
                emit("warning: 仅支持 config user.name / user.email（身份存于应用设置）")
                1
            }
        }
    }
}
