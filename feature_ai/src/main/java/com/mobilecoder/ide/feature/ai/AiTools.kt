package com.mobilecoder.ide.feature.ai

import com.mobilecoder.ide.core.storage.FileRepository
import java.io.File
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

/**
 * AI 可调用的项目工具集：浏览 + 修改项目文件 + 执行命令 / 查看 Git（Issue 6），
 * 文件类工具在应用进程内执行，命令类工具走 [AiCommandRunner]（proot + CliNative）。
 *
 * 安全约束：
 *  - 所有路径相对项目根，canonical 化后必须仍在根内（防目录穿越）；
 *  - 跳过构建产物 / VCS 内部目录（[FileRepository.isIgnored]）；
 *  - `run_command` 先过 [AiCommandGuard] 危险命令拦截，且恒需用户确认；
 *  - 回传给模型的文本统一截断，防止上下文爆炸。
 */
object AiTools {

    /** 回传模型的单条结果上限（字符）。 */
    private const val MAX_RESULT_CHARS = 16_000

    /** 单次 read_file 默认/最大行数。 */
    private const val DEFAULT_READ_LINES = 400
    private const val MAX_READ_LINES = 2000

    /** `run_command` 默认/最大超时（秒）。 */
    private const val DEFAULT_CMD_TIMEOUT_SEC = 300
    private const val MAX_CMD_TIMEOUT_SEC = 1800

    /** 只读 git 工具的超时（毫秒）：进程内 libgit2 操作，通常毫秒级返回。 */
    private const val GIT_TIMEOUT_MS = 30_000L

    /** 命令结果里 stdout / stderr 各自的保留上限（字符）。 */
    private const val MAX_STDOUT_CHARS = 12_000
    private const val MAX_STDERR_CHARS = 3_000

    /** 工具中文名（确认弹窗 / 事件行共用）。 */
    fun displayName(name: String): String = when (name) {
        "list_files" -> "列出"
        "read_file" -> "读取"
        "write_file" -> "写入"
        "search_replace" -> "替换"
        "delete_path" -> "删除"
        "run_command" -> "执行"
        "git_status", "git_diff", "git_log" -> "查看"
        else -> name
    }

    /** 会改写项目文件的工具（需快照保护；delete 恒需确认）。 */
    fun isMutating(name: String): Boolean =
        name == "write_file" || name == "search_replace" || name == "delete_path"

    /**
     * 无条件需要用户确认的工具：删除不可恢复，命令执行可绕过文件工具直接改动
     * 项目外的绑定目录（proot 里 `/sdcard`、`/data` 同路径可见）。与「写入前确认」开关无关。
     */
    fun needsConfirm(name: String): Boolean = name == "delete_path" || name == "run_command"

    /** 事件目标是项目内文件路径（对话里可点行跳编辑器）。 */
    fun opensFile(name: String): Boolean =
        name == "read_file" || name == "write_file" || name == "search_replace" || name == "delete_path"

    /**
     * 确认弹窗 / 事件行里的目标文本：命令类工具显示命令本身，其余显示 `path`
     * （[AiSnapshotStore] 只认 `path`，命令文本绝不能当快照路径）。
     */
    fun targetOf(name: String, argsJson: String): String = when (name) {
        "run_command" -> parseArgs(argsJson)?.optString("command").orEmpty().trim()
        "git_status", "git_diff", "git_log" -> parseArgs(argsJson)
            ?.let { gitLine(name, it) }.orEmpty()
        else -> parseArgs(argsJson)?.optString("path").orEmpty()
    }

    /** 解析工具参数（非法 JSON 返回 null，调用方一律回空串）。 */
    private fun parseArgs(raw: String): JSONObject? = runCatching { JSONObject(raw) }.getOrNull()

    /** OpenAI function-calling 工具 schema。 */
    fun schemas(): JSONArray = JSONArray(
        listOf(
            tool(
                "list_files",
                "列出项目文件树（相对项目根的路径）。修改代码前先用它了解结构。",
                properties = JSONObject()
                    .put("path", strProp("起始目录，留空表示项目根"))
                    .put("depth", intProp("递归深度（1-6，默认 2）")),
                required = emptyList(),
            ),
            tool(
                "read_file",
                "读取一个文本文件的内容（带行号）。修改文件前必须先读取。",
                properties = JSONObject()
                    .put("path", strProp("文件路径（相对项目根）"))
                    .put("offset", intProp("起始行号（从 1 开始，默认 1）"))
                    .put("limit", intProp("读取行数（默认 400）")),
                required = listOf("path"),
            ),
            tool(
                "write_file",
                "创建或整体覆写一个文件（父目录自动创建）。新文件、或大段重写时使用。",
                properties = JSONObject()
                    .put("path", strProp("文件路径（相对项目根）"))
                    .put("content", strProp("完整文件内容（UTF-8）")),
                required = listOf("path", "content"),
            ),
            tool(
                "search_replace",
                "在文件中做精确字符串替换（old_str 必须与文件内容逐字符一致，成功后写回）。" +
                    "修改已有文件时优先用它，而不是整体覆写。",
                properties = JSONObject()
                    .put("path", strProp("文件路径（相对项目根）"))
                    .put("old_str", strProp("要被替换的原文（必须完全一致）"))
                    .put("new_str", strProp("替换后的新文本")),
                required = listOf("path", "old_str", "new_str"),
            ),
            tool(
                "delete_path",
                "删除一个文件或目录（目录递归删除，不可恢复）。",
                properties = JSONObject().put("path", strProp("要删除的路径（相对项目根）")),
                required = listOf("path"),
            ),
            tool(
                "run_command",
                "在项目目录里执行一条 shell 命令（构建 / 测试 / linter / 生成器等），" +
                    "返回退出码与 stdout/stderr。修改代码后应运行构建或测试进行验证；" +
                    "危险命令（rm -rf /、shutdown、git reset --hard 等）会被拦截。" +
                    "gradlew 若缺执行位可用 `sh ./gradlew …`；git 命令请优先用 " +
                    "git_status / git_diff / git_log。",
                properties = JSONObject()
                    .put("command", strProp("要执行的命令行，如 ./gradlew test、npm run build"))
                    .put("cwd", strProp("工作目录（相对项目根，默认项目根）"))
                    .put(
                        "timeout",
                        intProp("超时秒数（默认 300，最大 1800；超时后进程被终止）"),
                    ),
                required = listOf("command"),
            ),
            tool(
                "git_status",
                "查看 git 工作区状态（只读，进程内执行）。",
                properties = JSONObject().put("short", boolProp("true 输出短格式")),
                required = emptyList(),
            ),
            tool(
                "git_diff",
                "查看 git 差异（只读，进程内执行）。",
                properties = JSONObject()
                    .put("staged", boolProp("true 查看暂存区差异，false 查看工作区差异"))
                    .put("stat", boolProp("true 只看统计摘要")),
                required = emptyList(),
            ),
            tool(
                "git_log",
                "查看提交历史（只读，进程内执行）。",
                properties = JSONObject().put("limit", intProp("条数（默认 20）")),
                required = emptyList(),
            ),
        ),
    )

    /** 执行工具调用。绝不抛异常：失败一律转成 `ok=false` 的结果回传模型。 */
    suspend fun execute(name: String, argsJson: String, projectRoot: File): AiToolResult {
        val args = runCatching { JSONObject(argsJson) }.getOrElse {
            return fail(name, "", "参数不是合法 JSON：${it.message}")
        }
        return try {
            when (name) {
                "list_files" -> listFiles(projectRoot, args)
                "read_file" -> readFile(projectRoot, args)
                "write_file" -> writeFile(projectRoot, args)
                "search_replace" -> searchReplace(projectRoot, args)
                "delete_path" -> deletePath(projectRoot, args)
                "run_command" -> runCommand(projectRoot, args)
                "git_status", "git_diff", "git_log" -> runGitTool(name, projectRoot, args)
                else -> fail(name, "", "未知工具：$name")
            }
        } catch (c: CancellationException) {
            // 命令执行中被用户停止：取消必须继续向上传播（不能伪装成工具失败）
            throw c
        } catch (t: Throwable) {
            fail(name, args.optString("path"), t.message ?: t.javaClass.simpleName)
        }
    }

    // ------------------------------------------------------------------
    // 工具实现
    // ------------------------------------------------------------------

    private fun listFiles(root: File, args: JSONObject): AiToolResult {
        val path = args.optString("path")
        val depth = args.optInt("depth", 2).coerceIn(1, 6)
        val dir = resolve(root, path) ?: return fail("list_files", path, "路径超出项目根目录")
        if (!dir.isDirectory) return fail("list_files", path, "不是目录：$path")
        val lines = ArrayList<String>()
        fun walk(current: File, prefix: String, remaining: Int) {
            if (lines.size >= 400 || remaining <= 0) return
            // 子项相对项目根的深度：区分浅层 build 产物与深层 build 源码包（与树同口径）
            val childDepth = current.absolutePath
                .removePrefix(root.absolutePath)
                .count { it == '/' || it == '\\' } + 1
            val children = FileRepository.listChildren(current, showHidden = false)
                .filterNot { FileRepository.isIgnored(it.name, childDepth) }
            for (child in children) {
                if (lines.size >= 400) return
                if (child.isDirectory) {
                    lines.add("$prefix${child.name}/")
                    walk(child.file, "$prefix  ", remaining - 1)
                } else {
                    lines.add("$prefix${child.name} (${humanSize(child.size)})")
                }
            }
        }
        walk(dir, "", depth)
        if (lines.isEmpty()) lines.add("(空目录)")
        val relative = relOf(root, dir)
        return ok(
            "list_files",
            relative,
            "列出 ${lines.size} 项",
            "项目文件（$relative，深度≤$depth）：\n" + lines.take(400).joinToString("\n"),
        )
    }

    private fun readFile(root: File, args: JSONObject): AiToolResult {
        val path = args.optString("path")
        val file = resolve(root, path) ?: return fail("read_file", path, "路径超出项目根目录")
        if (!file.isFile) return fail("read_file", path, "文件不存在：$path")
        val all = runCatching { file.readText(Charsets.UTF_8).replace("\r\n", "\n") }
            .getOrElse { return fail("read_file", path, "读取失败：${it.message}") }
        val lines = all.split('\n')
        val offset = args.optInt("offset", 1).coerceAtLeast(1)
        val limit = args.optInt("limit", DEFAULT_READ_LINES).coerceIn(1, MAX_READ_LINES)
        val end = (offset - 1 + limit).coerceAtMost(lines.size)
        if (offset > lines.size) return fail("read_file", path, "起始行 $offset 超出总行数 ${lines.size}")
        val body = (offset..end).joinToString("\n") { lineNo ->
            "%5d\t%s".format(lineNo, lines[lineNo - 1])
        }
        val tail = if (end < lines.size) "\n… 还有 ${lines.size - end} 行（用 offset=$end+1 继续）" else ""
        return ok(
            "read_file",
            path,
            "读取 ${end - offset + 1}/${lines.size} 行",
            body + tail,
        )
    }

    private fun writeFile(root: File, args: JSONObject): AiToolResult {
        val path = args.optString("path")
        if (path.isBlank()) return fail("write_file", path, "缺少 path")
        if (!args.has("content")) return fail("write_file", path, "缺少 content")
        val file = resolve(root, path) ?: return fail("write_file", path, "路径超出项目根目录")
        val content = args.optString("content")
        val okWrite = FileRepository.writeText(file, content)
        if (!okWrite) return fail("write_file", path, "写入失败（目录不可写？）")
        val lines = content.count { it == '\n' } + 1
        return ok("write_file", path, "已写入 $lines 行 / ${content.length} 字符", "写入成功：$path")
    }

    private fun searchReplace(root: File, args: JSONObject): AiToolResult {
        val path = args.optString("path")
        val old = args.optString("old_str")
        val new = args.optString("new_str")
        if (old.isEmpty()) return fail("search_replace", path, "old_str 不能为空")
        if (old == new) return fail("search_replace", path, "old_str 与 new_str 相同")
        val file = resolve(root, path) ?: return fail("search_replace", path, "路径超出项目根目录")
        if (!file.isFile) return fail("search_replace", path, "文件不存在：$path")
        val text = runCatching { file.readText(Charsets.UTF_8) }
            .getOrElse { return fail("search_replace", path, "读取失败：${it.message}") }
        var count = 0
        var index = text.indexOf(old)
        while (index >= 0) {
            count++
            index = text.indexOf(old, index + old.length)
        }
        if (count == 0) {
            return fail(
                "search_replace",
                path,
                "未找到与 old_str 完全一致的内容（含空格、缩进都要一致）；请先 read_file 再重试",
            )
        }
        val updated = text.replace(old, new)
        val okWrite = FileRepository.writeText(file, updated)
        if (!okWrite) return fail("search_replace", path, "写入失败")
        return ok(
            "search_replace",
            path,
            "替换 $count 处",
            "已在 $path 中替换 $count 处",
        )
    }

    private fun deletePath(root: File, args: JSONObject): AiToolResult {
        val path = args.optString("path")
        if (path.isBlank() || path == "." || path == "/") {
            return fail("delete_path", path, "不允许删除项目根目录")
        }
        val file = resolve(root, path) ?: return fail("delete_path", path, "路径超出项目根目录")
        if (!file.exists()) return fail("delete_path", path, "路径不存在：$path")
        val okDelete = FileRepository.delete(file)
        if (!okDelete) return fail("delete_path", path, "删除失败")
        return ok("delete_path", path, if (file.isDirectory) "已删除目录" else "已删除文件", "已删除：$path")
    }

    // ------------------------------------------------------------------
    // 命令与 Git（Issue 6：改代码 → 跑命令 → 看结果）
    // ------------------------------------------------------------------

    /**
     * `run_command`：proot 内 `/bin/sh -c <command>`（git 等进程内命令走 CliEngine）。
     *
     * 顺序：cwd 沙箱校验 → 危险命令拦截 → 执行（超时/取消会杀掉进程组）→
     * 组装「退出码 + stderr + stdout」回传模型。
     */
    private suspend fun runCommand(root: File, args: JSONObject): AiToolResult {
        val command = args.optString("command").trim()
        if (command.isBlank()) return fail("run_command", "", "缺少 command")
        val cwdArg = args.optString("cwd")
        val dir = resolve(root, cwdArg) ?: return fail("run_command", cwdArg, "cwd 超出项目根目录")
        if (!dir.isDirectory) return fail("run_command", cwdArg, "cwd 不是目录：$cwdArg")

        AiCommandGuard.rejectReason(command, dir, root)?.let { reason ->
            return fail("run_command", clip(command, 80), "危险命令已拦截：$reason")
        }

        val timeoutSec = args.optInt("timeout", DEFAULT_CMD_TIMEOUT_SEC)
            .coerceIn(1, MAX_CMD_TIMEOUT_SEC)
        val output = AiCommandRunner.run(command, dir, timeoutSec * 1000L)
        val body = buildString {
            append("命令：").append(command).append('\n')
            append("工作目录：").append(relOf(root, dir)).append('\n')
            append("退出码：").append(output.exitCode).append('\n')
            if (output.timedOut) append("已超时（${timeoutSec}s）并终止进程\n")
            output.hint?.let { append(it).append('\n') }
            if (output.stderr.isNotBlank()) {
                append("—— stderr ——\n").append(clip(output.stderr, MAX_STDERR_CHARS)).append('\n')
            }
            if (output.stdout.isNotBlank()) {
                append("—— stdout ——\n").append(clip(output.stdout, MAX_STDOUT_CHARS))
            }
        }.trim()
        val summary = when {
            output.timedOut -> "超时（${timeoutSec}s）已终止"
            output.exitCode == 0 -> "退出码 0"
            else -> "退出码 ${output.exitCode}"
        }
        return AiToolResult(
            ok = output.exitCode == 0 && !output.timedOut,
            target = clip(command, 80),
            summary = summary,
            content = clip(body, MAX_RESULT_CHARS),
        )
    }

    /** 只读 git 工具：进程内执行（与终端 Git 共用 CliEngine 锁与仓库句柄）。 */
    private suspend fun runGitTool(name: String, root: File, args: JSONObject): AiToolResult {
        val line = gitLine(name, args)
        val output = AiCommandRunner.runInProcess(line, root, GIT_TIMEOUT_MS)
        if (output.exitCode == 127) {
            return fail(name, line, "Git 命令引擎未初始化，请稍后重试或重启应用")
        }
        val body = if (output.exitCode == 0) {
            output.stdout
        } else {
            "退出码 ${output.exitCode}\n" + output.stderr.ifBlank { output.stdout }
        }
        val summary = if (output.exitCode == 0) {
            "退出码 0"
        } else {
            "退出码 ${output.exitCode}：" +
                output.stderr.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().trim().take(40)
        }
        return AiToolResult(
            ok = output.exitCode == 0,
            target = line,
            summary = summary.trim(),
            content = clip(body, MAX_RESULT_CHARS),
        )
    }

    /** 只读 git 工具的命令行（展示与执行共用，事件行里的命令与实际执行完全一致）。 */
    internal fun gitLine(name: String, args: JSONObject): String = when (name) {
        "git_status" -> if (args.optBoolean("short", false)) "git status --short" else "git status"
        "git_diff" -> buildString {
            append("git diff")
            if (args.optBoolean("staged", false)) append(" --staged")
            if (args.optBoolean("stat", false)) append(" --stat")
        }

        "git_log" -> "git log -n ${args.optInt("limit", 20).coerceIn(1, 200)}"
        else -> name
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private fun tool(
        name: String,
        description: String,
        properties: JSONObject,
        required: List<String>,
    ): JSONObject = JSONObject()
        .put("type", "function")
        .put(
            "function",
            JSONObject()
                .put("name", name)
                .put("description", description)
                .put(
                    "parameters",
                    JSONObject()
                        .put("type", "object")
                        .put("properties", properties)
                        .put("required", JSONArray(required)),
                ),
        )

    private fun strProp(desc: String): JSONObject =
        JSONObject().put("type", "string").put("description", desc)

    private fun intProp(desc: String): JSONObject =
        JSONObject().put("type", "integer").put("description", desc)

    private fun boolProp(desc: String): JSONObject =
        JSONObject().put("type", "boolean").put("description", desc)

    private fun resolve(root: File, path: String): File? {
        val clean = path.trim().ifBlank { "." }.removePrefix("./").removePrefix("/")
        val canonicalRoot = runCatching { root.canonicalFile }.getOrNull() ?: root
        val target = if (clean == ".") canonicalRoot else File(canonicalRoot, clean)
        val canonical = runCatching { target.canonicalFile }.getOrNull() ?: return null
        if (canonical != canonicalRoot &&
            !canonical.path.startsWith(canonicalRoot.path + File.separator)
        ) {
            return null
        }
        return canonical
    }

    private fun relOf(root: File, target: File): String {
        val canonicalRoot = runCatching { root.canonicalFile }.getOrNull() ?: root
        val relative = runCatching { target.relativeTo(canonicalRoot).path }.getOrDefault(".")
        return relative.ifBlank { "." }
    }

    private fun humanSize(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
        bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }

    private fun ok(name: String, target: String, summary: String, content: String) = AiToolResult(
        ok = true,
        target = target,
        summary = summary,
        content = clip(content, MAX_RESULT_CHARS),
    )

    private fun fail(name: String, target: String, reason: String) = AiToolResult(
        ok = false,
        target = target,
        summary = reason,
        content = "工具 $name 执行失败：$reason",
    )

    /**
     * 截断到 [max] 字符：超出时保留头部 + 尾部（中间省略并标注丢弃量）。
     *
     * 构建 / 测试日志的**结论在结尾**、文件内容的**上下文在开头**，
     * 两头都留才不会让模型看不到关键信息（纯 `take` 会把 stderr 结论截掉）。
     */
    private fun clip(text: String, max: Int): String = when {
        text.length <= max -> text
        else -> {
            val head = (max * 3 / 4).coerceAtLeast(1)
            val tail = (max - head).coerceAtLeast(0)
            text.take(head) +
                "\n…[输出过长，已截断 ${text.length - max} 字符]…\n" +
                text.takeLast(tail)
        }
    }
}
