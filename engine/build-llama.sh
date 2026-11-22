#!/usr/bin/env bash
# Builds the built-in engine (1.5.182): llama.cpp's llama-server for arm64 Android phones, as engine/out/jniLibs/arm64-v8a/libllama_server.so.
# Shipped as a "library" so Android unpacks it to nativeLibraryDir, the one app folder it may run programs from (EngineHost).
# Static C++ runtime, no curl or OpenSSL, no web UI; dotprod + fp16 (phones from about 2018 on) and KleidiAI's Arm kernels.
# Needs the Android NDK (ANDROID_NDK_HOME, ANDROID_NDK_LATEST_HOME or the SDK's ndk folder), cmake, ninja and git.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
TAG="$(python3 -c "import json;print(json.load(open('$here/SOURCES.json'))['llama.cpp']['tag'])")"
REV="$(python3 -c "import json;print(json.load(open('$here/SOURCES.json'))['llama.cpp']['revision'])")"
NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_LATEST_HOME:-}}"
if [ -z "$NDK" ]; then sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/android-sdk}}"; NDK="$(ls -d "$sdk"/ndk/* 2>/dev/null | sort -V | tail -1)"; fi
[ -d "$NDK" ] || { echo "Android NDK not found" >&2; exit 1; }
work="$here/work"; out="$here/out/jniLibs/arm64-v8a"; mkdir -p "$work" "$out" "$here/out/assets/licenses"
if [ ! -d "$work/llama.cpp/.git" ]; then git clone --depth 1 --branch "$TAG" https://github.com/ggml-org/llama.cpp "$work/llama.cpp"; fi
[ "$(git -C "$work/llama.cpp" rev-parse HEAD)" = "$REV" ] || { echo "llama.cpp revision differs from SOURCES.json" >&2; exit 1; }
cmake -S "$work/llama.cpp" -B "$work/build" -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26 \
  -DANDROID_STL=c++_static -DCMAKE_BUILD_TYPE=Release -DBUILD_SHARED_LIBS=OFF -DGGML_NATIVE=OFF -DGGML_OPENMP=OFF \
  -DGGML_CPU_KLEIDIAI=ON -DGGML_CPU_ARM_ARCH=armv8.2-a+dotprod+fp16 -DLLAMA_CURL=OFF -DLLAMA_OPENSSL=OFF \
  -DLLAMA_BUILD_TESTS=OFF -DLLAMA_BUILD_EXAMPLES=OFF -DLLAMA_BUILD_TOOLS=ON -DLLAMA_BUILD_SERVER=ON \
  -DLLAMA_BUILD_HTML=OFF -DLLAMA_BUILD_UI=OFF -DLLAMA_USE_PREBUILT_UI=OFF
ninja -C "$work/build" llama-server
"$(ls -d "$NDK"/toolchains/llvm/prebuilt/*/bin | head -1)/llvm-strip" -o "$out/libllama_server.so" "$work/build/bin/llama-server"
cp "$work/llama.cpp/LICENSE" "$here/out/assets/licenses/llama.cpp-LICENSE-MIT.txt"
k="$(ls -d "$work"/build/_deps/kleidiai-src 2>/dev/null || true)"; [ -n "$k" ] && cp "$k/LICENSES/Apache-2.0.txt" "$here/out/assets/licenses/kleidiai-LICENSE-Apache-2.0.txt" || true
ls -l "$out"
