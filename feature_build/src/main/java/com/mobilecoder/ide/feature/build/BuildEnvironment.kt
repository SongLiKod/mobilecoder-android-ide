package com.mobilecoder.ide.feature.build

import android.content.Context
import android.net.Uri
import com.mobilecoder.ide.core.common.linux.Proot
import com.mobilecoder.ide.core.nativebridge.NativeRuntime
import com.mobilecoder.ide.core.storage.AppStorage
import java.io.File
import java.nio.file.Files
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** 构建环境组件类别。 */
enum class EnvKind(val title: String) {
    JDK("JDK"),
    GRADLE("Gradle"),
    SDK("Android SDK"),
    NODE("Node.js"),

    /**
     * Linux 环境（Ubuntu 24.04 rootfs + proot，`files/linux`）。
     * JDK / Gradle / node 全是 glibc ELF，Android 只有 bionic，内核 exec 时找不到
     * `/lib/ld-linux-aarch64.so.1` → ENOENT(2)、退出码 127。装上它之后终端与构建
     * 命令全部在 proot 内运行（见 `core_common/…/linux/Proot.kt`），同时终端变成
     * 完整 Linux，`apt install` 可装任意软件包。
     */
    LINUX("Linux 环境"),
}

/** 单项环境检测结果。 */
data class EnvItem(
    val kind: EnvKind,
    /** 已配置/检测到的根目录，null 表示尚未导入。 */
    val path: String?,
    val ready: Boolean,
    /** 未就绪时的中文引导。 */
    val hint: String,
)

/** 整体环境体检结果。 */
data class EnvStatus(
    /** 当前项目**需要**的组件（按项目类型动态推断；纯打包类项目为空）。 */
    val items: List<EnvItem>,
    val required: List<EnvKind> = items.map { it.kind },
    /**
     * 设备上**已装但非当前项目必需**的工具（软件市场 / 终端里 agent 装的
     * Python、GCC、Git 等）。环境状态条据此体现「这些环境其实已经有了」。
     */
    val extraTools: List<String> = emptyList(),
) {

    /** 所需组件全部就绪才算构建环境可用（无需求的项目恒为 true）。 */
    val ready: Boolean get() = items.all { it.ready }

    fun item(kind: EnvKind): EnvItem? = items.firstOrNull { it.kind == kind }
}

/**
 * 构建环境（PRD 2.7「移动端轻量化 Gradle 编译环境」/ TECH 4.6「内置轻量化 Android SDK + Gradle 精简环境」）。
 *
 * 设备端**不联网**：JDK / Gradle / Android SDK 全部由用户在「构建环境」页
 * 通过 SAF 选择本地 zip 导入到 `files/sdk/`（zip slip 防护 + 权限修复 + 幂等）。
 */
object BuildEnvironment {

    private const val TAG = "BuildEnvironment"

    /** 最近一次体检结果（UI 订阅）。 */
    private val _status = MutableStateFlow<EnvStatus?>(null)
    val status: StateFlow<EnvStatus?> = _status.asStateFlow()

    // ------------------------------------------------------------------
    // 目录约定（TECH.md 5 存储结构）
    // ------------------------------------------------------------------

    /** `files/sdk` —— ANDROID_HOME / ANDROID_SDK_ROOT。 */
    fun sdkDir(context: Context): File = File(context.filesDir, "sdk")

    /** `files/.gradle` —— GRADLE_USER_HOME。 */
    fun gradleUserHome(context: Context): File = File(context.filesDir, ".gradle")

    /** `cache/tmp` —— TMPDIR。 */
    fun tmpDir(context: Context): File = File(context.cacheDir, "tmp")

    /** `files/bin` —— PATH 首位。 */
    fun binDir(context: Context): File = File(context.filesDir, "bin")

    private fun hasBin(dir: File?, executable: String): Boolean =
        dir != null && File(dir, "bin/$executable").exists()

    // ------------------------------------------------------------------
    // 检测
    // ------------------------------------------------------------------

    /** 解析 JDK 根目录：用户配置 → `files/sdk/jdk` → sdk 下任意含 `bin/java` 的目录。 */
    suspend fun resolveJdk(context: Context): String? {
        val configured = runCatching { AppStorage.preferences.jdkPath() }.getOrDefault("")
        if (configured.isNotBlank() && hasBin(File(configured), "java")) return configured
        return scanFor(context, "java")
    }

    /** 解析 Gradle 发行版根目录：用户配置 → `files/sdk/gradle` → sdk 下任意含 `bin/gradle` 的目录。 */
    suspend fun resolveGradle(context: Context): String? {
        val configured = runCatching { AppStorage.preferences.gradlePath() }.getOrDefault("")
        if (configured.isNotBlank() && hasBin(File(configured), "gradle")) return configured
        return scanFor(context, "gradle")
    }

    private fun scanFor(context: Context, executable: String): String? {
        val sdk = sdkDir(context)
        val direct = File(sdk, if (executable == "java") "jdk" else "gradle")
        if (hasBin(direct, executable)) return direct.absolutePath
        val found = sdk.listFiles()?.firstNotNullOfOrNull { dir ->
            when {
                dir.isDirectory && hasBin(dir, executable) -> dir
                dir.isDirectory -> dir.listFiles()?.firstOrNull { inner ->
                    inner.isDirectory && hasBin(inner, executable)
                }
                else -> null
            }
        } ?: return null
        return found.absolutePath
    }

    /** SDK 是否具备构建所需结构（platforms / build-tools / platform-tools 至少其一）。 */
    fun sdkLooksReady(sdk: File): Boolean =
        listOf("platforms", "build-tools", "platform-tools").any { name ->
            val dir = File(sdk, name)
            dir.isDirectory && (dir.listFiles()?.isNotEmpty() == true)
        }

    /** 执行一次体检并缓存结果（[projectDir] 用于按项目类型动态过滤所需组件）。 */
    suspend fun refresh(context: Context, projectDir: File? = null): EnvStatus {
        val all = detect(context)
        val required = withLinuxIfNeeded(requirementsFor(projectDir))
        val items = all.filter { it.kind in required }
        val status = EnvStatus(
            items = items,
            required = required,
            extraTools = extraTools(all, items, context),
        )
        _status.value = status
        // JAVA_HOME 同步注入进程环境，供终端 / CLI 子进程继承（与项目类型无关）
        runCatching {
            NativeRuntime.setJavaHome(context, all.firstOrNull { it.kind == EnvKind.JDK }?.path)
        }
        return status
    }

    /**
     * 把 **Linux 环境（LINUX）** 并入「所需组件」——仅当确有需要执行的工具链时。
     *
     * 终端命令、`npm`、`sdkmanager`、构建里的 `java`/`gradle` 全部统一从 proot 走，
     * rootfs 因此是**需要子进程的项目**（Gradle / Node）的硬依赖 —— 装一次即可。
     * 纯打包类项目（[ProjectKind.ZIP_PACKAGE]，进程内 zip）不跑任何子进程，
     * 需求为空时不再追加 Linux，避免构建页出现与实际无关的「Linux 环境 ✗」。
     *
     * @return 追加后的所需组件（已有或本就为空则原样返回）
     */
    internal fun withLinuxIfNeeded(required: List<EnvKind>): List<EnvKind> = when {
        required.isEmpty() || EnvKind.LINUX in required -> required
        else -> required + EnvKind.LINUX
    }

    /**
     * 按当前项目类型推断**需要**哪些环境（「构建环境」页据此动态展示与检查）。
     *
     * 统一委托 [detectProject]（构建页与环境中心共用同一套类型识别）：
     *  - 安卓 Gradle 工程 → JDK + Gradle + Android SDK
     *  - 纯 JVM/Kotlin Gradle 工程 → JDK + Gradle
     *  - Node/Vue 工程（package.json 且有 build 脚本）→ Node.js
     *  - 静态 HTML / Flutter / Python / 未识别 → 空（源码打包无需工具链）
     *  - [projectDir] 为 null（未打开项目）→ 按默认（安卓）展示
     */
    fun requirementsFor(projectDir: File?): List<EnvKind> {
        if (projectDir == null) return listOf(EnvKind.JDK, EnvKind.GRADLE, EnvKind.SDK)
        return detectProject(projectDir).requiredEnv
    }

    /**
     * 已装但**非当前项目必需**的工具（市场 / 终端 agent 装的）+ 已就绪而本次未要求的
     * 核心组件，去重后返回 —— 让构建页环境状态条体现设备已具备的全部能力。
     */
    private fun extraTools(all: List<EnvItem>, items: List<EnvItem>, context: Context): List<String> {
        val readyRequired = items.filter { it.ready }.map { it.kind.title }
        val coreReady = all.filter { it.ready }.map { it.kind.title }
        val rootfs = Proot.rootfsDir(context)
        val marketReady = MarketInstaller.items
            .filter { MarketInstaller.probeInstalled(rootfs, it) }
            .map { it.name }
        return (coreReady + marketReady).distinct().filterNot { it in readyRequired }
    }

    /** 是否安卓工程：存在 AndroidManifest，或构建脚本引用了 com.android 插件。 */
    fun isAndroidProject(root: File): Boolean {
        if (File(root, "app/src/main/AndroidManifest.xml").exists()) return true
        if (File(root, "src/main/AndroidManifest.xml").exists()) return true
        return listOf("settings.gradle", "settings.gradle.kts", "build.gradle", "build.gradle.kts")
            .mapNotNull { name -> File(root, name).takeIf { it.exists() } }
            .any { file -> runCatching { file.readText() }.getOrDefault("").contains("com.android") }
    }

    /** 体检：按 [projectDir] 所需组件返回路径与可用性。 */
    suspend fun status(context: Context, projectDir: File? = null): EnvStatus {
        val all = detect(context)
        val required = withLinuxIfNeeded(requirementsFor(projectDir))
        val items = all.filter { it.kind in required }
        return EnvStatus(items = items, required = required, extraTools = extraTools(all, items, context))
    }

    /** 全量检测（不做项目过滤）。 */
    private suspend fun detect(context: Context): List<EnvItem> {
        val jdk = resolveJdk(context)
        val gradle = resolveGradle(context)
        val node = resolveNode(context)
        // rootfs 内的 Node（软件市场 / 终端 apt 装的）：整条构建命令在 proot 里执行，
        // 用的正是 rootfs 的 node+npm —— 与 files/sdk 的 Node 二选一即可视为就绪
        val rootfsNode = run {
            val rootfs = Proot.rootfsDir(context)
            listOf("usr/local/bin/node", "usr/bin/node")
                .map { File(rootfs, it) }
                .firstOrNull { it.exists() }
        }
        val sdk = sdkDir(context)
        val sdkReady = sdkLooksReady(sdk)
        return listOf(
            EnvItem(
                kind = EnvKind.JDK,
                path = jdk,
                ready = jdk != null,
                hint = "未就绪：点「在线下载」安装 Temurin JDK 17（约 190MB），或导入本地 JDK 压缩包（zip / tar.gz）",
            ),
            EnvItem(
                kind = EnvKind.GRADLE,
                path = gradle,
                ready = gradle != null,
                hint = "未就绪：点「在线下载」安装 Gradle 8.9（约 130MB），或导入 gradle-8.9-bin.zip",
            ),
            EnvItem(
                kind = EnvKind.SDK,
                path = if (sdk.isDirectory) sdk.absolutePath else null,
                ready = sdkReady,
                hint = "未就绪：点「在线安装 SDK」将下载 cmdline-tools 并自动装好 platform-tools / android-35 / build-tools-35，也可导入完整 SDK zip",
            ),
            EnvItem(
                kind = EnvKind.NODE,
                path = node ?: rootfsNode?.absolutePath,
                ready = node != null || rootfsNode != null,
                hint = "未就绪：点「在线下载」安装 Node.js 20（含 npm，约 50MB），或导入 node-v*-linux-*.tar.gz",
            ),
            EnvItem(
                kind = EnvKind.LINUX,
                path = Proot.rootfsDir(context).takeIf { it.isDirectory }?.absolutePath,
                ready = Proot.isReady(context),
                hint = "未就绪：点「在线下载」安装 Linux 环境（Ubuntu 24.04 rootfs + proot，约 35MB）。" +
                    "装好后终端就是完整 Linux：`apt install` 可装任意软件包，JDK / Gradle / " +
                    "node 等 glibc 程序也在其中原样运行；也可导入本地 ubuntu-base-*.tar.gz",
            ),
        )
    }

    // ------------------------------------------------------------------
    // 导入与落位（本地 SAF 文件 / 在线下载共用）
    // ------------------------------------------------------------------

    /**
     * 解压用户选择的本地压缩包（zip / tar.gz，按流首部自动嗅探）到 `files/sdk/`。
     *
     * - **路径穿越防护**：条目 canonical 化后必须位于目标目录内；
     * - **结构识别**：自动定位含 `bin/java`（或 `bin/gradle`、`bin/node`、SDK 结构）的根目录；
     * - **幂等**：重复导入直接覆盖旧目录；
     * - **权限修复**：压缩包不保留 Unix 权限位，导入后对 `bin` 目录下的文件执行 setExecutable。
     *
     * @param onProgress 解压进度 0..1（按字节数）
     * @return 最终落地目录绝对路径
     */
    suspend fun importArchive(
        context: Context,
        uri: Uri,
        kind: EnvKind,
        onProgress: (Float) -> Unit = {},
    ): String = withContext(Dispatchers.IO) {
        // Linux 环境（ubuntu-base tar.gz）走专属管线：解压落位 rootfs 后还要补装
        // proot 三件套并做首启配置，不能进下面的「落位 files/sdk」通用流程
        if (kind == EnvKind.LINUX) {
            return@withContext RootfsManager.installFromArchive(context, uri, onStage = {}, onProgress = onProgress)
        }
        val sdk = sdkDir(context)
        if (!sdk.exists() && !sdk.mkdirs()) {
            throw IllegalStateException("无法创建目录：${sdk.absolutePath}")
        }
        val tmp = File(sdk, ".import_${kind.name.lowercase(Locale.ROOT)}_${System.nanoTime()}")
        if (!tmp.mkdirs() && !tmp.isDirectory) {
            throw IllegalStateException("无法创建临时目录：${tmp.absolutePath}")
        }
        try {
            val totalBytes = totalSize(context, uri).coerceAtLeast(1L)
            val raw = context.contentResolver.openInputStream(uri)
                ?: throw IllegalStateException("无法读取所选文件，请重新选择")
            raw.use { input -> ArchiveExtractor.extract(input, tmp, totalBytes, onProgress) }
            onProgress(1f)
            placeExtracted(context, kind, tmp)
        } finally {
            runCatching { tmp.deleteRecursively() }
        }
    }

    /**
     * 解压产物落位：结构识别 → 移入 files/sdk → 修复可执行权限 → 写偏好 → 刷新体检。
     *
     * @param requireReady SDK 合并后是否强制要求结构完整（在线下载 cmdline-tools 时传 false，
     *                     待 sdkmanager 装完平台组件后自然满足）
     * @return 最终落地目录绝对路径
     */
    internal suspend fun placeExtracted(
        context: Context,
        kind: EnvKind,
        tmp: File,
        requireReady: Boolean = true,
    ): String {
        val sdk = sdkDir(context)
        val target = when (kind) {
            EnvKind.JDK -> relocate(tmp, "bin/java", File(sdk, "jdk"))
            EnvKind.GRADLE -> relocate(tmp, "bin/gradle", File(sdk, "gradle"))
            EnvKind.NODE -> relocate(tmp, "bin/node", File(sdk, "node"))
            // LINUX 的导入与下载都在入口分流给了 RootfsManager（rootfs + proot + 首启配置），
            // 绝不能按「解压包塞进 files/sdk」处理
            EnvKind.LINUX -> throw IllegalStateException("Linux 环境由 RootfsManager 负责落位")
            EnvKind.SDK -> mergeIntoSdk(tmp, sdk, requireReady)
        }
        // 修复可执行权限（gradlew / java / gradle / node 都是脚本或 ELF）
        fixExecutable(File(target, "bin"))
        when (kind) {
            EnvKind.JDK -> {
                runCatching { AppStorage.preferences.setJdkPath(target) }
                runCatching { NativeRuntime.setJavaHome(context, target) }
            }
            EnvKind.GRADLE -> runCatching { AppStorage.preferences.setGradlePath(target) }
            EnvKind.NODE -> writeNodeShims(context, File(target))
            EnvKind.LINUX -> Unit
            EnvKind.SDK -> Unit
        }
        refresh(context)
        return target
    }

    /** 找到含 [marker]（相对路径）的目录并整体移动到 [dest]（旧目录先删除，保证幂等）。 */
    private fun relocate(tmp: File, marker: String, dest: File): String {
        val root = findContaining(tmp, marker, 4)
            ?: throw IllegalStateException("压缩包结构不匹配：未找到 $marker，请确认选择的是正确的 zip")
        if (root.canonicalPath == dest.canonicalPath) return dest.absolutePath
        if (dest.exists()) dest.deleteRecursively()
        dest.parentFile?.mkdirs()
        if (!root.renameTo(dest)) {
            root.copyRecursively(dest, overwrite = true)
        }
        return dest.absolutePath
    }

    /** SDK zip：把顶层内容合并进 `files/sdk`（兼容单层 `android-sdk/` 包裹目录）。 */
    private fun mergeIntoSdk(tmp: File, sdk: File, requireReady: Boolean = true): String {
        var source = tmp
        val children = tmp.listFiles()?.filter { !it.name.startsWith(".") }.orEmpty()
        val sdkMarkers = listOf("platforms", "build-tools", "platform-tools")
        if (children.size == 1 && children[0].isDirectory) {
            val inner = children[0]
            if (sdkMarkers.any { File(inner, it).isDirectory } || hasBin(inner, "java")) {
                source = inner
            }
        }
        source.listFiles()?.forEach { child ->
            val dest = File(sdk, child.name)
            if (child.isDirectory) {
                if (!child.renameTo(dest)) {
                    child.copyRecursively(dest, overwrite = true)
                }
            } else if (!dest.exists()) {
                child.copyTo(dest, overwrite = true)
            }
        }
        if (requireReady && !sdkLooksReady(sdk)) {
            throw IllegalStateException("SDK 结构不完整：缺少 platforms / build-tools，请导入正确的 Android SDK 压缩包")
        }
        return sdk.absolutePath
    }

    /** 深度优先查找包含 [marker] 的目录（depth ≤ [maxDepth]）。 */
    private fun findContaining(dir: File, marker: String, maxDepth: Int): File? {
        if (maxDepth < 0) return null
        if (File(dir, marker).exists()) return dir
        dir.listFiles()?.forEach { child ->
            if (child.isDirectory) {
                findContaining(child, marker, maxDepth - 1)?.let { return it }
            }
        }
        return null
    }

    /** 对目录下所有普通文件授予可执行权限（zip 不保留权限位）。 */
    private fun fixExecutable(bin: File) {
        if (!bin.isDirectory) return
        val files = mutableListOf<File>()
        bin.walkTopDown().forEach { file -> if (file.isFile) files += file }
        makeExecutableAll(files)
    }

    // ------------------------------------------------------------------
    // 可执行权限自愈
    // ------------------------------------------------------------------

    /**
     * 补执行位并**校验**：`File.setExecutable` 静默失败（返回 false、不抛异常）时，
     * 终端里照样报 `Permission denied`，所以对**没生效的那些**回退一次系统 `chmod`
     * （与 BuildRunner / EnvDownloader 已有的 shell `chmod` 兜底一致）。
     *
     * 批量处理：一个 JDK 的 `bin` 就有几十个文件，逐个起 shell 会拖慢导入流程。
     */
    private fun makeExecutable(file: File) = makeExecutableAll(listOf(file))

    private fun makeExecutableAll(files: List<File>) {
        if (files.isEmpty()) return
        files.forEach { file -> runCatching { file.setExecutable(true, false) } }
        chmodViaShell(files.filter { !it.canExecute() })
    }

    /** 一次性 `chmod u+rwx` 多个路径（走 /system/bin/sh，避免逐个起进程）。 */
    private fun chmodViaShell(files: List<File>) {
        if (files.isEmpty()) return
        runCatching {
            val args = files.joinToString(" ") { "'${it.absolutePath}'" }
            val process = ProcessBuilder("/system/bin/sh", "-c", "chmod u+rwx $args")
                .redirectOutput(ProcessBuilder.Redirect.from(File("/dev/null")))
                .redirectErrorStream(true)
                .start()
            runCatching { process.waitFor() }
            runCatching { process.outputStream.close() }
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
        }
    }

    /**
     * 自愈 `files/bin` 与 `files/sdk` 下各级 `bin` 的可执行权限（幂等，App 启动与执行 npm 前各跑一次）。
     *
     * 终端里直接敲 `node -v` 不经过任何安装流程，此前只有安装流程会补执行位：
     * 只要某次解压 / npm 写入 / 目录被改过导致权限位丢失，用户就会看到
     * `sh: …/files/bin/node: Permission denied`，且没有任何命令能修好它。
     *
     *  1. 目录本身必须可穿越——目录缺 `x` 时，里面的文件再有执行位同样报 Permission denied；
     *  2. Node 就绪则重建 `node` / `npm` / `npx` 入口（内容 + 执行位一起修）；
     *  3. `files/bin` 与各级 `bin` 下的普通文件补执行位（`canExecute()` 会跟随符号链接，
     *     因此 npm 生成的链接目标也会被一并修好）；
     *  4. `File.setExecutable` 没生效的路径回退系统 `chmod`，仍不行才作为异常返回。
     *
     * @return 校验后仍不可执行的路径（空 = 一切正常）
     */
    fun repairExecutable(context: Context): List<String> {
        val files = context.filesDir
        val sdk = sdkDir(context)
        val bin = binDir(context)
        // 1) 目录可穿越
        listOf(files, bin, sdk).forEach { dir ->
            if (dir.isDirectory && !dir.canExecute()) makeExecutable(dir)
        }
        // 2) 有可用 node（files/bin ELF 或 sdk/node）→ （重）建 npm/npx 入口；
        //    不覆盖 files/bin/node 真 ELF（官方 linux 构建在 Android 上会 ENOENT）
        val nodeRoot = File(sdk, "node")
        if (resolveNodeExec(files) != null) writeNodeShims(context, nodeRoot)
        // 3) 需要执行位的普通文件（files/bin + sdk 各级 bin）
        val targets = mutableListOf<File>()
        bin.listFiles()?.forEach { f -> if (f.isFile) targets += f }
        sdkBinDirs(sdk).forEach { dir -> dir.listFiles()?.forEach { f -> if (f.isFile) targets += f } }
        // 4) 一次性补执行位，setExecutable 没生效的批量回退系统 chmod
        makeExecutableAll(targets)
        return targets.filter { !it.canExecute() }.map { it.absolutePath }.distinct()
    }

    /** `files/sdk` 下各级 `bin` 目录（兼容 `sdk/node/bin` 与 `sdk/jdk/jdk-17/bin` 两种结构）。 */
    private fun sdkBinDirs(sdk: File): List<File> = buildList {
        sdk.listFiles()?.filter { it.isDirectory }?.forEach { root ->
            val direct = File(root, "bin")
            if (direct.isDirectory) {
                add(direct)
                return@forEach
            }
            root.listFiles()?.filter { it.isDirectory }?.forEach { inner ->
                val nested = File(inner, "bin")
                if (nested.isDirectory) add(nested)
            }
        }
    }

    // ------------------------------------------------------------------
    // 执行位与探测
    // ------------------------------------------------------------------

    /**
     * 关键命令入口的执行位状态（环境体检展示）。
     * 例：`node=+x  npm=+x  npx=缺执行位`——出现「缺执行位 / 非普通文件」即可定位
     * 终端里的 `Permission denied`。
     */
    fun binModesLine(context: Context): String {
        val bin = binDir(context)
        return listOf("node", "npm", "npx").joinToString("  ") { name ->
            val f = File(bin, name)
            when {
                !f.exists() -> "$name=缺失"
                !f.isFile -> "$name=非普通文件"
                f.canExecute() -> "$name=+x"
                else -> "$name=缺执行位"
            }
        }
    }

    /**
     * 真 `execve` 一次 `files/bin/node`（[binModesLine] 只看位图，看不到 SELinux 拦截），
     * 用来区分两种同名的 `Permission denied`：
     *
     *  - `ok v22.x` —— 执行链正常；
     *  - `exec失败：Permission denied` —— 入口本身/父目录缺执行位，自愈没跑到；
     *  - `exit=126 Permission denied …/sdk/…/node` —— shim 能跑、但 **node ELF** 被拒：
     *    Android 10 起禁止 targetSdk ≥ 29 的应用执行 app home 目录里的文件
     *    （W^X，SELinux 拒 `execute_no_trans`），此时再 chmod 也没用，属另一类问题。
     */
    fun execProbeLine(context: Context): String {
        val node = File(binDir(context), "node")
        if (!node.exists()) return "node=缺失"
        return "node → ${probeExec(context, node)}"
    }

    /**
     * 跑一次 `node --version`，3 秒兜底超时，返回 `ok <输出>` / `exit=N <输出>` / `exec失败：<原因>`。
     *
     * node 是 glibc ELF：Linux 环境就绪时命令被包进 proot（否则内核找不到
     * `/lib/ld-linux-aarch64.so.1`，必 exit=127）；未就绪则原样直跑，
     * 探测结果如实反映「缺 Linux 环境」这一现状。
     */
    private fun probeExec(context: Context, file: File): String = runCatching {
        val argv = Proot.wrap(context, arrayOf(file.absolutePath, "--version"), cwd = null)
        val process = ProcessBuilder(argv.toList())
            .redirectErrorStream(true)
            .apply { environment().putAll(Proot.envMap(context)) }
            .start()
        runCatching { process.outputStream.close() }
        // 先限时等待、再读输出：反过来会在进程挂住时阻塞在读上
        val done = runCatching { process.waitFor(3, TimeUnit.SECONDS) }.getOrDefault(false)
        if (!done) {
            runCatching { process.destroy() }
            runCatching { process.destroyForcibly() }
        }
        val text = runCatching { process.inputStream.bufferedReader().readText() }.getOrDefault("")
            .lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().trim()
        if (!done) return@runCatching "超时（3s 未退出）"
        val code = process.exitValue()
        when {
            code == 0 -> "ok ${text.take(24)}"
            text.isEmpty() -> "exit=$code"
            else -> "exit=$code ${text.take(90)}"
        }
    }.getOrElse { "exec失败：${it.message}" }

    /** Node 安装目录：优先 `files/sdk/node`，否则 `files/bin` 下的 ELF node。 */
    private fun resolveNode(context: Context): String? {
        val root = File(sdkDir(context), "node")
        if (File(root, "bin/node").exists()) return root.absolutePath
        val binNode = File(binDir(context), "node")
        return if (isElfBinary(binNode)) binDir(context).absolutePath else null
    }

    /**
     * 真正用来 exec 的 node 二进制。
     *
     * 官方 nodejs.org linux-arm64 依赖 `/lib/ld-linux-aarch64.so.1`，Android 上
     * execve 会报 `No such file or directory`（退出码 126）。
     * 用户放到 `files/bin/node` 的 Android/bionic ELF（约 90MB+）可以跑，必须优先用它，
     * 且 [writeNodeShims] 不得把它覆盖成指向 sdk 的 shell 包装。
     */
    fun resolveNodeExec(filesDir: File): File? {
        val binNode = File(filesDir, "bin/node")
        if (isElfBinary(binNode)) return binNode
        val sdkNode = File(filesDir, "sdk/node/bin/node")
        if (sdkNode.exists()) return sdkNode
        return if (binNode.isFile) binNode else null
    }

    fun findNpmCli(filesDir: File): File? = listOf(
        File(filesDir, "sdk/node/lib/node_modules/npm/bin/npm-cli.js"),
        File(filesDir, "lib/node_modules/npm/bin/npm-cli.js"),
    ).firstOrNull { it.isFile }

    fun findNpxCli(filesDir: File): File? = listOf(
        File(filesDir, "sdk/node/lib/node_modules/npm/bin/npx-cli.js"),
        File(filesDir, "lib/node_modules/npm/bin/npx-cli.js"),
    ).firstOrNull { it.isFile }

    /** ELF 魔数 `\x7fELF`：用来识别 `files/bin/node` 真二进制（相对 shell shim / 符号链接）。 */
    internal fun isElfBinary(file: File): Boolean {
        if (!file.exists() || file.length() < 4L) return false
        if (runCatching { Files.isSymbolicLink(file.toPath()) }.getOrDefault(false)) return false
        return runCatching {
            file.inputStream().use { ins ->
                val magic = ByteArray(4)
                if (ins.read(magic) != 4) return@use false
                magic[0] == 0x7F.toByte() &&
                    magic[1] == 'E'.code.toByte() &&
                    magic[2] == 'L'.code.toByte() &&
                    magic[3] == 'F'.code.toByte()
            }
        }.getOrDefault(false)
    }

    /**
     * 在 `files/bin` 生成 node / npm / npx 命令入口（PATH 已包含 files/bin）。
     * npm 通过**可执行的** node 直跑 CLI 脚本。
     * `files/bin/node` 已是 ELF 时不覆盖。
     */
    private fun writeNodeShims(context: Context, nodeRoot: File) {
        val bin = binDir(context)
        if (!bin.isDirectory && !bin.mkdirs()) return
        val files = context.filesDir
        val nodeExec = resolveNodeExec(files)?.absolutePath
            ?: File(nodeRoot, "bin/node").absolutePath
        val binNode = File(bin, "node")
        if (!isElfBinary(binNode)) {
            writeShim(binNode, "#!/system/bin/sh\nexec \"$nodeExec\" \"\$@\"\n")
        } else {
            // 旧 npm shebang 仍指向 sdk/node/bin/node（glibc，Android 上 ENOENT）
            relinkBrokenSdkNode(files)
        }
        val npmCli = findNpmCli(files)?.absolutePath
            ?: File(nodeRoot, "lib/node_modules/npm/bin/npm-cli.js").absolutePath
        val npxCli = findNpxCli(files)?.absolutePath
            ?: File(nodeRoot, "lib/node_modules/npm/bin/npx-cli.js").absolutePath
        writeShim(File(bin, "npm"), "#!/system/bin/sh\nexec \"$nodeExec\" \"$npmCli\" \"\$@\"\n")
        writeShim(File(bin, "npx"), "#!/system/bin/sh\nexec \"$nodeExec\" \"$npxCli\" \"\$@\"\n")
    }

    /**
     * 写 shim：内容一致则不重写（自愈会反复调用），写完**校验**执行位，
     * `setExecutable` 静默失败时由 [makeExecutable] 回退系统 `chmod`。
     *
     * 目标是符号链接时先删链接本身（避免写穿到链接指向的文件），
     * 是目录时整个删掉——**目录**被 PATH 命中同样报 `Permission denied`。
     * ELF 真二进制（如用户放入的 Android node）绝不覆盖。
     */
    private fun writeShim(file: File, body: String) {
        runCatching {
            if (isElfBinary(file)) return@runCatching
            val link = runCatching { Files.isSymbolicLink(file.toPath()) }.getOrDefault(false)
            when {
                link -> file.delete()
                file.isDirectory -> file.deleteRecursively()
            }
            if (runCatching { file.readText() }.getOrNull() != body) file.writeText(body)
            if (!file.canExecute()) makeExecutable(file)
        }
    }

    /**
     * `files/bin/node` 是可跑的 ELF 时，把 `sdk/node/bin/node` 换成指向它的符号链接。
     * 官方 linux 构建缺 `/lib/ld-linux-aarch64.so.1`，内核报 `No such file or directory`。
     */
    internal fun relinkBrokenSdkNode(filesDir: File) {
        val working = File(filesDir, "bin/node")
        if (!isElfBinary(working)) return
        val sdkNode = File(filesDir, "sdk/node/bin/node")
        val parent = sdkNode.parentFile ?: return
        if (!parent.isDirectory && !parent.mkdirs()) return
        runCatching {
            if (sdkNode.exists() && sdkNode.canonicalFile == working.canonicalFile) return@runCatching
            val path = sdkNode.toPath()
            if (Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                Files.delete(path)
            } else if (sdkNode.exists()) {
                sdkNode.delete()
            }
            Files.createSymbolicLink(path, working.toPath())
            makeExecutable(working)
        }
    }

    private fun totalSize(context: Context, uri: Uri): Long = runCatching {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L
    }.getOrDefault(0L)

    // ------------------------------------------------------------------
    // 环境体检输出（「环境体检」按钮）
    // ------------------------------------------------------------------

    /** 打印路径与可用性明细（逐行返回，由调用方写入日志流）。 */
    fun healthLines(context: Context, status: EnvStatus?): List<String> {
        val sdk = sdkDir(context)
        val jdk = status?.item(EnvKind.JDK)
        val gradle = status?.item(EnvKind.GRADLE)
        val sdkItem = status?.item(EnvKind.SDK)
        val node = status?.item(EnvKind.NODE)
        val time = SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(Date())
        return buildList {
            add("—— 环境体检 $time ——")
            add("  files         ${context.filesDir.absolutePath}")
            add("  files/bin     ${binModesLine(context)}（终端 Permission denied 看这里）")
            add("  执行探测     ${execProbeLine(context)}")
            status?.required?.let {
                add("  本项目所需   ${it.joinToString(" / ") { kind -> kind.title }.ifBlank { "无需（源码打包）" }}")
            }
            status?.extraTools?.takeIf { it.isNotEmpty() }?.let {
                add("  其它已装工具 ${it.joinToString(" / ")}（市场 / 终端安装，本项目不需要）")
            }
            add("  JDK           ${mark(jdk)} ${jdk?.path ?: "未安装"}")
            add("                bin/java = ${if (hasBin(dirOf(jdk), "java")) "存在" else "缺失"}")
            add("  Gradle        ${mark(gradle)} ${gradle?.path ?: "未安装"}")
            add("                bin/gradle = ${if (hasBin(dirOf(gradle), "gradle")) "存在" else "缺失"}")
            add("  Android SDK   ${mark(sdkItem)} ${sdk.absolutePath}")
            add("                结构检查 = ${if (sdkLooksReady(sdk)) "通过（platforms/build-tools）" else "缺失 platforms/build-tools"}")
            add("  Node.js       ${mark(node)} ${node?.path ?: "未安装"}")
            add(
                "  Linux 环境    ${mark(status?.item(EnvKind.LINUX))} " +
                    "${Proot.rootfsDir(context).absolutePath}",
            )
            val prootBin = Proot.prootBin(context)
            add(
                "                proot = " + if (prootBin.isFile) {
                    prootBin.absolutePath
                } else {
                    "缺失（终端/构建命令无法运行 glibc 程序，请在「构建环境」页安装 Linux 环境）"
                },
            )
            add("  ANDROID_HOME  ${sdk.absolutePath}")
            add("  GRADLE_USER_HOME ${gradleUserHome(context).absolutePath}")
            add("  TMPDIR        ${tmpDir(context).absolutePath}")
            if (status?.ready == true) {
                add("结论：构建环境已就绪，可以开始构建")
            } else {
                add("结论：构建环境未就绪，请在「构建环境」页在线下载或导入缺失组件")
            }
        }
    }

    private fun mark(item: EnvItem?): String = if (item?.ready == true) "[就绪]" else "[缺失]"

    private fun dirOf(item: EnvItem?): File? = item?.path?.let { File(it) }
}
