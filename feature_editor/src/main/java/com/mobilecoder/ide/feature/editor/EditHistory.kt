package com.mobilecoder.ide.feature.editor

import kotlin.math.abs

/**
 * 撤销 / 重做历史（纯逻辑、无 Android 依赖，便于单测）。
 *
 * 合并（coalescing）规则：连续的小改动（长度变化 ≤ 1 且距上次入账 < [COALESCE_MS] 毫秒）
 * 会被合并为一步 —— 对应打字场景，一次撤销回到整段输入之前；
 * 粘贴、删除多行等大改动各自独立成一步。任何新改动都会清空重做栈。
 *
 * @param limit 撤销栈容量上限（超出丢弃最早一步）
 */
class EditHistory<T : Any>(private val limit: Int = DEFAULT_LIMIT) {

    private val undoStack = ArrayDeque<T>()
    private val redoStack = ArrayDeque<T>()
    private var lastRecordAt = 0L

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    /**
     * 记录一次改动前的状态 [before]。
     *
     * @param delta 本次改动的长度差（新长度 - 旧长度），用于判断是否为连续键入
     * @param now 单调递增的时间毫秒，测试可显式传入
     */
    fun record(before: T, delta: Int, now: Long = System.currentTimeMillis()) {
        redoStack.clear()
        val coalescable = undoStack.isNotEmpty() &&
            abs(delta) <= 1 &&
            now - lastRecordAt < COALESCE_MS
        if (coalescable) return
        undoStack.addLast(before)
        if (undoStack.size > limit) undoStack.removeFirst()
        lastRecordAt = now
    }

    /**
     * 撤销一步：把当前状态 [current] 压入重做栈，返回应恢复的状态；
     * 无可撤销时返回 null。
     */
    fun undo(current: T): T? = step(current, undoStack, redoStack)

    /** 重做一步，语义同 [undo]。 */
    fun redo(current: T): T? = step(current, redoStack, undoStack)

    private fun step(current: T, from: ArrayDeque<T>, to: ArrayDeque<T>): T? {
        if (from.isEmpty()) return null
        val target = from.removeLast()
        to.addLast(current)
        if (to.size > limit) to.removeFirst()
        lastRecordAt = 0 // 打断合并窗口：撤销后的输入不与历史里的键入合并
        return target
    }

    /** 丢弃全部历史（关闭文件 / 切换项目）。 */
    fun clear() {
        undoStack.clear()
        redoStack.clear()
        lastRecordAt = 0
    }

    companion object {
        const val DEFAULT_LIMIT = 120
        const val COALESCE_MS = 800L
    }
}
