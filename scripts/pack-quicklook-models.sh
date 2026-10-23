#!/bin/bash
# Builds the quick-look model pack (D10: TinyCLIP ViT-61M/32; image tower dynamic int8 per channel, text tower weight-only int8) the app installs from Downloads,
# as a folder and a zip, checked against the app's own manifest (engine/QuickLookPack.kt: size and SHA-256 per file), and can
# push the zip to a phone's Download folder. Works with release builds (no run-as).
#   scripts/pack-quicklook-models.sh [--push]        (SRC=<dir with the manifest's image and text files> OUT=bench-data/quicklook-pack)
#   ANDROID_SERIAL=<serial> scripts/phone-lock.sh run <who> scripts/pack-quicklook-models.sh --push
# Make SRC with: experiments/twotower/export_onnx.py --models tinyclip-61m-32 --no-quant; the image tower with onnxruntime's
# quantize_dynamic(QInt8, per_channel=True) (image.dyn8pc.onnx), the text tower with experiments/twotower/weight_only_int8.py
# (text.wo8.onnx: dynamic int8 text graphs have been wrong on ARM).
# (The SigLIP 2 alternative, commit bed806c, also packs scripts/build_siglip_vocab.py's tokenizer.vocab.)
# The app finds Download/findr-quicklook-models/ or Download/trawl-quicklook-models*.zip (findr-quicklook-models*.zip works too) and copies each checked file to
# files/models/quicklook/ the next time it starts or indexes. A Play Asset Delivery pack later uses the same layout.
set -euo pipefail
cd "$(dirname "$0")/.."
SRC=${SRC:-bench-data/models/quicklook/tinyclip-61m-32}
OUT=${OUT:-bench-data/quicklook-pack}
PUSH=0
for a in "$@"; do case "$a" in --push) PUSH=1;; *) echo "unknown: $a"; exit 2;; esac; done
MANIFEST=app/src/main/java/com/craneai/findr/engine/QuickLookPack.kt
D="$OUT/findr-quicklook-models"
SUB=$(sed -nE 's/.*const val IMAGE = "([^/]+)\/.*/\1/p' "$MANIFEST")
IMG=$(sed -nE 's/.*const val IMAGE = "[^/]+\/([^"]+)".*/\1/p' "$MANIFEST")
TXT=$(sed -nE 's/.*const val TEXT = "[^/]+\/([^"]+)".*/\1/p' "$MANIFEST")
rm -rf "$D" "$OUT/trawl-quicklook-models.zip"; mkdir -p "$D/$SUB"
for f in "$IMG" "$TXT"; do
  ln "$SRC/$f" "$D/$SUB/$f" 2>/dev/null || cp "$SRC/$f" "$D/$SUB/"
done
# Every file must appear in the manifest with its exact size and hash.
for pair in "$IMG IMAGE" "$TXT TEXT"; do
  set -- $pair
  size=$(stat -f %z "$D/$SUB/$1"); sha=$(shasum -a 256 "$D/$SUB/$1" | cut -c1-64)
  tr -d _ < "$MANIFEST" | grep -E "AsrModelFile\($2, $size, \"$sha\"\)" >/dev/null || { echo "MISMATCH $SUB/$1 ($size bytes, $sha)"; exit 1; }
  echo "ok $SUB/$1 $size"
done
(cd "$OUT" && zip -q -r trawl-quicklook-models.zip findr-quicklook-models)   # deflated: the int8 weights shrink a little
ls -l "$OUT/trawl-quicklook-models.zip"
if [ "$PUSH" = 1 ]; then
  : "${ANDROID_SERIAL:?name the phone: ANDROID_SERIAL=<serial>}"
  adb -s "$ANDROID_SERIAL" push "$OUT/trawl-quicklook-models.zip" /sdcard/Download/ | tail -1
fi
