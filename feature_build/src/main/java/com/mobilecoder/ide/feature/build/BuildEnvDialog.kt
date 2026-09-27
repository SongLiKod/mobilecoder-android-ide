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
import java.io.File
import kotlinx.coroutines.launch

/**
 * 「构建环境」页（PRD 2.7 移动端轻量化 Gradle 编译环境 / 构建环境就绪检测）。
 *
 * 设备端没有系统 JDK / Gradle，因此这里提供：
 *  - 按当前项目**动态显示**所需组件（[BuildEnvironment.requirementsFor]）与就绪状态；
 *  - **在线下载**（官方源 / 国内镜像，静默落位 `files/sdk/`，完成即可用）与 SAF 本地压缩包导入；
 *  - buildVariant 与构建内存上限设置（同时作为 Gradle -Xmx 与看门狗阈值）；
 *  - 「环境体检」把路径与可用性打印到构建日志。
 */
@Composable
fun BuildEnvDialog(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    projectDir: File? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val status by BuildEnvironment.status.collectAsStateWithLifecycle()

    var variant by remember { mutableStateOf("debug") }
    var memoryLimitMb by remember { mutableIntStateOf(2048) }
    var source by remember { mutableStateOf(EnvSource.OFFICIAL) }
    var pendingKind by remember { mutableStateOf(EnvKind.JDK) }
    var importing by remember { mutableStateOf<EnvKind?>(null) }
    var importProgress by remember { mutableFloatStateOf(0f) }
    var downloading by remember { mutableStateOf<EnvKind?>(null) }
    var busyProgress by remember { mutableFloatStateOf(-1f) }
    var stageText by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }

    LaunchedEffect(projectDir) {
        runCatching { BuildEnvironment.refresh(context, projectDir) }
        runCatching { variant = AppStorage.preferences.buildVariant() }
        runCatching { memoryLimitMb = AppStorage.preferences.buildMemoryLimitMb() }
        runCatching {
            source = if (AppStorage.preferences.envDownloadSource() == "mirror") {
                EnvSource.MIRROR
            } else {
                EnvSource.OFFICIAL
            }
        }
    }

    /** 在线下载：JDK/Gradle/Node 走 [EnvDownloader.install]，SDK 走 sdkmanager 链路。 */
    val startDownload: (EnvKind) -> Unit = { kind ->
        downloading = kind
        stageText = ""
        busyProgress = -1f
        message = ""
        scope.launch {
            try {
                val path = if (kind == EnvKind.SDK) {
                    EnvDownloader.installSdk(context, source, { stageText = it }, { busyProgress = it })
                } else {
                    EnvDownloader.install(context, kind, source, { stageText = it }, { busyProgress = it })
                }
                message = "${kind.title} 已安装：$path"
            } catch (c: DownloadCancelled) {
                message = "已取消下载"
            } catch (t: Throwable) {
                message = "下载失败：${t.message ?: t::class.java.simpleName}"
            } finally {
                downloading = null
                stageText = ""
                busyProgress = -1f
                runCatching { BuildEnvironment.refresh(context, projectDir) }
            }
        }
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
                val path = BuildEnvironment.importArchive(context, uri, kind) { p ->
                    importProgress = p
                }
                message = "${kind.title} 已导入：$path"
                runCatching { BuildEnvironment.refresh(context, projectDir) }
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
                    text = "按当前项目动态显示所需组件：可在线静默下载（完成即可用），也可导入本地压缩包（zip / tar.gz）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // ---- 下载源：官方 / 国内镜像（持久化，失败自动回退另一源） ----
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("下载源", style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        EnvSource.entries.forEach { s ->
                            FilterChip(
                                selected = source == s,
                                onClick = {
                                    source = s
                                    scope.launch {
                                        runCatching {
                                            AppStorage.preferences.setEnvDownloadSource(s.name.lowercase())
                                        }
                                    }
                                },
                                label = { Text(s.title, style = MaterialTheme.typography.labelMedium) },
                            )
                        }
                    }
                }
                Text(
                    text = "国内镜像：${EnvSource.MIRROR.subtitle}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                status?.required?.let { required ->
                    Text(
                        text = "当前项目所需：${required.joinToString(" · ") { it.title }}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                status?.items?.forEach { item ->
                    EnvRow(
                        item = item,
                        busy = importing != null || downloading != null,
                        onImport = {
                            pendingKind = item.kind
                            launcher.launch(
                                arrayOf(
                                    "application/zip",
                                    "application/x-zip-compressed",
                                    "application/gzip",
                                    "application/x-gzip",
                                    "application/octet-stream",
                                ),
                            )
                        },
                        onDownload = { startDownload(item.kind) },
                    )
                }

                if (importing != null || downloading != null) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        val p = if (importing != null) importProgress else busyProgress
                        if (p >= 0f) {
                            LinearProgressIndicator(
                                progress = { p.coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = stageText.ifBlank {
                                    "解压中 ${(importProgress * 100).toInt()}%（完成后自动修复可执行权限）"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (downloading != null) {
                                TextButton(onClick = { EnvDownloader.cancel() }) {
                                    Text("取消", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
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
                    valueRange = 512f..8192f,
                    steps = 14,
                )
                Text(
                    text = "构建时写入项目 gradle.properties（org.gradle.jvmargs=-Xmx${memoryLimitMb}m），" +
                        "同时作为看门狗阈值：内存增长超过该值、或系统已用内存超过 90% 时自动中止构建，避免 OOM。",
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
                    scope.launch { runCatching { BuildEnvironment.refresh(context, projectDir) } }
                },
            ) { Text("重新检测") }
        },
    )
}

/** 单项环境状态：标题 + 状态徽标 + 路径 + 「在线下载 / 导入压缩包」按钮 + 未就绪引导。 */
@Composable
private fun EnvRow(
    item: EnvItem,
    busy: Boolean,
    onImport: () -> Unit,
    onDownload: () -> Unit,
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
                    text = item.path ?: "未安装",
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
                text = if (item.ready) "就绪" else "未就绪",
                style = MaterialTheme.typography.labelMedium,
                color = if (item.ready) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
            Spacer(Modifier.width(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (!item.ready) {
                    OutlinedButton(onClick = onDownload, enabled = !busy) {
                        Text("在线下载", style = MaterialTheme.typography.labelMedium)
                    }
                }
                OutlinedButton(onClick = onImport, enabled = !busy) {
                    Text("导入压缩包", style = MaterialTheme.typography.labelMedium)
                }
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
