package com.mobilecoder.ide.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mobilecoder.ide.R
import com.mobilecoder.ide.core.common.theme.AppThemeMode
import com.mobilecoder.ide.core.common.theme.ThemeManager

/**
 * 全局根布局（TECH.md 7：app 负责「主入口、主题管理、全局导航」）。
 *
 * 各 feature 模块的界面将挂载到此根布局的全局导航容器中。
 * 当前承载 PRD 2.1「全局主题系统」的三模式切换入口。
 */
@Composable
fun AppRoot(
    themeManager: ThemeManager,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = stringResource(R.string.app_full_name),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text(
                text = stringResource(R.string.theme_section_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(top = 8.dp),
            )

            ThemeModeRow(themeManager = themeManager)

            Text(
                text = stringResource(R.string.theme_scope_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** PRD 2.1 三模式切换：浅色 / 深色 / 跟随系统。 */
@Composable
private fun ThemeModeRow(
    themeManager: ThemeManager,
    modifier: Modifier = Modifier,
) {
    val current by themeManager.mode.collectAsStateWithLifecycle()

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ThemeModeOption.entries.forEach { option ->
            FilterChip(
                selected = current == option.mode,
                onClick = { themeManager.setMode(option.mode) },
                label = { Text(stringResource(option.labelRes)) },
            )
        }
    }
}

/** 三模式与文案资源的对应关系。 */
private enum class ThemeModeOption(val mode: AppThemeMode, val labelRes: Int) {
    LIGHT(AppThemeMode.LIGHT, R.string.theme_light),
    DARK(AppThemeMode.DARK, R.string.theme_dark),
    SYSTEM(AppThemeMode.SYSTEM, R.string.theme_system),
}
