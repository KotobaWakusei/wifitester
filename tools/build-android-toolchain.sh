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
# configure's AC_PROG_LD otherwise picks the HOST linker, which rejects the
# arm64 objects at final link ("Relocations in generic ELF (EM: 183)").
LD="$TOOLCHAIN/bin/ld"
CXX="$TOOLCHAIN/bin/aarch64-linux-android23-clang++"
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
  # The aircrack-ng source archive ships no generated configure script, so the
  # autotools are needed as well; without them autogen.sh aborts and the whole
  # strict build stops with a confusing "no such file: ./configure".
  command -v libtoolize >/dev/null 2>&1 || missing+=("libtool")
  command -v autoconf >/dev/null 2>&1 || missing+=("autoconf")
  command -v automake >/dev/null 2>&1 || missing+=("automake")
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
# TLS segment alignment
#
# Bionic refuses to run an ARM64 executable whose PT_TLS is underaligned, and
# it also rejects a segment whose virtual address is not aligned to p_align
# ("alignment is 64 (skew 48)"). Rewriting p_align in the ELF header does NOT
# fix that: it only makes the two values disagree.
#
# The tools are therefore linked the way Android links its own binaries: libc
# stays dynamic (only libpcap is static), so lld emits a correct segment, and
# this check rejects anything that would not start on the device.
# --------------------------------------------------------------------------
tls_ok() {
  local binary="$1"
  python3 - "$binary" <<'PYTHON'
import struct, sys
path = sys.argv[1]
with open(path, 'rb') as handle:
    data = handle.read()
if data[:4] != b'\x7fELF' or data[4] != 2 or data[5] != 1:
    sys.exit(0)                          # not a little-endian ELF64 file
e_phoff, = struct.unpack_from('<Q', data, 0x20)
e_phentsize, e_phnum = struct.unpack_from('<HH', data, 0x36)
for index in range(e_phnum):
    offset = e_phoff + index * e_phentsize
    if struct.unpack_from('<I', data, offset)[0] != 7:   # PT_TLS
        continue
    p_offset, p_vaddr = struct.unpack_from('<QQ', data, offset + 8)
    p_align, = struct.unpack_from('<Q', data, offset + 0x30)
    if p_align < 64 or p_vaddr % p_align != 0:
        sys.exit(1)
sys.exit(0)
PYTHON
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
  # libpcap is linked statically; libc must stay dynamic. A fully static link
  # keeps PT_TLS at 8-byte alignment on ARM64, which Bionic rejects, so the
  # binary would be packaged but never start on the device.
  CC="$CC" AR="$AR" RANLIB="$RANLIB" CFLAGS="-O2 -fPIC" \
    LIBS="$PREFIX/lib/libpcap.a" ./configure \
    --host=aarch64-linux-android --prefix="$PREFIX" --with-crypto=no
  make -j"$(nproc)" || return 1
  install -m 0755 tcpdump "$WORK/tcpdump" || return 1
  "$STRIP" "$WORK/tcpdump"
  if ! tls_ok "$WORK/tcpdump"; then
    fatal "tcpdump: PT_TLS is underaligned or skewed; Bionic would refuse to run it"
  fi
  install -m 0755 "$WORK/tcpdump" "$ASSETS/tcpdump" || return 1
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
  # Android Bionic at API 23 does not expose nl_langinfo(CODESET). The
  # Android terminal environment is UTF-8, so use that directly in the TUI.
  local console_src="lib/libac/tui/console.c"
  if [[ -f "$console_src" ]] && grep -q 'char \* codepage = nl_langinfo(CODESET);' "$console_src"; then
    sed -i 's/char \* codepage = nl_langinfo(CODESET);/char * codepage = "UTF-8";/' "$console_src"
  else
    fatal "aircrack-ng: expected nl_langinfo call not found in $console_src"
  fi
  # The release tarball ships a generated configure script; the GitHub
  # snapshot does not and has to be bootstrapped with autogen.sh.
  if [[ ! -x ./configure ]]; then
    ./autogen.sh || fatal "aircrack-ng: autogen.sh failed (install libtool, autoconf and automake)"
  fi
  # Android uses UTF-8 for app process text. Aircrack-ng 1.7 calls
  # nl_langinfo(CODESET) in its terminal helper, but that declaration is not
  # available with the API-23 NDK headers. Avoid this host-locale probe for the
  # Android build; the console helper can safely use UTF-8 directly.
  if [[ -f lib/libac/tui/console.c ]]; then
    sed -i 's/nl_langinfo(CODESET)/"UTF-8"/g' lib/libac/tui/console.c
  fi
  # These are configure environment variables, not positional configure
  # arguments. CPPFLAGS is required for the OpenSSL probe (CFLAGS alone is
  # overwritten by some generated makefiles); LIBS must include both static
  # OpenSSL archives and Android's dl/pthread compatibility flags.
  CC="$CC" CXX="$CXX" AR="$AR" RANLIB="$RANLIB" \
    CPPFLAGS="-I$PREFIX/openssl/include -I$PREFIX/include" \
    CFLAGS="-O2 -fPIC" \
    CXXFLAGS="-O2 -fPIC" \
    LDFLAGS="-L$PREFIX/openssl/lib -L$PREFIX/lib -static-libstdc++" \
    LIBS="-lssl -lcrypto -ldl -pthread" \
    PKG_CONFIG_PATH="$PREFIX/openssl/lib/pkgconfig" \
    OPENSSL_CFLAGS="-I$PREFIX/openssl/include" \
    OPENSSL_LIBS="-L$PREFIX/openssl/lib -lssl -lcrypto" \
    ./configure --host=aarch64-linux-android --prefix="$PREFIX" \
      --without-libnl --without-udev --disable-etc --disable-gcrypt
  make -j"$(nproc)" || return 1
  # Install through upstream's install rules. The linked executables are
  # commonly emitted under .libs/ and airmon-ng is installed as a script;
  # searching source files directly can miss these outputs or select wrappers.
  make install || return 1
  for tool in aircrack-ng airodump-ng aireplay-ng airmon-ng; do
    binary=""
    for candidate in "$PREFIX/bin/$tool" "$PREFIX/sbin/$tool"; do
      if [[ -f "$candidate" && -x "$candidate" ]]; then
        binary="$candidate"
        break
      fi
    done
    if [[ -z "$binary" ]]; then
      binary="$(find -L "$PREFIX" -type f -name "$tool" -print -quit)"
    fi
    if [[ -n "$binary" && -f "$binary" && -x "$binary" ]]; then
      install -m 0755 "$binary" "$ASSETS/$tool"
      # airmon-ng is a shell script; strip only ELF executables.
      if "$READELF" -h "$ASSETS/$tool" >/dev/null 2>&1; then
        "$STRIP" "$ASSETS/$tool"
      fi
    else
      fatal "aircrack-ng: required installed tool $tool missing under $PREFIX"
    fi
  done
  # Package shared libraries needed by the aircrack-ng suite so the device can
  # run them even when the system lacks the matching libraries.
  mkdir -p "$ASSETS/lib"
  for lib in "$PREFIX/lib/libaircrack-osdep-1.7.0.so" "$PREFIX/lib/libaircrack-ce-wpa.so" \
             "$PREFIX/lib/libz.so" "$PREFIX/lib/libssl.so" "$PREFIX/lib/libcrypto.so"; do
    if [[ -f "$lib" ]]; then
      install -m 0755 "$lib" "$ASSETS/lib/"
    fi
  done
  # Fix RUNPATH in packaged ELF executables: replace build-time temp dir with
  # $ORIGIN/../lib so the device can find the bundled shared libraries.
  python3 - "$ASSETS" <<'PYEOF'
import struct, sys, os

assets = sys.argv[1]
lib_dir = os.path.join(assets, "lib")
if not os.path.isdir(lib_dir):
    sys.exit(0)

for name in os.listdir(assets):
    path = os.path.join(assets, name)
    if not os.path.isfile(path):
        continue
    with open(path, "rb") as f:
        data = f.read()
    if data[:4] != b"\x7fELF":
        continue
    # Parse dynamic section to find RUNPATH string offset
    e_phoff = struct.unpack_from("<Q", data, 0x20)[0]
    e_phentsize, e_phnum = struct.unpack_from("<HH", data, 0x36)
    # Find PT_DYNAMIC
    dyn_offset = None
    for i in range(e_phnum):
        off = e_phoff + i * e_phentsize
        if struct.unpack_from("<I", data, off)[0] == 2:  # PT_DYNAMIC
            dyn_offset = struct.unpack_from("<Q", data, off + 8)[0]
            break
    if dyn_offset is None:
        continue
    # Walk dynamic entries to find RUNPATH (DT_RUNPATH = 0x1d)
    ptr = dyn_offset
    runpath_str_offset = None
    while True:
        tag, val = struct.unpack_from("<qq", data, ptr)
        if tag == 0:  # DT_NULL
            break
        if tag == 0x1d:  # DT_RUNPATH
            runpath_str_offset = val
            break
        ptr += 16
    if runpath_str_offset is None:
        continue
    # Read the RUNPATH string
    str_end = data.index(b"\x00", runpath_str_offset)
    old_runpath = data[runpath_str_offset:str_end].decode("utf-8", errors="replace")
    # Replace any absolute build paths with $ORIGIN/../lib
    new_runpath = old_runpath
    for part in old_runpath.split(":"):
        if part.startswith("/tmp/") or part.startswith("/build/") or "prefix/lib" in part:
            new_runpath = new_runpath.replace(part, "$ORIGIN/../lib")
    if new_runpath != old_runpath:
        new_bytes = new_runpath.encode("utf-8") + b"\x00"
        # Pad to same length
        if len(new_bytes) <= len(old_runpath) + 1:
            new_bytes = new_bytes + b"\x00" * (len(old_runpath) + 1 - len(new_bytes))
            data = data[:runpath_str_offset] + new_bytes + data[str_end + 1:]
            with open(path, "wb") as f:
                f.write(data)
            print(f"Fixed RUNPATH in {name}: {old_runpath} -> {new_runpath}")
        else:
            print(f"Skipped {name}: new RUNPATH too long")
PYEOF
}

# --------------------------------------------------------------------------
# libnl (static): the only nl80211 access library upstream hostapd accepts,
# and the NDK ships nothing like it.
# --------------------------------------------------------------------------
build_libnl() {
  log "libnl"
  download "$WORK/libnl.tar.gz" \
    "https://github.com/thom311/libnl/releases/download/libnl3_11_0/libnl-3.11.0.tar.gz" \
    "https://github.com/thom311/libnl/archive/refs/tags/libnl3_11_0.tar.gz" || return 1
  mkdir -p "$WORK/libnl"
  tar -xzf "$WORK/libnl.tar.gz" -C "$WORK/libnl" --strip-components=1 || return 1
  cd "$WORK/libnl" || return 1
  # The tag snapshot has no generated configure script.
  [[ -f ./configure ]] || autoreconf -fi >/dev/null 2>&1 || return 1
  # Bionic ships pthread inside libc, so the libpthread probe fails; bypass
  # it with the exact autoconf cache variable the check uses. Modern bionic
  # also stopped declaring in_addr_t anywhere in the include chain while
  # arpa/inet.h still uses it, hence the alias.
  CC="$CC" LD="$LD" AR="$AR" RANLIB="$RANLIB" \
    CFLAGS="-O2 -fPIC -D_GNU_SOURCE -Din_addr_t=uint32_t" \
    ac_cv_lib_pthread_pthread_mutex_lock=yes \
    ./configure --host=aarch64-linux-android23 --prefix="$PREFIX" \
      --disable-shared --enable-static --disable-cli --disable-debug \
      >/dev/null || return 1
  if ! make -j"$(nproc)" >/dev/null 2>&1; then
    make -j1 > "$WORK/libnl-make.log" 2>&1 || true
    head -n 60 "$WORK/libnl-make.log"
    return 1
  fi
  make install >/dev/null || return 1
}

# --------------------------------------------------------------------------
# wireless-tools (iwconfig/iwlist/iwpriv): aircrack-ng compiled without
# libnl initializes the wireless card through these classic tools. Without
# them airodump-ng exits with "Required wireless tools ... could not be
# found".
# --------------------------------------------------------------------------
build_iwtools() {
  log "wireless-tools 30pre9 (vendored)"
  # Vendored in tools/vendor/: every public download mirror for
  # wireless-tools is dead (the HPL page is gone, the Debian pool only
  # carries 30~pre9 and distfiles 404s). A fixed tarball keeps the build
  # reproducible; license GPL-2.0, same family as this repository.
  local vendored="$ROOT/tools/vendor/wireless-tools_30-pre9.orig.tar.gz"
  [[ -f "$vendored" ]] || fatal "vendored wireless-tools tarball missing: $vendored"
  mkdir -p "$WORK/iwtools"
  tar -xzf "$vendored" -C "$WORK/iwtools" --strip-components=1 || return 1
  cd "$WORK/iwtools" || return 1
  # STATIC=1 links libiw statically, so no extra shared library is needed on
  # the device. Build the three tools the osdep layer and the workbench use.
  make BUILD_STATIC=y CC="$CC" CFLAGS="-O2 -include strings.h" iwconfig iwlist iwpriv >/dev/null 2>&1 \
    || make BUILD_STATIC=y CC="$CC" CFLAGS="-O2 -include strings.h" iwconfig iwlist iwpriv || return 1
  install -m 0755 iwconfig iwlist iwpriv "$ASSETS/" || return 1
  for tool in iwconfig iwlist iwpriv; do
    "$STRIP" "$ASSETS/$tool"
  done
}

# --------------------------------------------------------------------------
# hostapd (rogue access point)
# --------------------------------------------------------------------------
build_hostapd() {
  log "hostapd ${HOSTAPD_VERSION}"
  # The nl80211 driver needs the statically cross-compiled libnl. Build it
  # BEFORE changing directory: build_libnl leaves the shell inside its own
  # source tree, and a make from there would silently do nothing.
  [[ -f "$PREFIX/lib/libnl-3.a" ]] || build_libnl || return 1
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
  # hostapd's Makefile uses "ifdef CONFIG_*", so a duplicate "=y" line still
  # counts; strip every existing definition of the ones we append. grep
  # returns 1 when nothing matches, so mv must not depend on its status.
  grep -vE '^CONFIG_(LIBNL32|LIBNL|LIBNL3|LIBNL_ROUTE3|LIBNL3_ROUTE|DRIVER_NL80211|DRIVER_HOSTAP|IEEE8021X_EAPOL)=' \
    .config > .config.new || true
  mv .config.new .config
  {
    echo 'CONFIG_DRIVER_NL80211=y'
    echo 'CONFIG_IEEE8021X_EAPOL=y'
  } >> .config
  # hostapd 2.11 builds out-of-tree: objects and the binary land in
  # ../build/<proj>/, so "make" can succeed without leaving ./hostapd.
  # V=1 keeps every command in the CI log for diagnosis.
  # ALL=hostapd skips hostapd_cli: its Makefile appends -lrt, which does
  # not exist in bionic (the APIs live in libc), and the app only needs the
  # daemon. hostapd 2.11 builds out-of-tree into ../build/<proj>/, so
  # locate the binary there (or in the source dir) before installing.
  make -j"$(nproc)" V=1 ALL=hostapd CC="$CC" AR="$AR" RANLIB="$RANLIB" \
    EXTRA_CFLAGS="-O2 -fPIC -I$PREFIX/openssl/include -I../src/utils -I$PREFIX/include/libnl3 -D__ANDROID_API__=23" \
    LIBS="-L$PREFIX/lib -lnl-genl-3 -lnl-3 -L$PREFIX/openssl/lib -lssl -lcrypto -lc -ldl" \
    EXTRA_LDFLAGS="-L$PREFIX/lib -L$PREFIX/openssl/lib" || return 1
  BUILT="$PWD/hostapd"
  [[ -f "$BUILT" ]] || BUILT="$PWD/../build/hostapd/hostapd"
  if [[ ! -f "$BUILT" ]]; then
    warn "hostapd: no binary after make; directory contents:"
    ls -la .
    ls -la ../build 2>/dev/null || true
    return 1
  fi
  install -m 0755 "$BUILT" "$ASSETS/hostapd" || return 1
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
  "$STRIP" "$WORK/arpspoof"
  if ! tls_ok "$WORK/arpspoof"; then
    fatal "arpspoof: PT_TLS is underaligned or skewed"
  fi
  install -m 0755 "$WORK/arpspoof" "$ASSETS/arpspoof" || return 1
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
  # wireless-tools must be in the bundled bin directory before aircrack-ng:
  # its osdep layer execs iwconfig to initialize the interface.
  run_step "wireless-tools" build_iwtools || true
  run_step "aircrack-ng" build_aircrack || true
  # hostapd's nl80211 backend needs the statically cross-compiled libnl
  # produced by build_libnl(); a failure here skips hostapd alone instead
  # of failing the whole APK build.
  run_step "hostapd" build_hostapd || true
else
  warn "skipping aircrack-ng and hostapd: no crypto backend"
fi

# arpspoof is built from the sources in this repository and needs no
# external dependency, so it is built even without the other tools.
run_step "arpspoof" build_arpspoof || true

log "verifying TLS alignment of bundled executables"
for binary in "$ASSETS"/*; do
  [[ -f "$binary" ]] || continue
  if "$READELF" -h "$binary" >/dev/null 2>&1; then
    if ! tls_ok "$binary"; then
      fatal "$(basename "$binary"): TLS segment alignment below 64 bytes"
    fi
  fi
done

log "packaged tools"
packaged=0
for binary in "$ASSETS"/*; do
  [[ -f "$binary" ]] || continue
  packaged=$((packaged + 1))
  printf '%-24s %8s bytes' "$(basename "$binary")" "$(stat -c %s "$binary")"
  if "$READELF" -h "$binary" >/dev/null 2>&1; then
    printf '\n'
    "$READELF" -h "$binary" | grep -E 'Class:|Machine:' | sed 's/^/    /'
  else
    # Scripts such as airmon-ng are valid bundled tools, but not ELF files.
    printf ' (script/non-ELF)\n'
  fi
done
if [[ $packaged -eq 0 ]]; then
  fatal "no tool was produced; see the errors above"
fi
