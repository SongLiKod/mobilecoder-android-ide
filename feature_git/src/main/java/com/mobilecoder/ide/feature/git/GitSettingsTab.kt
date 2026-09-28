package com.mobilecoder.ide.feature.git

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mobilecoder.ide.core.common.ui.SectionHeader
import com.mobilecoder.ide.core.common.ui.StatChip
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 设置页（PRD 2.5：仓库信息 / 提交身份 / 忽略规则）。
 *
 * 身份写入 `AppStorage.preferences` 并在每次提交时使用；
 * 忽略规则为 native 内存态，仅本次会话生效（不写入 .gitignore）。
 */
@Composable
fun SettingsTab(
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val repo by GitController.repo.collectAsStateWithLifecycle()
    val identity by GitController.identity.collectAsStateWithLifecycle()
    val ignoreRules by GitController.ignoreRules.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        RepositoryCard(repo = repo)
        IdentityCard(identity = identity) { name, email ->
            scope.launch { runCatching { GitController.saveIdentity(name, email) } }
        }
        IgnoreCard(
            rules = ignoreRules,
            onAdd = { rule ->
                scope.launch { runCatching { GitController.addIgnoreRule(rule) } }
            },
            onClear = {
                scope.launch { runCatching { GitController.clearIgnoreRules() } }
            },
        )
        PorcelainCard()
        Text(
            text = "提示：提交身份保存在本机应用存储；忽略规则仅内存生效，" +
                "需要持久化请在项目里编辑 .gitignore。终端也可输入 git help porcelain。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---------------------------------------------------------------------------
// 仓库信息
// ---------------------------------------------------------------------------

@Composable
private fun RepositoryCard(repo: GitRepoUiState) {
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1600)
            copied = false
        }
    }

    val dotGitExists = remember(repo.path) {
        if (repo.path.isBlank()) false else runCatching { File(repo.path, ".git").exists() }.getOrDefault(false)
    }

    SettingCard(title = "仓库信息") {
        InfoRow(label = "项目路径", value = repo.path.ifBlank { "-" }, mono = true)
        InfoRow(label = "仓库根目录", value = repo.workdir.ifBlank { "-" }, mono = true)
        InfoRow(
            label = "仓库状态",
            value = when {
                repo.opened -> "已打开"
                repo.isRepo -> "识别到仓库，但打开失败"
                else -> "当前目录不是 Git 仓库"
            },
        )
        InfoRow(label = ".git 目录", value = if (dotGitExists) "存在" else "不存在")
        InfoRow(label = "当前分支", value = repo.head?.branch?.ifBlank { "（尚无提交）" } ?: "（无 HEAD）")

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = {
                    try {
                        val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
                        clipboard?.setPrimaryClip(
                            android.content.ClipData.newPlainText("仓库路径", repo.path),
                        )
                        copied = true
                    } catch (_: Throwable) {
                        // 剪贴板不可用时静默忽略
                    }
                },
                enabled = repo.path.isNotBlank(),
            ) {
                Text(if (copied) "已复制路径" else "复制路径")
            }
            StatChip(label = "分支", value = repo.head?.branch ?: "-")
        }
    }
}

// ---------------------------------------------------------------------------
// 提交身份
// ---------------------------------------------------------------------------

@Composable
private fun IdentityCard(
    identity: GitIdentity,
    onSave: (String, String) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }

    // 首次拿到持久化身份时回填输入框
    LaunchedEffect(identity) {
        if (name.isBlank() && identity.name.isNotBlank()) name = identity.name
        if (email.isBlank() && identity.email.isNotBlank()) email = identity.email
    }

    SettingCard(title = "提交身份（commit author）") {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("姓名") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text("邮箱") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = { onSave(name.trim(), email.trim()) },
            enabled = name.isNotBlank() && email.isNotBlank(),
        ) { Text("保存身份") }
    }
}

// ---------------------------------------------------------------------------
// 忽略规则
// ---------------------------------------------------------------------------

@Composable
private fun IgnoreCard(
    rules: List<String>,
    onAdd: (String) -> Unit,
    onClear: () -> Unit,
) {
    var rule by rememberSaveable { mutableStateOf("") }

    SettingCard(title = "忽略规则（仅本次会话生效）") {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = rule,
                onValueChange = { rule = it },
                label = { Text("规则，如 *.log") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = {
                    val value = rule.trim()
                    rule = ""
                    onAdd(value)
                },
                enabled = rule.isNotBlank(),
            ) { Text("添加") }
        }

        if (rules.isEmpty()) {
            Text(
                text = "暂无会话级忽略规则",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            rules.forEach { item ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Text(
                        text = item,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
            OutlinedButton(
                onClick = onClear,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("清空忽略规则") }
        }
    }
}

// ---------------------------------------------------------------------------
// Main Porcelain 日常命令参考
// ---------------------------------------------------------------------------

@Composable
private fun PorcelainCard() {
    SettingCard(title = "日常命令参考（Main Porcelain）") {
        Text(
            text = "终端可执行标为「可用」；其余为标准 git 对照，可在 Git 页用可视化操作完成同类工作。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        GitPorcelain.groups.forEach { group ->
            Text(
                text = group.title,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
            group.commands.forEach { cmd ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(
                        text = cmd.name,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(0.32f),
                    )
                    Text(
                        text = if (cmd.implemented) "可用" else "参考",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (cmd.implemented) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.weight(0.14f),
                    )
                    Text(
                        text = cmd.summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(0.54f),
                    )
                }
            }
        }
        Text(
            text = "终端：git help porcelain",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---------------------------------------------------------------------------
// 通用卡片 / 信息行
// ---------------------------------------------------------------------------

@Composable
private fun SettingCard(
    title: String,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SectionHeader(title = title)
            content()
        }
    }
}

@Composable
private fun InfoRow(
    label: String,
    value: String,
    mono: Boolean = false,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
