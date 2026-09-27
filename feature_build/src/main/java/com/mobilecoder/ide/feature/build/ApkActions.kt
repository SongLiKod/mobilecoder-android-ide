package com.mobilecoder.ide.feature.build

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

/**
 * APK 产物操作（PRD 2.7「APK 生成、本地安装与分享」）。
 *
 * 所有 Uri 走 app 模块声明的 FileProvider（authority = `${applicationId}.fileprovider`，
 * paths 见 app/src/main/res/xml/file_paths.xml，覆盖 files/ 与 cache/）。
 * 每个方法返回 `null` 表示成功，否则返回中文提示（由 UI 展示，不抛异常）。
 */
object ApkActions {

    private const val MIME_APK = "application/vnd.android.package-archive"

    private fun authority(context: Context): String = "${context.packageName}.fileprovider"

    private fun uriOf(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, authority(context), file)

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

    /** 分享 APK（ACTION_SEND + FileProvider 只读授权）。 */
    fun share(context: Context, file: File): String? {
        if (!file.exists()) return "APK 不存在：${file.absolutePath}"
        return try {
            val uri = uriOf(context, file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = MIME_APK
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(send, "分享 APK")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
            null
        } catch (t: Throwable) {
            "分享失败：${t.message ?: t::class.java.simpleName}"
        }
    }

    /** 复制 APK 绝对路径到剪贴板。 */
    fun copyPath(context: Context, file: File): String? = try {
        val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("APK 路径", file.absolutePath))
        null
    } catch (t: Throwable) {
        "复制失败：${t.message ?: t::class.java.simpleName}"
    }

    /** 打开 APK 所在目录（无文件管理器时降级为复制路径）。 */
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
