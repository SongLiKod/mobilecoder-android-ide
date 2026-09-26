package com.mobilecoder.ide.core.common.theme

import android.content.res.Configuration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration

/**
 * 当前生效的完整调色板。
 *
 * 除了 Material3 ColorScheme 暴露的基础色，[AppPalette] 还携带终端配色、Git Diff 配色、
 * 日志高亮等 PRD 2.1「主题生效范围」内的派生色，各 feature 模块通过本 CompositionLocal
 * 取用，保证全站（编辑器 / 终端 / Diff / 日志 / 弹窗）同步切换。
 */
val LocalAppPalette = staticCompositionLocalOf { AppPalette.LIGHT }

/**
 * 全局主题入口（TECH.md 3.1 / 3.2 / 3.3 / 3.4）。
 *
 * - 三模式：LIGHT 强制浅色、DARK 强制深色、SYSTEM 跟随系统
 * - 固定色值取自 [AppPalette.LIGHT] / [AppPalette.DARK]，与系统 UiMode 联动
 * - 切换仅重建 Compose 组合，不重建 Activity（Manifest 已声明 configChanges 含 uiMode）
 */
@Composable
fun MobileCoderTheme(
    mode: AppThemeMode,
    content: @Composable () -> Unit,
) {
    val configuration = LocalConfiguration.current
    val systemIsDark =
        (configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    val isDark = ThemeManager.resolveIsDark(mode, systemIsDark)
    val palette = remember(isDark) { AppPalette.of(isDark) }

    CompositionLocalProvider(LocalAppPalette provides palette) {
        MaterialTheme(
            colorScheme = palette.toColorScheme(),
            content = content,
        )
    }
}

// ---------------------------------------------------------------------------
// AppPalette → Material3 ColorScheme
// ---------------------------------------------------------------------------

/** 0xRRGGBB → Compose Color（补足不透明 Alpha 通道）。 */
private fun AppColor.toComposeColor(): Color = Color(0xFF000000 or rgb)

/**
 * 两色线性插值，用于派生容器色 / 描边次级色。
 *
 * TECH.md 3.3 只固定了 primary / background / surface / onBackground 四个基础色，
 * Material3 其余角色一律由这四色派生，避免引入调色板之外的外来色相。
 */
private fun mix(from: AppColor, to: AppColor, ratio: Float): AppColor {
    val t = ratio.coerceIn(0f, 1f)
    val f = from.rgb.toInt()
    val g = to.rgb.toInt()

    fun channel(shift: Int): Int {
        val a = (f shr shift) and 0xFF
        val b = (g shr shift) and 0xFF
        return (a + (b - a) * t).toInt().coerceIn(0, 255)
    }

    return AppColor(((channel(16) shl 16) or (channel(8) shl 8) or channel(0)).toLong())
}

private fun AppPalette.toColorScheme() =
    if (isDark) {
        darkColorScheme(
            primary = primary.toComposeColor(),
            onPrimary = onPrimary.toComposeColor(),
            primaryContainer = mix(primary, background, 0.82f).toComposeColor(),
            onPrimaryContainer = primary.toComposeColor(),
            secondary = primary.toComposeColor(),
            onSecondary = onPrimary.toComposeColor(),
            secondaryContainer = mix(primary, background, 0.82f).toComposeColor(),
            onSecondaryContainer = primary.toComposeColor(),
            tertiary = primary.toComposeColor(),
            onTertiary = onPrimary.toComposeColor(),
            tertiaryContainer = mix(primary, background, 0.82f).toComposeColor(),
            onTertiaryContainer = primary.toComposeColor(),
            background = background.toComposeColor(),
            onBackground = onBackground.toComposeColor(),
            surface = surface.toComposeColor(),
            onSurface = onSurface.toComposeColor(),
            surfaceVariant = surfaceVariant.toComposeColor(),
            onSurfaceVariant = onSurfaceVariant.toComposeColor(),
            outline = outline.toComposeColor(),
            outlineVariant = mix(outline, background, 0.5f).toComposeColor(),
            error = error.toComposeColor(),
            onError = background.toComposeColor(),
            inverseSurface = onBackground.toComposeColor(),
            inverseOnSurface = background.toComposeColor(),
            inversePrimary = mix(primary, background, 0.5f).toComposeColor(),
        )
    } else {
        lightColorScheme(
            primary = primary.toComposeColor(),
            onPrimary = onPrimary.toComposeColor(),
            primaryContainer = mix(primary, background, 0.88f).toComposeColor(),
            onPrimaryContainer = primary.toComposeColor(),
            secondary = primary.toComposeColor(),
            onSecondary = onPrimary.toComposeColor(),
            secondaryContainer = mix(primary, background, 0.88f).toComposeColor(),
            onSecondaryContainer = primary.toComposeColor(),
            tertiary = primary.toComposeColor(),
            onTertiary = onPrimary.toComposeColor(),
            tertiaryContainer = mix(primary, background, 0.88f).toComposeColor(),
            onTertiaryContainer = primary.toComposeColor(),
            background = background.toComposeColor(),
            onBackground = onBackground.toComposeColor(),
            surface = surface.toComposeColor(),
            onSurface = onSurface.toComposeColor(),
            surfaceVariant = surfaceVariant.toComposeColor(),
            onSurfaceVariant = onSurfaceVariant.toComposeColor(),
            outline = outline.toComposeColor(),
            outlineVariant = mix(outline, background, 0.5f).toComposeColor(),
            error = error.toComposeColor(),
            onError = background.toComposeColor(),
            inverseSurface = onBackground.toComposeColor(),
            inverseOnSurface = background.toComposeColor(),
            inversePrimary = mix(primary, background, 0.5f).toComposeColor(),
        )
    }
