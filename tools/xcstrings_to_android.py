#!/usr/bin/env python3
"""Generate the Android translations from String Catalogs.

For every string/plural in androidApp/src/main/res/values/strings.xml the
translations come from, in order:

  1. androidApp/translations/Android.xcstrings — Android-only wording, in the
     same String Catalog format; it also overrides single languages of shared
     wording where Android needs different text (e.g. a gender-neutral form
     where iOS inflects automatically);
  2. LabImporter/Localizable.xcstrings — the iOS catalog, when the English
     text is identical to one of its keys (shared wording is translated once).

Apple's automatic grammar agreement markup (`^[word](inflect: true)`) has no
Android equivalent; a translation containing it must be overridden in
Android.xcstrings, and --check fails otherwise.

The result is written to values-<lang>/strings.xml (one generated file per
shipped language). Strings missing from both catalogs, or missing a language,
are reported — never silently left English-only.

Format specifiers are mapped both ways: Android %s / %1$s <-> iOS %@ and
Android %d / %1$d <-> iOS %lld (positions are preserved).

Usage: tools/xcstrings_to_android.py [--check]
  --check  exit non-zero if any Android string lacks a translation (CI)
"""

from __future__ import annotations

import json
import os
import re
import sys
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CATALOG = os.path.join(ROOT, "LabImporter", "Localizable.xcstrings")
ANDROID_CATALOG = os.path.join(ROOT, "androidApp", "translations", "Android.xcstrings")
RES = os.path.join(ROOT, "androidApp", "src", "main", "res")
SOURCE = os.path.join(RES, "values", "strings.xml")

# xcstrings locale -> Android resource qualifier
QUALIFIERS = {
    "bg": "bg", "ca": "ca", "cs": "cs", "da": "da", "de": "de", "el": "el", "es": "es",
    "fi": "fi", "fr": "fr", "hr": "hr", "hu": "hu", "it": "it", "ja": "ja", "nl": "nl",
    "pl": "pl", "pt-BR": "pt-rBR", "pt-PT": "pt-rPT", "ro": "ro", "ru": "ru", "sk": "sk",
    "sv": "sv", "tr": "tr", "uk": "uk", "zh-Hans": "b+zh+Hans",
}

# Plural quantities Android expects per locale (integers; CLDR).
REQUIRED_QUANTITIES = {
    "ja": ("other",), "zh-Hans": ("other",),
    "cs": ("one", "few", "many", "other"), "sk": ("one", "few", "many", "other"),
    "pl": ("one", "few", "many", "other"), "ru": ("one", "few", "many", "other"),
    "uk": ("one", "few", "many", "other"),
    "hr": ("one", "few", "other"), "ro": ("one", "few", "other"),
    # "many" covers millions here ("1000000 de jours"), worded like "other".
    "ca": ("one", "many", "other"), "es": ("one", "many", "other"), "fr": ("one", "many", "other"),
    "it": ("one", "many", "other"), "pt-BR": ("one", "many", "other"), "pt-PT": ("one", "many", "other"),
}

ANDROID_SPEC = re.compile(r"%(?:(\d+)\$)?([sd])")
IOS_SPEC = re.compile(r"%(?:(\d+)\$)?(@|lld|ld|d)")


def unescape_android(text: str) -> str:
    return text.replace("\\'", "'").replace('\\"', '"').replace("\\n", "\n").replace("\\@", "@").replace("\\?", "?")


def escape_android(text: str) -> str:
    text = text.replace("\\", "\\\\").replace("'", "\\'").replace('"', '\\"').replace("\n", "\\n")
    if text.startswith(("@", "?")):
        text = "\\" + text
    return text


def to_ios(text: str) -> str:
    return ANDROID_SPEC.sub(lambda m: "%" + (m.group(1) + "$" if m.group(1) else "") + ("@" if m.group(2) == "s" else "lld"), text)


def to_android(text: str, positional: bool) -> str:
    counter = iter(range(1, 100))

    def repl(m: re.Match) -> str:
        kind = "s" if m.group(2) == "@" else "d"
        pos = m.group(1) or (str(next(counter)) if positional else None)
        return "%" + (pos + "$" if pos else "") + kind

    return IOS_SPEC.sub(repl, text)


def lookup(catalogs: list[dict], english: str) -> dict | None:
    """The entry for an Android English text: exact, or with sequential
    positional specifiers dropped (Android "%1$s of %2$d" == iOS "%@ of %lld").
    Localizations from earlier catalogs win per language (Android overrides)."""
    candidates = [to_ios(english)]
    positions = re.findall(r"%(\d+)\$", english)
    if positions and positions == [str(i) for i in range(1, len(positions) + 1)]:
        candidates.append(to_ios(re.sub(r"%\d+\$", "%", english)))
    merged: dict | None = None
    for catalog in catalogs:
        for key in candidates:
            if key in catalog:
                localizations = catalog[key].get("localizations", {})
                if merged is None:
                    merged = {"localizations": dict(localizations)}
                else:
                    for lang, loc in localizations.items():
                        merged["localizations"].setdefault(lang, loc)
                break
    return merged


INFLECTION = re.compile(r"\^\[[^\]]*\]\(inflect:")


def unit_value(loc: dict) -> str | None:
    unit = loc.get("stringUnit")
    if unit and unit.get("state") in ("translated", "needs_review") and unit.get("value"):
        return unit["value"]
    return None


def main() -> int:
    catalogs = [json.load(open(path, encoding="utf-8"))["strings"] for path in (ANDROID_CATALOG, CATALOG) if os.path.exists(path)]
    tree = ET.parse(SOURCE)
    out: dict[str, list[str]] = {lang: [] for lang in QUALIFIERS}
    missing: list[str] = []
    incomplete: list[str] = []
    inflected: list[str] = []

    for node in tree.getroot():
        name = node.get("name")
        if node.get("translatable") == "false":
            continue
        if node.tag == "string":
            english = unescape_android("".join(node.itertext()))
            positional = "$" in english
            entry = lookup(catalogs, english)
            if not entry:
                missing.append(name)
                continue
            for lang in QUALIFIERS:
                value = unit_value(entry.get("localizations", {}).get(lang, {}))
                if value is None:
                    incomplete.append(f"{name} [{lang}]")
                    continue
                if INFLECTION.search(value):
                    inflected.append(f"{name} [{lang}]")
                    continue
                text = escape_android(to_android(value, positional)).replace("&", "&amp;").replace("<", "&lt;")
                out[lang].append(f'    <string name="{name}">{text}</string>')
        elif node.tag == "plurals":
            items = {item.get("quantity"): unescape_android("".join(item.itertext())) for item in node}
            positional = "$" in items.get("other", "")
            entry = lookup(catalogs, items.get("other", ""))
            if not entry:
                missing.append(name)
                continue
            for lang in QUALIFIERS:
                loc = entry.get("localizations", {}).get(lang, {})
                plural = loc.get("variations", {}).get("plural")
                forms = {q: unit_value(v) for q, v in plural.items()} if plural else {"other": unit_value(loc)}
                forms = {q: v for q, v in forms.items() if v}
                if "other" not in forms:
                    incomplete.append(f"{name} [{lang}]")
                    continue
                # Android lint wants every quantity the locale uses, even when a
                # catalog only ships "other" (e.g. a colon form that needs no
                # agreement, or Turkish); reuse the "other" form for those.
                for quantity in REQUIRED_QUANTITIES.get(lang, ("one", "other")):
                    forms.setdefault(quantity, forms["other"])
                lines = [f'    <plurals name="{name}">']
                for quantity, value in forms.items():
                    text = escape_android(to_android(value, positional)).replace("&", "&amp;").replace("<", "&lt;")
                    lines.append(f'        <item quantity="{quantity}">{text}</item>')
                lines.append("    </plurals>")
                out[lang].append("\n".join(lines))

    for lang, qualifier in QUALIFIERS.items():
        directory = os.path.join(RES, f"values-{qualifier}")
        os.makedirs(directory, exist_ok=True)
        with open(os.path.join(directory, "strings.xml"), "w", encoding="utf-8") as fh:
            fh.write('<?xml version="1.0" encoding="utf-8"?>\n')
            fh.write("<!-- GENERATED by tools/xcstrings_to_android.py from LabImporter/Localizable.xcstrings. Do not edit. -->\n")
            fh.write('<resources xmlns:tools="http://schemas.android.com/tools" tools:ignore="MissingTranslation">\n')
            fh.write("\n".join(out[lang]) + "\n")
            fh.write("</resources>\n")

    total = sum(1 for n in tree.getroot() if n.get("translatable") != "false")
    print(f"{total - len(missing)}/{total} Android strings translated into {len(QUALIFIERS)} languages "
          "(androidApp/translations/Android.xcstrings first, then the iOS catalog).")
    if missing:
        print("Not in any catalog (add to Localizable.xcstrings if iOS shares the wording, else to Android.xcstrings):")
        for name in missing:
            print(f"  - {name}")
    if incomplete:
        print("Missing a translation:")
        for item in incomplete:
            print(f"  - {item}")
    if inflected:
        print("Uses Apple's inflection markup (override it in Android.xcstrings with neutral wording):")
        for item in inflected:
            print(f"  - {item}")
    return 1 if (missing or incomplete or inflected) and "--check" in sys.argv else 0


if __name__ == "__main__":
    sys.exit(main())
