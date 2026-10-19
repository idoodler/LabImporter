#!/usr/bin/env python3
"""Reuse the iOS translations for the Android app.

For every string/plural in androidApp/src/main/res/values/strings.xml whose
English text is identical to a key in LabImporter/Localizable.xcstrings, this
writes the existing translations into values-<lang>/strings.xml (one file per
shipped language). Android-only strings that have no iOS twin are reported so
they can be added to the catalog / translated — never silently left EN-only.

Format specifiers are mapped both ways: Android %s / %1$s <-> iOS %@ and
Android %d / %1$d <-> iOS %lld (positions are preserved).

Usage: tools/xcstrings_to_android.py [--check]
  --check  exit non-zero if any Android string lacks a translation
"""

from __future__ import annotations

import json
import os
import re
import sys
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CATALOG = os.path.join(ROOT, "LabImporter", "Localizable.xcstrings")
RES = os.path.join(ROOT, "androidApp", "src", "main", "res")
SOURCE = os.path.join(RES, "values", "strings.xml")

# xcstrings locale -> Android resource qualifier
QUALIFIERS = {
    "bg": "bg", "ca": "ca", "cs": "cs", "da": "da", "de": "de", "el": "el", "es": "es",
    "fi": "fi", "fr": "fr", "hr": "hr", "hu": "hu", "it": "it", "ja": "ja", "nl": "nl",
    "pl": "pl", "pt-BR": "pt-rBR", "pt-PT": "pt-rPT", "ro": "ro", "ru": "ru", "sk": "sk",
    "sv": "sv", "tr": "tr", "uk": "uk", "zh-Hans": "b+zh+Hans",
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


def unit_value(loc: dict) -> str | None:
    unit = loc.get("stringUnit")
    if unit and unit.get("state") in ("translated", "needs_review") and unit.get("value"):
        return unit["value"]
    return None


def main() -> int:
    catalog = json.load(open(CATALOG, encoding="utf-8"))["strings"]
    tree = ET.parse(SOURCE)
    out: dict[str, list[str]] = {lang: [] for lang in QUALIFIERS}
    missing: list[str] = []

    for node in tree.getroot():
        name = node.get("name")
        if node.get("translatable") == "false":
            continue
        if node.tag == "string":
            english = unescape_android("".join(node.itertext()))
            positional = "$" in english
            entry = catalog.get(to_ios(english))
            if not entry:
                missing.append(name)
                continue
            for lang in QUALIFIERS:
                value = unit_value(entry.get("localizations", {}).get(lang, {}))
                if value is None:
                    continue
                text = escape_android(to_android(value, positional)).replace("&", "&amp;").replace("<", "&lt;")
                out[lang].append(f'    <string name="{name}">{text}</string>')
        elif node.tag == "plurals":
            items = {item.get("quantity"): unescape_android("".join(item.itertext())) for item in node}
            positional = "$" in items.get("other", "")
            entry = catalog.get(to_ios(items.get("other", "")))
            if not entry:
                missing.append(name)
                continue
            for lang in QUALIFIERS:
                loc = entry.get("localizations", {}).get(lang, {})
                plural = loc.get("variations", {}).get("plural")
                forms = {q: unit_value(v) for q, v in plural.items()} if plural else {"other": unit_value(loc)}
                forms = {q: v for q, v in forms.items() if v}
                if "other" not in forms:
                    continue
                # Android requires "one" for most locales even when iOS only
                # ships "other" (e.g. Turkish); reuse the "other" form.
                forms.setdefault("one", forms["other"])
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
    print(f"{total - len(missing)}/{total} Android strings reuse iOS translations ({len(QUALIFIERS)} languages).")
    if missing:
        print("No iOS translation yet (add these to Localizable.xcstrings and translate):")
        for name in missing:
            print(f"  - {name}")
    return 1 if missing and "--check" in sys.argv else 0


if __name__ == "__main__":
    sys.exit(main())
