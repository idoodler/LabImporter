#!/usr/bin/env python3
"""Fetch LOINC code names from Wikidata and build LabImporter/Resources/loinc.db.

Runs as an Xcode pre-build phase. Uses Wikidata's public SPARQL endpoint
(no auth required, CC0 data) to pull every Wikidata item with a LOINC ID
(property P4338) along with its multilingual labels. Builds a compact
SQLite database with FTS5 search and a translations table.

Wikidata coverage skews to common clinical tests (~1500–2500 LOINC codes
last surveyed). This is intentionally narrower than the full Regenstrief
release: it covers the chemistry, hematology, lipid, liver, and endocrine
panels typical of consumer-facing lab reports without requiring each
developer to register at loinc.org.

Behaviour:
- If LabImporter/Resources/loinc.db is younger than --max-age-days
  (default 30) AND already contains rows, exit 0 without touching it.
- Otherwise query Wikidata, build a new DB, and atomically replace the
  bundle resource. On network failure, keep the existing file.
"""

from __future__ import annotations

import argparse
import datetime
import json
import os
import pathlib
import sqlite3
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

REPO_ROOT = pathlib.Path(__file__).resolve().parent.parent
OUTPUT_DB = REPO_ROOT / "LabImporter" / "Resources" / "loinc.db"

SPARQL_ENDPOINT = "https://query.wikidata.org/sparql"
USER_AGENT = (
    "LabImporter-Build/0.1 "
    "(https://github.com/idoodler-s-Diabetes-Management/LabImporter; me@idoodler.de) "
    "python-urllib"
)

# Languages we pull labels for. Add more as the app gains locales.
LANGUAGES = ("en", "de", "fr", "es", "it", "nl", "pt", "pl", "ja", "zh")

PRIMARY_QUERY = """
SELECT ?loinc ?labelEn WHERE {
  ?item wdt:P4338 ?loinc .
  OPTIONAL { ?item rdfs:label ?labelEn . FILTER(LANG(?labelEn) = "en") }
}
"""

TRANSLATIONS_QUERY_TEMPLATE = """
SELECT ?loinc ?lang ?label WHERE {
  ?item wdt:P4338 ?loinc .
  ?item rdfs:label ?label .
  BIND(LANG(?label) AS ?lang)
  FILTER(?lang IN (%s))
}
"""


def sparql(query: str, timeout: int = 90) -> dict:
    url = SPARQL_ENDPOINT + "?" + urllib.parse.urlencode({"query": query, "format": "json"})
    req = urllib.request.Request(
        url,
        headers={"User-Agent": USER_AGENT, "Accept": "application/sparql-results+json"},
    )
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return json.load(resp)


def fetch_primary() -> dict[str, str]:
    payload = sparql(PRIMARY_QUERY)
    out: dict[str, str] = {}
    for row in payload["results"]["bindings"]:
        loinc = row["loinc"]["value"].strip()
        if not loinc:
            continue
        label = row.get("labelEn", {}).get("value") or loinc
        out[loinc] = label
    return out


def fetch_translations() -> list[tuple[str, str, str]]:
    lang_list = ",".join(f'"{code}"' for code in LANGUAGES if code != "en")
    payload = sparql(TRANSLATIONS_QUERY_TEMPLATE % lang_list)
    out: list[tuple[str, str, str]] = []
    for row in payload["results"]["bindings"]:
        loinc = row["loinc"]["value"].strip()
        lang = row["lang"]["value"].strip()
        label = row.get("label", {}).get("value") or ""
        if loinc and lang and label:
            out.append((loinc, lang, label))
    return out


def build_db(primary: dict[str, str], translations: list[tuple[str, str, str]]) -> pathlib.Path:
    OUTPUT_DB.parent.mkdir(parents=True, exist_ok=True)
    tmp = OUTPUT_DB.with_suffix(".db.tmp")
    if tmp.exists():
        tmp.unlink()

    conn = sqlite3.connect(tmp)
    try:
        conn.executescript(
            """
            CREATE TABLE loinc_codes (
                loinc TEXT PRIMARY KEY,
                long_common_name TEXT NOT NULL,
                shortname TEXT,
                component TEXT,
                property TEXT,
                system TEXT,
                scale_typ TEXT,
                method_typ TEXT,
                class TEXT,
                example_ucum_units TEXT,
                status TEXT
            );
            CREATE TABLE loinc_translations (
                loinc TEXT NOT NULL,
                language_code TEXT NOT NULL,
                long_common_name TEXT,
                shortname TEXT,
                component TEXT,
                PRIMARY KEY (loinc, language_code)
            );
            CREATE TABLE meta (
                key TEXT PRIMARY KEY,
                value TEXT NOT NULL
            );
            CREATE VIRTUAL TABLE loinc_search USING fts5(
                loinc UNINDEXED,
                long_common_name,
                shortname,
                component,
                tokenize='porter unicode61'
            );
            """
        )

        rows = [
            (loinc, name, None, None, None, None, None, None, None, None, "ACTIVE")
            for loinc, name in primary.items()
        ]
        search_rows = [(loinc, name, "", "") for loinc, name in primary.items()]

        conn.executemany(
            "INSERT OR REPLACE INTO loinc_codes "
            "(loinc, long_common_name, shortname, component, property, system, "
            "scale_typ, method_typ, class, example_ucum_units, status) "
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            rows,
        )
        conn.executemany(
            "INSERT INTO loinc_search (loinc, long_common_name, shortname, component) "
            "VALUES (?, ?, ?, ?)",
            search_rows,
        )

        valid = set(primary.keys())
        translation_rows = [
            (loinc, lang, label, None, None)
            for (loinc, lang, label) in translations
            if loinc in valid
        ]
        conn.executemany(
            "INSERT OR REPLACE INTO loinc_translations "
            "(loinc, language_code, long_common_name, shortname, component) "
            "VALUES (?, ?, ?, ?, ?)",
            translation_rows,
        )

        attribution = (
            "LOINC code identifiers © Regenstrief Institute, Inc. — used under the "
            "LOINC License (http://loinc.org/license). Display names sourced from "
            "Wikidata (CC0)."
        )
        conn.executemany(
            "INSERT OR REPLACE INTO meta (key, value) VALUES (?, ?)",
            [
                ("loinc_version", "wikidata-" + datetime.datetime.utcnow().strftime("%Y%m%d")),
                ("built_at", datetime.datetime.utcnow().isoformat(timespec="seconds") + "Z"),
                ("code_count", str(len(primary))),
                ("translation_count", str(len(translation_rows))),
                ("attribution", attribution),
                ("source", "wikidata-sparql"),
            ],
        )
        conn.execute("VACUUM")
        conn.commit()
    finally:
        conn.close()

    os.replace(tmp, OUTPUT_DB)
    return OUTPUT_DB


def existing_is_fresh(max_age_days: int) -> bool:
    if not OUTPUT_DB.exists():
        return False
    try:
        conn = sqlite3.connect(OUTPUT_DB)
        try:
            placeholder = conn.execute(
                "SELECT value FROM meta WHERE key = 'placeholder' LIMIT 1"
            ).fetchone()
            if placeholder and (placeholder[0] or "").lower() == "true":
                return False
            count_row = conn.execute("SELECT COUNT(*) FROM loinc_codes").fetchone()
            if not count_row or count_row[0] == 0:
                return False
        finally:
            conn.close()
    except sqlite3.DatabaseError:
        return False
    age = time.time() - OUTPUT_DB.stat().st_mtime
    return age < max_age_days * 86400


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--max-age-days", type=int, default=30)
    parser.add_argument("--force", action="store_true", help="Re-fetch even if the DB is fresh.")
    args = parser.parse_args()

    if not args.force and existing_is_fresh(args.max_age_days):
        size = OUTPUT_DB.stat().st_size / 1024
        print(f"loinc.db is fresh (< {args.max_age_days} days, {size:.0f} KB) — skipping fetch.")
        return

    try:
        print("Fetching LOINC codes from Wikidata SPARQL…", flush=True)
        primary = fetch_primary()
        print(f"  primary rows: {len(primary)}", flush=True)
        translations = fetch_translations()
        print(f"  translation rows: {len(translations)}", flush=True)
    except (urllib.error.URLError, urllib.error.HTTPError, TimeoutError, OSError) as exc:
        print(f"warning: Wikidata fetch failed ({exc}). Keeping existing loinc.db.",
              file=sys.stderr)
        # Exit 0 so the Xcode build doesn't fail when offline.
        sys.exit(0)

    if not primary:
        print("warning: Wikidata returned no LOINC rows — keeping existing loinc.db.",
              file=sys.stderr)
        sys.exit(0)

    db_path = build_db(primary, translations)
    size_kb = db_path.stat().st_size / 1024
    print(f"Built {db_path} — {len(primary)} codes, "
          f"{len(translations)} translations, {size_kb:.0f} KB.")


if __name__ == "__main__":
    main()
