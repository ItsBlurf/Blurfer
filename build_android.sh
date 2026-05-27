#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ANDROID_DIR="$ROOT_DIR/android"
DIST_DIR="$ROOT_DIR/dist"

if [ -z "${JAVA_HOME:-}" ] && [ -x "$HOME/scoop/apps/openjdk17/current/bin/javac.exe" ]; then
    export JAVA_HOME="$HOME/scoop/apps/openjdk17/current"
fi

if [ -n "${JAVA_HOME:-}" ]; then
    export PATH="$JAVA_HOME/bin:$PATH"
fi

if [ -z "${ANDROID_HOME:-}" ] && [ -z "${ANDROID_SDK_ROOT:-}" ] && [ -x "$HOME/scoop/apps/android-clt/current/cmdline-tools/latest/bin/sdkmanager.bat" ]; then
    export ANDROID_HOME="$HOME/scoop/apps/android-clt/current"
    export ANDROID_SDK_ROOT="$ANDROID_HOME"
fi

if [ -n "${ANDROID_HOME:-}" ]; then
    export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"
fi

cd "$ANDROID_DIR"

if [ -x "$ANDROID_DIR/gradlew" ]; then
    GRADLE_CMD="$ANDROID_DIR/gradlew"
elif command -v gradle >/dev/null 2>&1; then
    GRADLE_CMD="gradle"
else
    echo "Gradle was not found. Install Android Studio or Gradle, then rerun this script." >&2
    exit 1
fi

if [ -z "${ANDROID_HOME:-}" ] && [ -z "${ANDROID_SDK_ROOT:-}" ]; then
    echo "Android SDK was not found. Set ANDROID_HOME or ANDROID_SDK_ROOT, or open the android folder in Android Studio." >&2
    exit 1
fi

"$GRADLE_CMD" :app:assembleDebug

mkdir -p "$DIST_DIR"
cp "$ANDROID_DIR/app/build/outputs/apk/debug/app-debug.apk" "$DIST_DIR/Blurfer.apk"

echo
echo "Built: $ANDROID_DIR/app/build/outputs/apk/debug/app-debug.apk"
echo "Copied: $DIST_DIR/Blurfer.apk"
