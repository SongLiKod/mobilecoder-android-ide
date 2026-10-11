package com.mobilecoder.ide.feature.build

import android.content.Context
import com.mobilecoder.ide.core.nativebridge.CliCallback
import com.mobilecoder.ide.core.nativebridge.CliNative
import com.mobilecoder.ide.core.common.linux.Proot
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.zip.ZipFile

/**
 * ARM 手机跑 AGP 构建的适配层（真机验证 2026-10）：
 *
 *  Ubuntu rootfs 是 arm64，而 AGP 的 aapt2（maven classifier `linux`）与
 *  NDK 工具链都是 x86_64 ELF。Google 从未发布 aapt2 的 linux-arm64 构建，
 *  唯一可行路径是 **qemu-user 指令模拟**：
 *  1. rootfs 内 `apt install qemu-user-static libc6:amd64`（amd64 multiarch 提供
 *     `/lib64/ld-linux-x86-64.so.2` 与 x86_64 glibc）；
 *  2. 从 maven 镜像拉 `aapt2-<ver>-linux.jar`，抽出其中的 x86_64 `aapt2` 二进制；
 *  3. 生成 shell wrapper：`exec qemu-x86_64-static -L / <aapt2> "$@"`；
 *  4. 构建命令追加 `-Pandroid.aapt2FromMavenOverride=<wrapper>`（AGP 官方属性，
 *     设置后不再自行下载 / 校验 aapt2）。
 *
 * 所有操作幂等：[prepare] 可在每次安卓构建前放心调用。
 */
object Aapt2Adapter {

    /** aapt2 制品版本（与 AGP 8.7.x 匹配；override 不校验版本，升级 AGP 时同步）。 */
    const val AAPT2_VERSION = "8.7.3-12006047"

    /** 解压出的 x86_64 aapt2 二进制。 */
    fun aapt2BinFile(context: Context): File =
        File(BuildEnvironment.binDir(context), "aapt2-amd64/aapt2")

    /** 给 AGP 的 override 入口（qemu 包装脚本）。 */
    fun wrapperFile(context: Context): File =
        File(BuildEnvironment.binDir(context), "aapt2wrap/aapt2")

    /** aapt2 maven 制品候选源（国内镜像优先，官方兜底）。 */
    fun aapt2JarUrls(version: String = AAPT2_VERSION): List<String> = listOf(
        "https://maven.aliyun.com/repository/google/com/android/tools/build/aapt2/" +
            "$version/aapt2-$version-linux.jar",
        "https://dl.google.com/android/maven2/com/android/tools/build/aapt2/" +
            "$version/aapt2-$version-linux.jar",
    )

    /** wrapper 脚本内容（LF、ASCII，AGP 经 guest sh 执行）。 */
    fun wrapperScript(aapt2BinPath: String): String =
        "#!/bin/sh\nexec /usr/bin/qemu-x86_64-static -L / $aapt2BinPath \"\$@\"\n"

    /** qemu 覆盖安装准备脚本（guest bash 执行，幂等；真机验证过的版本）。 */
    internal fun qemuProvisionScript(): String = """
        set -e
        if [ -x /usr/bin/qemu-x86_64-static ] && [ -e /lib64/ld-linux-x86-64.so.2 ]; then
            exit 0
        fi
        dpkg --print-architecture
        dpkg --add-architecture amd64
        if ! grep -q 'arch=amd64' /etc/apt/sources.list.d/mobilecoder-amd64.list 2>/dev/null; then
            printf 'deb [arch=amd64] http://archive.ubuntu.com/ubuntu noble main universe\ndeb [arch=amd64] http://archive.ubuntu.com/ubuntu noble-updates main universe\n' \
                > /etc/apt/sources.list.d/mobilecoder-amd64.list
        fi
        if grep -q '^deb http' /etc/apt/sources.list && ! grep -q 'arch=arm64' /etc/apt/sources.list; then
            sed -i 's/^deb /deb [arch=arm64,armhf] /' /etc/apt/sources.list
        fi
        apt-get update -qq || apt-get update
        DEBIAN_FRONTEND=noninteractive apt-get install -y -qq qemu-user-static libc6:amd64
        test -x /usr/bin/qemu-x86_64-static
        test -e /lib64/ld-linux-x86-64.so.2
    """.trimIndent() + "\n"

    /** rootfs 内 qemu-user 是否已安装。 */
    fun qemuInstalled(context: Context): Boolean =
        File(Proot.rootfsDir(context), "usr/bin/qemu-x86_64-static").isFile

    /** wrapper 与 qemu 都就绪。 */
    fun isReady(context: Context): Boolean =
        qemuInstalled(context) && wrapperFile(context).canExecute()

    /** override 命令行参数（构建命令拼装时追加）。 */
    fun overrideArg(context: Context): String =
        "-Pandroid.aapt2FromMavenOverride=" + wrapperFile(context).absolutePath

    /** 从 aapt2 jar 抽出 `aapt2` 条目（zip 内可能带目录前缀，按 basename 匹配）。 */
    internal fun extractAapt2(jar: File, out: File): Boolean {
        return runCatching {
            ZipFile(jar).use { z ->
                val entry = z.entries().toList().firstOrNull { e ->
                    !e.isDirectory && e.name.substringAfterLast('/') == "aapt2"
                } ?: return false
                out.parentFile?.mkdirs()
                out.outputStream().use { os ->
                    z.getInputStream(entry).use { input ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            os.write(buf, 0, n)
                        }
                    }
                }
                runCatching { out.setExecutable(true, false) }
                out.length() > 0
            }
        }.getOrDefault(false)
    }

    /**
     * 安卓构建前适配准备（幂等）：qemu + aapt2 二进制 + wrapper。
     * @param log 构建日志回调
     * @return true = wrapper 就绪，构建命令应追加 [overrideArg]
     */
    suspend fun prepare(context: Context, log: (String) -> Unit): Boolean {
        if (!Proot.isReady(context)) {
            log("Linux 环境未就绪，跳过安卓 aapt2 适配")
            return false
        }
        if (!qemuInstalled(context)) {
            log("安卓适配：安装 qemu-user 与 amd64 运行库（约 40MB，一次性）…")
            val code = runGuestSetup(context, qemuProvisionScript())
            if (code != 0 || !qemuInstalled(context)) {
                log("qemu-user 安装失败（退出码 $code），aapt2 无法在本机运行")
                return false
            }
        }
        val bin = aapt2BinFile(context)
        if (!bin.isFile || bin.length() == 0L) {
            log("安卓适配：下载 aapt2（x86_64，经 qemu 执行）…")
            val cacheDir = File(BuildEnvironment.binDir(context), "aapt2-amd64").apply { mkdirs() }
            val jar = File(cacheDir, "aapt2-linux.jar")
            val ok = aapt2JarUrls().any { url ->
                runCatching {
                    EnvDownloader.download(url, jar) { _, total -> }
                }.isSuccess && extractAapt2(jar, bin).also {
                    if (it) log("aapt2 就绪：${bin.absolutePath}")
                }
            }
            jar.delete()
            if (!ok) {
                log("aapt2 下载 / 解包失败，将按原样构建（可能因 aapt2 无法执行而失败）")
                return false
            }
        }
        val wrapper = wrapperFile(context)
        val want = wrapperScript(bin.absolutePath)
        val current = runCatching { if (wrapper.isFile) wrapper.readText() else null }.getOrNull()
        if (current != want) {
            wrapper.parentFile?.mkdirs()
            runCatching { wrapper.writeText(want) }
            runCatching { wrapper.setExecutable(true, false) }
        }
        return wrapper.canExecute() && bin.canExecute()
    }

    /** 在 guest（proot bash）里跑一段准备脚本，等退出码。 */
    private suspend fun runGuestSetup(context: Context, script: String): Int {
        val exit = CompletableFuture<Int>()
        val tmpDir = File(context.cacheDir, "tmp").apply { mkdirs() }
        val workDir = File(context.cacheDir, "aapt2setup").apply { mkdirs() }
        val callback = object : CliCallback {
            override fun onOutput(pid: Int, stream: Int, data: ByteArray?) { /* 精简输出不上屏 */ }
            override fun onExit(pid: Int, code: Int) { exit.complete(code) }
        }
        val env = arrayOf(
            "PATH=${Proot.pathFor(BuildEnvironment.binDir(context).absolutePath)}",
            "HOME=${context.filesDir.absolutePath}",
            "TMPDIR=${tmpDir.absolutePath}",
            "LANG=C.UTF-8",
            "DEBIAN_FRONTEND=noninteractive",
        ) + Proot.envEntries(context)
        val argv = Proot.wrap(context, arrayOf("/bin/bash", "-c", script), workDir.absolutePath)
        val p = CliNative.exec(argv, workDir.absolutePath, env, callback)
        if (p < 0) return -1
        return runCatching { exit.get() }.getOrDefault(-1)
    }
}
