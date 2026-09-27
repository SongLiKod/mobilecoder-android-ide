package com.mobilecoder.ide.feature.cli

import java.io.File

/**
 * CLI 纯文件工具：格式化 / 静态检查 / 体积统计。
 *
 * 全部在 App 进程内实现（无需外部二进制），保证移动端离线可用（PRD 2.4 开箱即用）。
 */
object CodeTools {

    enum class Level { ERROR, WARN, INFO }

    data class Issue(val level: Level, val location: String, val message: String)

    private val FORMAT_EXT = setOf(
        "kt", "kts", "java", "xml", "gradle", "properties", "json", "md", "txt",
        "js", "ts", "jsx", "tsx", "dart", "yml", "yaml", "html", "css",
    )
    private val INDENT_EXT = setOf("kt", "kts", "java", "xml", "gradle", "js", "ts", "dart")
    private val SKIP_DIRS = setOf("build", ".gradle", ".git", ".idea", ".cxx", "node_modules", ".kotlin")

    // ---------------- format ----------------

    fun formatTargets(root: File): List<File> =
        walkText(root, FORMAT_EXT)

    /** 标准化：行尾空白清理 + Tab→4 空格（代码类）+ 文件末尾单换行。 */
    fun format(source: String, extension: String): String {
        val useSpaces = extension.lowercase() in INDENT_EXT
        val lines = source.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val formatted = lines
            .dropLast(if (lines.size > 1 && lines.last().isEmpty()) 1 else 0)
            .joinToString("\n") { raw ->
                var line = raw.trimEnd()
                if (useSpaces && line.contains('\t')) {
                    // 保留缩进层级：每遇到 Tab 前进到下一个 4 空格制表位
                    val out = StringBuilder()
                    for (ch in line) {
                        if (ch == '\t') {
                            while (out.length % 4 != 0) out.append(' ')
                        } else {
                            out.append(ch)
                        }
                    }
                    line = out.toString()
                }
                line
            }
        return formatted.trimEnd('\n') + "\n"
    }

    // ---------------- lint ----------------

    fun lintTargets(root: File): List<File> =
        walkText(root, setOf("kt", "kts", "java", "xml", "gradle", "js", "ts", "dart", "json", "sh"))

    fun lint(file: File, relative: String): List<Issue> {
        val issues = ArrayList<Issue>()
        val text = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: return issues
        val lines = text.replace("\r\n", "\n").split('\n')

        var brace = 0
        var paren = 0
        var bracket = 0
        var inBlockComment = false
        var conflict = false

        for ((index, raw) in lines.withIndex()) {
            val lineNo = index + 1
            val line = raw

            if (line.startsWith("<<<<<<<") || line.startsWith(">>>>>>>")) {
                conflict = true
                issues.add(Issue(Level.ERROR, "$relative:$lineNo", "存在未解决的合并冲突标记"))
            }
            if (line.length > 200) {
                issues.add(Issue(Level.WARN, "$relative:$lineNo", "行长度 ${line.length} > 200"))
            }
            if (line != line.trimEnd()) {
                issues.add(Issue(Level.WARN, "$relative:$lineNo", "行尾有多余空白"))
            }

            val code = stripComments(line, inBlockComment).also {
                inBlockComment = when {
                    inBlockComment -> !it.second
                    else -> it.second
                }
            }.first

            if (file.extension != "md") {
                for (ch in code) {
                    when (ch) {
                        '{' -> brace++
                        '}' -> brace--
                        '(' -> paren++
                        ')' -> paren--
                        '[' -> bracket++
                        ']' -> bracket--
                    }
                }
                if (brace < 0 || paren < 0 || bracket < 0) {
                    issues.add(Issue(Level.ERROR, "$relative:$lineNo", "括号不匹配（多余的闭合符号）"))
                    brace = maxOf(brace, 0)
                    paren = maxOf(paren, 0)
                    bracket = maxOf(bracket, 0)
                }
            }
        }

        if (inBlockComment && file.extension != "md") {
            issues.add(Issue(Level.ERROR, "$relative:${lines.size}", "块注释 /* 未闭合"))
        }
        if (!conflict && brace > 0) {
            issues.add(Issue(Level.ERROR, "$relative", "大括号未闭合（差 $brace 个）"))
        } else if (!conflict && paren > 0) {
            issues.add(Issue(Level.ERROR, "$relative", "小括号未闭合（差 $paren 个）"))
        }
        if (text.contains("TODO") || text.contains("FIXME")) {
            val count = Regex("TODO|FIXME").findAll(text).count()
            issues.add(Issue(Level.INFO, "$relative", "包含 $count 处 TODO/FIXME"))
        }
        if (text.contains("<<<<<<< ")) {
            issues.add(Issue(Level.ERROR, "$relative", "存在冲突标记"))
        }
        if (text.isNotBlank() && !text.endsWith("\n")) {
            issues.add(Issue(Level.WARN, "$relative", "文件末尾缺少换行"))
        }
        return issues
    }

    /** 去掉行内注释（简化模型：处理 // 与成对 /* */，字符串内的 // 也一并按注释处理的误差可接受）。 */
    private fun stripComments(line: String, inBlock: Boolean): Pair<String, Boolean> {
        val out = StringBuilder()
        var index = 0
        var inBlockComment = inBlock
        while (index < line.length) {
            if (inBlockComment) {
                val end = line.indexOf("*/", index)
                if (end < 0) return out.toString() to true
                index = end + 2
                inBlockComment = false
                continue
            }
            if (line.startsWith("/*", index)) {
                inBlockComment = true
                index += 2
                continue
            }
            if (line.startsWith("//", index)) break
            out.append(line[index])
            index++
        }
        return out.toString() to inBlockComment
    }

    // ---------------- 公共 ----------------

    fun walkText(root: File, extensions: Set<String>): List<File> {
        val out = ArrayList<File>()
        fun walk(dir: File, depth: Int) {
            if (depth > 10) return
            val children = dir.listFiles() ?: return
            for (child in children.sortedBy { it.name }) {
                if (child.isDirectory) {
                    if (child.name in SKIP_DIRS) continue
                    walk(child, depth + 1)
                } else if (extensions.contains(child.extension.lowercase())) {
                    out.add(child)
                }
            }
        }
        if (root.isDirectory) walk(root, 1)
        return out
    }

    fun sizeOf(file: File): Long {
        if (file.isFile) return file.length()
        var total = 0L
        file.listFiles()?.forEach { total += sizeOf(it) }
        return total
    }

    fun humanSize(bytes: Long): String = when {
        bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> "%.1f MB".format(bytes / (1L shl 20).toDouble())
        bytes >= 1L shl 10 -> "%.1f KB".format(bytes / (1L shl 10).toDouble())
        else -> "$bytes B"
    }
}
