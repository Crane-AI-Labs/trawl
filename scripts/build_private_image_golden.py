#!/usr/bin/env python3
"""Golden files for PrivateImageTest: Pillow's resize((384, 384), BICUBIC) of the same synthetic pictures as
build_clip_image_golden.py, which is the preprocessing the "looks private" model (Marqo/nsfw-image-detection-384) was
evaluated with (timm eval transform: squash to 384x384, mean and std 0.5). Stored as every 8th row and column of the result:
app/src/test/resources/private_resize_<w>x<h>.rgb.gz (48*48*3 bytes, row-major RGB).

  python3 scripts/build_private_image_golden.py
"""
import gzip
from pathlib import Path

import numpy as np
from PIL import Image

OUT = Path(__file__).resolve().parent.parent / "app/src/test/resources"


def picture(w, h):
    y, x = np.mgrid[0:h, 0:w]
    r = (x * 255 // max(w - 1, 1)) % 256
    g = ((x // 3 + y // 3) % 2) * 200 + (y * 55 // max(h - 1, 1))
    b = (x ^ y) % 256
    return np.stack([r, g, b], axis=-1).astype(np.uint8)


for w, h in ((640, 480), (150, 100)):
    out = np.asarray(Image.fromarray(picture(w, h), "RGB").resize((384, 384), Image.BICUBIC), dtype=np.uint8)[::8, ::8]
    path = OUT / f"private_resize_{w}x{h}.rgb.gz"
    path.write_bytes(gzip.compress(out.tobytes(), mtime=0))
    print(path.name, path.stat().st_size, "bytes")
