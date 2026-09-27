package com.mobilecoder.ide.feature.cli

import com.mobilecoder.ide.core.common.cli.CliCommand
import com.mobilecoder.ide.core.common.cli.OpencodeCli
import java.io.File

/**
 * CLI 内置命令注册（PRD 2.4：`init` / `format` / `lint` / `clean` …）。
 *
 * `build` / `package` 由 feature_build 注册，`help` / `version` 由引擎内置，
 * 因此这里只负责**纯文件类**命令，全部在 App 进程内执行、逐行日志回吐。
 *
 * 幂等：Application.onCreate 调用一次即可。
 */
object CliBootstrap {

    @Volatile
    private var done = false

    fun install() {
        if (done) return
        synchronized(this) {
            if (done) return
            OpencodeCli.registerAll(builtinCommands())
            done = true
        }
    }

    private fun builtinCommands(): List<CliCommand> = listOf(
        // ---------------- init ----------------
        CliCommand(
            name = "init",
            summary = "初始化项目骨架（安卓 / Kotlin CLI / Vue / HTML / Node / Flutter / 空目录）",
            usage = "opencode init [--android|--cli|--vue|--html|--node|--flutter|--empty]",
            group = "项目",
        ) { args, cwd, emit ->
            val template = when {
                args.any { it == "--empty" } -> com.mobilecoder.ide.core.storage.ProjectTemplate.EMPTY
                args.any { it == "--cli" } -> com.mobilecoder.ide.core.storage.ProjectTemplate.KOTLIN_CLI
                args.any { it == "--vue" } -> com.mobilecoder.ide.core.storage.ProjectTemplate.VUE_VITE
                args.any { it == "--html" } -> com.mobilecoder.ide.core.storage.ProjectTemplate.STATIC_HTML
                args.any { it == "--node" } -> com.mobilecoder.ide.core.storage.ProjectTemplate.NODE_APP
                args.any { it == "--flutter" } -> com.mobilecoder.ide.core.storage.ProjectTemplate.FLUTTER
                else -> com.mobilecoder.ide.core.storage.ProjectTemplate.ANDROID_APP
            }
            emit("在 ${cwd.absolutePath} 初始化 [${template.label}] 项目 …")
            val count = com.mobilecoder.ide.core.storage.AppStorage.projects.scaffold(cwd, template)
            if (count == 0) {
                emit("目录已存在同名文件，未做修改（0 个新文件）")
            } else {
                emit("已生成 $count 个文件")
                when (template) {
                    com.mobilecoder.ide.core.storage.ProjectTemplate.VUE_VITE,
                    com.mobilecoder.ide.core.storage.ProjectTemplate.NODE_APP,
                    -> emit("下一步：`npm install` 安装依赖后运行")

                    com.mobilecoder.ide.core.storage.ProjectTemplate.FLUTTER ->
                        emit("下一步：`flutter create .` 生成平台目录，再 `flutter run`")

                    com.mobilecoder.ide.core.storage.ProjectTemplate.STATIC_HTML ->
                        emit("下一步：打开 index.html，或 `npx serve .` 启动静态服务器")

                    else -> emit("下一步：`opencode lint` 检查，`opencode build` 编译")
                }
            }
            0
        },

        // ---------------- format ----------------
        CliCommand(
            name = "format",
            summary = "统一代码格式：行尾空白、缩进、文件末尾换行",
            usage = "opencode format [目录]",
            group = "代码",
        ) { args, cwd, emit ->
            val root = if (args.isNotEmpty()) File(cwd, args.first()) else cwd
            if (!root.exists()) {
                emit("目录不存在：${root.path}")
                return@CliCommand 1
            }
            var scanned = 0
            var changed = 0
            for (file in CodeTools.formatTargets(root)) {
                scanned++
                val original = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: continue
                val formatted = CodeTools.format(original, file.extension)
                if (formatted != original) {
                    if (runCatching { file.writeText(formatted, Charsets.UTF_8) }.isSuccess) {
                        changed++
                        emit("格式化 ${rel(root, file)}")
                    }
                }
            }
            emit("完成：扫描 $scanned 个文件，重写 $changed 个")
            0
        },

        // ---------------- lint ----------------
        CliCommand(
            name = "lint",
            summary = "静态检查：括号配对、冲突标记、超长行、行尾空白",
            usage = "opencode lint [目录]",
            group = "代码",
        ) { args, cwd, emit ->
            val root = if (args.isNotEmpty()) File(cwd, args.first()) else cwd
            if (!root.exists()) {
                emit("目录不存在：${root.path}")
                return@CliCommand 1
            }
            var errorCount = 0
            var warnCount = 0
            for (file in CodeTools.lintTargets(root)) {
                val issues = CodeTools.lint(file, rel(root, file))
                for (issue in issues) {
                    if (issue.level == CodeTools.Level.ERROR) errorCount++ else warnCount++
                    emit("[${issue.level.name}] ${issue.location}: ${issue.message}")
                }
            }
            emit("检查完成：$errorCount 个错误，$warnCount 个警告")
            if (errorCount > 0) 1 else 0
        },

        // ---------------- clean ----------------
        CliCommand(
            name = "clean",
            summary = "清理构建缓存（build/、.gradle/、.cxx/）",
            usage = "opencode clean",
            group = "构建",
        ) { _, cwd, emit ->
            val targets = listOf("build", "app/build", ".gradle", ".cxx", ".kotlin")
            var freed = 0L
            var removed = 0
            for (relative in targets) {
                val dir = File(cwd, relative)
                if (!dir.exists()) continue
                val size = CodeTools.sizeOf(dir)
                if (dir.deleteRecursively()) {
                    freed += size
                    removed++
                    emit("已删除 ${relative}（${CodeTools.humanSize(size)}）")
                }
            }
            emit("清理完成：删除 $removed 个目录，释放 ${CodeTools.humanSize(freed)}")
            0
        },

        // ---------------- doctor ----------------
        CliCommand(
            name = "doctor",
            summary = "环境体检：存储、JDK/Gradle/SDK、Native 引擎状态",
            usage = "opencode doctor",
            group = "系统",
        ) { _, _, emit ->
            val storage = com.mobilecoder.ide.core.storage.AppStorage.paths
            emit("存储结构（TECH.md 5）：")
            emit("  files/projects  ${CodeTools.humanSize(CodeTools.sizeOf(storage.projects))}")
            emit("  files/ssh_keys  ${storage.sshKeys.listFiles()?.size ?: 0} 个密钥文件")
            emit("  files/sdk       ${if (storage.sdk.isDirectory) "已导入" else "未导入"}")
            emit("  files/bin       ${if (storage.bin.isDirectory) "就绪" else "缺失"}")

            val prefs = com.mobilecoder.ide.core.storage.AppStorage.preferences
            val jdk = prefs.jdkPath()
            val gradle = prefs.gradlePath()
            emit("")
            emit("构建环境：")
            emit("  JDK    ${jdk.ifBlank { "未配置（设置 → 构建环境）" }}")
            emit("  Gradle ${gradle.ifBlank { "未配置（设置 → 构建环境）" }}")
            emit("  注：未配置时仍可编辑/终端/Git/SSH，构建命令会给出明确提示")

            emit("")
            emit("Native 引擎：")
            val gitOk = runCatching {
                com.mobilecoder.ide.core.nativebridge.NativeRuntime.ensureGitReady(
                    com.mobilecoder.ide.core.storage.AppStorage.context,
                )
            }.getOrDefault(false)
            val sshOk = runCatching {
                com.mobilecoder.ide.core.nativebridge.NativeRuntime.ensureSshReady()
            }.getOrDefault(false)
            emit("  libgit2 ${if (gitOk) "就绪" else "不可用"}")
            emit("  libssh2 ${if (sshOk) "就绪" else "不可用"}")
            0
        },

        // ---------------- ls / cat（终端友好） ----------------
        CliCommand(
            name = "ls",
            summary = "列出目录内容",
            usage = "opencode ls [路径]",
            group = "系统",
        ) { args, cwd, emit ->
            val dir = if (args.isNotEmpty()) File(cwd, args.first()) else cwd
            val children = dir.listFiles()
            if (children == null) {
                emit("无法访问：${dir.path}")
                return@CliCommand 1
            }
            children
                .sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name })
                .forEach {
                    emit(if (it.isDirectory) "${it.name}/" else "${it.name}  ${CodeTools.humanSize(it.length())}")
                }
            0
        },

        CliCommand(
            name = "cat",
            summary = "打印文件内容（前 200 行）",
            usage = "opencode cat <路径>",
            group = "系统",
        ) { args, cwd, emit ->
            if (args.isEmpty()) {
                emit("用法：opencode cat <路径>")
                return@CliCommand 1
            }
            val file = File(cwd, args.first())
            if (!file.isFile) {
                emit("文件不存在：${args.first()}")
                return@CliCommand 1
            }
            val lines = runCatching { file.readLines(Charsets.UTF_8) }.getOrDefault(emptyList())
            lines.take(200).forEach { emit(it) }
            if (lines.size > 200) emit("… 还有 ${lines.size - 200} 行")
            0
        },
    )

    private fun rel(root: File, file: File): String =
        runCatching { file.relativeTo(root).path }.getOrDefault(file.name)
}
