package com.mobilecoder.ide.core.common.apk

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 文件树菜单 / 产物行的「安装」入口判定（[ApkInstaller.isApk]）。
 *
 * 只有 `.apk` 才应出现「安装」：扩展名判定必须忽略大小写，且不能把
 * 其它产物（`.aab` / `.jar` / 无扩展名 / `.apk.txt`）误判成安装包；
 * 目录与否由调用方（文件树行 `isDirectory`）过滤。
 */
class ApkInstallerTest {

    @Test
    fun `apk 扩展名忽略大小写——命中安装入口`() {
        assertTrue(ApkInstaller.isApk(File("/sdcard/app-release.apk")))
        assertTrue(ApkInstaller.isApk(File("/sdcard/app-release.APK")))
        assertTrue(ApkInstaller.isApk(File("/sdcard/深拷贝.Apk")))
    }

    @Test
    fun `非 apk 文件——不显示安装入口`() {
        assertFalse(ApkInstaller.isApk(File("/sdcard/app-release.aab")))
        assertFalse(ApkInstaller.isApk(File("/sdcard/lib.jar")))
        assertFalse(ApkInstaller.isApk(File("/sdcard/README")))
        assertFalse(ApkInstaller.isApk(File("/sdcard/app-release.apk.txt")))
    }
}
