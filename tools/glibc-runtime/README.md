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

## 上传

两个基址是 `EnvDownloader.kt` 里的占位常量，**发布前必须替换为真实地址**：

| 常量 | 当前占位值 |
| --- | --- |
| `GLIBC_OFFICIAL_BASE` | `https://cdn.mobilecoder.dev/glibc` |
| `GLIBC_MIRROR_BASE`   | `https://cdn-mobilecoder.cn-shanghai.myqcloud.com/glibc` |

拼接规则：`<基址>/glibc-<GLIBC_VERSION>-<arch>.tar.gz`，`arch` 取
`EnvDownloader.primaryArch()` 的值（`aarch64` / `x64`）。即需要上传 4 个文件：

```
<官方基址>/glibc-2.39-aarch64.tar.gz
<官方基址>/glibc-2.39-x64.tar.gz
<镜像基址>/glibc-2.39-aarch64.tar.gz     # 与官方同一份文件，仅域名不同
<镜像基址>/glibc-2.39-x64.tar.gz
```

要求：

* `Content-Type: application/gzip`，支持 HTTP Range（`EnvDownloader` 复用现有
  下载逻辑，进度条依赖 Content-Length）；
* 镜像与官方必须是**同一份字节**（脚本一次产出、复制两处即可）；
* 路径保持一级子目录 `/glibc/`，不要改成扁平路径 —— 否则要同步改两个常量。

上传后建议先在真机「构建环境」页在线安装一次 glibc，再执行
`apt tools install node` 验证 127 是否消失。

## 离线兜底

包未发布时在线下载必然失败，`ToolInstaller.ensureGlibc` 会提示用户改从
「构建环境」页手动导入 `glibc-2.39-<arch>.tar.gz`（走 `EnvDownloader` 同一条
解压落位路径），不阻断阶段 1 的其余功能。
