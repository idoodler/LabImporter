# tools

Developer tooling that is **not** shipped in the app.

## build_loinc_resource.py — LOINC catalog generator

Distills the official LOINC release into a compact SQLite database (`loinc.db`)
that the app bundles and `LoincDirectory.swift` queries at runtime.

### How it runs

It is invoked automatically by the **"Generate LOINC resource"** Xcode build
phase on the `LabImporter` target, which:

1. picks the newest `Vendor/LOINC/*.zip`,
2. writes `loinc.db` straight into the built `.app` bundle,
3. skips regeneration when the output is newer than both the zip and this
   script (so incremental builds stay fast).

The generated database is a *build product* — it is not committed to git; only
the source zip under `Vendor/LOINC/` is.

### Why SQLite

The catalog opens instantly (no parse at launch), exact code lookups are
indexed, and search uses an **external-content FTS5** index over the names (so
the text is not duplicated). Schema: `term` (code, ucum, rank, english),
`label` (per-language name + description), `label_fts` (FTS5 over `label.name`),
and a `meta` table holding the LOINC version. Requires SQLite with FTS5, which
ships in macOS's system library (and on iOS at runtime).

### What it produces

* **Scope:** common laboratory terms only — `CLASSTYPE == 1` (Laboratory),
  `STATUS == ACTIVE`, and `COMMON_TEST_RANK > 0` (~18.5k of LOINC's 109k terms).
* **Per term:** LOINC code, example UCUM units, common-test rank, and a
  localized **name** + **description** for every language the app ships.
* **Languages:** driven by the `LANGS` map at the top of the script, which pairs
  each app locale (from `Localizable.xcstrings`) with its LOINC linguistic
  variant file. Languages with no LOINC variant (currently Japanese) fall back
  to English at runtime.

### Adding an app language

Add the locale to `LANGS` with the matching
`AccessoryFiles/LinguisticVariants/<xx><CC><n>LinguisticVariant.csv` filename
from the LOINC release (if Regenstrief publishes one for it), and add its
translations in `Localizable.xcstrings`.

### Run it manually

```sh
python3 tools/build_loinc_resource.py Vendor/LOINC /tmp/loinc.db
```
