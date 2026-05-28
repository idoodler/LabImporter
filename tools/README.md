# Tools

## `sync_loinc.py` — bundled LOINC lookup database

LabImporter ships a read-only SQLite database (`LabImporter/Resources/loinc.db`)
that powers the LOINC search and per-code colour customisation in Settings.
The DB is populated automatically at build time from Wikidata's public SPARQL
endpoint — **no Regenstrief account, no manual download, no checked-in binary
data**. A Run Script build phase in the Xcode project invokes
`python3 tools/sync_loinc.py` before the app sources compile.

### What it does

1. Queries `https://query.wikidata.org/sparql` for every item with a LOINC ID
   (property P4338), pulling the English label plus translations in DE, FR,
   ES, IT, NL, PT, PL, JA, ZH.
2. Builds a fresh SQLite file with an FTS5 search index, an
   `loinc_translations` table, and a `meta` table carrying the source
   (`wikidata-sparql`), build timestamp, and attribution.
3. Atomically replaces `LabImporter/Resources/loinc.db`.

### Caching

- If `loinc.db` already exists and is younger than 30 days, the script
  exits without contacting the network. Bypass with `--force`.
- If Wikidata is unreachable (offline build, blocked corporate proxy),
  the script prints a warning and exits 0 so the Xcode build still
  succeeds with whatever DB is already on disk (placeholder or previous
  fetch).

### Coverage

Wikidata's LOINC coverage is intentionally narrower than the full
Regenstrief release (~1.5–2.5K codes vs. ~100K). It captures the lab
tests that show up on a typical clinical chemistry, hematology, lipid,
liver, endocrine, or hormone panel — which is what consumer-facing
reports use. Tests outside that scope simply won't surface in Settings'
search; the user can still customise per-code colours and parsed
reference ranges flow through regardless.

### Manual run

From the repo root:

    python3 tools/sync_loinc.py            # respects 30-day cache
    python3 tools/sync_loinc.py --force    # rebuild now

### Licensing

LOINC identifiers themselves are © Regenstrief Institute, Inc. and used
under the LOINC License (http://loinc.org/license). Display names come
from Wikidata's CC0 corpus. Both attributions are stored in the `meta`
table and surfaced in **Settings → About → License** at runtime.
