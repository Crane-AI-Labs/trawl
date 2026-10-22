#!/usr/bin/env python3
"""SigLIP 2's query tokenizer for the app (D10): the Gemma SentencePiece vocabulary as a plain list, plus golden token ids.

The app (engine/SigLipTokenizer.kt) re-implements open_clip's SigLIP 2 text path: big_vision's canonicalisation (ftfy, html
unescape, '_' to space, ASCII punctuation removed, lower case, whitespace collapsed), then SentencePiece BPE, which for this
model is exactly "merge the adjacent pair whose concatenation is a NORMAL piece with the lowest id, leftmost first; a symbol
that is not a piece falls back to its UTF-8 bytes" (checked against sentencepiece and HF on 3,531 strings: identical), then
<eos> (1), truncated to 64 and padded with <pad> (0).

Writes:
  <out>/tokenizer.vocab                      one line per piece: id TAB type TAB piece (\\ \t \n \r escaped); type N normal,
                                             B byte, S special; the pack file (bench-data/models/quicklook/siglip2-b32-256/)
  app/src/test/resources/siglip_vocab_subset.tsv   the same format, only the pieces that are substrings of the golden texts
                                             (all the merge rule ever looks up), so the unit test needs no 4 MB vocabulary
  app/src/test/resources/siglip_tokenizer_golden.tsv   JSON text TAB space-separated ids (64), from open_clip's tokenizer

  build_siglip_vocab.py <out dir> [extra texts file]
"""
import json, sys
from pathlib import Path

import open_clip
import sentencepiece as spm
from sentencepiece import sentencepiece_model_pb2 as pb
from huggingface_hub import hf_hub_download
from open_clip.tokenizer import basic_clean, canonicalize_text

REPO = Path(__file__).resolve().parent.parent
T = pb.ModelProto.SentencePiece


def esc(s):
    return s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r")


def main():
    out = Path(sys.argv[1])
    out.mkdir(parents=True, exist_ok=True)
    model = hf_hub_download("google/siglip2-base-patch32-256", "tokenizer.model")
    m = pb.ModelProto()
    m.ParseFromString(Path(model).read_bytes())
    rows = []
    for i, p in enumerate(m.pieces):
        kind = {T.NORMAL: "N", T.BYTE: "B"}.get(p.type, "S")
        rows.append((i, kind, p.piece))
    (out / "tokenizer.vocab").write_text("".join(f"{i}\t{k}\t{esc(s)}\n" for i, k, s in rows), encoding="utf-8")

    tok = open_clip.get_tokenizer("ViT-B-32-SigLIP2-256")
    texts = ["a dog on the beach", "Embwa ku lubalama!", "mbwa ufukweni", "ng'ombe sokoni", "KDC-260914-0072",
             "Café crème brûlée", "naïve résumé", "Ünïcödé ÀÉÎÕÜ", "日本語のテキスト", "emoji 🐶 dog 🎉", "x\u00a0y\u2003z", "tab\there",
             "it’s “quoted” – dash", "a_b_c snake_case", "AT&amp;T &lt;tag&gt;", "  lots   of\nspace  ", "", "!!!", "...dots...",
             "ɛŋɔ ŋaŋa", "Ωμέγα ΣΊΣΥΦΟΣ", "İstanbul", "ß straße", "ﬁnal ligature", "ＦＵＬＬ ｗｉｄｔｈ", "100% of $5,000 (approx.)",
             " ".join(["word"] * 80), "supercalifragilisticexpialidocious " * 6]
    extra = Path(sys.argv[2]) if len(sys.argv) > 2 else None
    if extra:
        texts += [l.rstrip("\n") for l in extra.read_text(encoding="utf-8").splitlines() if l.strip()][:600]
    ids = tok(texts).numpy()
    gold = REPO / "app/src/test/resources/siglip_tokenizer_golden.tsv"
    gold.write_text("".join(f"{json.dumps(t, ensure_ascii=False)}\t{' '.join(map(str, r))}\n" for t, r in zip(texts, ids)), encoding="utf-8")
    # the subset: every NORMAL piece that is a substring of a canonicalised, space-escaped text; every byte piece; the specials
    norm = [canonicalize_text(basic_clean(t)).replace(" ", "\u2581") for t in texts]
    longest = max(len(s) for i, k, s in rows if k == "N")
    subs = set()
    for s in norm:
        for a in range(len(s)):
            for b in range(a + 1, min(len(s), a + longest) + 1):
                subs.add(s[a:b])
    keep = [(i, k, s) for i, k, s in rows if k != "N" or s in subs]
    (REPO / "app/src/test/resources/siglip_vocab_subset.tsv").write_text("".join(f"{i}\t{k}\t{esc(s)}\n" for i, k, s in keep), encoding="utf-8")
    print(f"vocab {len(rows)} pieces -> {out / 'tokenizer.vocab'} ({(out / 'tokenizer.vocab').stat().st_size} bytes); "
          f"golden {len(texts)} texts; test subset {len(keep)} pieces")
    print("longest normal piece", longest)


if __name__ == "__main__":
    main()
