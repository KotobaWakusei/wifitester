#!/usr/bin/env bash
set -euo pipefail

# Builds the optional tool packages that the app downloads on demand.
#
# The core tools (tcpdump, arpspoof) ship inside the APK and are produced by
# tools/build-android-toolchain.sh. The packages here are too large or too
# niche for every user, so they are published as GitHub Release assets:
#
#   wifitester-toolpack-aircrack-arm64-v8a.tar.gz
#       aircrack-ng, airodump-ng, aireplay-ng, airmon-ng
#   wifitester-toolpack-hostapd-arm64-v8a.tar.gz
#       hostapd
#   wifitester-toolpack-reaver-arm64-v8a.tar.gz
#       reaver, wash
#
# Each archive contains only regular files; the app extracts the entry whose
# file name matches each tool and installs it under files/toolchain/bin.
#
#   ANDROID_NDK_HOME=/path/to/ndk ./tools/build-toolpacks.sh
#
# Output: build/toolpacks/*.tar.gz. Upload them to the release tag that
# TOOL_PACKAGE_TAG in app/build.gradle points at.

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
NDK_VERSION="${NDK_VERSION:-27.2.12479018}"
NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
if [[ -z "$NDK" && -n "${ANDROID_HOME:-}" ]]; then
  NDK="${ANDROID_HOME}/ndk/${NDK_VERSION}"
fi
[[ -d "$NDK" ]] || { echo "Android NDK not found; set ANDROID_NDK_HOME" >&2; exit 2; }

ABI="${ABI:-arm64-v8a}"
case "$ABI" in
  arm64-v8a) TARGET=aarch64-linux-android; API=23 ;;
  armeabi-v7a) TARGET=armv7a-linux-androideabi; API=23 ;;
  *) echo "unsupported ABI: $ABI" >&2; exit 2 ;;
esac

# hostapd links against the platform libnl_2, which is not part of the NDK.
# The sysroot that matches the target ABI is used for headers and the stub
# library set; the device provides /system/lib64/libnl.so at runtime.
SYSTEM_LIB="arm64"
ANDROID_LIB_DIR="$NDK/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/aarch64-linux-android/23"

HOST_TAG="linux-x86_64"
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/$HOST_TAG"
CC="$TOOLCHAIN/bin/${TARGET}${API}-clang"
AR="$TOOLCHAIN/bin/llvm-ar"
RANLIB="$TOOLCHAIN/bin/llvm-ranlib"
STRIP="$TOOLCHAIN/bin/llvm-strip"

OPENSSL_VERSION="${OPENSSL_VERSION:-3.0.15}"
AIRCRACK_VERSION="${AIRCRACK_VERSION:-1.7}"
HOSTAPD_VERSION="${HOSTAPD_VERSION:-2.11}"
REAVER_VERSION="${REAVER_VERSION:-1.6.6}"

OUT="$ROOT/build/toolpacks"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
PREFIX="$WORK/prefix"
mkdir -p "$PREFIX" "$OUT"

log() { printf '\n=== %s ===\n' "$*"; }
warn() { echo "warning: $*" >&2; }

# Upstream hosts are slow and occasionally refuse connections, so downloaded
# sources and the compiled OpenSSL/libpcap prefix are cached between runs.
# TOOLPACK_CACHE can point at a persistent directory (CI cache, ~/.cache).
CACHE="${TOOLPACK_CACHE:-$ROOT/build/.toolpack-cache}"
mkdir -p "$CACHE"

# Several mirrors answer 200 with an HTML page (captive portal, regional block,
# "under maintenance"). Such a body must never be cached as a source archive;
# an earlier run cached exactly that and every later build failed to unpack it.
is_archive() {
  local file="$1"
  [[ -s "$file" ]] || return 1
  case "$file" in
    *.tar.gz|*.tgz) gzip -t "$file" >/dev/null 2>&1 ;;
    *.tar.xz) xz -t "$file" >/dev/null 2>&1 ;;
    *) return 0 ;;
  esac
}

download() {
  local target="$1"; shift
  if is_archive "$target"; then
    return 0
  fi
  rm -f "$target"
  local cached="$CACHE/$(basename "$target")"
  mkdir -p "$(dirname "$target")"
  if is_archive "$cached"; then
    cp "$cached" "$target"
    return 0
  fi
  rm -f "$cached"
  local url attempt
  for url in "$@"; do
    # Retrying by hand instead of curl --retry: with --output, a retried
    # transfer can leave a partial file that looks complete to the caller.
    for attempt in 1 2 3; do
      if curl --fail --location --connect-timeout 20 --max-time 600 \
          "$url" --output "$target"; then
        if is_archive "$target"; then
          cp "$target" "$cached"
          return 0
        fi
        warn "response is not a valid archive: $url"
        break
      fi
      warn "download attempt $attempt failed: $url"
      rm -f "$target"
      sleep 3
    done
    rm -f "$target"
  done
  warn "unable to download $(basename "$target")"
  return 1
}

# Packs the named binaries that were installed into $PREFIX/bin.
pack() {
  local name="$1"; shift
  local stage="$WORK/stage-$name"
  mkdir -p "$stage"
  local file
  for file in "$@"; do
    [[ -f "$PREFIX/bin/$file" ]] || { warn "$name: missing $file"; return 1; }
    install -m 0755 "$PREFIX/bin/$file" "$stage/$file"
    "$STRIP" "$stage/$file"
  done
  tar -czf "$OUT/wifitester-toolpack-$name-$ABI.tar.gz" -C "$stage" .
  printf '%-10s %10s bytes  members: %s\n' "$name" \
    "$(stat -c %s "$OUT/wifitester-toolpack-$name-$ABI.tar.gz")" "$(ls "$stage" | tr '\n' ' ')"
}

# OpenSSL's .pc files record the build-time prefix. When the cached copy is
# copied to the actual $PREFIX, those paths point at the wrong place and every
# downstream configure (aircrack-ng) fails to find OpenSSL.
fix_openssl_pc() {
  local dir="$1"
  [[ -d "$dir/lib/pkgconfig" ]] || return 0
  sed -i "s|^prefix=.*|prefix=$dir|" "$dir"/lib/pkgconfig/*.pc
}

build_openssl() {
  log "OpenSSL ${OPENSSL_VERSION}"
  if [[ -d "$CACHE/openssl" ]]; then
    mkdir -p "$PREFIX"
    rm -rf "$PREFIX/openssl"
    cp -a "$CACHE/openssl" "$PREFIX/openssl"
    fix_openssl_pc "$PREFIX/openssl"
    echo "reusing cached OpenSSL"
    return 0
  fi
  download "$WORK/openssl.tar.gz" \
    "https://www.openssl.org/source/openssl-${OPENSSL_VERSION}.tar.gz" \
    "https://github.com/openssl/openssl/releases/download/openssl-${OPENSSL_VERSION}/openssl-${OPENSSL_VERSION}.tar.gz" \
    "https://mirrors.huaweicloud.com/openssl/source/openssl-${OPENSSL_VERSION}.tar.gz" || return 1
  tar -xzf "$WORK/openssl.tar.gz" -C "$WORK" || return 1
  cd "$WORK/openssl-${OPENSSL_VERSION}" || return 1
  local target=linux-aarch64
  [[ "$ABI" == "armeabi-v7a" ]] && target=linux-armv4
  CC="$CC" AR="$AR" RANLIB="$RANLIB" \
    ./Configure "$target" --prefix="$PREFIX/openssl" no-shared no-tests \
    no-ui-console no-engine no-async no-afalgeng >/dev/null
  make -j"$(nproc)" build_libs >/dev/null || return 1
  make install_dev >/dev/null || return 1
  cp -a "$PREFIX/openssl" "$CACHE/openssl"
}

# libpcap is needed by airodump-ng and reaver.
build_libpcap() {
  log "libpcap"
  download "$WORK/libpcap.tar.xz" \
    "https://www.tcpdump.org/release/libpcap-1.10.5.tar.xz" \
    "https://github.com/the-tcpdump-group/libpcap/archive/refs/tags/libpcap-1.10.5.tar.gz" || return 1
  # The GitHub snapshot is .tar.gz while the release tarball is .tar.xz.
  mkdir -p "$WORK/libpcap-1.10.5"
  tar -xf "$WORK/libpcap.tar.xz" -C "$WORK/libpcap-1.10.5" --strip-components=1 \
    || return 1
  cd "$WORK/libpcap-1.10.5" || return 1
  # The GitHub snapshot has no pregenerated parser sources.
  [[ -f ./configure ]] || ./autogen.sh >/dev/null 2>&1 || return 1
  CC="$CC" AR="$AR" RANLIB="$RANLIB" CFLAGS="-O2 -fPIC" ./configure \
    --host="$TARGET" --prefix="$PREFIX" --disable-shared --enable-static \
    --disable-dbus --without-libnl >/dev/null || return 1
  make -j"$(nproc)" >/dev/null || return 1
  make install >/dev/null || return 1
}

build_aircrack() {
  log "aircrack-ng ${AIRCRACK_VERSION}"
  # The release tarball ships a generated configure script, so no autotools
  # are needed; the GitHub tag snapshot is only the fallback.
  download "$WORK/aircrack.tar.gz" \
    "https://download.aircrack-ng.org/aircrack-ng-${AIRCRACK_VERSION}.tar.gz" \
    "https://github.com/aircrack-ng/aircrack-ng/archive/refs/tags/${AIRCRACK_VERSION}.tar.gz" || return 1
  rm -rf "$WORK/ac"
  mkdir -p "$WORK/ac"
  # --strip-components normalises both tarball layouts to one flat directory.
  tar -xzf "$WORK/aircrack.tar.gz" -C "$WORK/ac" --strip-components=1 || return 1
  cd "$WORK/ac" || return 1
  [[ -f ./configure || -f ./autogen.sh ]] || { warn "aircrack-ng: no build script found"; return 1; }
  chmod +x ./configure ./autogen.sh 2>/dev/null || true
  # The GitHub tag snapshot has no configure script. It has to be generated
  # with the autotools, which are therefore part of the build requirements.
  if [[ ! -x ./configure ]]; then
    if ! command -v autoreconf >/dev/null 2>&1; then
      warn "aircrack-ng: no pregenerated configure (GitHub snapshot) and autotools are missing"
      return 1
    fi
    ./autogen.sh >/dev/null 2>&1 || { warn "aircrack-ng: autogen.sh failed"; return 1; }
  fi
  CC="$CC" AR="$AR" RANLIB="$RANLIB" \
    CFLAGS="-O2 -fPIC -I$PREFIX/openssl/include -I$PREFIX/include -D_GNU_SOURCE -DCODESET=0 -Dnl_langinfo(x)=\"UTF-8\" -DDISABLE_TUI=1" \
    LDFLAGS="-L$PREFIX/openssl/lib -L$PREFIX/lib -static" \
    LIBS="-ldl" \
    PKG_CONFIG_PATH="$PREFIX/openssl/lib/pkgconfig" \
    ./configure --host="$TARGET" --prefix="$PREFIX" \
      --without-libnl --without-udev --disable-etc --disable-gcrypt --disable-tui \
      --enable-static --disable-shared >/dev/null || return 1
  make -j"$(nproc)" >/dev/null 2>&1 || make -j"$(nproc)" || return 1
  make install >/dev/null 2>&1 || return 1
}

build_hostapd() {
  log "hostapd ${HOSTAPD_VERSION}"
  download "$WORK/hostapd.tar.gz" \
    "https://w1.fi/releases/hostapd-${HOSTAPD_VERSION}.tar.gz" || return 1
  tar -xzf "$WORK/hostapd.tar.gz" -C "$WORK" || return 1
  cd "$WORK/hostapd-${HOSTAPD_VERSION}" || return 1
  [[ -f Makefile ]] || cd hostapd || return 1
  # hostapd refuses to build without a .config; defconfig enables the nl80211
  # driver needed for a virtual AP on Android.
  # The Makefile uses "ifdef CONFIG_*", so a duplicate "=y" line still counts.
  # Remove every existing definition first, then write the final choices.
  grep -vE '^CONFIG_(LIBNL32|LIBNL|LIBNL3|LIBNL_ROUTE3|LIBNL3_ROUTE|DRIVER_NL80211|DRIVER_HOSTAP|IEEE8021X_EAPOL)=' \
    defconfig > .config
  {
    echo 'CONFIG_DRIVER_NL80211=y'
    echo 'CONFIG_IEEE8021X_EAPOL=y'
  } >> .config
  # The nl80211 driver needs libnl. Android ships AOSP's libnl_2 as
  # /system/lib64/libnl.so and its headers are bundled with hostapd
  # (src/drivers/driver_nl80211_android.c). Linking against the system library
  # keeps the binary small and matches how the stock wpa_supplicant is built;
  # a fully static build would need libnl built from source, which the NDK does
  # not provide.
  #
  # Do not pass CFLAGS on the command line: it replaces the include paths that
  # the hostapd Makefile adds (src/, src/utils ...) and every file fails with
  # "utils/includes.h file not found". EXTRA_CFLAGS/LDFLAGS are meant to append.
  # Use static libnl built earlier, not system library
  make -j"$(nproc)" CC="$CC" AR="$AR" RANLIB="$RANLIB" \
    EXTRA_CFLAGS="-O2 -fPIC -I$PREFIX/openssl/include -I../src/utils -I$PREFIX/include/libnl3" \
    LIBS="-L$PREFIX/lib -lnl-genl-3 -lnl-3 -lc -ldl" \
    LDFLAGS="-L$PREFIX/openssl/lib -L$PREFIX/lib -static" >/dev/null 2>&1 || return 1
  install -m 0755 hostapd "$PREFIX/bin/hostapd"
}

build_reaver() {
  log "reaver ${REAVER_VERSION}"
  # The GitHub tag snapshots ship no generated configure script, so the
  # pregenerated tarballs from the project site are preferred.
  download "$WORK/reaver.tar.gz" \
    "https://github.com/t6x/reaver-wps-fork-t6x/releases/download/v${REAVER_VERSION}/reaver-${REAVER_VERSION}.tar.gz" \
    "https://github.com/t6x/reaver-wps-fork-t6x/archive/refs/tags/v${REAVER_VERSION}.tar.gz" || return 1
  rm -rf "$WORK/reav"
  mkdir -p "$WORK/reav"
  tar -xzf "$WORK/reaver.tar.gz" -C "$WORK/reav" --strip-components=1 || return 1
  cd "$WORK/reav" || return 1
  if [[ ! -x ./configure ]]; then
    if ! command -v autoreconf >/dev/null 2>&1; then
      warn "reaver: no configure script and autotools are missing"
      return 1
    fi
    ./bootstrap >/dev/null 2>&1 || autoreconf -fi >/dev/null 2>&1 || return 1
  fi
  # reaver needs OpenSSL and libpcap. Ensure they are built first.
  [[ -f "$PREFIX/lib/libpcap.a" ]] || build_libpcap || return 1
  [[ -d "$PREFIX/openssl" ]] || build_openssl || return 1
  CC="$CC" AR="$AR" RANLIB="$RANLIB" \
    CFLAGS="-O2 -fPIC -I$PREFIX/include -I$PREFIX/openssl/include" \
    LDFLAGS="-L$PREFIX/lib -L$PREFIX/openssl/lib -static" \
    LIBS="-lpcap -lssl -lcrypto -ldl" \
    ./configure --host="$TARGET" --prefix="$PREFIX" >/dev/null || return 1
  make -j"$(nproc)" >/dev/null 2>&1 || make -j"$(nproc)" || return 1
  make install >/dev/null 2>&1 || return 1
}

# Each package is independent: one tool that upstream cannot build (missing
# autotools, moved tarball) must not hide the packages that did build. The
# script fails at the end only when nothing was produced.
FAILED=()
step() {
  local name="$1"; shift
  if ! "$@"; then
    warn "$name failed; it is skipped"
    FAILED+=("$name")
  fi
}

step openssl build_openssl
step libpcap build_libpcap
step aircrack-ng build_aircrack
step hostapd build_hostapd
step reaver build_reaver

log "packaging"
packed=0
try_pack() {
  if pack "$@"; then packed=$((packed + 1)); fi
}
try_pack aircrack aircrack-ng airodump-ng aireplay-ng airmon-ng
try_pack hostapd hostapd
try_pack reaver reaver wash

if [[ ${#FAILED[@]} -gt 0 ]]; then
  warn "skipped: ${FAILED[*]}"
fi
if [[ $packed -eq 0 ]]; then
  echo "error: no tool package was produced; see the warnings above" >&2
  exit 1
fi
log "done: $OUT ($packed package(s))"
