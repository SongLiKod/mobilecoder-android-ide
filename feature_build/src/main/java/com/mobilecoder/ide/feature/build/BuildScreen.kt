package com.mobilecoder.ide.feature.build

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Store
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mobilecoder.ide.core.common.theme.LocalAppPalette
import com.mobilecoder.ide.core.common.ui.EmptyState
import com.mobilecoder.ide.core.common.ui.SectionHeader
import com.mobilecoder.ide.core.storage.AppStorage
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * PRD 2.7 安卓项目编译打包页面。
 *
 * 布局（Column，不加 bottomBar，由 app 的 Scaffold 提供底部导航）：
 *  1. 工程信息 + 环境就绪徽标 + 「构建环境」入口
 *  2. 变体选择 + 开始/取消构建 + Clean + 内存仪表
 *  3. 阶段进度条
 *  4. 实时日志（自动滚动 / 暂停 / 清空 / 复制）
 *  5. 错误列表（可折叠，点击记录跳转目标）
 *  6. APK 产物（安装 / 分享 / 复制路径 / 打开目录）
 *  7. 构建历史（可折叠）
 *
 * 颜色全部取自 [LocalAppPalette] / MaterialTheme.colorScheme（PRD 2.1 日志高亮随主题）。
 */
@Composable
fun BuildScreen(
    projectPath: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val palette = LocalAppPalette.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val projectDir = remember(projectPath) { File(projectPath) }
    val isProject = remember(projectPath) { BuildRunner.isGradleProject(projectDir) }

    val state by BuildRunner.state.collectAsStateWithLifecycle()
    val logs by BuildRunner.logs.collectAsStateWithLifecycle()
    val phase by BuildRunner.phase.collectAsStateWithLifecycle()
    val progress by BuildRunner.progress.collectAsStateWithLifecycle()
    val memory by BuildRunner.memory.collectAsStateWithLifecycle()
    val errors by BuildRunner.errors.collectAsStateWithLifecycle()
    val artifacts by BuildRunner.artifacts.collectAsStateWithLifecycle()
    val history by BuildRunner.history.collectAsStateWithLifecycle()
    val envStatus by BuildEnvironment.status.collectAsStateWithLifecycle()

    var variant by rememberSaveable(projectPath) { mutableStateOf("debug") }
    var clean by rememberSaveable { mutableStateOf(false) }
    var autoScroll by rememberSaveable { mutableStateOf(true) }
    var showErrors by rememberSaveable { mutableStateOf(true) }
    var showArtifacts by rememberSaveable { mutableStateOf(true) }
    var showHistory by rememberSaveable { mutableStateOf(false) }
    var showAdvanced by rememberSaveable { mutableStateOf(false) }
    var extraTasksText by rememberSaveable { mutableStateOf("") }
    var showEnv by rememberSaveable { mutableStateOf(false) }
    var showMarket by rememberSaveable { mutableStateOf(false) }
    var hint by remember { mutableStateOf("") }
    var selectedError by remember { mutableStateOf<BuildError?>(null) }
    var elapsedMs by remember { mutableStateOf(0L) }

    LaunchedEffect(projectPath) {
        BuildRunner.init(context)
        runCatching { BuildEnvironment.refresh(context, projectDir) }
        runCatching { variant = AppStorage.preferences.buildVariant() }
    }

    val running = state is BuildState.Running || state is BuildState.Preparing

    // 计时：构建中每秒刷新一次耗时
    LaunchedEffect(state) {
        val task = (state as? BuildState.Running)?.task
        elapsedMs = 0L
        if (task != null) {
            while (true) {
                elapsedMs = System.currentTimeMillis() - task.startedAt
                delay(1000)
            }
        }
    }

    // 日志自动滚动到底部
    val listState = rememberLazyListState()
    LaunchedEffect(logs.size) {
        if (autoScroll && logs.isNotEmpty()) listState.animateScrollToItem(logs.size - 1)
    }

    if (showEnv) {
        BuildEnvDialog(onDismiss = { showEnv = false }, projectDir = projectDir)
    }

    // 软件市场：整页接管本 Tab（安装在 MarketInstaller 单例里静默跑，返回不中断）
    if (showMarket) {
        MarketScreen(onBack = { showMarket = false }, modifier = modifier)
        return
    }

    Column(modifier = modifier.fillMaxSize()) {

        // ---------------- 顶部：工程信息 + 环境徽标 + 入口 ----------------
        SectionHeader(
            title = "编译构建",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            action = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    EnvBadge(ready = envStatus?.ready == true)
                    IconButton(onClick = { showMarket = true }) {
                        Icon(Icons.Default.Store, contentDescription = "软件市场")
                    }
                    IconButton(onClick = { showEnv = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "构建环境")
                    }
                }
            },
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = projectDir.name.ifBlank { projectPath },
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = projectPath,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        if (!isProject) {
            val isNodeProject = remember(projectPath) { File(projectPath, "package.json").exists() }
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                ),
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = if (isNodeProject) "该项目是 Node / Vue 工程" else "该项目不是安卓 Gradle 工程",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Text(
                        text = if (isNodeProject) {
                            "Node 工程无需 Gradle 构建：请点右上角「构建环境」在线下载 Node.js，再在「终端」执行 npm install / npm run build。"
                        } else {
                            "缺少 settings.gradle / build.gradle。请在「项目」页新建「安卓应用」项目生成骨架后再构建。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }

        // ---------------- 变体 + Clean ----------------
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.weight(1f)) {
                listOf("debug" to "Debug", "release" to "Release").forEachIndexed { index, (value, label) ->
                    SegmentedButton(
                        selected = variant == value,
                        onClick = { if (!running) variant = value },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = 2),
                    ) {
                        Text(label, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            FilterChip(
                selected = clean,
                onClick = { if (!running) clean = !clean },
                label = { Text("Clean") },
            )
        }

        // ---------------- 主操作 + 内存仪表 ----------------
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = {
                    hint = ""
                    if (running) {
                        BuildRunner.cancel()
                    } else {
                        scope.launch {
                            runCatching { AppStorage.preferences.setBuildVariant(variant) }
                            val ok = BuildRunner.startBuild(
                                BuildRequest(
                                    projectDir = projectDir,
                                    variant = variant,
                                    clean = clean,
                                    extraTasks = BuildRunner.parseExtraTasks(extraTasksText),
                                ),
                            )
                            if (!ok) hint = "构建未能启动：请稍后重试（可能已有构建在进行）"
                        }
                    }
                },
                enabled = isProject || running,
            ) {
                Icon(
                    imageVector = if (running) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 4.dp),
                )
                Text(if (running) "取消构建" else "开始构建")
            }
            Spacer(Modifier.width(4.dp))
            MemoryGauge(sample = memory, modifier = Modifier.weight(1f))
        }

        // ---------------- 高级选项：附加 Gradle 任务（默认空 = 安全默认值） ----------------
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { showAdvanced = !showAdvanced }) {
                Text(
                    text = if (showAdvanced) "收起高级选项" else "高级选项",
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            Spacer(Modifier.weight(1f))
            if (extraTasksText.isNotBlank()) {
                Text(
                    text = "附加：${BuildRunner.parseExtraTasks(extraTasksText).joinToString(" ")}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(end = 16.dp),
                )
            }
        }
        if (showAdvanced) {
            OutlinedTextField(
                value = extraTasksText,
                onValueChange = { if (!running) extraTasksText = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                enabled = !running,
                singleLine = true,
                label = { Text("附加 Gradle 任务（可选，空格分隔）") },
                placeholder = { Text("例如：lint test") },
                textStyle = MaterialTheme.typography.bodySmall,
            )
        }

        // ---------------- 阶段进度 ----------------
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            color = when (state) {
                is BuildState.Success -> Color(0xFF000000 or palette.success.rgb)
                is BuildState.Failed -> Color(0xFF000000 or palette.logError.rgb)
                else -> MaterialTheme.colorScheme.primary
            },
        )
        StatusLine(state = state, phase = phase, elapsedMs = elapsedMs)

        // ---------------- 实时日志 ----------------
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "实时日志（${logs.size}）",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { autoScroll = !autoScroll }) {
                Icon(
                    imageVector = if (autoScroll) Icons.Default.Pause else Icons.Default.ArrowDownward,
                    contentDescription = if (autoScroll) "暂停自动滚动" else "恢复自动滚动",
                    tint = if (autoScroll) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            IconButton(
                onClick = {
                    clipboard.setText(AnnotatedString(logs.joinToString("\n") { it.text }))
                    hint = "已复制全部日志（${logs.size} 行）"
                },
                enabled = logs.isNotEmpty(),
            ) {
                Icon(Icons.Default.ContentCopy, contentDescription = "复制全部日志")
            }
            IconButton(onClick = { BuildRunner.clearLogs() }, enabled = logs.isNotEmpty()) {
                Icon(Icons.Default.DeleteSweep, contentDescription = "清空日志")
            }
        }

        LogPane(
            logs = logs,
            listState = listState,
            palette = palette,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .background(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                    shape = RoundedCornerShape(8.dp),
                )
                .padding(horizontal = 8.dp, vertical = 6.dp),
        )

        // ---------------- 底部：错误 / 产物 / 历史（可折叠，内部滚动） ----------------
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 280.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (errors.isNotEmpty()) {
                SectionHeader(
                    title = "错误定位（${errors.size}）",
                    action = {
                        IconButton(onClick = { showErrors = !showErrors }) {
                            Icon(
                                imageVector = if (showErrors) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = if (showErrors) "收起错误" else "展开错误",
                            )
                        }
                    },
                )
                if (showErrors) {
                    errors.takeLast(50).forEach { error ->
                        ErrorRow(
                            error = error,
                            selected = selectedError == error,
                            onClick = {
                                selectedError = error
                                BuildRunner.requestJump(error)
                                hint = if (error.inProject) {
                                    "已记录跳转目标：${error.location}"
                                } else {
                                    "该文件不在工程内，无法跳转：${error.location}"
                                }
                            },
                        )
                    }
                }
                HorizontalDivider()
            }

            if (artifacts.isNotEmpty() || state is BuildState.Success) {
                SectionHeader(
                    title = "APK 产物（${artifacts.size}）",
                    action = {
                        IconButton(onClick = { showArtifacts = !showArtifacts }) {
                            Icon(
                                imageVector = if (showArtifacts) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = if (showArtifacts) "收起产物" else "展开产物",
                            )
                        }
                    },
                )
                if (showArtifacts) {
                    if (artifacts.isEmpty()) {
                        Text(
                            text = "本次构建未发现 APK 产物",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        artifacts.forEach { artifact ->
                            ArtifactRow(
                                artifact = artifact,
                                onHint = { hint = it },
                            )
                        }
                    }
                }
                HorizontalDivider()
            }

            if (history.isNotEmpty()) {
                SectionHeader(
                    title = "构建历史（${history.size}）",
                    action = {
                        IconButton(onClick = { showHistory = !showHistory }) {
                            Icon(
                                imageVector = if (showHistory) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = if (showHistory) "收起历史" else "展开历史",
                            )
                        }
                    },
                )
                if (showHistory) {
                    history.forEach { entry -> HistoryRow(entry) }
                }
            }

            if (hint.isNotBlank()) {
                Text(
                    text = hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

// ---------------------------------------------------------------------------
// 组件
// ---------------------------------------------------------------------------

/** 环境就绪徽标：就绪绿 / 缺失红（随主题调色板）。 */
@Composable
private fun EnvBadge(ready: Boolean) {
    val palette = LocalAppPalette.current
    val color = if (ready) {
        Color(0xFF000000 or palette.success.rgb)
    } else {
        Color(0xFF000000 or palette.logError.rgb)
    }
    Surface(
        shape = RoundedCornerShape(50),
        color = color.copy(alpha = 0.14f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = if (ready) "●" else "▲",
                style = MaterialTheme.typography.labelSmall,
                color = color,
            )
            Text(
                text = if (ready) "环境就绪" else "环境缺失",
                style = MaterialTheme.typography.labelMedium,
                color = color,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/** 内存仪表：构建期增长 / 用户设定上限（PRD 内存管控）。 */
@Composable
private fun MemoryGauge(sample: MemorySample?, modifier: Modifier = Modifier) {
    val palette = LocalAppPalette.current
    val ratio = sample?.gauge ?: 0f
    val color = when {
        sample == null -> MaterialTheme.colorScheme.primary
        ratio >= 1f -> Color(0xFF000000 or palette.logError.rgb)
        ratio >= 0.8f -> Color(0xFF000000 or palette.logWarn.rgb)
        else -> Color(0xFF000000 or palette.success.rgb)
    }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "内存",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = if (sample == null) {
                    "未采样 / 限制 — MB"
                } else {
                    "构建期 +${sample.buildUsedMb} MB / 限制 ${sample.limitMb} MB · 系统 ${(sample.usedRatio * 100).toInt()}%"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        LinearProgressIndicator(
            progress = { ratio },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp),
            color = color,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
    }
}

/** 阶段状态行（待命 / 准备中 / 构建中耗时 / 成功 / 失败原因）。 */
@Composable
private fun StatusLine(state: BuildState, phase: String, elapsedMs: Long) {
    val palette = LocalAppPalette.current
    val (text, color) = when (state) {
        BuildState.Idle -> phase to MaterialTheme.colorScheme.onSurfaceVariant
        is BuildState.Preparing -> state.message to MaterialTheme.colorScheme.primary
        is BuildState.Running -> "构建中 · ${state.task.variant} · 已用 ${formatDuration(elapsedMs)}" to
            MaterialTheme.colorScheme.primary
        is BuildState.Success -> "构建成功 · ${state.apks.size} 个 APK · 耗时 ${formatDuration(state.durationMs)}" to
            Color(0xFF000000 or palette.success.rgb)
        is BuildState.Failed -> state.message to Color(0xFF000000 or palette.logError.rgb)
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = color,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
}

/** 日志面板：等宽字体 + 级别着色。 */
@Composable
private fun LogPane(
    logs: List<BuildLogLine>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    palette: com.mobilecoder.ide.core.common.theme.AppPalette,
    modifier: Modifier = Modifier,
) {
    if (logs.isEmpty()) {
        Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
            EmptyState(
                title = "暂无构建日志",
                subtitle = "点击「开始构建」后实时输出编译日志",
            )
        }
        return
    }
    LazyColumn(state = listState, modifier = modifier) {
        items(logs, key = { it.id }) { line ->
            Text(
                text = line.text,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                ),
                color = logColor(line.level, palette, MaterialTheme.colorScheme.onSurface),
            )
        }
    }
}

private fun logColor(
    level: BuildLogLevel,
    palette: com.mobilecoder.ide.core.common.theme.AppPalette,
    fallback: Color,
): Color = when (level) {
    BuildLogLevel.ERROR -> Color(0xFF000000 or palette.logError.rgb)
    BuildLogLevel.WARN -> Color(0xFF000000 or palette.logWarn.rgb)
    BuildLogLevel.SUCCESS -> Color(0xFF000000 or palette.success.rgb)
    BuildLogLevel.INPUT -> Color(0xFF000000 or palette.primary.rgb)
    BuildLogLevel.INFO -> fallback
}

/** 错误条目：文件:行:消息，点击记录跳转目标。 */
@Composable
private fun ErrorRow(
    error: BuildError,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val palette = LocalAppPalette.current
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(6.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = Color(0xFF000000 or palette.logError.rgb),
                modifier = Modifier.padding(top = 2.dp),
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = error.location,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = if (error.inProject) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = error.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 产物行：名称 / 模块 / 变体 / 大小 / 时间 + 安装·分享·复制路径。 */
@Composable
private fun ArtifactRow(
    artifact: BuildArtifact,
    onHint: (String) -> Unit,
) {
    val context = LocalContext.current
    val file = remember(artifact.path) { File(artifact.path) }
    val time = remember(artifact.modifiedAt) {
        SimpleDateFormat("MM-dd HH:mm", Locale.ROOT).format(Date(artifact.modifiedAt))
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = artifact.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "${artifact.variant} · ${humanSize(artifact.size)} · $time",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = "${artifact.module} · ${artifact.path}",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (artifact.unsigned) {
            Text(
                text = "未签名 APK：无法直接安装，请配置签名或改用 Debug 构建",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF000000 or LocalAppPalette.current.logWarn.rgb),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = {
                onHint(ApkActions.install(context, file) ?: "已发起安装：${artifact.name}")
            }) { Text("安装") }
            TextButton(onClick = {
                onHint(ApkActions.share(context, file) ?: "已打开分享面板")
            }) { Text("分享") }
            TextButton(onClick = {
                onHint(ApkActions.copyPath(context, file) ?: "路径已复制")
            }) { Text("复制路径") }
            TextButton(onClick = {
                onHint(ApkActions.openDirectory(context, file.parentFile ?: file) ?: "已打开所在目录")
            }) { Text("打开目录") }
        }
    }
}

/** 历史行：时间 / 变体 / 耗时 / 结果 / APK。 */
@Composable
private fun HistoryRow(entry: BuildHistoryEntry) {
    val palette = LocalAppPalette.current
    val time = remember(entry.time) {
        SimpleDateFormat("MM-dd HH:mm", Locale.ROOT).format(Date(entry.time))
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = time,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = entry.variant,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = formatDuration(entry.durationMs),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = if (entry.success) "成功" else "失败",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = if (entry.success) {
                Color(0xFF000000 or palette.success.rgb)
            } else {
                Color(0xFF000000 or palette.logError.rgb)
            },
        )
        Text(
            text = entry.apkPath.ifBlank { entry.message },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

private fun humanSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
