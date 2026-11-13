# How LabImporter's quality is measured

A quality score only means something if two reviewers reach the same number. So
the score here is built from measures that a script can collect, with a fixed
threshold for each. Judgement comes last, is limited to what a script can't see,
and is always done by several independent reviewers.

The measures come from the first council review: two reviewers from different
model providers each proposed a framework without seeing the other's, and this
is what they agreed on.

Run it with `tools/quality/collect.sh`. It writes `build/quality/` and
`docs/quality/SCORECARD.md`.

## What 9.5 means

9.5 is an engineering and expert-review score: what scripts, tests and independent
reviewers can establish from the code, the evidence and the screenshots. It is not a
usability or accessibility certification. The moderated usability test and the walkthrough
with real assistive-technology users ("What isn't measured" below) are release criteria
of their own, and a 9.5 says nothing about whether they pass.

The app ranks 9.5 only when all of these hold:

1. **Every gate below passes**, on three runs in a row (one flaky run fails it).
   `tools/quality/consistency.sh` runs `collect.sh` three times in a row at one commit and
   writes each run's step exits and measure results to `build/quality/consistency.json`; the
   scorecard reports it ("3 of 3 consecutive runs green at <commit>", or what failed). A
   working tree whose only change is `docs/quality/SCORECARD.md` counts as that commit, so
   the committed scorecard is the run of the code it sits next to.
2. **Every council member scores it 9.5 or higher, in two fresh rounds in a row.**
   A fresh round is a new reviewer with no memory of the last one, given the code
   and the evidence, not the previous scores. Reviewers judge the panel measures
   (Screenshot matrix, Heuristic panel) separately from their overall score: each
   panel measure gets its own verdict against its threshold, so a strong overall
   impression can't stand in for it, nor one weak screen sink the rest.
3. **No open data-loss defect.** Any open one caps the score at 7.0, whatever else
   is true. A health record that silently disappears outweighs everything else. The cap
   is for a defect that loses or corrupts stored data: in a save, an edit, a move, a
   migration or an export. It is not for a detection rate measured against planted errors
   (Extraction safety): that has its own threshold, and missing it fails that measure.

The scorecard also computes a **scripted score** for Code and for Design: the weighted
share of the scripted measures that pass, out of 10. A measure "not run" counts as not
passing; the panel's measures and the reported ones aren't scripted and aren't in it. It
says what the scripts establish on one run, nothing more: the overall 9.5 still needs
(1) to (3), the council's two rounds above all.

Consistency comes from (1) being enforced in CI, so the floor can't erode unnoticed.

## Code quality

| Measure | Weight | How it's collected | 9.5 threshold | In CI |
|---|---:|---|---|---|
| Write-path safety | 25 | Fault injection at each save, replace, delete, move and import step, with the real codec, on both platforms (Android's `ReportSavingFaultTest`, iOS's `ReportWriterFaultTests`) | The old or the new record always survives | Yes |
| Extraction safety | 15 | Planted errors (`evaluation/`, no model): every right reading of the committed replies made wrong — tenfold, a tenth, a digit dropped or doubled, the neighbouring test's value, a dropped "<"/">", another test's unit, another date column's value, a changed date — through the shared post-processing; plus the replay itself, scoring values, codes and dates | At least 95% of planted errors flagged for review, and at least 90% of each kind; at most 5% of right values flagged; every wrong value of the replay flagged; no planted or read date wrong without a flag | Replay yes, model by hand |
| Test effectiveness | 15 | PIT mutation testing of `shared/` (`tools/quality/mutation.sh`); a mutant PIT couldn't run (RUN_ERROR) is inconclusive and never counts as killed | At least 80% of mutants killed in export, parse, model and loinc | Weekly, and on pull requests that change `shared/…/core/export` or `core/parse` (`quality.yml`) |
| Round trips | 10 | Property tests: random reports through CDA, FHIR and the transfer file | No value, unit or date lost | Yes |
| Error visibility | 10 | `static_metrics.py` lists every swallowed error where reports are read, saved, deleted or imported (services, Home, Import, Review, History, Dashboard, Settings, Export, the Siri intents, the share extension; Android's data layer and `AppViewModel`; shared export and transfer) | None without a stated reason (a comment of at least four words saying why; not a lint directive or a `///` doc comment); failed reads never look empty | Yes |
| Privacy guardrails | 10 | Network guardrail tests on both platforms, privacy manifests, no results in logs, export cleanup | Nothing leaves the device; no leftover medical files | Yes |
| Static gates | 5 | SwiftLint strict and analyze, Android lint, compiler warnings, complexity, duplication | No violations; no function over complexity 15 or 100 lines without a reason; duplication under 3% | Yes |
| Build and release safety | 5 | Pinned CI actions and tools, dependency updates, the pull request's CI checks (`gh pr checks`) for the measured commit | All pinned; all green | Yes |

Extraction safety's reports are also the ones the Review check and the LOINC ranking were tuned on.
The held-out set (`evaluation/heldout`, `-Pheldout=true`) is ten synthetic reports in languages and
layouts the tuning set doesn't have (Czech, Danish, Swiss, Finnish, Turkish, Hungarian, Greek, Russian,
Indian English, Japanese; scan noise, cumulative tables, two columns), each written by hand before any
check scored it and never changed after. It is scored the same way and reported beside Extraction
safety, with each part marked where it falls under the 9.5 threshold, but has no weight and no gate: a
held-out score that becomes a gate starts being tuned against.

Extraction safety's blind spot: a value the OCR misread and the model then copied faithfully is printed
in the text the Review check reads, so no check against the source text can see it. The planted errors
model the model's reading mistakes (a reply that differs from the text), not OCR's (a text that differs
from the paper); only the user comparing the values with the original report catches those.

## Design quality

| Measure | Weight | How it's collected | 9.5 threshold | In CI |
|---|---:|---|---|---|
| Accessibility | 20 | `performAccessibilityAudit()` for Apple's documented audit types only (never `.all`, whose undocumented types no one could look into, so none is logged and let go), on every screen of `QualityScreen.all`: a pull request's run audits each screen's first pages (its `auditedPages` at the largest text, the first page at the standard size in light, and in dark where tinted surfaces change), the weekly run every page in each of those conditions; a contrast exemption is measured on the element's ink, the colour most of its pixels that differ clearly from the background are drawn in (not its darkest pixels), so one dark glyph can't carry faint text; a semantics audit on Android; the audit's exemptions, each listed with what it covers, and the rules that grant some, against a baseline that may only go down (`tools/quality/accessibility_exemptions_baseline.json`) | No issues without a stated reason; no exemption the baseline doesn't list, none wider, no more rules | Yes |
| Review safety | 20 | UI tests with planted errors: a value not in the report, a missing date, an unconfirmed code | Each is flagged where the user is looking; values are on screen when Review opens | Yes |
| Clinical readability | 15 | Catalog-wide test on primary labels; contrast check | No LOINC syntax in a primary label; 4.5:1 for text | Yes |
| Screenshot matrix | 15 | Every screen in light, dark, largest text and the longest language | No clipped label or value | Captured in CI, judged by the panel |
| State coverage | 10 | Screen by empty, loading, error and denied | Every state designed and covered by a preview or test | Yes |
| Cross-platform parity | 10 | One fixture through both apps' shared rules | Same axis, time scale and range result | Yes |
| Localization | 5 | String catalog check, 25 languages | No missing or stale key; no file-format jargon in main actions | Yes |
| Heuristic panel | 5 | Independent council reviewers with the screenshots | No severity 3 or 4 finding | No |

## What isn't measured

These matter and can't be done by a script or a model. They are not counted in the
score, and a 9.5 says nothing about them; the first two are release criteria:

- **Moderated usability test:** five people per platform review a report with a
  planted tenfold error. Target: at least 90% catch it, SUS of 85 or more.
- **Real assistive-technology walkthrough:** a VoiceOver and a TalkBack user going
  from import to trend. The automated audits catch missing labels, not whether the
  flow makes sense when you can't see it.
- **Production on-device models:** simulators and emulators can't run Apple
  Intelligence or Gemini Nano. `evaluation/` measures Gemma 4 on the Mac instead.

## Rejected as vanity metrics

Both reviewers rejected these, and they aren't scored:

- **Line coverage as a target.** It shows code ran, not that a test would notice it
  breaking. It's reported for orientation; mutation score is what counts.
- **Test, preview, language or lint-rule counts.** Presence says nothing about
  whether they catch anything.
- **Lines of code and commit counts.** They measure activity.
- **Pixel-diff alone.** It flags harmless rendering changes and misses bad design.
- **A single reviewer's beauty score.** It doesn't repeat.
