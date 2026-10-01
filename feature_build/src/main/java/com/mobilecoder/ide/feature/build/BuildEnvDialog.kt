package com.mobilecoder.ide.feature.build

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
 * 「构建环境」内容体（原「构建环境」对话框，已改为「环境中心」页的构建环境分段内容）。
 * PRD 2.7 移动端轻量化 Gradle 编译环境 / 构建环境就绪检测。
 *
 * 设备端没有系统 JDK / Gradle，因此这里提供：
 *  - 按当前项目**动态显示**所需组件（[BuildEnvironment.requirementsFor]）与就绪状态
 *    （[projectDir] 为 null 时按默认（安卓）项目类型展示）；
 *  - **在线下载**（官方源 / 国内镜像，静默落位 `files/sdk/`，完成即可用）与 SAF 本地压缩包导入；
 *  - **Linux 环境 rootfs 镜像管理**（Ubuntu 官方 / 清华 TUNA 多内置源自动回退 + 点选首选 + 手动输入自定义源，持久化）；
 *  - 构建内存上限设置（同时作为 Gradle -Xmx 与看门狗阈值；构建变体仍在「构建与运行」页选择）；
 *  - 「重新检测」与「环境体检」（把路径与可用性打印到构建日志）。
 *
 * 页面路由、返回栏与「环境中心 / 软件市场」分段切换由 [EnvironmentScreen] 负责，
 * 本函数只输出可滚动的内容体。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EnvironmentContent(
    projectDir: File? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val status by BuildEnvironment.status.collectAsStateWithLifecycle()

    var memoryLimitMb by remember { mutableIntStateOf(2048) }
    var source by remember { mutableStateOf(EnvSource.OFFICIAL) }
    var pendingKind by remember { mutableStateOf(EnvKind.JDK) }
    var importing by remember { mutableStateOf<EnvKind?>(null) }
    var importProgress by remember { mutableFloatStateOf(0f) }
    var downloading by remember { mutableStateOf<EnvKind?>(null) }
    var busyProgress by remember { mutableFloatStateOf(-1f) }
    var stageText by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var linuxCustom by remember { mutableStateOf("") }
    var linuxPreferred by remember { mutableStateOf("") }

    LaunchedEffect(projectDir) {
        runCatching { BuildEnvironment.refresh(context, projectDir) }
        runCatching { memoryLimitMb = AppStorage.preferences.buildMemoryLimitMb() }
        runCatching {
            source = if (AppStorage.preferences.envDownloadSource() == "mirror") {
                EnvSource.MIRROR
            } else {
                EnvSource.OFFICIAL
            }
        }
        runCatching { linuxCustom = AppStorage.preferences.linuxCustomSource() }
        runCatching { linuxPreferred = AppStorage.preferences.linuxPreferredSource() }
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

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
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

        // ---- Linux 环境 rootfs 镜像：多内置源自动回退 + 点选首选 + 手动输入自定义源 ----
        Text("Linux 环境镜像（Ubuntu rootfs）", style = MaterialTheme.typography.titleSmall)
        Text(
            text = "内置 Ubuntu 官方 cdimage 与清华 TUNA 两个镜像（约 30MB），按顺序自动回退；" +
                "点选设为首选（再点取消）。自定义源填自托管 ubuntu-base-*.tar.gz 的基址或完整地址，" +
                "保存后优先于全部内置源。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            RootfsManager.ROOTFS_MIRRORS.forEach { mirror ->
                FilterChip(
                    selected = linuxPreferred == mirror.id,
                    onClick = {
                        // 再点已选中的 chip = 取消首选，回退到「官方/国内镜像」偏好排序
                        linuxPreferred = if (linuxPreferred == mirror.id) "" else mirror.id
                        scope.launch {
                            runCatching {
                                AppStorage.preferences.setLinuxPreferredSource(linuxPreferred)
                            }
                        }
                    },
                    label = {
                        Text(mirror.label, style = MaterialTheme.typography.labelMedium)
                    },
                )
            }
        }
        OutlinedTextField(
            value = linuxCustom,
            onValueChange = { linuxCustom = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("自定义镜像源（可选，留空则只用内置源）") },
            placeholder = { Text("https://host/ubuntu-base") },
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            OutlinedButton(
                onClick = {
                    val input = linuxCustom.trim()
                    if (input.isNotEmpty() &&
                        !input.startsWith("http://") && !input.startsWith("https://")
                    ) {
                        message = "自定义源需以 http:// 或 https:// 开头"
                    } else {
                        linuxCustom = input
                        scope.launch {
                            runCatching {
                                AppStorage.preferences.setLinuxCustomSource(input)
                            }
                            message = if (input.isEmpty()) {
                                "已清除自定义源，rootfs 将只用内置镜像"
                            } else {
                                "已保存自定义源：$input"
                            }
                        }
                    }
                },
            ) {
                Text("保存自定义源", style = MaterialTheme.typography.labelMedium)
            }
        }

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

        OutlinedButton(
            onClick = {
                scope.launch { runCatching { BuildEnvironment.refresh(context, projectDir) } }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("重新检测", style = MaterialTheme.typography.labelMedium)
        }
    }
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
