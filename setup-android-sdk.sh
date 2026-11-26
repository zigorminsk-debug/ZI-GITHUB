#!/usr/bin/env bash
# Установка окружения для сборки ZI Git (Android SDK + JDK 21 + R8).
# Запускать один раз после старта новой сессии: ./setup-android-sdk.sh
set -euo pipefail

SDK=/opt/android-sdk
BT=34.0.0
PLATFORM=android-34
JDK=/usr/lib/jvm/java-21-openjdk-amd64

if [ ! -x "$JDK/bin/javac" ]; then
  echo "==> Устанавливаю JDK 21"
  sudo -n apt-get update -qq
  sudo -n apt-get install -y -qq openjdk-21-jdk-headless
fi

if [ ! -x "$SDK/build-tools/$BT/aapt2" ]; then
  echo "==> Устанавливаю Android SDK (cmdline-tools + platform $PLATFORM + build-tools $BT)"
  sudo -n mkdir -p "$SDK/cmdline-tools"
  sudo -n chown -R "$(id -un):$(id -gn)" "$SDK"
  tmp=$(mktemp -d)
  curl -sSL -o "$tmp/ct.zip" https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
  unzip -q "$tmp/ct.zip" -d "$tmp"
  rm -rf "$SDK/cmdline-tools/latest"
  mv "$tmp/cmdline-tools" "$SDK/cmdline-tools/latest"
  rm -rf "$tmp"
  yes | "$SDK/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK" --licenses >/dev/null 2>&1 || true
  "$SDK/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK" \
      "platforms;$PLATFORM" "build-tools;$BT" >/dev/null
fi

APP_DIR="$(cd "$(dirname "$0")" && pwd)/ghactions-app"
if [ ! -f "$APP_DIR/lib/r8.jar" ]; then
  echo "==> Скачиваю R8 (dex-компилятор; встроенный d8 падает на JDK 21)"
  mkdir -p "$APP_DIR/lib"
  curl -sSL -o "$APP_DIR/lib/r8.jar" https://maven.google.com/com/android/tools/r8/8.2.42/r8-8.2.42.jar
fi

echo "==> Готово: $("$SDK/build-tools/$BT/aapt2" version), $("$JDK/bin/java" -version 2>&1 | head -1)"
echo "    Теперь можно собирать: ./build.sh"
