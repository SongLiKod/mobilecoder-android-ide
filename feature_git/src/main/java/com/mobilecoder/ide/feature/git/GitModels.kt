package com.mobilecoder.ide.feature.git

/**
 * Git 数据模型 + 字符串帧解析（PRD 2.5）。
 *
 * 帧格式与 [com.mobilecoder.ide.core.nativebridge.GitNative] / git_jni.c 注释严格一致：
 *  - 记录以换行分隔、字段以 0x01 分隔
 *  - 解析时容错：空行、字段数不足、无法转换的数字一律跳过，绝不抛异常
 */

/** 帧字段分隔符的码点（与 git_jni.c 的 MC_FIELD_SEP 一致）。 */
private const val FIELD_SEP_CODE = 1

/** 按 0x01 拆分一行记录为字段列表。 */
private fun splitFields(line: String): List<String> {
    val out = ArrayList<String>(8)
    val sb = StringBuilder()
    for (ch in line) {
        if (ch.code == FIELD_SEP_CODE) {
            out.add(sb.toString())
            sb.setLength(0)
        } else {
            sb.append(ch)
        }
    }
    out.add(sb.toString())
    return out
}

/* ------------------------------------------------------------------ */
/* 帧解析工具                                                          */
/* ------------------------------------------------------------------ */

/** 把 native 返回的字符串拆成「每条记录的字段列表」，跳过字段数不足的行。 */
internal fun String.frames(minFields: Int): List<List<String>> =
    split('\n')
        .asSequence()
        .filter { it.isNotEmpty() }
        .map { splitFields(it) }
        .filter { it.size >= minFields && it[0].isNotEmpty() }
        .toList()

/** 取第一条有效记录（headInfo / diffStats 等不带结尾换行的单条记录）。 */
internal fun String.firstFrame(minFields: Int): List<String>? {
    val line = lineSequence().firstOrNull { it.isNotBlank() } ?: return null
    val fields = splitFields(line)
    return if (fields.size >= minFields) fields else null
}

/* ------------------------------------------------------------------ */
/* 状态                                                                */
/* ------------------------------------------------------------------ */

/** 文件状态语义（对应 SourceTree / GitKraken 的展示分类）。 */
enum class GitStatusKind(val label: String) {
    NEW("新增"),
    MODIFIED("修改"),
    DELETED("删除"),
    RENAMED("重命名"),
    TYPECHANGE("类型变更"),
    CONFLICTED("冲突"),
    IGNORED("已忽略"),
    UNTRACKED("未跟踪"),
    UNCHANGED("无变化"),
    UNREADABLE("不可读"),
}

/** 变更分组（变更页的分组标题）。 */
enum class GitChangeGroup(val label: String) {
    STAGED("已暂存"),
    UNSTAGED("未暂存"),
    UNTRACKED("未跟踪"),
    CONFLICTED("冲突"),
    IGNORED("已忽略"),
}

/* git_status_t 原始位（git_jni.c statusList 输出的是数字而非字符） */
private const val IDX_NEW = 0x01
private const val IDX_MODIFIED = 0x02
private const val IDX_DELETED = 0x04
private const val IDX_RENAMED = 0x08
private const val IDX_TYPECHANGE = 0x10
private const val WT_NEW = 0x80
private const val WT_MODIFIED = 0x100
private const val WT_DELETED = 0x200
private const val WT_TYPECHANGE = 0x400
private const val WT_RENAMED = 0x800
private const val WT_UNREADABLE = 0x1000
private const val STATUS_CONFLICTED = 0x8000

/** 索引区（staged）状态码 → 语义。 */
internal fun indexOf(raw: Int): GitStatusKind = when {
    raw and STATUS_CONFLICTED != 0 -> GitStatusKind.CONFLICTED
    raw and IDX_NEW != 0 -> GitStatusKind.NEW
    raw and IDX_MODIFIED != 0 -> GitStatusKind.MODIFIED
    raw and IDX_DELETED != 0 -> GitStatusKind.DELETED
    raw and IDX_RENAMED != 0 -> GitStatusKind.RENAMED
    raw and IDX_TYPECHANGE != 0 -> GitStatusKind.TYPECHANGE
    else -> GitStatusKind.UNCHANGED
}

/** 工作区（unstaged）状态码 → 语义。 */
internal fun worktreeOf(raw: Int, ignored: Boolean): GitStatusKind = when {
    raw and STATUS_CONFLICTED != 0 -> GitStatusKind.CONFLICTED
    raw and WT_NEW != 0 -> GitStatusKind.UNTRACKED
    raw and WT_MODIFIED != 0 -> GitStatusKind.MODIFIED
    raw and WT_DELETED != 0 -> GitStatusKind.DELETED
    raw and WT_TYPECHANGE != 0 -> GitStatusKind.TYPECHANGE
    raw and WT_RENAMED != 0 -> GitStatusKind.RENAMED
    raw and WT_UNREADABLE != 0 -> GitStatusKind.UNREADABLE
    ignored -> GitStatusKind.IGNORED
    else -> GitStatusKind.UNCHANGED
}

/** 索引区展示字符（大写）：A / M / D / R / T / U。 */
internal fun GitStatusKind.indexChar(): Char = when (this) {
    GitStatusKind.NEW -> 'A'
    GitStatusKind.MODIFIED -> 'M'
    GitStatusKind.DELETED -> 'D'
    GitStatusKind.RENAMED -> 'R'
    GitStatusKind.TYPECHANGE -> 'T'
    GitStatusKind.CONFLICTED -> 'U'
    else -> ' '
}

/** 工作区展示字符（小写）：? / m / d / r / t / u / U / !。 */
internal fun GitStatusKind.worktreeChar(): Char = when (this) {
    GitStatusKind.UNTRACKED -> '?'
    GitStatusKind.MODIFIED -> 'm'
    GitStatusKind.DELETED -> 'd'
    GitStatusKind.RENAMED -> 'r'
    GitStatusKind.TYPECHANGE -> 't'
    GitStatusKind.UNREADABLE -> 'u'
    GitStatusKind.CONFLICTED -> 'U'
    GitStatusKind.IGNORED -> '!'
    else -> ' '
}

/** statusList 的一行。 */
data class GitStatusEntry(
    val path: String,
    val indexRaw: Int,
    val worktreeRaw: Int,
    val ignored: Boolean,
) {
    val index: GitStatusKind = indexOf(indexRaw)

    val worktree: GitStatusKind = worktreeOf(worktreeRaw, ignored)

    /** 两字符缩写，如 "A "、" M"、"?"→"??"、"UU"、"!!"（与 git status 输出一致）。 */
    val code: String
        get() = when {
            index == GitStatusKind.UNCHANGED && worktree == GitStatusKind.UNTRACKED -> "??"
            index == GitStatusKind.UNCHANGED && ignored -> "!!"
            else -> "${index.indexChar()}${worktree.worktreeChar()}"
        }

    /** 该行是否属于指定分组（索引与工作区同时变更时，同一路径会在两个分组各出现一次）。 */
    fun inGroup(group: GitChangeGroup): Boolean = when (group) {
        GitChangeGroup.CONFLICTED ->
            index == GitStatusKind.CONFLICTED || worktree == GitStatusKind.CONFLICTED

        GitChangeGroup.IGNORED -> ignored && index == GitStatusKind.UNCHANGED

        GitChangeGroup.STAGED ->
            index != GitStatusKind.UNCHANGED && index != GitStatusKind.CONFLICTED

        GitChangeGroup.UNTRACKED ->
            worktree == GitStatusKind.UNTRACKED && index == GitStatusKind.UNCHANGED

        GitChangeGroup.UNSTAGED ->
            index == GitStatusKind.UNCHANGED && !ignored && (
                worktree == GitStatusKind.MODIFIED ||
                    worktree == GitStatusKind.DELETED ||
                    worktree == GitStatusKind.RENAMED ||
                    worktree == GitStatusKind.TYPECHANGE ||
                    worktree == GitStatusKind.UNREADABLE
                )
    }
}

/** 解析 statusList() 帧：path / idxStatus / wtStatus / ignored。 */
internal fun parseStatus(raw: String?): List<GitStatusEntry> {
    if (raw.isNullOrEmpty()) return emptyList()
    return raw.frames(4).mapNotNull { f ->
        val idx = f[1].trim().toIntOrNull() ?: return@mapNotNull null
        val wt = f[2].trim().toIntOrNull() ?: return@mapNotNull null
        GitStatusEntry(
            path = f[0],
            indexRaw = idx,
            worktreeRaw = wt,
            ignored = f[3].trim() == "1",
        )
    }
}

/* ------------------------------------------------------------------ */
/* 历史 / 分支 / 标签 / 远程 / 冲突 / Diff 统计                          */
/* ------------------------------------------------------------------ */

/** 提交时间线一行：oid / shortOid / summary / author / email / time / parents。 */
data class GitCommit(
    val oid: String,
    val shortOid: String,
    val summary: String,
    val author: String,
    val email: String,
    /** Unix 秒。 */
    val time: Long,
    /** 父提交数量（C 层回传的是 parentcount）。 */
    val parentCount: Int,
)

internal fun parseLog(raw: String?): List<GitCommit> {
    if (raw.isNullOrEmpty()) return emptyList()
    return raw.frames(7).mapNotNull { f ->
        GitCommit(
            oid = f[0],
            shortOid = f[1].ifBlank { f[0].take(10) },
            summary = f[2],
            author = f[3],
            email = f[4],
            time = f[5].trim().toLongOrNull() ?: 0L,
            parentCount = f[6].trim().toIntOrNull() ?: 0,
        )
    }
}

/** 分支：name / isHead / upstream / ahead / behind。 */
data class GitBranch(
    val name: String,
    val isHead: Boolean,
    val upstream: String,
    val ahead: Int,
    val behind: Int,
)

internal fun parseBranches(raw: String?): List<GitBranch> {
    if (raw.isNullOrEmpty()) return emptyList()
    return raw.frames(5).mapNotNull { f ->
        GitBranch(
            name = f[0],
            isHead = f[1].trim() == "1",
            upstream = f[2],
            ahead = f[3].trim().toIntOrNull() ?: 0,
            behind = f[4].trim().toIntOrNull() ?: 0,
        )
    }
}

/** HEAD 信息：branch / detached / unborn / ahead / behind（C 层无结尾换行）。 */
data class GitHead(
    val branch: String,
    val detached: Boolean,
    val unborn: Boolean,
    val ahead: Int,
    val behind: Int,
)

internal fun parseHead(raw: String?): GitHead? {
    val f = raw?.firstFrame(5) ?: return null
    return GitHead(
        branch = f[0],
        detached = f[1].trim() == "1",
        unborn = f[2].trim() == "1",
        ahead = f[3].trim().toIntOrNull() ?: 0,
        behind = f[4].trim().toIntOrNull() ?: 0,
    )
}

/** 标签：name / oid / annotated / message。 */
data class GitTag(
    val name: String,
    val oid: String,
    val annotated: Boolean,
    val message: String,
)

internal fun parseTags(raw: String?): List<GitTag> {
    if (raw.isNullOrEmpty()) return emptyList()
    return raw.frames(4).mapNotNull { f ->
        GitTag(
            name = f[0],
            oid = f[1],
            annotated = f[2].trim() == "1",
            message = f[3],
        )
    }
}

/** 远程：name / url。 */
data class GitRemote(
    val name: String,
    val url: String,
)

internal fun parseRemotes(raw: String?): List<GitRemote> {
    if (raw.isNullOrEmpty()) return emptyList()
    return raw.frames(2).mapNotNull { f -> GitRemote(name = f[0], url = f[1]) }
}

/** 冲突：path / ancestorOid / oursOid / theirsOid。 */
data class GitConflict(
    val path: String,
    val ancestorOid: String,
    val oursOid: String,
    val theirsOid: String,
)

internal fun parseConflicts(raw: String?): List<GitConflict> {
    if (raw.isNullOrEmpty()) return emptyList()
    return raw.frames(4).mapNotNull { f ->
        GitConflict(path = f[0], ancestorOid = f[1], oursOid = f[2], theirsOid = f[3])
    }
}

/** 差异统计：added / deleted / modified / linesAdd / linesDel（C 层为单条记录）。 */
data class GitDiffStats(
    val added: Int = 0,
    val deleted: Int = 0,
    val modified: Int = 0,
    val linesAdd: Int = 0,
    val linesDel: Int = 0,
) {
    val isEmpty: Boolean
        get() = added == 0 && deleted == 0 && modified == 0
}

internal fun parseDiffStats(raw: String?): GitDiffStats? {
    val f = raw?.firstFrame(5) ?: return null
    return GitDiffStats(
        added = f[0].trim().toIntOrNull() ?: 0,
        deleted = f[1].trim().toIntOrNull() ?: 0,
        modified = f[2].trim().toIntOrNull() ?: 0,
        linesAdd = f[3].trim().toIntOrNull() ?: 0,
        linesDel = f[4].trim().toIntOrNull() ?: 0,
    )
}

/** Git 身份（AppStorage.preferences 持久化，commit 时使用）。 */
data class GitIdentity(
    val name: String = "",
    val email: String = "",
)

/** 仓库页面状态。 */
data class GitRepoUiState(
    val path: String = "",
    val opening: Boolean = false,
    /** 目录是否为 Git 仓库。 */
    val isRepo: Boolean = false,
    /** 仓库是否已在 native 层打开。 */
    val opened: Boolean = false,
    val workdir: String = "",
    val head: GitHead? = null,
)

/** 提示消息（成功信息 / 中文错误）。 */
data class GitMessage(
    val text: String,
    val isError: Boolean,
)

/** 网络进度（native 任意线程回调 → StateFlow → 主线程 collectAsStateWithLifecycle 订阅）。 */
data class GitProgress(
    val stage: String = "",
    val message: String = "",
    val current: Int = -1,
    val total: Int = -1,
) {
    /** 0..1，无法量化时返回 null。 */
    val fraction: Float?
        get() = if (total > 0 && current >= 0) {
            (current.toFloat() / total).coerceIn(0f, 1f)
        } else {
            null
        }
}

/** 进度阶段名 → 中文。 */
internal fun stageLabel(stage: String): String = when (stage) {
    "clone" -> "克隆"
    "fetch" -> "拉取"
    "push" -> "推送"
    "transfer" -> "传输对象"
    "remote" -> "远程"
    "merge" -> "合并"
    else -> stage
}
