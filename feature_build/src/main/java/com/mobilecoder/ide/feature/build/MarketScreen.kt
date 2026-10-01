package com.mobilecoder.ide.feature.build

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mobilecoder.ide.core.common.linux.Proot
import com.mobilecoder.ide.core.common.theme.LocalAppPalette
import java.io.File

/**
 * 软件市场页：软件卡片 + 一键安装 + 阶段进度 / 实时日志 / 取消。
 *
 * 执行在 [MarketInstaller] 单例里静默进行——离开本页（回构建页、切终端）
 * 安装不中断，返回后通过 [MarketInstaller.state] 继续观察同一任务；
 * 同一时间只允许一个安装（单任务模型）。
 */
@Composable
fun MarketScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val state by MarketInstaller.state.collectAsStateWithLifecycle()

    // Linux 环境是否就绪（首次安装会自动补装 rootfs + proot，完成后刷新徽标）
    var linuxReady by remember { mutableStateOf(Proot.isReady(context)) }
    // 构建环境自带的 node shim 会遮蔽 /usr/local/bin/node（仅提示，不阻断）
    val nodeShimmed = remember { File(BuildEnvironment.binDir(context), "node").exists() }

    BackHandler(onBack = onBack)
    LaunchedEffect(Unit) { MarketInstaller.refreshInstalled(context) }
    LaunchedEffect(state.activeId, state.message) {
        if (state.activeId == null) linuxReady = Proot.isReady(context)
    }

    Column(modifier = modifier.fillMaxSize()) {

        // ---------------- 顶栏：返回 + 标题 ----------------
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "软件市场",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = "一键安装到 Linux 环境，无需命令行",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (!linuxReady) {
                item(key = "linux-env") { LinuxEnvBanner() }
            }
            items(MarketInstaller.items, key = { it.id }) { item ->
                MarketCard(
                    item = item,
                    state = state,
                    nodeShimmed = nodeShimmed,
                    onInstall = { MarketInstaller.install(context, item) },
                    onCancel = { MarketInstaller.cancel() },
                    onCopyLog = { clipboard.setText(AnnotatedString(it)) },
                )
            }
            item(key = "more") {
                Text(
                    text = "更多软件（Python、Git、数据库…）持续上架中…",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

// ------------------------------------------------------------------
// 软件卡片
// ------------------------------------------------------------------

@Composable
private fun MarketCard(
    item: MarketItem,
    state: MarketState,
    nodeShimmed: Boolean,
    onInstall: () -> Unit,
    onCancel: () -> Unit,
    onCopyLog: (String) -> Unit,
) {
    val palette = LocalAppPalette.current
    val successColor = Color(0xFF000000 or palette.success.rgb)
    val errorColor = Color(0xFF000000 or palette.logError.rgb)
    val warnColor = Color(0xFF000000 or palette.logWarn.rgb)

    val installed = item.id in state.installed
    val active = state.activeId == item.id
    // 面板归属最近一次安装的任务（结束后仍可回看日志与结果）
    val showPanel = state.lastId == item.id &&
        (active || state.logs.isNotEmpty() || state.message != null)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = item.name,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.secondaryContainer,
                        ) {
                            Text(
                                text = MarketInstaller.versionLabel(item.version),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                    Text(
                        text = item.summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                // 状态 / 操作（单任务：有任务在跑时其余安装按钮禁用）
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = 8.dp),
                ) {
                    if (installed) {
                        Text(
                            text = "已安装",
                            style = MaterialTheme.typography.labelMedium,
                            color = successColor,
                        )
                    }
                    if (active) {
                        TextButton(onClick = onCancel) { Text("取消") }
                    } else {
                        Button(onClick = onInstall, enabled = !state.busy) {
                            Text(if (installed) "重装" else "安装")
                        }
                    }
                }
            }

            Text(
                text = item.detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (item.id == "opencode" && "nodejs" !in state.installed) {
                Text(
                    text = "依赖 Node.js：请先安装上方的 Node.js，完成后再安装 opencode。",
                    style = MaterialTheme.typography.labelSmall,
                    color = warnColor,
                )
            }

            if (nodeShimmed && item.id == "nodejs") {
                Text(
                    text = "提示：「构建环境」已安装 Node.js 入口，终端 PATH 中它优先于本市场安装的版本；" +
                        "需要使用本版本时请先在构建环境中移除。",
                    style = MaterialTheme.typography.labelSmall,
                    color = warnColor,
                )
            }

            if (showPanel) {
                InstallPanel(
                    state = state,
                    active = active,
                    successColor = successColor,
                    errorColor = errorColor,
                    onCancel = onCancel,
                    onCopyLog = onCopyLog,
                )
            }
        }
    }
}

// ------------------------------------------------------------------
// 安装面板：阶段 + 进度 + 实时日志 + 结果
// ------------------------------------------------------------------

@Composable
private fun InstallPanel(
    state: MarketState,
    active: Boolean,
    successColor: Color,
    errorColor: Color,
    onCancel: () -> Unit,
    onCopyLog: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = state.stage.ifBlank { if (active) "执行中…" else "" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }

        // 进度：progress < 0 表示不确定（apt 阶段无百分比）
        if (active || state.progress >= 0f) {
            if (state.progress >= 0f) {
                LinearProgressIndicator(
                    progress = { state.progress },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }

        if (state.logs.isNotEmpty()) {
            LogPane(logs = state.logs, onCopy = onCopyLog)
        }

        state.message?.let { msg ->
            Text(
                text = msg,
                style = MaterialTheme.typography.bodySmall,
                color = if (state.success) successColor else errorColor,
            )
        }
    }
}

// ------------------------------------------------------------------
// 实时日志：等宽字体 + 自动贴底 + 一键复制
// ------------------------------------------------------------------

@Composable
private fun LogPane(logs: List<String>, onCopy: (String) -> Unit) {
    val listState = rememberLazyListState()

    // 日志追加时自动滚到最新一行
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) listState.scrollToItem(logs.size - 1)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 2.dp, top = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "日志（${logs.size} 行）",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = { onCopy(logs.joinToString("\n")) },
                modifier = Modifier.size(28.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.ContentCopy,
                    contentDescription = "复制日志",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .height(150.dp)
                .padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            items(logs.size) { index ->
                Text(
                    text = logs[index],
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
    }
}

// ------------------------------------------------------------------
// 前置条件横幅
// ------------------------------------------------------------------

/** guest 未就绪时置顶提示：首次安装会自动补装 Linux 环境。 */
@Composable
private fun LinuxEnvBanner() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "需要 Linux 环境",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Text(
                text = "软件安装在 Ubuntu rootfs（proot）内执行。首次安装会自动在线下载 Linux 环境" +
                    "（rootfs + proot），耗时取决于网络；也可先到本页「构建环境」中提前安装。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }
    }
}
