package com.mobilecoder.ide.feature.git

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mobilecoder.ide.core.common.theme.LocalAppPalette
import com.mobilecoder.ide.core.common.ui.EmptyState
import com.mobilecoder.ide.core.common.ui.StatChip

/** Diff 查看器的加载状态。 */
private data class DiffUiState(
    val loading: Boolean = true,
    val diff: DiffFilePatch? = null,
)

/**
 * 单文件 Diff 查看器（PRD 2.5「文件变更可视化 Diff」）。
 *
 * 解析 [GitController.fileDiff] 返回的 unified patch，逐行按 +/-/@@ 着色，
 * 配色取自 [LocalAppPalette] 的 diff / log 字段（深浅色自动切换）。
 */
@Composable
fun DiffViewer(
    path: String,
    staged: Boolean,
    modifier: Modifier = Modifier,
) {
    val state by produceState(
        initialValue = DiffUiState(),
        key1 = path,
        key2 = staged,
    ) {
        val diff = runCatching { GitController.fileDiff(path, staged) }.getOrNull()
        value = DiffUiState(loading = false, diff = diff)
    }

    val palette = LocalAppPalette.current
    val ui = state

    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 420.dp)
            .background(MaterialTheme.colorScheme.surface)
            .verticalScroll(rememberScrollState())
            .padding(bottom = 8.dp),
    ) {
        when {
            ui.loading -> Text(
                text = "正在生成差异…",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )

            ui.diff == null -> EmptyState(
                title = "该文件暂无可显示的差异",
                subtitle = if (staged) "（树 → 索引）" else "（索引 → 工作区）",
            )

            else -> {
                val diff = ui.diff
                DiffHeader(diff = diff, palette = palette)
                diff.hunks.forEach { hunk ->
                    HunkHeader(header = hunk.header, palette = palette)
                    hunk.lines.forEach { line ->
                        DiffLineRow(line = line)
                    }
                }
            }
        }
    }
}

/** 文件头：类型标签 + 增删统计。 */
@Composable
private fun DiffHeader(diff: DiffFilePatch, palette: com.mobilecoder.ide.core.common.theme.AppPalette) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Text(
            text = diff.path,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = diff.kindLabel,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        StatChip(label = "新增", value = "+${diff.linesAdd}", emphasize = true)
        StatChip(label = "删除", value = "-${diff.linesDel}", emphasize = true)
        if (diff.isBinary) {
            Text(
                text = "二进制文件不展示内容",
                style = MaterialTheme.typography.labelSmall,
                color = palette.logWarn.toComposeColor(),
            )
        }
    }
}

/** `@@ -a,b +c,d @@` 区段头。 */
@Composable
private fun HunkHeader(header: String, palette: com.mobilecoder.ide.core.common.theme.AppPalette) {
    Text(
        text = header,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 3.dp),
        style = MaterialTheme.typography.labelSmall,
        fontFamily = FontFamily.Monospace,
        color = palette.logInfo.toComposeColor(),
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

/** 单行差异（行号 + 符号 + 内容）。 */
@Composable
fun DiffLineRow(
    line: DiffLine,
    modifier: Modifier = Modifier,
) {
    val palette = LocalAppPalette.current
    val background = when (line.type) {
        DiffLineType.ADD -> palette.diffAddedBackground.toComposeColor()
        DiffLineType.DEL -> palette.diffRemovedBackground.toComposeColor()
        else -> Color.Transparent
    }
    val foreground = when (line.type) {
        DiffLineType.ADD -> palette.diffAddedForeground.toComposeColor()
        DiffLineType.DEL -> palette.diffRemovedForeground.toComposeColor()
        DiffLineType.META -> palette.logVerbose.toComposeColor()
        else -> MaterialTheme.colorScheme.onSurface
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(background),
    ) {
        Text(
            text = line.oldNo?.toString() ?: "",
            modifier = Modifier.width(38.dp),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = palette.logVerbose.toComposeColor(),
            maxLines = 1,
        )
        Text(
            text = line.newNo?.toString() ?: "",
            modifier = Modifier.width(38.dp),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = palette.logVerbose.toComposeColor(),
            maxLines = 1,
        )
        Text(
            text = line.text,
            modifier = Modifier
                .weight(1f)
                .padding(end = 8.dp),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = foreground,
        )
    }
}
