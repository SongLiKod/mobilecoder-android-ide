package com.mobilecoder.ide.feature.ssh

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mobilecoder.ide.core.common.theme.LocalAppPalette
import com.mobilecoder.ide.core.common.ui.AppAlertDialog
import com.mobilecoder.ide.core.common.ui.SectionHeader
import com.mobilecoder.ide.core.common.ui.StatChip
import com.mobilecoder.ide.core.storage.AppStorage
import com.mobilecoder.ide.core.storage.SshKeyMeta
import com.mobilecoder.ide.core.storage.SshKeyType
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 密钥详情页（查看 / 复制 / 导出 / 重命名 / 口令 / 激活 / 删除）。
 *
 * 私钥安全：仅提供「查看一次」，关闭即从状态清除；不提供明文导出、不写日志。
 */
@Composable
internal fun SshKeyDetailPage(
    meta: SshKeyMeta,
    onBack: () -> Unit,
    onActivated: () -> Unit,
    notify: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val palette = LocalAppPalette.current

    var nameField by remember(meta.id) { mutableStateOf(meta.name) }
    var usernameField by remember(meta.id) { mutableStateOf(meta.username.ifBlank { "git" }) }
    var notice by remember(meta.id) { mutableStateOf("") }
    var errorText by remember(meta.id) { mutableStateOf("") }

    var showPassphraseDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showPrivateConfirm by remember { mutableStateOf(false) }
    var privateKeyPem by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Text(
                text = meta.name,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            if (meta.isActive) StatChip(label = "使用中", emphasize = true)
        }

        if (busy) {
            androidx.compose.material3.LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        if (errorText.isNotBlank()) {
            Text(
                text = errorText,
                style = MaterialTheme.typography.bodySmall,
                color = Color(palette.error.toComposeColorValue()),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        if (notice.isNotBlank()) {
            Text(
                text = notice,
                style = MaterialTheme.typography.bodySmall,
                color = Color(palette.success.toComposeColorValue()),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        if (meta.type == SshKeyType.ED25519) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Text(
                    text = "用于 Git / 连接测试：" + ED25519_BACKEND_NOTICE,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }

        // ---------------- OpenSSH 公钥 ----------------
        SectionHeader(
            title = "OpenSSH 公钥",
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
        )
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            SelectionContainer {
                Text(
                    text = meta.publicKey,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            TextButton(onClick = {
                copyToClipboard(
                    context,
                    "SSH 公钥",
                    meta.publicKey,
                    "已复制，可粘贴到 GitHub → Settings → SSH keys",
                    notify,
                )
            }) {
                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.padding(end = 4.dp))
                Text("复制公钥")
            }
            TextButton(onClick = {
                copyToClipboard(context, "SSH 指纹", meta.fingerprint, "指纹已复制：${meta.fingerprint}", notify)
            }) {
                Text("复制指纹")
            }
            TextButton(onClick = {
                scope.launch {
                    errorText = ""
                    notice = ""
                    val message = withContext(Dispatchers.IO) { exportPublicKey(meta) }
                    if (message.startsWith("已导出")) notice = message else errorText = message
                }
            }) {
                Text("导出 .pub")
            }
        }

        // ---------------- 元信息 ----------------
        SectionHeader(
            title = "密钥信息",
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            InfoRow("类型 / 位数", "${meta.type.label} · ${meta.bits} 位")
            InfoRow("指纹", meta.fingerprint)
            InfoRow("创建时间", formatTime(meta.createdAt))
            InfoRow("口令", if (meta.hasPassphrase) "已设置（加密存储）" else "未设置")
            HorizontalDivider()
        }

        // ---------------- 用户名（多账号） ----------------
        SectionHeader(
            title = "SSH 用户名（多账号）",
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = usernameField,
                onValueChange = { usernameField = it },
                singleLine = true,
                label = { Text("用户名") },
                supportingText = { Text("GitHub 固定为 git；自建服务器可修改") },
                modifier = Modifier.weight(1f),
            )
            TextButton(
                enabled = usernameField.trim() != meta.username && usernameField.isNotBlank(),
                onClick = {
                    scope.launch {
                        val value = usernameField.trim()
                        val ok = withContext(Dispatchers.IO) {
                            runCatching { SshController.store()?.setSshUsername(meta.id, value) }.isSuccess
                        }
                        if (ok) {
                            SshController.refresh()
                            notice = "用户名已保存：$value"
                            errorText = ""
                        } else {
                            errorText = "保存用户名失败，请重试"
                        }
                    }
                },
            ) {
                Text("保存")
            }
        }

        // ---------------- 重命名 ----------------
        SectionHeader(
            title = "名称",
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = nameField,
                onValueChange = { nameField = it },
                singleLine = true,
                label = { Text("密钥名称") },
                modifier = Modifier.weight(1f),
            )
            TextButton(
                enabled = nameField.trim().isNotEmpty() && nameField.trim() != meta.name,
                onClick = {
                    scope.launch {
                        busy = true
                        errorText = ""
                        notice = ""
                        val value = nameField.trim()
                        val result = withContext(Dispatchers.IO) { renameKey(meta, value) }
                        busy = false
                        if (result == null) {
                            SshController.refresh()
                            notice = "名称已更新"
                        } else {
                            errorText = result
                        }
                    }
                },
            ) {
                Text("保存")
            }
        }

        // ---------------- 口令 ----------------
        SectionHeader(
            title = "私钥口令",
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (meta.hasPassphrase) "已设置口令" else "未设置口令",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = { showPassphraseDialog = true }) {
                Text(if (meta.hasPassphrase) "修改口令" else "设置口令")
            }
        }

        // ---------------- 私钥查看（一次性） ----------------
        SectionHeader(
            title = "私钥",
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
        )
        Text(
            text = "私钥仅以 AES-GCM 加密保存在本地（files/ssh_keys），不落明文、不上传、不写日志，也不提供明文导出。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = { showPrivateConfirm = true }) {
                Icon(Icons.Default.Visibility, contentDescription = null, modifier = Modifier.padding(end = 4.dp))
                Text("查看私钥（仅一次）")
            }
            if (privateKeyPem.isNotBlank()) {
                OutlinedButton(onClick = { privateKeyPem = "" }) {
                    Text("关闭并清除")
                }
            }
        }
        if (privateKeyPem.isNotBlank()) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                SelectionContainer {
                    Text(
                        text = privateKeyPem,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
        }

        // ---------------- 激活 / 删除 ----------------
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (!meta.isActive) {
                OutlinedButton(onClick = onActivated, modifier = Modifier.weight(1f)) {
                    Text("设为激活")
                }
            }
            Button(
                onClick = { showDeleteConfirm = true },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.padding(end = 4.dp))
                Text("删除密钥")
            }
        }

        Text(
            text = "指纹与公钥可安全分享；私钥任何时候都不要发送给他人。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }

    // ---------------- 弹窗 ----------------
    if (showPassphraseDialog) {
        PassphraseDialog(
            initial = "",
            hasExisting = meta.hasPassphrase,
            onDismiss = { showPassphraseDialog = false },
            onSave = { value ->
                showPassphraseDialog = false
                scope.launch {
                    val ok = withContext(Dispatchers.IO) {
                        runCatching { SshController.store()?.setPassphrase(meta.id, value) }.isSuccess
                    }
                    if (ok) {
                        SshController.refresh()
                        errorText = ""
                        notice = if (value.isEmpty()) "已清除口令" else "口令已加密保存"
                    } else {
                        errorText = "保存口令失败，请重试"
                    }
                }
            },
        )
    }

    if (showDeleteConfirm) {
        AppAlertDialog(
            title = "删除密钥",
            message = "确定删除「${meta.name}」吗？私钥文件与元信息将被移除，该操作不可恢复；" +
                "若此密钥已添加到 GitHub，请同时到 GitHub 删除对应公钥。",
            confirmLabel = "删除",
            destructive = true,
            onConfirm = {
                showDeleteConfirm = false
                scope.launch {
                    busy = true
                    val ok = withContext(Dispatchers.IO) {
                        runCatching { SshController.store()?.delete(meta.id) }.isSuccess
                    }
                    busy = false
                    if (ok) {
                        SshController.refresh()
                        onBack()
                    } else {
                        errorText = "删除失败，请重试"
                    }
                }
            },
            onDismiss = { showDeleteConfirm = false },
        )
    }

    if (showPrivateConfirm) {
        AppAlertDialog(
            title = "查看私钥",
            message = "私钥是最高敏感凭据：查看时请确认周围无人、不要截屏或录屏，" +
                "不要通过聊天工具发送。本应用不提供私钥明文导出，关闭后需重新验证才能再次查看。",
            confirmLabel = "我已确认，显示一次",
            onConfirm = {
                showPrivateConfirm = false
                scope.launch {
                    busy = true
                    errorText = ""
                    val pem = withContext(Dispatchers.IO) {
                        runCatching { SshController.store()?.material(meta.id)?.privateKeyPem }.getOrNull()
                    }
                    busy = false
                    if (pem.isNullOrBlank()) {
                        errorText = "读取私钥失败：文件不存在或解密失败"
                    } else {
                        privateKeyPem = pem
                    }
                }
            },
            onDismiss = { showPrivateConfirm = false },
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(116.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = if (label == "指纹") FontFamily.Monospace else FontFamily.Default,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Medium,
        )
    }
}

/** 重命名：存储层没有单独的 rename API，取回私钥后整体覆盖保存。 */
private suspend fun renameKey(meta: SshKeyMeta, newName: String): String? {
    val store = SshController.store() ?: return "存储层未就绪"
    return runCatching {
        val material = store.material(meta.id) ?: error("密钥文件缺失")
        store.save(
            meta.copy(name = newName),
            material.privateKeyPem,
            material.passphrase,
        )
    }.fold(onSuccess = { null }, onFailure = { "重命名失败：${it.message ?: "未知错误"}" })
}

private suspend fun exportPublicKey(meta: SshKeyMeta): String = runCatching {
    val dir = AppStorage.paths.sshKeys
    if (!dir.exists()) dir.mkdirs()
    val safe = meta.name.trim().replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_").ifBlank { meta.id }
    val target = File(dir, "$safe.pub")
    target.writeText(meta.publicKey.trim() + "\n", Charsets.UTF_8)
    "已导出到 ${target.absolutePath}"
}.fold(onSuccess = { it }, onFailure = { "导出失败：${it.message ?: "未知错误"}" })
