#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
NDK_ROOT="${ANDROID_NDK_HOME:-$SDK_ROOT/ndk/27.0.12077973}"
GOMOBILE_BIN="${GOMOBILE_BIN:-$(command -v gomobile || true)}"
GRADLE_BIN="${GRADLE_BIN:-}"
BUILD_TYPE="${BUILD_TYPE:-debug}"

case "$BUILD_TYPE" in
    debug)
        GRADLE_TASK="assembleDebug"
        ;;
    release)
        GRADLE_TASK="assembleRelease"
        : "${ANDROID_KEYSTORE_FILE:?Set ANDROID_KEYSTORE_FILE for a release build}"
        : "${ANDROID_KEYSTORE_PASSWORD:?Set ANDROID_KEYSTORE_PASSWORD for a release build}"
        : "${ANDROID_KEY_ALIAS:?Set ANDROID_KEY_ALIAS for a release build}"
        : "${ANDROID_KEY_PASSWORD:?Set ANDROID_KEY_PASSWORD for a release build}"
        ;;
    *)
        echo "Unsupported BUILD_TYPE: $BUILD_TYPE (use debug or release)"
        exit 1
        ;;
esac

if [ -z "$GRADLE_BIN" ]; then
    GRADLE_BIN="$(command -v gradle || true)"
fi
if [ -z "$GRADLE_BIN" ]; then
    GRADLE_BIN="$(find "$HOME/.gradle/wrapper/dists" -path '*/gradle-8.14.3/bin/gradle' -type f 2>/dev/null | head -n 1 || true)"
fi

if [ -z "$SDK_ROOT" ] || [ ! -d "$SDK_ROOT" ]; then
    echo "Android SDK not found. Set ANDROID_SDK_ROOT or ANDROID_HOME."
    exit 1
fi
if [ ! -x "$GOMOBILE_BIN" ]; then
    echo "gomobile not found in PATH."
    echo "Install it with: go install golang.org/x/mobile/cmd/gomobile@latest"
    exit 1
fi
if [ ! -x "$GRADLE_BIN" ]; then
    echo "Gradle 8.14.3 not found. Set GRADLE_BIN to its executable."
    exit 1
fi
if [ ! -d "$NDK_ROOT" ]; then
    echo "Android NDK not found at $NDK_ROOT"
    exit 1
fi

mkdir -p "$SCRIPT_DIR/android/app/libs" "$SCRIPT_DIR/dist"
rm -f "$SCRIPT_DIR/dist"/OpenFlux-android-*-$BUILD_TYPE.apk

export ANDROID_HOME="$SDK_ROOT"
export ANDROID_SDK_ROOT="$SDK_ROOT"
export ANDROID_NDK_HOME="$NDK_ROOT"
export PATH="$(dirname -- "$GOMOBILE_BIN"):$PATH"

if [ ! -f "$SCRIPT_DIR/core/mobile/go.mod" ]; then
    echo "The core submodule is missing. Run: git submodule update --init"
    exit 1
fi

(
    # The core (and its gomobile bridge) is the upstream repository,
    # checked out as the core/ submodule.
    cd "$SCRIPT_DIR/core/mobile"
    # github.com/wlynxg/anet (pulled in transitively by the oneme/WebRTC
    # transport) still uses a //go:linkname into net.zoneCache that Go's
    # linker rejects by default since the 1.23 linkname hardening; no
    # release of anet has adapted to it yet. -checklinkname=0 downgrades
    # that to the old permissive behavior instead of a hard link failure.
    "$GOMOBILE_BIN" bind \
        -target=android \
        -androidapi=26 \
        -javapkg=io.openflux.bridge \
        -ldflags="-checklinkname=0" \
        -o "$SCRIPT_DIR/android/app/libs/openflux.aar" \
        .
)

(
    cd "$SCRIPT_DIR/android"
    "$GRADLE_BIN" --no-daemon "$GRADLE_TASK"
)

OUTPUT_DIR="$SCRIPT_DIR/android/app/build/outputs/apk/$BUILD_TYPE"
for ABI in universal arm64-v8a armeabi-v7a x86_64 x86; do
    SOURCE_APK="$OUTPUT_DIR/app-$ABI-$BUILD_TYPE.apk"
    if [ ! -f "$SOURCE_APK" ]; then
        echo "Expected APK not found: $SOURCE_APK"
        exit 1
    fi
    cp "$SOURCE_APK" "$SCRIPT_DIR/dist/OpenFlux-android-$ABI-$BUILD_TYPE.apk"
done

echo "Built APKs:"
find "$SCRIPT_DIR/dist" -maxdepth 1 -name "OpenFlux-android-*-$BUILD_TYPE.apk" -print | sort
