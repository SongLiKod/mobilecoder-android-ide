# MobileCoder 移动码匠

> Android 端一体化开发 IDE：内置终端与 Linux 运行环境（proot）、多语言代码编辑器、进程内 Git、SSH 密钥管理、Gradle 构建、软件市场与 AI 助手，可在手机 / 平板上独立完成「编写 → 构建 → 安装」的完整开发流程。

[![Build & Test Android](https://github.com/SongLiKod/mobilecoder-android-ide/actions/workflows/build.yml/badge.svg)](https://github.com/SongLiKod/mobilecoder-android-ide/actions/workflows/build.yml)
![minSdk](https://img.shields.io/badge/minSdk-29-green)
![Kotlin](https://img.shields.io/badge/Kotlin-2.0.21-blue)
![Compose](https://img.shields.io/badge/Jetpack%20Compose-Material3-blue)
![version](https://img.shields.io/badge/version-4.0.0-blue)

---

## 目录

- [1. 项目简介](#1-项目简介)
- [2. 功能特性](#2-功能特性)
- [3. 系统架构](#3-系统架构)
- [4. 核心机制详解](#4-核心机制详解)
- [5. 工程目录结构](#5-工程目录结构)
- [6. 技术栈与版本](#6-技术栈与版本)
- [7. 构建与运行](#7-构建与运行)
- [8. 测试](#8-测试)
- [9. 权限说明](#9-权限说明)
- [10. 数据存储结构](#10-数据存储结构)
- [11. 已知限制](#11-已知限制)
- [12. 版本发布与应用内更新](#12-版本发布与应用内更新)
- [13. 相关文档](#13-相关文档)
- [14. 使用声明](#14-使用声明)

---

## 1. 项目简介

**MobileCoder（移动码匠）** 是一款运行在 Android 手机 / 平板上的原生集成开发环境，目标是解决移动端开发工具「无终端、无 CLI、Git 能力弱、SSH 配置繁琐」的痛点。

一句话能力公式：

```
MobileCoder = 代码编辑器 + 内置完整终端 + proot Linux 环境
             + 进程内 Git（libgit2） + SSH 密钥管理（libssh2）
             + 移动端 Gradle 编译打包 + AI 助手 + 三模式主题系统
```

**核心设计取向**：

| 维度 | 说明 |
| --- | --- |
| 原生实现 | Kotlin + Jetpack Compose Material3，编辑器为原生 `BasicTextField`（非 WebView/Monaco），全站声明式 UI |
| 无 ROOT | 终端 / 构建全部通过 **proot（用户态 chroot）** 运行 Ubuntu 24.04 rootfs，无需 root 权限 |
| 无外部 Git 二进制 | 设备上没有 `git` 可执行文件，Git 通过 **libgit2 静态编译 + JNI** 在进程内实现 |
| 安全 | SSH 私钥、HTTPS 凭据、AI API Key 均以 **AES-256-GCM（Android Keystore）** 本地加密，私钥参与 Git 认证时全程走内存接口，不落盘 |
| 轻量依赖 | 不引入 OkHttp / Retrofit / DI 框架 / JSON 库（网络用 `HttpURLConnection`，JSON 用平台 `org.json` + 手写解析） |

**基本信息**：

| 项 | 值 |
| --- | --- |
| 包名 | `com.mobilecoder.ide` |
| 版本 | `4.0.0`（唯一来源：`gradle.properties` 的 `VERSION_NAME`） |
| 系统要求 | Android 10（API 29）及以上，手机 / 平板 / 折叠屏自适应 |
| targetSdk | 28（**刻意保持**，原因见 [11. 已知限制](#11-已知限制)） |
| 架构支持 | armeabi-v7a / arm64-v8a / x86 / x86_64 |
| 开源许可 | 无 OSS 许可证，使用条款见 [14. 使用声明](#14-使用声明) |

---

## 2. 功能特性

### 2.1 项目管理

- 项目列表、最近打开恢复、创建 / 重命名 / 删除（含磁盘已删项目自动剔除）
- **7 种项目模板脚手架**：空目录、安卓应用（完整 Gradle 工程）、Kotlin 命令行、Vue 3 + Vite、静态 HTML、Node.js、Flutter
- 从 Git URL 克隆并自动登记为项目（失败自动回滚索引）

### 2.2 代码编辑器（`feature_editor`）

- **原生 Compose 编辑器**（`BasicTextField` + `VisualTransformation`），非 WebView
- **12 种语言语法高亮**：Kotlin / Java / XML / Gradle / 属性文件 / JSON / JavaScript / TypeScript / Dart / Shell / Markdown / 纯文本；自写单次线性扫描高亮器，>20 万字符自动跳过高亮保证输入流畅
- 代码折叠（配对花括号 / 块注释，折叠偏移量正确映射光标）、行号槽、自动缩进（回车继承缩进、闭括号反缩进）
- **代码大纲 / 方法导航**：Kotlin、Java、JS/TS、Dart、Markdown 标题、HTML 结构、JSON 键、Shell 函数等启发式符号提取
- **静态代码检查**：括号配对错误、未闭合字符串 / 注释、Git 合并冲突标记检测（结构级 lint，非编译诊断）
- 多文件 Tab（脏标记 + 关闭确认）、文件树抽屉（新建 / 重命名 / 删除 / 长按拖拽移动）
- 文件内查找（5000 命中上限）+ **全项目内容检索**（点击命中跳转到行）
- 撤销 / 重做历史（每文件独立，连续打字 800ms 内合并为一步，容量 120 步）
- 自动保存（800ms 防抖）、跳转行号、双指缩放字号（10–28）、每 Tab 独立滚动位置

### 2.3 内置终端（`feature_terminal`）

- JNI `forkpty()` 创建真实 PTY，**最多 8 个并行会话**
- 首选在 **proot Ubuntu 24.04 rootfs 内运行 `/bin/bash --login`**；Linux 环境未安装时回退系统 `/system/bin/sh`（装环境期间终端照常可用）
- 自写 **VT100/xterm 终端模拟器**：完整 CSI/ESC/OSC 状态机、SGR 16 色 / 256 色 / 24 位真彩色、备用屏、滚动区、鼠标报告模式（`?1000/1006`）、DSR/DA 应答、2000 行回滚缓冲
- 会话自动绑定当前项目目录；键盘弹出时按字符测量动态 resize（不丢内容）
- 触控手势：单指滑动向 TUI 发滚轮事件、双指滑动滚动本地回滚、长按选中复制
- 历史命令 ↑ 回填、日志导出（`files/logs/`）、按键行（Esc/Tab/Ctrl/方向键等）
- 前台服务保活（`dataSync` 类型，会话存在即常驻，不持 WAKE_LOCK 以避免耗电）

### 2.4 进程内命令引擎与 Git（`core_common` + `feature_git`）

- **`CliEngine`**：进程内命令注册表 + 串行执行引擎，负责「设备上没有可执行文件」的命令；支持引号分词、协作式取消（Ctrl+C）、退出码对齐（127 未找到 / 128 fatal / 129 用法错误）
- **`git` 命令被终端拦截器接管**，由 **libgit2（静态编译进 `libmobilecoder.so`）** 执行，输出格式对齐真实 git：
  - 已实现 20+ 子命令：`status / add / restore / reset / commit / diff / log / branch / checkout / switch / remote / fetch / pull / push / merge / tag / init / clone / rev-parse / config / help`
  - `git help porcelain` 列出日常命令参考表（未实现项明确标注）
- **Git 图形界面 4 个主 Tab + 2 个子页**：
  - 变更：分组列表（已暂存 / 未暂存 / 未跟踪 / 已忽略）、提交、行级暂存 / 还原 / 删除、**全屏 Diff 查看**（libgit2 xdiff 生成 unified patch，Kotlin 解析渲染）
  - 历史：提交时间线、合并提交标记、加载更多（50 条步进）
  - 分支：新建 / 切换 / 合并 / 删除、分离 HEAD 警示、领先落后上游徽标、标签子页
  - 远程：fetch / pull / push + 进度日志面板 + 远程仓库增删改
  - 冲突处理：三方（祖先 / 我方 / 对方）整文件对照、一键采用某侧、手动编辑、中止 / 完成合并
  - 仓库设置：身份（user.name/email）、会话级忽略规则、命令参考
- 网络操作（fetch/push/clone）带进度回调，可随时取消（`GitNative.cancelNetwork()` 跨线程中止阻塞传输）

### 2.5 SSH 密钥管理（`feature_ssh`）

- **可视化生成 RSA（4096）/ Ed25519 密钥对**（JCE + BouncyCastle），公钥按 OpenSSH wire format 手工构造
- **导入已有私钥**：支持 PKCS#8 / PKCS#1、旧版 `DEK-Info` 加密 PEM、PKCS#8 v2（PBES2/PBKDF2）；拒绝 `OPENSSH PRIVATE KEY` 新格式并给出转换提示
- 私钥与口令经 **AES-256-GCM 加密**存于 `files/ssh_keys/`，不落明文、不上云；「查看私钥」需二次确认且关闭即清空，无明文导出
- 一键复制公钥 / 指纹（`SHA256:…`）、导出 `.pub`、多密钥管理、激活密钥切换、GitHub 配置页跳转指引
- **连通性测试**：libssh2 JNI 完成 TCP 连接 → 握手 → 主机密钥指纹 → 公钥认证（内存私钥），中文错误提示
- **与 Git 联动**：不走 `GIT_SSH_COMMAND`，而是 libgit2 的 **`git_credential_ssh_key_memory_new` 内存凭据回调**，激活密钥自动用于 `ssh://` 与 SCP 风格地址

### 2.6 编译构建（`feature_build`）

- Gradle 构建**整体跑在 proot rootfs 内**（glibc 版 JDK/Gradle），Debug / Release 双模式 + clean + 附加任务（白名单校验）
- **环境中心**：在线下载或本地导入 JDK 17（≈190MB）、Gradle 8.9（≈130MB）、Node 20（≈50MB）、Android SDK（cmdline-tools → sdkmanager 拉 platform-tools / android-35 / build-tools 35.0.0）、Linux rootfs（≈30MB）；官方源 / 国内镜像（清华 TUNA、npmmirror、腾讯云）双源自动回退
- **构建流水线**：工程校验 → 环境体检 → rootfs 兜底安装 → 内存参数写入 `gradle.properties` → proot 内执行 → stdout/stderr 流式日志（5000 行上限）→ 4 条正则解析编译错误（Kotlin/Java/Gradle）→ **点击错误行跳转编辑器定位** → 扫描 APK 产物
- **内存看门狗**：1.5s 采样，系统占用 ≥90% 或超用户设定上限即 SIGKILL 构建进程，防 OOM
- 前台服务 + `PARTIAL_WAKE_LOCK` 保活，锁屏 / 切后台不中断；构建记录写 `build_history.json` 并同步到「记录」页
- **APK 动作**：安装（FileProvider + 系统安装器）、分享、复制路径、打开目录
- 构建失败 / 成功通知

### 2.7 软件市场（`feature_build/Market*`）

安装到 proot guest 的一键工具目录，当前 11 项：

| 类别 | 条目 |
| --- | --- |
| 二进制包 | Node.js 22.2.0（npm 包管理基础） |
| npm | **opencode**（AI 编程 Agent CLI，依赖 Node.js） |
| apt（Ubuntu noble 源） | Git、Python 3、C/C++ 工具链（gcc/make）、tmux、ripgrep、fzf、jq、vim、fd |

- 自包含 bash 脚本在 guest 内执行，阶段标记 + curl 进度解析出 0–100% 进度条，装完以「探测目标路径存在」复核，可取消、可复制脚本重跑

### 2.8 AI 助手（`feature_ai`）

- **OpenAI 兼容端点**（`POST {baseUrl}/chat/completions`，SSE 流式），可对接 OpenAI / DeepSeek / Kimi / 通义 / 本地 Ollama（Key 留空）
- **5 个项目工具**的 function calling：`list_files`、`read_file`、`write_file`、`search_replace`、`delete_path`；最多 12 轮工具循环，工具事件在对话中以 chips 展示
- 工具安全约束：路径 canonical 化防穿越、禁删项目根、跳过 build/.git 等目录、单次结果 16000 字符截断、失败不抛异常
- 会话持久化 `files/ai/sessions.json`；API Key 加密存储；可随时取消请求

### 2.9 记录页（`feature_history`）

终端命令 / 构建 / 市场安装 / 手动录入的统一流水：来源筛选、收藏置顶（上限 500 条，按文本去重）、复制 / 编辑 / 删除（带撤销）。

### 2.10 三模式主题系统（`core_common/theme`）

- `LIGHT / DARK / SYSTEM` 三模式，切换仅做 Compose 局部重组（`uiMode` 在 `configChanges` 内），**无闪烁、不重启 Activity**
- 平台无关调色板 `AppPalette`（基础 4 色 + 终端 ANSI 8 色 + Diff 增删色 + 日志 5 级色），经 `LocalAppPalette` CompositionLocal 全站分发：编辑器高亮、终端配色、Git Diff、构建日志、弹窗同步切换
- 持久化于 DataStore `theme_mode`

### 2.11 应用内更新（`app/update`）

检查 GitHub Releases（`SongLiKod/mobilecoder-android-ide`）→ 语义化版本比较 → 按已装变体选择 debug/release APK 资产 → SHA-256 摘要校验 → `PackageInstaller` 会话安装（含用户确认页回跳）；进度可取消，403 限流 / 404 无 Release 均有中文提示。

---

## 3. 系统架构

### 3.1 模块划分（Clean Architecture + 组件化）

```
mobilecoder-android-ide
├── app                # 入口、全局导航（底栏 + 二级页）、AppState 项目上下文、设置/关于、应用内更新
│
├── feature_editor     # 代码编辑器
├── feature_terminal   # 内置终端（PTY / 模拟器 / 前台服务）
├── feature_git        # Git 图形界面 + 进程内 git CLI（GitCli/GitController）
├── feature_ssh        # SSH 密钥生成/导入/测试
├── feature_build      # Gradle 构建、环境中心、软件市场、APK 动作
├── feature_ai         # AI 对话助手与工具调用
├── feature_history    # 记录页
│
├── core_storage       # 数据持久化：DataStore、AES-GCM 加密、项目/文件/SSH/历史仓库
├── core_common        # 横切能力：主题、通用 UI、CliEngine（进程内命令引擎）、Proot（执行通道）
└── core_native        # JNI 层：NativeRuntime + Java 桥（Terminal/Git/Ssh/Cli Native）+ C 源码与第三方库
```

### 3.2 模块依赖图

```
                         ┌──────────┐
                         │   app    │  UI 壳 / 导航 / 更新
                         └────┬─────┘
        ┌──────────┬──────────┼──────────┬──────────┬──────────┬─────────┐
        ▼          ▼          ▼          ▼          ▼          ▼         ▼
  feature_editor feature_terminal feature_git  feature_ssh feature_build feature_ai feature_history
        │          │          │          │          │          │           │
        │          │          │ (唯一 feature→feature：Git 依赖 SSH 预留)
        │          │          └────┬─────┘          │          │           │
        ▼          ▼               ▼                ▼          ▼           ▼
        └──────────┴───────┬───────┴────────────────┴──────────┴───────────┘
                           ▼
              ┌────────────┼────────────┐
              ▼            ▼            ▼
        core_storage   core_common   core_native
        (DataStore、   (CliEngine、  (JNI：PTY / libgit2 /
         AES-GCM、      Proot、       libssh2 / 子进程 exec)
         项目/SSH/历史)  主题/通用UI)
              │            │
              └─────┬──────┘
                    ▼  core_storage 实现 core_common 定义的 ThemePersistence 接口（依赖倒置）
```

依赖方向要点：

- `core_common` 不依赖任何项目模块（最底层横切层）
- `core_storage → core_common`（实现主题持久化接口）
- 所有 `feature_* → core_common + core_storage`；除 `feature_git → feature_ssh`（预留路由）外，**feature 之间互不依赖**
- `app` 汇总全部模块，负责导航与全局状态

### 3.3 关键全局单例（object + StateFlow，无 DI 框架）

| 单例 | 职责 |
| --- | --- |
| `AppState` | 当前打开项目（贯穿编辑器 / 终端 / Git / 构建 / AI 的唯一项目上下文），冷启动从 `last_project_path` 恢复 |
| `AppStorage` | 存储装配入口：paths / DataStore / CryptoBox / preferences / projects / sshKeys |
| `ThemeManager` | 主题模式状态与持久化 |
| `CliEngine` | 进程内命令注册与串行执行 |
| `TerminalManager` | 终端会话池（≤8）与保活同步 |
| `GitController` | Git 全部操作（单仓库句柄 + `kotlinx Mutex` 串行化 + StateFlow 状态） |
| `SshController` / `BuildRunner` / `AiController` / `HistoryStore` / `UpdateController` | 各自领域的状态机 |

### 3.4 两条执行路径（架构核心）

```
终端输入一行命令
   │
   ▼
CommandInterceptor ── 命中拦截目标（当前仅 `git`）──► CliEngine.run()  进程内 Kotlin/libgit2
   │                                                    │ 串行 mutex、可 Ctrl+C 取消
   └── 未命中（回滚本地回显后整行写 PTY）                ▼
        │                                        输出经 emit 回终端屏幕
        ▼
   PTY (JNI forkpty) ── argv = Proot.terminalArgv()
        │                    │ 就绪 → proot -r rootfs /bin/bash --login（真 Linux 命令）
        │                    └ 未就绪 → /system/bin/sh（bionic 降级）
        ▼
   终端模拟器解析 → Compose 重绘

构建 / 市场 / 环境下载 / 探测
   │
   ▼
CliNative.exec(Proot.wrap("/bin/sh -c <cmd>"), cwd, env)   同样进入 proot rootfs
```

`Proot` 是**用户态 chroot 替身**（非虚拟机、非真 namespace 沙箱）：同路径 bind `/dev /proc /sys /system /vendor /storage /sdcard` + 应用 dataDir + 工作目录（绝不 bind `/`），`--root-id` 伪装 root 身份，并把运行时 uid/gid 同步进 guest 的 `/etc/passwd`、`/etc/group` 以避免 `groups/id` 报错。

---

## 4. 核心机制详解

### 4.1 冷启动流程

```
MobileCoderApplication.onCreate
  ├─ 崩溃留痕（写 files/crash/last_crash.txt）
  ├─ AppStorage.init + paths.ensure()（建目录、预热加密密钥与 SSH 索引）
  ├─ themeManager.restore()（DataStore 恢复主题）
  ├─ NativeRuntime.ensureProcessEnvironment()（HOME/PATH/ANDROID_HOME/GRADLE_USER_HOME）
  ├─ BuildEnvironment.repairExecutable()（files/bin、sdk/*/bin 执行位自愈）
  ├─ HistoryStore / TerminalManager / GitController / SshController / BuildRunner / AiController .init()
  └─ AppState.restore()（恢复上次项目）
MainActivity.setContent → MobileCoderTheme(mode) → AppRoot(NavHost)
```

### 4.2 导航结构

- **底部栏 5 个 tab**（iOS 风格悬浮胶囊条，键盘可见 / 全屏时自动隐藏）：`项目` · `编辑` · `终端` · `Git` · `更多`
- **二级页**（统一返回条，底栏保持来源 tab 高亮）：`构建与运行`、`AI 助手`、`SSH 密钥`、`记录`、`环境中心 / 软件市场`、`设置`、`关于`
- `编辑 / 终端 / Git / 构建 / AI` 需要项目上下文，无项目时由 `ProjectGuard` 渲染引导页
- 全局交互：Chrome 式滚动收起顶/底栏、顶栏全局 Git 分支徽标、沉浸式全屏（返回键=退出全屏）

### 4.3 终端数据流

```
输出：PTY 读线程 → TerminalCallback.onData → 字节缓冲 → 16ms 节流（主线程）
      → UTF-8 解码（carry 处理半截多字节）→ emulator.feed() → version++
      → Compose LazyColumn 重绘可见行

输入：命令输入框 submitLine → HistoryStore.add → CommandInterceptor（4 态状态机：
      IDLE/PREFIX/INTERCEPT/PASSTHROUGH，本地回显 + 词边界校验）
      ├─ 命中 → CliEngine.run（协程、串行、Ctrl+C 取消）
      └─ 未命中 → 回滚回显 → TerminalNative.write → PTY → shell
```

- **命令拦截的本质**：`git` 在 Android 上没有可执行文件，所以逐字符拦截本地回显，回车时交给进程内 libgit2 实现；`apt`、`ls` 等一律直通 rootfs 里的真实二进制
- 模拟器 resize 策略：列变窄拆行不截字、行数变少时内容进回滚缓冲（键盘弹出不丢内容，有单测覆盖）

### 4.4 Git 实现细节

- **单仓库句柄** `g_repo`（`git_jni.c` 全局）→ 所有 JNI 操作经 `GitController` 的 `Mutex + Dispatchers.IO` 串行；终端 `git` 与 Git 页**共用同一把锁**（`runExclusiveAt` 切句柄 → 执行 → 还原），写操作后的 UI 刷新必须在锁外进行（否则死锁）
- **JNI 数据交换协议**：记录 `\n` 分隔、字段 `\x01` 分隔；`boolean`=成败（错误取 `lastError()`），`int` 状态码 `0 成功 / 1 冲突 / 2 已最新 / -1 错误`
- **Diff**：libgit2 xdiff 生成标准 unified patch（`git_diff_tree_to_index` / `git_diff_index_to_workdir`）→ Kotlin `DiffParser` 解析 hunk / 行号 / 新增删除文件 → `GitDiffView` 渲染
- **凭据**：HTTPS 用加密存储的账密；SSH 用激活密钥经 `git_credential_ssh_key_memory_new` **内存认证**（CMake 强制 `HAVE_LIBSSH2_MEMORY_CREDENTIALS=1`）；操作结束 `clearCredentials()` 必定清理
- **取消**：`GitNative.cancelNetwork()` 以 pthread mutex 保护传输句柄，可从任意线程立即中止 fetch/push（终端 Ctrl+C 钩子 `CliEngine.onCancel` 即调它）
- 输出文案由纯函数 `GitText` 渲染（列宽对齐、`[origin/main: ahead 1, behind 2]`、`* [new branch] x -> origin/x` 等），便于单测锁定

### 4.5 构建流水线

```
BuildRunner.start(BuildRequest{projectDir, variant, clean, extraTasks})
  1. isGradleProject 校验（settings/build.gradle/gradlew 任一）
  2. BuildEnvironment.refresh：JDK / Gradle / SDK 路径 + 就绪探测
  3. Proot.isReady? 否 → ToolInstaller.ensureLinux() 在线装 rootfs（glibc 前置）
  4. syncGradleHeap：用户内存上限(512–8192MB, 默认2048) 写入 gradle.properties
  5. 组装 cmd = "sh ./gradlew <tasks> --no-daemon --stacktrace"
  6. BuildForegroundService.startBuild() + WAKE_LOCK
  7. CliNative.exec(Proot.wrap(sh -c cmd), cwd, env, callback)
  8. 日志行缓冲 → 分级(五级) → 4 条正则解析错误 → requestJump 跳编辑器
  9. 内存看门狗 1.5s：≥90% 系统占用或超限 → SIGKILL
 10. 退出码 0 → scanApks(**/build/outputs/apk/**/*.apk) → 通知 + build_history + HistoryStore
```

环境组件与体积（环境中心可见、可下载或 SAF 导入）：

| 组件 | 版本 | 约体积 | 来源 |
| --- | --- | --- | --- |
| JDK | Temurin 17 | ≈190MB | adoptium ↔ 清华 TUNA |
| Gradle | 8.9 | ≈130MB | services.gradle.org ↔ 腾讯云 |
| Node.js | 20.18.0（市场内 22.2.0） | ≈50MB | nodejs.org ↔ npmmirror |
| Android SDK | cmdline-tools → android-35 / build-tools 35.0.0 | 视组件 | dl.google.com |
| Linux rootfs | Ubuntu 24.04.5 base | ≈30MB | cdimage.ubuntu.com ↔ 清华 TUNA |
| proot 三件套 | proot 5.1.107 / libtalloc / libandroid-shmem | ≈140KB | packages.termux.dev |

> 环境包**不做哈希校验**，依靠结构探测（`bin/java`、`bin/bash`、marker 文件、exec probe）保证完整性；SHA-256 校验仅用于 App 自升级。

### 4.6 存储与加密

- **DataStore（Preferences）** 单例承载全部配置：项目索引、最后打开项目、Git 身份、HTTPS 凭据（加密）、编辑器/终端字号、构建变体与内存、下载源、AI 配置（加密）、SSH 索引与激活密钥、历史记录 JSON、主题
- **CryptoBox：AES-256-GCM/NoPadding**，密文格式 `0x01 | IV(12B) | ciphertext(+tag)`，Base64 存储；密钥首选 **AndroidKeystore**（别名 `mobilecoder_master_key`，不可导出），Keystore 不可用回退 DataStore 随机密钥
- 加密对象：SSH 私钥、SSH 口令、HTTPS 账密、AI API Key
- 文件操作（`FileRepository`）：树遍历（深度上限、忽略 build/.git/node_modules）、文本嗅探（扩展名白名单 + 4MB + `\0` 检测）、内容搜索（`路径:行号:内容`）

### 4.7 原生层（`core_native`）

```
core_native/src/main/cpp/
├── CMakeLists.txt        # 静态编译 mbedtls → libssh2 → libgit2，产出 libmobilecoder.so
├── jni_util.c/h          # JNI_OnLoad、线程 attach、字符串/数组转换、日志
├── terminal_jni.c        # forkpty + 读线程 + write/resize/destroy/setEnv
├── cli_jni.c             # 子进程 exec：3 组 pipe + fork，stdout/stderr 独立读线程，killProcess
├── git_jni.c             # libgit2 封装（单句柄、进度回调、跨线程 cancelNetwork）
├── ssh_jni.c             # libssh2：runtimeInit / testConnection（内存私钥认证）
└── third_party/          # libgit2 1.8.0、libssh2 1.11.1、mbedTLS 3.6.7
```

- 三个 Java 桥：`TerminalNative`、`GitNative`（≈50 方法）、`SshNative`、`CliNative`；`NativeRuntime` 用**三把独立锁**幂等初始化（git / ssh / 进程环境），避免随机数种子阻塞拖死主线程
- `proot` **不是**源码编译，而是从 Termux `.deb` 下载的外部二进制，由 Kotlin `Proot.kt` 组装命令行

### 4.8 单元测试中的关键约定（示例）

- `CliEngineCancelTest`：取消是协作式的，`onCancel` 钩子先于 `job.cancel()` 执行
- `TerminalEmulatorResizeTest`：键盘弹出时行列收缩不丢内容
- `GitControllerDeleteTargetTest`：删除路径防 `..` 逃逸
- `LiveRootfsVerificationTest`：联网时对真实 Ubuntu/Termux 源做魔数与解包断言（断网自动跳过）
- `AboutContentTest`：使用声明文案逐字锁定

---

## 5. 工程目录结构

```
mobilecoder-android-ide/
├── app/                          # 主入口（唯一 Activity）
│   └── src/main/java/com/mobilecoder/ide/
│       ├── MobileCoderApplication.kt      # 启动编排
│       ├── MainActivity.kt                # setContent + 通知授权
│       ├── AppState.kt                    # 当前项目上下文
│       ├── ui/                            # AppRoot 导航 / Home / Settings / More / About
│       └── update/                        # UpdateSource / UpdateController / UpdateInstall
├── core_common/
│   └── .../common/{cli,linux,theme,ui}    # CliEngine、Proot、主题、通用组件
├── core_native/
│   ├── src/main/java/.../nativebridge/    # NativeRuntime + Terminal/Git/Ssh/Cli Native (Java)
│   └── src/main/cpp/                      # C 源码 + CMakeLists + third_party
├── core_storage/
│   └── .../storage/                       # AppStorage/Preferences/CryptoBox/Project/File/
│                                          # History/SshKey/ThemePersistence/StoragePaths
├── feature_editor/    # 编辑器（高亮/分析/大纲/历史/组件/控制器/文件树）
├── feature_terminal/  # 终端（Session/Manager/Emulator/Interceptor/Mouse/Screen/Service）
├── feature_git/       # Git（Controller/Cli/Porcelain/Models/Text/Diff/各 Tab/Screen）
├── feature_ssh/       # SSH（KeyFactory/KeyStore 桥/Controller/Dialogs/Detail/Tester/Screen）
├── feature_build/     # 构建（Runner/Environment/Downloader/Rootfs/Market/ApkActions/Screen）
├── feature_ai/        # AI（Client/Controller/Models/Tools/Screen）
├── feature_history/   # 记录页（HistoryScreen）
├── docs/              # 需求文档 + 技术文档
├── gradle/libs.versions.toml              # 版本目录（统一版本管理）
├── .github/workflows/build.yml            # CI：单测 + 打包 + 发 Release
└── gradle.properties                      # VERSION_NAME=4.0.0（版本唯一来源）
```

---

## 6. 技术栈与版本

### 6.1 框架与工具链

| 类别 | 选型 | 版本 |
| --- | --- | --- |
| 语言 | Kotlin（主体）+ Java（JNI 桥）+ C（原生层） | Kotlin 2.0.21 |
| UI | Jetpack Compose + Material3 + Material Icons Extended | Compose BOM 2024.10.01 |
| 导航 | navigation-compose | 2.8.3 |
| 异步 | kotlinx-coroutines（Flow 状态管理） | 1.9.0 |
| 存储 | datastore-preferences | 1.1.1 |
| 构建 | AGP / Gradle Wrapper | AGP 8.7.3 / Gradle 8.9 |
| 原生 | NDK / CMake / C++17 静态链接 | NDK 26.3.11579264 / CMake 3.22.1 |
| JDK（编译） | Java 17（`sourceCompatibility 17`） | 17 |

### 6.2 第三方依赖（刻意保持精简）

| 库 | 版本 | 用途 |
| --- | --- | --- |
| `org.bouncycastle:bcprov-jdk18on` | 1.78.1 | SSH 密钥生成（RSA/Ed25519） |
| `org.tukaani:xz` | 1.9 | 解 `.deb` 的 `data.tar.xz`（proot 安装） |
| libgit2（源码静态编译） | 1.8.0 | Git 后端（`USE_SSH=libssh2`、`USE_HTTPS=mbedTLS`） |
| libssh2（源码静态编译） | 1.11.1 | Git SSH 传输 + 连接测试（`CRYPTO_BACKEND=mbedTLS`） |
| mbedTLS（源码静态编译） | 3.6.7 | TLS/加密后端（替代 OpenSSL），HTTPS 校验用内置 `cacert.pem` |

**未引入**：OkHttp/Retrofit、Hilt/Koin、Glide/Coil、任何 JSON 库（平台 `org.json` + 手写解析）、任何终端/Git 的现成 Android 库。

### 6.3 架构模式

- **Clean Architecture 分层**：UI（Compose）→ 业务（feature_* 单例 + StateFlow）→ 数据（core_storage）→ 原生（core_native/JNI）
- **状态管理**：进程级 `object` 单例 + `StateFlow` + `collectAsStateWithLifecycle()`，不使用 ViewModel/DI 框架（有意为之的轻量取向）
- **主题**：接口定义在 `core_common`、实现放在 `core_storage`（依赖倒置，保持 common 纯 Kotlin 可测）

---

## 7. 构建与运行

### 7.1 环境要求

| 依赖 | 版本 | 说明 |
| --- | --- | --- |
| JDK | 17 | Gradle 与 Kotlin 编译 |
| Android SDK | compileSdk 35 | platform 35 |
| NDK | **26.3.11579264** | `core_native` 固定 `ndkVersion`，缺失会报 "NDK not configured" |
| CMake | 3.22.1 | 随 NDK 一并安装 |
| Gradle | 8.9 | 使用 Wrapper（`./gradlew`）自动下载 |

安装 NDK/CMake：

```bash
sdkmanager "ndk;26.3.11579264" "cmake;3.22.1"
```

### 7.2 构建命令

```bash
# 单元测试（与 CI 的 test 作业一致）
./gradlew testDebugUnitTest

# 构建 Debug / Release APK
./gradlew assembleDebug assembleRelease
# 产物：app/build/outputs/apk/{debug,release}/
```

首次构建会静态编译 mbedTLS → libssh2 → libgit2，耗时较长属正常现象。

### 7.3 版本号管理

版本**唯一来源**是 `gradle.properties`：

```properties
VERSION_NAME=4.0.0
```

`versionName` 直取该值，`versionCode = 主×1e6 + 次×1e3 + 修订`（4.0.0 → 4000000），改版本只改这一行。

### 7.4 CI（`.github/workflows/build.yml`）

| 作业 | 触发 | 内容 |
| --- | --- | --- |
| `test`（30min） | push main / PR / tag / 手动 | JDK17 + NDK/CMake → `./gradlew testDebugUnitTest` |
| `android`（90min） | 同上 | 若配置 `ANDROID_KEYSTORE_BASE64` 密钥则动态追加签名配置 → `assembleDebug assembleRelease` → 上传 artifact |
| `release` | `v*` tag 或手动填 version | 下载 artifact → 重命名为 `MobileCoder-<ver>.apk` → 创建 GitHub Release（tag 含 `-` 自动标 pre-release） |

### 7.5 设备上首次使用（运行时环境）

App 内构建 / 软件市场需要一次性准备运行环境（在「环境中心」操作，可在线下载或本地导入）：

1. **Linux 环境**（必需，≈30MB + proot）：Ubuntu 24.04 rootfs + proot 三件套
2. **JDK 17 + Gradle 8.9**（构建 Android/JVM 项目）
3. **Android SDK**（sdkmanager 拉 android-35 / build-tools）
4. 按需：Node.js、软件市场内的 apt 工具

下载源支持「官方 / 国内镜像」切换，逐个候选 URL 自动回退。

---

## 8. 测试

```bash
./gradlew testDebugUnitTest
```

- **26 个测试类，204 个 `@Test` 用例**（纯 JVM 单测，JUnit4 + kotlinx-coroutines-test）
- 覆盖重点：

| 模块 | 测试 | 关注点 |
| --- | --- | --- |
| core_common | CliEngineTest / CliEngineCancelTest / ProotTest | 命令分发、取消语义、proot argv/env/bind 组装 |
| core_storage | HistoryStoreTest / ProjectScaffoldTest | 历史去重收藏、7 种模板脚手架落盘 |
| feature_editor | CodeOutlineTest / EditHistoryTest | 11 类语言符号提取、撤销合并窗口 |
| feature_terminal | CommandInterceptorTest / EmulatorResizeTest / MouseTest / KeepAliveTest | 拦截状态机、resize 不丢内容、鼠标序列、通知 ID 不冲突 |
| feature_git | GitTextTest / GitPorcelainTest / BranchFramesTest / DeleteTargetTest / RegistrationTest | 输出文案对齐真 git、帧解析兼容、路径逃逸、拦截注册 |
| feature_build | MarketInstallerTest / RootfsManagerTest / EnvDownloaderSourceTest / ArchiveExtractor* 等 8 个 | 安装脚本阶段标记、镜像回退、zip-slip 防护、真源魔数校验 |
| app | UpdateSourceTest / AboutContentTest | 手写 JSON 解析、版本比较、声明文案锁定 |

---

## 9. 权限说明

遵循最小权限原则（`app/src/main/AndroidManifest.xml` + feature 模块 Manifest 合并）：

| 权限 | 用途 |
| --- | --- |
| `INTERNET` | Git fetch/push/clone、SSH 连接、环境下载、检查更新、AI 请求 |
| `WAKE_LOCK` | 构建期间防休眠（`PARTIAL_WAKE_LOCK`，仅构建前台服务持有；终端服务刻意不持锁） |
| `POST_NOTIFICATIONS` | 构建完成 / 更新安装等任务通知（Android 13+ 运行时申请，拒绝不阻塞功能） |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_DATA_SYNC` | 构建服务（通知 ID 4207）与终端服务（4208）保活 |
| `REQUEST_INSTALL_PACKAGES` | 应用内更新与 APK 产物安装 |

组件清单：1 个 Activity（`MainActivity`，singleTask）、2 个前台 Service（Build/Terminal，均 `dataSync`、不导出）、1 个 `FileProvider`（APK 分享）、1 个 `UpdateReceiver`；无 deep link、无隐式组件。

`allowBackup=false`；网络证书校验用打包的 CA bundle（`assets/certs/cacert.pem`）。

---

## 10. 数据存储结构

```
/data/data/com.mobilecoder.ide/
├── files/
│   ├── projects/            # 用户全部项目源码（相对路径为项目唯一键）
│   ├── bin/                 # 外部 CLI（如 npm -g 安装的 opencode）
│   ├── sdk/                 # ANDROID_HOME（cmdline-tools / platforms / build-tools）
│   ├── .gradle/             # GRADLE_USER_HOME
│   ├── linux/               # proot + Ubuntu rootfs（rootfs/ bin/ lib/ marker）
│   ├── ssh_keys/            # AES-GCM 加密的 SSH 私钥（<id>.key）
│   ├── ai/sessions.json     # AI 会话
│   ├── logs/                # 终端日志导出、构建日志
│   ├── build_history.json   # 最近 10 条构建历史
│   └── crash/last_crash.txt # 崩溃留痕
├── cache/
│   ├── tmp/                 # TMPDIR（Android 无 /tmp，proot 依赖它）
│   ├── envdl/               # 环境组件下载暂存
│   ├── certs/               # 解压出的 cacert.pem
│   └── update/update.apk    # 自升级下载暂存
└── datastore/               # DataStore：主题、项目索引、SSH 索引、全部偏好设置
```

---

## 11. 已知限制

以下均为**有意的设计取舍**，修改前请先理解原因：

1. **`targetSdk` 固定为 28**
   Android 10+ 对 `targetSdk >= 29` 的应用禁止 `execve()` 执行自身 app home 目录内的文件（W^X，SELinux `execute_no_trans`），会直接打死终端、Gradle、npm（即使执行位齐全也报 `Permission denied`）。Termux 至今仍停 28 是同一原因。本项目自行分发、不上 Google Play，故 lint 关闭 `ExpiredTargetSdkVersion`。**不要"顺手升级" targetSdk。**

2. **终端 `git` 是进程内实现，非完整 git**
   已实现 20+ 常用子命令且输出 / 退出码对齐 git 2.43，但 `stash / rebase / cherry-pick / revert / submodule / worktree / gc` 等未实现（`git help porcelain` 可查参考表）。需要真 git 可在软件市场安装（guest 内 `/usr/bin/git`），终端会直通执行。

3. **SSH Ed25519 认证受后端限制**
   当前 mbedTLS 构建的 libssh2 不支持 Ed25519 公钥认证，连接测试 / 推送请使用 **RSA 密钥**；Ed25519 密钥可生成、存储、复制公钥（UI 标注「仅存储」）。

4. **SSH 主机密钥为 TOFU 策略**
   本地无 known_hosts，连接测试页展示主机密钥 `SHA256:` 指纹供人工核对；HTTPS 则完整校验 X509 证书。

5. **环境组件下载无哈希校验**
   JDK/Gradle/Node/rootfs 依靠结构探测 + exec 探针保证完整性；SHA-256 校验目前仅用于 App 自升级 APK。

6. **编辑器无替换功能、无编译级诊断**
   查找支持命中跳转但没有替换；代码检查是结构级 lint（括号/字符串/冲突标记），不做类型检查。编辑器为原生实现（技术文档早期方案中的 WebView/Monaco 未采用）。

7. **单仓库句柄**
   `git_jni.c` 全局只有一个 `g_repo`，Git 页与终端 `git` 共用一把锁串行执行；并行操作多个仓库不被支持。

8. **冲突解决为三方整文件对照**
   采用祖先/我方/对方整文件「采用其一」或手动编辑（需自行删除 `<<<<<<<` 标记），没有 hunk 级三栏并排合并器。

9. **proot 非真隔离**
   proot 是用户态 chroot 替身，不是虚拟机或 namespace 沙箱，仅用于提供 glibc 运行环境，不构成安全边界。

---

## 12. 版本发布与应用内更新

- **发布渠道**：GitHub Releases（`SongLiKod/mobilecoder-android-ide`），CI 在推送 `v*` tag 或手动触发时自动创建 Release 并上传 `MobileCoder-<ver>.apk`（含 debug/unsigned 变体）
- **应用内更新**：`关于` 页 → 检查更新 → 语义化版本比较（`3.10.0 > 3.9.0`，非字典序）→ 按已装变体选择 APK 资产 → 流式下载（进度可取消）→ `sha256:` 摘要校验 → `PackageInstaller` 会话安装
- **注意**：debug 与 release 签名不同，互不覆盖安装，需选择与已装版本匹配的资产

---

## 13. 相关文档

| 文档 | 位置 | 内容 |
| --- | --- | --- |
| 需求 + 技术文档 | [`docs/MobileCoder（移动码匠）需求文档+技术文档（MD完整版）.md`](docs/MobileCoder（移动码匠）需求文档+技术文档（MD完整版）.md) | PRD（功能需求、非功能需求）与 TECH（技术选型、主题规范、模块细则） |
| 第三方库文档 | `core_native/src/main/cpp/third_party/*/` | libgit2 / libssh2 / mbedTLS 上游 README 与许可 |

> 文档与实现存在差异时，**以代码为准**（例如编辑器实际为原生 Compose 而非 WebView/Monaco；PRD 中的 `feature_cli` 实际落地为 `core_common/cli/CliEngine` + 软件市场内的 opencode CLI）。

---

## 14. 使用声明

应用内「关于」页的使用声明为作者原文，本 README 不复制其法律文本，以应用内 `AboutContent.DISCLAIMER` 为准（由单测逐字锁定）。要点：

- 本软件为个人兴趣开发产物，仅供个人学习、娱乐、非商业性质免费使用
- 作者保留全部知识产权，未经书面许可不得二次开发、修改、复刻、衍生创作或用于任何商业营利用途
- 使用者需遵守当地法律法规与网络使用规范，违规使用的后果自行承担

---

<p align="center"><b>MobileCoder 移动码匠</b> · 让开发脱离桌面，随身进行</p>
