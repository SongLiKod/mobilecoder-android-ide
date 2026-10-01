package com.mobilecoder.ide.feature.ssh

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.mobilecoder.ide.core.common.theme.LocalAppPalette
import com.mobilecoder.ide.core.common.ui.SectionHeader
import com.mobilecoder.ide.core.common.ui.StatChip
import com.mobilecoder.ide.core.storage.SshKeyMeta
import com.mobilecoder.ide.core.storage.SshKeyType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 一键测试 SSH 连接（PRD 2.6）。
 *
 * 表单：host / port(22) / username(git) / 可选口令 → native 四段帧 → 结果卡片。
 * 默认使用「激活密钥」，可通过密钥胶囊切换到其他密钥（多密钥多账号）。
 */
@Composable
internal fun SshTestPage(
    keys: List<SshKeyMeta>,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val palette = LocalAppPalette.current

    var selectedId by rememberSaveable { mutableStateOf("") }
    var useKey by rememberSaveable { mutableStateOf(true) }

    // 用户手动选择过（含「不使用密钥」）后，不再被 keys 变更自动重置
    var userPicked by rememberSaveable { mutableStateOf(false) }
    var host by rememberSaveable { mutableStateOf("github.com") }
    var port by rememberSaveable { mutableStateOf("22") }
    var username by rememberSaveable { mutableStateOf("git") }
    var passphrase by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<SshTestResult?>(null) }

    val selectedKey = keys.firstOrNull { it.id == selectedId }

    // 默认选中激活密钥（用户手动选择过则不覆盖）
    LaunchedEffect(keys) {
        if (!userPicked && selectedId.isBlank()) {
            selectedId = keys.firstOrNull { it.isActive }?.id ?: keys.firstOrNull()?.id ?: ""
            useKey = selectedId.isNotBlank()
        }
        // 被删除的密钥：回退到激活密钥
        if (selectedId.isNotBlank() && keys.none { it.id == selectedId }) {
            selectedId = if (userPicked) "" else (keys.firstOrNull { it.isActive }?.id ?: keys.firstOrNull()?.id ?: "")
            useKey = selectedId.isNotBlank()
        }
    }

    fun selectKey(id: String) {
        userPicked = true
        selectedId = id
        useKey = id.isNotBlank()
        val key = keys.firstOrNull { it.id == id }
        if (key != null) {
            username = key.username.ifBlank { "git" }
        }
        scope.launch {
            val stored = withContext(Dispatchers.IO) {
                if (id.isBlank()) "" else runCatching {
                    SshController.store()?.passphraseOf(id)
                }.getOrNull() ?: ""
            }
            passphrase = stored
        }
    }

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
                text = "测试 SSH 连接",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = {
                host = "github.com"
                port = "22"
                username = "git"
            }) {
                Text("填入 github.com")
            }
        }

        if (busy) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        // ---------------- 密钥选择 ----------------
        SectionHeader(
            title = "使用的密钥",
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = selectedId.isBlank(),
                onClick = { selectKey("") },
                label = { Text("不使用密钥") },
            )
            keys.forEach { key ->
                FilterChip(
                    selected = selectedId == key.id,
                    onClick = { selectKey(key.id) },
                    label = { Text(key.name) },
                )
            }
        }
        if (keys.isEmpty()) {
            Text(
                text = "还没有密钥：点击右上角「新建」生成后再测试，当前仅测连通性。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        // ---------------- 连接表单 ----------------
        OutlinedTextField(
            value = host,
            onValueChange = { host = it },
            label = { Text("主机 host") },
            placeholder = { Text("github.com") },
            singleLine = true,
            enabled = !busy,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = port,
                onValueChange = { port = it.filter { c -> c.isDigit() }.take(5) },
                label = { Text("端口") },
                singleLine = true,
                enabled = !busy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text("用户名") },
                supportingText = { Text("GitHub 固定 git") },
                singleLine = true,
                enabled = !busy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next),
                modifier = Modifier.weight(1.4f),
            )
        }
        OutlinedTextField(
            value = passphrase,
            onValueChange = { passphrase = it },
            label = { Text("私钥口令（可选）") },
            supportingText = { Text("无口令请留空") },
            singleLine = true,
            enabled = !busy && useKey,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
        )

        if (errorText.isNotBlank()) {
            ErrorText(
                text = errorText,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        Button(
            onClick = {
                scope.launch {
                    errorText = ""
                    result = null
                    val portValue = port.trim().toIntOrNull()
                    when {
                        host.isBlank() -> {
                            errorText = "请填写主机地址"
                            return@launch
                        }

                        portValue == null || portValue !in 1..65535 -> {
                            errorText = "端口必须是 1~65535 之间的数字"
                            return@launch
                        }

                        username.isBlank() -> {
                            errorText = "请填写用户名（GitHub 为 git）"
                            return@launch
                        }
                    }

                    busy = true
                    var materialPem: String? = null
                    if (useKey && selectedKey != null) {
                        val loaded = withContext(Dispatchers.IO) {
                            runCatching { SshController.store()?.material(selectedKey.id) }.getOrNull()
                        }
                        if (loaded == null) {
                            busy = false
                            errorText = "读取所选密钥失败（文件缺失或解密失败）"
                            return@launch
                        }
                        materialPem = loaded.privateKeyPem
                    }
                    val outcome = SshConnectionTester.test(
                        host = host.trim(),
                        port = portValue,
                        username = username.trim(),
                        privateKeyPem = materialPem,
                        passphrase = passphrase,
                        usedEd25519 = selectedKey?.type == SshKeyType.ED25519,
                    )
                    busy = false
                    result = outcome
                }
            },
            enabled = !busy,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.padding(end = 10.dp),
                    strokeWidth = 2.dp,
                )
            }
            Text(if (busy) "正在测试…" else "开始测试")
        }

        result?.let { outcome -> TestResultCard(outcome) }

        Text(
            text = "测试在本机发起：TCP 连接 → SSH 握手 → 主机密钥指纹 → 公钥认证。" +
                "私钥仅以内存凭据传入 native 层，不写日志、不落明文。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun TestResultCard(result: SshTestResult) {
    val palette = LocalAppPalette.current
    val color = when {
        result.success -> Color(palette.success.toComposeColorValue())
        result.reachable -> Color(palette.warning.toComposeColorValue())
        else -> Color(palette.error.toComposeColorValue())
    }
    val title = when {
        result.success -> "连接并认证成功"
        !result.reachable -> "连接失败"
        result.usedKey -> "连接成功，但认证失败"
        else -> "服务器可达（未使用密钥，未认证）"
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.16f)),
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
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = color,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (result.success) StatChip(label = "PASS", emphasize = true)
            }
            if (result.message.isNotBlank()) {
                Text(
                    text = result.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            if (result.fingerprint.isNotBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "主机指纹 ",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = result.fingerprint,
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            if (result.hostKeyType.isNotBlank() && result.hostKeyType != "unknown") {
                Text(
                    text = "主机密钥类型：${result.hostKeyType}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!result.success && result.reachable && result.usedKey) {
                Text(
                    text = "请检查：① 公钥是否已添加到 GitHub → Settings → SSH keys；" +
                        "② 用户名是否为 git；③ 私钥口令是否正确；④ 若为自建服务器，公钥是否已写入 ~/.ssh/authorized_keys。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
