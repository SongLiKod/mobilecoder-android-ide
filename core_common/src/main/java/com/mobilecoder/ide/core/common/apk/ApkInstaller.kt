package com.mobilecoder.ide.core.common.apk

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

/**
 * APK 安装（系统安装器 + 「安装未知应用」授权）。
 *
 * 下沉到 core_common：feature_build（构建产物行）与 feature_editor（文件树长按菜单）
 * 都要发起安装，共享同一 FileProvider 口径（authority = `${applicationId}.fileprovider`，
 * paths 见 app/src/main/res/xml/file_paths.xml，覆盖 files/ 与 cache/ —— 项目文件
 * 与构建产物都在 files/ 下，均可被系统安装器读取）。
 *
 * 每个方法返回 `null` 表示成功，否则返回中文提示（由 UI 展示，不抛异常）。
 */
object ApkInstaller {

    const val MIME_APK = "application/vnd.android.package-archive"

    /** 是否安装包（决定产物行 / 文件树菜单是否出现「安装」入口）。 */
    fun isApk(file: File): Boolean = file.extension.equals("apk", ignoreCase = true)

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
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
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

    /** 是否已具备安装未知来源应用的权限（用于入口提示）。 */
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
