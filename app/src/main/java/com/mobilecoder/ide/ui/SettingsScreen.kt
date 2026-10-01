package com.mobilecoder.ide.ui

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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
    }
}

/** 字号调节行：标题 + 数值 + 减 / 加（触达 ≥40dp，按钮在范围端点自动禁用）。 */
@Composable
private fun FontSizeRow(
    title: String,
    value: Int,
    range: IntRange,
    onChange: (Int) -> Unit,
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
            text = "$value sp",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(48.dp),
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
