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

The app ranks 9.5 only when all of these hold:

1. **Every gate below passes**, on three runs in a row (one flaky run fails it).
2. **Every council member scores it 9.5 or higher, in two fresh rounds in a row.**
   A fresh round is a new reviewer with no memory of the last one, given the code
   and the evidence, not the previous scores.
3. **No open data-loss defect.** Any open one caps the score at 7.0, whatever else
   is true. A health record that silently disappears outweighs everything else.

Consistency comes from (1) being enforced in CI, so the floor can't erode unnoticed.

## Code quality

| Measure | Weight | How it's collected | 9.5 threshold | In CI |
|---|---:|---|---|---|
| Write-path safety | 25 | Fault injection at each save, replace, move and import step, with the real codec | The old or the new record always survives | Yes |
| Extraction safety | 15 | `evaluation/` on the sample reports, scoring values, codes and dates | Every wrong value is flagged for review; no silent wrong date | Replay yes, model by hand |
| Test effectiveness | 15 | PIT mutation testing of `shared/` (`tools/quality/mutation.sh`) | At least 80% of mutants killed in export, parse and model | Scheduled |
| Round trips | 10 | Property tests: random reports through CDA, FHIR and the transfer file | No value, unit or date lost | Yes |
| Error visibility | 10 | `static_metrics.py` lists every swallowed error in a storage path | None without a stated reason; failed reads never look empty | Yes |
| Privacy guardrails | 10 | Network guardrail tests on both platforms, privacy manifests, no results in logs, export cleanup | Nothing leaves the device; no leftover medical files | Yes |
| Static gates | 5 | SwiftLint strict and analyze, Android lint, compiler warnings, complexity, duplication | No violations; no function over complexity 15 or 100 lines without a reason; duplication under 3% | Yes |
| Build and release safety | 5 | Pinned CI actions, dependency updates, CI green on a clean machine | All pinned; all green | Yes |

## Design quality

| Measure | Weight | How it's collected | 9.5 threshold | In CI |
|---|---:|---|---|---|
| Accessibility | 20 | `performAccessibilityAudit()` on every screen in light, dark and the largest text; a semantics audit on Android | No issues without a stated reason | Yes |
| Review safety | 20 | UI tests with planted errors: a value not in the report, a missing date, an unconfirmed code | Each is flagged where the user is looking; values are on screen when Review opens | Yes |
| Clinical readability | 15 | Catalog-wide test on primary labels; contrast check | No LOINC syntax in a primary label; 4.5:1 for text | Yes |
| Screenshot matrix | 15 | Every screen in light, dark, largest text and the longest language | No clipped label or value | Captured in CI, judged by the panel |
| State coverage | 10 | Screen by empty, loading, error and denied | Every state designed and covered by a preview or test | Yes |
| Cross-platform parity | 10 | One fixture through both apps' shared rules | Same axis, time scale and range result | Yes |
| Localization | 5 | String catalog check, 25 languages | No missing or stale key; no file-format jargon in main actions | Yes |
| Heuristic panel | 5 | Independent council reviewers with the screenshots | No severity 3 or 4 finding | No |

## What isn't measured

These matter and can't be done by a script or a model. They are not counted in the
score, and a 9.5 says nothing about them:

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
