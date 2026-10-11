package com.mobilecoder.ide.feature.preview

import android.annotation.SuppressLint
import android.content.Intent
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mobilecoder.ide.core.common.ui.EmptyState

/**
 * Issue 13「HTML / Web 项目实时预览」的窗格 UI：头部动作条 + 主体
 * （静态 `file://` 页面 / dev server 页面 / 启动引导 / 进程日志）。
 *
 * 状态全部来自 [PreviewController] 单例，本 composable 无自身业务状态，
 * 因此在编辑 / 构建 / 终端之间来回切换时 WebView 与 dev server 状态天然保留。
 *
 * @param compact 小屏浮层模式：头部额外给一个「切回编辑器」按钮（浮层遮住编辑区，
 *   必须有出口），同时隐藏分屏方向切换
 * @param onSwitchToEditor 浮层模式下「切回编辑器」：只把窗格藏起来，**不动 dev server**，
 *   编辑完随时可以切回来继续看；与 [onClose]（真正关闭并停掉服务）语义不同
 * @param onClose 收起窗格（会连带停掉 dev server，见 [PreviewController.setOpen]）
 */
@Composable
fun PreviewPane(
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    onSwitchToEditor: () -> Unit = onClose,
) {
    val state by PreviewController.state.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surface)
            .heightIn(min = 120.dp),
    ) {
        PreviewHeader(
            state = state,
            compact = compact,
            onRefresh = { PreviewController.reloadNow() },
            onToggleOrientation = { PreviewController.toggleOrientation() },
            onStart = { PreviewController.startDevServer() },
            onStop = { PreviewController.stopDevServer() },
            onSwitchToEditor = onSwitchToEditor,
            onClose = onClose,
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            PreviewBody(state = state, modifier = Modifier.fillMaxSize())
        }
    }
}

// ---------------------------------------------------------------- Header

@Composable
private fun PreviewHeader(
    state: PreviewController.PreviewState,
    compact: Boolean,
    onRefresh: () -> Unit,
    onToggleOrientation: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onSwitchToEditor: () -> Unit,
    onClose: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "预览",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 12.dp),
        )
        Text(
            text = " · ${state.kind.label}${statusSuffix(state)}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(start = 2.dp, end = 4.dp),
        )
        // dev server：未运行给「启动」，运行中 / 启动中给「停止」
        if (state.kind == PreviewKind.DEV_SERVER && state.status != PreviewController.ServerStatus.FAILED) {
            if (state.running || state.status == PreviewController.ServerStatus.STARTING) {
                IconButton(onClick = onStop) {
                    Icon(Icons.Default.Stop, contentDescription = "停止 dev server")
                }
            } else {
                IconButton(onClick = onStart) {
                    Icon(Icons.Default.PlayArrow, contentDescription = "启动预览")
                }
            }
        }
        if (state.kind != PreviewKind.NONE && state.url.isNotEmpty()) {
            IconButton(onClick = onRefresh) {
                Icon(Icons.Default.Refresh, contentDescription = "刷新")
            }
        }
        if (!compact && state.kind == PreviewKind.DEV_SERVER) {
            IconButton(onClick = onToggleOrientation) {
                Icon(Icons.Default.SwapVert, contentDescription = "切换分屏方向")
            }
        }
        if (compact) {
            // 只把浮层收起来：dev server 与页面状态都留着，切回编辑器改完即回到原样
            IconButton(onClick = onSwitchToEditor) {
                Icon(Icons.Default.Edit, contentDescription = "切回编辑器")
            }
        }
        IconButton(onClick = onClose) {
            Icon(Icons.Default.Close, contentDescription = "关闭预览")
        }
    }
}

private fun statusSuffix(state: PreviewController.PreviewState): String = when (state.status) {
    PreviewController.ServerStatus.STOPPED -> ""
    PreviewController.ServerStatus.STARTING -> " · 启动中"
    PreviewController.ServerStatus.RUNNING -> " · 运行中"
    PreviewController.ServerStatus.FAILED -> " · 失败"
}

// ---------------------------------------------------------------- Body

@Composable
private fun PreviewBody(state: PreviewController.PreviewState, modifier: Modifier = Modifier) {
    when (state.kind) {
        PreviewKind.NONE -> EmptyState(
            title = "此项目没有可预览的入口",
            subtitle = "需要根目录下的 index.html（静态站点），" +
                "或 package.json 里定义了 dev 脚本（Vite 等开发服务器）",
            modifier = modifier,
        )

        PreviewKind.STATIC_HTML -> PreviewWebView(
            url = state.url,
            reloadTick = state.reloadTick,
            modifier = modifier,
        )

        PreviewKind.DEV_SERVER -> when (state.status) {
            PreviewController.ServerStatus.RUNNING -> PreviewWebView(
                url = state.url,
                reloadTick = state.reloadTick,
                modifier = modifier,
            )

            PreviewController.ServerStatus.STARTING -> StartingBody(state, modifier)

            PreviewController.ServerStatus.FAILED -> FailedBody(state, modifier)

            PreviewController.ServerStatus.STOPPED -> IdleBody(state, modifier)
        }
    }
}

@Composable
private fun IdleBody(state: PreviewController.PreviewState, modifier: Modifier) {
    Column(
        modifier = modifier.padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = "检测到 dev 脚本，尚未启动",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = "点「启动预览」会先自动安装依赖（首次），再后台执行 npm run dev；" +
                "识别到本地地址后立即加载。停止 dev server 会一并回收进程。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = { PreviewController.startDevServer() }) {
            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(text = " 启动预览", style = MaterialTheme.typography.labelLarge)
        }
        if (state.log.isNotBlank()) LogBody(state, modifier)
    }
}

@Composable
private fun StartingBody(state: PreviewController.PreviewState, modifier: Modifier) {
    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            Text(
                text = "正在启动 dev server…（依赖安装 / 首次编译可能需要 1–2 分钟）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LogBody(state, Modifier.weight(1f))
    }
}

@Composable
private fun FailedBody(state: PreviewController.PreviewState, modifier: Modifier) {
    Column(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = state.message.ifBlank { "dev server 启动失败" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { PreviewController.startDevServer() }) {
                    Text("重试")
                }
            }
        }
        LogBody(state, Modifier.weight(1f))
    }
}

@Composable
private fun LogBody(state: PreviewController.PreviewState, modifier: Modifier) {
    if (state.log.isBlank()) return
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            text = state.log,
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 200.dp)
                .verticalScroll(rememberScrollState())
                .padding(8.dp),
        )
    }
}

// ---------------------------------------------------------------- WebView

/**
 * 承载预览页面的 WebView。
 *
 * - `file://` 页面开 [WebView.getAllowFileAccess] / `AllowFileAccessFromFileURLs`，
 *   项目里的 css / js / 图片等相对引用才能加载；
 * - 站内（回环 host）链接留在 WebView 里，外链交给系统浏览器，不会把 IDE 带走；
 * - `reloadTick` 增长即 reload（[PreviewController.notifySaved] 的保存信号），
 *   静态站点由此做到「保存即刷新」；
 * - 窗格收起 / 离开组合时 [WebView.destroy]，避免 WebView 泄漏。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun PreviewWebView(url: String, reloadTick: Int, modifier: Modifier = Modifier) {
    if (url.isEmpty()) {
        EmptyState(title = "预览地址尚未就绪", modifier = modifier)
        return
    }

    val context = LocalContext.current
    var webView by remember { mutableStateOf<WebView?>(null) }
    var loadedUrl by remember { mutableStateOf("") }

    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = true
                settings.allowContentAccess = true
                // 静态页以 file:// 打开，必须放行同源 file 引用，否则 css/js 全部被拦
                @Suppress("DEPRECATION")
                settings.setAllowFileAccessFromFileURLs(true)
                @Suppress("DEPRECATION")
                settings.setAllowUniversalAccessFromFileURLs(true)
                settings.setSupportZoom(true)
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView,
                        request: WebResourceRequest,
                    ): Boolean {
                        val target = request.url
                        val host = target.host ?: return false // file:// 与 about:blank 留在页内
                        if (host == "localhost" || host == "127.0.0.1" || host == "::1") {
                            return false
                        }
                        // 外链（文档 / npm registry）交给系统浏览器，预览窗格保持自包含
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, target))
                        }
                        return true
                    }
                }
                webChromeClient = WebChromeClient()
            }.also { webView = it }
        },
        update = { view ->
            if (loadedUrl != url) {
                loadedUrl = url
                view.loadUrl(url)
            }
        },
        onRelease = { view ->
            view.stopLoading()
            view.destroy()
            webView = null
        },
        modifier = modifier,
    )

    // 保存信号 → 重新加载（首帧的 0 不触发，避免重复 loadUrl）
    LaunchedEffect(reloadTick) {
        if (reloadTick > 0) webView?.reload()
    }
}
