package com.mobilecoder.ide.feature.cli

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mobilecoder.ide.core.common.cli.OpencodeCli
import com.mobilecoder.ide.core.common.theme.LocalAppPalette
import com.mobilecoder.ide.core.common.ui.EmptyState
import com.mobilecoder.ide.core.common.ui.SectionHeader
import java.io.File

/**
 * PRD 2.4「OpenCode 类 CLI 工具链」可视化面板：
 * 一键执行 + 实时日志 + 历史 + 自定义命令行。
 *
 * 与终端共用 [OpencodeCli] 引擎，支持协程队列串行化（TECH.md 4.3）。
 */
@Composable
fun CliScreen(
    projectPath: String,
    modifier: Modifier = Modifier,
) {
    val projectDir = remember(projectPath) { File(projectPath) }
    val palette = LocalAppPalette.current
    val log by CliController.log.collectAsStateWithLifecycle()
    val busy by CliController.busy.collectAsStateWithLifecycle()
    val history by OpencodeCli.history.collectAsStateWithLifecycle()
    val commands = remember { OpencodeCli.commands() }

    var tab by rememberSaveable { mutableIntStateOf(0) }
    var input by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(projectPath) { CliController.setProject(projectDir) }

    // 自动滚到底部（有新输出时）
    val listState = rememberLazyListState()
    LaunchedEffect(log.size) {
        if (log.isNotEmpty()) listState.animateScrollToItem(log.size - 1)
    }

    Column(
        modifier = modifier.fillMaxSize(),
    ) {
        SectionHeader(
            title = "OpenCode CLI",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            action = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = projectDir.name.ifBlank { projectPath },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(end = 4.dp),
                    )
                    IconButton(onClick = { CliController.clear() }, enabled = log.isNotEmpty()) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = "清空日志")
                    }
                }
            },
        )

        TabRow(selectedTabIndex = tab) {
            Tab(
                selected = tab == 0,
                onClick = { tab = 0 },
                text = { Text("日志 (${log.size})") },
            )
            Tab(
                selected = tab == 1,
                onClick = { tab = 1 },
                text = { Text("命令") },
            )
        }

        if (busy) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        when (tab) {
            0 -> LogPane(
                log = log,
                listState = listState,
                palette = palette,
                modifier = Modifier.weight(1f),
            )

            else -> CommandPane(
                commands = commands,
                history = history,
                busy = busy,
                onRun = { line ->
                    tab = 0
                    CliController.run(line, projectDir)
                },
                modifier = Modifier.weight(1f),
            )
        }

        HorizontalDivider()

        // 自定义命令行输入
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                enabled = !busy,
                placeholder = { Text("opencode …", style = MaterialTheme.typography.bodyMedium) },
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                trailingIcon = {
                    if (busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(4.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                },
            )
            if (busy) {
                // 执行中可随时停止（取消协程 + 中止 native 网络传输）
                IconButton(onClick = { CliController.cancel() }) {
                    Icon(
                        imageVector = Icons.Default.Stop,
                        contentDescription = "停止",
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
            IconButton(
                onClick = {
                    val line = input.trim()
                    if (line.isNotEmpty()) {
                        input = ""
                        tab = 0
                        CliController.run(line, projectDir)
                    }
                },
                enabled = !busy && input.isNotBlank(),
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "执行")
            }
        }
    }
}

@Composable
private fun LogPane(
    log: List<CliLogLine>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    palette: com.mobilecoder.ide.core.common.theme.AppPalette,
    modifier: Modifier = Modifier,
) {
    if (log.isEmpty()) {
        EmptyState(
            title = "暂无输出",
            subtitle = "在下方「命令」页一键执行，或直接输入 opencode 命令",
            modifier = modifier.fillMaxWidth(),
        )
        return
    }
    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        state = listState,
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        items(log.size) { index ->
            val line = log[index]
            Text(
                text = line.text,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = logColor(line.level, palette, MaterialTheme.colorScheme.onSurface),
            )
        }
    }
}

private fun logColor(
    level: CliLogLine.Level,
    palette: com.mobilecoder.ide.core.common.theme.AppPalette,
    fallback: Color,
): Color = when (level) {
    CliLogLine.Level.ERROR -> Color(0xFF000000 or palette.logError.rgb)
    CliLogLine.Level.WARN -> Color(0xFF000000 or palette.logWarn.rgb)
    CliLogLine.Level.INFO -> Color(0xFF000000 or palette.logInfo.rgb)
    CliLogLine.Level.INPUT -> Color(0xFF000000 or palette.primary.rgb)
    CliLogLine.Level.STDOUT -> fallback
}

@Composable
private fun CommandPane(
    commands: List<com.mobilecoder.ide.core.common.cli.CliCommand>,
    history: List<String>,
    busy: Boolean,
    onRun: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val grouped = remember(commands) { commands.groupBy { it.group } }
    LazyColumn(
        modifier = modifier
            .fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (history.isNotEmpty()) {
            item(key = "history") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    history.take(8).forEach { line ->
                        Card(
                            onClick = { if (!busy) onRun(line) },
                            enabled = !busy,
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            ),
                        ) {
                            Text(
                                text = line,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }

        grouped.forEach { (group, items) ->
            item(key = "group-$group") {
                Text(
                    text = group,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 16.dp, top = 10.dp, bottom = 2.dp),
                    fontWeight = FontWeight.SemiBold,
                )
            }
            items(items, key = { it.name }) { command ->
                Card(
                    onClick = { if (!busy) onRun("opencode ${command.name}") },
                    enabled = !busy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = command.name,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onBackground,
                            )
                            Text(
                                text = "运行",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Text(
                            text = command.summary,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = command.usage,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}
