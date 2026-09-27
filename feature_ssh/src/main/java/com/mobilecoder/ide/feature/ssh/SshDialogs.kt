package com.mobilecoder.ide.feature.ssh

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.mobilecoder.ide.core.common.theme.LocalAppPalette
import com.mobilecoder.ide.core.storage.SshKeyMeta
import com.mobilecoder.ide.core.storage.SshKeyStore
import com.mobilecoder.ide.core.storage.SshKeyType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 统一弹窗容器（跟随深浅色主题）。 */
@Composable
internal fun SshDialog(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                content()
            }
        }
    }
}

/** 后端限制提示行（警示配色）。 */
@Composable
internal fun WarningText(text: String, modifier: Modifier = Modifier) {
    val palette = LocalAppPalette.current
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = Color(palette.warning.toComposeColorValue()),
        modifier = modifier,
    )
}

/** 错误提示行。 */
@Composable
internal fun ErrorText(text: String, modifier: Modifier = Modifier) {
    val palette = LocalAppPalette.current
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = Color(palette.error.toComposeColorValue()),
        modifier = modifier,
    )
}

// ---------------------------------------------------------------------------
// 生成密钥
// ---------------------------------------------------------------------------

/** PRD 2.6：可视化一键生成 RSA（2048 / 4096，默认 4096） / Ed25519 密钥对。 */
@Composable
internal fun GenerateKeyDialog(
    onDismiss: () -> Unit,
    onGenerated: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val palette = LocalAppPalette.current

    var type by remember { mutableStateOf(SshKeyType.RSA) }
    var bits by remember { mutableStateOf(4096) }
    var name by remember { mutableStateOf("GitHub 密钥") }
    var comment by remember { mutableStateOf(SshKeyFactory.defaultComment()) }
    var username by remember { mutableStateOf("git") }
    var passphrase by remember { mutableStateOf("") }
    var confirmPassphrase by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf("") }

    SshDialog(title = "生成 SSH 密钥", onDismiss = { if (!busy) onDismiss() }) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SshKeyType.entries.forEach { option ->
                FilterChip(
                    selected = type == option,
                    onClick = { if (!busy) type = option },
                    label = { Text(option.label) },
                )
            }
        }

        if (type == SshKeyType.RSA) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(2048, 4096).forEach { size ->
                    FilterChip(
                        selected = bits == size,
                        onClick = { if (!busy) bits = size },
                        label = { Text("$size 位") },
                    )
                }
            }
        } else {
            WarningText(ED25519_BACKEND_NOTICE)
        }

        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("名称") },
            singleLine = true,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = comment,
            onValueChange = { comment = it },
            label = { Text("公钥注释") },
            supportingText = { Text("公钥行尾的 comment，默认 mobilecoder@设备名") },
            singleLine = true,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text("SSH 用户名") },
            supportingText = { Text("GitHub 固定 git；自建服务器可改，可随时在详情页修改") },
            singleLine = true,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = passphrase,
            onValueChange = { passphrase = it },
            label = { Text("私钥口令（可选）") },
            supportingText = { Text("留空则无口令；填写后与私钥一同加密保存") },
            singleLine = true,
            enabled = !busy,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        if (passphrase.isNotEmpty()) {
            OutlinedTextField(
                value = confirmPassphrase,
                onValueChange = { confirmPassphrase = it },
                label = { Text("确认口令") },
                singleLine = true,
                enabled = !busy,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (busy) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.padding(2.dp))
                Text(
                    text = if (type == SshKeyType.RSA && bits >= 4096) {
                        "正在生成 RSA 4096 密钥对，可能需要数秒…"
                    } else {
                        "正在生成密钥对…"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (errorText.isNotBlank()) ErrorText(errorText)

        Text(
            text = "私钥将由 core_storage 以 AES-GCM 加密保存在本地（files/ssh_keys），全程不落明文、不上传。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") }
            TextButton(
                enabled = !busy,
                onClick = {
                    scope.launch {
                        errorText = ""
                        when {
                            name.isBlank() -> {
                                errorText = "请填写密钥名称"
                                return@launch
                            }

                            passphrase.isNotEmpty() && passphrase != confirmPassphrase -> {
                                errorText = "两次输入的口令不一致"
                                return@launch
                            }
                        }
                        busy = true
                        val result = withContext(Dispatchers.IO) {
                            runCatching { createKey(type, bits, name.trim(), comment.trim(), username.trim(), passphrase) }
                        }
                        busy = false
                        result.fold(
                            onSuccess = { id -> onGenerated(id) },
                            onFailure = { t ->
                                errorText = "生成失败：${t.message ?: "未知错误"}"
                            },
                        )
                    }
                },
            ) {
                Text("生成密钥", fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
            }
        }
    }
}

/** 真正的生成 + 加密保存（必须在 IO 线程调用）。 */
private suspend fun createKey(
    type: SshKeyType,
    bits: Int,
    name: String,
    comment: String,
    username: String,
    passphrase: String,
): String {
    val store = SshController.store() ?: throw SshKeyException("存储层未就绪，请稍后重试")
    val prepared = SshKeyFactory.generate(type, bits, comment)
    val id = SshKeyStore.newId()
    val meta = SshKeyMeta(
        id = id,
        name = name,
        type = type,
        bits = prepared.bits,
        comment = comment,
        username = username.ifBlank { "git" },
        publicKey = prepared.publicKey,
        fingerprint = prepared.fingerprint,
        createdAt = System.currentTimeMillis(),
        hasPassphrase = passphrase.isNotEmpty(),
    )
    // save() 只负责私钥加密落盘与元信息入索引；口令需另行加密写入
    store.save(meta, prepared.privateKeyPem, passphrase)
    if (passphrase.isNotEmpty()) {
        store.setPassphrase(id, passphrase)
    }
    return id
}

// ---------------------------------------------------------------------------
// 导入密钥
// ---------------------------------------------------------------------------

@Composable
internal fun ImportKeyDialog(
    onDismiss: () -> Unit,
    onImported: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()

    var pemText by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("导入的密钥") }
    var comment by remember { mutableStateOf(SshKeyFactory.defaultComment()) }
    var busy by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf("") }

    SshDialog(title = "导入已有密钥", onDismiss = { if (!busy) onDismiss() }) {
        OutlinedTextField(
            value = pemText,
            onValueChange = { pemText = it },
            label = { Text("PEM 私钥文本") },
            placeholder = { Text("-----BEGIN PRIVATE KEY-----\n…\n-----END PRIVATE KEY-----") },
            minLines = 6,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = passphrase,
            onValueChange = { passphrase = it },
            label = { Text("私钥口令（可选）") },
            supportingText = { Text("私钥已加密时填写；OpenSSH 新格式暂不支持") },
            singleLine = true,
            enabled = !busy,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("名称") },
            singleLine = true,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = comment,
            onValueChange = { comment = it },
            label = { Text("公钥注释") },
            singleLine = true,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        )

        Text(
            text = "支持 RSA（PKCS#1 / PKCS#8）与 Ed25519（PKCS#8）私钥；导入后会自动推导 OpenSSH 公钥并加密存储，私钥不上传、不落明文。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (busy) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CircularProgressIndicator()
                Text(
                    text = "正在解析并校验私钥…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (errorText.isNotBlank()) ErrorText(errorText)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") }
            TextButton(
                enabled = !busy,
                onClick = {
                    scope.launch {
                        errorText = ""
                        if (pemText.isBlank()) {
                            errorText = "请粘贴 PEM 私钥文本"
                            return@launch
                        }
                        if (name.isBlank()) {
                            errorText = "请填写密钥名称"
                            return@launch
                        }
                        busy = true
                        val result = withContext(Dispatchers.IO) {
                            runCatching { importKey(pemText, passphrase, name.trim(), comment.trim()) }
                        }
                        busy = false
                        result.fold(
                            onSuccess = { id -> onImported(id) },
                            onFailure = { t -> errorText = t.message ?: "导入失败，请重试" },
                        )
                    }
                },
            ) {
                Text("导入", fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
            }
        }
    }
}

/** 真正的解析 + 加密保存（必须在 IO 线程调用）。 */
private suspend fun importKey(pemText: String, passphrase: String, name: String, comment: String): String {
    val store = SshController.store() ?: throw SshKeyException("存储层未就绪，请稍后重试")
    val prepared = SshKeyFactory.importPrivateKey(pemText, passphrase, comment)
    val id = SshKeyStore.newId()
    val meta = SshKeyMeta(
        id = id,
        name = name,
        type = prepared.type,
        bits = prepared.bits,
        comment = comment,
        username = "git",
        publicKey = prepared.publicKey,
        fingerprint = prepared.fingerprint,
        createdAt = System.currentTimeMillis(),
        hasPassphrase = false,
    )
    store.save(meta, prepared.privateKeyPem, passphrase)
    if (passphrase.isNotEmpty()) {
        store.setPassphrase(id, passphrase)
    }
    return id
}

// ---------------------------------------------------------------------------
// 口令设置
// ---------------------------------------------------------------------------

@Composable
internal fun PassphraseDialog(
    initial: String,
    hasExisting: Boolean,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    var confirm by remember { mutableStateOf(initial) }
    var errorText by remember { mutableStateOf("") }

    SshDialog(
        title = if (hasExisting) "修改私钥口令" else "设置私钥口令",
        onDismiss = onDismiss,
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = { value = it },
            label = { Text("新口令（留空表示清除口令）") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        if (value.isNotEmpty()) {
            OutlinedTextField(
                value = confirm,
                onValueChange = { confirm = it },
                label = { Text("确认口令") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (errorText.isNotBlank()) ErrorText(errorText)
        Text(
            text = "口令会以 AES-GCM 加密保存，用于 Git / 连接测试时自动解密私钥（仅在内存中）。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onDismiss) { Text("取消") }
            TextButton(
                onClick = {
                    if (value.isNotEmpty() && value != confirm) {
                        errorText = "两次输入的口令不一致"
                    } else {
                        onSave(value)
                    }
                },
            ) {
                Text("保存", fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
            }
        }
    }
}
