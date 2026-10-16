#!/bin/bash
# Builds libaikeyboard.so (llama.cpp) and libaikvoice.so (whisper.cpp) for arm64-v8a with the Android NDK.
set -e
cd "$(dirname "$0")/.."; git submodule update --init
: "${ANDROID_NDK:?set ANDROID_NDK to your NDK path}"
for d in native native/voice; do
  cmake -S $d -B $d/build-android -G Ninja -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_TOOLCHAIN_FILE=$ANDROID_NDK/build/cmake/android.toolchain.cmake -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-30
  cmake --build $d/build-android
done
