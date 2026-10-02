package com.mobilecoder.ide.feature.ssh

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mobilecoder.ide.core.common.theme.AppColor
import com.mobilecoder.ide.core.common.ui.EmptyState
import com.mobilecoder.ide.core.common.ui.SectionHeader
import com.mobilecoder.ide.core.common.ui.StatChip
import com.mobilecoder.ide.core.storage.SshKeyMeta
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * PRD 2.6「GitHub SSH Key 可视化配置模块」主页面。
 *
 * SSH 是全局功能（不依赖项目），页面结构：
 *  操作行（测试 / 新建 / 导入 / 更多） → 密钥列表（内部滚动）
 *  无密钥时内嵌 GitHub 绑定指引；有密钥时收进「⋮ 查看绑定指引」浮层。
 *  路由级标题由外壳统一渲染（SSH 密钥），本页不再自绘页级大标题。
 *  详情 / 连接测试为模块内二级页（保留内部返回箭头），生成 / 导入为弹窗。
 */
@Composable
fun SshScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keys by SshController.keys.collectAsStateWithLifecycle()

    var detailId by rememberSaveable { mutableStateOf("") }
    var showTest by rememberSaveable { mutableStateOf(false) }
    var showGenerate by remember { mutableStateOf(false) }
    var showImport by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    // 页面级 Snackbar（替代 Toast）：短时提示，自动消失
    val snackbarHostState = remember { SnackbarHostState() }
    val notify: (String) -> Unit = { message ->
        scope.launch { snackbarHostState.showSnackbar(message) }
    }

    LaunchedEffect(Unit) {
        SshController.init(context)
        SshController.refresh()
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (showTest) {
            SshTestPage(
                keys = keys,
                onBack = { showTest = false },
            )
        } else {
            val detailKey = keys.firstOrNull { it.id == detailId }
            if (detailKey != null) {
                SshKeyDetailPage(
                    meta = detailKey,
                    onBack = { detailId = "" },
                    notify = notify,
                    onActivated = {
                        scope.launch {
                            withContext(Dispatchers.IO) { SshController.store()?.setActive(detailKey.id) }
                            SshController.refresh()
                        }
                    },
                )
            } else {
                SshHome(
                    keys = keys,
                    busy = busy,
                    notify = notify,
                    onOpenGenerate = { showGenerate = true },
                    onOpenImport = { showImport = true },
                    onOpenTest = { showTest = true },
                    onOpenDetail = { detailId = it },
                    onActivate = { id ->
                        scope.launch {
                            busy = true
                            val ok = withContext(Dispatchers.IO) {
                                runCatching { SshController.store()?.setActive(id) }.isSuccess
                            }
                            busy = false
                            if (ok) {
                                SshController.refresh()
                                notify("已切换激活密钥，Git 与连接测试将默认使用它")
                            } else {
                                notify("设置激活密钥失败，请重试")
                            }
                        }
                    },
                )
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }

    if (showGenerate) {
        GenerateKeyDialog(
            onDismiss = { showGenerate = false },
            onGenerated = { id ->
                showGenerate = false
                detailId = id
                notify("密钥已生成并加密保存")
            },
        )
    }

    if (showImport) {
        ImportKeyDialog(
            onDismiss = { showImport = false },
            onImported = { id ->
                showImport = false
                detailId = id
                notify("私钥已导入并加密保存")
            },
        )
    }
}

// ---------------------------------------------------------------------------
// 首页
// ---------------------------------------------------------------------------

@Composable
private fun SshHome(
    keys: List<SshKeyMeta>,
    busy: Boolean,
    notify: (String) -> Unit,
    onOpenGenerate: () -> Unit,
    onOpenImport: () -> Unit,
    onOpenTest: () -> Unit,
    onOpenDetail: (String) -> Unit,
    onActivate: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val active = keys.firstOrNull { it.isActive }
    var moreMenu by rememberSaveable { mutableStateOf(false) }
    var showGuide by rememberSaveable { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize()) {
        // 操作行：路由级标题由外壳统一渲染，此处只保留右对齐操作（每个点击区 >= 40dp）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(modifier = Modifier.weight(1f))
            OutlinedButton(
                onClick = onOpenGenerate,
                modifier = Modifier
                    .padding(end = 6.dp)
                    .heightIn(min = 40.dp),
            ) {
                Text("新建")
            }
            OutlinedButton(
                onClick = onOpenImport,
                modifier = Modifier
                    .padding(end = 6.dp)
                    .heightIn(min = 40.dp),
            ) {
                Text("导入")
            }
            OutlinedButton(
                onClick = onOpenTest,
                modifier = Modifier.heightIn(min = 40.dp),
            ) {
                Text("测试")
            }
            if (keys.isNotEmpty()) {
                // 有密钥时绑定指引收进「⋮」菜单，避免长期占据列表空间
                Box {
                    IconButton(
                        onClick = { moreMenu = true },
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "更多操作",
                        )
                    }
                    DropdownMenu(
                        expanded = moreMenu,
                        onDismissRequest = { moreMenu = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("查看绑定指引") },
                            onClick = {
                                moreMenu = false
                                showGuide = true
                            },
                        )
                    }
                }
            }
        }

        if (busy) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (active != null) {
                item(key = "active") {
                    ActiveSummaryCard(meta = active, onOpen = { onOpenDetail(active.id) })
                }
            }
            if (keys.isEmpty()) {
                item(key = "guide") {
                    GitHubGuideCard(onOpenGitHub = { openGitHubSshSettings(context, notify) })
                }
            }
            item(key = "list-header") {
                SectionHeader(
                    title = "密钥列表（${keys.size}）",
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (keys.isEmpty()) {
                item(key = "empty") {
                    EmptyState(
                        title = "还没有 SSH 密钥",
                        subtitle = "点击右上角「新建」一键生成 RSA / Ed25519 密钥对",
                    )
                }
            } else {
                items(keys, key = { it.id }) { meta ->
                    KeyCard(
                        meta = meta,
                        onOpen = { onOpenDetail(meta.id) },
                        onActivate = { onActivate(meta.id) },
                    )
                }
            }
        }
    }

    if (showGuide) {
        GuideDialog(
            onDismiss = { showGuide = false },
            onOpenGitHub = { openGitHubSshSettings(context, notify) },
        )
    }
}

/** 激活密钥摘要：Git / 连接测试默认使用的凭据。 */
@Composable
private fun ActiveSummaryCard(meta: SshKeyMeta, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "当前激活：${meta.name}",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                StatChip(label = "使用中", emphasize = true)
            }
            Text(
                text = "${meta.type.label} · ${meta.bits} 位 · 用户 ${meta.username.ifBlank { "git" }}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                text = meta.fingerprint,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "激活的密钥会自动作为 Git 推送 / 拉取的凭据（core_storage 本地加密存储，不落明文、不上传）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

/** 单条密钥卡片。 */
@Composable
private fun KeyCard(
    meta: SshKeyMeta,
    onOpen: () -> Unit,
    onActivate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
        colors = CardDefaults.cardColors(
            containerColor = if (meta.isActive) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = meta.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (meta.isActive) {
                    StatChip(label = "使用中", emphasize = true)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StatChip(label = "${meta.type.label} ${meta.bits} 位")
                if (meta.hasPassphrase) StatChip(label = "有口令")
                StatChip(label = "用户 ${meta.username.ifBlank { "git" }}" + if (meta.type == com.mobilecoder.ide.core.storage.SshKeyType.ED25519) " · 仅存储" else "")
            }
            Text(
                text = meta.fingerprint,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "创建于 ${formatTime(meta.createdAt)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (!meta.isActive) {
                    TextButton(onClick = onActivate) { Text("设为激活") }
                }
            }
        }
    }
}

/** GitHub 绑定分步指引（折叠卡）。 */
@Composable
private fun GitHubGuideCard(onOpenGitHub: () -> Unit, modifier: Modifier = Modifier) {
    var expanded by rememberSaveable { mutableStateOf(false) }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "GitHub 绑定指引",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "收起指引" else "展开指引",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (expanded) {
                GuideBody(onOpenGitHub = onOpenGitHub)
            }
        }
    }
}

/** 指引正文（折叠卡与浮层共用，文案保持与 PRD 一致）。 */
@Composable
private fun GuideBody(onOpenGitHub: () -> Unit) {
    HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))
    GUIDE_STEPS.forEachIndexed { index, step ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "${index + 1}.",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = step,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
    OutlinedButton(
        onClick = onOpenGitHub,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("打开 GitHub → Settings → SSH keys")
    }
    Text(
        text = "激活的密钥会自动作为 Git 推送 / 拉取的凭据（core_storage 加密存储，不落明文、不上传）。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
    )
}

/** 有密钥时通过「⋮ 查看绑定指引」打开的浮层（复用指引正文）。 */
@Composable
private fun GuideDialog(
    onDismiss: () -> Unit,
    onOpenGitHub: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "GitHub 绑定指引",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "关闭",
                        )
                    }
                }
                GuideBody(onOpenGitHub = onOpenGitHub)
            }
        }
    }
}

private val GUIDE_STEPS = listOf(
    "生成密钥：点击「新建」，推荐 RSA 4096（可视化一键生成，可选 Ed25519）。",
    "复制公钥：进入密钥详情，点击「复制公钥」。",
    "粘贴到 GitHub：打开 SSH keys 设置页，New SSH key 并粘贴保存。",
    "测试连通性：回到本页点击「测试」，确认连接与认证通过。",
    "在 Git 页使用：克隆 / 推送 / 拉取时自动使用激活的密钥免密认证。",
)

// ---------------------------------------------------------------------------
// 二级页：连接测试
// ---------------------------------------------------------------------------

/** 后端限制提示（mbedTLS 构建的 libssh2 无 Ed25519 认证能力）。 */
internal const val ED25519_BACKEND_NOTICE =
    "当前设备端 SSH 引擎（mbedTLS 构建）不支持 Ed25519 认证，连接测试/推送请使用 RSA 密钥；" +
        "Ed25519 密钥可用于生成、存储与复制公钥"

// ---------------------------------------------------------------------------
// 通用小工具
// ---------------------------------------------------------------------------

/** 0xRRGGBB → Compose Color。 */
internal fun AppColor.toComposeColorValue(): Long = 0xFF000000L or rgb

internal fun formatTime(millis: Long): String = try {
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(millis))
} catch (_: Throwable) {
    ""
}

/** 复制到剪贴板 + 瞬时提示（Snackbar，PRD：一键复制公钥）。 */
internal fun copyToClipboard(
    context: Context,
    label: String,
    text: String,
    successMessage: String,
    notify: (String) -> Unit,
) {
    try {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: throw IllegalStateException("剪贴板服务不可用")
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        notify(successMessage)
    } catch (t: Throwable) {
        notify("复制失败：${t.message ?: "未知错误"}")
    }
}

/** 跳转 GitHub SSH 密钥设置页（无浏览器时给出可手动打开的提示）。 */
private fun openGitHubSshSettings(context: Context, notify: (String) -> Unit) {
    try {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/settings/keys"))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    } catch (_: Throwable) {
        notify("未找到可用浏览器，请手动打开 https://github.com/settings/keys")
    }
}
