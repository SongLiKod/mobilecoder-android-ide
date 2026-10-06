package com.mobilecoder.ide.feature.ai

import com.mobilecoder.ide.core.storage.AppStorage
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * AI 写操作快照（P0 写保护）：每轮工具执行前备份受影响文件，支持整轮撤销。
 *
 * 存放于应用缓存目录（不污染项目树）：
 * `cache/ai-snapshots/<sessionId>/<id>/manifest.json` + `b/<i>` 备份体。
 *
 * 备份上限（防 node_modules 等巨量目录拖垮缓存）：单文件 5MB、单轮合计 10MB、
 * 单轮 2000 个文件；超限的条目记为 `oversize`（撤销时无法恢复，会在结果里点名）。
 */
object AiSnapshotStore {

    private const val MAX_FILE_BYTES = 5L * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 10L * 1024 * 1024
    private const val MAX_FILES = 2000
    private const val MAX_KEEP_PER_SESSION = 40

    /** 撤销清单里的一项。 */
    data class Entry(
        /** 相对项目根的路径（`/` 分隔）。 */
        val path: String,
        /** true = 当时存在（备份于 `b/<i>`，撤销=写回）；false = 当时不存在（撤销=删除）。 */
        val existed: Boolean,
        val isDir: Boolean,
        /** 备份文件名（相对快照目录）；null = 超限未备份（撤销时不可恢复）。 */
        val bak: String?,
    )

    data class Manifest(
        val id: String,
        val entries: List<Entry>,
        /** 全部条目均已备份（false = 有 oversize，撤销会不完整）。 */
        val complete: Boolean,
    )

    data class UndoResult(
        val restored: Int,
        val deleted: Int,
        /** 未能恢复的路径（超限未备份 / 写回失败）。 */
        val unrecovered: List<String>,
    )

    /**
     * 备份一组将被修改/删除的路径，返回清单；无可备份内容（全不存在且非创建类）返回 null。
     *
     * @param paths 相对项目根的路径（写入/替换/删除的目标）
     */
    suspend fun capture(projectRoot: File, sessionId: String, paths: List<String>): Manifest? =
        withContext(Dispatchers.IO) {
            runCatching {
                val root = runCatching { projectRoot.canonicalFile }.getOrNull() ?: projectRoot
                val snapshotId = "$sessionId/${System.currentTimeMillis().toString(36)}"
                val snapshotDir = File(sessionsRoot(), snapshotId)
                val bakDir = File(snapshotDir, "b")
                val entries = ArrayList<Entry>()
                var totalBytes = 0L
                var bakIndex = 0
                var oversize = false

                fun backup(file: File, rel: String): Boolean {
                    val size = file.length()
                    if (size > MAX_FILE_BYTES || totalBytes + size > MAX_TOTAL_BYTES ||
                        entries.size >= MAX_FILES
                    ) {
                        oversize = true
                        entries += Entry(rel, existed = true, isDir = false, bak = null)
                        return false
                    }
                    val bakName = "$bakIndex.bak"
                    bakIndex++
                    if (!bakDir.exists() && !bakDir.mkdirs()) return false
                    val target = File(bakDir, bakName)
                    val copied = runCatching { file.copyTo(target, overwrite = true) }.isSuccess
                    if (!copied) {
                        oversize = true
                        entries += Entry(rel, existed = true, isDir = false, bak = null)
                        return false
                    }
                    totalBytes += size
                    entries += Entry(rel, existed = true, isDir = false, bak = bakName)
                    return true
                }

                for (raw in paths.distinct()) {
                    val rel = normalizeRel(raw) ?: continue
                    val file = resolve(root, rel) ?: continue
                    when {
                        file.isFile -> backup(file, rel)

                        file.isDirectory -> {
                            // 目录删除：逐文件备份（超限条目单独记录，撤销时点名）
                            val all = ArrayList<File>()
                            runCatching { file.walkTopDown().filter { it.isFile }.forEach { all += it } }
                            for (f in all) {
                                val childRel = rel + "/" + relOf(root, f)
                                backup(f, childRel)
                            }
                        }

                        else -> {
                            // 当时不存在 → 这是一次“创建”，撤销时删除（先补记缺失的父目录）
                            var parent = file.parentFile
                            val missingDirs = ArrayList<String>()
                            while (parent != null && parent != root && !parent.exists()) {
                                missingDirs += relOf(root, parent)
                                parent = parent.parentFile
                            }
                            missingDirs.forEach { dirRel ->
                                if (entries.none { it.path == dirRel }) {
                                    entries += Entry(dirRel, existed = false, isDir = true, bak = null)
                                }
                            }
                            entries += Entry(rel, existed = false, isDir = false, bak = null)
                        }
                    }
                }

                if (entries.isEmpty()) return@withContext null
                snapshotDir.mkdirs()
                val json = JSONObject().put(
                    "entries",
                    JSONArray().also { arr ->
                        entries.forEach { e ->
                            arr.put(
                                JSONObject()
                                    .put("path", e.path)
                                    .put("existed", e.existed)
                                    .put("isDir", e.isDir)
                                    .put("optBak", e.bak ?: ""),
                            )
                        }
                    },
                )
                File(snapshotDir, "manifest.json").writeText(json.toString(), Charsets.UTF_8)
                pruneOldSessions(sessionId)
                Manifest(snapshotId, entries, complete = !oversize)
            }.getOrNull()
        }

    /** 读取清单（撤销前用）。 */
    suspend fun read(snapshotId: String): Manifest? = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(sessionsRoot(), snapshotId)
            val manifest = File(dir, "manifest.json")
            if (!manifest.isFile) return@runCatching null
            val arr = JSONObject(manifest.readText(Charsets.UTF_8)).optJSONArray("entries")
                ?: return@runCatching null
            val entries = (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val bak = o.optString("optBak")
                Entry(
                    path = o.optString("path"),
                    existed = o.optBoolean("existed"),
                    isDir = o.optBoolean("isDir"),
                    bak = bak.ifBlank { null },
                )
            }
            Manifest(snapshotId, entries, complete = entries.none { it.existed && it.bak == null })
        }.getOrNull()
    }

    /** 读取某条备份的文本内容（Diff 预览用）。 */
    suspend fun readBackupText(snapshotId: String, bak: String): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                File(File(File(sessionsRoot(), snapshotId), "b"), bak).readText(Charsets.UTF_8)
            }.getOrNull()
        }

    /** 整轮撤销：写回备份、删除本轮创建的路径。 */
    suspend fun undo(projectRoot: File, snapshotId: String): UndoResult? =
        withContext(Dispatchers.IO) {
            val manifest = read(snapshotId) ?: return@withContext null
            val root = runCatching { projectRoot.canonicalFile }.getOrNull() ?: projectRoot
            val dir = File(sessionsRoot(), snapshotId)
            var restored = 0
            var deleted = 0
            val unrecovered = ArrayList<String>()

            // 1) 写回：当时存在的文件
            for (e in manifest.entries) {
                if (!e.existed || e.isDir) continue
                val target = resolve(root, e.path)
                if (target == null) {
                    unrecovered += e.path
                    continue
                }
                val bakFile = e.bak?.let { File(File(dir, "b"), it) }
                if (bakFile == null || !bakFile.isFile) {
                    if (target.exists()) continue // 未备份且文件还在 → 无需动作
                    unrecovered += e.path
                    continue
                }
                val ok = runCatching {
                    target.parentFile?.let { if (!it.exists()) it.mkdirs() }
                    bakFile.copyTo(target, overwrite = true)
                }.isSuccess
                if (ok) restored++ else unrecovered += e.path
            }

            // 2) 删除：本轮创建的路径（文件先删、目录按深度倒序删）
            val created = manifest.entries.filter { !it.existed }.sortedByDescending { it.path.length }
            for (e in created) {
                val target = resolve(root, e.path) ?: continue
                if (!target.exists()) continue
                val ok = runCatching {
                    if (target.isDirectory) target.deleteRecursively() else target.delete()
                }.getOrDefault(false)
                if (ok) deleted++ else unrecovered += e.path
            }

            runCatching { dir.deleteRecursively() }
            UndoResult(restored, deleted, unrecovered)
        }

    // ------------------------------------------------------------------

    private fun sessionsRoot(): File = File(AppStorage.context.cacheDir, "ai-snapshots")

    private fun normalizeRel(raw: String): String? {
        val clean = raw.trim().removePrefix("./").removePrefix("/")
        if (clean.isBlank() || clean == "." || clean.startsWith("..")) return null
        return clean.replace('\\', '/').trimEnd('/')
    }

    private fun resolve(root: File, rel: String): File? {
        val target = File(root, rel)
        val canonical = runCatching { target.canonicalFile }.getOrNull() ?: return null
        val canonicalRoot = runCatching { root.canonicalFile }.getOrNull() ?: root
        if (canonical != canonicalRoot &&
            !canonical.path.startsWith(canonicalRoot.path + File.separator)
        ) {
            return null
        }
        return canonical
    }

    private fun relOf(root: File, target: File): String = runCatching {
        target.relativeTo(root).path.replace(File.separatorChar, '/')
    }.getOrDefault(target.name)

    /** 只保留会话最近 [MAX_KEEP_PER_SESSION] 份快照，避免缓存无限增长。 */
    private fun pruneOldSessions(sessionId: String) {
        runCatching {
            val sessionDir = File(sessionsRoot(), sessionId)
            val dirs = sessionDir.listFiles()?.filter { it.isDirectory } ?: return
            if (dirs.size <= MAX_KEEP_PER_SESSION) return
            dirs.sortedBy { it.lastModified() }
                .take(dirs.size - MAX_KEEP_PER_SESSION)
                .forEach { runCatching { it.deleteRecursively() } }
        }
    }
}
