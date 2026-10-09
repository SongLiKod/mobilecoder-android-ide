package com.mobilecoder.ide.feature.build

import java.io.File

/**
 * PRD 2.7 安卓项目编译打包模块的共享数据模型。
 *
 * 全部为不可变 data class，配合 StateFlow 使用：
 * JNI 回调线程只负责产生新快照，Compose 侧在主线程收集（线程安全）。
 */

/** 构建日志级别：ERROR 红 / WARN 黄 / SUCCESS 绿 / INFO·INPUT 取主题色（PRD 2.1 日志高亮随主题）。 */
enum class BuildLogLevel { INFO, WARN, ERROR, SUCCESS, INPUT }

/** 一行构建日志（id 单调递增，供 LazyColumn key）。 */
data class BuildLogLine(
    val id: Long,
    val text: String,
    val level: BuildLogLevel,
    /** 1=stdout，2=stderr（子进程输出通道）。 */
    val stream: Int = 1,
    val at: Long = System.currentTimeMillis(),
)

/** 一次构建请求：UI「开始构建」按钮的统一入口。 */
data class BuildRequest(
    val projectDir: File,
    /** `debug` / `release`（对应 assembleDebug / assembleRelease）。 */
    val variant: String = "debug",
    val clean: Boolean = false,
    /** 高级选项：附加 Gradle 任务名（已做安全字符校验）。 */
    val extraTasks: List<String> = emptyList(),
)

/** 构建任务快照（进入 Running 后不再变化）。 */
data class BuildTask(
    val projectPath: String,
    /** `debug` / `release`（安卓）；`assemble` / `build` / `package`（其它类型）。 */
    val variant: String,
    val clean: Boolean,
    val extraTasks: List<String>,
    val startedAt: Long,
    /** 展示用任务名：`assembleDebug` / `assemble` / `npm run build` / `打包源码`。 */
    val label: String = variant,
)

/**
 * 构建状态机：
 * Idle → Preparing → Running → Success(产物路径) / Failed(错误)。
 */
sealed interface BuildState {

    /** 空闲：没有构建任务。 */
    data object Idle : BuildState

    /** 准备中：校验工程与构建环境、组装命令。 */
    data class Preparing(val message: String) : BuildState

    /** 构建中：子进程已启动（或进程内打包进行中）。 */
    data class Running(val task: BuildTask) : BuildState

    /** 构建成功：附带产出的产物绝对路径（APK / JAR / 压缩包）。 */
    data class Success(
        val task: BuildTask,
        val artifacts: List<String>,
        val durationMs: Long,
    ) : BuildState

    /** 构建失败 / 被取消 / 内存保护中止。 */
    data class Failed(
        val message: String,
        val task: BuildTask? = null,
        val durationMs: Long = 0,
    ) : BuildState
}

/**
 * 解析出的编译错误条目（TECH 4.6「编译日志实时流式输出 + 错误精准定位」）。
 *
 * 点击后由 [BuildRunner.requestJump] 记录跳转目标，供后续编辑器联动。
 */
data class BuildError(
    val message: String,
    val file: String? = null,
    val line: Int? = null,
    val column: Int? = null,
    val raw: String = "",
    /** 文件是否位于当前工程内（在工程内才可跳转编辑器）。 */
    val inProject: Boolean = false,
) {
    /** 展示用位置文本：`文件:行:列`。 */
    val location: String
        get() = when {
            file.isNullOrBlank() -> "构建"
            line == null -> file
            column == null -> "$file:$line"
            else -> "$file:$line:$column"
        }
}

/** 一个构建产物（APK / JAR / dist 压缩包 / 源码压缩包）。 */
data class BuildArtifact(
    /** 文件名，例如 `app-debug.apk`。 */
    val name: String,
    /** 所属模块（相对工程根，例如 `app`；根模块为空串）。 */
    val module: String,
    /** `debug` / `release` / `unknown`。 */
    val variant: String,
    val size: Long,
    val modifiedAt: Long,
    val path: String,
    /** 文件名含 `-unsigned` 表示未签名，安装前需要配置签名。 */
    val unsigned: Boolean = name.contains("unsigned", ignoreCase = true),
)

/** 一条构建历史（内存 + files/build_history.json，最近 10 条）。 */
data class BuildHistoryEntry(
    val time: Long,
    val projectPath: String,
    val variant: String,
    val durationMs: Long,
    val success: Boolean,
    val apkPath: String,
    val message: String,
)

/**
 * 内存采样（PRD 2.7「内存管控，避免 OOM 崩溃」）。
 *
 * @param buildUsedMb 构建启动以来的系统已用内存增长（与 [limitMb] 同口径比较）
 * @param systemUsedMb 系统当前已用内存
 * @param totalMb 系统总内存
 * @param usedRatio 系统已用占比 0..1（> 0.90 触发保护）
 * @param limitMb 用户配置的构建内存上限（AppPreferences.buildMemoryLimitMb）
 */
data class MemorySample(
    val buildUsedMb: Long,
    val systemUsedMb: Long,
    val totalMb: Long,
    val usedRatio: Float,
    val limitMb: Int,
) {
    /** 内存仪表进度 0..1（增长量 / 上限）。 */
    val gauge: Float
        get() = if (limitMb <= 0) 0f else (buildUsedMb.toFloat() / limitMb.toFloat()).coerceIn(0f, 1f)
}

/** 时长格式化：`820ms` / `45 秒` / `1 分 23 秒`。 */
fun formatDuration(ms: Long): String = when {
    ms < 1000 -> "${ms}ms"
    ms < 60_000 -> "${ms / 1000} 秒"
    else -> "${ms / 60_000} 分 ${((ms % 60_000) / 1000)} 秒"
}
