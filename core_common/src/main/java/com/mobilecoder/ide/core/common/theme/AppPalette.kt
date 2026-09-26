package com.mobilecoder.ide.core.common.theme

/**
 * 统一颜色表示（0xRRGGBB，不含 Alpha），与平台无关，便于单测严格校验固定色值。
 */
data class AppColor(val rgb: Long) {

    init {
        require(rgb in 0x000000..0xFFFFFF) { "AppColor 必须是 0xRRGGBB 格式: $rgb" }
    }

    /** 规范十六进制写法，例如 #2563EB */
    val hex: String
        get() = "#%06X".format(rgb)

    companion object {
        /** 解析 #RGB / #RRGGBB / RRGGBB 文本，失败返回 null */
        fun parse(text: String?): AppColor? {
            if (text.isNullOrBlank()) return null
            val raw = text.trim().removePrefix("#")
            return when (raw.length) {
                3 -> {
                    val expanded = raw.map { "$it$it" }.joinToString("")
                    expanded.toLongOrNull(16)?.let { AppColor(it) }
                }
                6 -> raw.toLongOrNull(16)?.let { AppColor(it) }
                else -> null
            }
        }
    }
}

/**
 * 全局主题色规范。
 *
 * [primary] / [background] / [surface] / [onBackground] 四个基础色严格按照 TECH.md 3.3
 * 固定色值；其余派生色用于终端配色、Git Diff 配色、日志高亮、弹窗组件等生效范围
 * （PRD 2.1 主题生效范围），与基础色保持同一体系。
 */
data class AppPalette(
    val isDark: Boolean,
    val primary: AppColor,
    val background: AppColor,
    val surface: AppColor,
    val onBackground: AppColor,
    val onPrimary: AppColor,
    val onSurface: AppColor,
    val surfaceVariant: AppColor,
    val onSurfaceVariant: AppColor,
    val outline: AppColor,
    val error: AppColor,
    val success: AppColor,
    val warning: AppColor,
    // 终端配色（主题生效范围：终端配色同步切换）
    val terminalBackground: AppColor,
    val terminalForeground: AppColor,
    val terminalSelection: AppColor,
    val terminalRed: AppColor,
    val terminalGreen: AppColor,
    val terminalYellow: AppColor,
    val terminalBlue: AppColor,
    val terminalMagenta: AppColor,
    val terminalCyan: AppColor,
    val terminalWhite: AppColor,
    // Git Diff 配色
    val diffAddedBackground: AppColor,
    val diffAddedForeground: AppColor,
    val diffRemovedBackground: AppColor,
    val diffRemovedForeground: AppColor,
    // 日志高亮
    val logVerbose: AppColor,
    val logDebug: AppColor,
    val logInfo: AppColor,
    val logWarn: AppColor,
    val logError: AppColor,
) {
    companion object {
        /** 浅色主题（TECH.md 3.3 浅色主题） */
        val LIGHT = AppPalette(
            isDark = false,
            primary = AppColor(0x2563EB),
            background = AppColor(0xFFFFFF),
            surface = AppColor(0xF8FAFC),
            onBackground = AppColor(0x111827),
            onPrimary = AppColor(0xFFFFFF),
            onSurface = AppColor(0x334155),
            surfaceVariant = AppColor(0xE2E8F0),
            onSurfaceVariant = AppColor(0x475569),
            outline = AppColor(0xCBd5E1),
            error = AppColor(0xDC2626),
            success = AppColor(0x16A34A),
            warning = AppColor(0xD97706),
            terminalBackground = AppColor(0xF8FAFC),
            terminalForeground = AppColor(0x111827),
            terminalSelection = AppColor(0xBFDBFE),
            terminalRed = AppColor(0xB91C1C),
            terminalGreen = AppColor(0x15803D),
            terminalYellow = AppColor(0xA16207),
            terminalBlue = AppColor(0x1D4ED8),
            terminalMagenta = AppColor(0x9333EA),
            terminalCyan = AppColor(0x0E7490),
            terminalWhite = AppColor(0x334155),
            diffAddedBackground = AppColor(0DCFCE7),
            diffAddedForeground = AppColor(0x166534),
            diffRemovedBackground = AppColor(0xFEE2E2),
            diffRemovedForeground = AppColor(0x991B1B),
            logVerbose = AppColor(0x64748B),
            logDebug = AppColor(0x0891B2),
            logInfo = AppColor(0x2563EB),
            logWarn = AppColor(0xD97706),
            logError = AppColor(0xDC2626),
        )

        /** 深色主题（TECH.md 3.3 深色主题） */
        val DARK = AppPalette(
            isDark = true,
            primary = AppColor(0x60A5FA),
            background = AppColor(0x0F172A),
            surface = AppColor(0x1E293B),
            onBackground = AppColor(0xE2E8F0),
            onPrimary = AppColor(0x0F172A),
            onSurface = AppColor(0xCBD5E1),
            surfaceVariant = AppColor(0x334155),
            onSurfaceVariant = AppColor(0x94A3B8),
            outline = AppColor(0x475569),
            error = AppColor(0xF87171),
            success = AppColor(0x4ADE80),
            warning = AppColor(0xFBBF24),
            terminalBackground = AppColor(0x0F172A),
            terminalForeground = AppColor(0xE2E8F0),
            terminalSelection = AppColor(0x334155),
            terminalRed = AppColor(0xF87171),
            terminalGreen = AppColor(0x4ADE80),
            terminalYellow = AppColor(0xFBBF24),
            terminalBlue = AppColor(0x60A5FA),
            terminalMagenta = AppColor(0xC084FC),
            terminalCyan = AppColor(0x22D3EE),
            terminalWhite = AppColor(0xE2E8F0),
            diffAddedBackground = AppColor(0x14532D),
            diffAddedForeground = AppColor(0xBBF7D0),
            diffRemovedBackground = AppColor(0x7F1D1D),
            diffRemovedForeground = AppColor(0xFECACA),
            logVerbose = AppColor(0x94A3B8),
            logDebug = AppColor(0x22D3EE),
            logInfo = AppColor(0x60A5FA),
            logWarn = AppColor(0xFBBF24),
            logError = AppColor(0xF87171),
        )

        /** 依据有效深浅状态取主题色 */
        fun of(isDark: Boolean): AppPalette = if (isDark) DARK else LIGHT
    }
}
