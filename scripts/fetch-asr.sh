#!/bin/bash
# Fetches what speech-to-text needs and does not keep in git:
#   app/libs/sherpa-onnx-1.13.8.aar   the native library + Kotlin API (ONNX Runtime linked in; MIT/Apache-2.0)
# The models (Moonshine tiny int8 MIT, silero VAD MIT, CED-mini Apache-2.0) are pushed to the phone's
# files/models/asr/ by scripts/push-asr-models.sh (they are not in the APK).
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p app/libs
V=1.13.8
[ -s app/libs/sherpa-onnx-$V.aar ] || curl -fL -o app/libs/sherpa-onnx-$V.aar \
  "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$V/sherpa-onnx-static-link-onnxruntime-$V.aar"
ls -la app/libs
