set -e
export PATH=/usr/bin:/bin:/sbin
if [ -x /usr/bin/qemu-x86_64-static ] && [ -e /lib/x86_64-linux-gnu/libz.so.1 ] &&
   [ -x /usr/bin/cmake ] && [ -x /usr/bin/ninja ]; then
    echo "apt 组件已就绪"
    exit 0
fi
dpkg --add-architecture amd64
if ! grep -q 'arch=amd64' /etc/apt/sources.list.d/mobilecoder-amd64.list 2>/dev/null; then
    printf 'deb [arch=amd64] http://archive.ubuntu.com/ubuntu noble main universe\ndeb [arch=amd64] http://archive.ubuntu.com/ubuntu noble-updates main universe\n' \
        > /etc/apt/sources.list.d/mobilecoder-amd64.list
fi
if grep -q '^deb http' /etc/apt/sources.list && ! grep -q 'arch=arm64' /etc/apt/sources.list; then
    sed -i 's/^deb /deb [arch=arm64,armhf] /' /etc/apt/sources.list
fi
apt-get update -qq || apt-get update
DEBIAN_FRONTEND=noninteractive apt-get install -y -qq \
    qemu-user-static libc6:amd64 zlib1g:amd64 libncurses6:amd64 libtinfo6:amd64 \
    libstdc++6:amd64 libgcc-s1:amd64 cmake ninja-build
test -x /usr/bin/qemu-x86_64-static
test -e /lib/x86_64-linux-gnu/libz.so.1
test -x /usr/bin/cmake
test -x /usr/bin/ninja
echo "apt 组件安装完成"
