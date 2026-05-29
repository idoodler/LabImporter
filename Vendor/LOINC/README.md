# LOINC source data

The official LOINC release archive lives here (currently `Loinc_2.82.zip`).
Drop a newer release in beside/over it as `Loinc_<version>.zip`; the build picks
the most recent `*.zip` automatically.

This is the **raw source data**, not the file that ships in the app. The Xcode
"Generate LOINC resource" build phase runs `tools/build_loinc_resource.py`,
which distills `LoincTable/Loinc.csv` plus the German/Spanish/French/… linguistic
variants into a compact, multilingual `loinc_common.json` bundled into the app.
See `tools/README.md` for details. The full uncompressed table (~80 MB+) is far
too large to embed verbatim.

## Required contents in the zip

- `LoincTable/Loinc.csv`
- `AccessoryFiles/LinguisticVariants/*.csv` (translations; one per app language)
- `LoincLicense_*.txt` (keep it — see below)

These come from the standard "LOINC Table File (CSV)" download at
<https://loinc.org/downloads/>.

## License

LOINC is © Regenstrief Institute, Inc. and distributed under the LOINC license.
Keep the license/notice files from the archive intact; the Regenstrief LOINC
License permits redistribution as long as the license travels with the data.
