#!/bin/bash
# ./build.sh -> output/LocalKey-AI-v8.2-arm64.apk
# Signing: set AIK_KEYSTORE, AIK_KEY_ALIAS, AIK_STORE_PASS, AIK_KEY_PASS (or put them in gitignored local.properties as KEY=VALUE lines)
set -e
[ -f /workspace/apkport/env.sh ] && . /workspace/apkport/env.sh
R=$(cd "$(dirname "$0")" && pwd); cd $R
SDK=${ANDROID_SDK:-/workspace/tools/sdk}; BT=${BUILD_TOOLS:-$SDK/build-tools/36.0.0}; AJ=${ANDROID_JAR:-$SDK/platforms/android-36/android.jar}
[ -f local.properties ] && { set -a; . ./local.properties; set +a; }
: "${AIK_KEYSTORE:?set AIK_KEYSTORE (path to release .jks)}" "${AIK_KEY_ALIAS:?}" "${AIK_STORE_PASS:?}" "${AIK_KEY_PASS:=$AIK_STORE_PASS}"
VERSION_CODE=${VERSION_CODE:-10}; VERSION_NAME=${VERSION_NAME:-8.2}
W=$R/work; rm -rf $W; mkdir -p $W/res $W/cls $W/dex $W/gen
$BT/aapt2 compile --dir app/res -o $W/res/res.zip
$BT/aapt2 link -I $AJ --manifest app/AndroidManifest.xml -o $W/base.apk --java $W/gen $W/res/res.zip --min-sdk-version 30 --target-sdk-version 36 --version-code $VERSION_CODE --version-name $VERSION_NAME
javac -nowarn --release 11 -cp $AJ -d $W/cls $(find app/java $W/gen -name '*.java') 2>&1 | grep -v "^Note:" || true
test -f $W/cls/com/jason/aikeyboard/AIKeyboardService.class
$BT/d8 --release --min-api 30 --lib $AJ --output $W/dex $(find $W/cls -name '*.class')
# assemble: model first so its offset is easy to align; native lib + model STORED; everything else deflated
python3 - $W <<'PY'
import zipfile, sys, os
W = sys.argv[1]; R = os.path.dirname(W)
zi = zipfile.ZipFile(W + '/base.apk'); zo = zipfile.ZipFile(W + '/unsigned.apk', 'w')
def add(name, data, store):
    n = zipfile.ZipInfo(name, date_time=(2026, 10, 15, 0, 0, 0)); n.external_attr = 0o644 << 16
    n.compress_type = zipfile.ZIP_STORED if store else zipfile.ZIP_DEFLATED
    if store:  # pad local header extra so the data starts on a 16 KB boundary
        off = zo.fp.tell() + 30 + len(name.encode())
        pad = (-(off + 4)) % 16384
        n.extra = b'\x35\xd9' + (pad).to_bytes(2, 'little') + b'\0' * pad  # 0xd935 = zipalign-style alignment extra
    if isinstance(data, str):
        with open(data, 'rb') as f, zo.open(n, 'w', force_zip64=False) as o:
            while True:
                b = f.read(1 << 22)
                if not b: break
                o.write(b)
    else: zo.writestr(n, data)
add('assets/model.gguf', R + '/models/qwen2.5-0.5b-instruct-q4_k_m.gguf', True)
add('lib/arm64-v8a/libaikeyboard.so', open(R + '/native/build-android/libaikeyboard.so', 'rb').read(), True)
add('lib/arm64-v8a/libaikvoice.so', open(R + '/native/voice/build-android/libaikvoice.so', 'rb').read(), True)
add('assets/whisper.bin', R + '/models/ggml-base.en-q5_1.bin', True)  # v7: Whisper base.en q5_1, read in place from the APK
for e in zi.infolist():
    add(e.filename, zi.read(e.filename), e.filename == 'resources.arsc')
add('classes.dex', open(W + '/dex/classes.dex', 'rb').read(), False)
add('assets/words.txt', open(R + '/app/assets/words.txt', 'rb').read(), False)
for f in ('LICENSES.txt', 'emoji.txt'):
    add('assets/' + f, open(R + '/app/assets/' + f, 'rb').read(), False)
zo.close()
PY
$BT/zipalign -c -P 16 4 $W/unsigned.apk || { echo "alignment check failed"; exit 1; }
cp $W/unsigned.apk $W/aligned.apk
mkdir -p $R/output; OUT=$R/output/LocalKey-AI-v$VERSION_NAME-arm64.apk
$BT/apksigner sign --ks "$AIK_KEYSTORE" --ks-key-alias "$AIK_KEY_ALIAS" --ks-pass env:AIK_STORE_PASS --key-pass env:AIK_KEY_PASS \
  --min-sdk-version 23 --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true --alignment-preserved --out $OUT $W/aligned.apk
rm -f $OUT.idsig
$BT/zipalign -c -P 16 4 $OUT && echo "zipalign -P 16 OK"
$BT/apksigner verify -v --min-sdk-version 23 $OUT | grep -E "Verified using|Verifies"
$BT/aapt2 dump badging $OUT | grep -iE "^package|sdkVersion|native-code|uses-permission"
python3 -c "
import zipfile;z=zipfile.ZipFile('$OUT')
for n in ('assets/model.gguf','lib/arm64-v8a/libaikeyboard.so','lib/arm64-v8a/libaikvoice.so','assets/whisper.bin'):
  i=z.getinfo(n); off=i.header_offset+30+len(i.filename)+len(i.extra); print(n,'stored' if i.compress_type==0 else 'DEFLATED','data offset',off,'mod16384=',off%16384)"
ls -l $OUT; sha256sum $OUT
