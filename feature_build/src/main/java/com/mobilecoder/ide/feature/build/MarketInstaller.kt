package com.mobilecoder.ide.feature.build

import android.content.Context
import android.system.Os
import com.mobilecoder.ide.core.common.linux.Proot
import com.mobilecoder.ide.core.nativebridge.CliCallback
import com.mobilecoder.ide.core.nativebridge.CliNative
import com.mobilecoder.ide.core.storage.AppStorage
import com.mobilecoder.ide.core.storage.HistoryStore
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 软件市场的一个商品：安装到 Linux 环境（guest）的软件包。
 *
 * @param id      稳定标识（状态集合、日志归属用）
 * @param name    展示名（Node.js …）
 * @param version 语义化版本号，不带 v 前缀（"22.2.0"）
 * @param summary 一句话说明（列表副标题）
 * @param detail  补充信息（来源 / 安装位置提示）
 * @param probe   安装完成探测点：相对 rootfs 的可执行文件路径
 */
data class MarketItem(
    val id: String,
    val name: String,
    val version: String,
    val summary: String,
    val detail: String,
    val probe: String,
)

/**
 * 软件市场安装状态（页面订阅 [MarketInstaller.state]）。
 *
 * 单任务模型：同一时间最多一个安装在跑（[activeId]）；日志与结果
 * 归属到最近一次安装的条目（[lastId]），安装结束后仍可回看。
 */
data class MarketState(
    /** 正在安装的条目 id（null = 空闲）。 */
    val activeId: String? = null,
    /** 最近一次安装的条目 id（日志 / 结果归属）。 */
    val lastId: String? = null,
    /** 当前阶段文案（脚本内 `###MC:` 标记解析而来）。 */
    val stage: String = "",
    /** 总进度 0..1；-1 表示不确定进度。 */
    val progress: Float = -1f,
    /** 实时日志（最多保留 500 行，见 [MarketInstaller.LOG_LIMIT]）。 */
    val logs: List<String> = emptyList(),
    /** 终态结果消息（null = 进行中或未开始）。 */
    val message: String? = null,
    /** 最近一次安装是否成功。 */
    val success: Boolean = false,
    /** 已安装条目 id 集合（按 rootfs 探测点刷新）。 */
    val installed: Set<String> = emptySet(),
) {
    /** 是否有安装任务进行中。 */
    val busy: Boolean get() = activeId != null
}

/**
 * 软件市场执行器：一条自包含的 guest bash 脚本（apt → curl 下载 → tar 解压
 * → 落位 /usr/local → 校验），经 [Proot.wrap] + [CliNative.exec] 后台静默执行。
 *
 * 设计要点：
 *  - **单例作用域**：安装在 [scope] 里跑，切走页面不中断，回页面继续看日志；
 *  - **首次自动补环境**：guest 未就绪时先走 [EnvDownloader.install]（rootfs + proot），
 *    进度按 0..0.25 折算，避免用户先去构建页手动装一遍；
 *  - **临时目录**：`cache/tmp` 是 proot 的 PROOT_TMP_DIR（终端同约定），启动前
 *    Kotlin 侧 mkdirs + chmod 01777 保证 proot 能起；下载工作区 `cache/marketdl`
 *    与 dataDir 同路径 bind，在 guest 内原样可见，宿主与脚本看到同一份文件；
 *  - **阶段标记**：脚本 `echo '###MC:xxx'` → 解析为阶段文案 + 基准进度，
 *    下载阶段解析 curl `-#` 进度条百分比推进 0.35..0.90；
 *  - **可取消**：[cancel] 置位 + [CliNative.killProcess]（proot --kill-on-exit
 *    连带子进程），Linux 环境阶段转交 [EnvDownloader.cancel]；
 *  - **落地记录**：安装成功写入历史（源 = 市场），文本为头注释 + 完整脚本，
 *    复制即可在终端重跑。
 */
object MarketInstaller {

    /** 脚本阶段标记前缀（安装脚本 echo，运行时解析为阶段文案）。 */
    const val STAGE_PREFIX = "###MC:"

    /** 日志保留上限（超出丢最旧）。 */
    private const val LOG_LIMIT = 500

    /** chmod 1777（十进制 1023）：与终端 TMPDIR 约定一致的粘滞位全开。 */
    private const val MODE_1777 = 1023

    /** 首批上架软件（后续按此结构扩展即可，无需改执行链路）。 */
    val items: List<MarketItem> = listOf(
        MarketItem(
            id = "nodejs",
            name = "Node.js",
            version = "22.2.0",
            summary = "JavaScript 运行时：npm 包管理、前端构建、opencode 等 AI CLI 的运行基础",
            detail = "npmmirror 加速（失败自动回退 nodejs.org 官方源）· 安装到 guest 的 /usr/local",
            probe = "usr/local/bin/node",
        ),
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 状态锁：回调来自 native 线程，日志追加与阶段更新都经它。 */
    private val lock = Any()

    private val _state = MutableStateFlow(MarketState())
    val state: StateFlow<MarketState> = _state.asStateFlow()

    @Volatile
    private var cancelled = false

    @Volatile
    private var pid = -1

    /** 是否正处在「安装 Linux 环境」阶段（取消时决定转交谁）。 */
    @Volatile
    private var linuxPhase = false

    // ------------------------------------------------------------------
    // 对外入口
    // ------------------------------------------------------------------

    /** 开始安装 [item]（单任务：已有任务在跑时忽略本次点击）。 */
    fun install(context: Context, item: MarketItem) {
        synchronized(lock) {
            if (_state.value.busy) return
            _state.value = MarketState(
                activeId = item.id,
                lastId = item.id,
                stage = "准备中…",
                progress = 0f,
                installed = _state.value.installed,
            )
        }
        cancelled = false
        val app = context.applicationContext
        scope.launch { run(app, item) }
    }

    /** 取消当前安装（环境阶段转交 [EnvDownloader.cancel]，脚本阶段杀进程组）。 */
    fun cancel() {
        cancelled = true
        if (linuxPhase) runCatching { EnvDownloader.cancel() }
        pid.takeIf { it > 0 }?.let { p -> runCatching { CliNative.killProcess(p, 15) } }
    }

    /** 按 rootfs 探测点刷新已安装集合（进入页面 / 安装结束后调用）。 */
    fun refreshInstalled(context: Context) {
        val rootfs = Proot.rootfsDir(context)
        val installed = items.filter { probeInstalled(rootfs, it) }.mapTo(mutableSetOf()) { it.id }
        synchronized(lock) { _state.value = _state.value.copy(installed = installed) }
    }

    // ------------------------------------------------------------------
    // 安装主流程
    // ------------------------------------------------------------------

    private suspend fun run(context: Context, item: MarketItem) {
        try {
            // 1) Linux 环境前提（apt / proot 内执行），首次自动在线安装
            if (!Proot.isReady(context)) {
                publish { it.copy(stage = "首次安装：Linux 环境（rootfs + proot）…", progress = 0.02f) }
                linuxPhase = true
                val source = downloadSource()
                EnvDownloader.install(
                    context,
                    EnvKind.LINUX,
                    source,
                    onStage = { s -> publish { st -> st.copy(stage = s) } },
                    onProgress = { p -> publish { st -> st.copy(progress = (p * 0.25f).coerceIn(0f, 1f)) } },
                )
                linuxPhase = false
            }
            throwIfCancelled()

            // 2) 目录准备：PROOT_TMP_DIR（proot 启动依赖）+ 下载工作区（guest 同路径可见）
            val tmpDir = File(context.cacheDir, "tmp").apply { mkdirs() }
            runCatching { Os.chmod(tmpDir.absolutePath, MODE_1777) }
            val workDir = File(context.cacheDir, "marketdl").apply { mkdirs() }

            // 3) 组装并执行脚本
            val script = nodeScript(
                tmpDir = tmpDir.absolutePath,
                workDir = workDir.absolutePath,
                version = item.version,
                arch = nodeArch(EnvDownloader.primaryArch()),
            )
            publish { it.copy(stage = "准备目录", progress = stageProgress("准备目录")) }

            val exit = CompletableDeferred<Int>()
            val assembler = LineAssembler()
            val callback = object : CliCallback {
                override fun onOutput(pid: Int, stream: Int, data: ByteArray?) {
                    if (data == null || data.isEmpty()) return
                    assembler.feed(String(data, Charsets.UTF_8)).forEach { handleLine(it) }
                }

                override fun onExit(pid: Int, code: Int) {
                    assembler.flush().forEach { handleLine(it) }
                    exit.complete(code)
                }
            }
            val env = arrayOf(
                "PATH=${Proot.pathFor(BuildEnvironment.binDir(context).absolutePath)}",
                "HOME=${context.filesDir.absolutePath}",
                "TMPDIR=${BuildEnvironment.tmpDir(context).absolutePath}",
                "LANG=C.UTF-8",
                "DEBIAN_FRONTEND=noninteractive",
                "SHELL=/bin/bash",
            ) + Proot.envEntries(context)
            val argv = Proot.wrap(context, arrayOf("/bin/bash", "-c", script), workDir.absolutePath)
            val p = CliNative.exec(argv, workDir.absolutePath, env, callback)
            if (p < 0) throw IllegalStateException("无法启动安装进程：并发进程数已达上限")
            pid = p
            // 竞态兜底：exec 返回前用户已点取消
            if (cancelled) runCatching { CliNative.killProcess(p, 15) }
            val code = exit.await()
            pid = -1
            throwIfCancelled()
            if (code != 0) {
                val tail = _state.value.logs.lastOrNull().orEmpty()
                throw IllegalStateException(
                    "安装脚本失败（退出码 $code）" + if (tail.isNotBlank()) "：$tail" else "",
                )
            }

            // 4) 校验探测点 → 成功收尾
            if (!probeInstalled(Proot.rootfsDir(context), item)) {
                throw IllegalStateException("脚本已执行完，但未检测到 ${item.name}（${item.probe}）")
            }
            val scriptForHistory = script
            publish {
                it.copy(
                    activeId = null,
                    stage = "安装完成",
                    progress = 1f,
                    success = true,
                    message = "安装完成 · ${item.name} v${item.version} 已可用",
                )
            }
            runCatching {
                HistoryStore.add(
                    "# ${item.name} v${item.version}（软件市场）\n$scriptForHistory",
                    HistoryStore.SOURCE_MARKET,
                )
            }
        } catch (e: DownloadCancelled) {
            publish { it.copy(activeId = null, stage = "已取消", success = false, message = "已取消安装") }
        } catch (t: Throwable) {
            val msg = if (cancelled || t is InstallCancelledException) {
                "已取消安装"
            } else {
                t.message ?: t.toString()
            }
            publish { it.copy(activeId = null, success = false, message = msg) }
        } finally {
            linuxPhase = false
            pid = -1
            runCatching { refreshInstalled(context) }
        }
    }

    // ------------------------------------------------------------------
    // 输出处理（native 回调线程）
    // ------------------------------------------------------------------

    /** 单行处理：阶段标记 → 更新阶段；下载进度帧 → 更新百分比；其余进日志。 */
    private fun handleLine(raw: String) {
        val line = raw.trim()
        if (line.isEmpty()) return
        val marker = parseStage(line)
        if (marker != null) {
            publish { it.copy(stage = marker, progress = stageProgress(marker)) }
            return
        }
        val stage = _state.value.stage
        if (stage.startsWith("下载") && isProgressFrame(line)) {
            val pct = downloadPercent(line)
            if (pct != null) {
                val p = 0.35f + 0.55f * pct
                publish { it.copy(progress = p) }
            }
            // 进度帧（含起步动画帧）不进日志，避免刷屏
            return
        }
        appendLog(line)
    }

    /** 追加一行日志（超上限丢最旧）。 */
    private fun appendLog(line: String) {
        synchronized(lock) {
            val cur = _state.value
            val merged = cur.logs + line
            _state.value = cur.copy(
                logs = if (merged.size > LOG_LIMIT) merged.takeLast(LOG_LIMIT) else merged,
            )
        }
    }

    /** 状态更新（线程安全）。 */
    private fun publish(transform: (MarketState) -> MarketState) {
        synchronized(lock) { _state.value = transform(_state.value) }
    }

    private fun throwIfCancelled() {
        if (cancelled) throw InstallCancelledException()
    }

    private suspend fun downloadSource(): EnvSource = runCatching {
        if (AppStorage.preferences.envDownloadSource() == "mirror") EnvSource.MIRROR else EnvSource.OFFICIAL
    }.getOrDefault(EnvSource.OFFICIAL)

    private class InstallCancelledException : Exception("已取消安装")

    // ------------------------------------------------------------------
    // 纯函数（脚本生成 / 解析 / 探测，JVM 单测覆盖）
    // ------------------------------------------------------------------

    /** ABI → Node 官方发布包架构（aarch64 → arm64，其余按 x64 处理）。 */
    internal fun nodeArch(abi: String): String = if (abi == "aarch64") "arm64" else "x64"

    /**
     * 生成 Node.js 安装脚本（自包含单文件，guest 内直接执行）：
     *  1. 准备目录（PROOT_TMP_DIR 粘滞位 01777，与终端约定一致）
     *  2. apt 装 ca-certificates / curl
     *  3. npmmirror 下载（失败回退 nodejs.org 官方源）
     *  4. tar 解压 → cp 落位 /usr/local → 校验 → 清理
     *
     * 任意一步失败即中止（`set -e`），退出码带上最后一条日志定位问题。
     *
     * @param tmpDir   PROOT_TMP_DIR（guest 内同路径可见）
     * @param workDir  下载与解压工作区（cwd）
     * @param version  语义化版本号，带不带 v 前缀均可
     * @param arch     [nodeArch] 结果（arm64 / x64）
     */
    internal fun nodeScript(tmpDir: String, workDir: String, version: String, arch: String): String {
        val v = version.removePrefix("v")
        // 官方发布包文件名带 v 前缀：node-v22.2.0-linux-arm64.tar.gz（2026-10 实测官方源与 npmmirror 均如此）
        val pkg = "node-v$v-linux-$arch"
        val mirror = "https://registry.npmmirror.com/-/binary/node/v$v/$pkg.tar.gz"
        val official = "https://nodejs.org/dist/v$v/$pkg.tar.gz"
        return listOf(
            "set -e",
            "echo '${STAGE_PREFIX}准备目录'",
            "mkdir -p '$tmpDir' '$workDir'",
            "chmod 1777 '$tmpDir'",
            "cd '$workDir'",
            "echo '${STAGE_PREFIX}安装依赖（apt：ca-certificates / curl）'",
            "apt update",
            "apt install -y ca-certificates curl",
            "echo '${STAGE_PREFIX}下载 $pkg（npmmirror，失败回退官方源）'",
            "rm -f node.tar.gz",
            "curl -# -fL -k '$mirror' -o node.tar.gz || curl -# -fL -k '$official' -o node.tar.gz",
            "echo '${STAGE_PREFIX}解压并安装到 /usr/local'",
            "rm -rf '$workDir/$pkg'",
            "tar -zxf node.tar.gz",
            "cp -r '$workDir/$pkg/bin' '$workDir/$pkg/include' '$workDir/$pkg/lib' '$workDir/$pkg/share' /usr/local/",
            "echo '${STAGE_PREFIX}校验安装'",
            "/usr/local/bin/node --version",
            "echo '${STAGE_PREFIX}清理临时文件'",
            "rm -rf '$workDir/node.tar.gz' '$workDir/$pkg'",
            "echo '${STAGE_PREFIX}完成'",
            "",
        ).joinToString("\n")
    }

    /** 从输出行解析阶段标记（非标记行返回 null）。 */
    internal fun parseStage(line: String): String? =
        if (line.startsWith(STAGE_PREFIX)) line.removePrefix(STAGE_PREFIX).trim() else null

    /** 阶段 → 基准进度（-1 = 不确定进度）。 */
    internal fun stageProgress(stage: String): Float = when {
        stage.startsWith("准备") -> 0.30f
        stage.startsWith("安装依赖") -> -1f
        stage.startsWith("下载") -> 0.35f
        stage.startsWith("解压") -> 0.92f
        stage.startsWith("校验") -> 0.96f
        stage.startsWith("清理") -> 0.98f
        stage.startsWith("完成") -> 1f
        else -> -1f
    }

    /** 解析 curl `-#` 进度帧百分比（0..1）；无百分比返回 null。 */
    internal fun downloadPercent(line: String): Float? {
        val match = PERCENT.findAll(line).lastOrNull() ?: return null
        val value = match.groupValues[1].toFloatOrNull() ?: return null
        return if (value in 0f..100f) value / 100f else null
    }

    /**
     * 判断是否为 curl `-#` 进度帧：带百分比的条帧（`#####… 62.4%`），
     * 或起步阶段尚无百分比的纯动画帧（`#=-O ` 构成，如 `#=#=#`）。
     * 用来把 `\r` 原地刷新的进度帧与普通日志行区分开。
     */
    internal fun isProgressFrame(line: String): Boolean {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return false
        if (trimmed.endsWith("%")) {
            val before = trimmed.dropLast(1)
            val hasDigit = before.any { it.isDigit() }
            val barOnly = before.all { it == '#' || it == ' ' || it == '.' || it.isDigit() }
            if (hasDigit && barOnly) return true
        }
        return trimmed.all { it == '#' || it == '=' || it == '-' || it == 'O' || it == ' ' }
    }

    /** 按 rootfs 探测点判断 [item] 是否已安装。 */
    internal fun probeInstalled(rootfs: File, item: MarketItem): Boolean =
        File(rootfs, item.probe).exists()

    private val PERCENT = Regex("""(\d{1,3}(?:\.\d+)?)%""")

    /**
     * 字节块 → 行装配器：`\n` 与 `\r` 都作为行分隔（curl 进度帧用 `\r`
     * 原地刷新），`\r\n` 合并为一次分隔；无换行的残留字节留给下一次 feed。
     */
    internal class LineAssembler {
        private val buf = StringBuilder()
        private var lastWasCr = false

        /** 喂入一段输出，返回其中完整（非空）行。 */
        fun feed(chunk: String): List<String> {
            val out = mutableListOf<String>()
            for (ch in chunk) {
                when {
                    ch == '\n' && lastWasCr -> lastWasCr = false // \r\n 只切一次
                    ch == '\n' || ch == '\r' -> {
                        emit(out)
                        lastWasCr = ch == '\r'
                    }
                    else -> {
                        lastWasCr = false
                        buf.append(ch)
                    }
                }
            }
            return out
        }

        /** 进程结束后取残留行。 */
        fun flush(): List<String> {
            val out = mutableListOf<String>()
            emit(out)
            lastWasCr = false
            return out
        }

        private fun emit(out: MutableList<String>) {
            val line = buf.toString().trim()
            buf.setLength(0)
            if (line.isNotEmpty()) out.add(line)
        }
    }
}
