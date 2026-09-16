#!/usr/bin/env bash
# Окружение для сборки ZI Git: JDK (если нет) + Android SDK 34 + R8.
# Работает локально и в GitHub Actions (без sudo, SDK в $HOME/android-sdk).
set -euo pipefail

BT_VER="${ANDROID_BUILD_TOOLS:-34.0.0}"
PLATFORM="${ANDROID_PLATFORM:-android-34}"

if [ -n "${ANDROID_HOME:-}" ]; then
  SDK="$ANDROID_HOME"
elif [ -n "${ANDROID_SDK_ROOT:-}" ]; then
  SDK="$ANDROID_SDK_ROOT"
elif [ -d /opt/android-sdk ] && [ -x "/opt/android-sdk/build-tools/${BT_VER}/aapt2" ]; then
  SDK=/opt/android-sdk
else
  SDK="${HOME}/android-sdk"
fi

export ANDROID_HOME="$SDK"
export ANDROID_SDK_ROOT="$SDK"
mkdir -p "$SDK"

need_packages=0
if [ ! -x "$SDK/build-tools/$BT_VER/aapt2" ]; then need_packages=1; fi
if [ ! -f "$SDK/platforms/$PLATFORM/android.jar" ]; then need_packages=1; fi

if [ "$need_packages" = 1 ]; then
  if [ ! -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]; then
    echo "==> Скачиваю Android cmdline-tools → $SDK"
    tmp=$(mktemp -d)
    curl -fsSL -o "$tmp/ct.zip" \
      https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
    unzip -q "$tmp/ct.zip" -d "$tmp"
    rm -rf "$SDK/cmdline-tools/latest"
    mkdir -p "$SDK/cmdline-tools"
    mv "$tmp/cmdline-tools" "$SDK/cmdline-tools/latest"
    rm -rf "$tmp"
  fi
  echo "==> Устанавливаю platforms;$PLATFORM и build-tools;$BT_VER"
  yes | "$SDK/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK" --licenses >/dev/null 2>&1 || true
  "$SDK/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK" \
      "platforms;$PLATFORM" "build-tools;$BT_VER"
fi

if ! command -v javac >/dev/null 2>&1; then
  if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/javac" ]; then
    :
  elif [ -x /usr/lib/jvm/java-21-openjdk-amd64/bin/javac ]; then
    export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
  elif [ -x /usr/lib/jvm/java-17-openjdk-amd64/bin/javac ]; then
    export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
  elif command -v sudo >/dev/null 2>&1 && sudo -n true 2>/dev/null; then
    echo "==> Устанавливаю JDK 17"
    sudo -n apt-get update -qq
    sudo -n apt-get install -y -qq openjdk-17-jdk-headless unzip zip
  else
    echo "javac не найден. Установите JDK 17+ или задайте JAVA_HOME."
    exit 1
  fi
fi

APP_DIR="$(cd "$(dirname "$0")" && pwd)/ghactions-app"
if [ ! -f "$APP_DIR/lib/r8.jar" ]; then
  echo "==> Скачиваю R8 (dex-компилятор)"
  mkdir -p "$APP_DIR/lib"
  curl -fsSL -o "$APP_DIR/lib/r8.jar" \
    https://maven.google.com/com/android/tools/r8/8.2.42/r8-8.2.42.jar
fi

if [ -n "${GITHUB_ENV:-}" ]; then
  {
    echo "ANDROID_HOME=$SDK"
    echo "ANDROID_SDK_ROOT=$SDK"
  } >> "$GITHUB_ENV"
fi

echo "==> Готово: SDK=$SDK"
"$SDK/build-tools/$BT_VER/aapt2" version
if command -v java >/dev/null 2>&1; then
  java -version 2>&1 | head -1
elif [ -n "${JAVA_HOME:-}" ]; then
  "$JAVA_HOME/bin/java" -version 2>&1 | head -1
fi
echo "    Сборка: ./build.sh"
