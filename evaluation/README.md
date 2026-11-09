# Model quality check

Runs an on-device model over the sample reports in `reports/` — with the app's
exact instructions, prompt, JSON schema and shared post-processing — and scores
the values against each report's `.expected.json`: a value counts when its
number and unit were read (the read test name is replaced by the catalog's in
post-processing; the unit must be the same after case, spacing, µ/u,
superscripts and the decimal comma, so a cut-off "mg" for "mg/dl" is wrong),
then its resolved LOINC code, numbers made up from text,
results that aren't numbers kept as text (`notNumeric`),
extra numbers, the report date and the patient. `SOURCE CHECK` scores Review's
"compare with report" badge (`LabValue.needsSourceCheck`): wrong values flagged,
wrong values not flagged, right values falsely flagged. A wrong value is one
that matches nothing expected.

`PLANTED ERRORS` asks the source check what the replies can't: the saved replies
hold almost no wrong value. Every right reading is made wrong the ways a reader
goes wrong — tenfold, a tenth, a digit dropped or doubled, the neighbouring
test's value, a dropped "<"/">", another test's unit, and the report date a day
or a year off or with day and month swapped — deterministically, without the
model (`PlantedErrors.kt`), and each through the shared post-processing. A
planted value counts as caught when it comes out flagged, a planted date when it
doesn't come out as planted (corrected, or left empty for Review to show). The
right values, as read and respelled harmlessly (the decimal comma for the point,
"mg/dL" for "mg/dl"), measure false alarms. `./gradlew :evaluation:test` tests
the scoring and the planting.

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
(`.github/workflows/quality.yml`, `tools/quality/evaluation_floor.json`), which also
bounds each kind of planted error on its own.

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

Since units must match exactly (2026-11-09; before, either unit being the start
of the other counted), the same replies read one value fewer: LDL's
"mg/dL (calc)" is not "mg/dL", and the source check doesn't flag it, since the
report prints it:

```
TOTAL  correct 75/77 (recall 97%), extra numbers 2, precision 97%, invalid replies 0, LOINC 61/64, text results 14/16, dates 7/8
SOURCE CHECK  wrong values flagged 1, wrong values NOT flagged 1, right values falsely flagged 0/89
PLANTED ERRORS  flagged 379/464 (81%), right values falsely flagged 0/196
    tenfold 75/75, a tenth 75/75, digit dropped 72/73, digit doubled 75/75, neighbouring test's value 62/72,
    "<"/">" dropped 2/3, another test's unit 1/73, date a day off 7/7, date a year off 7/7, day and month swapped 3/4
```

Misread numbers are caught; what isn't is where the check can't tell from the
text. Another test's unit is caught once in 73: the check asks whether the unit
is printed anywhere in the report, and another test's unit is. A neighbouring
test's value slips through where one line prints several tests (`de_patmed`:
"KREA: 0,80 mg/dl; HARNS: 5,1 mg/dl; …"), since that line also prints the
neighbour's value. ">60" read as 60 passes because the line prints "60", a
first digit dropped from 512 because the line's own name, "Vitamin B12", prints "12", and the
swapped date because "03/10/2026" reads either way. Catching those needs the
unit and the value checked against the test's own part of a line, not the line
or the report as a whole.
