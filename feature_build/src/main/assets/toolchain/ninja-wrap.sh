#!/bin/bash
# clamp parallelism for qemu-hosted NDK builds on phone:
# -j / -jN 一律压到 __MAXJOBS__；--version 等查询原样透传
args=()
hadj=0
for a in "$@"; do
  case "$a" in
    -j*) hadj=1 ;;
    *) args+=("$a") ;;
  esac
done
case " $* " in
  *--version*)
    exec /usr/bin/ninja "$@" ;;
esac
if [ "$hadj" = 1 ]; then
  exec /usr/bin/ninja -j__MAXJOBS__ "${args[@]}"
else
  exec /usr/bin/ninja "${args[@]}"
fi
