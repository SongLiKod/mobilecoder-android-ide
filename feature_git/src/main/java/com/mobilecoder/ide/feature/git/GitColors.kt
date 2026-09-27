package com.mobilecoder.ide.feature.git

import androidx.compose.ui.graphics.Color
import com.mobilecoder.ide.core.common.theme.AppColor

/**
 * 主题色转换：`AppPalette` 的字段是平台无关的 [AppColor]（0xRRGGBB），
 * Compose 组件需要带 Alpha 的 [Color]，统一在这里补上不透明 Alpha。
 *
 * 配色取值仍严格来自 `MaterialTheme.colorScheme` 或 `LocalAppPalette.current`，
 * 本文件只负责类型适配（TECH.md：Diff / 日志配色随主题切换）。
 */
internal fun AppColor.toComposeColor(): Color = Color(0xFF000000 or rgb)
