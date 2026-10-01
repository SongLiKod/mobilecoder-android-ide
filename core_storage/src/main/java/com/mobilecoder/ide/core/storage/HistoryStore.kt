package com.mobilecoder.ide.core.storage

import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 一条历史记录（终端命令、构建等操作的流水）。 */
data class HistoryRecord(
    /** 稳定 id（编辑 / 删除定位用）。 */
    val id: String,
    /** 记录内容（可编辑：命令或任意文本）。 */
    val text: String,
    /** 来源标签：终端 / 构建 / 手动 … */
    val source: String,
    /** 记录时间（epoch ms）。 */
    val time: Long,
    /** 是否收藏（收藏项在「记录」页置顶）。 */
    val favorite: Boolean = false,
)

/**
 * 全局历史记录（「记录」页）：终端命令与其它操作的统一流水，
 * 每条可编辑、复制、收藏、删除。
 *
 *  - 内存态 [records] 按时间倒序（最新在前），UI 直接订阅；
 *  - 持久化走 [AppPreferences.historyRecords]（DataStore + JSON 数组）；
 *  - 首次访问 [ensureLoaded] 时迁移旧的终端命令历史（`terminal_history` 键）；
 *  - 按文本去重：重复执行同一命令 = 刷新时间戳，收藏状态保留。
 */
object HistoryStore {

    const val SOURCE_TERMINAL = "终端"
    const val SOURCE_BUILD = "构建"
    const val SOURCE_MANUAL = "手动"

    /** 最多保留的记录条数（超出后丢弃最旧的普通项，收藏项优先保留）。 */
    private const val LIMIT = 500

    private val _records = MutableStateFlow<List<HistoryRecord>>(emptyList())

    /** 全部记录，按时间倒序（最新在前）。 */
    val records: StateFlow<List<HistoryRecord>> = _records.asStateFlow()

    private val mutex = Mutex()

    @Volatile
    private var loaded = false

    /** 首次访问时加载持久化记录并迁移旧终端历史（幂等、可并发）。 */
    suspend fun ensureLoaded() {
        if (loaded) return
        mutex.withLock {
            if (loaded) return
            val stored = runCatching { AppStorage.preferences.historyRecords() }
                .getOrDefault(emptyList())
            val legacy = runCatching { AppStorage.preferences.terminalHistory() }
                .getOrDefault(emptyList())
            var list = stored
            if (legacy.isNotEmpty()) {
                // 旧列表已是「最新在前」；迁移时间从当前时刻递减，保持相对顺序
                val now = System.currentTimeMillis()
                val migrated = legacy.mapIndexed { index, text ->
                    HistoryRecord(
                        id = newId(),
                        text = text,
                        source = SOURCE_TERMINAL,
                        time = now - index,
                    )
                }
                list = (migrated + list).sortedByDescending { it.time }.take(LIMIT)
                runCatching { AppStorage.preferences.clearTerminalHistory() }
            }
            _records.value = list.sortedByDescending { it.time }
            loaded = true
        }
    }

    /** 新增（或刷新）一条记录：同文本去重置顶，收藏状态保留。 */
    suspend fun add(text: String, source: String) {
        val value = text.trim()
        if (value.isEmpty()) return
        ensureLoaded()
        mutex.withLock {
            val record = HistoryRecord(
                id = newId(),
                text = value,
                source = source,
                time = System.currentTimeMillis(),
            )
            persist(addRecord(_records.value, record, LIMIT))
        }
    }

    /** 编辑记录文本（空文本忽略）。 */
    suspend fun update(id: String, text: String) {
        val value = text.trim()
        if (value.isEmpty()) return
        ensureLoaded()
        mutex.withLock {
            persist(_records.value.map { if (it.id == id) it.copy(text = value) else it })
        }
    }

    /** 收藏 / 取消收藏。 */
    suspend fun setFavorite(id: String, favorite: Boolean) {
        ensureLoaded()
        mutex.withLock {
            persist(_records.value.map { if (it.id == id) it.copy(favorite = favorite) else it })
        }
    }

    /** 删除单条记录。 */
    suspend fun remove(id: String) {
        ensureLoaded()
        mutex.withLock {
            persist(_records.value.filterNot { it.id == id })
        }
    }

    /** 终端命令（时间倒序），供终端 ↑ 键逐条回填。 */
    fun terminalCommands(): List<String> = textsOf(_records.value, SOURCE_TERMINAL)

    // ------------------------------------------------------------------
    // 纯逻辑（JVM 单测直接覆盖）
    // ------------------------------------------------------------------

    /**
     * 追加一条记录：按文本去重（旧记录的收藏标记保留）、总量限顶
     * （收藏项优先保留）、整体按时间倒序。
     */
    internal fun addRecord(
        list: List<HistoryRecord>,
        record: HistoryRecord,
        limit: Int,
    ): List<HistoryRecord> {
        val favorite = list.firstOrNull { it.text == record.text }?.favorite ?: false
        val merged = (list.filterNot { it.text == record.text } + record.copy(favorite = favorite))
            .sortedByDescending { it.time }
        val ordered = merged.filter { it.favorite } + merged.filterNot { it.favorite }
        return ordered.take(limit).sortedByDescending { it.time }
    }

    /** 展示顺序：收藏在前（各自时间倒序），其余随后（时间倒序）。 */
    fun displayOrder(list: List<HistoryRecord>): List<HistoryRecord> {
        val sorted = list.sortedByDescending { it.time }
        return sorted.filter { it.favorite } + sorted.filterNot { it.favorite }
    }

    /** 指定来源的记录文本（时间倒序）。 */
    internal fun textsOf(list: List<HistoryRecord>, source: String): List<String> =
        list.sortedByDescending { it.time }.filter { it.source == source }.map { it.text }

    // ------------------------------------------------------------------

    private suspend fun persist(list: List<HistoryRecord>) {
        _records.value = list
        runCatching { AppStorage.preferences.setHistoryRecords(list) }
    }

    private fun newId(): String = UUID.randomUUID().toString()
}
