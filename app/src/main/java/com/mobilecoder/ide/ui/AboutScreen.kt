package com.mobilecoder.ide.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mobilecoder.ide.BuildConfig
import com.mobilecoder.ide.update.UpdateController
import com.mobilecoder.ide.update.UpdatePhase
import com.mobilecoder.ide.update.UpdateState
import kotlin.math.roundToInt

/**
 * 关于页：版本信息 + 简介 + 检查更新（应用内下载、系统确认框覆盖安装，
 * 全程不跳转其他界面）+ 使用声明。
 *
 * 二级路由：返回条由 app 壳的 SubPage 统一提供，页面内不再自绘返回行。
 */
@Composable
fun AboutScreen(
    modifier: Modifier = Modifier,
) {
    val state by UpdateController.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ① 版本
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = "移动码匠 MobileCoder",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "当前版本 ${BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = context.packageName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // ② 简介
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "简介",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = AboutContent.INTRO,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // ③ 检查更新
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "检查更新",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    UpdateStatusContent(
                        state = state,
                        onCheck = { UpdateController.check() },
                        onDownload = { UpdateController.startDownload(context) },
                        onCancel = { UpdateController.cancelDownload() },
                    )
                }
            }

            // ④ 声明
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = "声明",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    AboutContent.DISCLAIMER.forEach { paragraph ->
                        Text(
                            text = paragraph,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

/** 更新区状态内容：按 [UpdateState.phase] 渲染对应操作与提示。 */
@Composable
private fun UpdateStatusContent(
    state: UpdateState,
    onCheck: () -> Unit,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
) {
    when (state.phase) {
        UpdatePhase.IDLE -> {
            Text(
                text = "当前版本 ${BuildConfig.VERSION_NAME}，可在线检查是否有新版本。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onCheck, modifier = Modifier.fillMaxWidth()) {
                Text("检查更新")
            }
        }

        UpdatePhase.CHECKING -> {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text(
                text = "正在检查…",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        UpdatePhase.UP_TO_DATE -> {
            Text(
                text = state.message ?: "已是最新版本（当前 ${BuildConfig.VERSION_NAME}）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
            state.latestTag?.let {
                Text(
                    text = "最新发布：$it",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onCheck) { Text("重新检查") }
        }

        UpdatePhase.AVAILABLE -> {
            Text(
                text = "发现新版本 ${state.latestTag ?: ""}（当前 ${BuildConfig.VERSION_NAME}）",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = "将在应用内下载并调起系统安装确认，全程不跳转其他界面。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            state.message?.let {
                if (it != "发现新版本") {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Button(onClick = onDownload, modifier = Modifier.fillMaxWidth()) {
                Text("下载并安装")
            }
            TextButton(onClick = onCheck) { Text("重新检查") }
        }

        UpdatePhase.DOWNLOADING -> {
            val progress = state.progress
            if (progress != null) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "下载中 ${(progress * 100).roundToInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(
                    text = "下载中…",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                Text("取消下载")
            }
        }

        UpdatePhase.INSTALLING -> {
            Text(
                text = state.message ?: "等待系统安装确认",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = "请在系统弹出的安装确认框中点「安装」（确认框覆盖在本应用上方）。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        UpdatePhase.ERROR -> {
            Text(
                text = state.message ?: "检查更新失败",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            Button(onClick = onCheck, modifier = Modifier.fillMaxWidth()) {
                Text("重试")
            }
        }
    }
}
