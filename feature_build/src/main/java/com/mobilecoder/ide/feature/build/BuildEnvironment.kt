package com.mobilecoder.ide.feature.build

import android.content.Context
import android.net.Uri
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
     * glibc 运行时（`files/sdk/glibc`）。
     * nodejs.org / Adoptium / gradle 官方包都是 glibc 程序，Android 只有 bionic，
     * 内核 exec 时找不到 `/lib/ld-linux-aarch64.so.1` → ENOENT(2)、退出码 127。
     */
    GLIBC("glibc 运行时"),
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
    /** 当前项目**需要**的组件（按项目类型动态推断，未识别的工程按安卓处理）。 */
    val items: List<EnvItem>,
    val required: List<EnvKind> = items.map { it.kind },
) {

    /** 所需组件全部就绪才算构建环境可用。 */
    val ready: Boolean get() = items.isNotEmpty() && items.all { it.ready }

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

    /** glibc 版 LD_PRELOAD 钩子在运行时包里的文件名（与 loader 同目录）。 */
    private const val GLIBC_HOOK_NAME = "mcexechook-glibc.so"

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

    /** `files/sdk/glibc` —— glibc 运行时根目录（loader 与 libc 都在这里）。 */
    fun glibcDir(context: Context): File = File(sdkDir(context), "glibc")

    /**
     * 定位 glibc loader（`ld-linux-aarch64.so.1` / `ld-linux-x86-64.so.2`）。
     *
     * 不写死路径：Debian 包解出来是 `lib/<三元组>/ld-linux-*.so.1`，
     * 自己打的包是 `lib/ld-linux-aarch64.so.1`，两种布局都要认。
     * 符号链接会解析到真实 ELF 再判断（包里 loader 常是链接）。
     *
     * @return loader 的真实文件；运行时未安装返回 null
     */
    fun glibcLoader(root: File): File? {
        if (!root.isDirectory) return null
        val queue = ArrayDeque<Pair<File, Int>>()
        queue.add(root to 0)
        while (queue.isNotEmpty()) {
            val (dir, depth) = queue.removeFirst()
            dir.listFiles()?.forEach { child ->
                if (child.isFile && child.name.startsWith("ld-linux")) {
                    val real = runCatching { child.canonicalFile }.getOrDefault(child)
                    if (isElfBinary(real)) return real
                }
                if (child.isDirectory && depth < 5) queue.add(child to depth + 1)
            }
        }
        return null
    }

    /** glibc 运行时是否已安装（loader 找得到）。 */
    fun glibcReady(root: File): Boolean = glibcLoader(root) != null

    /**
     * bionic 版 exec 钩子（APK 里打包的 `libmcexechook.so`，见 `mc_exec_hook.c`）。
     *
     * 钩子是**按 ABI 成对**的：bionic 进程只能 preloaded bionic 版，
     * glibc 进程只能 preloaded 随运行时下发的 glibc 版，混用会被
     * bionic linker 直接判 `CANNOT LINK EXECUTABLE`（glibc 侧则是
     * `ld.so` 打一行 `cannot be preloaded` 到 stderr 并忽略）。
     *
     * 前提：app/build.gradle.kts 里 `packaging.jniLibs.useLegacyPackaging = true`，
     * 否则 .so 不解压、[Context.applicationInfo.nativeLibraryDir] 为空目录。
     *
     * @return .so 的绝对路径；未打包（理论外）返回 null
     */
    fun bionicHook(context: Context): String? = runCatching {
        File(context.applicationInfo.nativeLibraryDir, "libmcexechook.so")
            .takeIf { it.exists() }
            ?.absolutePath
    }.getOrNull()

    /**
     * glibc 版 exec 钩子（`glibc-<版本>-<arch>.tar.gz` 解出来的，
     * 与 loader 同目录，见 `tools/glibc-runtime/build.sh`）。
     *
     * @return .so 的绝对路径；运行时未装 / 旧包没有该文件返回 null
     */
    fun glibcHook(context: Context): String? =
        glibcLoader(glibcDir(context))?.parentFile?.let { dir ->
            File(dir, GLIBC_HOOK_NAME).takeIf { it.exists() }?.absolutePath
        }

    /**
     * 直接目标 [target] 应该用的 `LD_PRELOAD`（未就绪 / 找不到对应钩子返回 null）。
     *
     * 阶段 2 的核心约定：**按目标 ABI 切换**。glibc 目标只认 glibc 版钩子，
     * 其余（bionic ELF、shell 脚本 → `/system/bin/sh`、非 ELF）只认 bionic 版；
     * 交叉的那一步由钩子自己在 exec 时改写，Kotlin 侧不猜子进程的子进程。
     */
    fun preloadFor(context: Context, target: File?): String? =
        if (target != null && GlibcCompat.kind(target) == InterpKind.GLIBC) {
            glibcHook(context)
        } else {
            bionicHook(context)
        }

    /**
     * glibc 运行时的**子进程环境变量**（未就绪返回空数组）。
     *
     * Android 没有 `/lib`，glibc 程序靠 `LD_LIBRARY_PATH` 找 `libc.so.6`；
     * 该目录下的 soname（`libc.so.6` / `libdl.so.2` …）与 bionic（`libc.so` / `libdl.so` …）
     * 不同名，所以同一份 PATH 上的 bionic 程序不受影响。
     *
     * 其余变量供 LD_PRELOAD 钩子（`mc_exec_hook.c`）使用：
     * `MOBILECODER_GLIBC` 定位运行时、`MOBILECODER_GLIBC_LIB` 直接给库目录、
     * `MOBILECODER_*_HOOK` 是两套 ABI 的钩子路径（exec 时按子目标二选一）。
     */
    fun glibcEnv(context: Context): Array<String> {
        val root = glibcDir(context)
        val lib = glibcLoader(root)?.parentFile ?: return emptyArray()
        return buildList {
            add("MOBILECODER_GLIBC=${root.absolutePath}")
            add("MOBILECODER_GLIBC_LIB=${lib.absolutePath}")
            add("LD_LIBRARY_PATH=${lib.absolutePath}")
            glibcHook(context)?.let { add("MOBILECODER_GLIBC_HOOK=$it") }
            bionicHook(context)?.let { add("MOBILECODER_BIONIC_HOOK=$it") }
        }.toTypedArray()
    }

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
        val required = withGlibcIfNeeded(context, requirementsFor(projectDir))
        val status = EnvStatus(items = all.filter { it.kind in required }, required = required)
        _status.value = status
        // JAVA_HOME 同步注入进程环境，供终端 / CLI 子进程继承（与项目类型无关）
        runCatching {
            NativeRuntime.setJavaHome(context, all.firstOrNull { it.kind == EnvKind.JDK }?.path)
        }
        // glibc 运行时同理：终端 PTY 与 CLI 子进程都要能拿到 LD_LIBRARY_PATH 与钩子路径
        runCatching {
            NativeRuntime.setGlibcEnv(
                context,
                root = glibcDir(context).takeIf { glibcReady(it) }?.absolutePath,
                lib = glibcLoader(glibcDir(context))?.parentFile?.absolutePath,
                glibcHook = glibcHook(context),
                bionicHook = bionicHook(context),
            )
        }
        return status
    }

    /**
     * 把「需要 glibc 运行时」的组件并入「所需组件」：
     *
     * 1. **需要 NODE 且 node 是官方 glibc 构建** —— 不并入的后果是 node「文件在 +
     *    有执行位」被体检判成就绪，真跑 `npm i -g` 才炸出 127，而「构建环境」页
     *    什么都不缺、看不出该装什么；
     * 2. **需要 JDK** —— Temurin / gradle daemon 同为 glibc ELF。纯安卓 / JVM 工程
     *    没有 node，以前不并入 → `java` 照样 127，页面却只显示「缺 JDK」。
     *    java 还没安装时按「将安装的是官方 glibc 构建」预判并入；已安装则按真实
     *    ELF 判断（将来若有非 glibc 构建则不并入）。
     *
     * @return 追加后的所需组件（无需追加则原样返回）
     */
    internal suspend fun withGlibcIfNeeded(context: Context, required: List<EnvKind>): List<EnvKind> {
        if (EnvKind.GLIBC in required) return required
        // 1) node：官方 glibc 构建（解析不出 node 时交给下面的 JDK 分支判断）
        if (EnvKind.NODE in required) {
            val node = resolveNodeExec(context.filesDir)
            if (node != null && GlibcCompat.kind(node) == InterpKind.GLIBC) return required + EnvKind.GLIBC
        }
        // 2) JDK：Temurin / gradle daemon 同为 glibc ELF。纯安卓 / JVM 工程没有 node，
        //    以前不并入 → `java` 照样 127，页面却只显示「缺 JDK」。
        if (EnvKind.JDK in required) {
            val java = resolveJdk(context)?.let { File(it, "bin/java") }
            if (java?.exists() != true || GlibcCompat.kind(java) == InterpKind.GLIBC) {
                return required + EnvKind.GLIBC
            }
        }
        return required
    }

    /**
     * 按当前项目类型推断**需要**哪些环境（「构建环境」页据此动态展示与检查）。
     *
     * - 安卓 Gradle 工程 → JDK + Gradle + Android SDK
     * - 纯 JVM/Kotlin Gradle 工程 → JDK + Gradle
     * - Node/Vue 工程（package.json）→ Node.js
     * - 未识别 → 按安卓工程处理
     */
    fun requirementsFor(projectDir: File?): List<EnvKind> {
        if (projectDir == null) return listOf(EnvKind.JDK, EnvKind.GRADLE, EnvKind.SDK)
        val hasGradle = File(projectDir, "gradlew").exists() || listOf(
            "settings.gradle",
            "settings.gradle.kts",
            "build.gradle",
            "build.gradle.kts",
        ).any { File(projectDir, it).exists() }
        return when {
            hasGradle && isAndroidProject(projectDir) -> listOf(EnvKind.JDK, EnvKind.GRADLE, EnvKind.SDK)
            hasGradle -> listOf(EnvKind.JDK, EnvKind.GRADLE)
            File(projectDir, "package.json").exists() -> listOf(EnvKind.NODE)
            else -> listOf(EnvKind.JDK, EnvKind.GRADLE, EnvKind.SDK)
        }
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
        val required = withGlibcIfNeeded(context, requirementsFor(projectDir))
        return EnvStatus(items = all.filter { it.kind in required }, required = required)
    }

    /** 全量检测（不做项目过滤）。 */
    private suspend fun detect(context: Context): List<EnvItem> {
        val jdk = resolveJdk(context)
        val gradle = resolveGradle(context)
        val node = resolveNode(context)
        val sdk = sdkDir(context)
        val sdkReady = sdkLooksReady(sdk)
        val glibc = glibcDir(context)
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
                path = node,
                ready = node != null,
                hint = "未就绪：点「在线下载」安装 Node.js 20（含 npm，约 50MB），或导入 node-v*-linux-*.tar.gz",
            ),
            EnvItem(
                kind = EnvKind.GLIBC,
                path = if (glibc.isDirectory) glibc.absolutePath else null,
                ready = glibcReady(glibc),
                hint = "未就绪：官方 Node.js / JDK 链的是 glibc，Android 缺 /lib/ld-linux-aarch64.so.1，" +
                    "exec 时直接 ENOENT（退出码 127）。点「在线下载」安装 glibc 运行时，" +
                    "或导入 glibc-*-linux-*.tar.gz",
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
            EnvKind.GLIBC -> relocateGlibc(tmp, File(sdk, "glibc"))
            EnvKind.SDK -> mergeIntoSdk(tmp, sdk, requireReady)
        }
        // 修复可执行权限（gradlew / java / gradle / node 都是脚本或 ELF）
        fixExecutable(File(target, "bin"))
        // loader 由内核直接 open，缺执行位同样报 ENOENT，单独补一次
        if (kind == EnvKind.GLIBC) glibcLoader(File(target))?.let { makeExecutable(it) }
        when (kind) {
            EnvKind.JDK -> {
                runCatching { AppStorage.preferences.setJdkPath(target) }
                runCatching { NativeRuntime.setJavaHome(context, target) }
            }
            EnvKind.GRADLE -> runCatching { AppStorage.preferences.setGradlePath(target) }
            EnvKind.NODE -> writeNodeShims(context, File(target))
            EnvKind.GLIBC -> Unit
            EnvKind.SDK -> Unit
        }
        // 落位完成 → 把已有的官方 Linux 包解释器改写到本机 loader（幂等，未装运行时是空操作）
        patchGlibcInterps(context)
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

    /**
     * glibc 运行时包落位：按「哪里有 `ld-linux*`」认根目录，而不是固定 marker——
     * Debian 的 `libc6_*_arm64.deb` 解出来是 `lib/aarch64-linux-gnu/ld-linux-aarch64.so.1`，
     * 自己打的包是 `lib/ld-linux-aarch64.so.1`，两种都要能装。
     */
    private fun relocateGlibc(tmp: File, dest: File): String {
        val root = findGlibcRoot(tmp, 5)
            ?: throw IllegalStateException(
                "压缩包结构不匹配：未找到 glibc loader（ld-linux*），请选择 glibc 运行时压缩包",
            )
        if (root.canonicalPath == dest.canonicalPath) return dest.absolutePath
        if (dest.exists()) dest.deleteRecursively()
        dest.parentFile?.mkdirs()
        if (!root.renameTo(dest)) {
            root.copyRecursively(dest, overwrite = true)
        }
        return dest.absolutePath
    }

    /** 深度优先查找含 glibc loader 的目录（depth ≤ [maxDepth]）。 */
    private fun findGlibcRoot(dir: File, maxDepth: Int): File? {
        if (maxDepth < 0) return null
        if (glibcLoader(dir) != null) return dir
        dir.listFiles()?.forEach { child ->
            if (child.isDirectory) findGlibcRoot(child, maxDepth - 1)?.let { return it }
        }
        return null
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
     * 终端里直接敲 `node -v` **不经过** `apt tools install`，此前只有安装流程会补执行位：
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
    // glibc 解释器改写
    // ------------------------------------------------------------------

    /**
     * 把 `files` 下「官方 Linux 发行包」的 glibc ELF 解释器改写到本机 loader（幂等）。
     *
     * 覆盖 `sdk/node`（含 npm 装进 `lib/node_modules` 的原生二进制）、`files/bin`
     * （npm 的命令入口与用户手放的 ELF）、`sdk/jdk`、`sdk/gradle`。
     * glibc 运行时未安装时是空操作——先有 loader 才谈得上改写。
     *
     * 详见 [GlibcCompat]：内核只按 `p_offset + p_filesz` 读解释器，
     * 所以把新路径**追加到文件末尾**再改两个字段即可，不移动任何已有段。
     *
     * @return 成功改写的文件数（0 = 无需改写 / 运行时未就绪）
     */
    fun patchGlibcInterps(context: Context): Int {
        val loader = glibcLoader(glibcDir(context))?.absolutePath ?: return 0
        val files = context.filesDir
        val roots = listOf(
            File(files, "sdk/node"),
            File(files, "bin"),
            File(files, "lib/node_modules"),
            File(files, "sdk/jdk"),
            File(files, "sdk/gradle"),
        )
        return roots.sumOf { root -> GlibcCompat.rewriteTree(root, loader) }
    }

    /**
     * 关键命令入口的执行位状态（`apt doctor` / 环境体检展示）。
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
        return "node → ${probeExec(node)}"
    }

    /** 跑一次 `file --version`，3 秒兜底超时，返回 `ok <输出>` / `exit=N <输出>` / `exec失败：<原因>`。 */
    private fun probeExec(file: File): String = runCatching {
        val process = ProcessBuilder(file.absolutePath, "--version")
            .redirectErrorStream(true)
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
    // 环境体检输出（「环境体检」按钮 / `apt doctor` 风格）
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
                add("  本项目所需   ${it.joinToString(" / ") { kind -> kind.title }}")
            }
            add("  JDK           ${mark(jdk)} ${jdk?.path ?: "未安装"}")
            add("                bin/java = ${if (hasBin(dirOf(jdk), "java")) "存在" else "缺失"}")
            add("  Gradle        ${mark(gradle)} ${gradle?.path ?: "未安装"}")
            add("                bin/gradle = ${if (hasBin(dirOf(gradle), "gradle")) "存在" else "缺失"}")
            add("  Android SDK   ${mark(sdkItem)} ${sdk.absolutePath}")
            add("                结构检查 = ${if (sdkLooksReady(sdk)) "通过（platforms/build-tools）" else "缺失 platforms/build-tools"}")
            add("  Node.js       ${mark(node)} ${node?.path ?: "未安装"}")
            if (status?.required?.contains(EnvKind.GLIBC) == true) {
                val groot = glibcDir(context)
                val loader = glibcLoader(groot)
                add("  glibc 运行时  ${mark(status?.item(EnvKind.GLIBC))} ${groot.absolutePath}")
                add("                loader = ${loader?.absolutePath ?: "缺失（glibc 程序 exec 必 ENOENT）"}")
            }
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
