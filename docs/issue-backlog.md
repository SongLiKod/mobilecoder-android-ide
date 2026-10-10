# MobileCoder Issue Backlog

> 本文档汇总项目评审提出的问题与功能建议，每个条目均为一个独立 Issue，可直接复制到 GitHub Issues。
> 建议标签：`bug` / `tech-debt` / `enhancement` / `security` / `compatibility`
> 优先级：`P0 紧急` / `P1 高` / `P2 中` / `P3 低`

---

## 一、问题与缺陷（tech-debt / bug）

---

### Issue 1：升级 targetSdk，消除 Android 13+ 合规与兼容性风险

- **类型**：tech-debt / compatibility
- **优先级**：P0
- **涉及模块**：app / core_common

**背景**：当前 `targetSdk = 28`（README「已知限制」中说明是刻意保持）。但 Google Play 已强制新上架、更新需 targetSdk 34+，且低 targetSdk 在 Android 13+ 设备上对 `POST_NOTIFICATIONS` 等运行时权限、前台服务类型限制、文件访问等行为存在差异，长期是合规风险与兼容性隐患。

**建议方案**：
1. 将 `targetSdk` 提升到 34/35，`compileSdk` 保持 35；
2. 逐项适配新行为：Android 13 通知运行时权限（已有申请逻辑需回归验证）、Android 14 前台服务类型限制（`FOREGROUND_SERVICE_DATA_SYNC` 是否受影响）、`REQUEST_INSTALL_PACKAGES` 在低 targetSdk 下的行为差异；
3. 完成一轮真机回归（重点：终端前台服务、构建前台服务、APK 安装、应用内更新安装器、SAF 文件访问）。

**验收标准**：`targetSdk` 升级后全部现有功能在 Android 14/15 真机回归通过；文档「已知限制」同步更新。

---

### Issue 2：环境包增加 SHA-256 哈希校验，防供应链投毒

- **类型**：security
- **优先级**：P1
- **涉及模块**：feature_build（EnvDownloader / RootfsManager / ToolInstaller / MarketInstaller）

**背景**：README 4.5 明示环境包（JDK ~190MB / Gradle ~130MB / Node ~50MB / rootfs ~30MB / proot）**不做哈希校验**，只靠结构探测（`bin/java`、`bin/bash`、marker 文件、exec probe）保证完整性。这些均为可执行代码，若镜像源被篡改将直接威胁用户设备。

**建议方案**：
1. 为官方镜像（adoptium、services.gradle.org、nodejs.org、cdimage.ubuntu.com、packages.termux.dev）建立固定版本 → SHA-256 清单（在仓库内维护一份 `checksums.json` 或硬编码常量）；
2. 下载完成后、解压/安装前校验；国内镜像回退分支同样校验；
3. 校验失败给出中文提示并中止安装，不允许静默降级。

**验收标准**：每个环境组件下载后均做哈希校验；构造篡改样本时安装被正确拒绝；新增测试覆盖校验逻辑（含镜像回退路径）。

---

### Issue 3：拆分超大单文件，降低维护成本

- **类型**：tech-debt
- **优先级**：P2
- **涉及模块**：app（AppRoot.kt 61.8KB）、feature_ai（AiSettings.kt 56.5KB / AiScreen.kt 39KB / AiController.kt 35.6KB）、feature_build（BuildRunner.kt 51.5KB / BuildScreen.kt 40.3KB）、feature_editor（EditorController.kt 39.4KB / EditorComponents.kt 35.4KB / EditorScreen.kt 34KB / FileTreePanel.kt 40.4KB）

**背景**：多个源文件超过 35KB，单文件内聚合了过多职责（如 AppRoot 同时承载导航 + 多个页面），diff 冲突率高、难以并行协作。

**建议方案**：
1. `AppRoot.kt`：按底部 tab 拆分导航目标文件；
2. `AiSettings.kt`：按「模型配置 / 端点管理 / 工具开关 / 导入导出」拆分；
3. `BuildRunner.kt`：将状态机、日志解析、错误定位、内存看门狗拆成独立文件；
4. 拆分时保持公开 API 与外部行为不变，纯重构。

**验收标准**：拆分后编译通过、全部 204 个单测通过；对外状态与 UI 行为无任何变化（纯移动代码）。

---

### Issue 4：Flutter 模板与构建能力不对齐

- **类型**：bug（体验缺口）
- **优先级**：P2
- **涉及模块**：core_storage（ProjectScaffold）、feature_build

**背景**：项目模板支持 7 种脚手架（含 Flutter），但「环境中心」没有 Flutter SDK 安装项，构建也没覆盖 Flutter 项目类型（BuildRunner 目前分派 Gradle / npm / 进程内源码打包三类）。用户可创建 Flutter 工程却无法构建，存在体验落差。

**建议方案**（二选一）：
- **A**：在环境中心增加 Flutter SDK 下载项（含镜像回退），BuildRunner 增加 Flutter 构建路径；
- **B**：若短期不做 Flutter 构建，在 Flutter 模板创建时明确提示「当前暂不支持 Flutter 构建」，避免误导。

**验收标准**：采用方案 A 时可完成 Flutter 项目构建；采用方案 B 时模板页出现明确提示文案（需补文案单测）。

---

### Issue 5：AI search_replace 增加模糊匹配兜底，降低工具失败率

- **类型**：enhancement（体验优化）
- **优先级**：P3
- **涉及模块**：feature_ai（AiTools / AiDiff）

**背景**：`search_replace` 要求 `old_str` 与文件内容逐字符一致，但 LLM 生成文本常存在细微差异（多余空格、换行风格、转义），导致替换频繁失败需要模型自我纠错重试，浪费 token 与轮次。

**建议方案**：
1. 当精确匹配失败时，尝试归一化匹配：把候选字符串与文件内容做「空白归一化（连续空白折叠为单个空格）」后再匹配；
2. 若归一化后唯一命中，则按归一化后的位置执行替换；若多命中则不替换并提示模型加上下文；
3. 新增单测覆盖归一化匹配、唯一命中、多命中三种场景。

**验收标准**：构造「多空格/CRLF 与 LF 混用」的差异样本，search_replace 可自动落位完成替换；多命中场景正确拒绝并给出提示。

---

## 二、新功能建议（enhancement）

---

### Issue 6：AI 增加 `run_command` 与 git 只读工具，打通「改代码 → 跑命令 → 看结果」闭环

- **类型**：enhancement
- **优先级**：P1
- **涉及模块**：feature_ai（AiTools）、core_common（CliEngine）、feature_git

**背景**：当前 AI 智能读写文件，无法执行构建 / 测试 / 查看 git 状态，模型只能盲改，无法自我验证。

**建议方案**：
1. 新增工具 `run_command`：复用 `CliNative.exec`（proot 内执行），参数含 `command`、`cwd`、`timeout`；白名单限制（禁止 rm -rf、shutdown 等危险命令或加确认）；
2. 新增只读 git 工具：`git_status` / `git_diff` / `git_log`（复用 GitController 句柄与锁，避免与终端 Git 互斥冲突）；
3. 结果同样按 16000 字符截断；失败返回退出码与 stderr；
4. AI 提示词补充「改完代码后建议运行构建/测试验证」。

**验收标准**：AI 可在对话中执行 `gradlew test` / `git status` 等命令并基于输出继续修改；危险命令被拦截；与终端 Git 共用锁无死锁。

---

### Issue 7：可自定义代码片段（Snippets）与常用模板

- **类型**：enhancement
- **优先级**：P1
- **涉及模块**：feature_editor、core_storage

**背景**：移动端打字效率低，重复代码（for/if/Compose 模板/函数签名）手敲成本高。

**建议方案**：
1. 内置常用片段库（按语言分类：Kotlin/Java/JS/TS/HTML/Shell）；
2. 编辑器内触发：输入触发词（如 `fori` / `composable`）弹出片段面板，回车插入（带 `$N` 光标跳位）；
3. 支持用户自定义片段（增删改，持久化到 DataStore 或 `files/snippets/`），支持导入导出。

**验收标准**：内置片段可插入且光标跳位正确；自定义片段增删改后重启仍生效；新增单测覆盖片段解析与光标映射。

---

### Issue 8：模糊匹配「快速打开文件」面板（Ctrl+P 风格）

- **类型**：enhancement
- **优先级**：P1
- **涉及模块**：feature_editor、core_storage（FileRepository）

**背景**：编辑器有全项目内容检索，但缺「按文件名模糊打开」的轻量入口；移动端文件树逐级点开低效。

**建议方案**：
1. 复用 FileRepository 树遍历建立文件名索引（惰性构建 + 内存缓存，项目切换时失效）；
2. 支持：文件名模糊匹配（子序列匹配，命中按路径深度/编辑时间排序）、相对路径过滤；
3. UI 作为编辑器顶栏/悬浮按钮入口，键盘弹出时自动聚焦搜索框，选中即开 Tab。

**验收标准**：大项目索引构建不阻塞主线程；输入 2-3 字符可命中目标文件；命中列表滚动流畅；新建/删除文件后索引可失效重建。

---

### Issue 9：项目级 TODO / FIXME 扫描面板

- **类型**：enhancement
- **优先级**：P3
- **涉及模块**：feature_editor

**背景**：全项目内容检索已存在，但缺「TODO/FIXME/HACK 等标记」的一键汇总入口，不利于代码审查与遗留事项跟踪。

**建议方案**：
1. 复用 FileRepository 搜索通道，新增固定查询（`TODO|FIXME|HACK|XXX` 前缀标记）；
2. 结果按文件分组、带行号与上下文，点击跳转编辑器对应行；
3. 可在文件树/大纲页提供入口图标。

**验收标准**：扫描结果准确且跳转正确；大项目扫描有进度指示、可取消。

---

### Issue 10：Git 提交历史分支可视化图 + stash / amend 支持

- **类型**：enhancement
- **优先级**：P1
- **涉及模块**：feature_git（GitController / HistoryTab / BranchTab）、core_native（git_jni.c）

**背景**：当前历史页为线性时间线，多分支项目看不清分叉与合并关系；且缺少 `stash`（移动端切分支前暂存现场）与 `amend`（修正提交信息）这类高频操作。

**建议方案**：
1. **分支图**：基于 libgit2 revwalk + parents 关系在 Kotlin 侧建图（lane 分配），Compose Canvas 绘制提交节点连线，保留现有时间线列表交互（加载更多、详情、diff）；
2. **stash**：git_jni 暴露 stash push/pop/list/drop，UI 增加「暂存/恢复」按钮与列表页；
3. **amend**：提交详情页允许修改上次提交信息（`git commit --amend`），含交互式确认。

**验收标准**：分支图在合并/分叉场景渲染正确；stash 四操作可用且有单测；amend 后历史刷新正确；所有新 JNI 方法保持 Mutex 串行（写操作后锁外刷新 UI）。

---

### Issue 11：AI 长会话上下文管理（摘要压缩 / 自动裁剪）

- **类型**：enhancement
- **优先级**：P1
- **涉及模块**：feature_ai（AiController / AiClient）

**背景**：长对话 token 累积会击穿免费模型的上下文限制（docs「AI免费模型接入方案」已涉及免费模型），导致旧上下文被截断或费用膨胀。

**建议方案**：
1. 会话消息接近上下文上限（按模型配置的 maxContext 比例阈值）时触发压缩：
   - 将最早的 N 轮对话发送给模型生成摘要，用摘要替换原文；
   - 摘要轮次自然合并到历史，工具事件与文件快照保留关键结论；
2. 保留手动「清空上下文」与「导出会话 JSON」；压缩动作在 UI 以 chip 提示；
3. 提供 maxContext 可配置项（每模型）。

**验收标准**：超长会话压缩后继续对话不丢关键意图；摘要生成失败可安全回退为清空旧轮次；单测覆盖压缩触发阈值与回退。

---

### Issue 12：构建耗时统计与产物管理页

- **类型**：enhancement
- **优先级**：P2
- **涉及模块**：feature_build（BuildRunner）、feature_history

**背景**：弱移动设备上构建成本高（分钟级），用户需要历史数据判断「该不该现在构建」。当前 build_history.json 只记录最近 10 条，无统计维度。

**建议方案**：
1. 扩展 build_history 字段：耗时、产物大小、任务列表、项目名；
2. 「构建与运行」页增加「历史/统计」子页：最近 N 次耗时趋势（简单柱状）、平均耗时、产物大小列表，可清理历史；
3. 与「记录」页区分：记录页是统一流水，构建统计页专注构建指标。

**验收标准**：每次构建正确落库统计字段；统计页渲染正确；历史删除与已有 HistoryStore 去重逻辑不冲突。

---

### Issue 13：HTML / Web 项目实时预览窗格

- **类型**：enhancement
- **优先级**：P1
- **涉及模块**：feature_editor、feature_build（或新 feature_preview）

**背景**：模板含 Vue 3 + Vite 与静态 HTML 项目，但目前只能用终端跑 dev server 再跳浏览器，边写边看成本高。「移动端 IDE 边改边预览」是 Web 开发者的决定性体验。

**建议方案**：
1. 编辑器侧边栏增加「预览」窗格（WebView 指向本地 dev server 或静态文件）；
2. 静态 HTML：直接加载项目内 index.html（webview 允许访问项目目录）；
3. Vite：检测到 `dev` 脚本且未运行时给出「启动预览」按钮（复用 CliNative 后台起进程），文件保存后 WebView reload；
4. 预览窗格与编辑区可横竖分屏（小屏以浮层切换）。

**验收标准**：静态 HTML 改动保存后预览即时刷新；Vite 项目一键起 dev server 并加载；预览窗格在设置中可开关。

---

### Issue 14：多光标编辑（Multi-Cursor）

- **类型**：enhancement
- **优先级**：P2
- **涉及模块**：feature_editor（EditorController / EditorComponents）

**背景**：批量改同名变量/缩进时逐处修改在移动端效率极低，多光标是桌面 IDE 标配能力。

**建议方案**：
1. 基于现有单光标架构扩展：维护多选区列表，输入/删除同步作用于全部选区；
2. 触发方式：长按选择单词后「全选同词」（Alt+Enter 风格）、手动新增光标（长按空白处）；
3. 撤销历史把多光标操作视为单步；
4. 与语法高亮/折叠/自动缩进兼容（先做同词多选，不做鼠标拖拽多选）。

**验收标准**：同词多选后输入同步生效；撤销为单步；与现有字体重绘/滚动位置不冲突；新增单测覆盖多选区编辑与撤销。

---

### Issue 15：终端输出搜索与会话重命名

- **类型**：enhancement
- **优先级**：P3
- **涉及模块**：feature_terminal

**背景**：终端 2000 行回滚缓冲中找历史输出只能靠肉眼翻；多会话场景下会话标识不直观。

**建议方案**：
1. 终端增加搜索：匹配回滚缓冲中的文本，高亮命中，上下条跳转；
2. 会话列表支持重命名（默认取会话首条命令/目录名），持久化到会话元数据；
3. 搜索框不挤占命令输入行，可折叠。

**验收标准**：搜索命中准确、跳转正确；重命名后会话列表与切换页同步；搜索对 resize 后的拆行内容不丢命中。

---

### Issue 16：折叠屏/大屏分屏体验优化（编辑器 + 终端同屏）

- **类型**：enhancement
- **优先级**：P2
- **涉及模块**：app（AppRoot）、feature_editor、feature_terminal

**背景**：项目目标含折叠屏自适应，但当前编辑器与终端仍是整屏切换，折叠屏展开态的空间未被利用。

**建议方案**：
1. 宽屏（展开态 / 平板横屏 / 普通手机横屏）下支持「编辑器 | 终端」「编辑器 | Git」左右分栏；
2. 分栏宽度可拖拽调节，窄屏自动回退单栏；
3. 分栏布局下底部导航与二级页返回逻辑保持现状（分栏是页面内的增强布局）。

**验收标准**：折叠屏展开态与平板横屏出现分栏且可调宽；切换单栏/分栏状态保持各页内部状态（Tab、滚动位置）；无布局异常与状态丢失。

---

### Issue 17：带语法高亮的代码分享卡片

- **类型**：enhancement
- **优先级**：P3
- **涉及模块**：feature_editor（新组件）、app

**背景**：移动端社交场景（截图分享代码、粘贴进群聊/笔记）很常见，但语音/截图排版差。目前已有 FileExporter（导出文件），可扩展为图片导出。

**建议方案**：
1. 编辑器选中片段（或整文件）→ 用现有语法高亮 token 渲染成卡片（可配主题/行号/文件名头）→ 导出 PNG 到相册或系统分享；
2. 复用 SyntaxHighlighter 的 token 序列，不引入额外渲染库（Canvas 绘制文字）；
3. 分享入口：编辑器更多菜单「分享为图片」，长按文件树文件菜单同步提供。

**验收标准**：导出的 PNG 高亮与编辑器一致、触屏分辨率清晰可读；可分享到系统分享面板；大文件导出有长度限制提示。

---

### Issue 18：「AI 修 Bug」向导模式：构建报错 → AI 定位 → 生成 diff → 确认应用

- **类型**：enhancement
- **优先级**：P2
- **涉及模块**：feature_ai、feature_build、feature_editor

**背景**：现在构建错误可点击跳转编辑器，但定位后由用户手动修；「让 AI 接住报错」是把构建与 AI 串成闭环的最直观价值点。

**建议方案**：
1. 构建失败页增加「让 AI 修复」按钮：把错误列表 + 相关文件内容打包进 AiController；
2. AI 按既有 5 工具（必要时 + run_command）工作，产出修改；
3. AI 完成后展示「变更摘要 / diff 预览」，用户逐条确认或整体应用/回滚（复用 AiSnapshotStore 快照回滚能力）；
4. 超时与失败给出中文提示，不阻塞原构建页面。

**验收标准**：对可控的编译错误（如缺 import、大小写、未定义变量）AI 能完成修复闭环；变更应用前有 diff 预览与确认；可一键回滚。

---

## 三、汇总表

| # | 标题 | 类型 | 优先级 | 模块 |
| --- | --- | --- | --- | --- |
| 1 | 升级 targetSdk 消除合规与兼容风险 | tech-debt | P0 | app / core_common |
| 2 | 环境包 SHA-256 哈希校验 | security | P1 | feature_build |
| 3 | 拆分超大单文件 | tech-debt | P2 | 多模块 |
| 4 | Flutter 模板与构建能力对齐 | bug | P2 | core_storage / feature_build |
| 5 | AI search_replace 模糊匹配兜底 | enhancement | P3 | feature_ai |
| 6 | AI run_command 与 git 只读工具 | enhancement | P1 | feature_ai |
| 7 | 可自定义代码片段 | enhancement | P1 | feature_editor |
| 8 | 快速打开文件面板 | enhancement | P1 | feature_editor |
| 9 | TODO/FIXME 扫描面板 | enhancement | P3 | feature_editor |
| 10 | Git 分支图 + stash / amend | enhancement | P1 | feature_git |
| 11 | AI 长会话上下文管理 | enhancement | P1 | feature_ai |
| 12 | 构建耗时统计与产物管理 | enhancement | P2 | feature_build |
| 13 | HTML/Web 实时预览窗格 | enhancement | P1 | feature_editor / feature_build |
| 14 | 多光标编辑 | enhancement | P2 | feature_editor |
| 15 | 终端输出搜索与会话重命名 | enhancement | P3 | feature_terminal |
| 16 | 折叠屏/大屏分屏优化 | enhancement | P2 | app |
| 17 | 代码分享卡片 | enhancement | P3 | feature_editor / app |
| 18 | 「AI 修 Bug」向导模式 | enhancement | P2 | feature_ai / feature_build |