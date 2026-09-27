package com.mobilecoder.ide.feature.build

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mobilecoder.ide.core.storage.AppStorage
import kotlinx.coroutines.launch

/**
 * 「构建环境」页（PRD 2.7 移动端轻量化 Gradle 编译环境 / 构建环境就绪检测）。
 *
 * 设备端没有系统 JDK / Gradle，因此这里提供：
 *  - JDK / Gradle / Android SDK 三项就绪状态与目录展示；
 *  - SAF 选择本地 zip 导入到 `files/sdk/`（进度 + 校验 + 幂等，全程不联网）；
 *  - buildVariant 与构建内存上限设置（AppPreferences 读写）；
 *  - 「环境体检」把路径与可用性打印到构建日志。
 */
@Composable
fun BuildEnvDialog(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val status by BuildEnvironment.status.collectAsStateWithLifecycle()

    var variant by remember { mutableStateOf("debug") }
    var memoryLimitMb by remember { mutableIntStateOf(1024) }
    var pendingKind by remember { mutableStateOf(EnvKind.JDK) }
    var importing by remember { mutableStateOf<EnvKind?>(null) }
    var importProgress by remember { mutableFloatStateOf(0f) }
    var message by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        runCatching { BuildEnvironment.refresh(context) }
        runCatching { variant = AppStorage.preferences.buildVariant() }
        runCatching { memoryLimitMb = AppStorage.preferences.buildMemoryLimitMb() }
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val kind = pendingKind
        scope.launch {
            importing = kind
            importProgress = 0f
            message = "正在解压 ${kind.title} …"
            try {
                val path = BuildEnvironment.importZip(context, uri, kind) { p ->
                    importProgress = p
                }
                message = "${kind.title} 已导入：$path"
                runCatching { BuildEnvironment.refresh(context) }
            } catch (t: Throwable) {
                message = "导入失败：${t.message ?: t::class.java.simpleName}"
            } finally {
                importing = null
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        title = {
            Text("构建环境", style = MaterialTheme.typography.titleLarge)
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "本页所有组件均通过本地 zip 离线导入，不会联网下载。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                status?.items?.forEach { item ->
                    EnvRow(
                        item = item,
                        busy = importing != null,
                        onImport = {
                            pendingKind = item.kind
                            launcher.launch(
                                arrayOf(
                                    "application/zip",
                                    "application/x-zip-compressed",
                                    "application/octet-stream",
                                ),
                            )
                        },
                    )
                }

                if (importing != null) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        LinearProgressIndicator(
                            progress = { importProgress },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            text = "解压中 ${(importProgress * 100).toInt()}%（完成后会自动修复可执行权限）",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                if (message.isNotBlank()) {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                HorizontalDivider()

                // ---- 构建变体 ----
                Text("构建变体", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("debug" to "Debug", "release" to "Release").forEach { (value, label) ->
                        FilterChip(
                            selected = variant == value,
                            onClick = {
                                variant = value
                                scope.launch {
                                    runCatching { AppStorage.preferences.setBuildVariant(value) }
                                }
                            },
                            label = { Text(label) },
                        )
                    }
                }

                // ---- 内存上限 ----
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("构建内存上限", style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = "${memoryLimitMb} MB",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Slider(
                    value = memoryLimitMb.toFloat(),
                    onValueChange = { memoryLimitMb = it.toInt() },
                    onValueChangeFinished = {
                        scope.launch {
                            runCatching { AppStorage.preferences.setBuildMemoryLimitMb(memoryLimitMb) }
                        }
                    },
                    valueRange = 256f..4096f,
                    steps = 14,
                )
                Text(
                    text = "构建期间内存增长超过该值、或系统已用内存超过 90% 时会自动中止构建，避免 OOM 崩溃。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                OutlinedButton(
                    onClick = { BuildRunner.runHealthCheck() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("环境体检（打印到构建日志）")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("完成") }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    scope.launch { runCatching { BuildEnvironment.refresh(context) } }
                },
            ) { Text("重新检测") }
        },
    )
}

/** 单项环境状态：标题 + 状态徽标 + 路径 + 导入按钮 + 未就绪引导。 */
@Composable
private fun EnvRow(
    item: EnvItem,
    busy: Boolean,
    onImport: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f, fill = false)) {
                Text(
                    text = item.kind.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = item.path ?: "未导入",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = if (item.ready) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (item.ready) "就绪" else "缺失",
                style = MaterialTheme.typography.labelMedium,
                color = if (item.ready) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = onImport, enabled = !busy) {
                Text("导入 zip", style = MaterialTheme.typography.labelMedium)
            }
        }
        if (!item.ready) {
            Text(
                text = item.hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 2.dp),
            )
        }
    }
}
