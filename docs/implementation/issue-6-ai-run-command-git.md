# Issue 6 实施记录：AI `run_command` 与只读 git 工具

> 对应 `docs/issue-backlog.md` 的 **Issue 6**（P1 / enhancement）。
> 打通「**改代码 → 跑命令 → 看结果**」闭环：模型改完文件后可以自己跑构建 / 测试、
> 看 git 状态，再据输出继续修改，而不是盲改。

---

## 0. 目标与边界

**目标**：

1. 新增 `run_command`：在项目目录里执行一条命令（复用 `CliNative.exec`，Linux 环境就绪时整条命令包进 proot）；
2. 新增只读 git 工具 `git_status` / `git_diff` / `git_log`；
3. 结果按 16000 字符截断，失败回传**退出码 + stderr**；
4. system prompt 补充「改完代码后建议运行构建 / 测试验证」。

**明确不做**：

- ❌ 不做通用「写类 git 工具」（commit / push / checkout…）：写操作仍由 Git 页与终端负责，AI 侧只读；
- ❌ 不做白名单 UI / 逐条命令审批设置：先用「危险命令硬拦截 + `run_command` 恒需确认」这一组合（见 §4）；
- ❌ 不复用 `BuildRunner`：它是带前台服务 / 状态机 / 产物收集的构建页专用管线，AI 只需要一条一次性命令通道。

---

## 1. 改动文件总览

| 文件 | 改动类型 | 说明 |
|---|---|---|
| `core_common/.../cli/CliEngine.kt` | 修改 | 新增 `CliCapture` + `runCaptured()`：带超时与截断的**捕获式执行**（与终端共用同一把引擎锁） |
| `feature_ai/.../AiCommandGuard.kt` | 新增 | `run_command` 危险命令拦截（纯逻辑，JVM 可测） |
| `feature_ai/.../AiCommandRunner.kt` | 新增 | 命令执行通道：进程内命令走 CliEngine，其余走 `Proot.wrap` + `CliNative.exec` |
| `feature_ai/.../AiTools.kt` | 修改 | 新增 4 个工具 schema 与实现；`execute` 改 `suspend`；新增 `needsConfirm` / `opensFile` / `targetOf`；结果改「头尾保留」截断 |
| `feature_ai/.../AiController.kt` | 修改 | 初始化执行通道；`run_command` 恒需确认；确认弹窗 / 事件行改用 `targetOf`；system prompt 补验证纪律 |
| `feature_ai/.../AiModels.kt`、`AiMessageUi.kt`、`AiScreen.kt`、`AiSettings.kt` | 修改 | 工具名清单、事件行「可点开编辑器」判定（命令 / git 结果不是文件路径）、确认开关文案 |
| `feature_ai/build.gradle.kts`、`gradle/libs.versions.toml` | 修改 | 增加 `core_native` 依赖；单测用真实 `org.json`（android.jar 里是 mockable 桩） |
| `core_common/.../CliEngineCaptureTest.kt`、`feature_ai/.../AiCommandGuardTest.kt`、`AiToolsCommandTest.kt` | 新增 | 单测（见 §6） |

**模块关系**：`feature_ai → core_native`（CliNative）是本次唯一新增的模块依赖；git 能力**没有**直接依赖 `feature_git`，
而是经 `CliEngine` 分发到 GitCli 注册的 `git` 命令（见 §3.2）。

---

## 2. `run_command`

参数：`command`（必填）、`cwd`（相对项目根，默认项目根）、`timeout`（秒，默认 300、上限 1800）。

执行顺序（`AiTools.runCommand`）：

1. **cwd 沙箱校验** → 复用 `AiTools.resolve`，canonical 化后必须仍在项目根内；
2. **危险命令拦截** → 命中即 `ok=false`，回传中文原因（不启动任何进程）；
3. **执行**（`AiCommandRunner`）：
   - 命令首词是进程内注册命令（`CliEngine.isInProcessLine`，目前是 `git`）→ `CliEngine.runCaptured`；
   - 否则 `/bin/sh -c <command>` 经 `Proot.wrap` 包进 proot → `CliNative.exec`，stdout / stderr 分开捕获，
     `withTimeoutOrNull` 超时或协程取消（用户点停止）时 `killProcess(pid, 9)` 回收整个进程组；
4. **组装结果**：`命令 / 工作目录 / 退出码 / [stderr] / [stdout]`，失败时 `ok=false` 且退出码与 stderr 一并回传模型。

子环境变量与构建页同口径（`HOME` / `TMPDIR` / `PATH=files/bin + guest 标准路径` / `LANG` / `ANDROID_HOME` /
`GRADLE_USER_HOME` / `JAVA_HOME` / `Proot.envEntries`）。其中 `JAVA_HOME` 由 `AiCommandRunner.resolveJavaHome`
按「用户配置 → `files/sdk/jdk` → `files/sdk` 下含 `bin/java` 的目录」解析——`./gradlew` 没有 JAVA_HOME 会直接报错。

Linux 环境未安装时命令照常执行（bionic `/system/bin` 兜底），但结果里附一句提示，
避免模型对着 `command not found` 反复重试。

---

## 3. 只读 git 工具

### 3.1 参数 → 命令行

| 工具 | 参数 | 实际执行 |
|---|---|---|
| `git_status` | `short?` | `git status` / `git status --short` |
| `git_diff` | `staged?`、`stat?` | `git diff [--staged] [--stat]` |
| `git_log` | `limit?`（默认 20） | `git log -n <limit>` |

命令行由 `AiTools.gitLine` 统一组装，**展示与执行共用**：确认弹窗 / 事件行里看到的命令就是实际执行的命令。

### 3.2 为什么走 `CliEngine` 而不是直接调 `GitCli`

- 设备上没有 `git` 可执行文件，交给 shell 只会得到 `command not found`；
- `feature_git` 在 `GitController.init` 里把 `git` 注册进了 `CliEngine`（终端拦截器就是这条路），
  AI 直接复用它，**不新增 `feature_ai → feature_git` 依赖**；
- 锁序天然一致：两处都是「`CliEngine` 引擎锁 → `GitController` 仓库锁」，
  因此与终端里的 `git …` 串行执行、**不会死锁**；引擎锁在 `withLock` 的 `finally` 里释放，
  超时 / 取消也不会把锁留下（`CliEngineCaptureTest` 专门验证这一点）。

---

## 4. 安全：危险命令拦截 + 恒需确认

`AiCommandGuard.rejectReason(command, cwd, root)`：按 `;` / `&&` / `||` / `|` 拆段，
取每段**命令位**（跳过 `VAR=1`、`sudo`、`env` 等前缀）判定，命中即拒绝：

- 系统级命令：`shutdown` / `reboot` / `poweroff` / `init` / `mkfs*` / `fdisk` / `parted` / `su` …
- `rm` 指向高危目标：`.`、`/`、`*`、`.git`、`~`、`$HOME`，或**项目根之外**的绝对路径
  （proot 里 `/sdcard`、`/data` 同路径 bind 了真实数据）；项目内绝对路径放行；
- 破坏性 git：`reset --hard`、`clean -f`、`push --force`、`branch -D`、`filter-branch`、`checkout --`、`restore <path>`；
- 越权写：`>` 重定向到项目外绝对路径（`/dev/null`、`/proc`、`/tmp` 与项目内路径放行）；
- 其他：fork 炸弹、`curl … | sh`、`dd of=/dev/*`、`kill -9 1`、终止应用自身进程。

由于按**命令位**判定，`git commit -m "rm -rf /"` 这类把危险文本当参数的命令不会被误杀；
按段拆分则让 `ls && rm -rf /` 无法绕过。

确认策略：`run_command` 与 `delete_path` 一样**恒需用户确认**（与「写入前确认」开关无关）——
命令可以绕过文件工具直接改动项目外的绑定目录，风险不低于删除；git 只读工具不确认。

---

## 5. 结果截断

沿用 16000 字符上限，但截断方式从「只留头部」改为「**头 3/4 + 标注 + 尾 1/4**」（`AiTools.clip`）：
构建 / 测试日志的结论在结尾，纯 `take` 会把 stderr 的报错截掉。命令结果里 stdout 与 stderr
各自还有 12000 / 3000 字符的独立预算，保证两路输出都能进上下文。

---

## 6. 单测

| 测试 | 覆盖 |
|---|---|
| `CliEngineCaptureTest`（core_common） | 捕获输出与退出码；超时返回 124 **且释放引擎锁**（后续命令仍可执行，无死锁）；超长截断标注；未注册命令 127 |
| `AiCommandGuardTest` | 20 条危险命令逐条拦截；多段 / `sudo` / 环境变量前缀绕过无效；14 条常见构建测试命令放行；引号内危险文本不误杀 |
| `AiToolsCommandTest` | schema 含 4 个新工具与 `required`；确认 / 快照 / 可点开分类；命令行组装；危险命令经 `execute` 被拦；cwd 越界拒绝；git 工具经命令引擎执行与失败回传 |
| `AiSystemPromptAndDiffTest`（新增 1 条） | 默认提示词包含「构建 / 测试验证」与只读 git 工具纪律 |

新增用例 20 条（`CliEngineCaptureTest` 4 + `AiCommandGuardTest` 5 + `AiToolsCommandTest` 10 + 提示词 1），全部通过；`feature_ai` / `core_common` 既有用例无回归。

> `org.json` 在 android.jar 里是 mockable 桩，因此给 `feature_ai` 加了 `testImplementation(libs.org.json)`
> （仅单测使用），才能在 JVM 上解析工具参数与 schema。

运行：

```bash
sh gradlew --offline :core_common:testDebugUnitTest :feature_ai:testDebugUnitTest
```

---

## 7. 验收对照

| 验收标准 | 结果 |
|---|---|
| AI 可在对话中执行 `gradlew test` / `git status` 等命令并基于输出继续修改 | ✅ `run_command`（proot + CliNative，含 JAVA_HOME / GRADLE_USER_HOME）与 `git_status`（进程内 libgit2）均已接通，system prompt 要求改完即验证 |
| 危险命令被拦截 | ✅ `AiCommandGuard` 硬拦截 + `run_command` 恒需确认；命中时回传中文原因，模型可换写法重试 |
| 与终端 Git 共用锁无死锁 | ✅ 走 `CliEngine` 同一把引擎锁，锁序与终端一致（引擎锁 → Git 锁）；超时 / 取消路径的锁释放有单测覆盖 |

---

## 8. 风险与后续

- **确认疲劳**：`run_command` 每次都弹确认。若后续要做「常用命令自动放行」，可在设置里加白名单
  （如 `./gradlew *`、`npm test`），并保留硬拦截兜底；
- **长任务**：`gradlew test` 在手机上可能跑数分钟，默认 300s 超时可通过 `timeout` 参数放宽到 1800s，
  期间用户可随时点停止（协程取消 → SIGKILL 进程组）；
- **进程内命令覆盖面**：目前只有 `git` 注册在 `CliEngine`，其余命令一律走 shell；
  若后续 `feature_ssh` / `feature_build` 注册更多进程内命令，`run_command` 会自动复用（无需改动）。
