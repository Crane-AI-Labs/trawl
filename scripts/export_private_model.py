"""Exports Marqo/nsfw-image-detection-384 (timm vit_tiny_patch16_384, 2 classes: NSFW, SFW) to ONNX: input pixel_values [1,3,S,S]
(RGB, (x/255-0.5)/0.5), output logits [1,2]. Prints the max difference to torch; fails if it is not tiny.
Usage: export_private_model.py <out.onnx> [side]   side defaults to 384 (the model as trained). A smaller side (e.g. 224) runs the
same weights on fewer patches: the position embedding is resampled by timm; the decisions must be re-measured (flips vs 384)."""
import sys, numpy as np, torch, timm, onnxruntime as ort
from huggingface_hub import hf_hub_download
from safetensors.torch import load_file
from timm.layers import resample_abs_pos_embed

out = sys.argv[1]
side = int(sys.argv[2]) if len(sys.argv) > 2 else 384
state = load_file(hf_hub_download("Marqo/nsfw-image-detection-384", "model.safetensors"))
m = timm.create_model("vit_tiny_patch16_384", pretrained=False, num_classes=2, img_size=side)
if side != 384:
    state["pos_embed"] = resample_abs_pos_embed(state["pos_embed"], (side // 16, side // 16), num_prefix_tokens=1)
m.load_state_dict(state, strict=True)
m.eval()
x = torch.randn(1, 3, side, side)
torch.onnx.export(m, x, out, input_names=["pixel_values"], output_names=["logits"], opset_version=17, dynamo=False)
a = ort.InferenceSession(out).run(None, {"pixel_values": x.numpy()})[0]
diff = float(abs(a - m(x).detach().numpy()).max())
print("onnx vs torch max diff", diff)
assert diff < 1e-3, "export does not match torch"
