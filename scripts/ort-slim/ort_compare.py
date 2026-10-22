#!/usr/bin/env python3
"""Compares what two builds stored in their index for the same photos (the index files pulled from two side builds, one per ORT).

  ort_compare.py <A.sqlite> <B.sqlite> [--like OrtSlim] [--host blur_manifest.json] [--threshold 0.94]

For every file whose path contains --like: the "looks private" score (nsfw-384) and the quick-look image vector
(TinyCLIP, fp16 blob). Prints how many are bit-identical, the largest score difference, the smallest cosine, and whether the set
of previews that would be blurred (score >= threshold, the main app's) is the same. --host: a JSON list of {id, host} with
the scores a desktop ONNX Runtime gave the same pictures (file name = id.jpg); a phone score is compared to it for information.
"""
import argparse
import json
import shutil
import sqlite3
import tempfile
from pathlib import Path

import numpy as np


def load(path, like):
    # The -wal file sits beside the db when the app was force-stopped before a checkpoint; sqlite reads it from the same name.
    d = Path(tempfile.mkdtemp())
    shutil.copy(path, d / "i.db")
    wal = Path(str(path) + "-wal")
    if wal.exists() and wal.stat().st_size:
        shutil.copy(wal, d / "i.db-wal")
    db = sqlite3.connect(d / "i.db")
    rows = {}
    for fid, name, score in db.execute("SELECT id, name, looks_private FROM files WHERE rel_path LIKE ? OR name LIKE ?", (f"%{like}%", f"%{like}%")):
        vec = db.execute("SELECT vec FROM segments WHERE file = ? AND modality = 'clip' AND vec IS NOT NULL", (fid,)).fetchone()
        rows[name] = (score, vec[0] if vec else None)
    return rows


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("a"); ap.add_argument("b")
    ap.add_argument("--like", default="OrtSlim")
    ap.add_argument("--host")
    ap.add_argument("--threshold", type=float, default=0.94)
    a = ap.parse_args()
    A, B = load(a.a, a.like), load(a.b, a.like)
    names = sorted(set(A) & set(B))
    print(f"files in both: {len(names)} (A {len(A)}, B {len(B)})")
    sc = [(n, A[n][0], B[n][0]) for n in names if A[n][0] is not None and B[n][0] is not None and A[n][0] >= 0 and B[n][0] >= 0]
    same = sum(1 for _, x, y in sc if x == y)
    print(f"private score: scored in both {len(sc)}; bit-identical {same}; max |diff| {max((abs(x - y) for _, x, y in sc), default=0):.3g}")
    blA = {n for n, x, _ in sc if x >= a.threshold}; blB = {n for n, _, y in sc if y >= a.threshold}
    print(f"blurred at >= {a.threshold}: A {len(blA)}, B {len(blB)}, same set: {blA == blB}")
    vs = [(n, A[n][1], B[n][1]) for n in names if A[n][1] and B[n][1]]
    ident = sum(1 for _, x, y in vs if x == y)
    cos = []
    for _, x, y in vs:
        fx = np.frombuffer(x, dtype="<f2").astype(np.float64); fy = np.frombuffer(y, dtype="<f2").astype(np.float64)
        cos.append(float(fx @ fy / (np.linalg.norm(fx) * np.linalg.norm(fy))))
    print(f"quick-look vectors: both {len(vs)}; byte-identical {ident}; cosine min {min(cos, default=float('nan')):.7f}")
    if a.host:
        host = {h["id"] + ".jpg": h["host"] for h in json.load(open(a.host))}
        for label, S in (("A", {n: x for n, x, _ in sc}), ("B", {n: y for n, _, y in sc})):
            d = [abs(S[n] - host[n]) for n in S if n in host]
            print(f"{label} against the desktop run (information): {len(d)} files, mean |diff| {np.mean(d):.4f}, max {max(d):.4f}")
    return 0 if (same == len(sc) and blA == blB and ident == len(vs)) else 1


raise SystemExit(main())
