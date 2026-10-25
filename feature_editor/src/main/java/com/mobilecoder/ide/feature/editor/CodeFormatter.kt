package com.mobilecoder.ide.feature.editor

import android.content.Context
import com.mobilecoder.ide.core.common.linux.Proot
import com.mobilecoder.ide.core.common.linux.ProotRunner
import com.mobilecoder.ide.core.storage.AppStorage
import java.io.File

/**
 * 代码格式化器：在 proot（Ubuntu rootfs）内调用命令行格式化器。
 *
 * | 语言 | 工具 | 安装方式 |
 * | --- | --- | --- |
 * | Kotlin / Java（`.kt` `.kts` `.java`） | `ktfmt`（Maven uber-jar + `/usr/local/bin` 包装脚本） | 软件市场 |
 * | JS / TS / HTML / CSS / JSON / Markdown / YAML | `prettier`（`npm install -g`） | 软件市场 |
 *
 * 工具缺失不自动下载：格式化菜单会给出「去软件市场安装」的引导，
 * 与其余按需工具（Node.js、ripgrep …）的口径一致。
 *
 * 执行通道是共享的 [ProotRunner]（`Proot.wrap` + `CliNative.exec`，
 * 与 AI 命令、预览 dev server 同一条链路）。
 */
enum class FormatTool(
    /** guest 内的可执行名（也是探测命令）。 */
    val binary: String,
    /** 软件市场里的条目名（缺失提示用）。 */
    val marketName: String,
    /** 展示名（提示语 / 成功消息）。 */
    val label: String,
) {
    KTFMT("ktfmt", "ktfmt", "ktfmt"),
    PRETTIER("prettier", "prettier", "prettier");

    companion object {
        /** 按扩展名选工具；null = 该文件类型暂不支持格式化。 */
        fun of(path: String): FormatTool? {
            val ext = path.substringAfterLast('.', "").lowercase()
            return when (ext) {
                "kt", "kts", "java" -> KTFMT
                "js", "jsx", "mjs", "cjs", "ts", "tsx",
                "html", "htm", "css", "scss", "less",
                "json", "md", "markdown", "yaml", "yml",
                -> PRETTIER
                else -> null
            }
        }
    }
}

/** 一次格式化的结果。 */
data class FormatResult(
    /** 是否成功跑完（false 时 [message] 是给用户看的原因）。 */
    val ok: Boolean,
    /** 文件内容是否被改动。 */
    val changed: Boolean,
    /** 格式化后的全文（ok = true 时非空，供调用方写回缓冲区）。 */
    val text: String?,
    /** 实际使用的工具名。 */
    val tool: String,
    /** 失败原因（ok = false 时非空）。 */
    val message: String,
)

/** proot 内跑格式化器（「格式化当前文件」菜单项的实现，见 [EditorController.formatActiveFile]）。 */
object CodeFormatter {

    /** 工具探测结果缓存时长：避免每次点菜单都起一次 shell。 */
    private const val PROBE_TTL_MS = 30_000L

    /** 探测超时（首次 proot 启动较慢）。 */
    private const val PROBE_TIMEOUT_MS = 20_000L

    /** 格式化超时：ktfmt / prettier 单文件通常秒级，留足余量。 */
    private const val FORMAT_TIMEOUT_MS = 90_000L

    @Volatile
    private var probedAt = 0L

    @Volatile
    private var probed: Set<String> = emptySet()

    /**
     * 格式化 [path] 指向的文件（调用方保证内容已落盘）。
     *
     * @param projectRoot 命令的工作目录（与编辑器的项目根一致，同路径 bind 后
     * guest 内可见，故可直接传 Android 绝对路径）
     */
    suspend fun format(context: Context, projectRoot: File, path: String): FormatResult {
        val tool = FormatTool.of(path) ?: return FormatResult(
            ok = false,
            changed = false,
            text = null,
            tool = "",
            message = "该文件类型暂不支持格式化",
        )
        if (!Proot.isReady(context)) {
            return fail(
                tool,
                "未安装 Linux 环境（proot + Ubuntu rootfs），无法调用 ${tool.label}；" +
                    "请到「环境中心」在线安装后重试",
            )
        }
        if (tool == FormatTool.KTFMT && javaHome(context) == null) {
            return fail(tool, "未找到 JDK：${tool.label} 需要 Java 运行时，请先到「环境中心」安装 JDK")
        }
        if (tool.binary !in installed(context)) {
            return fail(tool, "未安装 ${tool.label}：请到「软件市场」一键安装后再试")
        }

        val file = File(path)
        val before = runCatching { file.readText(Charsets.UTF_8) }.getOrNull()
            ?: return fail(tool, "文件读取失败：${file.name}")
        val command = when (tool) {
            FormatTool.KTFMT -> "ktfmt ${shellQuote(path)}"
            FormatTool.PRETTIER -> "prettier --write ${shellQuote(path)}"
        }
        val extra = buildMap<String, String> {
            javaHome(context)?.let { put("JAVA_HOME", it) }
        }
        val result = ProotRunner.run(
            context = context,
            command = command,
            cwd = projectRoot,
            env = ProotRunner.baseEnv(context, extra = extra),
            timeoutMs = FORMAT_TIMEOUT_MS,
        )
        if (result.timedOut) return fail(tool, "格式化超时（${FORMAT_TIMEOUT_MS / 1000} 秒），请稍后重试")
        if (result.exitCode != 0) {
            val tail = result.tail()
            return fail(
                tool,
                if (tail.isBlank()) "格式化失败（退出码 ${result.exitCode}）" else tail,
            )
        }
        val after = runCatching { file.readText(Charsets.UTF_8) }.getOrNull()
            ?: return fail(tool, "格式化完成，但读回文件失败：${file.name}")
        return FormatResult(
            ok = true,
            changed = after != before,
            text = after,
            tool = tool.label,
            message = "",
        )
    }

    /**
     * 探测 guest 内已安装的格式化器（一条 shell 同时问 ktfmt / prettier），
     * 结果缓存 [PROBE_TTL_MS]；缺失时每次调用都会重新探测（装完即生效）。
     */
    suspend fun installed(context: Context): Set<String> {
        val now = System.currentTimeMillis()
        val cached = probed
        if (cached.isNotEmpty() && now - probedAt < PROBE_TTL_MS) return cached
        val result = ProotRunner.run(
            context = context,
            command = "for t in ktfmt prettier; do command -v \"\$t\" >/dev/null 2>&1 && echo \"\$t\"; done",
            cwd = context.filesDir,
            env = ProotRunner.baseEnv(context),
            timeoutMs = PROBE_TIMEOUT_MS,
        )
        val found = result.stdout
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
        probedAt = now
        probed = found
        return found
    }

    /** 失败结果（工具名带上，便于提示语回指）。 */
    private fun fail(tool: FormatTool, message: String) = FormatResult(
        ok = false,
        changed = false,
        text = null,
        tool = tool.label,
        message = message,
    )

    /**
     * JDK 根目录：用户配置 → `files/sdk/jdk` → sdk 下任意含 `bin/java` 的目录。
     *
     * 与 `BuildEnvironment.resolveJdk` / `AiCommandRunner.resolveJavaHome` 同口径的
     * 轻量版（feature_editor 不依赖 feature_build，故此处自带一份）。
     */
    private suspend fun javaHome(context: Context): String? {
        val configured = runCatching { AppStorage.preferences.jdkPath() }.getOrDefault("")
        if (configured.isNotBlank() && File(configured, "bin/java").exists()) return configured
        val sdk = File(context.filesDir, "sdk")
        val direct = File(sdk, "jdk")
        if (File(direct, "bin/java").exists()) return direct.absolutePath
        return sdk.listFiles()?.firstNotNullOfOrNull { dir ->
            when {
                !dir.isDirectory -> null
                File(dir, "bin/java").exists() -> dir.absolutePath
                else -> dir.listFiles()?.firstOrNull { inner ->
                    inner.isDirectory && File(inner, "bin/java").exists()
                }?.absolutePath
            }
        }
    }

    /**
     * 单引号包裹路径：文件名可能含空格 / 引号 / `$`，
     * 内部的 `'` 转义成 `'\''`（shell 里拼接回单引号段的标准写法）。
     */
    internal fun shellQuote(path: String): String = "'" + path.replace("'", "'\\''") + "'"
}
