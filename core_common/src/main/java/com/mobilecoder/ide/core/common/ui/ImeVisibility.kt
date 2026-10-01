package com.mobilecoder.ide.core.common.ui

import android.view.View
import android.view.ViewTreeObserver
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * 软键盘（IME）可见状态：正在输入为 true，收起键盘后回到 false。
 *
 * 用法：
 *  - 底部导航在键盘弹出时整条隐藏、收起键盘后恢复（AppRoot），
 *    避免「导航栏 + 输入区」吃掉近半屏；
 *  - 终端的键盘图标据此做「收起 / 唤起」双向切换（TerminalScreen）。
 *
 * 为什么不直接用 `WindowInsets.ime`：它只是被动读取的普通值，读它不会引起重组，
 * 拿不到"状态"；这里改用根 View 的全局布局监听——键盘弹出/收起必然改变窗口
 * insets 并触发一次 layout，因此状态及时且与输入框焦点无关。
 */
@Composable
fun rememberImeVisible(): State<Boolean> {
    val view = LocalView.current
    val state = remember(view) { mutableStateOf(view.isImeVisible()) }
    DisposableEffect(view) {
        val listener = ViewTreeObserver.OnGlobalLayoutListener {
            val visible = view.isImeVisible()
            if (state.value != visible) state.value = visible
        }
        view.viewTreeObserver.addOnGlobalLayoutListener(listener)
        onDispose {
            // detach 后旧 observer 可能已失效，重新取当前的再移除
            val observer = view.viewTreeObserver
            if (observer.isAlive) observer.removeOnGlobalLayoutListener(listener)
        }
    }
    return state
}

/** 软键盘当前是否可见：IME 占用的底部像素 > 0（未显示 / 尚未分发 insets 时为 false）。 */
private fun View.isImeVisible(): Boolean {
    val imeBottom = ViewCompat.getRootWindowInsets(this)
        ?.getInsets(WindowInsetsCompat.Type.ime())
        ?.bottom ?: 0
    return imeBottom > 0
}
