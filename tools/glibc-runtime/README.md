# glibc 运行时打包（`tools/glibc-runtime`）

nodejs.org / Adoptium / gradle 的官方 Linux 发行包都是 **glibc** 程序，而 Android
没有 `/lib/ld-linux-aarch64.so.1`：内核在 exec 阶段就找不到解释器，症状是

```
execvp ENOENT (errno=2, …/files/sdk/node/bin/node)（退出码 127）
```

官方也**不提供** Android 构建。运行时因此有两条来源，App 按序自动兜底：

| 来源 | 内容 | 是否需要构建 / 托管 |
| --- | --- | --- |
| **内置 4 个 Debian 镜像**（默认在线） | `libc6` + `libgcc-s1` + `libstdc++6` 运行库（**不含钩子**） | 否 —— Debian 官方与国内镜像公开可达，App 在线下载 `.deb` 自动组装（`GlibcDebRuntime`） |
| **本目录 `build.sh` 产物**（APK 内置 / 自定义源） | 上述运行库 **+ `mcexechook` LD_PRELOAD 钩子**（DNS 兜底、exec 补丁窗口） | 是 —— 需 Linux/Docker 或 CI，产物自托管或打进 APK |

> 为什么在线源用 Debian：glibc aarch64/x86_64 的**唯一真实公开分发**就是 Debian
> 仓库。曾经的 4 个自建占位源（`cdn.mobilecoder.dev`、`mobilecoder/mobilecoder-glibc`、
> `cdn-mobilecoder.cn-shanghai.myqcloud.com`、jsDelivr 同仓库）实测全部 DNS 解析失败
> 或 404，**已整体移除**（2026-09 取证）。

App 侧的完整链路：

1. `EnvDownloader.install` 落位 `files/sdk/glibc`（顶层必须是 `lib/`），来源顺序：
   **APK 内置包（`assets/glibc/`，零网络）→ 自定义源（tar.gz，含钩子）→
   4 个内置 Debian 镜像（在线组装 .deb）**；
2. `GlibcCompat` / `mc_exec_hook` 把目标 ELF 的 `PT_INTERP` **原地改写**到包内
   loader（新路径追加到文件末尾，改 `p_offset` / `p_filesz`）；
3. 之后这些程序以及它们 fork/exec 出来的子进程都能正常运行；
4. 若来源含钩子（内置包 / 自定义源），`lib/mcexechook-glibc.so` 作为 LD_PRELOAD 挂上：
   堵 `npm i -g` 解包瞬间「刚落盘还没打补丁」的二进制（阶段 2），并在 glibc 无
   `/etc/resolv.conf` 时提供裸 DNS 兜底。**Debian 在线组装不含钩子**（钩子必须在
   Linux 交叉编译）—— 只跑 node/JDK/Gradle 够用；要 npm 的 DNS 兜底请用内置包或
   自定义源。

## 内置 4 个 Debian 镜像（已实测可达）

`EnvDownloader.GLIBC_MIRRORS`（4 个，按「官方源 / 国内镜像」偏好排序，失败自动回退）：

| id | label | 基址 |
| --- | --- | --- |
| `official` | Debian 官方 | `https://deb.debian.org/debian` |
| `tuna` | 清华 TUNA | `https://mirrors.tuna.tsinghua.edu.cn/debian` |
| `aliyun` | 阿里云镜像 | `https://mirrors.aliyun.com/debian` |
| `tencent` | 腾讯云镜像 | `https://mirrors.cloud.tencent.com/debian` |

**实测证据（2026-09-29，索引与全部 `.deb` 在 4 个镜像上逐一 HEAD/GET 均 200）**，
`arch=arm64`（`x64` 为同名 `_amd64.deb`，同样 200）：

| 文件 | Content-Length | 官方 | 清华 | 阿里 | 腾讯 |
| --- | ---: | :---: | :---: | :---: | :---: |
| `dists/trixie/main/binary-arm64/Packages.gz` | 13,243,337 | 200 | 200 | 200 | 200 |
| `pool/main/g/glibc/libc6_2.41-12+deb13u4_arm64.deb` | 2,483,572 | 200 | 200 | 200 | 200 |
| `pool/main/g/gcc-14/libgcc-s1_14.2.0-19_arm64.deb` | 54,104 | 200 | 200 | 200 | 200 |
| `pool/main/g/gcc-14/libstdc++6_14.2.0-19_arm64.deb` | 637,536 | 200 | 200 | 200 | 200 |

安装流程（`EnvDownloader.installFromDebRepo`，与 `build.sh` 同款）：

1. 下载 `dists/<suite>/main/binary-<debArch>/Packages.gz`，`GlibcDebRuntime.parseIndex`
   动态解析 `libc6` / `libgcc-s1` / `libstdc++6` 的 `Filename` —— **不写死版本号**，
   Debian 点版本滚动后文件名自动跟随（硬编码文件名必然过期 404）；
2. 逐个下载 `.deb`，`ArchiveExtractor.extractDeb`（ar 归档 + `data.tar.xz`，依赖
   `org.tukaani:xz`）解包到同一根目录；
3. `GlibcDebRuntime.flatten` 扁平化出 `lib/`（软链按包内布局解析、跳过
   gconv/locale/audit/lint、必须解出 `ld-linux*` loader）→ 落位 `files/sdk/glibc`。

`debArch`：`aarch64 → arm64`、`x64 → amd64`；suite = `trixie`（`GlibcDebRuntime.DEFAULT_SUITE`
与 `build.sh` 的 `SUITE` 同步维护）。端到端有自动化实测：

```bash
# 真实镜像全链路（索引 → 3 个 .deb → 解包 → flatten → loader ELF 校验；无网自动跳过）
.\gradlew.bat :feature_build:testDebugUnitTest --tests "*LiveDebVerificationTest"
```

## 构建钩子包（可选：内置 APK / 自定义源）

> 只要「node / JDK / Gradle 能跑」，**跳过本节**，内置 Debian 镜像已足够。
> 需要 **npm 的 DNS 兜底 / exec 补丁窗口**（钩子）才需要打这个包。

```bash
# 依赖：curl gzip tar binutils gcc libc6-dev gcc-aarch64-linux-gnu dpkg
tools/glibc-runtime/build.sh --arch all          # 或 --arch aarch64 / x64

# 推荐：容器内编译，保证 glibc 符号版本与包内一致（脚本会自检）
docker run --rm -v "$PWD":/work -w /work debian:trixie \
  bash tools/glibc-runtime/build.sh --arch all
```

产物：

```
dist/glibc-2.41-aarch64.tar.gz
dist/glibc-2.41-x64.tar.gz
```

包布局（**顶层就是 `lib/`，不要多套一层目录**，`BuildEnvironment.relocateGlibc`
按「哪里有 `ld-linux*`」认根目录，`终端 .mkshrc` 与 `glibcEnv` 都按
`files/sdk/glibc/lib/…` 找）：

```
lib/ld-linux-aarch64.so.1      # Debian libc6 的 loader（真实 ELF，非链接）
lib/libc.so.6 lib/libstdc++.so.6 lib/libgcc_s.so.1 …
lib/mcexechook-glibc.so        # glibc 版 LD_PRELOAD 钩子（本仓库 mc_*.c 编译）
```

注意：`GLIBC_VERSION`（`EnvDownloader.kt` 私有常量，当前 `2.41`）必须与 Debian
suite 对应（trixie = 2.41，实测 `libc6_2.41-12+deb13u4`），也必须与包文件名一致，
否则自定义源 / 内置包按精确名匹配不上。换 suite / 升版本时同步改 `build.sh` 顶部的
`SUITE` / `GLIBC_VERSION` 和 `EnvDownloader.GLIBC_VERSION`。**内置 Debian 镜像不受此
约束**（走索引动态解析）。

脚本内建两道自检，任一不过即退出：

* `libc6` 包版本 ≠ `GLIBC_VERSION` → 报错（防止索引里混进别的 glibc 版本）；
* 钩子 `.so` 的最大 `GLIBC_x.y` 符号版本 > `GLIBC_VERSION` → 报错
  （防止用更新的发行版编译出设备上装不上的 .so）。

## 内置进 APK（推荐：零网络、零托管）

产物可以直接打进 APK，App 首次需要时自动解出 —— **不需要任何 HTTP 托管**：

1. **生成产物**（任选其一）：

   ```bash
   # a) 本机 Docker / 任意 Linux
   docker run --rm -v "$PWD":/work -w /work debian:trixie \
     bash tools/glibc-runtime/build.sh --arch all

   # b) CI：仓库自带 GitHub Actions（.github/workflows/glibc-runtime.yml）
   #    Actions → glibc-runtime → Run workflow，下载 artifact（glibc-runtime）
   #    解压得到 glibc-2.41-*.tar.gz
   ```

2. **放进 `dist/`**：`tools/glibc-runtime/dist/glibc-2.41-<arch>.tar.gz`（本地构建
   默认就落在这里；CI 产物手动解到这里）；

3. **构建 APK**：`app/build.gradle.kts` 的 `copyGlibcAssets` 任务会把 `dist/` 下的
   `glibc-*.tar.gz` 自动拷进 `app/src/main/assets/glibc/`，随 APK 打包。
   `dist/` 为空时该任务静默跳过 —— 不影响构建，此时仍走在线镜像 / 手动导入。

`dist/` 与 `assets/glibc/` 都在 `.gitignore` 里（不往仓库提交二进制）；要让某个
发布版固定内置，先放好产物再 `git add -f app/src/main/assets/glibc/`。

内置包存在时的安装顺序（`EnvDownloader.install`）：**内置 assets → 自定义源 →
内置 Debian 镜像**，内置包损坏才回退在线，全失败才要求手动导入。

## 自定义源 / 自托管（可选）

用户侧两层覆盖（「构建环境」页 → glibc 运行时镜像，持久化到 `AppPreferences`）：

* **点选首选源**：把某个内置 Debian 镜像排到第 1 位尝试，再点一次取消
  （回退到「官方源 / 国内镜像」偏好排序）；
* **自定义镜像源**：手动输入**自托管完整 tar.gz** 的基址（`https://host/glibc`）或
  完整包地址（`…/glibc-2.41-aarch64.tar.gz`），保存后作为**第 1 优先候选**，
  失败仍自动回退全部内置镜像 —— 传到自己的对象存储 / 局域网 HTTP 即可直接试。
  自定义源的价值：它是**含钩子**的完整包（DNS 兜底），且离线局域网可达。

自托管要求：

* `Content-Type: application/gzip`，支持 HTTP Range（`EnvDownloader` 复用现有
  下载逻辑，进度条依赖 Content-Length）；
* 基址下按 `<基址>/glibc-<版本>-<arch>.tar.gz` 拼接（`arch` = `aarch64` / `x64`），
  两个文件都是 `build.sh` 同一次产出的字节。

验证：先在真机「构建环境」页在线安装一次 glibc，再执行
`apt tools install node` 确认 127 是否消失。

## 离线兜底

**首选内置 APK（上一节）**：产物随包走，断网也能就位。包未内置且在线源也不可达时
（提示会列出已尝试的每个来源；内置 4 镜像全部失败通常是**网络不可达**，先切官方源 /
国内镜像），还有两条自救路径：

1. 从「构建环境」页手动导入 `glibc-2.41-<arch>.tar.gz`（走 `EnvDownloader` 同一条
   解压落位路径），不阻断阶段 1 的其余功能；
2. 在「构建环境」页填写**自定义镜像源**指向任意可达 URL 后重试在线下载。
