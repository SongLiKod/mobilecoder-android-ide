# glibc 运行时打包（`tools/glibc-runtime`）

nodejs.org / Adoptium / gradle 的官方 Linux 发行包都是 **glibc** 程序，而 Android
没有 `/lib/ld-linux-aarch64.so.1`：内核在 exec 阶段就找不到解释器，症状是

```
execvp ENOENT (errno=2, …/files/sdk/node/bin/node)（退出码 127）
```

官方也**不提供** Android 构建，所以运行时由本目录自行打包、自托管分发。

App 侧的完整链路：

1. `EnvDownloader` 下载本包 → 解压到 `files/sdk/glibc`（顶层必须是 `lib/`）；
2. `GlibcCompat` / `mc_exec_hook` 把目标 ELF 的 `PT_INTERP` **原地改写**到包内
   loader（新路径追加到文件末尾，改 `p_offset` / `p_filesz`）；
3. 之后这些程序以及它们 fork/exec 出来的子进程都能正常运行；
4. `lib/mcexechook-glibc.so` 是 LD_PRELOAD 钩子，堵 `npm i -g` 解包瞬间
   「刚落盘还没打补丁」的二进制（阶段 2），并在 glibc 无 `/etc/resolv.conf`
   时提供裸 DNS 兜底。

## 构建

```bash
# 依赖：curl gzip tar binutils gcc libc6-dev gcc-aarch64-linux-gnu dpkg
tools/glibc-runtime/build.sh --arch all          # 或 --arch aarch64 / x64

# 推荐：容器内编译，保证 glibc 符号版本与包内一致（脚本会自检）
docker run --rm -v "$PWD":/work -w /work debian:trixie \
  bash tools/glibc-runtime/build.sh --arch all
```

产物：

```
dist/glibc-2.39-aarch64.tar.gz
dist/glibc-2.39-x64.tar.gz
```

包布局（**顶层就是 `lib/`，不要多套一层目录**，`BuildEnvironment.relocateGlibc`
按「哪里有 `ld-linux*`」认根目录，`终端 .mkshrc` 与 `glibcEnv` 都按
`files/sdk/glibc/lib/…` 找）：

```
lib/ld-linux-aarch64.so.1      # Debian libc6 的 loader（真实 ELF，非链接）
lib/libc.so.6 lib/libstdc++.so.6 lib/libgcc_s.so.1 …
lib/mcexechook-glibc.so        # glibc 版 LD_PRELOAD 钩子（本仓库 mc_*.c 编译）
```

注意：`GLIBC_VERSION`（`EnvDownloader.kt` 私有常量，当前 `2.39`）必须与
Debian suite 对应（trixie = 2.39），也必须与包文件名一致，否则下载 404。
换 suite / 升版本时同步改 `build.sh` 顶部的 `SUITE` / `GLIBC_VERSION` 和
`EnvDownloader.GLIBC_VERSION`。

脚本内建两道自检，任一不过即退出：

* `libc6` 包版本 ≠ `GLIBC_VERSION` → 报错（防止索引里混进别的 glibc 版本）；
* 钩子 `.so` 的最大 `GLIBC_x.y` 符号版本 > `GLIBC_VERSION` → 报错
  （防止用更新的发行版编译出设备上装不上的 .so）。

## 上传（内置多镜像 + 手动输入）

App 内置 **4 个默认镜像源**（`EnvDownloader.GLIBC_MIRRORS`），下载按顺序自动回退，
全失败才报错；同一份文件传到**任意一处**即可用（至少一处就能跑通，其余作为兜底）：

| id | label | 常量 / 基址 | 拼接规则 |
| --- | --- | --- | --- |
| `official` | MobileCoder 官方 | `GLIBC_OFFICIAL_BASE` = `https://cdn.mobilecoder.dev/glibc` | `<基址>/glibc-<版本>-<arch>.tar.gz` |
| `github` | GitHub Releases | `GLIBC_GITHUB_BASE` = `https://github.com/mobilecoder/mobilecoder-glibc/releases/download/v1` | 同上（release 资产同名） |
| `tencent` | 腾讯云镜像 | `GLIBC_MIRROR_BASE` = `https://cdn-mobilecoder.cn-shanghai.myqcloud.com/glibc` | 同上 |
| `jsdelivr` | jsDelivr 加速 | `GLIBC_JSDELIVR_BASE` = `https://cdn.jsdelivr.net/gh/mobilecoder/mobilecoder-glibc@main` | 同上（仓库 main 分支**根目录**） |

`official` / `tencent` 两个基址是占位常量，**发布前必须替换为真实地址**；`github` /
`jsdelivr` 指向仓库 `mobilecoder/mobilecoder-glibc`（发布前改名或改 `EnvDownloader` 常量）。

`arch` 取 `EnvDownloader.primaryArch()` 的值（`aarch64` / `x64`），即每个托管位
各需 2 个文件（共 8 个，都是同一份字节的复制）：

```
<托管位>/glibc-2.39-aarch64.tar.gz
<托管位>/glibc-2.39-x64.tar.gz
```

用户侧还有两层覆盖（「构建环境」页 → glibc 运行时镜像，持久化到 `AppPreferences`）：

* **点选首选源**：把某个内置源排到第 1 位尝试，再点一次取消（回退到「官方源 /
  国内镜像」偏好排序）；
* **自定义镜像源**：手动输入基址（`https://host/glibc`）或完整包地址
  （`…/glibc-2.39-aarch64.tar.gz`），保存后作为**第 1 优先候选**，
  失败仍自动回退全部内置源 —— 传到自己的对象存储 / 局域网 HTTP 即可直接试。

要求：

* `Content-Type: application/gzip`，支持 HTTP Range（`EnvDownloader` 复用现有
  下载逻辑，进度条依赖 Content-Length）；
* 各镜像必须是**同一份字节**（脚本一次产出、复制到各处即可）；
* 内置源路径保持表中的拼接规则 —— 改路径需同步改 `EnvDownloader` 对应常量。

上传后建议先在真机「构建环境」页在线安装一次 glibc，再执行
`apt tools install node` 验证 127 是否消失。

## 离线兜底

包未发布时在线下载必然失败（提示会列出已尝试的每个源），此时有三条自救路径：

1. 从「构建环境」页手动导入 `glibc-2.39-<arch>.tar.gz`（走 `EnvDownloader` 同一条
   解压落位路径），不阻断阶段 1 的其余功能；
2. 在「构建环境」页填写**自定义镜像源**指向任意可达 URL 后重试在线下载；
3. 把包上传到任一内置托管位（见上节）。
