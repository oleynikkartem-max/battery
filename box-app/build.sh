#!/usr/bin/env bash
# Builds battery-hub.apk without Gradle: aapt2 (manifest) -> javac -> d8 -> zipalign -> apksigner.
# Tools: JDK 17 and Android SDK (platform 28, build-tools 34) in ~/.android-build.
#   bash build.sh            build + run the decoder test
set -euo pipefail
export MSYS_NO_PATHCONV=1
HERE=$(cd "$(dirname "$0")" && pwd)
T=${ANDROID_BUILD:-$HOME/.android-build}
JDK=$(ls -d "$T"/jdk-17*/ | head -1)
export JAVA_HOME=$(cygpath -w "$JDK")
BT="$T/sdk/build-tools/34.0.0"
JAR="$T/sdk/platforms/android-28/android.jar"
OUT="$HERE/out"
rm -rf "$OUT" && mkdir -p "$OUT/classes" "$OUT/dex"

echo "== decoder test"
"$JDK/bin/javac" -encoding UTF-8 -d "$(cygpath -w "$OUT/test")" "$(cygpath -w "$HERE/src/ua/battery/hub/Bms.java")" "$(cygpath -w "$HERE/test/BmsTest.java")"
"$JDK/bin/java" -cp "$(cygpath -w "$OUT/test")" BmsTest | tail -1

echo "== manifest"
"$BT/aapt2.exe" link --manifest "$(cygpath -w "$HERE/AndroidManifest.xml")" -I "$(cygpath -w "$JAR")" \
  --debug-mode -o "$(cygpath -w "$OUT/base.apk")"

echo "== java"
"$JDK/bin/javac" -encoding UTF-8 -source 1.8 -target 1.8 -Xlint:-options -classpath "$(cygpath -w "$JAR")" \
  -d "$(cygpath -w "$OUT/classes")" $(find "$HERE/src" -name '*.java' | while read -r f; do cygpath -w "$f"; done)
"$BT/d8.bat" --min-api 26 --lib "$(cygpath -w "$JAR")" --output "$(cygpath -w "$OUT/dex")" \
  $(find "$OUT/classes" -name '*.class' | while read -r f; do cygpath -w "$f"; done)

echo "== package"
python - "$(cygpath -w "$OUT/base.apk")" "$(cygpath -w "$OUT/dex/classes.dex")" <<'EOF'
import sys, zipfile
with zipfile.ZipFile(sys.argv[1], 'a', zipfile.ZIP_DEFLATED) as z:
    z.write(sys.argv[2], 'classes.dex')
EOF
"$BT/zipalign.exe" -f -p 4 "$(cygpath -w "$OUT/base.apk")" "$(cygpath -w "$OUT/aligned.apk")"
KS="$T/debug.keystore"
[ -f "$KS" ] || "$JDK/bin/keytool" -genkeypair -keystore "$(cygpath -w "$KS")" -storepass android -keypass android \
  -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US" >/dev/null 2>&1
"$BT/apksigner.bat" sign --ks "$(cygpath -w "$KS")" --ks-pass pass:android --out "$(cygpath -w "$OUT/battery-hub.apk")" \
  "$(cygpath -w "$OUT/aligned.apk")"
ls -l "$OUT/battery-hub.apk"
