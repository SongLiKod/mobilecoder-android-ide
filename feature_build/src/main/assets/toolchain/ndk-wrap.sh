set -e
export PATH=/usr/bin:/bin:/sbin
ZIP="__ZIP__"
TARGET="__NDK_TARGET__"
TOOLBIN="__TOOL_BIN__"
NDKROOT="__NDK_ROOT__"
# 1) guest unzip：符号链接必须正确落盘（toybox unzip 会压成普通文件）
rm -rf "$NDKROOT/.unzip"
mkdir -p "$NDKROOT"
cd "$NDKROOT"
mkdir .unzip
cd .unzip
unzip -oq "$ZIP"
rm -rf "$TARGET"
mv android-ndk-r26d "$TARGET"
rm -rf "$NDKROOT/.unzip"
# 2) 工具链 bin：x86_64 ELF → .bin + qemu 包装脚本（argv0 = 绝对调用路径，
#    lld 靠 argv0 认模式、clang 靠 argv0 定位 InstalledDir）
B="$TOOLBIN"
Q=/usr/bin/qemu-x86_64-static
cd "$B"
declare -A FINAL
for f in *; do
  node="$f"
  while [ -L "$node" ]; do
    t=$(readlink "$node")
    case "$t" in /*) node="${t##*/}";; *) node="$t";; esac
  done
  [ -f "$node" ] || continue
  m=$(head -c 4 "$node" | od -An -tx1 | tr -d " ")
  [ "$m" = "7f454c46" ] || continue
  mach=$(dd if="$node" bs=1 skip=18 count=2 2>/dev/null | od -An -tx1 | tr -d " ")
  [ "$mach" = "3e00" ] || continue
  FINAL["$f"]="$node"
done
for elf in $(printf "%s\n" "${FINAL[@]}" | sort -u); do
  if [ -f "$elf" ]; then mv "$elf" "$elf.bin"; fi
done
for name in "${!FINAL[@]}"; do
  elf="${FINAL[$name]}"
  rm -f "$name"
  printf '#!/bin/sh\nexec %s -0 %s/%s -L / %s/%s.bin "$@"\n' "$Q" "$B" "$name" "$B" "$elf" > "$name"
  chmod 755 "$name"
done
chmod 755 "$B"/*.bin 2>/dev/null || true
# 3) 自检：clang 已是脚本且能跑
head -c 2 clang | grep -q '#!'
./clang --version >/dev/null 2>&1
echo "NDK 装配完成"
