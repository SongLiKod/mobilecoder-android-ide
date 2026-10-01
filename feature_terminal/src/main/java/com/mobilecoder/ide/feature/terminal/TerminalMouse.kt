package com.mobilecoder.ide.feature.terminal

/**
 * 鼠标事件序列编码：把触摸（滚轮 / 点击）翻译成 PTY 能理解的鼠标序列。
 *
 * 应用以 DECSET `?1000/1002/1003` 开启鼠标报告后，桌面终端会把滚轮转发给它，
 * 由 TUI（opencode / lazygit / htop …）自己滚动面板；这里遵循同一约定：
 *
 *  - SGR（`?1006`，现代 TUI 都会开）：`CSI < b ; x ; y M`（按下/滚轮）或 `m`（释放），
 *    坐标 1 基、无上限；
 *  - X10 传统编码：`CSI M` + (b+32)(x+32)(y+32) 三字节，坐标夹在 1..223，
 *    **没有释放语义** —— 非 SGR 模式下释放事件返回空序列（不发送）。
 */
object TerminalMouse {

    /** 左键（button 0） */
    const val LEFT = 0

    /** 滚轮上 / 下（button 位域 64 / 65，SGR 与 X10 同值） */
    const val WHEEL_UP = 64
    const val WHEEL_DOWN = 65

    /** X10 单坐标上限：col + 32 <= 255 */
    private const val X10_MAX = 223

    /**
     * 编码一个鼠标事件。
     *
     * @param sgr `?1006` 是否开启（决定编码格式）
     * @param button 按钮位域：0 = 左键，64/65 = 滚轮上/下
     * @param col,row 1 基字符格坐标
     * @param press `false` = 释放（仅 SGR 支持，X10 返回空数组）
     */
    fun encode(sgr: Boolean, button: Int, col: Int, row: Int, press: Boolean): ByteArray {
        if (sgr) {
            val m = if (press) 'M' else 'm'
            return "\u001b[<${button};${col.coerceAtLeast(1)};${row.coerceAtLeast(1)}$m".toByteArray()
        }
        if (!press) return ByteArray(0) // X10 无法表达释放
        return ByteArray(6).also {
            it[0] = 0x1B
            it[1] = '['.code.toByte()
            it[2] = 'M'.code.toByte()
            it[3] = button.coerceIn(0, 223).plus(32).toByte()
            it[4] = col.coerceIn(1, X10_MAX).plus(32).toByte()
            it[5] = row.coerceIn(1, X10_MAX).plus(32).toByte()
        }
    }
}
