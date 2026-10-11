package com.mobilecoder.ide.feature.ai

import java.io.File

/**
 * `run_command` 的危险命令拦截（Issue 6）。
 *
 * AI 能在 proot guest 里执行任意命令，而 proot 是 `--root-id` 伪 root：
 * guest 内的 `/` 是 rootfs，但 `/data/...`、`/sdcard`、`/storage` 等目录**同路径
 * bind** 了真实数据（见 `core_common/…/linux/Proot.kt`）。一旦模型幻觉出
 * `rm -rf /`、`shutdown` 这类命令，破坏的是用户的真实数据，因此在执行前统一拦截。
 *
 * 设计取舍：
 *  - **按命令位判定**：只看每一段的第一个词（跳过 `VAR=1`、`sudo` 等前缀），
 *    因此 `git commit -m "rm -rf /"` 这类把危险文本当参数的命令不会被误杀；
 *  - **按段拆分**：`;`、`&&`、`||`、`|` 切开逐段判定，`ls && rm -rf /` 无法绕过；
 *  - **宁可误杀不可漏杀**：命中即拒绝并回传中文原因，模型可换写法重试
 *    （如把 `rm -rf build/` 之外的目标交给 `delete_path` 工具）。
 *
 * 纯字符串 + 文件路径判断，JVM 单测可直接覆盖（见 `AiCommandGuardTest`）。
 */
object AiCommandGuard {

    /** 命令位直接拒绝的系统级命令。 */
    private val HARD_COMMANDS = setOf(
        "shutdown", "reboot", "halt", "poweroff", "init", "fdisk", "parted", "wipefs",
        "blkdiscard", "swapoff", "swapon", "su", "chroot", "sysctl", "iptables", "svc",
    )

    /** shell 变量形式的 HOME 引用（字符串里要转义 `$`）。 */
    private const val HOME_REF = "\$HOME"

    /** `rm` 等命令的危险目标（精确匹配）。 */
    private val ROOT_TARGETS = setOf(
        "/", "/*", ".", "..", "./", "../", "*", ".*", ".git", "~", "~/", HOME_REF, "$HOME_REF/",
    )

    /** 重定向允许写出的目录前缀（设备 / 进程 / 临时区，不属于用户数据）。 */
    private val SAFE_REDIRECT_PREFIXES = listOf("/dev/", "/proc/", "/tmp/", "/var/tmp/")

    /** 命令位前的可跳过前缀（`sudo env time …`）。 */
    private val PREFIX_SKIP = setOf("sudo", "env", "nohup", "time", "command", "exec")

    /** `VAR=value` 形式的环境变量前缀。 */
    private val ASSIGN = Regex("""^[A-Za-z_][A-Za-z0-9_]*=.*""")

    /** 重定向：`> file`、`2>> log`（前置数字是 stdout/stderr 重定向）。 */
    private val REDIRECT = Regex("""(?:^|\s)[012]?>{1,2}\s*([^\s;|&]+)""")

    /** 下载即执行：`curl … | sh`。 */
    private val PIPE_TO_SHELL = Regex("""(curl|wget)\b[^|]*\|\s*(ba|z|da|fi)?sh\b""")

    /** 返回 null 表示可以执行；否则是给模型看的中文拒绝原因。 */
    fun rejectReason(command: String, cwd: File, root: File): String? {
        if (command.isBlank()) return "命令为空"
        if (command.replace(Regex("""\s"""), "").contains(":(){")) {
            return "疑似 fork 炸弹，已拒绝"
        }
        if (PIPE_TO_SHELL.containsMatchIn(command)) {
            return "禁止「下载脚本直接执行」（curl/wget | sh），请改为可审计的分步命令"
        }
        for (raw in command.split(Regex("""\|\||&&|[;|\n]"""))) {
            val segment = raw.trim()
            if (segment.isEmpty()) continue
            rejectSegment(segment, cwd, root)?.let { return it }
        }
        return null
    }

    // ------------------------------------------------------------------

    private fun rejectSegment(segment: String, cwd: File, root: File): String? {
        val tokens = tokenize(segment)
        var index = 0
        while (index < tokens.size &&
            (ASSIGN.matches(tokens[index]) || tokens[index] in PREFIX_SKIP)
        ) {
            index++
        }
        // `/sbin/reboot` → `reboot`；`./gradlew` → `.`
        val cmd = tokens.getOrNull(index)?.substringBefore('/')?.trim().orEmpty()
        if (cmd.isEmpty()) return null
        val args = tokens.drop(index + 1)

        if (cmd in HARD_COMMANDS) return "系统级危险命令：$cmd"
        if (cmd.startsWith("mkfs")) return "系统级危险命令：$cmd（格式化磁盘）"

        if (cmd == "rm") {
            val bad = args.filterNot { it.startsWith("-") }.firstOrNull { isDangerousTarget(it, cwd, root) }
            if (bad != null) {
                return "禁止删除高危目标「$bad」（会摧毁项目或系统数据）；" +
                    "项目内文件请改用 delete_path，或指定具体的相对路径"
            }
        }

        if (cmd == "git") {
            gitDestructiveReason(args)?.let { return it }
        }

        if (cmd == "dd" && args.any { it.startsWith("of=/dev/") }) {
            return "禁止向块设备写入（dd of=/dev/…）"
        }

        if ((cmd == "chmod" || cmd == "chown") && args.any { isDangerousTarget(it, cwd, root) }) {
            return "禁止对「/」等高危目标变更权限/属主"
        }

        killReason(cmd, args, segment)?.let { return it }

        redirectReason(segment, cwd, root)?.let { return it }
        return null
    }

    /** git 里会**不可逆丢弃工作**的子命令。 */
    private fun gitDestructiveReason(args: List<String>): String? {
        val sub = args.firstOrNull() ?: return null
        val rest = args.drop(1)
        val combined = rest.filter { it.startsWith("-") && !it.startsWith("--") }.joinToString("")
        return when {
            sub == "reset" && rest.any { it == "--hard" } ->
                "git reset --hard 会丢弃全部未提交改动"

            sub == "clean" && (combined.contains('f') || combined.contains('x') ||
                rest.any { it == "--force" || it.startsWith("--clean") }) ->
                "git clean -f 会删除未跟踪文件"

            sub == "push" && (rest.any { it == "--force" || it == "-f" } ||
                rest.any { it.startsWith("-") && !it.startsWith("--") && it.contains('f') }) ->
                "git push --force 会覆盖远程历史"

            sub == "branch" && combined.contains('D') ->
                "git branch -D 会强制删除分支"

            sub == "filter-branch" -> "git filter-branch 会重写全部历史"

            sub == "checkout" && ("--" in rest || "." in rest) ->
                "git checkout 会丢弃工作区改动，请改用文件工具修改"

            sub == "restore" && "--staged" !in rest && rest.any { !it.startsWith("-") } ->
                "git restore 会丢弃工作区改动，请改用文件工具修改"

            else -> null
        }
    }

    private fun killReason(cmd: String, args: List<String>, segment: String): String? {
        if (cmd !in setOf("kill", "pkill", "killall")) return null
        if (segment.contains("mobilecoder")) return "禁止终止应用自身进程"
        if (cmd == "kill" && args.any { it == "1" }) return "禁止终止 1 号进程"
        return null
    }

    private fun redirectReason(segment: String, cwd: File, root: File): String? {
        for (match in REDIRECT.findAll(segment)) {
            val target = match.groupValues[1]
            if (!target.startsWith("/")) continue
            if (SAFE_REDIRECT_PREFIXES.any { target.startsWith(it) }) continue
            if (insideProject(cwd, root, target)) continue
            return "禁止写入项目外的绝对路径：$target"
        }
        return null
    }

    /**
     * 高危目标判定：
     *
     *  - 精确命中 [ROOT_TARGETS]（`.`、`/`、`*`、`.git`…）——在项目根执行等同于清空项目；
     *  - `~` / `$HOME` 前缀——shell 会展开到 app 数据目录，永远在项目沙箱之外；
     *  - 绝对路径——只有 canonical 化后仍在项目根内才放行
     *    （proot 里 `/sdcard`、`/data`、`/storage` 同路径 bind 了真实数据）。
     */
    private fun isDangerousTarget(target: String, cwd: File, root: File): Boolean {
        val value = target.trim()
        if (value in ROOT_TARGETS) return true
        if (value.startsWith("~") || value.startsWith(HOME_REF)) return true
        if (!value.startsWith("/")) return false
        return !insideProject(cwd, root, value)
    }

    private fun insideProject(cwd: File, root: File, target: String): Boolean {
        val canonicalRoot = runCatching { root.canonicalFile }.getOrNull() ?: root
        val file = if (target.startsWith("/")) File(target) else File(cwd, target)
        val canonical = runCatching { file.canonicalFile }.getOrNull() ?: return false
        return canonical == canonicalRoot ||
            canonical.path.startsWith(canonicalRoot.path + File.separator)
    }

    /** 简易分词：按空白切，引号内的空白并入同一个 token（只用于规则匹配，不用于执行）。 */
    private fun tokenize(line: String): List<String> {
        val out = ArrayList<String>()
        val current = StringBuilder()
        var quote: Char? = null
        for (ch in line) {
            when {
                quote != null -> {
                    if (ch == quote) quote = null else current.append(ch)
                }

                ch == '"' || ch == '\'' -> quote = ch
                ch.isWhitespace() -> {
                    if (current.isNotEmpty()) {
                        out.add(current.toString())
                        current.clear()
                    }
                }

                else -> current.append(ch)
            }
        }
        if (current.isNotEmpty()) out.add(current.toString())
        return out
    }
}
