package com.mobilecoder.ide.feature.cli

import android.content.Context
import com.mobilecoder.ide.core.common.cli.CliTask
import com.mobilecoder.ide.core.common.cli.OpencodeCli
import com.mobilecoder.ide.core.storage.AppStorage
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 面板日志行。 */
data class CliLogLine(
    val text: String,
    val level: Level,
) {
    enum class Level { STDOUT, INFO, WARN, ERROR, INPUT }

    companion object {
        /** 由内置标记 `[ERROR] / [WARN] / [INFO]` 映射到着色级别（日志高亮随主题）。 */
        fun of(text: String): CliLogLine = when {
            text.startsWith("[ERROR]") || text.startsWith("[ERR]") ->
                CliLogLine(text.substringAfter(']').trimStart(), Level.ERROR)
            text.startsWith("[WARN]") ->
                CliLogLine(text.substringAfter(']').trimStart(), Level.WARN)
            text.startsWith("[INFO]") ->
                CliLogLine(text.substringAfter(']').trimStart(), Level.INFO)
            text.contains("失败") || text.contains("错误") || text.contains("未找到") ->
                CliLogLine(text, Level.ERROR)
            text.startsWith("完成") || text.startsWith("已") || text.startsWith("✅") ->
                CliLogLine(text, Level.INFO)
            else -> CliLogLine(text, Level.STDOUT)
        }
    }
}

/**
 * CLI 面板控制器：命令执行、日志流、历史（PRD 2.4「可视化面板一键执行」）。
 *
 * 与终端共享同一引擎（OpencodeCli），因此面板日志与终端输出互不干扰：
 * 面板把自己的 emit 接到 [log]，终端把 emit 接到自己的屏幕缓冲。
 */
object CliController {

    private const val MAX_LOG = 4000

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _log = MutableStateFlow<List<CliLogLine>>(emptyList())
    val log: StateFlow<List<CliLogLine>> = _log.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _task = MutableStateFlow<CliTask?>(null)
    val task: StateFlow<CliTask?> = _task.asStateFlow()

    @Volatile
    private var projectDir: File? = null

    fun init(context: Context) {
        runCatching {
            AppStorage.init(context)
            CliBootstrap.install()
        }
    }

    /** 切换当前项目（App 导航时调用）。 */
    fun setProject(dir: File) {
        projectDir = dir
    }

    val currentProject: File? get() = projectDir

    /** 执行一行命令（自动排队，防止多指令冲突）。 */
    fun run(line: String, dir: File? = null) {
        if (line.isBlank()) return
        val target = dir ?: projectDir
        if (target == null) {
            append(CliLogLine.of("未打开项目，请先在「项目」页新建或打开项目"))
            return
        }
        append(CliLogLine.of("> $line").copy(level = CliLogLine.Level.INPUT))
        _busy.value = true
        scope.launch {
            try {
                OpencodeCli.run(line, target) { output -> append(CliLogLine.of(output)) }
            } finally {
                withContext(Dispatchers.Main) { _busy.value = false }
            }
        }
    }

    fun clear() {
        _log.value = emptyList()
    }

    /** 当前任务快照（用于顶部进度条）。 */
    fun currentTask(): CliTask? = OpencodeCli.currentTask()

    private fun append(line: CliLogLine) {
        val next = _log.value.toMutableList().apply { add(line) }
        _log.value = if (next.size > MAX_LOG) next.takeLast(MAX_LOG) else next
    }
}
