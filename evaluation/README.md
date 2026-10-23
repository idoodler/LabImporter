# Model quality check

Runs an on-device model over the sample reports in `reports/` — with the app's
exact instructions, prompt, JSON schema and shared post-processing — and scores
the values against each report's `.expected.json`: a value counts when its
number and unit were read (the read test name is replaced by the catalog's in
post-processing), then its resolved LOINC code, numbers made up from text,
extra numbers, the report date and the patient.

The reports are synthetic, written to look like real OCR output in several
formats and languages. Add a report by writing `name.txt` and
`name.expected.json` by hand from what the report shows; give `loinc` only
where the right code is unambiguous.

```
./gradlew :evaluation:run --args="$HOME/.cache/labimporter-eval/gemma-4-E2B-it.litertlm gpu schema"
```

Arguments: model file, `gpu`|`cpu`, `schema` (constrained JSON) | `text`, and
optionally `think`. Model replies land in `evaluation/build/replies/`.
`--args="replay"` re-scores those saved replies without the model — quick when
only the shared post-processing (LOINC lookup, dates) changed; add
`-Pverbose=true` to list the LOINC every value resolved to.

Run it before changing the prompt (`LabParsePrompt`), the LOINC lookup or the
model the app downloads.

## Last result

Gemma 4 E2B (`gemma-4-E2B-it.litertlm`, revision `a790a85`) on an M-series Mac
GPU, constrained JSON, no thinking (2026-10-23):

```
TOTAL  correct 72/73 (recall 98%), extra numbers 1, precision 98%, invalid replies 0, LOINC 60/62, median time 16.3 s
```

The one miss is a misread digit (ALT 22 → 222); the two LOINC misses are
"GPT"/"ALT (GPT)", which no catalog label names and which stay unmapped for
the user to place.
