#!/usr/bin/env bash
#
# 打包 glibc 运行时：glibc-<版本>-<arch>.tar.gz
#
# 为什么需要它：nodejs.org / Adoptium / gradle 官方发行包都是 glibc 程序，
# Android 上没有 `/lib/ld-linux-aarch64.so.1`，内核 exec 阶段直接 ENOENT(2)/127。
# 本脚本产出的压缩包就是那份「缺失的运行时」，App 侧下载到 `files/sdk/glibc`，
# 再由 GlibcCompat / mc_exec_hook 把目标 ELF 的 PT_INTERP 原地改写到包内 loader。
#
# 包内容（布局必须是顶层 `lib/`，见 BuildEnvironment.relocateGlibc）：
#   lib/ld-linux-aarch64.so.1   Debian libc6 里的 loader（真实 ELF）
#   lib/*.so*                   libc / libstdc++ / libgcc 等（按名扁平化）
#   lib/mcexechook-glibc.so     本仓库源码交叉编译出的 glibc 版 LD_PRELOAD 钩子
#
# 用法：
#   tools/glibc-runtime/build.sh [--arch aarch64|x64|all] [--out DIR]
#
# 依赖（Debian / Ubuntu）：
#   apt install curl gzip tar binutils gcc libc6-dev gcc-aarch64-linux-gnu dpkg
#   （只打本机架构时可省掉交叉编译器）
# 推荐直接用 Docker 保证 glibc 版本与包内一致：
#   docker run --rm -v "$PWD":/work -w /work debian:trixie \
#     bash tools/glibc-runtime/build.sh --arch all
#
set -euo pipefail

# ------------------------------------------------------------------
# 参数与常量
# ------------------------------------------------------------------

# 与 EnvDownloader.GLIBC_VERSION 保持一致；不一致会导致下载文件名对不上
GLIBC_VERSION="2.41"
# glibc 版本取自 Debian trixie（13），换 suite 必须同步改上面的版本号
SUITE="trixie"
MIRROR="https://deb.debian.org/debian"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SRC_DIR="$(cd "$SCRIPT_DIR/../../core_native/src/main/cpp" && pwd)"
OUT_DIR="$SCRIPT_DIR/dist"
CACHE_DIR="$SCRIPT_DIR/.cache"
ARCH="all"

while [ $# -gt 0 ]; do
    case "$1" in
        --arch) ARCH="$2"; shift 2 ;;
        --out)  OUT_DIR="$2"; shift 2 ;;
        -h|--help)
            sed -n '2,30p' "$0"
            exit 0
            ;;
        *) echo "未知参数：$1（--help 查看用法）" >&2; exit 2 ;;
    esac
done

die() { echo "[ERROR] $*" >&2; exit 1; }
note() { echo "==> $*"; }

# App 侧架构 token → Debian 架构（EnvDownloader.primaryArch 的取值）
deb_arch_of() {
    case "$1" in
        aarch64) echo arm64 ;;
        x64)     echo amd64 ;;
        *) die "未知架构 token：$1（只支持 aarch64 / x64）" ;;
    esac
}

# App 侧架构 → 宿主机 uname -m
machine_of() {
    case "$1" in
        aarch64) echo aarch64 ;;
        x64)     echo x86_64 ;;
    esac
}

# ------------------------------------------------------------------
# 工具检查
# ------------------------------------------------------------------

need() {
    command -v "$1" >/dev/null 2>&1 || die "缺少 $1${2:+（$2）}"
}

need tar
need gzip
need awk
need md5sum
need readlink

fetch() {
    if command -v curl >/dev/null 2>&1; then
        curl -fsSL "$1" -o "$2"
    elif command -v wget >/dev/null 2>&1; then
        wget -q -O "$2" "$1"
    else
        die "需要 curl 或 wget 下载 Debian 包"
    fi
}

# ------------------------------------------------------------------
# 单架构构建
# ------------------------------------------------------------------

build_one() {
    local app_arch="$1"
    local deb_arch
    deb_arch="$(deb_arch_of "$app_arch")"
    local machine
    machine="$(machine_of "$app_arch")"
    local pkg_name="glibc-$GLIBC_VERSION-$app_arch.tar.gz"
    local stage="$CACHE_DIR/stage-$app_arch"

    note "[$app_arch / $deb_arch] 准备工作目录"
    rm -rf "$stage"
    mkdir -p "$stage/lib" "$CACHE_DIR/$deb_arch" "$OUT_DIR"

    # ---- 1) 解析并下载 Debian 包 ----------------------------------
    # libc6 提供 loader + libc + nss 模块；libgcc-s1 / libstdc++6 是
    # node 官方二进制的动态依赖（ldd 可见）。
    local pkg_index="$CACHE_DIR/$deb_arch/Packages"
    if [ ! -s "$pkg_index" ]; then
        note "[$app_arch] 下载 $SUITE 软件包索引（binary-$deb_arch）"
        fetch "$MIRROR/dists/$SUITE/main/binary-$deb_arch/Packages.gz" \
            "$pkg_index.gz"
        gzip -dc "$pkg_index.gz" > "$pkg_index"
    fi

    local pkgs="libc6 libgcc-s1 libstdc++6"
    local deb_list=""
    for p in $pkgs; do
        local rel
        rel="$(awk -v want="$p" '
            /^Package: /  { cur = $2 }
            /^Filename: / && cur == want { print $2; cur = "" }
        ' "$pkg_index")"
        [ -n "$rel" ] || die "$deb_arch 索引里找不到包 $p（suite=$SUITE）"
        local deb="$CACHE_DIR/$deb_arch/${rel##*/}"
        if [ ! -s "$deb" ]; then
            note "[$app_arch] 下载 ${rel##*/}"
            fetch "$MIRROR/$rel" "$deb"
        fi
        deb_list="$deb_list $deb"
    done

    # libc6 版本必须就是 GLIBC_VERSION，否则包名与 EnvDownloader 对不上
    local libc6_deb libc6_ver
    libc6_deb="$(echo "$deb_list" | tr ' ' '\n' | grep '/libc6_' | head -1)"
    [ -n "$libc6_deb" ] || die "没拿到 libc6 的 deb"
    if command -v dpkg-deb >/dev/null 2>&1; then
        libc6_ver="$(dpkg-deb -f "$libc6_deb" Version)"
    else
        # 文件名形如 libc6_2.41-12+deb13u4_arm64.deb，版本段就是第二个下划线前的部分
        libc6_ver="$(basename "$libc6_deb" | awk -F_ '{print $2}')"
    fi
    case "$libc6_ver" in
        "$GLIBC_VERSION"*) : ;;
        *) die "libc6 版本是 $libc6_ver，与 GLIBC_VERSION=$GLIBC_VERSION 不符（请同步 EnvDownloader 常量或换 suite）" ;;
    esac

    # ---- 2) 解包 ---------------------------------------------------
    # dpkg-deb 不在时退回 ar + tar（data.tar.* 由宿主 tar 自动识别压缩格式）
    extract_deb() {
        local deb="$1" root="$2"
        mkdir -p "$root"
        if command -v dpkg-deb >/dev/null 2>&1; then
            dpkg-deb -x "$deb" "$root"
            return
        fi
        need ar
        local work="$root/.deb"
        mkdir -p "$work"
        (cd "$work" && ar x "$deb")
        local data
        data="$(find "$work" -maxdepth 1 -name 'data.tar*' | head -1)"
        [ -n "$data" ] || die "${deb##*/} 里没有 data.tar.*"
        tar -xf "$data" -C "$root"
        rm -rf "$work"
    }

    local root="$CACHE_DIR/$deb_arch/root"
    rm -rf "$root"
    for deb in $deb_list; do
        note "[$app_arch] 解包 ${deb##*/}"
        extract_deb "$deb" "$root"
    done

    # ---- 3) 扁平化到 stage/lib -------------------------------------
    # 符号链接按包内布局解析（绝对路径前缀换成解包根），再以「原名 + 真实内容」
    # 落到同一层：Android 上没有 /lib/<三元组> 这种目录结构。
    resolve_target() {
        local f="$1" r="$2" hops=0
        while [ -L "$f" ] && [ "$hops" -lt 8 ]; do
            local t
            t="$(readlink "$f")"
            case "$t" in
                /*) t="$r$t" ;;
                *)  t="$(dirname "$f")/$t" ;;
            esac
            f="$t"
            hops=$((hops + 1))
        done
        [ -f "$f" ] && printf '%s' "$f"
    }

    local copied=0 skipped=0
    while IFS= read -r f; do
        case "$f" in
            */gconv/*|*/locale/*|*/audit/*|*/lint/*) continue ;;
        esac
        local name real
        name="$(basename "$f")"
        if ! real="$(resolve_target "$f" "$root")" || [ -z "$real" ]; then
            echo "    跳过（链接目标不存在）：${f#"$root"/}"
            skipped=$((skipped + 1))
            continue
        fi
        cp "$real" "$stage/lib/$name"
        copied=$((copied + 1))
    done < <(find "$root" \( -type f -o -type l \) -name '*.so*' | sort)

    [ "$copied" -gt 0 ] || die "没有解出任何 .so（解包失败？）"
    note "[$app_arch] 收纳 $copied 个 .so，跳过 $skipped 个"

    # loader 必须在，且名字以 ld-linux 开头（BuildEnvironment.glibcLoader /
    # mc_compat.c find_loader_in 都按这个前缀找）
    local loader
    loader="$(find "$stage/lib" -maxdepth 1 -name 'ld-linux*' | head -1)"
    [ -n "$loader" ] || die "包内没有 ld-linux* loader（libc6 结构变了？）"

    # ---- 4) 交叉编译 glibc 版 LD_PRELOAD 钩子 -----------------------
    local cc
    if [ -n "${CC:-}" ]; then
        cc="$CC"
    elif [ "$(uname -m)" = "$machine" ]; then
        cc="gcc"
    else
        cc="$machine-linux-gnu-gcc"
        [ "$machine" = "x86_64" ] && cc="x86_64-linux-gnu-gcc"
    fi
    command -v "$cc" >/dev/null 2>&1 || die \
        "找不到 $cc（目标 $machine）。apt install gcc-$machine-linux-gnu，或在 debian:trixie 容器里跑本脚本"

    note "[$app_arch] 编译 mcexechook-glibc.so（$cc）"
    "$cc" -O2 -fPIC -shared \
        -Wall -Wextra -Wno-unused-parameter -Wno-missing-field-initializers \
        -I "$SRC_DIR" \
        -o "$stage/lib/mcexechook-glibc.so" \
        "$SRC_DIR/mc_compat.c" "$SRC_DIR/mc_dns.c" "$SRC_DIR/mc_exec_hook.c" \
        -ldl

    # ---- 5) 符号版本自检 -------------------------------------------
    # 用比 trixie 更新的发行版编译时，钩子可能带上超过 2.41 的 GLIBC_ 符号版本，
    # 装到设备上会 `version 'GLIBC_2.4x' not found`。这里直接判失败。
    if command -v objdump >/dev/null 2>&1; then
        local max ver
        max="$(objdump -T "$stage/lib/mcexechook-glibc.so" \
            | grep -oE 'GLIBC_[0-9]+\.[0-9]+' | sed 's/GLIBC_//' \
            | sort -u | sort -V | tail -1 || true)"
        if [ -n "$max" ]; then
            ver="$(printf '%s\n%s\n' "$max" "$GLIBC_VERSION" | sort -V | tail -1)"
            [ "$ver" = "$GLIBC_VERSION" ] || die \
                "钩子依赖 GLIBC_$max > $GLIBC_VERSION，请在 glibc $GLIBC_VERSION 的环境（debian:$SUITE 容器）里编译"
            note "[$app_arch] 钩子最大符号版本 GLIBC_$max ≤ $GLIBC_VERSION ✓"
        fi
    fi

    # ---- 6) 去重（同内容改硬链接，压缩包里只存一份） ----------------
    declare -A seen=()
    local f h
    for f in "$stage"/lib/*; do
        [ -f "$f" ] || continue
        chmod 0755 "$f"
        h="$(md5sum "$f" | awk '{print $1}')"
        if [ -n "${seen[$h]:-}" ]; then
            rm -f "$f"
            ln "${seen[$h]}" "$f"
        else
            seen[$h]="$f"
        fi
    done
    unset seen

    # ---- 7) 打包 ---------------------------------------------------
    # 顶层就是 `lib/`：App 解压后 relocateGlibc 会把整个解压目录挪成
    # files/sdk/glibc，最终路径 files/sdk/glibc/lib/ld-linux-* 必须成立
    # （终端 .mkshrc 与 glibcEnv 都按这个路径找）。
    note "[$app_arch] 打包 $pkg_name"
    tar --owner=0 --group=0 --numeric-owner -C "$stage" \
        -czf "$OUT_DIR/$pkg_name" lib

    tar -tzf "$OUT_DIR/$pkg_name" | head -5 | sed 's/^/    /'
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$OUT_DIR/$pkg_name"
    fi
    echo
}

# ------------------------------------------------------------------
# 入口
# ------------------------------------------------------------------

mkdir -p "$CACHE_DIR" "$OUT_DIR"

case "$ARCH" in
    all)    for a in aarch64 x64; do build_one "$a"; done ;;
    aarch64|x64) build_one "$ARCH" ;;
    *) die "--arch 只支持 aarch64 / x64 / all（收到：$ARCH）" ;;
esac

note "产物目录：$OUT_DIR"
cat <<EOF
产物去向（见 tools/glibc-runtime/README.md）：
  glibc-$GLIBC_VERSION-aarch64.tar.gz
  glibc-$GLIBC_VERSION-x64.tar.gz
    1) 自托管 HTTP：作为「构建环境」页的 glibc 自定义源（含 DNS/exec 钩子）；
    2) 放进 app/src/main/assets/glibc/ 随 APK 内置（离线零网络，首选兜底）。
  注：App 内置 4 镜像已改为真实 Debian 仓库（在线 .deb 组装，不含钩子），本包可选。
EOF
