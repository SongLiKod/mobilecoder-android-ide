package com.mobilecoder.ide.feature.git

/**
 * git CLI 文案/输出的纯函数渲染层（与 native 调用解耦，便于单测）。
 *
 * 对齐目标：
 *  - `git branch [-a|-r] [-v|-vv] [--list <pattern>]` 的行格式
 *  - `git fetch` 的 ` * [new branch] x -> origin/x` / `   a..b  y -> origin/y` 更新行
 *  - `git checkout origin/x` 的 "Note: switching to ..." + "HEAD is now at ..." 分离提示
 *  - `git pull` 无上游配置时的提示块
 */

/* ------------------------------------------------------------------ */
/*  git branch                                                         */
/* ------------------------------------------------------------------ */

/**
 * 渲染 `git branch` 输出行。
 *
 * @param all        `-a`：本地 + 远程
 * @param remoteOnly `-r`：仅远程
 * @param verbose    0 无 / 1 `-v`（提交号 + 标题）/ 2 `-vv`（再加 `[上游]` 与领先落后）
 * @param patterns   `--list <pattern>` 过滤（glob：`*`、`?`）
 */
internal fun renderBranchLines(
    branches: List<GitBranch>,
    all: Boolean = false,
    remoteOnly: Boolean = false,
    verbose: Int = 0,
    patterns: List<String> = emptyList(),
): List<String> {
    val regexes = patterns.map { globToRegex(it) }
    val visible = branches
        .filter { b -> if (remoteOnly) b.isRemote else if (all) true else !b.isRemote }
        .filter { b ->
            /* 真 git 的 pattern 按简写匹配：远程行去掉 remotes/ 前缀（origin/main） */
            val probe = b.name.removePrefix("remotes/")
            regexes.isEmpty() || regexes.any { it.matches(probe) }
        }

    /* `-v/-vv` 时按最长分支名对齐列（HEAD 箭头行不参与对齐） */
    val nameWidth = if (verbose > 0) {
        visible.filter { !it.isHeadArrow }
            .maxOfOrNull { it.name.length }
            ?.plus(1) ?: 0
    } else {
        0
    }

    return visible.map { b -> renderBranchLine(b, verbose, nameWidth) }
}

/** `remotes/<r>/HEAD -> <target>` 箭头行判定。 */
private val GitBranch.isHeadArrow: Boolean
    get() = isRemote && name.endsWith("/HEAD")

private fun renderBranchLine(b: GitBranch, verbose: Int, nameWidth: Int): String {
    val mark = if (b.isHead) "*" else " "
    if (b.isHeadArrow) {
        val arrow = if (b.upstream.isNotBlank()) " -> ${b.upstream}" else ""
        return "$mark ${b.name}$arrow"
    }
    val parts = buildList {
        if (verbose > 0 && b.tipOid.isNotBlank()) add(b.tipOid)
        if (verbose > 1 && !b.isRemote && b.upstream.isNotBlank()) add(upstreamBracket(b))
        if (verbose > 0 && b.tipSummary.isNotBlank()) add(b.tipSummary)
    }
    if (parts.isEmpty()) return "$mark ${b.name}"
    /* nameWidth = 最长分支名 + 1：padEnd 的结果自带列分隔用的空格，列即对齐 */
    val name = if (nameWidth > 0) b.name.padEnd(nameWidth) else b.name + " "
    return "$mark $name" + parts.joinToString(" ")
}

/** `-vv` 的上游括号：`[origin/main]` / `[origin/main: ahead 1, behind 2]`。 */
internal fun upstreamBracket(b: GitBranch): String {
    val parts = buildList {
        if (b.ahead > 0) add("ahead ${b.ahead}")
        if (b.behind > 0) add("behind ${b.behind}")
    }
    return if (parts.isEmpty()) "[${b.upstream}]"
    else "[${b.upstream}: ${parts.joinToString(", ")}]"
}

/** glob → 正则（`*` 匹配任意段、`?` 匹配单字符；其余转义）。 */
internal fun globToRegex(pattern: String): Regex {
    val sb = StringBuilder()
    for (ch in pattern) {
        when (ch) {
            '*' -> sb.append(".*")
            '?' -> sb.append('.')
            else -> sb.append(Regex.escape(ch.toString()))
        }
    }
    return Regex(sb.toString())
}

/* ------------------------------------------------------------------ */
/*  git fetch                                                          */
/* ------------------------------------------------------------------ */

/**
 * fetch 前后引用快照 diff → `git fetch` 的更新行：
 * ```
 *  * [new branch]      dev      -> origin/dev
 *    5b7d6e5..89f0a2c  main     -> origin/main
 *  * [new tag]         v1.0     -> v1.0
 * ```
 * @param before/after     远程跟踪分支（shorthand `origin/dev` → oid）
 * @param tagsBefore/after 标签（name → oid）
 */
internal fun formatFetchRefLines(
    remote: String,
    before: Map<String, String>,
    after: Map<String, String>,
    tagsBefore: Map<String, String> = emptyMap(),
    tagsAfter: Map<String, String> = emptyMap(),
): List<String> {
    val lines = mutableListOf<String>()

    after.keys.sorted().forEach { key ->
        /* 键来自 remoteRefs 快照：shorthand（origin/dev）；也兼容短名（dev） */
        val shortName = key.removePrefix("$remote/")
        val target = if (shortName == key) "$remote/$key" else key
        val newOid = after[key].orEmpty()
        val oldOid = before[key]
        when {
            oldOid == null ->
                lines += fetchRefLine(" * ", "[new branch]", shortName, target)

            oldOid != newOid ->
                lines += fetchRefLine("   ", "${oldOid.take(7)}..${newOid.take(7)}", shortName, target)
        }
    }
    before.keys.sorted().forEach { key ->
        if (key !in after) {
            val shortName = key.removePrefix("$remote/")
            val target = if (shortName == key) "$remote/$key" else key
            lines += fetchRefLine(" - ", "[deleted]", shortName, target)
        }
    }

    tagsAfter.keys.sorted().forEach { tag ->
        val newOid = tagsAfter[tag].orEmpty()
        val oldOid = tagsBefore[tag]
        when {
            oldOid == null ->
                lines += fetchRefLine(" * ", "[new tag]", tag, tag)

            oldOid != newOid ->
                lines += fetchRefLine("   ", "${oldOid.take(7)}..${newOid.take(7)}", tag, tag)
        }
    }
    return lines
}

private fun fetchRefLine(prefix: String, label: String, name: String, target: String): String =
    "$prefix${label.padEnd(16)}${name.padEnd(12)}-> $target"

/* ------------------------------------------------------------------ */
/*  git checkout / git pull                                            */
/* ------------------------------------------------------------------ */

/** `git checkout origin/x`（分离头指针）的标准提示块。 */
internal fun detachedHeadLines(refName: String, shortOid: String, summary: String): List<String> {
    val lines = mutableListOf("Note: switching to '$refName'.", "")
    lines += "You are in 'detached HEAD state'. You can look around, make experimental"
    lines += "changes and commit them, and you can discard any commits you make in this"
    lines += "state without impacting any branches by switching back to a branch."
    lines += ""
    lines += "If you want to create a new branch retaining the commits you have created,"
    lines += "you may switch back now with 'git switch -c <new-branch-name>' and undo this"
    lines += "operation with 'git switch -<old-branch>'."
    lines += ""
    if (shortOid.isNotBlank()) {
        lines += if (summary.isBlank()) "HEAD is now at $shortOid"
        else "HEAD is now at $shortOid $summary"
    }
    return lines
}

/**
 * `git pull` 当前分支无上游配置时的提示块（对齐 git 2.x 实测文案）。
 * [remote] 渲染 `--set-upstream-to=` 行的远程名；`<remote>`/`<branch>` 是 git 的字面占位。
 */
internal fun pullNoTrackingLines(branch: String, remote: String?): List<String> = listOf(
    "There is no tracking information for the current branch.",
    "Please specify which branch you want to merge with.",
    "See git-pull(1) for details.",
    "",
    "    git pull <remote> <branch>",
    "",
    "If you wish to set tracking information for this branch you can do so with:",
    "",
    "    git branch --set-upstream-to=${remote ?: "origin"}/<branch> $branch",
)

/**
 * `git push` 时当前分支无上游（且未显式给出分支）的 fatal 块
 * （对齐 git 2.x 实测文案，exit 128）。
 */
internal fun pushNoUpstreamLines(branch: String, remote: String): List<String> = listOf(
    "fatal: The current branch $branch has no upstream branch.",
    "To push the current branch and set the remote as upstream, use",
    "",
    "    git push --set-upstream $remote $branch",
    "",
    "To have this happen automatically for branches without a tracking",
    "upstream, see 'push.autoSetupRemote' in 'git help config'.",
)

/** `git branch -u <不存在的上游>` 的 fatal + hint 块（对齐 git 实测文案，exit 128）。 */
internal fun upstreamNotFoundLines(upstream: String): List<String> = listOf(
    "fatal: the requested upstream branch '$upstream' does not exist",
    "hint:",
    "hint: If you are planning on basing your work on an upstream",
    "hint: branch that already exists at the remote, you may need to",
    "hint: run \"git fetch\" to retrieve it.",
    "hint:",
    "hint: If you are planning to push out a new local branch that",
    "hint: will track its remote counterpart, you may want to use",
    "hint: \"git push -u\" to set the upstream config as you push.",
    "hint: Disable this message with \"git config set advice.setUpstreamFailure false\"",
)

/**
 * `git pull` 的合并目标（返回 `refs/remotes/<远程>/<分支>` 的简写 `<远程>/<分支>`）：
 * 显式分支参数 → 配置的 merge 分支 → 当前分支；都拿不到返回 null。
 */
internal fun pullMergeRef(
    remote: String,
    branchArg: String?,
    configuredMerge: String?,
    current: String?,
): String? {
    val branch = branchArg
        ?: configuredMerge?.removePrefix("refs/heads/")?.takeIf { it.isNotBlank() }
        ?: current?.takeIf { it.isNotBlank() }
    return branch?.let { "$remote/$it" }
}
