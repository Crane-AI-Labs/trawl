#!/usr/bin/env python3
"""Golden files for QuickLookTest: Pillow's resize((S, S), BICUBIC) of synthetic RGB pictures, the exact preprocessing the
quick-look eval scores (experiments/twotower/clipmodels.py "squash", scripts/eval_quicklook_candidates.py), at the pack's
input size S (QuickLookPack.SIZE: 256 for SigLIP 2). The pictures are a formula both sides compute, so only Pillow's output
is stored: app/src/test/resources/clip_resize_<w>x<h>.rgb.gz (every 4th row and column of the SxS result: (S/4)^2*3 bytes,
row-major RGB).

  python scripts/build_clip_image_golden.py [S]
"""
import gzip, sys
from pathlib import Path

import numpy as np
from PIL import Image

OUT = Path(__file__).resolve().parent.parent / "app/src/test/resources"
S = int(sys.argv[1]) if len(sys.argv) > 1 else 256


def picture(w, h):
    """Mixed frequencies: smooth ramps, a fine checker and an XOR texture (aliasing shows up as a mismatch)."""
    y, x = np.mgrid[0:h, 0:w]
    r = (x * 255 // max(w - 1, 1)) % 256
    g = ((x // 3 + y // 3) % 2) * 200 + (y * 55 // max(h - 1, 1))
    b = (x ^ y) % 256
    return np.stack([r, g, b], axis=-1).astype(np.uint8)


for w, h in ((640, 480), (150, 100)):
    out = np.asarray(Image.fromarray(picture(w, h), "RGB").resize((S, S), Image.BICUBIC), dtype=np.uint8)[::4, ::4]
    path = OUT / f"clip_resize_{w}x{h}.rgb.gz"
    path.write_bytes(gzip.compress(out.tobytes(), mtime=0))
    print(path.name, path.stat().st_size, "bytes")
