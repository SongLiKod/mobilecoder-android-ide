package com.mobilecoder.ide.feature.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mobilecoder.ide.core.common.theme.LocalAppPalette
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 「本轮修改」Diff 预览（P0 写保护配套）：
 * 从快照读取改前内容，与当前文件逐行对比，先列文件、点开看行级差异。
 */

// ---------------------------------------------------------------------------
// 行级 diff（LCS，纯 Kotlin，行数超限截断保护）
// ---------------------------------------------------------------------------

internal enum class AiDiffRowType { ADD, DEL, CONTEXT }

internal data class AiDiffRow(
    val type: AiDiffRowType,
    val oldNo: Int?,
    val newNo: Int?,
    val text: String,
)

/** 参与对比的最大行数（DP 表 O(n·m)，手机上限制内存）。 */
private const val MAX_DIFF_LINES = 800

/**
 * 经典 LCS 行对比。
 *
 * @return 逐行结果；两侧超过 [MAX_DIFF_LINES] 时截断到前 N 行
 * （调用方通过 [truncated] 提示用户）。
 */
internal fun diffLines(old: List<String>, new: List<String>): Pair<List<AiDiffRow>, Boolean> {
    val truncated = old.size > MAX_DIFF_LINES || new.size > MAX_DIFF_LINES
    val a = if (old.size > MAX_DIFF_LINES) old.take(MAX_DIFF_LINES) else old
    val b = if (new.size > MAX_DIFF_LINES) new.take(MAX_DIFF_LINES) else new
    val n = a.size
    val m = b.size

    // dp[i][j] = a[i:] 与 b[j:] 的 LCS 长度
    val dp = Array(n + 1) { IntArray(m + 1) }
    for (i in n - 1 downTo 0) {
        val ai = a[i]
        val rowI = dp[i]
        val rowI1 = dp[i + 1]
        for (j in m - 1 downTo 0) {
            rowI[j] = if (ai == b[j]) rowI1[j + 1] + 1 else maxOf(rowI1[j], rowI[j + 1])
        }
    }

    val rows = ArrayList<AiDiffRow>()
    var i = 0
    var j = 0
    while (i < n && j < m) {
        when {
            a[i] == b[j] -> {
                rows += AiDiffRow(AiDiffRowType.CONTEXT, i + 1, j + 1, a[i])
                i++
                j++
            }

            dp[i + 1][j] >= dp[i][j + 1] -> {
                rows += AiDiffRow(AiDiffRowType.DEL, i + 1, null, a[i])
                i++
            }

            else -> {
                rows += AiDiffRow(AiDiffRowType.ADD, null, j + 1, b[j])
                j++
            }
        }
    }
    while (i < n) {
        rows += AiDiffRow(AiDiffRowType.DEL, i + 1, null, a[i])
        i++
    }
    while (j < m) {
        rows += AiDiffRow(AiDiffRowType.ADD, null, j + 1, b[j])
        j++
    }
    return rows to truncated
}

// ---------------------------------------------------------------------------
// 对话框
// ---------------------------------------------------------------------------

@Composable
fun AiDiffDialog(
    projectRoot: String,
    snapshotId: String,
    onDismiss: () -> Unit,
) {
    val manifest by produceState<AiSnapshotStore.Manifest?>(initialValue = null, key1 = snapshotId) {
        value = AiSnapshotStore.read(snapshotId)
    }
    var selected by remember { mutableStateOf<AiSnapshotStore.Entry?>(null) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // 顶栏
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (selected != null) {
                        IconButton(
                            onClick = { selected = null },
                            modifier = Modifier.size(40.dp),
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (selected == null) "本轮修改的差异" else "差异对比",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = selected?.path
                                ?: (manifest?.let {
                                    "${it.entries.size} 个路径 · 改前 → 当前"
                                } ?: "读取快照中…"),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "关闭")
                    }
                }

                when {
                    manifest == null -> Text(
                        text = "快照不存在或已被清理",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )

                    selected == null -> FileList(
                        entries = manifest!!.entries.filter { !it.isDir },
                        onPick = { selected = it },
                    )

                    else -> DiffDetail(
                        projectRoot = projectRoot,
                        snapshotId = snapshotId,
                        entry = selected!!,
                    )
                }
            }
        }
    }
}

/** 文件列表：路径 + 类型 + 备份状态。 */
@Composable
private fun FileList(
    entries: List<AiSnapshotStore.Entry>,
    onPick: (AiSnapshotStore.Entry) -> Unit,
) {
    if (entries.isEmpty()) {
        Text(
            text = "该轮没有可对比的文件",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp),
        )
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = 16.dp,
            vertical = 8.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(entries.size) { index ->
            val entry = entries[index]
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPick(entry) }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = entry.path,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = when {
                            !entry.existed -> "新建（撤销将删除）"
                            entry.bak == null -> "改前内容过大，未备份"
                            else -> "修改（已备份改前版本）"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = when {
                            !entry.existed -> MaterialTheme.colorScheme.primary
                            entry.bak == null -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 单文件行级差异。 */
@Composable
private fun DiffDetail(
    projectRoot: String,
    snapshotId: String,
    entry: AiSnapshotStore.Entry,
) {
    val palette = LocalAppPalette.current
    val state by produceState<DiffDetailState?>(initialValue = null, key1 = snapshotId, key2 = entry) {
        value = loadDetail(projectRoot, snapshotId, entry)
    }
    val ui = state
    when {
        ui == null -> Text(
            text = "正在生成差异…",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp),
        )

        ui.notice != null -> Text(
            text = ui.notice,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp),
        )

        else -> {
            Column(modifier = Modifier.fillMaxSize()) {
                if (ui.truncated) {
                    Text(
                        text = "文件超过 $MAX_DIFF_LINES 行，仅显示前段差异",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(ui.rows.size) { index ->
                        DiffRowRow(row = ui.rows[index], palette = palette)
                    }
                }
            }
        }
    }
}

private data class DiffDetailState(
    val rows: List<AiDiffRow>,
    val truncated: Boolean,
    val notice: String? = null,
)

private suspend fun loadDetail(
    projectRoot: String,
    snapshotId: String,
    entry: AiSnapshotStore.Entry,
): DiffDetailState = withContext(Dispatchers.IO) {
    val old: String? = if (!entry.existed) {
        ""
    } else {
        entry.bak?.let { AiSnapshotStore.readBackupText(snapshotId, it) }
    }
    if (entry.existed && old == null) {
        return@withContext DiffDetailState(emptyList(), false, "改前内容过大未备份，无法预览差异")
    }
    val new = runCatching {
        val file = File(projectRoot, entry.path)
        if (file.isFile) file.readText(Charsets.UTF_8) else null
    }.getOrNull()

    if (new == null) {
        return@withContext if (old.isNullOrEmpty()) {
            DiffDetailState(emptyList(), false, "文件已不存在")
        } else {
            val (rows, trunc) = diffLines(old.replace("\r\n", "\n").split('\n'), emptyList())
            DiffDetailState(rows, trunc, "文件当前已删除（显示改前内容）")
        }
    }
    val (rows, trunc) = diffLines(
        old.orEmpty().replace("\r\n", "\n").split('\n'),
        new.replace("\r\n", "\n").split('\n'),
    )
    if (rows.none { it.type != AiDiffRowType.CONTEXT }) {
        DiffDetailState(emptyList(), false, "内容无差异（可能已被手工改回）")
    } else {
        DiffDetailState(rows, trunc, null)
    }
}

@Composable
private fun DiffRowRow(
    row: AiDiffRow,
    palette: com.mobilecoder.ide.core.common.theme.AppPalette,
) {
    fun com(c: com.mobilecoder.ide.core.common.theme.AppColor): Color =
        Color(0xFF000000 or c.rgb)

    val (bg, fg) = when (row.type) {
        AiDiffRowType.ADD -> com(palette.diffAddedBackground) to com(palette.diffAddedForeground)
        AiDiffRowType.DEL -> com(palette.diffRemovedBackground) to com(palette.diffRemovedForeground)
        AiDiffRowType.CONTEXT -> Color.Transparent to MaterialTheme.colorScheme.onSurfaceVariant
    }
    val prefix = when (row.type) {
        AiDiffRowType.ADD -> "+"
        AiDiffRowType.DEL -> "-"
        AiDiffRowType.CONTEXT -> " "
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 1.dp),
    ) {
        Text(
            text = (row.oldNo?.toString() ?: "").padStart(4),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = fg.copy(alpha = 0.6f),
            modifier = Modifier.padding(end = 6.dp),
        )
        Text(
            text = (row.newNo?.toString() ?: "").padStart(4),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = fg.copy(alpha = 0.6f),
            modifier = Modifier.padding(end = 6.dp),
        )
        Text(
            text = prefix + row.text,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = fg,
        )
    }
}
