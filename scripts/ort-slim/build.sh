#!/bin/bash
# Builds the slim ONNX Runtime that quick look uses (D10 follow-up) and drops app/libs/onnxruntime-slim.aar.
#   TRAWL_BUILD_SSH=user@host scripts/ort-slim/build.sh   build on a Linux x86-64 machine you can ssh to (8+ cores, 30 GB disk, Android NDK 27.1
#                                        under ~/Android/Sdk, python3, git), fetch the result, package
#   BUILD=name scripts/ort-slim/build.sh keep another build directory (default "release"); an existing one builds incrementally
#   GEN=1 scripts/ort-slim/build.sh      first regenerate scripts/ort-slim/ops.config from the .onnx graphs of EVERY ORT model the app
#                                        runs (gen_ops_config.py, on the box, with the same onnxruntime version); see README.md
#
# Why: the stock onnxruntime-android AAR costs 12.9 MB compressed in the APK (every operator kernel, ML ops, telemetry).
# This build is ORT v1.31.0 for arm64-v8a only, MinSizeRel + LTO, with only the operators the app's ONNX graphs use
# (TinyCLIP's image and text towers for quick look, nsfw-384 for "Blur private-looking previews")
# (ops.config, via --include_ops_by_config), --disable_ml_ops and --no_telemetry (no telemetry client, no INTERNET permission).
# Keep ops.config in step with the models: a graph that needs an operator the config lacks fails at session creation with
# "Could not find an implementation for X". That includes ops that ORT's own optimiser passes create and drop again (Gemm
# comes from MatMul+Add, and without a CPU kernel the fused MatMulNBits node is left with no execution provider).
# Java classes come from the same tag's own build; make_aar.py takes only the .class files from its jar.
set -euo pipefail
cd "$(dirname "$0")/../.."
# No default host: you name the Linux machine that does the build.
HOST=${TRAWL_BUILD_SSH:-}
[ -n "$HOST" ] || { echo "Set TRAWL_BUILD_SSH=user@host: the Linux x86-64 machine that builds ONNX Runtime (see scripts/ort-slim/README.md)." >&2; exit 2; }
ROOT=${TRAWL_BUILD_ROOT:-$(ssh "$HOST" 'echo $HOME')/trawl-ortslim}   # absolute path on that machine
BUILD=${BUILD:-release}
TAG=v1.31.0
OUT=$(mktemp -d)

if [ "${GEN:-0}" = 1 ]; then
  # Every model the app opens with ORT (not sherpa-onnx: that has its own static ORT). Keep this list in step with QuickLookPack.kt and PrivatePack.kt.
  M=$ROOT/models
  ssh "$HOST" "mkdir -p $M/tinyclip-61m-32 $M/nsfw-384"
  Q=bench-data/quicklook-pack/findr-quicklook-models/tinyclip-61m-32
  P=bench-data/private-pack/findr-private-models/nsfw-384
  IMG=$(sed -nE 's/.*const val IMAGE = "[^/]+\/([^"]+)".*/\1/p' app/src/main/java/com/craneai/findr/engine/QuickLookPack.kt)
  TXT=$(sed -nE 's/.*const val TEXT = "[^/]+\/([^"]+)".*/\1/p' app/src/main/java/com/craneai/findr/engine/QuickLookPack.kt)
  scp -q "$Q/$IMG" "$Q/$TXT" "$HOST:$M/tinyclip-61m-32/"
  scp -q "$P/nsfw-384.onnx" "$HOST:$M/nsfw-384/"
  scp -q scripts/ort-slim/gen_ops_config.py "$HOST:$ROOT/"
  ssh "$HOST" bash -s -- "$ROOT" "$TAG" "$IMG" "$TXT" <<'GEN'
set -euo pipefail
ROOT=$1; TAG=${2#v}; cd "$ROOT"
[ -x venv-tools/bin/python ] || { python3 -m venv venv-tools; venv-tools/bin/pip install -q onnx numpy "onnxruntime==$TAG"; }
venv-tools/bin/python gen_ops_config.py ops.config models/tinyclip-61m-32/$3 models/tinyclip-61m-32/$4 models/nsfw-384/nsfw-384.onnx 2>/dev/null
GEN
  scp -q "$HOST:$ROOT/ops.config" scripts/ort-slim/ops.config
fi
scp -q scripts/ort-slim/ops.config "$HOST:$ROOT/ops.config" 2>/dev/null || { ssh "$HOST" "mkdir -p $ROOT"; scp -q scripts/ort-slim/ops.config "$HOST:$ROOT/ops.config"; }

ssh "$HOST" bash -s -- "$ROOT" "$BUILD" "$TAG" <<'REMOTE'
set -euo pipefail
ROOT=$1; BUILD=$2; TAG=$3
cd "$ROOT"
[ -d ort ] || git clone --depth 1 --branch "$TAG" https://github.com/microsoft/onnxruntime ort
if [ ! -x venv/bin/python ]; then
  python3 -m venv venv
  venv/bin/pip install -q cmake ninja numpy onnx packaging flatbuffers protobuf ml_dtypes
fi
export PATH="$ROOT/venv/bin:$PATH" ANDROID_HOME="$HOME/Android/Sdk"
# The last build step (the Android instrumentation-test app for the AAR) can fail on this box; the libraries are linked
# before it, so the check below is on the libraries, not on build.py's exit status.
set +e
(cd ort && python3 tools/ci_build/build.py --build_dir "$ROOT/$BUILD" --config MinSizeRel --android \
  --android_sdk_path "$HOME/Android/Sdk" --android_ndk_path "$HOME/Android/Sdk/ndk/27.1.12297006" \
  --android_abi arm64-v8a --android_api 30 --cmake_generator Ninja --build_shared_lib --build_java --parallel \
  --skip_tests --disable_ml_ops --no_telemetry --enable_lto --include_ops_by_config "$ROOT/ops.config" \
  --compile_no_warning_as_error --skip_submodule_sync) > "$ROOT/$BUILD.log" 2>&1
STATUS=$?
set -e
B="$ROOT/$BUILD/MinSizeRel"
for f in libonnxruntime.so libonnxruntime4j_jni.so java/build/libs/onnxruntime.jar; do
  [ -s "$B/$f" ] || { echo "missing $f (build.py exit $STATUS, see $ROOT/$BUILD.log)"; exit 1; }
done
echo "build.py exit $STATUS; libraries present"
STRIP="$HOME/Android/Sdk/ndk/27.1.12297006/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip"
rm -rf "$ROOT/out-$BUILD"; mkdir "$ROOT/out-$BUILD"
for f in libonnxruntime.so libonnxruntime4j_jni.so; do "$STRIP" --strip-unneeded -o "$ROOT/out-$BUILD/$f" "$B/$f"; done
cp "$B/java/build/libs/onnxruntime.jar" "$ROOT/out-$BUILD/"
ls -l "$ROOT/out-$BUILD"
REMOTE

scp -q "$HOST:$ROOT/out-$BUILD/*" "$OUT/"
mkdir -p app/libs
python3 scripts/ort-slim/make_aar.py app/libs/onnxruntime-slim.aar "$OUT/onnxruntime.jar" "$OUT/libonnxruntime.so" "$OUT/libonnxruntime4j_jni.so"
rm -rf "$OUT"
