#!/usr/bin/env python3
"""Validate a translated strings file and put it in the app.

    python3 scripts/i18n_import.py <translated.xml> <tag> [--dry-run]

<tag> is the language tag: fr, pt, ar, sw, lg, rw, nyn, ach, teo, lgg, am, ha, yo, so, zu (2 letters -> values-<tag>,
3 letters -> values-b+<tag>, the same rule as AppLanguage.valuesQualifier).

Checks (the same rules as TranslationsTest / TranslationCheck.kt): every name exists in English, translatable="false" strings
are not translated, placeholders match English, the xliff app_name span matches, plural keywords are CLDR and "other" is present,
apostrophes and quotes are escaped, the literal app name is absent. Missing English keys are only reported (the app falls back
to English). On any problem nothing is written and the exit code is 1.

Writes app/src/main/res/values-<qualifier>/strings.xml, sorted like the English files. It does NOT add the language to
AppLanguages.shipped or res/xml/locales_config.xml: that is a separate step, once the translation has been reviewed.
Python 3 standard library only.
"""
import argparse
import re
import sys
from pathlib import Path
import xml.etree.ElementTree as ET

sys.path.insert(0, str(Path(__file__).resolve().parent))
import i18n_export as ex  # noqa: E402

QUANTITIES = ex.QUANTITIES


def qualifier(tag):
    return tag if len(tag) == 2 else "b+" + tag.replace("-", "+")


def english_map():
    return {e["name"]: e for e in ex.english_entries()}


def items_of(entry):
    return {i.get("quantity"): i for i in entry["elem"].iter("item")}


def piece_problems(label, en_text, en_slots, tr_text, tr_slots, app_name, exact, allowed):
    out = []
    enp, trp = sorted(ex.placeholders(en_text)), sorted(ex.placeholders(tr_text))
    if exact and enp != trp:
        out.append("%s: placeholders %s do not match English %s" % (label, trp, enp))
    if not exact:
        extra = [p for p in trp if p not in allowed]
        if extra:
            out.append("%s: uses %s, which English does not" % (label, extra))
    if exact:
        if set(en_slots) != set(tr_slots):
            out.append('%s: the app name span (<xliff:g id="app_name">) holds %s but English has %s; keep it as it is' % (label, tr_slots, en_slots))
    else:
        extra = [s for s in tr_slots if s not in en_slots]
        if extra:
            out.append("%s: the app name span holds %s, English has %s" % (label, extra, en_slots))
    if app_name and app_name in tr_text:
        out.append('%s: writes the app name "%s"; use the <xliff:g id="app_name"> placeholder' % (label, app_name))
    if not (len(tr_text) >= 2 and tr_text.startswith('"') and tr_text.endswith('"')):
        # escaped only by an odd number of backslashes: in ma\\'ana the backslash escapes itself and the quote is bare
        m = re.search(r"(?<!\\)(?:\\\\)*'", tr_text)
        if m:
            out.append("%s: unescaped apostrophe at %d (write \\')" % (label, m.start()))
        m = re.search(r'(?<!\\)(?:\\\\)*"', tr_text)
        if m:
            out.append('%s: unescaped double quote at %d (write \\")' % (label, m.start()))
    if re.match(r"^[@?]", tr_text) and not tr_text.startswith("@@"):
        out.append("%s: starts with @ or ? (write \\@ or \\?)" % label)
    if not tr_text.strip():
        out.append("%s: empty" % label)
    return out


def problems(english, translated, app_name):
    """english: {name: entry}; translated: list of entries. Returns a list of problem strings."""
    out = []
    seen = set()
    for t in translated:
        name = t["name"]
        if name in seen:
            out.append("%s: appears twice" % name)
        seen.add(name)
        en = english.get(name)
        if en is None:
            out.append("%s: not in the English strings (a typo, or an old key)" % name)
            continue
        if not en["translatable"]:
            out.append('%s: marked translatable="false" in English, so it must not be translated' % name)
            continue
        if en["plural"] != t["plural"]:
            out.append("%s: English is %s, the translation is %s" % (name, "<plurals>" if en["plural"] else "<string>", "<plurals>" if t["plural"] else "<string>"))
            continue
        if not en["plural"]:
            out += piece_problems(name, ex.full_text(en["elem"]), ex.app_name_slots(en["elem"]),
                                  ex.full_text(t["elem"]), ex.app_name_slots(t["elem"]), app_name, True, None)
            continue
        en_items, tr_items = items_of(en), items_of(t)
        for q in tr_items:
            if q not in QUANTITIES:
                out.append('%s: "%s" is not a plural keyword (zero, one, two, few, many, other)' % (name, q))
        if "other" not in tr_items:
            out.append('%s: plural has no "other" item' % name)
            continue
        en_other = en_items["other"]
        allowed = {p for i in en_items.values() for p in ex.placeholders(ex.full_text(i))}
        for q, item in tr_items.items():
            if q not in QUANTITIES:
                continue
            out += piece_problems("%s[%s]" % (name, q), ex.full_text(en_other), ex.app_name_slots(en_other),
                                  ex.full_text(item), ex.app_name_slots(item), app_name, q == "other", allowed)
    return out


def output_document(english, translated):
    """Translated entries in English order, plural items in CLDR order, as an Android strings file."""
    by_name = {t["name"]: t for t in translated}
    lines = ['<?xml version="1.0" encoding="utf-8"?>', '<resources xmlns:xliff="%s">' % ex.XLIFF]
    for name in english:
        t = by_name.get(name)
        if t is None:
            continue
        elem = t["elem"]
        elem.attrib.pop("translatable", None)
        if t["plural"]:
            items = sorted(elem.findall("item"), key=lambda i: QUANTITIES.index(i.get("quantity")))
            for i in list(elem):
                elem.remove(i)
            for i in items:
                i.tail = None
                elem.append(i)
            body = ex.render(elem)
            body = re.sub(r"(<item\b)", r"\n    \1", body).replace("</plurals>", "\n  </plurals>")
        else:
            body = ex.render(elem)
        lines.append("  " + body)
    lines.append("</resources>")
    return "\n".join(lines) + "\n"


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("translated", type=Path)
    ap.add_argument("tag")
    ap.add_argument("--dry-run", action="store_true", help="validate and summarise, write nothing")
    ap.add_argument("--file", default="strings.xml",
                    help="the strings file this translates, e.g. strings_background.xml: written as values-<tag>/<file>, and only "
                         "the English strings of values/<file> count as missing")
    ap.add_argument("--res-dir", default=None,
                    help="a flavor's own res folder, e.g. app/src/go/res (Trawl Go): the English is that folder's values/strings.xml "
                         "(its names override the app's), and the result is written to <res-dir>/values-<tag>/strings.xml")
    ap.add_argument("--replace", action="store_true", help="allow an import that drops strings the existing file has")
    args = ap.parse_args()

    if not re.fullmatch(r"[a-z]{2,3}(-[A-Za-z0-9]{2,8})*", args.tag) or args.tag == "en":
        sys.exit("tag %r is not a language tag other than en (examples: fr, sw, nyn, pt-BR)" % args.tag)
    try:
        root = ex.parse(args.translated)
    except ET.ParseError as e:
        sys.exit("%s is not valid XML: %s" % (args.translated, e))
    if root.tag != "resources":
        sys.exit("root element is <%s>, expected <resources>" % root.tag)

    english = english_map()
    app_name = ex.full_text(english["app_name"]["elem"]) if "app_name" in english else ""
    flavor_res = None
    if args.res_dir:
        flavor_res = ex.ROOT / args.res_dir
        en_file = flavor_res / "values" / "strings.xml"
        if not en_file.is_file():
            sys.exit("no English strings at %s" % en_file)
        flavor = {e["name"]: e for e in ex.load_entries(ex.parse(en_file))}
        missing_in_app = sorted(n for n in flavor if n not in english)
        if missing_in_app:
            sys.exit("these flavor strings override nothing in the app: %s" % ", ".join(missing_in_app))
        english = flavor
    translated = ex.load_entries(root)
    probs = problems(english, translated, app_name)

    scope = set(english)
    if args.file != "strings.xml":
        en_file = ex.ROOT / ex.RES_VALUES / args.file
        if not en_file.is_file():
            sys.exit("no English %s in %s" % (args.file, ex.RES_VALUES))
        scope = {e["name"] for e in ex.load_entries(ex.parse(en_file))}
        stray = sorted(t["name"] for t in translated if t["name"] not in scope)
        if stray:
            sys.exit("these strings are not in values/%s, so they would be defined twice: %s" % (args.file, ", ".join(stray)))
    translatable = [n for n, e in english.items() if e["translatable"] and n in scope]
    got = {t["name"] for t in translated}
    missing = [n for n in translatable if n not in got]

    folder = (flavor_res or ex.ROOT / "app/src/main/res") / ("values-" + qualifier(args.tag))
    print("language %s -> %s" % (args.tag, folder.relative_to(ex.ROOT)))
    print("translated %d of %d strings; missing %d; problems %d" % (len(got & set(translatable)), len(translatable), len(missing), len(probs)))
    if missing:
        shown = ", ".join(missing[:20]) + (" ... (+%d more)" % (len(missing) - 20) if len(missing) > 20 else "")
        print("missing English keys (the app shows English for these): " + shown)
    for p in probs:
        print("PROBLEM " + p)
    if probs:
        print("nothing written")
        sys.exit(1)
    if args.dry_run:
        print("dry run: nothing written")
        return
    target = folder / args.file
    if target.is_file() and not args.replace:
        # A partial file must never overwrite a fuller one (the 9-string delta once nearly replaced a 412-string strings.xml).
        before = {e["name"] for e in ex.load_entries(ex.parse(target))}
        lost = sorted(before - got)
        if lost:
            sys.exit("refusing to overwrite %s: it would drop %d strings it has now (%s ...); pass --replace if that is meant, "
                     "or --file for a delta that has its own English file" % (target.relative_to(ex.ROOT), len(lost), ", ".join(lost[:5])))
    folder.mkdir(parents=True, exist_ok=True)
    target.write_text(output_document(list(english), translated), encoding="utf-8")
    print("wrote %s" % target.relative_to(ex.ROOT))
    print("not switched on: add the tag to AppLanguages.shipped and res/xml/locales_config.xml when it is reviewed")


if __name__ == "__main__":
    main()
