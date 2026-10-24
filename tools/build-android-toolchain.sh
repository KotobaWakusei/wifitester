#!/usr/bin/env bash
set -euo pipefail

# Build the Android ARM64 command line tools that ship inside the APK.
#
# The binaries are packaged as APK assets and extracted into app-private
# storage by BundledToolchain on first launch. Nothing is downloaded at
# runtime and no third-party package manager is involved.
#
#   libpcap     -> static, shared by every tool
#   tcpdump     -> passive capture and PCAP reading
#   OpenSSL     -> crypto backend for aircrack-ng and hostapd
#   aircrack-ng -> aircrack-ng, airodump-ng, aireplay-ng, airmon-ng
#   hostapd     -> rogue access point
#   arpspoof    -> ARP spoofing helper (built from tools/native/arpspoof.c)
#
# Every step is optional: a failing optional step is reported and skipped so a
# transient upstream problem does not break the whole build. Set
# WIFITESTER_STRICT_TOOLCHAIN=1 to turn those warnings into hard errors.
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
READELF="$TOOLCHAIN/bin/llvm-readelf"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
PREFIX="$WORK/prefix"
ASSETS="$ROOT/app/src/main/assets/toolchain/arm64-v8a"
mkdir -p "$PREFIX" "$ASSETS"

LIBPCAP_VERSION="${LIBPCAP_VERSION:-1.10.5}"
TCPDUMP_VERSION="${TCPDUMP_VERSION:-4.99.5}"
OPENSSL_VERSION="${OPENSSL_VERSION:-3.0.15}"
AIRCRACK_VERSION="${AIRCRACK_VERSION:-1.7}"
HOSTAPD_VERSION="${HOSTAPD_VERSION:-2.11}"

log() { printf '\n=== %s ===\n' "$*"; }
warn() { echo "warning: $*" >&2; }
fatal() { echo "error: $*" >&2; exit 1; }

# Reports the host tools that are needed to build the native components.
check_prerequisites() {
  local missing=()
  command -v make >/dev/null 2>&1 || missing+=("make")
  command -v flex >/dev/null 2>&1 || missing+=("flex")
  # libpcap needs a yacc compatible parser generator.
  if ! command -v bison >/dev/null 2>&1 \
      && ! command -v byacc >/dev/null 2>&1 \
      && ! command -v yacc >/dev/null 2>&1; then
    missing+=("bison (or byacc/yacc)")
  fi
  if [[ ${#missing[@]} -gt 0 ]]; then
    echo "missing host tools: ${missing[*]}" >&2
    echo "install them first, e.g.:" >&2
    echo "  sudo apt-get install -y build-essential flex bison" >&2
    return 1
  fi
  return 0
}

# Downloads the first URL that works. Upstream hosts are occasionally
# unreachable, so every artifact has a mirror. The function returns a non-zero
# status when no mirror could be reached, which lets the caller skip the step
# instead of aborting the whole build.
download() {
  local target="$1"; shift
  local url
  for url in "$@"; do
    if curl --fail --location --retry 3 --retry-delay 2 --connect-timeout 30 \
        "$url" --output "$target"; then
      return 0
    fi
    warn "download failed, trying the next mirror: $url"
  done
  warn "unable to download $(basename "$target")"
  return 1
}

# --------------------------------------------------------------------------
# libpcap (static)
# --------------------------------------------------------------------------
build_libpcap() {
  log "libpcap ${LIBPCAP_VERSION}"
  download "$WORK/libpcap.tar.xz" \
    "https://www.tcpdump.org/release/libpcap-${LIBPCAP_VERSION}.tar.xz" \
    "https://github.com/the-tcpdump-group/libpcap/releases/download/libpcap-${LIBPCAP_VERSION}/libpcap-${LIBPCAP_VERSION}.tar.gz"
  tar -xf "$WORK/libpcap.tar.xz" -C "$WORK" || tar -xzf "$WORK/libpcap.tar.xz" -C "$WORK" || return 1
  cd "$WORK/libpcap-${LIBPCAP_VERSION}" || return 1
  CC="$CC" AR="$AR" RANLIB="$RANLIB" CFLAGS="-O2 -fPIC" ./configure \
    --host=aarch64-linux-android --prefix="$PREFIX" --disable-shared --enable-static \
    --disable-dbus --without-libnl
  make -j"$(nproc)" || return 1
  make install || return 1
}

# --------------------------------------------------------------------------
# tcpdump
# --------------------------------------------------------------------------
build_tcpdump() {
  log "tcpdump ${TCPDUMP_VERSION}"
  download "$WORK/tcpdump.tar.xz" \
    "https://www.tcpdump.org/release/tcpdump-${TCPDUMP_VERSION}.tar.xz" \
    "https://github.com/the-tcpdump-group/tcpdump/releases/download/tcpdump-${TCPDUMP_VERSION}/tcpdump-${TCPDUMP_VERSION}.tar.gz"
  tar -xf "$WORK/tcpdump.tar.xz" -C "$WORK" || tar -xzf "$WORK/tcpdump.tar.xz" -C "$WORK" || return 1
  cd "$WORK/tcpdump-${TCPDUMP_VERSION}" || return 1
  CC="$CC" AR="$AR" RANLIB="$RANLIB" CFLAGS="-O2 -fPIC" \
    LDFLAGS="-static" LIBS="$PREFIX/lib/libpcap.a" ./configure \
    --host=aarch64-linux-android --prefix="$PREFIX" --with-crypto=no
  make -j"$(nproc)" || return 1
  install -m 0755 tcpdump "$ASSETS/tcpdump" || return 1
  "$STRIP" "$ASSETS/tcpdump"
}

# --------------------------------------------------------------------------
# OpenSSL (static, no shared libraries, no engine modules)
# --------------------------------------------------------------------------
build_openssl() {
  log "OpenSSL ${OPENSSL_VERSION}"
  download "$WORK/openssl.tar.gz" \
    "https://www.openssl.org/source/openssl-${OPENSSL_VERSION}.tar.gz" \
    "https://github.com/openssl/openssl/releases/download/openssl-${OPENSSL_VERSION}/openssl-${OPENSSL_VERSION}.tar.gz" \
    "https://mirrors.huaweicloud.com/openssl/source/openssl-${OPENSSL_VERSION}.tar.gz" \
    "https://ftp.openssl.org/source/openssl-${OPENSSL_VERSION}.tar.gz"
  tar -xzf "$WORK/openssl.tar.gz" -C "$WORK" || return 1
  cd "$WORK/openssl-${OPENSSL_VERSION}" || return 1
  CC="$CC" AR="$AR" RANLIB="$RANLIB" \
    ./Configure linux-aarch64 --prefix="$PREFIX/openssl" no-shared no-tests no-ui-console \
    no-engine no-async no-afalgeng
  make -j"$(nproc)" build_libs || return 1
  make install_dev || return 1
}

# --------------------------------------------------------------------------
# aircrack-ng suite: aircrack-ng, airodump-ng, aireplay-ng, airmon-ng
# --------------------------------------------------------------------------
build_aircrack() {
  log "aircrack-ng ${AIRCRACK_VERSION}"
  download "$WORK/aircrack.tar.gz" \
    "https://aircrack-ng.org/aircrack-ng-${AIRCRACK_VERSION}.tar.gz" \
    "https://github.com/aircrack-ng/aircrack-ng/archive/refs/tags/${AIRCRACK_VERSION}.tar.gz"
  tar -xzf "$WORK/aircrack.tar.gz" -C "$WORK" || return 1
  cd "$WORK/aircrack-ng-${AIRCRACK_VERSION}" || return 1
  # The release tarball ships a generated configure script; the GitHub
  # snapshot does not and has to be bootstrapped with autogen.sh.
  if [[ ! -x ./configure ]]; then
    ./autogen.sh
  fi
  CC="$CC" AR="$AR" RANLIB="$RANLIB" \
    CFLAGS="-O2 -fPIC -I$PREFIX/openssl/include" \
    LDFLAGS="-L$PREFIX/openssl/lib -static" \
    ./configure --host=aarch64-linux-android --prefix="$PREFIX" \
      --without-libnl --without-udev --disable-etc \
      PKG_CONFIG_PATH="$PREFIX/openssl/lib/pkgconfig" \
      OPENSSL_PREFIX="$PREFIX/openssl"
  make -j"$(nproc)" || return 1
  # Binaries may be symlinked and airmon-ng is shipped as a script under
  # scripts/, so search the whole source tree and follow symlinks.
  for tool in aircrack-ng airodump-ng aireplay-ng airmon-ng; do
    binary="$(find -L . -type f -name "$tool" -print -quit)"
    if [[ -n "$binary" && -x "$binary" ]]; then
      install -m 0755 "$binary" "$ASSETS/$tool"
      # airmon-ng is a shell script; llvm-strip must only process ELF files.
      if "$READELF" -h "$ASSETS/$tool" >/dev/null 2>&1; then
        "$STRIP" "$ASSETS/$tool"
      fi
    else
      fatal "aircrack-ng: required tool $tool was not produced"
    fi
  done
}

# --------------------------------------------------------------------------
# hostapd (rogue access point)
# --------------------------------------------------------------------------
build_hostapd() {
  log "hostapd ${HOSTAPD_VERSION}"
  download "$WORK/hostapd.tar.gz" \
    "https://w1.fi/releases/hostapd-${HOSTAPD_VERSION}.tar.gz" \
    "https://w1.fi/cgit/hostap/snapshot/hostapd-${HOSTAPD_VERSION}.tar.gz"
  tar -xzf "$WORK/hostapd.tar.gz" -C "$WORK" || return 1
  cd "$WORK/hostapd-${HOSTAPD_VERSION}" || return 1
  if [[ -d hostapd ]]; then
    cd hostapd || return 1
  fi
  [[ -f Makefile ]] || fatal "hostapd: no Makefile found in $(pwd)"
  if [[ ! -f .config ]]; then
    [[ -f defconfig ]] || fatal "hostapd: defconfig missing in $(pwd)"
    cp defconfig .config || return 1
  fi
  if ! grep -q '^CONFIG_DRIVER_NL80211=y$' .config; then
    echo 'CONFIG_DRIVER_NL80211=y' >> .config
  fi
  # Do not pass CFLAGS/LDFLAGS on make's command line: GNU make then
  # suppresses hostapd's own include flags (notably -I../src), causing
  # utils/includes.h failures. hostapd supports EXTRA_CFLAGS/EXTRA_LDFLAGS.
  make -j"$(nproc)" CC="$CC" AR="$AR" RANLIB="$RANLIB" \
    EXTRA_CFLAGS="-I$PREFIX/openssl/include -D__ANDROID_API__=23" \
    EXTRA_LDFLAGS="-L$PREFIX/openssl/lib -static" \
    CONFIG_LIBNL=n CONFIG_IEEE8021AX=n CONFIG_DRIVER_NL80211=y || return 1
  install -m 0755 hostapd "$ASSETS/hostapd" || return 1
  "$STRIP" "$ASSETS/hostapd"
}

# --------------------------------------------------------------------------
# arpspoof: built from the sources in this repository so the result is
# reproducible and does not depend on an external mirror.
# --------------------------------------------------------------------------
build_arpspoof() {
  log "arpspoof (bundled source)"
  local source="$ROOT/tools/native/arpspoof.c"
  [[ -f "$source" ]] || fatal "missing $source"
  "$CC" -O2 -fPIE -pie \
    -o "$WORK/arpspoof" "$source"
  install -m 0755 "$WORK/arpspoof" "$ASSETS/arpspoof" || return 1
  "$STRIP" "$ASSETS/arpspoof"
}

# --------------------------------------------------------------------------
# main
# --------------------------------------------------------------------------
run_step() {
  local name="$1"; shift
  if "$@"; then
    return 0
  fi
  if [[ "${WIFITESTER_STRICT_TOOLCHAIN:-0}" == "1" ]]; then
    fatal "$name failed"
  fi
  warn "$name failed; the corresponding feature stays unavailable in the app"
  return 1
}

if [[ "${WIFITESTER_STRICT_TOOLCHAIN:-0}" == "1" ]]; then
  check_prerequisites || fatal "required host build dependencies are missing"
else
  check_prerequisites || warn "host build dependencies are missing; native tools may be skipped"
fi

run_step "libpcap" build_libpcap || true
run_step "tcpdump" build_tcpdump || true

# The remaining tools depend on the crypto backend.
if run_step "openssl" build_openssl; then
  run_step "aircrack-ng" build_aircrack || true
  run_step "hostapd" build_hostapd || true
else
  warn "skipping aircrack-ng and hostapd: no crypto backend"
fi

# arpspoof is built from the sources in this repository and needs no
# external dependency, so it is built even without the other tools.
run_step "arpspoof" build_arpspoof || true

log "packaged tools"
packaged=0
for binary in "$ASSETS"/*; do
  [[ -f "$binary" ]] || continue
  packaged=$((packaged + 1))
  printf '%-24s %8s bytes\n' "$(basename "$binary")" "$(stat -c %s "$binary")"
  "$READELF" -h "$binary" | grep -E 'Class:|Machine:' | sed 's/^/    /'
done
if [[ $packaged -eq 0 ]]; then
  fatal "no tool was produced; see the errors above"
fi
