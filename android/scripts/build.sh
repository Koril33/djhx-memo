#!/usr/bin/env bash
set -euo pipefail
android_root="$(cd "$(dirname "$0")/.." && pwd)"
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}"
export ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
"$android_root/scripts/build-native.sh"
cd "$android_root"
./gradlew --no-daemon testDebugUnitTest detekt lintDebug assembleDebug assembleRelease "$@"
