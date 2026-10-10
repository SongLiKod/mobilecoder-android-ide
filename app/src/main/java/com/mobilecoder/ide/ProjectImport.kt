package com.mobilecoder.ide

import android.content.Context
import android.net.Uri
import com.mobilecoder.ide.core.storage.AppStorage
import com.mobilecoder.ide.core.storage.ProjectMeta
import com.mobilecoder.ide.core.storage.SafImport
import com.mobilecoder.ide.feature.build.ArchiveExtractor
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 导入已有项目（首页「导入项目」）：
 * - [importArchive]：zip / tar.gz 压缩包解压进 `files/projects/<名>`；
 *   压缩内容只有一个顶层目录（GitHub 源码打包常见 `repo-main/`）时自动拍平；
 * - [importFolder]：SAF 目录树整份复制进 `files/projects/<名>`。
 *
 * 落盘后经 ProjectRepository.adopt 登记（自动识别模板），重名目录自动加序号；
 * 失败时清掉半成品，不在项目根里留垃圾目录。
 */
object ProjectImport {

    private val ARCHIVE_EXTS = listOf(".tar.gz", ".tgz", ".zip")

    /** 导入压缩包项目；成功返回元信息，格式不认识 / 解不开 / 登记不了返回 null。 */
    suspend fun importArchive(context: Context, uri: Uri): ProjectMeta? = withContext(Dispatchers.IO) {
        val dest = uniqueDir(baseName(context, uri))
        val total = runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
        }.getOrDefault(-1L)
        val extracted = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                ArchiveExtractor.extract(input, dest, if (total > 0) total else 0L)
                true
            } ?: false
        }.getOrDefault(false)
        if (!extracted) {
            dest.deleteRecursively()
            return@withContext null
        }
        flattenSingleTopDir(dest)
        AppStorage.projects.adopt(dest) ?: run {
            dest.deleteRecursively()
            null
        }
    }

    /** 导入文件夹项目（SAF 目录树）；空目录 / 复制失败返回 null。 */
    suspend fun importFolder(context: Context, treeUri: Uri): ProjectMeta? = withContext(Dispatchers.IO) {
        val dest = uniqueDir(baseName(context, treeUri))
        if (!dest.mkdirs()) return@withContext null
        val ok = SafImport.copyTreeContents(context, treeUri, dest) &&
            dest.listFiles()?.isNotEmpty() == true
        if (!ok) {
            dest.deleteRecursively()
            return@withContext null
        }
        AppStorage.projects.adopt(dest) ?: run {
            dest.deleteRecursively()
            null
        }
    }

    /** 项目建议名：显示名去压缩包后缀、非法字符换下划线，空则兜底 imported。 */
    private fun baseName(context: Context, uri: Uri): String {
        val raw = SafImport.displayName(context, uri) ?: "imported"
        val noExt = ARCHIVE_EXTS.firstOrNull { raw.lowercase().endsWith(it) }
            ?.let { raw.dropLast(it.length) } ?: raw
        val cleaned = noExt.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_").take(64).trim('.', '_', ' ')
        return cleaned.ifBlank { "imported" }
    }

    /** 项目根下的重名避让：`x` → `x-2` → `x-3`…（与 ProjectRepository.create 同风格）。 */
    private fun uniqueDir(base: String): File {
        val root = AppStorage.projects.projectsRoot
        var candidate = File(root, base)
        var index = 2
        while (candidate.exists()) {
            candidate = File(root, "$base-$index")
            index++
        }
        return candidate
    }

    /** 只有一个顶层目录时拍平：GitHub zip 解出 `repo-main/`，项目根应直接是其内容。 */
    private fun flattenSingleTopDir(dir: File) {
        val kids = dir.listFiles() ?: return
        if (kids.size != 1 || !kids[0].isDirectory) return
        val top = kids[0]
        top.listFiles()?.forEach { child -> child.renameTo(File(dir, child.name)) }
        top.deleteRecursively()
    }
}
