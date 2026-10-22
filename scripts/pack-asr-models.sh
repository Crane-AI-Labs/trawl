#!/bin/bash
# Builds the speech-model pack the app installs from Downloads (engine/AsrModelPack.kt lists the files, sizes and SHA-256),
# as a folder and a zip, and can push the zip to a phone's Download folder. Works with release builds (no run-as needed).
#   scripts/pack-asr-models.sh [--with-moonshine] [--push]      (SRC=bench-data/models/asr OUT=bench-data/asr-pack)
#   ANDROID_SERIAL=<serial> scripts/phone-lock.sh run <who> scripts/pack-asr-models.sh --push
# The app finds either Download/findr-speech-models/ (folder) or Download/findr-speech-models*.zip, checks each file, and copies it to
# files/models/asr/ the next time it starts or indexes. A Play Asset Delivery pack later uses the same layout.
set -euo pipefail
cd "$(dirname "$0")/.."
SRC=${SRC:-bench-data/models/asr}
OUT=${OUT:-bench-data/asr-pack}
WITH_MOON=0; PUSH=0
for a in "$@"; do case "$a" in --with-moonshine) WITH_MOON=1;; --push) PUSH=1;; *) echo "unknown: $a"; exit 2;; esac; done
P="$SRC/sherpa-onnx-nemo-parakeet_tdt_ctc_110m-en-36000-int8"
C="$SRC/sherpa-onnx-ced-mini-audio-tagging-2024-04-19"
M="$SRC/sherpa-onnx-moonshine-tiny-en-int8"
D="$OUT/findr-speech-models"
rm -rf "$D" "$OUT/findr-speech-models.zip"; mkdir -p "$D/parakeet-tdt-ctc-110m-int8" "$D/ced-mini-int8"
cp "$P/model.int8.onnx" "$P/tokens.txt" "$D/parakeet-tdt-ctc-110m-int8/"
cp "$SRC/silero_vad.onnx" "$D/"
cp "$C/model.int8.onnx" "$C/class_labels_indices.csv" "$D/ced-mini-int8/"
if [ "$WITH_MOON" = 1 ]; then
  mkdir -p "$D/moonshine-tiny-en-int8"
  for f in preprocess.onnx encode.int8.onnx uncached_decode.int8.onnx cached_decode.int8.onnx tokens.txt; do cp "$M/$f" "$D/moonshine-tiny-en-int8/"; done
fi
# Check the pack against the app's own manifest before anyone installs it.
grep -E 'AsrModelFile\("' app/src/main/java/com/craneai/findr/engine/AsrModelPack.kt | while IFS= read -r line; do
  path=$(sed -E 's/.*AsrModelFile\("([^"]+)".*/\1/' <<<"$line")
  size=$(sed -E 's/.*", ([0-9_]+), ".*/\1/' <<<"$line" | tr -d _)
  sha=$(sed -E 's/.*, "([0-9a-f]{64})".*/\1/' <<<"$line")
  [ -f "$D/$path" ] || continue
  [ "$(stat -f %z "$D/$path")" = "$size" ] && [ "$(shasum -a 256 "$D/$path" | cut -c1-64)" = "$sha" ] || { echo "MISMATCH $path"; exit 1; }
  echo "ok $path"
done
(cd "$OUT" && zip -q -0 -r findr-speech-models.zip findr-speech-models)   # stored, not deflated: the models are already compressed
ls -l "$OUT/findr-speech-models.zip"
if [ "$PUSH" = 1 ]; then
  : "${ANDROID_SERIAL:?name the phone: ANDROID_SERIAL=<serial>}"
  adb -s "$ANDROID_SERIAL" push "$OUT/findr-speech-models.zip" /sdcard/Download/ | tail -1
fi
