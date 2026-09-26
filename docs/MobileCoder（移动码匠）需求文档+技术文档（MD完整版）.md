# MobileCoder（移动码匠）需求文档\+技术文档（MD完整版）

# 一、产品需求文档 PRD\.md

## 1\. 项目概述

### 1\.1 项目名称

**MobileCoder 移动码匠**

一款 Android 端轻量化、一体化全功能开发 IDE，解决目前移动端开发工具功能残缺、无终端、无 CLI、Git 能力弱、SSH 配置繁琐、桌面 IDE 臃肿卡顿的行业痛点。

### 1\.2 产品定位

面向移动端开发者、随身开发者、学生开发者，打造**手机/平板端可独立完成 Android 项目编写、编译、CLI 构建、版本管理、SSH 免密开发**的一站式开发工具。

### 1\.3 设计理念（扬长避短）

整合市面主流工具优点：

- 继承 Android Studio 安卓原生编译能力

- 继承 VSCode 轻量化、流畅编辑体验

- 继承 Termux 完整终端环境

- 继承 GitKraken / SourceTree 可视化 Git 与 SSH 管理

- 继承 OpenCode 轻量化 CLI 工程化工具链

规避市面工具缺点：

- 规避 Android Studio 臃肿、内存高、启动慢、仅桌面可用

- 规避 Acode / Spck 无终端、无 CLI、无编译、无 SSH

- 规避 Termux 无代码编辑、无项目管理、操作门槛高

- 规避传统 CLI 无界面、无法移动端使用

- 规避移动端 Git 工具无法可视化配置 SSH Key

### 1\.4 核心能力一句话

**MobileCoder = 移动端代码编辑器 \+ 内置完整终端 \+ OpenCode 风格 CLI 构建工具 \+ 全功能可视化 Git \+ GitHub SSH 密钥管理 \+ 移动端安卓编译打包 \+ 三模式主题系统**

## 2\. 整体功能需求

### 2\.1 全局主题系统（核心特色）

全局统一三套主题模式，全站 UI、编辑器、终端、日志面板同步切换：

- 浅色模式（Light）

- 深色模式（Dark）

- 跟随系统（Auto / System）

主题生效范围：全部页面、代码高亮、终端配色、Git Diff 配色、日志高亮、弹窗组件。

### 2\.2 代码编辑模块

- 支持 Java / Kotlin / Dart / JS / XML / Gradle 等安卓项目全量语法

- 语法高亮、代码折叠、自动缩进、实时代码报错

- 多文件 Tab 分页、项目树目录、快速检索

- 触控优化：缩放、长按选中、拖拽文件

- 编辑器主题跟随系统主题自动切换明暗高亮

### 2\.3 内置终端模块（核心 P0）

- APP 内嵌独立终端，无需 ROOT、无需跳转第三方应用

- 支持多终端窗口并行

- 完整 Linux 基础指令支持

- 终端自动绑定当前项目路径

- 历史指令缓存、日志实时输出、日志导出

- 终端配色跟随深浅主题自动切换

- 支持后台长时间任务（编译、同步、打包不中断）

### 2\.4 OpenCode 类 CLI 工具链（核心差异化）

内置自研移动端轻量化 CLI 工具，对标 OpenCode / DevCLI，开箱即用：

- `opencode init` 初始化安卓项目

- `opencode format` 代码格式化

- `opencode lint` 代码检查

- `opencode build` 项目编译

- `opencode package` APK 打包

- `opencode clean` 清理构建缓存

支持两种使用方式：终端手动输入、可视化面板一键执行。

### 2\.5 Git 版本控制模块

- 仓库初始化、克隆、拉取、提交、推送

- 分支创建、切换、合并、删除、标签管理

- 文件变更可视化 Diff、代码冲突可视化解决

- 完整提交时间线、日志记录

### 2\.6 GitHub SSH Key 可视化配置模块（P0）

解决移动端开发最大痛点：SSH 配置繁琐、命令行门槛高

- 可视化一键生成 SSH 密钥（RSA / ED25519）

- 本地加密存储私钥

- 一键复制公钥

- 一键跳转 GitHub 配置页

- 一键测试 SSH 连接是否通畅

- 多密钥管理、多账号切换

- Git 自动优先使用 SSH 免密推送拉取

### 2\.7 安卓项目编译打包模块

- 移动端轻量化 Gradle 编译环境

- Debug / Release 双模式打包

- 实时编译日志、错误精准定位

- APK 生成、本地安装与分享

- 内存管控，避免 OOM 崩溃

### 2\.8 性能与体验需求

- 冷启动 ≤ 2s

- 常态内存 ≤ 300MB，峰值 ≤ 800MB

- 全机型适配 Android 10\+

- 无广告、无冗余后台、轻量化纯净体验

## 3\. 非功能需求

### 3\.1 安全性

- SSH 私钥本地 AES 加密存储，不上传云端

- 项目代码本地沙箱隔离

- 所有网络 Git 通信加密传输

### 3\.2 稳定性

- 多任务并行不崩溃

- 编译失败、指令报错不闪退

### 3\.3 兼容性

适配 Android 10 \~ Android 15，手机、平板、折叠屏自适应。

---

# 二、技术设计文档 TECH\.md

## 1\. 项目基础信息

- **项目名称**：MobileCoder 移动码匠

- **仓库名称**：`mobilecoder-android-ide`

- **包名**：`com.mobilecoder.ide`

- **适配平台**：Android 10 \(API29\) \~ Android 15 \(API35\)

- **开发形态**：Android 原生轻量化 IDE 应用

## 2\. 技术栈详细选型

### 2\.1 开发语言

- **Kotlin**：主体业务、UI、逻辑、调度

- **C/C\+\+**：底层终端、Git、SSH、CLI 二进制交叉编译

- **JS**：编辑器 Web 交互

### 2\.2 UI 框架

- **Jetpack Compose Material3**

- 全站声明式 UI，完美支持动态主题切换

- 支持横竖屏、折叠屏自适应

### 2\.3 核心底层依赖

- **终端引擎**：libtermux\-terminal（JNI 封装）

- **Git 底层**：libgit2 静态编译

- **SSH 底层**：libssh2 \+ BouncyCastle 加密

- **代码编辑器**：Monaco Editor（WebView 嵌入）

- **CLI 工具**：自研 OpenCode 移动端静态二进制

### 2\.4 架构模式

**Clean Architecture 分层架构 \+ 模块化组件化**

1. UI 层（Compose）

2. 领域层（UseCase 业务）

3. 数据层（仓库、文件、配置）

4. Native 底层层（JNI / 二进制工具）

## 3\. 全局主题系统技术实现（三模式）

### 3\.1 主题枚举定义

```kotlin
enum class AppThemeMode {
    LIGHT,
    DARK,
    SYSTEM
}
```

### 3\.2 主题切换逻辑

- **LIGHT**：强制浅色，忽略系统

- **DARK**：强制深色，忽略系统

- **SYSTEM**：监听系统 UiModeManager 动态跟随

系统主题变化实时重组所有页面，无闪烁。

### 3\.3 主题色规范（固定色值，无歧义）

#### 浅色主题

- Primary：\#2563EB

- Background：\#FFFFFF

- Surface：\#F8FAFC

- OnBackground：\#111827

#### 深色主题

- Primary：\#60A5FA

- Background：\#0F172A

- Surface：\#1E293B

- OnBackground：\#E2E8F0

### 3\.4 联动机制

- App 主题变更 → 自动通知 Monaco 编辑器切换代码高亮主题

- App 主题变更 → 终端自动重载配色方案

- Git Diff、日志面板、弹窗全部自动适配

## 4\. 核心模块技术实现细则

### 4\.1 代码编辑器模块

- 基于 WebView 嵌套 Monaco Editor

- Kotlin \<\-\> JS 双向通信

- 支持大文件分块加载防 OOM

- 主题动态联动、自动保存、文件热重载

### 4\.2 内置终端模块

- 基于 PTY 伪终端实现独立会话

- 多窗口多 PTY 实例并发

- 自动绑定当前项目工作目录

- 支持 ANSI 彩色日志、主题配色切换

- 后台任务保活，锁屏不中断编译

### 4\.3 OpenCode CLI 模块

- 预编译 Android 平台静态二进制

- 首次启动自动解压至 App 私有 bin 目录并授权可执行

- 复用终端沙箱环境运行

- 协程任务队列，防止多指令冲突

- 日志实时回调 UI 层展示

### 4\.4 Git 模块技术方案

- 底层使用 libgit2 JNI，不依赖系统 Git

- 支持完整仓库操作、Diff 渲染、冲突解析

- 自动识别 SSH / HTTPS 协议

### 4\.5 SSH 密钥管理技术方案

- libssh2 生成标准 RSA / ED25519 密钥对

- 私钥 AES 加密本地存储

- 可视化测试 SSH 连通性

- 多密钥绑定不同仓库，自动匹配免密提交

### 4\.6 编译模块技术方案

- 内置轻量化 Android SDK \+ Gradle 精简环境

- 独立线程执行编译任务

- 内存阈值监控，防止 OOM

- 编译日志实时流式输出

## 5\. 存储结构设计

```Plain Text
/data/data/com.mobilecoder.ide/
├── files/bin/         # CLI、Git、SSH 二进制工具
├── files/projects/    # 用户所有项目源码
├── files/ssh_keys/    # 加密密钥存储
├── files/sdk/         # 编译环境
├── cache/tmp/         # 编译缓存
└── datastore/         # 主题、全局配置
```

## 6\. 权限清单

最小权限原则：

- INTERNET：Git 同步、SSH 连接

- WAKE\_LOCK：后台编译保活

- POST\_NOTIFICATIONS：任务完成通知

## 7\. 工程模块划分

- app：主入口、主题管理、全局导航

- core\_common：公共组件、常量、主题管理器

- core\_native：JNI 底层调用封装

- core\_storage：数据持久化

- feature\_editor：代码编辑器

- feature\_terminal：内置终端

- feature\_cli：OpenCode 命令工具

- feature\_git：版本控制

- feature\_ssh：SSH 密钥管理

- feature\_build：项目编译打包

## 8\. 硬性技术指标

- 冷启动 \< 2s

- 常态内存 \< 300MB

- 终端响应延迟 \< 100ms

- 主题切换全局无闪烁、无重启

- 所有密钥本地加密、不上云

## 9\. 项目仓库信息（最终定稿）

- **项目名称**：MobileCoder 移动码匠

- **仓库名**：mobilecoder\-android\-ide

- **包名**：com\.mobilecoder\.ide

- **项目简介**：MobileCoder 移动码匠，Android 端一体化开发 IDE，内置终端、OpenCode CLI、完整 Git 版本控制与 GitHub SSH 密钥管理，支持安卓项目移动端本地编译，支持浅色/深色/跟随系统三主题模式。

> （注：部分内容可能由 AI 生成）
