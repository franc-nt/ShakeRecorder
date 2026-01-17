#!/bin/bash

# ShakeRecorder Build Script
# Incrementa versão, compila e copia para pasta de release

set -e

# Configurações
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$SCRIPT_DIR"
BUILD_DIR="$PROJECT_DIR/build"
GRADLE_FILE="$PROJECT_DIR/app/build.gradle.kts"
APK_NAME="ShakeRecorder.apk"

# Ambiente (SDK fica na pasta pai)
export JAVA_HOME=/mnt/c/Users/Francisco/record/sdk/jdk-17.0.2
export ANDROID_HOME=/mnt/c/Users/Francisco/record/sdk
export PATH=$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH

echo "=========================================="
echo "  ShakeRecorder Build Script"
echo "=========================================="

# 1. Ler versão atual
CURRENT_VERSION_CODE=$(grep -oP 'versionCode = \K\d+' "$GRADLE_FILE")
CURRENT_VERSION_NAME=$(grep -oP 'versionName = "\K[^"]+' "$GRADLE_FILE")

echo ""
echo "Versão atual: $CURRENT_VERSION_NAME (code: $CURRENT_VERSION_CODE)"

# 2. Calcular nova versão
NEW_VERSION_CODE=$((CURRENT_VERSION_CODE + 1))

# Incrementar minor version (1.7 -> 1.8, 1.9 -> 1.10)
MAJOR=$(echo "$CURRENT_VERSION_NAME" | cut -d. -f1)
MINOR=$(echo "$CURRENT_VERSION_NAME" | cut -d. -f2)
NEW_MINOR=$((MINOR + 1))
NEW_VERSION_NAME="$MAJOR.$NEW_MINOR"

echo "Nova versão:  $NEW_VERSION_NAME (code: $NEW_VERSION_CODE)"
echo ""

# 3. Atualizar build.gradle.kts
sed -i "s/versionCode = $CURRENT_VERSION_CODE/versionCode = $NEW_VERSION_CODE/" "$GRADLE_FILE"
sed -i "s/versionName = \"$CURRENT_VERSION_NAME\"/versionName = \"$NEW_VERSION_NAME\"/" "$GRADLE_FILE"

echo "✓ build.gradle.kts atualizado"

# 4. Compilar
echo ""
echo "Compilando..."
cd "$PROJECT_DIR"
./gradlew assembleDebug --quiet

echo "✓ Build concluído"

# 5. Limpar pasta de release e copiar novo APK
APK_SOURCE="$PROJECT_DIR/app/build/outputs/apk/debug/app-debug.apk"
APK_DEST="$BUILD_DIR/$APK_NAME"

mkdir -p "$BUILD_DIR"
rm -f "$BUILD_DIR"/*.apk
cp "$APK_SOURCE" "$APK_DEST"

echo "✓ APK copiado para: $APK_DEST"

echo ""
echo "=========================================="
echo "  Build v$NEW_VERSION_NAME concluído!"
echo "=========================================="
