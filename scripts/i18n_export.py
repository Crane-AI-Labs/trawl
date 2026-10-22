#!/usr/bin/env python3
"""Export the English strings for translators.

    python3 scripts/i18n_export.py                  # internal/i18n/strings-en.xml (+ README.md)
    python3 scripts/i18n_export.py --since <ref>    # also internal/i18n/strings-en.delta.xml: strings added or changed since <ref>

Merges every app/src/main/res/values/strings*.xml into one file, keeps each string's preceding XML comment as context,
adds an "[auto]" note saying what each placeholder means, and leaves out translatable="false" strings.
Python 3 standard library only. internal/ is git-ignored, so nothing here enters the repo.
"""
import argparse
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

XLIFF = "urn:oasis:names:tc:xliff:document:1.2"
ET.register_namespace("xliff", XLIFF)
ROOT = Path(__file__).resolve().parent.parent
RES_VALUES = "app/src/main/res/values"
OUT_DIR = ROOT / "internal" / "i18n"

QUANTITIES = ["zero", "one", "two", "few", "many", "other"]
# Same pattern as TranslationCheck.kt: %1$s, %2$d, %d, %s, %,d, %.1f, %%, %n
PLACEHOLDER = re.compile(r"%(?:(\d+)\$)?[-#+ 0,(]*\d*(?:\.\d+)?[a-zA-Z%]")
SHORT_KEY = re.compile(r"^(action_.*|.*_button|onb_next|filter_.*|switch_.*)$")


def parse(source):
    """Parse a file path or an XML string; keep comments. Returns the <resources> element."""
    parser = ET.XMLParser(target=ET.TreeBuilder(insert_comments=True))
    if isinstance(source, Path):
        return ET.parse(str(source), parser=parser).getroot()
    return ET.fromstring(source, parser=parser)


def full_text(elem):
    return "".join(elem.itertext())


def placeholders(text):
    return [m.group(0) for m in PLACEHOLDER.finditer(text)]


def app_name_slots(elem):
    return [full_text(g) for g in elem.iter("{%s}g" % XLIFF) if g.get("id") == "app_name"]


def render(elem):
    """One element as text, without its tail and without the xmlns:xliff declaration ET adds to a lone element."""
    saved = elem.tail
    elem.tail = None
    s = ET.tostring(elem, encoding="unicode")
    elem.tail = saved
    return re.sub(r'\s+xmlns:xliff="%s"' % re.escape(XLIFF), "", s)


def load_entries(resources):
    """List of dicts: name, plural, translatable, comments (the comments right before it), elem."""
    out = []
    pending = []
    for child in resources:
        if child.tag is ET.Comment:
            pending.append((child.text or "").strip())
            continue
        if child.tag in ("string", "plurals"):
            out.append({
                "name": child.get("name"),
                "plural": child.tag == "plurals",
                "translatable": child.get("translatable") != "false",
                "comments": pending,
                "elem": child,
            })
        pending = []
    return out


def english_entries(read=None):
    """All entries of values/strings*.xml, in file-name then file order. [read] maps a file name to its text (used for git refs)."""
    files = sorted((ROOT / RES_VALUES).glob("strings*.xml")) if read is None else read
    out = []
    for f in files:
        out += load_entries(parse(f))
    return out


def auto_notes(entry):
    """Plain-language notes for the translator, derived from the key and the placeholders."""
    e = entry["elem"]
    name = entry["name"]
    notes = []
    if SHORT_KEY.match(name):
        notes.append("Keep short: button label.")
    texts = [full_text(i) for i in e.iter("item")] if entry["plural"] else [full_text(e)]
    spans = set(app_name_slots(e))
    seen = {}
    for t in texts:
        for m in PLACEHOLDER.finditer(t):
            if m.group(0) in ("%%", "%n"):
                continue
            idx = m.group(1) or "next"
            if m.group(0) in spans or any(m.group(0) == s for s in spans):
                continue
            kind = m.group(0)[-1]
            after_pct = t[m.end():m.end() + 2] == "%%"
            if kind == "d" and after_pct:
                what = "a percentage"
            elif kind == "d":
                what = "a number (a count, size or amount)"
            elif kind in "fF":
                what = "a decimal number"
            else:
                what = "text filled in by the app (a file name, a size such as \"142 MB\", a formatted number, or another sentence)"
            seen.setdefault(m.group(0), what)
            _ = idx
    for ph in sorted(spans):
        notes.append("%s is the app name; it sits inside an <xliff:g id=\"app_name\"> span: keep the span and the placeholder exactly, and translate only the words around it." % ph)
    for ph, what in seen.items():
        notes.append("%s is %s." % (ph, what))
    if "%%" in "".join(texts):
        notes.append("%% is a literal percent sign: keep it doubled.")
    if "\\n" in "".join(texts):
        notes.append("Keep \\n (a line break) where it is.")
    if entry["plural"]:
        notes.append("Plurals: give one <item> for each plural form your language uses (CLDR), always including quantity=\"other\".")
    return notes


def clean_comment(text):
    return text.replace("--", "- -")


def entry_block(entry):
    lines = []
    for c in entry["comments"]:
        lines.append("  <!-- %s -->" % clean_comment(c))
    notes = auto_notes(entry)
    if notes:
        lines.append("  <!-- [auto] %s -->" % clean_comment(" ".join(notes)))
    lines.append("  " + render(entry["elem"]).replace("\n", "\n  "))
    return "\n".join(lines)


def document(entries, header):
    parts = ['<?xml version="1.0" encoding="utf-8"?>', '<resources xmlns:xliff="%s">' % XLIFF, "  <!-- %s -->" % clean_comment(header), ""]
    for e in entries:
        parts.append(entry_block(e))
        parts.append("")
    parts.append("</resources>")
    return "\n".join(parts) + "\n"


def git_files_at(ref):
    """{file name: text} of values/strings*.xml at a git ref."""
    names = subprocess.run(["git", "ls-tree", "--name-only", ref, RES_VALUES + "/"], cwd=ROOT, capture_output=True, text=True)
    if names.returncode != 0:
        sys.exit("git could not read ref %r: %s" % (ref, names.stderr.strip()))
    out = []
    for path in names.stdout.split():
        if re.match(r"strings.*\.xml$", Path(path).name):
            out.append(subprocess.run(["git", "show", "%s:%s" % (ref, path)], cwd=ROOT, capture_output=True, text=True, check=True).stdout)
    return out


def changed_since(ref, current):
    old = {}
    for text in git_files_at(ref):
        for e in load_entries(parse(text)):
            old[e["name"]] = render(e["elem"])
    return [e for e in current if old.get(e["name"]) != render(e["elem"])]


README = """# Translating the app's strings

You are translating the screens of a phone app that finds files by meaning. The app works offline and never sends anything
anywhere, so some sentences say so; keep that meaning. Write for an everyday phone user, in plain, short sentences.

Files here:
- `strings-en.xml`: every English string to translate (`translatable="false"` strings are left out on purpose).
- `strings-en.delta.xml`: only strings added or changed since a release; present when export was run with `--since <ref>`.

## Rules (the import script checks every one of these)
1. Keep every placeholder: `%1$s`, `%2$d`, `%d`, `%s`, `%%`. The set must match English exactly. Reorder them freely when your
   grammar needs it (`%2$s ... %1$d`), because they are numbered.
2. Keep each `<xliff:g id="app_name" example="Trawl">%1$s</xliff:g>` span exactly as it is, including its number. It is where
   the app's name goes. Never write the app's name yourself.
3. Keep `\\n` (a line break) and write an apostrophe as `\\'`. A double quote is `\\"`. A string cannot start with `@` or `?`
   unless escaped (`\\@`, `\\?`).
4. Plurals (`<plurals>`): write one `<item quantity="...">` for each plural form your language has in CLDR
   (https://cldr.unicode.org/index/cldr-spec/plural-rules): `zero one two few many other`. `other` is always required. An item
   may leave out a number (for example "one file" in place of "%1$d file"), but never add a placeholder English does not have.
5. Short labels: the notes marked "Keep short: button label" are buttons and tabs. Make them as short as you can.
6. `<!-- ... -->` comments before a string explain where it appears; `[auto]` notes explain its placeholders. They are not
   part of the translation. Do not copy them into your file.
7. Do not translate the unit symbols `MB`, `KB` or file names such as `findr-speech-models.zip` unless your language writes them
   differently.
8. Languages marked "(preview)" in the picker (Acholi, Ateso, Lugbara) are shown to people as unfinished. Translate everything
   anyway; if unsure of a sentence, do your best and flag it in the cover note.

## Returning your work
Return one XML file in the same shape as `strings-en.xml` (a `<resources>` root with the xliff namespace declared), with the same
`name` on each string, in your language. Then, from the repo root:

    python3 scripts/i18n_import.py path/to/translated.xml <tag>

`<tag>` is the language tag: `fr`, `pt`, `ar`, `sw`, `lg`, `rw`, `nyn`, `ach`, `teo`, `lgg`, `am`, `ha`, `yo`, `so`, `zu`.
It validates the file, then writes `app/src/main/res/values-<tag>/strings.xml` (three-letter tags become `values-b+nyn`).
It does not switch the language on: adding it to `AppLanguages.shipped` and `res/xml/locales_config.xml` is a separate step, taken
when the translation has been reviewed.
"""


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--since", metavar="REF", help="also write strings-en.delta.xml with strings added or changed since this git ref")
    args = ap.parse_args()

    entries = [e for e in english_entries() if e["translatable"]]
    names = [e["name"] for e in entries]
    dup = sorted({n for n in names if names.count(n) > 1})
    if dup:
        sys.exit("duplicate string names across values/strings*.xml: %s" % dup)

    OUT_DIR.mkdir(parents=True, exist_ok=True)
    (OUT_DIR / "strings-en.xml").write_text(document(entries, "English source strings. Translate each string; see README.md for the rules."), encoding="utf-8")
    (OUT_DIR / "README.md").write_text(README, encoding="utf-8")
    print("wrote %s (%d strings)" % ((OUT_DIR / "strings-en.xml").relative_to(ROOT), len(entries)))

    if args.since:
        delta = changed_since(args.since, entries)
        (OUT_DIR / "strings-en.delta.xml").write_text(document(delta, "Strings added or changed since %s." % args.since), encoding="utf-8")
        print("wrote %s (%d strings added or changed since %s)" % ((OUT_DIR / "strings-en.delta.xml").relative_to(ROOT), len(delta), args.since))


if __name__ == "__main__":
    main()
