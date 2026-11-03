# Vendored: wireless-tools 30~pre9

`wireless-tools_30-pre9.orig.tar.gz` is the unmodified Debian upstream
tarball (`wireless-tools_30~pre9.orig.tar.gz`, the tilde renamed away for
URL friendliness) of Jean Tourrilhes' wireless-tools.

It is vendored because every public download mirror has disappeared: the
original HPL page is gone, distfiles.macports.org 404s for the 29 tarball,
and the Debian/Ubuntu pools only carry the current 30~pre9 version.

License: GPL-2.0 (compatible with this repository's GPL-2.0-or-later).
The cross-build uses `make STATIC=1 iwconfig iwlist iwpriv` so the tools
carry libiw statically and no extra shared library is needed on the
device. aircrack-ng, when compiled without libnl, initializes the
wireless card by exec'ing iwconfig - without these tools its osdep layer
exits with "Required wireless tools ... could not be found".
