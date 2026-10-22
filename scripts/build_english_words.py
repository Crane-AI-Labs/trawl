#!/usr/bin/env python3
"""Builds app/src/main/assets/english_words.txt.gz, the word list behind the speech-language gate (engine/EnglishWords.kt).

Source: Webster's Second International (web2), public domain, as shipped in /usr/share/dict/web2 on macOS, plus the 150 words of
/usr/share/dict/connectives and a short list of modern or spoken words web2 lacks (below). Lower-cased, only [a-z]+ words kept,
sorted by byte order, one per line with a final newline, gzip level 9, reproducible (mtime 0). The app finds a word by binary search
over the unpacked bytes, so the order matters: Python's sorted() on ASCII equals the byte order the Kotlin comparison uses.
Inflections (s es ed d ing ly er ers, e+ing, y+ies/ied, contractions) are not stored: the lookup derives them (see EnglishWords.kt).
usage: python3 scripts/build_english_words.py
"""
import gzip, re, sys
from pathlib import Path

OUT = Path(__file__).resolve().parent.parent / "app/src/main/assets/english_words.txt.gz"
EXTRAS = """alright app apps cellphone download email facebook gonna google gotta hello hi hmm instagram internet login mobile nope okay
ok online password selfie smartphone thanks tiktok um uh upload website whatsapp wanna wifi yeah yep youtube""".split()

words = set()
for f in ("/usr/share/dict/web2", "/usr/share/dict/connectives"):
    words |= {w.strip().lower() for w in open(f, errors="ignore")}
words |= set(EXTRAS)
words = sorted(w for w in words if re.fullmatch("[a-z]+", w))
raw = ("\n".join(words) + "\n").encode("ascii")
OUT.parent.mkdir(parents=True, exist_ok=True)
with open(OUT, "wb") as f, gzip.GzipFile(fileobj=f, mode="wb", compresslevel=9, mtime=0, filename="") as g:
    g.write(raw)
print(f"{len(words)} words, {len(raw)} bytes raw, {OUT.stat().st_size} bytes gzipped -> {OUT}")
