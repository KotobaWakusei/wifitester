#!/usr/bin/env bash
set -euo pipefail

# Build a small, self-contained Android ARM64 tcpdump using the official NDK.
# The binary is packaged as an APK asset and extracted by BundledToolchain.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
NDK_VERSION="${NDK_VERSION:-27.2.12479018}"
NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
if [[ -z "$NDK" && -n "${ANDROID_HOME:-}" ]]; then
  NDK="${ANDROID_HOME}/ndk/${NDK_VERSION}"
fi
if [[ -z "$NDK" ]]; then
  echo "Android NDK path not found; set ANDROID_NDK_HOME or ANDROID_HOME" >&2
  exit 2
fi
if [[ ! -d "$NDK" ]]; then
  echo "Android NDK not found at $NDK" >&2
  exit 2
fi
HOST_TAG="linux-x86_64"
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/$HOST_TAG"
CC="$TOOLCHAIN/bin/aarch64-linux-android23-clang"
AR="$TOOLCHAIN/bin/llvm-ar"
RANLIB="$TOOLCHAIN/bin/llvm-ranlib"
STRIP="$TOOLCHAIN/bin/llvm-strip"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
PREFIX="$WORK/prefix"
ASSETS="$ROOT/app/src/main/assets/toolchain/arm64-v8a"
mkdir -p "$PREFIX" "$ASSETS"

curl --fail --location --retry 3 https://www.tcpdump.org/release/libpcap-1.10.5.tar.xz -o "$WORK/libpcap.tar.xz"
tar -xf "$WORK/libpcap.tar.xz" -C "$WORK"
cd "$WORK/libpcap-1.10.5"
CC="$CC" AR="$AR" RANLIB="$RANLIB" CFLAGS="-O2 -fPIC" ./configure \
  --host=aarch64-linux-android --prefix="$PREFIX" --disable-shared --enable-static \
  --disable-dbus --without-libnl
make -j2
make install

curl --fail --location --retry 3 https://www.tcpdump.org/release/tcpdump-4.99.5.tar.xz -o "$WORK/tcpdump.tar.xz"
tar -xf "$WORK/tcpdump.tar.xz" -C "$WORK"
cd "$WORK/tcpdump-4.99.5"
CC="$CC" AR="$AR" RANLIB="$RANLIB" CFLAGS="-O2 -fPIC" \
  LDFLAGS="-static" LIBS="$PREFIX/lib/libpcap.a" ./configure \
  --host=aarch64-linux-android --prefix="$PREFIX" --disable-shared --enable-static
make -j2
install -m 0755 tcpdump "$ASSETS/tcpdump"
"$STRIP" "$ASSETS/tcpdump"
file "$ASSETS/tcpdump" || true
"$TOOLCHAIN/bin/llvm-readelf" -h "$ASSETS/tcpdump" | grep -E 'Class:|Machine:'
echo "Bundled ARM64 tcpdump written to $ASSETS/tcpdump"
