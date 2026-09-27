package com.mobilecoder.ide.feature.ssh

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mobilecoder.ide.core.common.theme.AppColor
import com.mobilecoder.ide.core.common.theme.LocalAppPalette
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
 *  标题 + 操作（新建 / 导入 / 测试） → 密钥列表（内部滚动） → GitHub 绑定指引（折叠）
 * 详情 / 连接测试为模块内二级页，生成 / 导入为弹窗。
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

    LaunchedEffect(Unit) {
        SshController.init(context)
        SshController.refresh()
    }

    if (showTest) {
        SshTestPage(
            modifier = modifier,
            keys = keys,
            onBack = { showTest = false },
        )
    } else {
        val detailKey = keys.firstOrNull { it.id == detailId }
        if (detailKey != null) {
            SshKeyDetailPage(
                modifier = modifier,
                meta = detailKey,
                onBack = { detailId = "" },
                onActivated = {
                    scope.launch {
                        withContext(Dispatchers.IO) { SshController.store()?.setActive(detailKey.id) }
                        SshController.refresh()
                    }
                },
            )
        } else {
            SshHome(
                modifier = modifier,
                keys = keys,
                busy = busy,
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
                            showToast(context, "已切换激活密钥，Git 与连接测试将默认使用它")
                        } else {
                            showToast(context, "设置激活密钥失败，请重试")
                        }
                    }
                },
            )
        }
    }

    if (showGenerate) {
        GenerateKeyDialog(
            onDismiss = { showGenerate = false },
            onGenerated = { id ->
                showGenerate = false
                detailId = id
                showToast(context, "密钥已生成并加密保存")
            },
        )
    }

    if (showImport) {
        ImportKeyDialog(
            onDismiss = { showImport = false },
            onImported = { id ->
                showImport = false
                detailId = id
                showToast(context, "私钥已导入并加密保存")
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
    onOpenGenerate: () -> Unit,
    onOpenImport: () -> Unit,
    onOpenTest: () -> Unit,
    onOpenDetail: (String) -> Unit,
    onActivate: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val active = keys.firstOrNull { it.isActive }

    Column(modifier = modifier.fillMaxSize()) {
        SectionHeader(
            title = "SSH 密钥管理",
            modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 4.dp),
            action = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onOpenGenerate) { Text("新建") }
                    TextButton(onClick = onOpenImport) { Text("导入") }
                    TextButton(onClick = onOpenTest) { Text("测试") }
                }
            },
        )

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
            item(key = "guide") { GitHubGuideCard(onOpenGitHub = { openGitHubSshSettings(context) }) }
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
}

/** 激活密钥摘要：Git / 连接测试默认使用的凭据。 */
@Composable
private fun ActiveSummaryCard(meta: SshKeyMeta, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val palette = LocalAppPalette.current
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
            if (meta.type == com.mobilecoder.ide.core.storage.SshKeyType.ED25519) {
                Text(
                    text = ED25519_BACKEND_NOTICE,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(palette.warning.toComposeColorValue()),
                )
            }
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

internal fun showToast(context: Context, message: String) {
    try {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context.applicationContext, message, Toast.LENGTH_SHORT).show()
        }
    } catch (_: Throwable) {
        // Toast 失败不致命
    }
}

/** 复制到剪贴板 + Toast 引导（PRD：一键复制公钥）。 */
internal fun copyToClipboard(context: Context, label: String, text: String, toastMessage: String) {
    try {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: throw IllegalStateException("剪贴板服务不可用")
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        showToast(context, toastMessage)
    } catch (t: Throwable) {
        showToast(context, "复制失败：${t.message ?: "未知错误"}")
    }
}

/** 跳转 GitHub SSH 密钥设置页（无浏览器时给出可手动打开的提示）。 */
private fun openGitHubSshSettings(context: Context) {
    try {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/settings/keys"))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    } catch (_: Throwable) {
        showToast(context, "未找到可用浏览器，请手动打开 https://github.com/settings/keys")
    }
}
