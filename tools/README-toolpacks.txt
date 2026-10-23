WiFiTester optional tool packages
=================================

These archives are downloaded on demand from
Settings > Tool package management.

Layout: every archive is a flat tar.gz whose entries are the tool binaries
(no directories). The app extracts each entry into its private
files/toolchain/bin and marks the package installed only when every tool is
present and executable.

  aircrack-ng, airodump-ng, aireplay-ng, airmon-ng
  hostapd
  reaver, wash

Build with tools/build-toolpacks.sh, upload next to the APK of the release
tag named by TOOL_PACKAGE_TAG in app/build.gradle.
