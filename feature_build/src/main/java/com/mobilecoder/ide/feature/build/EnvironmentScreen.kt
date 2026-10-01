package com.mobilecoder.ide.feature.build

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.io.File

/**
 * 「环境中心」路由页（PRD 2.7 构建环境就绪检测 + 软件市场）。
 *
 * 本页是**二级路由**：返回栏（返回箭头 + 「环境中心」标题）与系统返回键由 app 壳层统一
 * 处理，因此这里**不自带**返回栏 / 页面大标题，也不注册 BackHandler。
 *
 * 页面顶部一段式分段控件，两个分段：
 *  - **构建环境**：[EnvironmentContent]（原「构建环境」对话框体去掉对话框外壳后转为
 *    页面内容——组件列表 / 下载源 / 在线下载与导入 / Linux rootfs 源管理 / 内存上限 /
 *    重新检测 / 环境体检）；构建变体选择留在「构建与运行」页。
 *  - **软件市场**：[MarketScreen]（安装在 [MarketInstaller] 单例里静默进行，
 *    离开本页不中断，回来后继续观察同一任务）。
 *
 * @param startOnMarket 初始是否选中「软件市场」分段（构建页「环境状态条」之外的市场入口传 true）
 * @param projectPath 当前项目路径；为 null 时按默认（安卓）项目类型展示必需组件，
 *                    并在「构建环境」分段顶部给出提示
 */
@Composable
fun EnvironmentScreen(
    startOnMarket: Boolean = false,
    projectPath: String? = null,
    modifier: Modifier = Modifier,
) {
    // rememberSaveable + inputs：每次以不同 startOnMarket 进入本页时按入参重新选中分段
    var showMarket by rememberSaveable(startOnMarket) { mutableStateOf(startOnMarket) }
    val projectDir = remember(projectPath) { projectPath?.let { File(it) } }

    Column(modifier = modifier.fillMaxSize()) {

        // ---------------- 分段切换：构建环境 | 软件市场 ----------------
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            listOf(false to "构建环境", true to "软件市场").forEachIndexed { index, (isMarket, label) ->
                SegmentedButton(
                    selected = showMarket == isMarket,
                    onClick = { showMarket = isMarket },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = 2),
                ) {
                    Text(label, style = MaterialTheme.typography.labelMedium)
                }
            }
        }

        if (showMarket) {
            MarketScreen(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )
        } else {
            if (projectPath == null) {
                Text(
                    text = "打开项目后将按项目类型校验必需组件",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            EnvironmentContent(
                projectDir = projectDir,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )
        }
    }
}
