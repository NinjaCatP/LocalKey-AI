#!/bin/bash
# Builds the app (classes) and runs JVM + Robolectric tests; screenshots -> shots/
set -e; set +e
. /workspace/apkport/env.sh
R=/workspace/aikeyboard; T=$R/test; cd $T
H=/workspace/sharkordie/harness
rm -rf gen && mkdir -p gen/cls gen/res/com/android/tools
AJ=$(ls ~/.m2/repository/org/robolectric/android-all-instrumented/15-*/*.jar | head -1)
CP=$(ls $H/deps/jars/*.jar | tr '\n' ':')$AJ
javac -nowarn -d gen/cls -cp $CP $(find $R/app/java $R/work/gen src -name '*.java') 2>&1 | grep -v "^Note:" || true
cp $R/app/AndroidManifest.xml gen/AndroidManifest.xml
cat > gen/res/com/android/tools/test_config.properties <<P
android_merged_manifest=$T/gen/AndroidManifest.xml
android_resource_apk=$R/work/base.apk
android_custom_package=com.jason.aikeyboard
P
# robolectric needs assets: put a small resource apk with words.txt (model excluded)
python3 -c "import zipfile,shutil;shutil.copy('$R/work/base.apk','gen/res_assets.apk');z=zipfile.ZipFile('gen/res_assets.apk','a');z.write('$R/app/assets/words.txt','assets/words.txt');z.write('$R/app/assets/emoji.txt','assets/emoji.txt');z.close()" 
sed -i "s#android_resource_apk=.*#android_resource_apk=$T/gen/res_assets.apk#" gen/res/com/android/tools/test_config.properties
java -Xmx2g -Djava.library.path=$R/native/build-host:$R/native/voice/build-host -Daik.model=$R/models/qwen2.5-0.5b-instruct-q4_k_m.gguf -Daik.out=$R/shots \
  -cp $CP:gen/cls:gen/res org.junit.runner.JUnitCore com.jason.aikeyboard.CoreTest com.jason.aikeyboard.PersonalTest com.jason.aikeyboard.ServiceTest com.jason.aikeyboard.SwipeTest com.jason.aikeyboard.VoiceServiceTest
# separate JVM: real-model personalization measurement
java -Xmx2g -Djava.library.path=$R/native/build-host:$R/native/voice/build-host -Daik.model=$R/models/qwen2.5-0.5b-instruct-q4_k_m.gguf -cp $CP:gen/cls:gen/res org.junit.runner.JUnitCore com.jason.aikeyboard.PersonalLlmTest
# separate JVM: whisper.cpp on TTS WAVs (native lib can only bind to one classloader per JVM)
java -Xmx2g -Djava.library.path=$R/native/voice/build-host -cp $CP:gen/cls:gen/res org.junit.runner.JUnitCore com.jason.aikeyboard.VoiceCoreTest
