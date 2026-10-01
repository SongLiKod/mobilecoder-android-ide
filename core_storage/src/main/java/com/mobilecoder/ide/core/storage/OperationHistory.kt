package com.mobilecoder.ide.core.storage

import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 一条操作历史记录（原 CLI 面板废弃后，取而代之的「操作历史」条目）。 */
data class OperationRecord(
    /** 稳定 id（记录可被单独删除 / 收藏，重复命令不会互相影响）。 */
    val id: String,
    /** 执行的命令或操作描述，例如 `apt build` / `npm install` / `gradle assembleDebug`。 */
    val command: String,
    /** 记录来源（终端 / apt 命令 / 构建 / Git）。 */
    val source: OperationSource,
    /** 所属项目（目录名），未知时为空串。 */
    val project: String = "",
    /** 退出码；`null` = 未知（shell 命令拿不到退出码），`-1` = 被取消。 */
    val exitCode: Int? = null,
    /** 耗时（毫秒）；`null` = 未知。 */
    val durationMs: Long? = null,
    /** 发生时间（epoch ms）。 */
    val createdAt: Long = System.currentTimeMillis(),
    /** 是否被收藏（收藏记录不会被容量上限挤掉，也不会被「清空」删除）。 */
    val favorite: Boolean = false,
)

/** 记录来源，决定历史列表里的彩色标签。 */
enum class OperationSource(val label: String) {
    /** 终端里直通 shell 的普通命令（`ls`、`npm install`、`git status` …）。 */
    TERMINAL("终端"),

    /** 进程内 apt 命令（`apt build` / `apt lint` / `git …` 等被注册表接管的行）。 */
    CLI("apt 命令"),

    /** 「构建」页发起的 Gradle 构建 / 打包。 */
    BUILD("构建"),

    /** Git 页与首页发起的克隆 / 提交 / 推送 / 拉取 / 抓取。 */
    GIT("Git"),
    ;

    companion object {
        /** 解析磁盘数据（未知值兜底为 [TERMINAL]，保证旧数据/脏数据不炸）。 */
        fun of(name: String?): OperationSource =
            entries.firstOrNull { it.name == name } ?: TERMINAL
    }
}

/**
 * 操作历史存储（原 CLI 面板的「日志 + 命令」界面废弃后，由本存储承接历史需求）。
 *
 * 设计要点：
 *  - **内存同步、落盘异步**：[record] / [setFavorite] / [remove] / [clear] 立即更新
 *    [records]（UI 零延迟），写文件放到 IO 协程，主线程不阻塞；
 *  - **写串行 + 最新快照**：落盘统一走 [writeMutex]，且快照在持锁后才取，
 *    因此并发写永远落到最后一次变更，不会被旧快照覆盖；
 *  - **收藏优先**：超过 [maxRecords] 只裁剪非收藏记录，[clear] 默认也保留收藏；
 *  - **行式文本格式**（`files/history/operations.tsv`）：纯 Kotlin 编解码、可单测，
 *    不依赖 `org.json`（JVM 单测里它是 Android stub）。
 *
 * 字段顺序：`id \t createdAt \t source \t exitCode \t durationMs \t favorite \t project \t command`
 * 其中 project / command 做 `\` `\t` `\n` `\r` 转义。
 */
class OperationHistoryStore(
    private val file: File,
    private val maxRecords: Int = 500,
) {

    private val lock = Any()
    private val writeMutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _records = MutableStateFlow<List<OperationRecord>>(emptyList())

    /** 全部记录，**最新在前**。UI 用 `collectAsStateWithLifecycle` 订阅。 */
    val records: StateFlow<List<OperationRecord>> = _records.asStateFlow()

    init {
        loadFromDisk()
    }

    /**
     * 追加一条记录（命令执行完成 / 界面操作完成时调用）。
     *
     * @return 写入的记录；[command] 为空白时返回 null（不产生空记录）
     */
    fun record(
        command: String,
        source: OperationSource,
        project: String = "",
        exitCode: Int? = null,
        durationMs: Long? = null,
        createdAt: Long = System.currentTimeMillis(),
    ): OperationRecord? {
        val text = command.trim()
        if (text.isEmpty()) return null
        val record = OperationRecord(
            id = UUID.randomUUID().toString(),
            command = text,
            source = source,
            project = project.trim(),
            exitCode = exitCode,
            durationMs = durationMs,
            createdAt = createdAt,
        )
        synchronized(lock) {
            _records.value = trim(listOf(record) + _records.value)
        }
        schedulePersist()
        return record
    }

    /** 收藏 / 取消收藏（记录不存在时静默忽略）。 */
    fun setFavorite(id: String, favorite: Boolean) {
        synchronized(lock) {
            val list = _records.value
            if (list.none { it.id == id }) return
            _records.value = list.map { if (it.id == id) it.copy(favorite = favorite) else it }
        }
        schedulePersist()
    }

    /** 删除单条记录。 */
    fun remove(id: String) {
        synchronized(lock) {
            val list = _records.value
            val next = list.filterNot { it.id == id }
            if (next.size == list.size) return
            _records.value = next
        }
        schedulePersist()
    }

    /** 清空记录；[keepFavorites] = true（默认）时收藏的记录会留下。 */
    fun clear(keepFavorites: Boolean = true) {
        synchronized(lock) {
            val list = _records.value
            val next = if (keepFavorites) list.filter { it.favorite } else emptyList()
            if (next.size == list.size) return
            _records.value = next
        }
        schedulePersist()
    }

    /**
     * 立即把最新快照写盘并等待完成（单测断言、退出前收尾用）。
     * 与排队中的写任务共用 [writeMutex]，因此返回后文件内容一定是当前内存状态。
     */
    suspend fun flush() {
        writeMutex.withLock { persistNow() }
    }

    // ------------------------------------------------------------------
    // 持久化
    // ------------------------------------------------------------------

    /** 构造时同步读盘：UI 首帧就有历史，也避免「启动瞬间的记录被加载覆盖」。 */
    private fun loadFromDisk() {
        val loaded = runCatching { readAll() }.getOrDefault(emptyList())
        if (loaded.isEmpty()) return
        synchronized(lock) {
            // 正常情况下内存为空（= 磁盘全量）；若已有内存记录，合并并按 id 去重
            val merged = (loaded + _records.value).distinctBy { it.id }
            _records.value = trim(merged)
        }
    }

    private fun schedulePersist() {
        scope.launch { writeMutex.withLock { persistNow() } }
    }

    private fun persistNow() {
        val snapshot = synchronized(lock) { _records.value }
        runCatching {
            val text = if (snapshot.isEmpty()) {
                ""
            } else {
                snapshot.joinToString(separator = "\n", postfix = "\n") { encode(it) }
            }
            file.parentFile?.let { if (!it.exists()) it.mkdirs() }
            // 先写临时文件再改名：进程被杀也不会留下半截文件
            val tmp = File(file.path + ".tmp")
            tmp.writeText(text, Charsets.UTF_8)
            if (!tmp.renameTo(file)) {
                file.writeText(text, Charsets.UTF_8)
                runCatching { tmp.delete() }
            }
        }
    }

    // ------------------------------------------------------------------
    // 编解码
    // ------------------------------------------------------------------

    private fun encode(record: OperationRecord): String = listOf(
        record.id,
        record.createdAt.toString(),
        record.source.name,
        record.exitCode?.toString().orEmpty(),
        record.durationMs?.toString().orEmpty(),
        if (record.favorite) "1" else "0",
        escape(record.project),
        escape(record.command),
    ).joinToString("\t")

    private fun readAll(): List<OperationRecord> {
        if (!file.exists()) return emptyList()
        return file.readLines(Charsets.UTF_8).mapNotNull { decode(it) }
    }

    private fun decode(line: String): OperationRecord? {
        if (line.isBlank()) return null
        val parts = line.split('\t')
        if (parts.size < 8) return null
        val createdAt = parts[1].toLongOrNull() ?: return null
        // 7 之后都属于 command（转义后不该出现裸制表符；出现即视为脏数据，尽量还原）
        val command = unescape(parts.subList(7, parts.size).joinToString("\t"))
        if (command.isBlank()) return null
        return OperationRecord(
            id = parts[0].ifBlank { UUID.randomUUID().toString() },
            command = command,
            source = OperationSource.of(parts[2]),
            project = unescape(parts[6]),
            exitCode = parts[3].toIntOrNull(),
            durationMs = parts[4].toLongOrNull(),
            createdAt = createdAt,
            favorite = parts[5] == "1",
        )
    }

    private fun escape(value: String): String = buildString(value.length) {
        for (ch in value) {
            when (ch) {
                '\\' -> append("\\\\")
                '\t' -> append("\\t")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                else -> append(ch)
            }
        }
    }

    private fun unescape(value: String): String {
        if ('\\' !in value) return value
        val out = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val ch = value[i]
            if (ch != '\\' || i == value.length - 1) {
                out.append(ch)
                i++
                continue
            }
            when (val next = value[i + 1]) {
                't' -> out.append('\t')
                'n' -> out.append('\n')
                'r' -> out.append('\r')
                '\\' -> out.append('\\')
                else -> out.append(ch).append(next)
            }
            i += 2
        }
        return out.toString()
    }

    /**
     * 截断到 [maxRecords]：**收藏永不被挤掉**，只丢弃最早的非收藏记录，
     * 且保持原有时间顺序（最新在前）。
     */
    private fun trim(list: List<OperationRecord>): List<OperationRecord> {
        if (list.size <= maxRecords) return list
        val budget = (maxRecords - list.count { it.favorite }).coerceAtLeast(0)
        val out = ArrayList<OperationRecord>(list.size)
        var keptPlain = 0
        for (record in list) {
            if (record.favorite) {
                out.add(record)
            } else if (keptPlain < budget) {
                out.add(record)
                keptPlain++
            }
        }
        return out
    }
}
