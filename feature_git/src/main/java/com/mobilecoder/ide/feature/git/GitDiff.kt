package com.mobilecoder.ide.feature.git

/**
 * unified patch 解析（PRD 2.5「文件变更可视化 Diff」）。
 *
 * 输入为 [com.mobilecoder.ide.core.nativebridge.GitNative.diffPatch] 返回的
 * 标准 unified diff 文本，解析成按文件分组的 hunk / 行结构，供 Diff 查看器逐行着色。
 * 解析全程容错：无法识别的行按原文保留（META），绝不抛异常。
 */

enum class DiffLineType {
    /** `@@ -a,b +c,d @@` 区段头。 */
    HUNK,

    /** 新增行（+）。 */
    ADD,

    /** 删除行（-）。 */
    DEL,

    /** 上下文行（空格）。 */
    CONTEXT,

    /** 其它元信息行（mode / binary / "\ No newline" 等）。 */
    META,
}

/** Diff 的一行（含新旧行号）。 */
data class DiffLine(
    val type: DiffLineType,
    /** 旧行号，新增行为 null。 */
    val oldNo: Int?,
    /** 新行号，删除行为 null。 */
    val newNo: Int?,
    val text: String,
)

/** 一个 hunk（区段）。 */
data class DiffHunk(
    val header: String,
    val oldStart: Int,
    val newStart: Int,
    val lines: List<DiffLine>,
)

/** 单个文件的差异。 */
data class DiffFilePatch(
    /** 工作区相对路径。 */
    val path: String,
    val oldPath: String,
    val isNew: Boolean,
    val isDeleted: Boolean,
    val isBinary: Boolean,
    /** `diff --git` 之后、首个 hunk 之前的元信息行。 */
    val header: List<String>,
    val hunks: List<DiffHunk>,
) {
    val linesAdd: Int
        get() = hunks.sumOf { h -> h.lines.count { it.type == DiffLineType.ADD } }

    val linesDel: Int
        get() = hunks.sumOf { h -> h.lines.count { it.type == DiffLineType.DEL } }

    /** 展示用类型标签。 */
    val kindLabel: String
        get() = when {
            isBinary -> "二进制"
            isNew -> "新增文件"
            isDeleted -> "删除文件"
            else -> "修改"
        }
}

object DiffParser {

    private val HUNK_HEADER =
        Regex("""^@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@""")

    /** 解析整份 patch 为文件列表。 */
    fun parse(patch: String): List<DiffFilePatch> {
        if (patch.isBlank()) return emptyList()
        val out = ArrayList<DiffFilePatch>()
        var current: MutableDiffFile? = null

        fun flush() {
            current?.toPatch()?.let { out.add(it) }
            current = null
        }

        for (raw in patch.split('\n')) {
            if (raw.startsWith("diff --git ")) {
                flush()
                current = MutableDiffFile(headerFromDiffLine(raw))
                continue
            }
            val file = current
            if (file == null) continue

            val hunkMatch = HUNK_HEADER.find(raw)
            if (hunkMatch != null && raw.startsWith("@@ ")) {
                val oldStart = hunkMatch.groupValues[1].toIntOrNull() ?: 1
                val newStart = hunkMatch.groupValues[2].toIntOrNull() ?: 1
                val hunkLines = ArrayList<DiffLine>()
                file.hunks.add(
                    DiffHunk(
                        header = raw,
                        oldStart = oldStart,
                        newStart = newStart,
                        lines = hunkLines,
                    ),
                )
                file.currentLines = hunkLines
                file.oldNo = oldStart
                file.newNo = newStart
                continue
            }

            val lines = file.currentLines
            if (lines == null) {                // hunk 之前的元信息
                when {
                    raw.startsWith("new file mode") -> file.isNew = true
                    raw.startsWith("deleted file mode") -> file.isDeleted = true
                    raw.startsWith("Binary files") ||
                        raw.startsWith("GIT binary patch") -> file.isBinary = true
                    raw.startsWith("--- ") -> {
                        val p = raw.removePrefix("--- ").trim()
                        if (p != "/dev/null") file.oldPath = stripPrefixPath(p)
                    }
                    raw.startsWith("+++ ") -> {
                        val p = raw.removePrefix("+++ ").trim()
                        if (p != "/dev/null") file.path = stripPrefixPath(p)
                    }
                }
                if (raw.isNotEmpty()) file.header.add(raw)
                continue
            }

            when {
                raw.startsWith("+") -> {
                    lines.add(DiffLine(DiffLineType.ADD, null, file.newNo, raw))
                    file.newNo = (file.newNo ?: 0) + 1
                }
                raw.startsWith("-") -> {
                    lines.add(DiffLine(DiffLineType.DEL, file.oldNo, null, raw))
                    file.oldNo = (file.oldNo ?: 0) + 1
                }
                raw.startsWith("\\") -> {
                    lines.add(DiffLine(DiffLineType.META, null, null, raw))
                }
                raw.startsWith(" ") || raw.isEmpty() -> {
                    lines.add(
                        DiffLine(DiffLineType.CONTEXT, file.oldNo, file.newNo, raw),
                    )
                    file.oldNo = (file.oldNo ?: 0) + 1
                    file.newNo = (file.newNo ?: 0) + 1
                }
                else -> {
                    lines.add(DiffLine(DiffLineType.META, null, null, raw))
                }
            }
        }
        flush()
        return out
    }

    /** 从整份 patch 中取出指定路径的文件差异（没有则返回 null）。 */
    fun forPath(patch: String, path: String): DiffFilePatch? {
        if (patch.isBlank()) return null
        val all = parse(patch)
        return all.firstOrNull { it.path == path || it.oldPath == path }
            ?: all.firstOrNull { it.path.endsWith("/$path") || it.oldPath.endsWith("/$path") }
    }

    private fun headerFromDiffLine(line: String): String {
        // "diff --git a/<old> b/<new>"，路径含空格时 git 会加引号
        val body = line.removePrefix("diff --git ").trim()
        val newPath = body.substringAfterLast(" b/", "")
            .ifBlank { body.substringAfterLast("b/", "") }
        return stripPrefixPath(newPath).ifBlank { body }
    }

    private fun stripPrefixPath(path: String): String = path
        .removePrefix("\"a/").removePrefix("\"b/")
        .removePrefix("a/").removePrefix("b/")
        .removeSuffix("\"")
}

/** 解析过程中的可变文件状态。 */
private class MutableDiffFile(header: String) {
    var path: String = ""
    var oldPath: String = ""
    var isNew: Boolean = false
    var isDeleted: Boolean = false
    var isBinary: Boolean = false
    val header: ArrayList<String> = arrayListOf(header)
    val hunks: ArrayList<DiffHunk> = ArrayList()
    var currentLines: ArrayList<DiffLine>? = null
    var oldNo: Int? = null
    var newNo: Int? = null

    fun toPatch(): DiffFilePatch {
        val resolvedPath = path.ifBlank { oldPath }
        return DiffFilePatch(
            path = resolvedPath,
            oldPath = oldPath.ifBlank { resolvedPath },
            isNew = isNew,
            isDeleted = isDeleted,
            isBinary = isBinary,
            header = header,
            hunks = hunks,
        )
    }
}
