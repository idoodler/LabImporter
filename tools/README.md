# tools

Developer tooling that is **not** shipped in the app.

## build_loinc_resource.py — LOINC catalog generator

Distills the official LOINC release into the compact `loinc_common.json` that
the app bundles and `LoincDirectory.swift` reads at runtime.

### How it runs

It is invoked automatically by the **"Generate LOINC resource"** Xcode build
phase on the `LabImporter` target, which:

1. picks the newest `Vendor/LOINC/*.zip`,
2. writes `loinc_common.json` straight into the built `.app` bundle,
3. skips regeneration when the output is newer than both the zip and this
   script (so incremental builds stay fast).

The generated JSON is a *build product* — it is not committed to git; only the
source zip under `Vendor/LOINC/` is.

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
python3 tools/build_loinc_resource.py Vendor/LOINC /tmp/loinc_common.json
```
