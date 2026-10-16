#!/usr/bin/env bash
# Builds a signed debug APK WITHOUT the Android Gradle Plugin, for environments where Google's
# Maven/SDK hosts are unreachable. Uses: kotlinc, an android.jar to compile against (API 35),
# and the Debian/Ubuntu build-tools (aapt2, dx, zipalign, apksigner).
#
# On a normal machine prefer Android Studio / `./gradlew :app:assembleDebug`.
#
# Environment overrides:
#   KOTLINC       path to kotlinc               (default: kotlinc on PATH or /root/android-deps/kotlinc/bin/kotlinc)
#   ANDROID_JAR   framework jar to compile with (default: robolectric android-all API 35)
#   LINK_JAR      android.jar used by aapt2      (default: /usr/lib/android-sdk/platforms/android-23/android.jar)
#   BUILD_TOOLS   dir with dx/aapt2/zipalign     (default: /usr/lib/android-sdk/build-tools/debian)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
KOTLINC="${KOTLINC:-$(command -v kotlinc || echo /root/android-deps/kotlinc/bin/kotlinc)}"
KHOME="$(cd "$(dirname "$KOTLINC")/.." && pwd)"
ANDROID_JAR="${ANDROID_JAR:-/root/android-deps/android-all-15-robolectric-12650502.jar}"
LINK_JAR="${LINK_JAR:-/usr/lib/android-sdk/platforms/android-23/android.jar}"
BT="${BUILD_TOOLS:-/usr/lib/android-sdk/build-tools/debian}"
OUT="$ROOT/build/offline"
VERSION_CODE=1
VERSION_NAME="0.1.0"
MIN_SDK=31
TARGET_SDK=35

for f in "$KOTLINC" "$ANDROID_JAR" "$LINK_JAR" "$BT/dx" "$BT/aapt2"; do
  [ -e "$f" ] || { echo "faltando: $f" >&2; exit 1; }
done

rm -rf "$OUT"; mkdir -p "$OUT/classes" "$OUT/dex" "$OUT/res"
echo "[1/6] recursos (aapt2)"
"$BT/aapt2" compile --dir "$ROOT/app/src/main/res" -o "$OUT/res/compiled.zip"
"$BT/aapt2" link -o "$OUT/base.apk" -I "$LINK_JAR" \
  --manifest "$ROOT/app/src/main/AndroidManifest.xml" \
  --min-sdk-version $MIN_SDK --target-sdk-version $TARGET_SDK \
  --version-code $VERSION_CODE --version-name $VERSION_NAME \
  "$OUT/res/compiled.zip"

echo "[2/6] Kotlin (imaging + app)"
# Bytecode without invokedynamic: ART has no LambdaMetafactory and dx does not desugar.
"$KOTLINC" "$ROOT/imaging/src/main/kotlin" "$ROOT/app/src/main/kotlin" \
  -cp "$ANDROID_JAR" -jvm-target 1.8 -no-reflect \
  -Xlambdas=class -Xsam-conversions=class -Xstring-concat=inline \
  -d "$OUT/classes" 2>&1 | grep -v JAVA_TOOL_OPTIONS || true
[ -n "$(find "$OUT/classes" -name '*.class' | head -1)" ] || { echo "compilacao falhou" >&2; exit 1; }

echo "[3/6] verificando ausencia de invokedynamic"
STDLIB="$OUT/kotlin-stdlib-dx.jar"
( mkdir -p "$OUT/stdlib" && cd "$OUT/stdlib" && unzip -q -o "$KHOME/lib/kotlin-stdlib.jar" && rm -rf META-INF/versions && zip -q -r "$STDLIB" . )
INDY=$(cd "$OUT/classes" && find . -name '*.class' -print0 | xargs -0 javap -c -p 2>/dev/null | grep -c invokedynamic || true)
if [ "$INDY" != "0" ]; then echo "ERRO: $INDY invokedynamic no bytecode do app" >&2; exit 1; fi
python3 "$ROOT/tools/check-indy.py" "$OUT/classes" "$OUT/stdlib" 2>&1 | grep -v JAVA_TOOL_OPTIONS

echo "[4/6] dex (dx)"
"$BT/dx" --dex --min-sdk-version=26 --output="$OUT/dex/classes.dex" "$OUT/classes" "$STDLIB" 2>&1 | grep -v JAVA_TOOL_OPTIONS || true
[ -s "$OUT/dex/classes.dex" ] || { echo "dx falhou" >&2; exit 1; }

echo "[5/6] empacotando"
cp "$OUT/base.apk" "$OUT/unaligned.apk"
( cd "$OUT/dex" && zip -q "$OUT/unaligned.apk" classes.dex )
"$BT/zipalign" -f -p 4 "$OUT/unaligned.apk" "$OUT/aligned.apk"

echo "[6/6] assinando (chave de debug)"
mkdir -p "$ROOT/build/outputs"
APK="$ROOT/build/outputs/CameraPremium-debug.apk"
"$BT/apksigner" sign --ks "$ROOT/tools/debug.keystore" --ks-pass pass:android --key-pass pass:android \
  --ks-key-alias androiddebugkey --out "$APK" "$OUT/aligned.apk" 2>&1 | grep -v JAVA_TOOL_OPTIONS || true
"$BT/apksigner" verify "$APK" 2>&1 | grep -v JAVA_TOOL_OPTIONS || true
ls -la "$APK"
echo "APK: $APK"
