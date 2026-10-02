package com.mobilecoder.ide.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mobilecoder.ide.core.common.ui.SectionHeader

/**
 * 「更多」页（底栏第 5 个 tab，PRD 重构 5-tab 方案）：所有二级页的入口大厅。
 *
 * 构建从底栏移入此处（编辑器工具行提供 ▶ 快捷构建）；软件市场深链到
 * 「环境中心」的市场分段（与构建环境同页，入口两处、落点一个）。
 * 每项 [AppDestination] 都是二级路由，点击后由 app 壳入栈并挂统一返回条。
 */
@Composable
fun MoreScreen(
    onOpen: (AppDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    // (目的地, 副标题)；标题统一取 dest.label，保证与返回条文案一致
    val entries = rememberMoreEntries()

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
        ) {
            SectionHeader(title = "更多", modifier = Modifier.weight(1f))
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            items(entries, key = { it.first.route }) { (destination, subtitle) ->
                MoreRow(
                    destination = destination,
                    subtitle = subtitle,
                    onClick = { onOpen(destination) },
                )
            }
        }
    }
}

/** 「更多」页入口清单（顺序即展示顺序）。 */
@Composable
private fun rememberMoreEntries(): List<Pair<AppDestination, String>> =
    remember {
        listOf(
            AppDestination.BUILD to "编译项目、查看日志与 APK 产物",
            AppDestination.AI to "对话式辅助修改项目代码",
            AppDestination.SSH to "生成 / 导入 / 一键连通性测试",
            AppDestination.MARKET to "Linux 工具包一键安装（环境中心）",
            AppDestination.HISTORY to "终端命令与构建记录",
            AppDestination.SETTINGS to "主题外观与字体字号",
            AppDestination.ABOUT to "版本信息 / 检查更新 / 使用声明",
        )
    }

/** 单个入口行：图标章 + 标题 + 副标题 + 右箭头；整行 ≥56dp 触达。 */
@Composable
private fun MoreRow(
    destination: AppDestination,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.size(40.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Icon(
                    imageVector = destination.icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = destination.label,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
