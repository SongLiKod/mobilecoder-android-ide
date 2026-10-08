package com.mobilecoder.ide.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mobilecoder.ide.R
import com.mobilecoder.ide.core.common.theme.ThemeManager
import com.mobilecoder.ide.core.common.ui.SectionHeader
import com.mobilecoder.ide.core.storage.AppStorage
import com.mobilecoder.ide.feature.editor.EditorController
import kotlinx.coroutines.launch

/**
 * 「设置」页（二级路由，返回条由 app 壳的 [SubPage] 统一提供）。
 *
 * PRD 2.1 主题三模式从首页迁入；字号与编辑器 ⋮ 菜单 / 终端溢出菜单里的
 * 字号调节收敛到此处（编辑器写 [EditorController] 持久化设置，终端写
 * [AppStorage.preferences]，与原菜单同一条存储链路）。
 */
@Composable
fun SettingsScreen(
    themeManager: ThemeManager,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 编辑器设置（单例；先 ensureInit 载入持久化值）
    LaunchedEffect(Unit) {
        runCatching { EditorController.ensureInit(context) }
    }
    val editorSettings by EditorController.settings.collectAsStateWithLifecycle()

    // 终端字号（AppPreferences；切回终端页时由其 LaunchedEffect 重新读取生效）
    var termFontSize by remember { mutableIntStateOf(13) }
    LaunchedEffect(Unit) {
        runCatching { termFontSize = AppStorage.preferences.terminalFontSize() }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        // ---------------- 外观 ----------------
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            SectionHeader(title = stringResource(R.string.theme_section_title))
            Spacer(modifier = Modifier.padding(top = 8.dp))
            ThemeModeRow(themeManager = themeManager)
            Text(
                text = stringResource(R.string.theme_scope_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))

        // ---------------- 字体 ----------------
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            SectionHeader(title = "字体")
            Spacer(modifier = Modifier.padding(top = 4.dp))
            FontSizeRow(
                title = "编辑器字号",
                value = editorSettings.fontSize,
                range = 10..28,
                onChange = { EditorController.setFontSize(it) },
            )
            FontSizeRow(
                title = "终端字号",
                value = termFontSize,
                range = 9..24,
                onChange = { next ->
                    termFontSize = next
                    scope.launch { runCatching { AppStorage.preferences.setTerminalFontSize(next) } }
                },
            )
            Text(
                text = "编辑器立即生效；终端字号在下次进入终端页时应用",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))

        // ---------------- 文件打开 ----------------
        var unopenableDialogOpen by remember { mutableStateOf(false) }
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            SectionHeader(title = "文件打开")
            Spacer(modifier = Modifier.padding(top = 4.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "不支持打开的文件类型",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "${editorSettings.unopenableExts.size} 项",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(56.dp),
                )
                TextButton(
                    onClick = { unopenableDialogOpen = true },
                    modifier = Modifier.heightIn(min = 40.dp),
                ) {
                    Text(text = "自定义…", style = MaterialTheme.typography.labelLarge)
                }
            }
            Text(
                text = "这些扩展名的文件在编辑器里点击时只提示「不支持在线预览或编辑」，不加载内容，" +
                    "避免大文件 / 二进制把界面卡死；超过大小上限或二进制内容的文件始终会被拦截。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            FontSizeRow(
                title = "单个文件打开上限",
                value = editorSettings.maxOpenMb,
                range = 1..1024,
                unit = "MB",
                onChange = { EditorController.setMaxOpenMb(it) },
            )
            Text(
                text = "点击超过该大小的文件只提示不支持预览或编辑，防止整读大文件卡死界面（默认 10MB）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        if (unopenableDialogOpen) {
            UnopenableExtsDialog(
                initial = editorSettings.unopenableExts,
                onConfirm = { exts ->
                    unopenableDialogOpen = false
                    EditorController.setUnopenableExts(exts)
                },
                onDismiss = { unopenableDialogOpen = false },
            )
        }
    }
}

/**
 * 「不支持打开的文件类型」编辑弹窗：每行（或逗号）一个扩展名，可带可不带点。
 * 保存经 [EditorController.setUnopenableExts] 归一化（小写、去点）后注入拦截口径。
 */
@Composable
private fun UnopenableExtsDialog(
    initial: List<String>,
    onConfirm: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial.joinToString("\n")) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("不支持打开的文件类型") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "每行一个扩展名（也可用逗号分隔，带不带点均可）。这些文件在编辑器里点击时" +
                        "会提示不支持预览或编辑；留空则全部尝试打开，但仍会拦截二进制与超过大小上限的文件。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 160.dp),
                    placeholder = { Text("apk\njar\nso\nzip") },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(parseExts(text)) }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/** 扩展名名单解析：按行 / 逗号 / 分号切分，去点、转小写并去重。 */
private fun parseExts(raw: String): List<String> = raw
    .split('\n', '\r', ',', '，', ';', '；', '、', ' ')
    .map { it.trim().removePrefix(".").lowercase() }
    .filter { it.isNotEmpty() }
    .distinct()

/** 数值调节行：标题 + 数值 + 减 / 加（触达 ≥40dp，按钮在范围端点自动禁用）。 */
@Composable
private fun FontSizeRow(
    title: String,
    value: Int,
    range: IntRange,
    onChange: (Int) -> Unit,
    unit: String = "sp",
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "$value $unit",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(64.dp),
        )
        IconButton(
            onClick = { onChange(value - 1) },
            enabled = value > range.first,
        ) {
            Icon(Icons.Default.Remove, contentDescription = "减小$title")
        }
        IconButton(
            onClick = { onChange(value + 1) },
            enabled = value < range.last,
        ) {
            Icon(Icons.Default.Add, contentDescription = "增大$title")
        }
    }
}
