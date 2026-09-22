#!/usr/bin/env bash
# アシスタントアプリのビルド（GitHub Actions / Linux 用）。Gradle 不使用
set -euo pipefail
cd "$(dirname "$0")"

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/usr/local/lib/android/sdk}}"
BT="$SDK/build-tools/$(ls "$SDK/build-tools" | grep -v rc | sort -V | tail -1)"
PLAT="$SDK/platforms/$(ls "$SDK/platforms" | grep -E '^android-[0-9]+$' | sort -V | tail -1)/android.jar"
echo "BUILD_TOOLS=$BT"
echo "PLATFORM=$PLAT"
java -version 2>&1 | head -1

rm -rf build && mkdir -p build/gen build/classes build/dex

echo "[1/7] aapt2 compile"
"$BT/aapt2" compile --dir res -o build/res.zip
echo "[2/7] aapt2 link"
"$BT/aapt2" link -o build/base.apk -I "$PLAT" --manifest AndroidManifest.xml \
  --java build/gen --min-sdk-version 29 --target-sdk-version 35 build/res.zip
echo "[3/7] javac"
find src build/gen -name '*.java' > build/sources.txt
javac -encoding UTF-8 -source 17 -target 17 -nowarn -Xlint:-options \
  -classpath "$PLAT" -d build/classes @build/sources.txt
echo "[4/7] d8"
(cd build/classes && jar cf ../classes.jar .)
"$BT/d8" --lib "$PLAT" --min-api 29 --output build/dex build/classes.jar
echo "[5/7] package"
cp build/base.apk build/unsigned.apk
(cd build/dex && zip -q ../unsigned.apk classes.dex)
echo "[6/7] zipalign"
"$BT/zipalign" -f -p 4 build/unsigned.apk build/aligned.apk
echo "[7/7] apksigner"
"$BT/apksigner" sign --ks release.keystore --ks-pass env:KS_PASS --key-pass env:KS_PASS \
  --ks-key-alias assistant --out Assistant.apk build/aligned.apk
rm -f Assistant.apk.idsig
"$BT/apksigner" verify --print-certs Assistant.apk | grep -E "SHA-256|DN"

# latest.json のバージョンをマニフェストに合わせる（notes はそのまま）
python3 - <<'PY'
import json, re
m = open('AndroidManifest.xml', encoding='utf-8').read()
vc = int(re.search(r'versionCode="(\d+)"', m).group(1))
vn = re.search(r'versionName="([^"]+)"', m).group(1)
d = json.load(open('latest.json', encoding='utf-8'))
d['versionCode'], d['versionName'] = vc, vn
with open('latest.json', 'w', encoding='utf-8') as f:
    json.dump(d, f, ensure_ascii=False, indent=2); f.write('\n')
print(f'latest.json -> {vc} / {vn}')
PY
echo "BUILD OK: $(stat -c %s Assistant.apk) bytes"
