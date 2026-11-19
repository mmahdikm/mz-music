#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
: "${JAVA_HOME:?Set JAVA_HOME to JDK 17 or newer}"
: "${ANDROID_HOME:?Set ANDROID_HOME to an SDK with API 37 and build-tools 36.0.0}"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$PWD/.tooling/gradle-user}"
export ANDROID_USER_HOME="$PWD/.tooling/android-user"
mkdir -p dist "$ANDROID_USER_HOME"
python3 tools/prepare_signing.py
if [ "$#" -eq 0 ]; then
    ./gradlew :app:assembleRelease :app:testDebugUnitTest :app:lintRelease
    python3 tools/package_release.py
else
    ./gradlew "$@"
fi
