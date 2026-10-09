package com.mobilecoder.ide.feature.build

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File

/**
 * 构建产物操作（PRD 2.7「产物本地安装与分享」+ 通用 下载 / 导出）。
 *
 * 产物不再限定 APK：JAR、dist / 源码压缩包等一律可 **分享 / 下载 / 复制路径**；
 * **安装** 仅对 `.apk` 显示（系统安装器 + 「安装未知应用」授权）。
 *
 * 所有 Uri 走 app 模块声明的 FileProvider（authority = `${applicationId}.fileprovider`，
 * paths 见 app/src/main/res/xml/file_paths.xml，覆盖 files/ 与 cache/）。
 * 每个方法返回 `null` 表示成功，否则返回中文提示（由 UI 展示，不抛异常）。
 */
object ApkActions {

    private const val MIME_APK = "application/vnd.android.package-archive"

    /** 下载落位目录：`Download/MobileCoder/`（minSdk 29 MediaStore 免权限直写）。 */
    private const val DOWNLOAD_SUBDIR = "Download/MobileCoder"

    private fun authority(context: Context): String = "${context.packageName}.fileprovider"

    private fun uriOf(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, authority(context), file)

    /** 是否安装包（决定产物行是否显示「安装」按钮）。 */
    fun isApk(file: File): Boolean = file.extension.equals("apk", ignoreCase = true)

    /** 按扩展名取 MIME（未知回退二进制）。 */
    fun mimeOf(file: File): String {
        val ext = file.extension.lowercase()
        if (ext == "apk") return MIME_APK
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: when (ext) {
                "zip" -> "application/zip"
                "jar" -> "application/java-archive"
                "gz" -> "application/gzip"
                "html", "htm" -> "text/html"
                else -> "application/octet-stream"
            }
    }

    /**
     * 安装 APK。
     *
     * 未授予「安装未知应用」权限时，先跳转 `ACTION_MANAGE_UNKNOWN_APP_SOURCES` 授权页。
     */
    fun install(context: Context, file: File): String? {
        if (!file.exists()) return "APK 不存在：${file.absolutePath}"
        return try {
            val allowed = runCatching {
                context.packageManager.canRequestPackageInstalls()
            }.getOrDefault(false)
            if (!allowed) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return "已打开「安装未知应用」授权页，允许后返回重试安装 ${file.name}"
            }
            val uri = uriOf(context, file)
            val view = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, MIME_APK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(view)
            null
        } catch (t: Throwable) {
            "无法安装：${t.message ?: t::class.java.simpleName}"
        }
    }

    /** 分享产物（ACTION_SEND + FileProvider 只读授权，MIME 按扩展名）。 */
    fun share(context: Context, file: File): String? {
        if (!file.exists()) return "文件不存在：${file.absolutePath}"
        return try {
            val uri = uriOf(context, file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = mimeOf(file)
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(send, "分享 ${file.name}")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
            null
        } catch (t: Throwable) {
            "分享失败：${t.message ?: t::class.java.simpleName}"
        }
    }

    /**
     * 下载产物到系统「下载」目录（`Download/MobileCoder/`）。
     *
     * minSdk 29：MediaStore.Downloads 直写免存储权限、无需文件选择器 —— 一键完成，
     * 符合无人值守交互；插入时 `IS_PENDING=1` 写完再置 0，避免半成品被别的应用看到。
     * 同名文件由系统自动改名（`x (1).apk`），不会覆盖用户已有下载。
     */
    fun download(context: Context, file: File): String? {
        if (!file.exists()) return "文件不存在：${file.absolutePath}"
        return try {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeOf(file))
                put(MediaStore.MediaColumns.RELATIVE_PATH, "$DOWNLOAD_SUBDIR/")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return "下载失败：系统拒绝创建下载记录"
            val ok = runCatching {
                resolver.openOutputStream(uri)?.use { out ->
                    file.inputStream().use { input -> input.copyTo(out) }
                } ?: error("无法打开输出流")
            }.isSuccess
            if (!ok) {
                runCatching { resolver.delete(uri, null, null) }
                return "下载失败：写入中断"
            }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            runCatching { resolver.update(uri, values, null, null) }
            null
        } catch (t: Throwable) {
            "下载失败：${t.message ?: t::class.java.simpleName}"
        }
    }

    /** 复制产物绝对路径到剪贴板。 */
    fun copyPath(context: Context, file: File): String? = try {
        val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("产物路径", file.absolutePath))
        null
    } catch (t: Throwable) {
        "复制失败：${t.message ?: t::class.java.simpleName}"
    }

    /** 打开产物所在目录（无文件管理器时降级为复制路径）。 */
    fun openDirectory(context: Context, dir: File): String? {
        if (!dir.exists()) return "目录不存在：${dir.absolutePath}"
        return try {
            val uri = uriOf(context, dir)
            val view = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "resource/folder")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(view)
            null
        } catch (t: Throwable) {
            copyPath(context, dir)
            "未找到可打开目录的应用，路径已复制：${dir.absolutePath}"
        }
    }

    /** 是否已具备安装未知来源应用的权限（用于产物行的提示徽标）。 */
    fun canInstall(context: Context): Boolean = runCatching {
        context.packageManager.canRequestPackageInstalls()
    }.getOrDefault(false) ||
        runCatching {
            context.packageManager.checkPermission(
                "android.permission.REQUEST_INSTALL_PACKAGES",
                context.packageName,
            ) == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
}
