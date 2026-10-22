#!/bin/bash
# Puts the speech models into the debug app's private storage (files/models/asr/) on a phone. They are not in the APK or in git.
#   ANDROID_SERIAL=<serial> scripts/push-asr-models.sh [path/to/models/asr]   (default: bench-data/models/asr)
# Developer path only. Testers and release builds use scripts/pack-asr-models.sh --push (Downloads, verified by the app).
# Hold the phone lock while you run it (scripts/phone-lock.sh run <who> scripts/push-asr-models.sh). Needs a debuggable build (run-as).
# Fetch the sherpa-onnx models first (github.com/k2-fsa/sherpa-onnx, release tags asr-models and audio-tagging-models; silero_vad.onnx
# is on the same project's releases). Unpack them under the folder below. Layout the app expects:
#   moonshine-tiny-en-int8/{preprocess.onnx,encode.int8.onnx,uncached_decode.int8.onnx,cached_decode.int8.onnx,tokens.txt}
#   parakeet-tdt-ctc-110m-int8/{model.int8.onnx,tokens.txt}   (default engine; engine.txt = "moonshine" selects the other)
#   silero_vad.onnx        ced-mini-int8/{model.int8.onnx,class_labels_indices.csv}
set -euo pipefail
: "${ANDROID_SERIAL:?name the phone: ANDROID_SERIAL=<serial>}"
SRC=${1:-$(dirname "$0")/../bench-data/models/asr}
PKG=${PKG:-com.craneai.findr}
STAGE=/data/local/tmp/asr/stage
A="adb -s $ANDROID_SERIAL"
$A shell "rm -rf $STAGE; mkdir -p $STAGE/moonshine-tiny-en-int8 $STAGE/parakeet-tdt-ctc-110m-int8 $STAGE/ced-mini-int8"
M="$SRC/sherpa-onnx-moonshine-tiny-en-int8"
for f in preprocess.onnx encode.int8.onnx uncached_decode.int8.onnx cached_decode.int8.onnx tokens.txt; do
  $A push "$M/$f" $STAGE/moonshine-tiny-en-int8/ >/dev/null
done
P="$SRC/sherpa-onnx-nemo-parakeet_tdt_ctc_110m-en-36000-int8"
$A push "$P/model.int8.onnx" "$P/tokens.txt" $STAGE/parakeet-tdt-ctc-110m-int8/ >/dev/null
$A push "$SRC/silero_vad.onnx" $STAGE/ >/dev/null
C="$SRC/sherpa-onnx-ced-mini-audio-tagging-2024-04-19"
$A push "$C/model.int8.onnx" "$C/class_labels_indices.csv" $STAGE/ced-mini-int8/ >/dev/null
$A shell "run-as $PKG sh -c 'mkdir -p files/models/asr && rm -rf files/models/asr/* && cp -r $STAGE/* files/models/asr/'"
$A shell "run-as $PKG ls -lR files/models/asr"
$A shell "rm -rf $STAGE"
