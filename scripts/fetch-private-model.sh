#!/bin/bash
# Fetches the "looks private" classifier and puts it in models/private/nsfw-384.onnx (git-ignored), where scripts/pack-private-models.sh picks it up.
# Marqo/nsfw-image-detection-384 (Apache-2.0, https://huggingface.co/Marqo/nsfw-image-detection-384): a ViT-tiny/16 fine-tune at 384 px.
# Needs python3 with: timm torch onnx onnxruntime huggingface_hub safetensors. The export is checked against torch on random
# input, so a different export can't slip in unnoticed.
#   scripts/fetch-private-model.sh
set -euo pipefail
cd "$(dirname "$0")/.."
OUT=models/private
mkdir -p "$OUT"
python3 scripts/export_private_model.py "$OUT/nsfw-384.onnx"
size=$(stat -f %z "$OUT/nsfw-384.onnx" 2>/dev/null || stat -c %s "$OUT/nsfw-384.onnx")
sha=$(shasum -a 256 "$OUT/nsfw-384.onnx" | cut -c1-64)
echo "nsfw-384.onnx $size bytes sha256 ${sha}"
# The licence text and a source note travel with the model in the add-on pack (Apache-2.0 asks for both).
curl -fsSL https://huggingface.co/Marqo/nsfw-image-detection-384/resolve/main/LICENSE -o "$OUT/LICENSE.txt" || curl -fsSL https://www.apache.org/licenses/LICENSE-2.0.txt -o "$OUT/LICENSE.txt"
printf '%s\n' \
  "nsfw-384.onnx: Marqo/nsfw-image-detection-384, https://huggingface.co/Marqo/nsfw-image-detection-384" \
  "Licence: Apache-2.0 (LICENSE.txt). A fine-tune of timm vit_tiny_patch16_384 (Apache-2.0)." \
  "Change: converted to ONNX (opset 17, fp32) by scripts/export_private_model.py in Findr." \
  "Findr uses it only to blur thumbnails that look private. It can be wrong." > "$OUT/SOURCE.txt"
echo "the pack manifest (engine/PrivatePack.kt) pins each file's size and sha256; update it if the licence text or the export changes"
echo "version label in the app (PrivateLook.MODEL) ties scores to this model; change it if you export a different one"
