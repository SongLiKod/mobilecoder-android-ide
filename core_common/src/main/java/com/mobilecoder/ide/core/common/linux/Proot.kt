package com.mobilecoder.ide.core.common.linux

import android.content.Context
import java.io.File

/**
 * proot 执行通道：目录约定、就绪判定、argv 组装、子进程环境变量。
 *
 * **架构**：Ubuntu 24.04 rootfs（`files/linux/rootfs`）+ Termux proot 三件套
 * （`files/linux/bin/proot`、`bin/loader`、`lib` 下的运行库）。终端、构建、npm、sdkmanager
 * 所有子进程执行点统一包一层 proot，在 guest 里跑真正的 Linux 程序。
 *
 * **为什么可行**（proot 上游 `src/execve/enter.c` 源码实证）：proot 拦截 execve 后
 * 自己解析 ELF 的 `PT_INTERP`，把解释器路径**翻译成 guest 路径**后换成自带的
 * loader 执行（`translate_execve_enter` → `set_sysarg_path(tracee, loader_path, SYSARG_1)`）。
 * 因此 Android 缺 `/lib/ld-linux-aarch64.so.1` 的问题在 guest 内不存在——解释器与
 * libc 全部来自 rootfs。Termux 的 proot 编译期定义了 `PROOT_UNBUNDLE_LOADER`，
 * 启动必须设置 `PROOT_LOADER` 指向解出的 loader（否则回退到编译期 Termux 前缀路径，
 * 本 App 里不存在）。
 *
 * **同路径 bind**（`-b <path>`，host 与 guest 同名）是本模块的核心约定：`files`、
 * `cache`、`/dev`、`/proc`、项目目录在 guest 内**原样可见**，于是 `HOME` / `PATH` /
 * `JAVA_HOME` / 各类构建环境变量无需任何路径翻译，Kotlin 侧拼好的绝对路径直接可用。
 *
 * 未安装（[isReady] == false）时所有包装函数**原样返回**，调用方按 bionic
 * （`/system/bin/sh`）路径回退。
 */
object Proot {

    /**
     * rootfs 版本标识（marker 文件内容前缀）。升级 rootfs 时改这里，
     * 旧 marker 不匹配会触发整体重装（见 `RootfsManager`）。
     */
    const val ROOTFS_ID = "ubuntu-24.04.5"

    // ------------------------------------------------------------------
    // 目录约定（全部在 `files/linux` 下，安装侧与执行侧共用）
    // ------------------------------------------------------------------

    /** `files/linux` —— proot 运行时根目录。 */
    fun linuxDir(context: Context): File = File(context.filesDir, "linux")

    /** `files/linux/rootfs` —— Ubuntu 24.04 根文件系统（`proot -r` 目标）。 */
    fun rootfsDir(context: Context): File = File(linuxDir(context), "rootfs")

    /** `files/linux/bin/proot` —— proot 可执行文件（Termux deb 解出）。 */
    fun prootBin(context: Context): File = File(linuxDir(context), "bin/proot")

    /** `files/linux/bin/loader` —— proot 自带 loader（64 位），`PROOT_LOADER` 指向它。 */
    fun prootLoader(context: Context): File = File(linuxDir(context), "bin/loader")

    /** `files/linux/bin/loader32` —— 32 位 guest 程序用，`PROOT_LOADER_32` 指向它。 */
    fun prootLoader32(context: Context): File = File(linuxDir(context), "bin/loader32")

    /** `files/linux/lib` —— proot 自身依赖（libtalloc / libandroid-shmem），`LD_LIBRARY_PATH` 指向它。 */
    fun prootLibDir(context: Context): File = File(linuxDir(context), "lib")

    /** `files/linux/.rootfs` —— 安装完成标记（内容 = [ROOTFS_ID] + 架构）。 */
    fun markerFile(context: Context): File = File(linuxDir(context), ".rootfs")

    // ------------------------------------------------------------------
    // 就绪判定
    // ------------------------------------------------------------------

    /**
     * Linux 环境是否就绪：marker 版本匹配 + rootfs 关键文件 + proot/loader 齐全。
     * `rootfs/bin/bash` 经 `bin → usr/bin` 软链解析（`File.exists` 跟随符号链接）。
     */
    fun isReady(context: Context): Boolean = isReady(linuxDir(context))

    /** [isReady] 的纯文件版（JVM 单测无需 Android Context）。 */
    fun isReady(linuxDir: File): Boolean {
        val marker = File(linuxDir, ".rootfs")
        val head = runCatching { marker.takeIf { it.isFile }?.readText() }.getOrNull()
        if (head == null || !head.trim().startsWith(ROOTFS_ID)) return false
        return File(linuxDir, "rootfs/bin/bash").exists() &&
            File(linuxDir, "bin/proot").isFile &&
            File(linuxDir, "bin/loader").isFile
    }

    // ------------------------------------------------------------------
    // 子进程环境变量
    // ------------------------------------------------------------------

    /**
     * proot 子进程需要的环境变量（未就绪返回空 Map）。
     *
     * - `LD_LIBRARY_PATH`：proot 是 bionic ELF，缺它找不到 `libtalloc.so.2`
     *   （其 DT_RUNPATH 指向 Termux 前缀，在本 App 内不存在，必须由这里补）；
     * - `PROOT_LOADER` / `PROOT_LOADER_32`：loader 实际路径（见类文档）；
     * - `PROOT_TMP_DIR`：proot 打开临时文件用（Android 无 `/tmp`）。
     *
     * 这几个变量会随 env 透传进 guest，但只含 proot 专用 soname，对 guest 内
     * glibc 程序无副作用（ld.so 只在需要对应 soname 时才去这些目录找）。
     */
    fun envMap(context: Context): Map<String, String> {
        if (!isReady(context)) return emptyMap()
        val linux = linuxDir(context)
        return mapOf(
            "LD_LIBRARY_PATH" to File(linux, "lib").absolutePath,
            "PROOT_LOADER" to File(linux, "bin/loader").absolutePath,
            "PROOT_LOADER_32" to File(linux, "bin/loader32").absolutePath,
            "PROOT_TMP_DIR" to File(context.cacheDir, "tmp").absolutePath,
        )
    }

    /** [envMap] 的 `"KEY=VALUE"` 数组形态（CliNative envArr 用）。 */
    fun envEntries(context: Context): Array<String> =
        envMap(context).map { (k, v) -> "$k=$v" }.toTypedArray()

    /**
     * 构造子进程 `PATH`：`filesBin`（node / npm 入口）优先，其后是 **guest 标准路径**
     * （rootfs 的 `/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin`），
     * 最后才是 bionic 的 `/system/bin` 等。
     *
     * rootfs 未安装时 guest 目录不存在，PATH 搜索逐段跳过 → 仍回落 `/system/bin`，
     * bionic 直跑路径不受影响；装好后 `chmod` / `ls` / `apt` 等 Linux 工具自然排在
     * toybox 前面，proot 内的命令形态与桌面 Linux 一致。
     */
    fun pathFor(filesBin: String): String =
        "$filesBin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin" +
            ":/system/bin:/system/xbin:/vendor/bin"

    // ------------------------------------------------------------------
    // bind 与 argv 组装
    // ------------------------------------------------------------------

    /**
     * 需要同路径 bind 进 guest 的固定系统目录（存在才 bind，Android 版本间差异靠
     * `exists()` 自然过滤；`/apex` / `/linkerconfig` 是 bionic linker 在 guest 内
     * 跑 `/system/bin` 下的程序（脚本 shebang、npm script-shell）时的依赖）。
     */
    private val HOST_BINDS = listOf(
        "/dev", "/proc", "/sys", "/system", "/vendor", "/apex", "/linkerconfig",
        "/storage", "/sdcard",
    )

    /**
     * 同路径 bind 列表：固定系统目录 + app 数据目录（files / cache / 项目常在其下）
     * + 当前工作目录。去重；**绝不 bind `/`**（会把 host 根盖到 guest 根上）。
     *
     * @param dataDir app 数据目录（`context.dataDir`，涵盖 filesDir 与 cacheDir）
     * @param cwd     当前工作目录（不存在或为 `/` 时忽略）
     */
    fun binds(dataDir: File, cwd: String?): List<String> {
        val out = LinkedHashSet<String>()
        HOST_BINDS.forEach { if (File(it).isDirectory) out += it }
        out += dataDir.absolutePath
        cwd?.trim()?.takeIf { it.isNotBlank() && it != "/" && File(it).isDirectory }
            ?.let { out += it }
        return out.filter { it.isNotBlank() && it != "/" }
    }

    /** proot 运行参数（不含被包裹的命令）。 */
    data class Config(
        val proot: String,
        val rootfs: String,
        val workdir: String,
        val binds: List<String>,
    )

    /**
     * 组装 proot 命令行前缀：
     *
     * - `--root-id`：伪装成 uid 0——`apt` / `dpkg` 写 `/var/lib/dpkg` 的前提
     *   （单 uid 的 app 私有目录，伪装 root 拿到的还是自己的文件）；
     * - `--link2symlink`：硬链接落盘转成软链，dpkg 的文件硬链接在 app 目录可工作；
     * - `--kill-on-exit`：终端会话被杀时连带回收 guest 内进程，不留孤儿；
     * - `-w`：guest 内工作目录（与 host 同路径，见 [binds]）。
     */
    fun prootArgs(config: Config): Array<String> = buildList {
        add(config.proot)
        add("-r")
        add(config.rootfs)
        add("--root-id")
        add("--link2symlink")
        add("--kill-on-exit")
        add("-w")
        add(config.workdir)
        config.binds.forEach { b ->
            add("-b")
            add(b)
        }
        add("--")
    }.toTypedArray()

    /** 当前上下文的 [Config]（[cwd] 不存在时回退 guest 根 `/`）。 */
    fun configFor(context: Context, cwd: String?): Config {
        val dir = cwd?.trim()?.takeIf { File(it).isDirectory }
        return Config(
            proot = prootBin(context).absolutePath,
            rootfs = rootfsDir(context).absolutePath,
            workdir = dir ?: "/",
            binds = binds(context.dataDir, dir),
        )
    }

    /**
     * 把 [argv] 包进 proot 执行；**[argv][0] 必须是 guest 内的绝对路径**
     * （如 `/bin/sh`、`/bin/bash`，或经 bind 可见的 `files/...` 绝对路径）。
     *
     * 环境未就绪时原样返回——调用方负责在此之前保证就绪或提供 bionic 回退。
     */
    fun wrap(context: Context, argv: Array<String>, cwd: String?): Array<String> {
        if (argv.isEmpty() || !isReady(context)) return argv
        return prootArgs(configFor(context, cwd)) + argv
    }

    /**
     * 终端 shell 的启动 argv：就绪 → `proot … /bin/bash --login`；
     * 未就绪 → null（调用方回退 bionic `/system/bin/sh`，保证装系统前终端可用）。
     */
    fun terminalArgv(context: Context, cwd: String?): Array<String>? {
        if (!isReady(context)) return null
        return prootArgs(configFor(context, cwd)) + arrayOf("/bin/bash", "--login")
    }
}
