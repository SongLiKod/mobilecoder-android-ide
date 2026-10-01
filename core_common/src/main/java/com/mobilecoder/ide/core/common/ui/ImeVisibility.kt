package com.mobilecoder.ide.core.common.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.LocalDensity

/**
 * 软键盘（IME）是否可见：正在输入为 true，收起键盘后回到 false。
 *
 * 用法：
 *  - 底部导航在键盘弹出时整条隐藏、收起键盘后恢复（AppRoot），
 *    避免「导航栏 + 输入区」吃掉近半屏；
 *  - 终端的键盘图标据此做「收起 / 唤起」双向切换（TerminalScreen）。
 *
 * 实现说明：只读 Compose 自带的 [WindowInsets.ime]。它底层的 `insets` 字段是
 * `mutableStateOf` 快照状态，**在组合期读取即完成订阅**，键盘弹出/收起会自动触发
 * 本组件重组；且这套 insets 监听由 Compose 自己在 ComposeView 上注册（`imePadding()`
 * 已在用），本方法**不注册任何 View 监听、不碰 AndroidX insets API，全程零副作用**：
 * 最坏情况只是取不到最新值（功能不生效），不可能拖垮启动。
 */
@Composable
fun isImeVisible(): Boolean = WindowInsets.ime.getBottom(LocalDensity.current) > 0
