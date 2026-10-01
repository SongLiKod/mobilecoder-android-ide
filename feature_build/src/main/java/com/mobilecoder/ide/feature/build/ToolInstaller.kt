package com.mobilecoder.ide.feature.build

import android.content.Context
import com.mobilecoder.ide.core.common.linux.Proot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Linux 环境（Ubuntu rootfs + proot）安装辅助。
 *
 * node / npm / java 全是 glibc ELF，Android 内核 exec 时找不到
 * `/lib/ld-linux-aarch64.so.1` → ENOENT(2)、退出码 127。[ensureLinux] 在线下载
 * rootfs + proot 补上命令通道（见 `RootfsManager` / `EnvDownloader`），后续构建命令
 * 统一包进 proot 执行。
 *
 * 历史：本类曾承载 `apt tools …`（npm 软件安装）整套命令；apt CLI 已整体移除，
 * 该入口随之删除 —— 终端里仍可直接使用 npm（Node.js 由「构建环境」页安装）。
 */
object ToolInstaller {

    /**
     * 确保 node 能被 exec：安装 **Linux 环境**（Ubuntu rootfs + proot）。
     * node / npm / java 全是 glibc ELF，没有 proot 时内核 exec 阶段就
     * ENOENT(2)、退出码 127；装好后命令统一包进 proot，由 guest rootfs 提供
     * `/lib/ld-linux-aarch64.so.1`。
     *
     * @return true = 可以继续探测 node；false = 安装失败（错误信息已 emit）
     */
    private suspend fun ensureLinux(
        context: Context,
        source: EnvSource,
        emit: (String) -> Unit,
        onStage: (String) -> Unit,
    ): Boolean {
        if (Proot.isReady(context)) return true
        emit("Linux 环境未就绪 → 安装（Ubuntu rootfs + proot；glibc 程序必须在其中运行）…")
        val failure = runCatching {
            EnvDownloader.install(context, EnvKind.LINUX, source, onStage = { onStage(it) })
        }.exceptionOrNull()
        if (failure is DownloadCancelled) throw failure
        if (failure != null) {
            emit("[ERROR] Linux 环境安装失败：${failure.message}")
            emit("       rootfs 源：Ubuntu 官方 cdimage / 清华 TUNA（ubuntu-base 约 30MB）；")
            emit("       proot 三件套：Termux 官方仓库（固定地址，约 140KB）。")
            emit("       全部失败通常是网络不可达，可切换官方源/国内镜像重试。")
            emit("       自救：「构建环境」→ Linux 环境 → 导入本地 ubuntu-base-*.tar.gz（官方 cdimage 可下载）。")
            return false
        }
        emit("Linux 环境已安装：${Proot.rootfsDir(context).absolutePath}")
        return true
    }

    /**
     * 构建前置（BuildRunner 用）：确保 Linux 环境就绪 —— JDK（Temurin）/ Gradle /
     * node 全是 glibc ELF，没有 proot 时 `java` 同样会在 exec 阶段 127。
     *
     * 与「构建环境」页在线安装是同一条路径（[EnvDownloader.install] → [RootfsManager]），
     * 不依赖 node 存在。
     *
     * @return true = 已就绪（或安装成功）
     */
    suspend fun ensureLinux(
        context: Context,
        source: EnvSource,
        emit: (String) -> Unit,
    ): Boolean = withContext(Dispatchers.IO) {
        if (Proot.isReady(context)) return@withContext true
        ensureLinux(context, source, emit, onStage = { emit(it) })
    }
}
