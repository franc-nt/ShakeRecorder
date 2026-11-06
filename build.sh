#!/usr/bin/env bash
# Incrementa versão, valida, compila e copia o APK sem depender de um PC específico.
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
GRADLE_FILE="$PROJECT_DIR/app/build.gradle.kts"
BUILD_DIR="$PROJECT_DIR/build"

# Parse first: validation-only and release-retry modes must never be combined.
BUMP_VERSION=true
CHECK_ONLY=false
if [[ $# -eq 1 && "$1" == "--check-only" ]]; then
    CHECK_ONLY=true
elif [[ $# -eq 1 && "$1" == "--no-version-bump" ]]; then
    BUMP_VERSION=false
elif [[ $# -ne 0 ]]; then
    echo "Uso: ./build.sh [--check-only | --no-version-bump]" >&2
    exit 2
fi

if [[ -z "${JAVA_HOME:-}" ]]; then
    export JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")"
fi
if [[ -z "${ANDROID_HOME:-}" && -n "${ANDROID_SDK_ROOT:-}" ]]; then
    export ANDROID_HOME="$ANDROID_SDK_ROOT"
fi
if [[ -z "${ANDROID_HOME:-}" && -d "$PROJECT_DIR/../sdk" ]]; then
    export ANDROID_HOME="$PROJECT_DIR/../sdk"
fi
export PATH="$JAVA_HOME/bin:$PATH"
cd "$PROJECT_DIR"

if $CHECK_ONLY; then
    # No packaging/signing tasks: no APK, key, version bump or release artifacts.
    python3 -m unittest discover -s scripts -p 'test_*.py'
    ./gradlew testDebugUnitTest lintDebug lintRelease --console=plain
    echo "Validação concluída sem gerar APK ou alterar a versão."
    exit 0
fi

# Fail before modifying version or artifacts. Never create a key or fall back to debug.
for name in SHAKE_KEYSTORE_PATH SHAKE_KEYSTORE_PASSWORD SHAKE_KEY_ALIAS SHAKE_KEY_PASSWORD; do
    if [[ -z "${!name:-}" ]]; then
        echo "Missing required signing environment variable: $name" >&2
        exit 1
    fi
done
[[ -r "$SHAKE_KEYSTORE_PATH" ]] || { echo "Signing keystore is not readable" >&2; exit 1; }

CURRENT_CODE=$(sed -n 's/.*versionCode = \([0-9]*\).*/\1/p' "$GRADLE_FILE")
CURRENT_NAME=$(sed -n 's/.*versionName = "\([^"]*\)".*/\1/p' "$GRADLE_FILE")
if $BUMP_VERSION; then
    NEW_CODE=$((CURRENT_CODE + 1))
    NEW_NAME="${CURRENT_NAME%%.*}.$((${CURRENT_NAME#*.} + 1))"
    sed -i "s/versionCode = $CURRENT_CODE/versionCode = $NEW_CODE/; s/versionName = \"$CURRENT_NAME\"/versionName = \"$NEW_NAME\"/" "$GRADLE_FILE"
else
    NEW_NAME="$CURRENT_NAME"
fi
# A failed build must not leave an incremented version or replace the previous APK.
restore_version() {
    local result=$?
    if [[ $result -ne 0 ]] && $BUMP_VERSION; then
        sed -i "s/versionCode = $NEW_CODE/versionCode = $CURRENT_CODE/; s/versionName = \"$NEW_NAME\"/versionName = \"$CURRENT_NAME\"/" "$GRADLE_FILE"
    fi
    exit "$result"
}
trap restore_version EXIT

python3 -m unittest discover -s scripts -p 'test_*.py'
./gradlew testDebugUnitTest lintDebug lintRelease assembleRelease --console=plain
mkdir -p "$BUILD_DIR"
STAGING_DIR=$(mktemp -d "$BUILD_DIR/release.XXXXXX")
cp "$PROJECT_DIR/app/build/outputs/apk/release/app-release.apk" "$STAGING_DIR/ShakeRecorder.apk"
python3 scripts/release_metadata.py "$STAGING_DIR/ShakeRecorder.apk" "$STAGING_DIR/update.json"
mv "$STAGING_DIR/ShakeRecorder.apk" "$BUILD_DIR/ShakeRecorder.apk"
mv "$STAGING_DIR/update.json" "$BUILD_DIR/update.json"
rmdir "$STAGING_DIR"
echo "Build v$NEW_NAME validado: $BUILD_DIR/ShakeRecorder.apk"
