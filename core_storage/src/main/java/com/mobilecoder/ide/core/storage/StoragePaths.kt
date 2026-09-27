package com.mobilecoder.ide.core.storage

import java.io.File

/**
 * 存储结构（TECH.md 5. 存储结构设计）：
 * ```
 * /data/data/com.mobilecoder.ide/
 * ├── files/bin/         # CLI、Git、SSH 二进制工具
 * ├── files/projects/    # 用户所有项目源码
 * ├── files/ssh_keys/    # 加密密钥存储
 * ├── files/sdk/         # 编译环境
 * ├── cache/tmp/         # 编译缓存
 * └── datastore/         # 主题、全局配置
 * ```
 */
data class StoragePaths(
    val files: File,
    val projects: File,
    val bin: File,
    val sshKeys: File,
    val sdk: File,
    val tmp: File,
) {
    /** 首次启动创建全部目录（幂等）。 */
    fun ensure() {
        listOf(files, projects, bin, sshKeys, sdk, tmp).forEach { dir ->
            if (!dir.exists()) dir.mkdirs()
        }
    }

    companion object {
        fun from(filesDir: File, cacheDir: File): StoragePaths = StoragePaths(
            files = filesDir,
            projects = File(filesDir, "projects"),
            bin = File(filesDir, "bin"),
            sshKeys = File(filesDir, "ssh_keys"),
            sdk = File(filesDir, "sdk"),
            tmp = File(cacheDir, "tmp"),
        )
    }
}
