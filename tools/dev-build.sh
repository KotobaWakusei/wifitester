#!/bin/zsh
# Local build helper for the WiFiTester workspace.
#
#   ./tools/dev-build.sh            -> unit tests + debug APK
#   ./tools/dev-build.sh assembleDebug
#
# The toolchain lives under /tmp/opencode so the repository stays clean.
export JAVA_HOME=/tmp/opencode/jdk17
export ANDROID_SDK_ROOT=/tmp/opencode/androidsdk
export ANDROID_HOME=$ANDROID_SDK_ROOT
export PATH="$JAVA_HOME/bin:$ANDROID_SDK_ROOT/platform-tools:$PATH"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$HOME/.gradle}"

GRADLE=/tmp/opencode/gradle-8.7/bin/gradle
if [[ ! -x "$GRADLE" ]]; then
  echo "gradle 8.7 not found at $GRADLE" >&2
  exit 1
fi

cd "$(dirname "$0")/.." || exit 1
exec "$GRADLE" --offline=false --console=plain "$@"
