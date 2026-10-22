#!/usr/bin/env python3
"""Builds the two files behind engine/ClipTokenizer.kt, both from open_clip's own tokenizer so the Kotlin port is checked against the original.

1. app/src/main/assets/clip_bpe_merges.txt.gz: the 48,894 merges open_clip's SimpleTokenizer uses (lines [1 : 49152-256-2+1] of its
   bpe_simple_vocab_16e6.txt.gz, header line dropped, one "a b" pair per line, UTF-8, gzip level 9, reproducible with mtime 0).
   The other ~213,000 lines of that file are never used by any CLIP model, so they are not shipped.
2. app/src/test/resources/clip_tokenizer_golden.tsv: one line per case, JSON-encoded text (ASCII-escaped, so tabs, newlines and astral
   characters survive), TAB, the 77 ids space-separated, exactly what open_clip's tokenizer returns for it.

The ONNX text tower was exported with open_clip.get_tokenizer("hf-hub:laion/CLIP-ViT-B-32-laion2B-s34B-b79K"). That repo's
open_clip_config.json has no hf_tokenizer_name and no tokenizer_kwargs, so open_clip resolves it to SimpleTokenizer(context_length=77,
clean="lower"), which is what this script instantiates (SimpleTokenizer() with its defaults).

No torch is needed. open_clip/tokenizer.py imports torch and numpy at the top, so this script loads that one file by path and stubs both
modules (torch is only used for the output tensor). It needs ftfy and regex, which the tokenizer imports for real.

Run (small venv, no torch; ~20 MB):
  python3 -m venv /tmp/clipvenv && /tmp/clipvenv/bin/pip install ftfy regex numpy
  /tmp/clipvenv/bin/pip download open_clip_torch --no-deps -d /tmp/clipwhl && unzip -q /tmp/clipwhl/*.whl -d /tmp/clipwhl/x
  /tmp/clipvenv/bin/python scripts/build_clip_tokenizer.py --open-clip-dir /tmp/clipwhl/x/open_clip
Without --open-clip-dir it uses an installed open_clip (found without importing it). --queries points at the eval queries
(default bench-data/eval-en/queries.json, which is git-ignored, so pass the main checkout's copy from a worktree).
"""
import argparse, gzip, importlib.util, json, sys, types
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
N_MERGES = 49152 - 256 - 2   # open_clip: merges[1 : 49152-256-2+1]
CONTEXT = 77


def load_tokenizer_module(open_clip_dir):
    """tokenizer.py by path, with torch and numpy replaced by the few calls its __call__ makes."""
    import numpy as np
    torch = types.ModuleType("torch")
    torch.LongTensor = object
    torch.Tensor = object
    torch.long = np.int64
    torch.zeros = lambda *shape, dtype=None: np.zeros(shape, dtype=dtype)
    torch.tensor = lambda x: np.array(x)
    sys.modules["torch"] = torch
    path = Path(open_clip_dir) / "tokenizer.py"
    spec = importlib.util.spec_from_file_location("clip_tokenizer_ref", path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod, Path(open_clip_dir) / "bpe_simple_vocab_16e6.txt.gz"


def edge_cases():
    c = []
    # punctuation and symbols
    c += ["a photo of a dog.", "Hello, world!", "what?! really...", "(brackets) [square] {curly}", "a/b\\c|d", "100% sure #tag @user",
          "price: $5.99 + tax = ~6", "quote \"double\" and 'single'", "a--b---c", "...", "!!!", "-", "_", "<>", "a<b", "x > y"]
    # digits and codes
    c += ["IMG_2026_receipt", "4815162342", "K7Q2LM", "12:30pm", "2026-10-15", "+256 700 123456", "v1.2.3", "3.14159", "A1B2C3", "0",
          "IMG_20260814_153022.jpg", "PO-00417/2026", "1,234,567.89", "٣٤٥ and ²³ and ½ and ①"]
    # contractions and apostrophes, straight and curly
    c += ["it's", "don't", "we're", "I've", "I'm", "they'll", "he'd", "can't won't", "IT'S DON'T WE'RE I'VE I'M THEY'LL HE'D",
          "it’s", "don’t we’re I’ve I’m they’ll he’d", "‘quoted’ and “double”", "rock 'n' roll", "'tis the season", "o'clock",
          "''", "'", "dogs' toys", "ng'ombe", "n'amannyo", "it'ſ", "x'sx", "'ss", "'re're", "he'llo", "ʼn ʼs"]
    # upper case
    c += ["HELLO WORLD", "Hello World", "McDonald's Big Mac", "THE QUICK BROWN FOX", "ΟΔΥΣΣΕΥΣ", "İstanbul", "ǅungla", "ẞ STRASSE"]
    # accents
    c += ["café", "naïve", "Zoë", "CAFÉ", "résumé", "El Niño", "façade", "Ångström", "école", "café", "Ōsaka", "Dvořák",
          "ﬁne ﬂuffy ﬃ ﬀ ﬅ", "Ĳsselmeer ǆ", "ʼn"]
    # Luganda and Swahili
    c += ["embwa", "ng'ombe", "mbwa ufukweni", "Omusajja agenze mu katale", "Nsonyiwa, nnyinza okuyamba ntya?", "ekitabo ky'omwana",
          "Nnalimu w'ekibiina", "ssente z'omukwano", "Kampala e Mukono", "emmotoka empya", "kuku wa kienyeji", "Habari yako, rafiki?",
          "ninataka kununua nyama sokoni", "chakula kitamu sana", "picha ya mama na watoto", "risiti ya malipo ya umeme",
          "Ntegeka okugenda ku ssomero enkya", "ŋ ɲ ɔ ɛ", "taarifa ya benki"]
    # other scripts, emoji
    c += ["你好，世界", "我喜欢猫和狗", "مرحبا بالعالم", "كلب وقطة", "שלום עולם", "Привет, мир", "こんにちは世界", "안녕하세요", "नमस्ते दुनिया",
          "สวัสดีชาวโลก", "😀", "👍🏽 great", "🐶🐱", "👨‍👩‍👧", "🇺🇬 Uganda", "I ❤️ pizza 🍕", "a​b", "﻿bom", "𝒥𝒶𝓃𝑒"]
    # whitespace
    c += ["tab\there", "line1\nline2", "  leading and trailing  ", "many    spaces   here", "a\r\nb", "a\rb", "a b c", "a\x85b",
          "a b", "a　b", "a b", "a\x0bb\x0cc", "a\x1cb", "", " ", "   ", "\t\n", "\n", " ", "​"]
    # html entities and ftfy-style fixes
    c += ["fish &amp; chips", "a &lt; b &gt; c", "&amp;amp;", "&amp;lt;b&amp;gt;", "&quot;hi&quot; &apos;x&apos;", "&nbsp;x&nbsp;",
          "caf&eacute; &EACUTE; &Eacute;", "&ntilde; &NTILDE; &nTILDE;", "&copy; 2026 &reg; &trade;", "&#8217;s &#x41;&#66; &#x1F600;",
          "&#0; &#128; &#xD800; &#1114112;", "&amp", "&lt &gt", "a&b", "&notit;", "&hellip; &mdash; &ndash; &euro;", "a<b&amp;c",
          "<b>bold</b> &amp; more", "<div>x</div>\nnext &amp; line", "first &amp; line\nsecond <i>x</i>\nthird &amp; line", "&frac12; &sup2; &bull;"]
    c += ["ＬＯＵＤ　ＮＯＩＳＥＳ", "Ｕﾀｰﾝ", "ｱｲｳ ｶﾞ", "１２３ ＡＢＣ ａｂｃ", "a\x01b\x02c", "a\x7fb", "\x1b[31mred\x1b[0m text", "a⁪b￹c￼d",
          "\x93hi\x94 \x85 \x80", "ʼn ‛x‟ „y"]
    # mojibake that ftfy repairs and this port deliberately does not
    c += ["cafÃ© au lait", "donâ€™t", "naÃ¯ve", "âœ” done", "Ã€"]
    # special tokens
    c += ["<start_of_text>", "<end_of_text>", "hi <start_of_text> there <end_of_text> x", "<START_OF_TEXT>", "<|startoftext|>", "<|endoftext|>",
          "<|startoftext|> a photo <|endoftext|>", "<start_of_text", "start_of_text>"]
    # length and truncation
    word = "supercalifragilisticexpialidocious"
    c += [word, word * 12, "x" * 500, "a " * 73, "a " * 74, "a " * 75, "a " * 76, "a " * 77, "a " * 200,
          "the quick brown fox jumps over the lazy dog " * 12, "a photo of a dog, " * 30, "ng'ombe " * 60, "你好" * 60, "😀" * 80, "12345 " * 40]
    return c


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--open-clip-dir", help="directory holding open_clip's tokenizer.py and bpe_simple_vocab_16e6.txt.gz")
    ap.add_argument("--queries", default=str(ROOT / "bench-data/eval-en/queries.json"))
    ap.add_argument("--asset", default=str(ROOT / "app/src/main/assets/clip_bpe_merges.txt.gz"))
    ap.add_argument("--golden", default=str(ROOT / "app/src/test/resources/clip_tokenizer_golden.tsv"))
    a = ap.parse_args()

    d = a.open_clip_dir
    if not d:
        spec = importlib.util.find_spec("open_clip")
        if spec is None or not spec.submodule_search_locations:
            sys.exit("open_clip not installed; pass --open-clip-dir")
        d = list(spec.submodule_search_locations)[0]
    mod, vocab_path = load_tokenizer_module(d)

    lines = gzip.open(vocab_path).read().decode("utf-8").split("\n")
    merges = lines[1:N_MERGES + 1]
    assert len(merges) == N_MERGES and all(len(m.split()) == 2 for m in merges)
    raw = ("\n".join(merges) + "\n").encode("utf-8")
    out = Path(a.asset)
    out.parent.mkdir(parents=True, exist_ok=True)
    with open(out, "wb") as f, gzip.GzipFile(fileobj=f, mode="wb", compresslevel=9, mtime=0, filename="") as g:
        g.write(raw)
    print(f"{len(merges)} merges, {len(raw)} bytes raw, {out.stat().st_size} bytes gzipped -> {out}")

    tok = mod.SimpleTokenizer(bpe_path=str(vocab_path))   # defaults: context_length 77, clean="lower"
    assert tok.context_length == CONTEXT and tok.sot_token_id == 49406 and tok.eot_token_id == 49407
    queries = [q["q"] for q in json.load(open(a.queries))["queries"]]
    texts = queries + edge_cases()
    seen, rows = set(), []
    for t in texts:
        if t in seen:
            continue
        seen.add(t)
        ids = tok(t)[0].tolist()
        assert len(ids) == CONTEXT
        rows.append(json.dumps(t, ensure_ascii=True) + "\t" + " ".join(map(str, ids)))
    g = Path(a.golden)
    g.parent.mkdir(parents=True, exist_ok=True)
    g.write_text("\n".join(rows) + "\n", encoding="utf-8")
    print(f"{len(rows)} golden cases ({len(queries)} queries + {len(rows) - len(set(queries))} edge cases) -> {g}")


if __name__ == "__main__":
    main()
