#!/usr/bin/env bash
# Сборка APK без Gradle: aapt2 -> javac -> d8 -> zipalign -> apksigner
# Локально и в GitHub Actions. Пути SDK/JDK берутся из окружения.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"

if [ -z "${JAVA_HOME:-}" ]; then
  if [ -x /usr/lib/jvm/java-21-openjdk-amd64/bin/javac ]; then
    export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
  elif [ -x /usr/lib/jvm/java-17-openjdk-amd64/bin/javac ]; then
    export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
  elif command -v javac >/dev/null 2>&1; then
    JAVA_BIN="$(command -v javac)"
    JAVA_BIN="$(readlink -f "$JAVA_BIN" 2>/dev/null || echo "$JAVA_BIN")"
    export JAVA_HOME="$(dirname "$(dirname "$JAVA_BIN")")"
  fi
fi
if [ -z "${JAVA_HOME:-}" ] || [ ! -x "$JAVA_HOME/bin/javac" ]; then
  echo "JAVA_HOME не задан и javac не найден. Запустите ./setup-android-sdk.sh"
  exit 1
fi

ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/opt/android-sdk}}"
export ANDROID_HOME
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$ANDROID_HOME}"

if [ -d "$ANDROID_HOME/build-tools/34.0.0" ] && [ -x "$ANDROID_HOME/build-tools/34.0.0/aapt2" ]; then
  BT="$ANDROID_HOME/build-tools/34.0.0"
else
  BT="$(ls -d "$ANDROID_HOME"/build-tools/* 2>/dev/null | sort -V | tail -1 || true)"
fi
if [ -z "${BT:-}" ] || [ ! -x "$BT/aapt2" ]; then
  echo "Android build-tools не найдены в $ANDROID_HOME"
  echo "Запустите ./setup-android-sdk.sh или задайте ANDROID_HOME"
  exit 1
fi

if [ -f "$ANDROID_HOME/platforms/android-34/android.jar" ]; then
  PLATFORM="$ANDROID_HOME/platforms/android-34/android.jar"
else
  PLATFORM="$(ls -d "$ANDROID_HOME"/platforms/android-* 2>/dev/null | sort -V | tail -1)/android.jar"
fi
if [ ! -f "$PLATFORM" ]; then
  echo "android.jar не найден в $ANDROID_HOME/platforms"
  exit 1
fi

if ! command -v zip >/dev/null 2>&1; then
  echo "Нужна утилита zip (apt install zip)"
  exit 1
fi

APP_DIR="$ROOT/ghactions-app"
BUILD="$APP_DIR/build"
OUT="$APP_DIR/out"
mkdir -p "$BUILD" "$OUT"

VER="$(sed -n 's/.*android:versionName="\([^"]*\)".*/\1/p' "$APP_DIR/AndroidManifest.xml" | head -1)"
VER="${VER:-dev}"

echo "==> SDK=$ANDROID_HOME"
echo "==> build-tools=$(basename "$BT")  java=$("$JAVA_HOME/bin/java" -version 2>&1 | head -1)"
echo "==> версия приложения: $VER"

echo "==> 1/6 Компиляция ресурсов (aapt2 compile)"
"$BT/aapt2" compile --dir "$APP_DIR/res" -o "$BUILD/res.zip"

echo "==> 2/6 Линковка ресурсов и манифеста (aapt2 link)"
"$BT/aapt2" link \
  -o "$BUILD/app-res.apk" \
  -I "$PLATFORM" \
  --manifest "$APP_DIR/AndroidManifest.xml" \
  -R "$BUILD/res.zip" \
  --java "$BUILD/gen" \
  --min-sdk-version 26 \
  --target-sdk-version 34 \
  --auto-add-overlay

echo "==> 3/6 Компиляция Java (javac)"
rm -rf "$BUILD/classes" && mkdir -p "$BUILD/classes"
find "$BUILD/gen" -name '*.java' > "$BUILD/sources.txt"
find "$APP_DIR/src" -name '*.java' >> "$BUILD/sources.txt"
if ! "$JAVA_HOME/bin/javac" -nowarn -source 17 -target 17 \
  -classpath "$PLATFORM" \
  -d "$BUILD/classes" \
  @"$BUILD/sources.txt" > "$BUILD/javac.log" 2>&1; then
  grep -v '^Note:' "$BUILD/javac.log" || true
  echo "!!! Ошибка компиляции Java"
  exit 1
fi

echo "==> 4/6 Dex (R8/D8)"
R8_JAR="$APP_DIR/lib/r8.jar"
if [ ! -f "$R8_JAR" ]; then
  mkdir -p "$APP_DIR/lib"
  curl -fsSL -o "$R8_JAR" https://maven.google.com/com/android/tools/r8/8.2.42/r8-8.2.42.jar
fi
rm -rf "$BUILD/dex" && mkdir -p "$BUILD/dex"
find "$BUILD/classes" -name '*.class' > "$BUILD/classes.txt"
"$JAVA_HOME/bin/java" -cp "$R8_JAR" com.android.tools.r8.D8 \
  --lib "$PLATFORM" --min-api 26 --output "$BUILD/dex" @"$BUILD/classes.txt"

echo "==> 5/6 Упаковка APK"
cp "$BUILD/app-res.apk" "$BUILD/app-unsigned.apk"
(cd "$BUILD/dex" && zip -q -X "$BUILD/app-unsigned.apk" classes.dex)
"$BT/zipalign" -f 4 "$BUILD/app-unsigned.apk" "$BUILD/app-aligned.apk"

echo "==> 6/6 Подпись (постоянный release-ключ ZI Git)"
# ВСЕГДА используем keystore из репозитория — это гарантирует,
# что подпись совпадает и обновления ставятся поверх.
KS="${ZIGIT_KEYSTORE:-$ROOT/keystore/zigit-release.jks}"
KS_PASS="${ZIGIT_KEYSTORE_PASS:-ZigitRelease2026!}"
KEY_ALIAS="${ZIGIT_KEY_ALIAS:-zigit}"
KEY_PASS="${ZIGIT_KEY_PASS:-$KS_PASS}"
if [ ! -f "$KS" ]; then
  echo "    ключ не найден, создаю новый: $KS"
  mkdir -p "$(dirname "$KS")"
  "$JAVA_HOME/bin/keytool" -genkeypair -keystore "$KS" -storetype PKCS12 \
    -storepass "$KS_PASS" -keypass "$KEY_PASS" -alias "$KEY_ALIAS" \
    -dname "CN=ZI Git, O=ZI, C=BY" -keyalg RSA -keysize 4096 -validity 10950
fi
"$BT/apksigner" sign \
  --ks "$KS" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KEY_PASS" --ks-key-alias "$KEY_ALIAS" \
  --out "$OUT/ZIGit.apk" "$BUILD/app-aligned.apk"
"$BT/apksigner" verify --print-certs "$OUT/ZIGit.apk" | grep -E "DN|SHA-256|SHA-1" | head -3
"$BT/apksigner" verify -v "$OUT/ZIGit.apk" | grep -E "Verified using|Verifies"

cp -f "$OUT/ZIGit.apk" "$OUT/ZI-Git-v${VER}.apk"
ls -la "$OUT/ZIGit.apk" "$OUT/ZI-Git-v${VER}.apk"
echo "==> Готово: $OUT/ZI-Git-v${VER}.apk"
