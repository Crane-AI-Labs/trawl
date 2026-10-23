#!/bin/bash
# Builds the "Blur private-looking previews" add-on pack the app installs from Downloads, as a folder and a zip, checked against
# the app's own manifest (engine/PrivatePack.kt: size and SHA-256 per file), and can push the zip to a phone's Download folder.
#   scripts/pack-private-models.sh [--push]        (SRC=models/private OUT=bench-data/private-pack)
#   ANDROID_SERIAL=<serial> scripts/phone-lock.sh run <who> scripts/pack-private-models.sh --push
# SRC holds nsfw-384.onnx, LICENSE.txt and SOURCE.txt (scripts/fetch-private-model.sh makes them). The app finds
# Download/findr-private-models/ or Download/trawl-private-models*.zip (findr-private-models*.zip works too) and copies each checked file to files/models/private/.
# A Play Asset Delivery pack later uses the same layout.
set -euo pipefail
cd "$(dirname "$0")/.."
SRC=${SRC:-models/private}
OUT=${OUT:-bench-data/private-pack}
PUSH=0
for a in "$@"; do case "$a" in --push) PUSH=1;; *) echo "unknown: $a"; exit 2;; esac; done
MANIFEST=app/src/main/java/com/craneai/findr/engine/PrivatePack.kt
D="$OUT/findr-private-models"
SUB=$(sed -nE 's/.*const val MODEL = "([^/]+)\/.*/\1/p' "$MANIFEST")
rm -rf "$D" "$OUT/trawl-private-models.zip"; mkdir -p "$D/$SUB"
for f in nsfw-384.onnx LICENSE.txt SOURCE.txt; do cp "$SRC/$f" "$D/$SUB/"; done
# Every file must appear in the manifest with its exact size and hash.
for f in nsfw-384.onnx LICENSE.txt SOURCE.txt; do
  size=$(stat -f %z "$D/$SUB/$f"); sha=$(shasum -a 256 "$D/$SUB/$f" | cut -c1-64)
  tr -d _ < "$MANIFEST" | grep -E "AsrModelFile\((MODEL|LICENCE|SOURCE), $size, \"$sha\"\)" >/dev/null || { echo "MISMATCH $SUB/$f ($size bytes, $sha)"; exit 1; }
  echo "ok $SUB/$f $size"
done
(cd "$OUT" && zip -q -0 -r trawl-private-models.zip findr-private-models)   # stored: ONNX weights don't compress
ls -l "$OUT/trawl-private-models.zip"
if [ "$PUSH" = 1 ]; then
  : "${ANDROID_SERIAL:?name the phone: ANDROID_SERIAL=<serial>}"
  adb -s "$ANDROID_SERIAL" push "$OUT/trawl-private-models.zip" /sdcard/Download/ | tail -1
fi
