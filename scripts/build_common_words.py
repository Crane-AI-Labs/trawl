#!/usr/bin/env python3
"""Builds app/src/main/assets/common_words.txt.gz, the common-English-word list behind the speech-language gate (engine/SpeechLanguageGate.kt).

Source: the sentences of Mozilla Common Voice 17 English (CC0). Every distinct sentence of the TRAIN split counts once; any sentence that also
appears in the test split is left out (the gate was tuned on dev data and scored on held-out clips, never on these sentences). A word is kept
when it occurs at least MIN_COUNT (30) times: that is what separates the words real speech is made of from the rare dictionary words
that Parakeet's junk on Luganda or Swahili hits by luck (a web2 hit is a poor sign of English; a hit on a word seen 30 times in a million
sentences is a good one, the research notes). Tokens are the gate's: [a-z]+('[a-z]+)? in the lower-cased text with curly
quotes folded, contractions kept whole ("don't"). Sorted by byte order, one per line, gzip level 9, reproducible (mtime 0); the app
finds a word by binary search over the unpacked bytes (EnglishWords.contains).
usage: python3 scripts/build_common_words.py <en/train.tsv> <en/test.tsv>     (TSV with sentence_id and sentence columns)
"""
import collections, csv, gzip, re, sys
from pathlib import Path

OUT = Path(__file__).resolve().parent.parent / "app/src/main/assets/common_words.txt.gz"
MIN_COUNT = 30
TOKEN = re.compile(r"[a-z]+(?:'[a-z]+)?")
csv.field_size_limit(10 ** 9)


def sentences(path):
    with open(path, newline="", encoding="utf-8") as f:
        for r in csv.DictReader(f, delimiter="\t", quoting=csv.QUOTE_NONE):
            yield r["sentence_id"], r["sentence"]


def main(train, test):
    held_out = {s for _, s in sentences(test)}
    seen, counts = set(), collections.Counter()
    for sid, s in sentences(train):
        if sid in seen or s in held_out:
            continue
        seen.add(sid)
        counts.update(TOKEN.findall(s.lower().replace("’", "'").replace("‘", "'")))
    words = sorted(w for w, n in counts.items() if n >= MIN_COUNT)
    raw = ("\n".join(words) + "\n").encode("ascii")
    OUT.parent.mkdir(parents=True, exist_ok=True)
    with open(OUT, "wb") as f, gzip.GzipFile(fileobj=f, mode="wb", compresslevel=9, mtime=0, filename="") as g:
        g.write(raw)
    print(f"{len(seen)} sentences, {len(counts)} word types, {len(words)} kept (>= {MIN_COUNT}), {len(raw)} bytes raw, {OUT.stat().st_size} bytes gzipped -> {OUT}")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    main(sys.argv[1], sys.argv[2])
