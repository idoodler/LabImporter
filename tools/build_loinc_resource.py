#!/usr/bin/env python3
"""Distill the official LOINC release zip into a compact bundle resource.

This runs as an Xcode build phase (see the "Generate LOINC resource" script
phase on the LabImporter target). It reads the LOINC release archive committed
under Vendor/LOINC/*.zip, keeps only the common laboratory terms, and writes a
SQLite database (loinc.db) consumed at runtime by LoincDirectory.swift. For
every term it stores a localized name and description in each language the app
ships, plus an FTS5 index over the names for fast search.

The database is built for speed: it opens instantly (no parse), exact code
lookups are indexed, and search uses an external-content FTS5 index so the text
is not duplicated. See LoincDirectory.swift for the queries.

Selection criteria ("common lab tests only"):
  * CLASSTYPE == 1            -> Laboratory class
  * STATUS    == ACTIVE       -> not deprecated/trial
  * COMMON_TEST_RANK > 0      -> appears in LOINC's common-test ordering

Languages:
  LANGS maps an app language code (as used in Localizable.xcstrings) to the
  LOINC linguistic-variant file that provides its translations. `None` means no
  LOINC variant exists for that language (or it is the English base table), so
  it falls back to English at runtime. When the app gains a localization, add it
  here with the matching file from AccessoryFiles/LinguisticVariants/ in the
  LOINC release (if LOINC publishes one for it).

Usage:
  build_loinc_resource.py <zip-path-or-dir> <output-db>

The base table is read once into the common-lab term set; each language variant
is then streamed once and merged in, so peak memory stays bounded regardless of
how large the individual variant files are. The script is idempotent; the Xcode
phase only re-runs it when the zip or this script changes.
"""

from __future__ import annotations

import csv
import glob
import io
import os
import sqlite3
import sys
import zipfile

# App language (Localizable.xcstrings locale) -> LOINC linguistic-variant file.
# None -> no LOINC translation available; the app falls back to English.
LANGS: dict[str, str | None] = {
    "en": None,  # base English Loinc.csv
    "de": "deDE15LinguisticVariant.csv",
    "es": "esES12LinguisticVariant.csv",
    "fr": "frFR18LinguisticVariant.csv",
    "it": "itIT16LinguisticVariant.csv",
    "ja": None,  # LOINC 2.82 ships no Japanese variant -> English fallback
    "nl": "nlNL22LinguisticVariant.csv",
    "pl": "plPL29LinguisticVariant.csv",
    "pt-BR": "ptBR11LinguisticVariant.csv",
    "ru": "ruRU20LinguisticVariant.csv",
    "tr": "trTR19LinguisticVariant.csv",
    "uk": "ukUA30LinguisticVariant.csv",
    "zh-Hans": "zhCN5LinguisticVariant.csv",
}


def find_zip(arg: str) -> str:
    if os.path.isdir(arg):
        zips = sorted(glob.glob(os.path.join(arg, "*.zip")), key=os.path.getmtime)
        if not zips:
            sys.exit(f"error: no *.zip found in {arg}")
        return zips[-1]
    if not os.path.isfile(arg):
        sys.exit(f"error: zip not found: {arg}")
    return arg


def _member(zf: zipfile.ZipFile, suffix: str) -> str | None:
    for name in zf.namelist():
        if name.endswith(suffix):
            return name
    return None


def _name_from(row: dict, display_key: str) -> str:
    for key in (display_key, "LONG_COMMON_NAME", "SHORTNAME"):
        val = (row.get(key) or "").strip()
        if val:
            return val
    return ""


def _description_from(row: dict, allow_definition: bool) -> str:
    """Readable description: prose definition if present, else composed parts."""
    if allow_definition:
        prose = (row.get("DefinitionDescription") or "").strip()
        if prose:
            return prose
    component = (row.get("COMPONENT") or "").strip()
    if not component:
        return ""
    system = (row.get("SYSTEM") or "").strip()
    text = component if not system else f"{component} in {system}"
    extras = [v for v in ((row.get("PROPERTY") or "").strip(),
                          (row.get("METHOD_TYP") or "").strip()) if v]
    if extras:
        text += " (" + ", ".join(extras) + ")"
    return text


def read_base(zf: zipfile.ZipFile) -> dict[str, dict]:
    """Common lab terms keyed by LOINC_NUM, seeded with English name/description."""
    member = _member(zf, "LoincTable/Loinc.csv")
    if member is None:
        sys.exit("error: LoincTable/Loinc.csv missing from archive")
    entries: dict[str, dict] = {}
    with zf.open(member) as fh:
        for row in csv.DictReader(io.TextIOWrapper(fh, encoding="utf-8")):
            if row["CLASSTYPE"] != "1" or row["STATUS"] != "ACTIVE":
                continue
            try:
                rank = int(row["COMMON_TEST_RANK"])
            except (KeyError, ValueError):
                rank = 0
            if rank <= 0:
                continue
            num = row["LOINC_NUM"]
            names = {"en": _name_from(row, "DisplayName")}
            descs = {}
            en_desc = _description_from(row, allow_definition=True)
            if en_desc:
                descs["en"] = en_desc
            entries[num] = {
                "c": num,
                "u": (row.get("EXAMPLE_UCUM_UNITS") or "").strip(),
                "r": rank,
                "n": names,
                "d": descs,
                "a": _attributes(row),
            }
    return entries


def _attributes(row: dict) -> dict:
    """Structured LOINC attributes (the six-part name + class/status/names) as
    shown on a loinc.org details page. English, stored once per code."""
    def value(key: str) -> str:
        return (row.get(key) or "").strip()
    return {
        "component": value("COMPONENT"),
        "property": value("PROPERTY"),
        "timing": value("TIME_ASPCT"),
        "system": value("SYSTEM"),
        "scale": value("SCALE_TYP"),
        "method": value("METHOD_TYP"),
        "class": value("CLASS"),
        "status": value("STATUS"),
        "long": value("LONG_COMMON_NAME"),
        "short": value("SHORTNAME"),
    }


def merge_variant(zf: zipfile.ZipFile, lang: str, filename: str, entries: dict[str, dict]) -> int:
    member = _member(zf, f"LinguisticVariants/{filename}")
    if member is None:
        sys.stderr.write(f"warning: linguistic variant {filename} ({lang}) not in archive\n")
        return 0
    hits = 0
    with zf.open(member) as fh:
        for row in csv.DictReader(io.TextIOWrapper(fh, encoding="utf-8")):
            entry = entries.get(row.get("LOINC_NUM", ""))
            if entry is None:
                continue
            desc = _description_from(row, allow_definition=False)
            # Several variants translate the parts but leave the assembled name
            # blank — compose a translated name from those parts in that case.
            name = _name_from(row, "LinguisticVariantDisplayName") or desc
            if name:
                entry["n"][lang] = name
            if desc:
                entry["d"][lang] = desc
            hits += 1
    return hits


SCHEMA = """
PRAGMA page_size = 4096;
CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT);
CREATE TABLE term(
    code TEXT PRIMARY KEY, ucum TEXT, rank INTEGER, english TEXT,
    component TEXT, property TEXT, timing TEXT, system TEXT,
    scale TEXT, method TEXT, loinc_class TEXT, status TEXT,
    long_name TEXT, short_name TEXT
) WITHOUT ROWID;
CREATE TABLE label(rowid INTEGER PRIMARY KEY, code TEXT, lang TEXT, name TEXT, descr TEXT);
CREATE INDEX idx_label ON label(code, lang);
CREATE VIRTUAL TABLE label_fts USING fts5(
    name, content='label', content_rowid='rowid',
    tokenize='unicode61 remove_diacritics 2'
);
"""


def load_license(zf: zipfile.ZipFile) -> str:
    """The Regenstrief LOINC license text (must travel with the data)."""
    for name in zf.namelist():
        base = name.rsplit("/", 1)[-1].lower()
        if "license" in base and base.endswith(".txt"):
            with zf.open(name) as fh:
                return io.TextIOWrapper(fh, encoding="utf-8", errors="replace").read().strip()
    sys.stderr.write("warning: no LOINC license file found in archive\n")
    return ""


def build(zip_path: str, out_path: str) -> None:
    with zipfile.ZipFile(zip_path) as zf:
        entries = read_base(zf)
        for lang, fname in LANGS.items():
            if fname is None:
                continue
            hits = merge_variant(zf, lang, fname, entries)
            print(f"LOINC: {lang} matched {hits} terms")
        license_text = load_license(zf)

    ordered = sorted(entries.values(), key=lambda e: e["r"])
    write_db(ordered, _release_version(zip_path), license_text, out_path)


def write_db(ordered: list[dict], version: str, license_text: str, out_path: str) -> None:
    os.makedirs(os.path.dirname(out_path) or ".", exist_ok=True)
    if os.path.exists(out_path):
        os.remove(out_path)

    con = sqlite3.connect(out_path)
    try:
        con.execute("PRAGMA journal_mode = OFF")
        con.execute("PRAGMA synchronous = OFF")
        con.executescript(SCHEMA)
        con.execute("INSERT INTO meta VALUES('version', ?)", (version,))
        con.execute("INSERT INTO meta VALUES('languages', ?)", (",".join(LANGS.keys()),))
        con.execute("INSERT INTO meta VALUES('license', ?)", (license_text,))

        rowid = 0
        labels = 0
        for entry in ordered:
            names = entry["n"]
            descs = entry["d"]
            attr = entry["a"]
            con.execute("INSERT INTO term VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                        (entry["c"], entry["u"], entry["r"], names.get("en", ""),
                         attr["component"], attr["property"], attr["timing"], attr["system"],
                         attr["scale"], attr["method"], attr["class"], attr["status"],
                         attr["long"], attr["short"]))
            for lang, name in names.items():
                rowid += 1
                labels += 1
                descr = descs.get(lang, "")
                # Drop a description that merely repeats the name.
                if descr == name:
                    descr = ""
                con.execute("INSERT INTO label VALUES(?,?,?,?,?)",
                            (rowid, entry["c"], lang, name, descr))
        # Build the external-content FTS index from the populated label table.
        con.execute("INSERT INTO label_fts(label_fts) VALUES('rebuild')")
        con.commit()
        con.execute("VACUUM")
        con.commit()
    finally:
        con.close()

    size_kb = os.path.getsize(out_path) // 1024
    print(f"LOINC: wrote {len(ordered)} terms / {labels} labels "
          f"[{','.join(LANGS.keys())}] -> {out_path} ({size_kb} KB)")


def _release_version(zip_path: str) -> str:
    base = os.path.basename(zip_path)
    name = base[:-4] if base.lower().endswith(".zip") else base
    return name.replace("Loinc_", "").replace("Loinc", "") or name


def main() -> None:
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    build(find_zip(sys.argv[1]), sys.argv[2])


if __name__ == "__main__":
    main()
