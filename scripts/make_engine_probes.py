#!/usr/bin/env python3
"""Golden probes for the engine self-test (Embedder / EngineProbe): app/src/main/assets/engine_probes.json.

  .venv/bin/python scripts/make_engine_probes.py <models_dir> <out.json> [bundle.litertlm ...]

Per model bundle: the fixed probe texts and their expected normalised 768-d vectors, made by the Mac/ARM LiteRT-LM reference
(python litert_lm, CPU, int4 QAT: the phone's model and runtime; phone parity against it is 1.0000, scripts/eval_quality.py).
The app embeds the same texts after every engine load and compares: a NaN, or a cosine under 0.99, means a corrupt xnnpack
cache. A bundle with no entry is simply not checked (the fine-tuned bundles, until they ship).

The probe texts are fixed, invented sentences (no personal data). Vectors are stored as base64 of little-endian float32.
Also prints the cosine between the bundles' vectors (the stock bundles share their text model: expect ~1.0).
"""
import base64, json, sys
from pathlib import Path

import numpy as np
import litert_lm as L
from litert_lm._messages import Text

QUERY_PREFIX = "task: search result | query: "
PROBES = [
    ("query-en", QUERY_PREFIX + "photos of the wedding from last year"),
    ("query-lug", QUERY_PREFIX + "ekifaananyi ky'embuzi eky'omwana"),
    ("doc-en", "title: none | text: The electricity bill for March was paid at the Kampala office on 14 March 2024."),
]
DEFAULT_BUNDLES = [
    "embeddinggemma-2-740m.litertlm",
    "embeddinggemma-2-text-vision-440m.litertlm",
    "embeddinggemma-2-text-270m.litertlm",
]


def embed_all(model: Path):
    multimodal = "text-270m" not in model.name
    kw = dict(backend=L.Backend.CPU())
    if multimodal:
        kw["vision_backend"] = L.Backend.CPU()
    with L.EmbeddingEngine(str(model), **kw) as engine:
        opts = L.EmbeddingOptions(normalize=True)
        return [np.array(engine.compute_embedding([Text(t)], opts).embedding, dtype=np.float32) for _, t in PROBES]


def main():
    models, out = Path(sys.argv[1]), Path(sys.argv[2])
    names = sys.argv[3:] or DEFAULT_BUNDLES
    bundles, vecs = {}, {}
    for name in names:
        vs = embed_all(models / name)
        for v in vs:
            assert v.shape == (768,) and np.isfinite(v).all() and abs(float(np.linalg.norm(v)) - 1) < 1e-3, (name, v.shape)
        vecs[name] = vs
        bundles[name] = [{"id": pid, "text": t, "vector": base64.b64encode(v.astype("<f4").tobytes()).decode()}
                         for (pid, t), v in zip(PROBES, vs)]
        print(name, "ok")
    first = names[0]
    for name in names[1:]:
        print(f"cosine {name} vs {first}:", [round(float(a @ b), 4) for a, b in zip(vecs[name], vecs[first])])
    out.write_text(json.dumps({"version": 1, "dimension": 768, "bundles": bundles}, indent=1) + "\n")
    print("wrote", out, out.stat().st_size, "bytes")


main()
