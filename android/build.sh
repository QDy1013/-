#!/usr/bin/env bash
# 不依赖 Gradle 的构建脚本：aapt2 + javac + d8 + zipalign + apksigner
# 这样整条链路可复现，也不需要在机器上装 Android Studio。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SDK="${SDK:-C:/Users/QD/WorkBuddy/2026-11-03-21-21-03/_sdk}"
BT="$SDK/build-tools/34.0.0"
ANDROID_JAR="$SDK/platforms/android-34/android.jar"
OUT="$ROOT/build"
KS="$ROOT/build/debug.keystore"

mkdir -p "$OUT"

if [ ! -f "$KS" ]; then
  keytool -genkeypair -v -keystore "$KS" -storepass android -keypass android \
    -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Android Debug,O=Android,C=US" >/dev/null 2>&1
  echo "生成调试签名"
fi

build_app () {
  local name="$1" dir="$2"
  local b="$OUT/$name"
  rm -rf "$b" && mkdir -p "$b/res" "$b/classes" "$b/dex"

  echo "== [$name] 编译资源"
  "$BT/aapt2.exe" compile --dir "$dir/res" -o "$b/res.zip" >/dev/null

  echo "== [$name] 链接资源"
  if [ -d "$dir/assets" ]; then
    "$BT/aapt2.exe" link -o "$b/base.apk" -I "$ANDROID_JAR" \
      --manifest "$dir/AndroidManifest.xml" -R "$b/res.zip" \
      -A "$dir/assets" --min-sdk-version 24 --target-sdk-version 34 --auto-add-overlay
  else
    "$BT/aapt2.exe" link -o "$b/base.apk" -I "$ANDROID_JAR" \
      --manifest "$dir/AndroidManifest.xml" -R "$b/res.zip" \
      --min-sdk-version 24 --target-sdk-version 34 --auto-add-overlay
  fi

  echo "== [$name] 编译 Java"
  find "$dir/src" -name "*.java" > "$b/sources.txt"
  mapfile -t SRC < "$b/sources.txt"
  javac -nowarn -encoding UTF-8 --release 11 -classpath "$ANDROID_JAR" \
    -d "$b/classes" "${SRC[@]}" > "$b/javac.log" 2>&1 || true
  if [ ! -d "$b/classes/com" ]; then echo "!! javac 失败"; sed -n '1,40p' "$b/javac.log"; exit 1; fi

  echo "== [$name] dex"
  "$BT/d8.bat" --lib "$ANDROID_JAR" --min-api 24 --output "$b/dex" \
    $(find "$b/classes" -name "*.class") >/dev/null

  echo "== [$name] 打包"
  python3 - "$b/base.apk" "$b/dex/classes.dex" <<'PY'
import sys, zipfile
apk, dex = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(apk, 'a', zipfile.ZIP_DEFLATED) as z:
    z.write(dex, 'classes.dex')
PY
  "$BT/zipalign.exe" -f 4 "$b/base.apk" "$b/aligned.apk"
  "$BT/apksigner.bat" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
    --v1-signing-enabled true --v2-signing-enabled true \
    --out "$OUT/$name.apk" "$b/aligned.apk"

  echo "== [$name] OK → $OUT/$name.apk ($(du -k "$OUT/$name.apk" | cut -f1) KB)"
}

build_app "nopalou" "$ROOT/app"
build_app "replay" "$ROOT/replay"
echo "全部完成"
