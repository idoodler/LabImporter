# Model quality check

Runs an on-device model over the sample reports in `reports/` — with the app's
exact instructions, prompt, JSON schema and shared post-processing — and scores
the values against each report's `.expected.json`: a value counts when its
number and unit were read (the read test name is replaced by the catalog's in
post-processing), then its resolved LOINC code, numbers made up from text,
results that aren't numbers kept as text (`notNumeric`),
extra numbers, the report date and the patient. `SOURCE CHECK` scores Review's
"compare with report" badge (`LabValue.needsSourceCheck`): wrong values flagged,
wrong values not flagged, right values falsely flagged. A wrong value is one
that matches nothing expected.

The reports are synthetic, written to look like real OCR output in several
formats and languages. Add a report by writing `name.txt` and
`name.expected.json` by hand from what the report shows; give `loinc` only
where the right code is unambiguous.

```
./gradlew :evaluation:run --args="$HOME/.cache/labimporter-eval/gemma-4-E2B-it.litertlm gpu schema"
```

Arguments: model file, `gpu`|`cpu`, `schema` (constrained JSON) | `text`, and
optionally `think`. Model replies land in `evaluation/replies/`, which is
committed: commit them with the run's result below.
`--args="replay"` re-scores those saved replies without the model — quick when
only the shared post-processing (LOINC lookup, dates) changed; add
`-Pverbose=true` to list the LOINC every value resolved to, and
`-Psummary=build/quality/evaluation.json` to also write the totals as JSON.
CI's quality workflow replays them on every pull request and fails below a floor
(`.github/workflows/quality.yml`).

Run it before changing the prompt (`LabParsePrompt`), the LOINC lookup or the
model the app downloads.

## Last result

Gemma 4 E2B (`gemma-4-E2B-it.litertlm`, revision `a790a85`) on an M-series Mac
GPU, constrained JSON, no thinking (2026-10-31), after results that aren't
numbers became savable (#99):

```
TOTAL  correct 76/77 (recall 98%), extra numbers 1, precision 98%, invalid replies 0, LOINC 62/64, text results 13/16, median time 17.7 s
```

The previous prompt scored the same on numbers and LOINC (76/77, 62/64) but
kept 11/16 text results. The one miss is ALT 22 in `de_table` (read as 222,
or with the unit "/l"), which shows up with either prompt; the two LOINC
misses are "GPT"/"ALT (GPT)", which no catalog label names and which stay
unmapped for the user to place. Text results the model leaves out entirely
("Orina: Proteínas … Negativo") or names ambiguously ("Eiweiß", which the
catalog also uses for egg white — now kept unmapped, see below) are the
misses there.

The source check, replaying those replies (2026-11-08), after it also checks
the unit and the test's own line:

```
SOURCE CHECK  wrong values flagged 1, wrong values NOT flagged 1, right values falsely flagged 0/89
```

Before (result printed anywhere only): flagged 0, not flagged 2, false 0/89.
"22" with the unit "/l" is now flagged: "/l" is no whole unit of the report
("U/l"). The one left was "positiv (+)", which the model read right, on its
own line ("Eiweiß positiv (+)"): the LOINC lookup then took "Eiweiß" for egg
white (an allergen mix), so no check of the text against the reply could see
it.

Since an allergen mix is no longer suggested for a name of one of its members
(`LoincRanking.namesOneMemberOfMix`), that "Eiweiß" stays unmapped for the
user to place, and the replay (2026-11-08) reads:

```
TOTAL  correct 76/77 (recall 98%), extra numbers 1, precision 98%, invalid replies 0, LOINC 62/64, text results 14/16, dates 7/8
SOURCE CHECK  wrong values flagged 1, wrong values NOT flagged 0, right values falsely flagged 0/90
```

No wrong value goes unflagged any more; the right values are one more (the
unmapped "Eiweiß"), none falsely flagged. The saved replies hold no misread
digit and no result on another test's row, so this run says nothing about
catching those; `ReportSourceTextTest` covers them (222 for 22, and every
sample report's results moved to another row).
