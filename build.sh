#!/bin/bash
# build.sh — Build DeviceTracker APK
# Usage: bash build.sh [debug|release]
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

# Source SDK if available
export ANDROID_HOME="${ANDROID_HOME:-/opt/android-sdk}"
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"

MODE="${1:-debug}"
echo "Building DeviceTracker ($MODE) using ANDROID_HOME=$ANDROID_HOME"
echo "Java: $(java -version 2>&1 | head -1)"

# Check for Gradle wrapper
if [ ! -f gradlew ]; then
    echo "Creating Gradle wrapper..."
    gradle wrapper --gradle-version=8.5 2>/dev/null || {
        echo "Downloading Gradle wrapper directly..."
        curl -sL "https://services.gradle.org/distributions/gradle-8.5-bin.zip" -o /tmp/gradle-8.5.zip
        unzip -q /tmp/gradle-8.5.zip -d /tmp/gradle-8.5
        /tmp/gradle-8.5/gradle-8.5/bin/gradle wrapper --gradle-version=8.5
        rm -f /tmp/gradle-8.5.zip
        rm -rf /tmp/gradle-8.5
    }
fi

echo "Building..."
if [ "$MODE" = "release" ]; then
    ./gradlew assembleRelease
    APK="app/build/outputs/apk/release/app-release.apk"
else
    ./gradlew assembleDebug
    APK="app/build/outputs/apk/debug/app-debug.apk"
fi

if [ -f "$APK" ]; then
    echo ""
    echo "✓ Build complete: $APK ($(ls -lh "$APK" | awk '{print $5}'))"
    echo "  adb install -r \"$APK\""
else
    echo "✗ Build failed — check build output above"
    exit 1
fi
