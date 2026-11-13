# Model quality check

Runs an on-device model over the sample reports in `reports/` — with the app's
exact instructions, prompt, JSON schema and shared post-processing — and scores
the values against each report's `.expected.json`: a value counts when its
number and unit were read (the read test name is replaced by the catalog's in
post-processing; the unit must be the same after case, spacing, µ/u,
superscripts and the decimal comma, so a cut-off "mg" for "mg/dl" is wrong),
then its resolved LOINC code, numbers made up from text,
results that aren't numbers kept as text (`notNumeric`; with its `result`, only
that one: ">1500" for a printed ">150" is wrong), extra numbers, the report date
(a wrong one Review flags to check isn't silently wrong) and the patient. `SOURCE CHECK` scores Review's
"compare with report" badge (`LabValue.needsSourceCheck`): wrong values flagged,
wrong values not flagged, right values falsely flagged. A wrong value is one
that matches nothing expected.

`PLANTED ERRORS` asks the source check what the replies can't: the saved replies
hold almost no wrong value. Every right reading is made wrong the ways a reader
goes wrong — tenfold, a tenth, a digit dropped or doubled, the neighbouring
test's value, a dropped "<"/">", another test's unit, the value of another date column
(a cumulative report's older column or a previous result, from `otherDates` in the expected values), and the report date a day
or a year off or with day and month swapped — deterministically, without the
model (`PlantedErrors.kt`), and each through the shared post-processing. A
planted value counts as caught when it comes out flagged, a planted date when it
doesn't come out as planted unflagged (corrected, left empty, or flagged for the
user to check, all of which Review shows) — by its flag only where the report's
right date comes out unflagged, since a flag on both tells nothing apart. The
right values, as read and respelled harmlessly (the decimal comma for the point,
"mg/dL" for "mg/dl"), and each report's right date measure false alarms
("right dates flagged"). Everything reads on a fixed day (`EVALUATION_DAY`), as a
date after it is no report's: move it on when a report dated later joins.
`./gradlew :evaluation:test` tests the scoring and the planting.

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

## Held-out set

The reports above are also the ones the Review check and the LOINC ranking were
tuned on, so their scores measure fit, not how either does on a report it hasn't
seen. `heldout/` is the other half: ten synthetic reports in languages and layouts
the tuning set doesn't have — Czech with scan noise and katal units, a Danish and a
Swiss cumulative table (newest column, Hb in mmol/L, "1'240"), Finnish in two
columns, Turkish with the unit in the section heading or the label, a Hungarian
urine strip and sediment, a Greek and a Russian serology panel (Cyrillic units),
Indian English with scan noise ("8,450 cells/cumm", lakhs), Japanese. Each was
written by hand from what it shows, before any check scored it, and is never
changed after.

**Never change the Review check, the LOINC ranking or the prompt to read the
held-out set better**, and never move a report between the sets: a held-out
report that something was fixed for is a tuning report. When it shows a failure,
write a new tuning report of that kind and fix it there; the held-out set then says
whether the fix generalises. It is reported beside Extraction safety in the
scorecard, never scored and never gated (`tools/quality/collect.sh`): a held-out
score that becomes a gate starts being tuned against.

```
./gradlew :evaluation:run -Pheldout=true --args="replay" -Psummary=build/quality/evaluation_heldout.json
./gradlew :evaluation:run -Pheldout=true --args="$HOME/.cache/labimporter-eval/gemma-4-E2B-it.litertlm gpu schema"
```

`-Pheldout=true` reads `heldout/reports` and `heldout/replies` in either mode, with
the same planted errors; the JSON summary says `"set": "heldout"`.

Scored once (2026-11-10, at commit `069247f` with this set's uncommitted changes;
no rule in `shared/` changed): replies from Gemma 4 E2B with the settings of the
last result below (GPU, constrained JSON, no thinking), then the replay. The
tuning set's replay at the same commit for comparison:

```
HELD-OUT  correct 78/88 (recall 88%), extra numbers 6, precision 92%, invalid replies 0, LOINC 42/66, text results 14/24, dates 4/10 (flagged 0, right ones 0; wrong unflagged 3)
SOURCE CHECK  wrong values flagged 3, wrong values NOT flagged 7, right values falsely flagged 1/92
PLANTED ERRORS  flagged 451/466 (96%), right values falsely flagged 3/188, right dates flagged 1/10
    tenfold 78/78, a tenth 78/78, digit dropped 77/78, digit doubled 78/78, neighbouring test's value 65/66,
    "<"/">" dropped 2/2, another test's unit 66/76, date a day off 3/4, date a year off 3/4, day and month swapped 1/2

TUNING  correct 159/162 (recall 98%), extra numbers 3, precision 98%, invalid replies 0, LOINC 115/122, text results 49/52, dates 18/22 (flagged 2, right ones 0; wrong unflagged 0)
SOURCE CHECK  wrong values flagged 4, wrong values NOT flagged 0, right values falsely flagged 0/208
PLANTED ERRORS  flagged 999/1007 (99%), right values falsely flagged 0/478, right dates flagged 1/22
    tenfold 159/159, a tenth 159/159, digit dropped 153/154, digit doubled 159/159, neighbouring test's value 126/132,
    "<"/">" dropped 38/38, another test's unit 156/157, date a day off 18/18, date a year off 18/18, day and month swapped 13/13
```

Against the 9.5 thresholds the held-out set would miss: another test's unit (87%;
Turkish units printed in a section heading, the Swiss cumulative table), the planted
dates (3 of 10 let through, all in `hu_vizelet`'s "2026.10.07"), 7 of 10 wrong
values unflagged (a cumulative table's older columns read as values, a reference
range read as a unit, "nyomokban" read as "negatív", a reference appended to a
result; two of them, `el_orologikos`' HBsAg and Toxoplasma IgM, were read right and
resolved to catalog names the expected file doesn't list, "HBV surface Ag" and
"T. gondii IgM IF" — a gap of the set's names, left as written), and 3 silent
wrong dates (a print date for the draw date, `da_kumulativ`, `ja_kensa`,
`ru_analiz`). LOINC drops most (42/66): names in Finnish, Greek, Russian and
Japanese the catalog lookup didn't place. The model also read B12 289 as 2899 and
rubella 64 as 644 (both flagged).

Then the Review check began judging a result by its test's own part of a line, from
its name up to the next test's, as it already judged the unit — so a neighbour's
value on the same line is caught (`de_patmed`'s "KREA: 0,80 mg/dl; HARNS: 5,1
mg/dl"), and a number of the test's own name (the 12 of "Vitamin B12") is no result
of it. Tuned on the tuning set only; the held-out set was then replayed once,
unchanged (2026-11-10):

```
TUNING    PLANTED ERRORS  flagged 1006/1007 (99%), right values falsely flagged 0/478, right dates flagged 1/22
    digit dropped 154/154 (was 153), neighbouring test's value 132/132 (was 126), another test's unit 156/157; the rest as above
HELD-OUT  PLANTED ERRORS  flagged 452/466 (96%), right values falsely flagged 3/188, right dates flagged 1/10
    digit dropped 78/78 (was 77), neighbouring test's value 65/66, another test's unit 66/76; the rest as above
HELD-OUT  SOURCE CHECK  wrong values flagged 3, wrong values NOT flagged 7, right values falsely flagged 1/92 (unchanged)
```

The one planted error the tuning set still lets through is `en_serology`'s ANA flag
"A" put on Ferritin as its unit: post-processing drops a flag read as a unit, and a
value without a unit claims nothing about its unit. The held-out set's neighbour
left through is `de_ch_kumulativ`'s Albumin 29 for 31, a cumulative table's older
column on the test's own row.

A review then found three false alarms the parts brought (none in either set): a
result in words in front of a number ("Toxoplasma IgG  negative  <3.0 IU/mL"), a line
whose results stand before their names ("141 mmol/l Natrium  4,1 mmol/l Kalium") and a
whole number of the name one space before a word ("Vitamin D 25 OH  32 ng/ml"). With
them fixed, both replays gave the numbers above unchanged.

Cumulative reports (2026-11-13). The held-out set showed `da_kumulativ` saved with its
print date for the newest column's and `de_ch_kumulativ`'s older columns read as current
results, unflagged. A new tuning report of that kind, `nl_cumulatief` (its reply the
model's; every other reply re-read identically), and the planted kind "value from another
date column" (`otherDates`; also `en_cumulative_flags`' previous results) came first. A
first rule flagged every value and the date of any report with a line of two dates: it
caught them, but flagged right values in every such report (16/92 on the held-out set)
and fired on ordinary headers ("10/14/2026 0830 Local   10/15/2026   10/16/2026",
"Muster, Anna   12.03.1965   03.11.2026"). It was replaced by a precise one
(`ReportSourceText.resultColumns`, `isFromAnotherColumn`): a report has result columns
where it heads its results with two or more result dates — numeric, ISO, with the month's
name ("03-Nov-26"), without a year ("03.11."), with times ("31.10.26 07:12 Uhr"), or one
date a line before the table; a date more than five years before the latest is a birth
date — and at least half of the rows below that print a result print two or more. A value
is then flagged only where its own row prints more than one result (a reference range,
flags and a unit's numbers aside) and it isn't the one in the report date's column (by
position under the header where the row lines up with it, else by order; the latest
column where the report date heads none); the date only where it isn't the latest
column's. A previous result with its date behind it ("65 Low 92 04/02/2026"), read for the
current one, is flagged on its own (`isAnotherDatesResult`). So that the other header
shapes are measured too, `nl_cumulatief`'s header is also planted rewritten with month
names, without a year, with times and one date a line (`headerVariants`, tuning set only).
The held-out set was then replayed once, unchanged:

```
TUNING    SOURCE CHECK  wrong values flagged 6, NOT flagged 0, right values falsely flagged 0/214
          PLANTED ERRORS  flagged 1102/1103 (99%), right values falsely flagged 0/517, right dates flagged 1/23
    value from another date column 61/61 (17 as printed, 44 under the rewritten headers); the rest as before
HELD-OUT  before  dates 4/10 (flagged 0; wrong unflagged 3); SOURCE CHECK wrong values flagged 3, NOT flagged 7, right values falsely flagged 1/92
          PLANTED ERRORS  flagged 452/466 (96%), right values falsely flagged 3/188, right dates flagged 1/10
HELD-OUT  after   dates 4/10 (flagged 1, right ones 0; wrong unflagged 2); SOURCE CHECK wrong values flagged 5, NOT flagged 5, right values falsely flagged 1/92
          PLANTED ERRORS  flagged 453/466 (97%), right values falsely flagged 3/188, right dates flagged 1/10
```

The two cumulative reports' older-column values are now flagged and their right values
aren't. The model also read `nl_cumulatief`'s Glucose of the two older columns as two more
values (extra numbers 3 -> 5, both flagged). What the rule can't see: a row that prints
one result, the other cells blank, is never flagged by it, even where that result is an
older column's; a row with blank cells whose text doesn't line up with the header (most
PDF and OCR text) is read by order, so its results can shift a column; and a header with
the month first ("Nov 3, 2026") or a date in words only is no header it finds.

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

Since the unit is checked behind the result on its own row (2026-11-09: up to the
next number with a word in front of it, so "KREA: 0,80 mg/dl; GPT: 59 U/l" no
longer lends U/l to Kreatinin; a row with no unit behind its result still leaves
it to the whole report), and a printed note is no part of a unit ("mg/dL (calc)"
is mg/dL, shown and saved; the planting skips such a unit as "another" one):

```
TOTAL  correct 76/77 (recall 98%), extra numbers 1, precision 98%, invalid replies 0, LOINC 62/64, text results 14/16, dates 7/8
SOURCE CHECK  wrong values flagged 1, wrong values NOT flagged 0, right values falsely flagged 0/90
PLANTED ERRORS  flagged 460/470 (97%), right values falsely flagged 0/198
    tenfold 76/76, a tenth 76/76, digit dropped 73/74, digit doubled 76/76, neighbouring test's value 67/73,
    "<"/">" dropped 2/3, another test's unit 73/74, date a day off 7/7, date a year off 7/7, day and month swapped 3/4
```

The unit one left is Ferritin read with "A", the ANA row's flag, which the
post-processing drops as a flag: no unit claims nothing. Neighbouring values gained
too, where the neighbour's unit differs (TSH '88' mU/l). What still slips through is
the value check reading a whole line: a neighbour on the same line with the same
unit (de_patmed), ">60" for 60 (the reference "> OR = 60" prints 60), 12 for 512
("Vitamin B12"), and the swapped date.

Up to the next number with a word in front read too much as the result's part of
the row: a written-out flag ("65 Low 92 mg/dL", a previous result's column), a
reference's "bis 0,5" or the next test's label ("2.10  Free T4 (ng/dL)") counted
as another unit, and none of the sample reports printed such a row. Two joined
(2026-11-09; their replies from the model run above, the other replies unchanged):
`en_cumulative_flags` (a cumulative report's flag and previous-result columns,
two tests a line with each unit in its label) and `de_einheit_vorn` (the unit's
column first). Replayed with the check as it was, they gave 7 of 110 right values
and 17 of 249 planted controls flagged falsely. The unit is now the first unit
behind the result before a word that labels the next test (flags, other numbers
and a reference range may stand between), else the word right in front of it:

```
TOTAL  correct 96/98 (recall 97%), extra numbers 2, precision 97%, invalid replies 0, LOINC 81/84, text results 14/16, dates 9/10
SOURCE CHECK  wrong values flagged 2, wrong values NOT flagged 0, right values falsely flagged 0/110
PLANTED ERRORS  flagged 583/593 (98%), right values falsely flagged 0/249
    tenfold 96/96, a tenth 96/96, digit dropped 92/93, digit doubled 96/96, neighbouring test's value 87/93,
    "<"/">" dropped 2/3, another test's unit 93/94, date a day off 9/9, date a year off 9/9, day and month swapped 3/4
```

The new extra number is the model reading WBC 11.8 as 111.8, which is flagged.
Another test's unit on a unit-first row was missed 6 times in 8 before (the whole
report prints it); the word in front of the result now catches it. What slips
through is the same 10 as before.

Planted "<"/">" dropped (3) and day-and-month swaps (4) were too few to say much, so
twelve reports joined (2026-11-09), each written by hand without the check in mind:
results below or above what an assay measures ("<0,5", "> 90", ">1476", Quest's
"< OR = 0.2"), and report dates with day and month both 12 or under, day-first and
month-first, in English (UK, US, Australian), German, French, Spanish, Italian,
Dutch (dashes), Portuguese and Swedish (ISO). Their replies come from a model run
with the same settings as above; the other replies came back unchanged. The model
read 63 of their 64 values and 35 of their 36 text results right; it read
en_us_wellness' HDL 44 as 444 and Vitamin D ">150" as ">1500" (both flagged),
en_us_wellness' "11/04/2026" day-first (a US report whose birth date, 05/07/1958,
says nothing either), nl_uitslag's birth date as the report's, and en_us_summit's
date as "2026-02-52" (no date). Replayed with the check as it was:

```
TOTAL  correct 159/162 (recall 98%), extra numbers 3, precision 98%, invalid replies 0, LOINC 115/122, text results 49/52, dates 18/22
SOURCE CHECK  wrong values flagged 4, wrong values NOT flagged 0, right values falsely flagged 0/208
PLANTED ERRORS  flagged 989/1007 (98%), right values falsely flagged 0/478
    tenfold 159/159, a tenth 159/159, digit dropped 153/154, digit doubled 159/159, neighbouring test's value 126/132,
    "<"/">" dropped 36/38, another test's unit 156/157, date a day off 18/18, date a year off 18/18, day and month swapped 5/13
```

and two read dates silently wrong. The dropped qualifiers missed are Quest's: the
reference "> OR = 60" and "< OR = 0.2" print the bare number on the result's row. The
swaps missed are every date with slashes or dashes, which the check read both ways.
Now a row's result is the first printing of the number behind the test's name (or the
value the next line starts with), so a plain 60 where that is ">60" is flagged and a
plain 100 beside a reference "<100" is not; and a date's day and month are ordered
by another date of the report that reads one way only (DOB 23/04/1979, 12/19/1970),
else by a language that writes the day first (not English): a swap is then corrected
(flagged too where only the language said so). Where nothing orders them, the date is
kept and flagged for the user to check ("This date may be misread"), as is a date more
than a year before another printed one (a birth date):

```
TOTAL  correct 159/162 (recall 98%), extra numbers 3, precision 98%, invalid replies 0, LOINC 115/122, text results 49/52, dates 18/22 (flagged 2, right ones 0; wrong unflagged 0)
SOURCE CHECK  wrong values flagged 4, wrong values NOT flagged 0, right values falsely flagged 0/208
PLANTED ERRORS  flagged 999/1007 (99%), right values falsely flagged 0/478, right dates flagged 1/22
    tenfold 159/159, a tenth 159/159, digit dropped 153/154, digit doubled 159/159, neighbouring test's value 126/132,
    "<"/">" dropped 38/38, another test's unit 156/157, date a day off 18/18, date a year off 18/18, day and month swapped 13/13
```

The two flagged dates are the model's two wrong ones; no right date the model read
is flagged here. As a control, each report's right date is flagged once: en_us_wellness'
11/04/2026, which nothing in that report orders (`right dates flagged 1/22`). A
reading after the day it's read on is no report date (08/12/2026 on 9 November 2026
is 12 August), a two-digit year after this year is 19xx ("geb. 12.03.58"), a date
ahead (a next appointment) doesn't make the report's date look like a birth date,
and a region in the language tag orders day and month (en-GB, en-AU the day first,
en-US the month). What still slips through is 8 planted values, all in the earlier reports: six neighbouring values on de_patmed's one-line rows, 12 for 512 ("Vitamin B12") and Ferritin's flag "A" as its unit.
