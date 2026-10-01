package com.mobilecoder.ide.core.common.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity

/**
 * 软键盘（IME）当前是否可见。
 *
 * 组合期读取 [WindowInsets.ime] 的底部尺寸：该值是 Snapshot 状态，键盘弹出/收起时
 * 会让读它的 Composable 重组（`ime.getBottom()` 内部读 `insets$delegate` 状态）。
 *
 * 刻意不用 `WindowInsets.isImeVisible`：foundation 1.7 上它同时带
 * `@ExperimentalLayoutApi` 和 Deprecated 标记，跨版本不稳定。
 */
@Composable
fun isImeVisible(): Boolean {
    val density = LocalDensity.current
    return WindowInsets.ime.getBottom(density) > 0
}
