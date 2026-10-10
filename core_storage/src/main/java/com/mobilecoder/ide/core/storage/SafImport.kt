package com.mobilecoder.ide.core.storage

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import java.io.File

/**
 * SAF 导入：把系统选择器（OpenDocument / OpenDocumentTree）选中的文件 / 目录
 * 复制进 App 私有目录（项目目录、编辑器文件树等）。
 *
 * - 条目名一律取路径末段，防提供方在 DISPLAY_NAME 里塞斜杠造成路径穿越；
 * - 目录树递归有深度上限，兜住异常 / 自嵌套时的失控复制；
 * - 任一文件写入失败即整体失败（返回 false），由调用方清理半成品目录。
 */
object SafImport {

    /** 递归上限：远高于真实工程深度。 */
    private const val MAX_DEPTH = 64

    /** 选择器返回的文件 / 目录显示名（查询失败退回 URI 末段 / documentId 猜测）。 */
    fun displayName(context: Context, uri: Uri): String? {
        val fromQuery = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }.getOrNull()
        return safeName(fromQuery)
            ?: safeName(uri.lastPathSegment?.substringAfterLast('/'))
            ?: safeName(runCatching {
                DocumentsContract.getTreeDocumentId(uri).substringAfterLast(':').substringAfterLast('/')
            }.getOrNull())
    }

    /**
     * 复制 [uri] 指向的单个文档到 [destDir]（同名文件覆盖），返回落盘文件名。
     * 源不可读、目标是同名目录时返回 null。
     */
    fun copyDocumentTo(context: Context, uri: Uri, destDir: File): String? {
        val name = displayName(context, uri) ?: return null
        val dest = File(destDir, name)
        if (dest.isDirectory) return null
        val ok = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { input.copyTo(it) }
            } != null
        }.getOrDefault(false)
        return if (ok) name else null
    }

    /**
     * 把 [treeUri] 选中的目录连同内容复制为 [destDir] 的子目录（重名自动加序号），
     * 返回落盘子目录名；失败返回 null（半成品已清掉）。
     */
    fun copyTreeTo(context: Context, treeUri: Uri, destDir: File): String? {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return null
        if (!root.isDirectory) return null
        val name = displayName(context, treeUri) ?: root.name?.let { safeName(it) } ?: return null
        val child = uniqueChild(destDir, name)
        if (!child.mkdirs()) return null
        return if (copyChildren(context, root, child, 0)) child.name
        else { child.deleteRecursively(); null }
    }

    /** 把 [treeUri] 选中目录的**内容**（不含目录本身）复制进 [destDir]；全部成功才 true。 */
    fun copyTreeContents(context: Context, treeUri: Uri, destDir: File): Boolean {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return false
        if (!root.isDirectory || !destDir.isDirectory) return false
        return copyChildren(context, root, destDir, 0)
    }

    /** 递归复制 [src] 目录的子项到本地 [dest]（同名文件覆盖）；任一失败即 false。 */
    private fun copyChildren(context: Context, src: DocumentFile, dest: File, depth: Int): Boolean {
        if (depth > MAX_DEPTH) return false
        var ok = true
        for (child in src.listFiles()) {
            val name = child.name?.let { safeName(it) } ?: continue
            if (child.isDirectory) {
                val sub = File(dest, name)
                if (sub.isFile && !sub.delete()) { ok = false; continue }
                if (!sub.isDirectory && !sub.mkdirs()) { ok = false; continue }
                if (!copyChildren(context, child, sub, depth + 1)) ok = false
            } else {
                val written = runCatching {
                    context.contentResolver.openInputStream(child.uri)?.use { input ->
                        File(dest, name).outputStream().use { input.copyTo(it) }
                    } != null
                }.getOrDefault(false)
                if (!written) ok = false
            }
        }
        return ok
    }

    /** 目录下的重名避让：`x` → `x-2` → `x-3`…（与 ProjectRepository.create 同风格）。 */
    private fun uniqueChild(dir: File, name: String): File {
        var candidate = File(dir, name)
        var index = 2
        while (candidate.exists()) {
            candidate = File(dir, "$name-$index")
            index++
        }
        return candidate
    }

    /** 名称只留路径末段并剔除非法值；空 / 点号名一律拒绝。 */
    private fun safeName(raw: String?): String? = raw
        ?.substringAfterLast('/')
        ?.substringAfterLast('\\')
        ?.trim()
        ?.takeIf { it.isNotEmpty() && it != "." && it != ".." }
}
