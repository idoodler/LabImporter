#!/usr/bin/env python3
"""Writes docs/quality/SCORECARD.md from what tools/quality/collect.sh left in build/quality/.

Every measure of docs/quality/RUBRIC.md gets its measured value, its threshold and
pass or FAIL. A measure whose input is missing — a step that wasn't run, or ran and
wrote nothing — shows as "not run", never as passed. Standard library only.

    python3 tools/quality/scorecard.py [--input build/quality] [--output docs/quality/SCORECARD.md]
    python3 tools/quality/scorecard.py --check-evaluation build/quality/evaluation.json   # CI's floor
    python3 tools/quality/scorecard.py --check-mutation build/quality/mutation.json       # CI, weekly
    python3 tools/quality/scorecard.py --check-audit build/quality/audit.json --all-pages # CI, weekly
    python3 tools/quality/scorecard.py --record-run build/quality/run.json                # collect.sh, at its start
    python3 tools/quality/scorecard.py --finish-run build/quality/run.json                # collect.sh, at its end
    python3 tools/quality/scorecard.py --consistency build/quality-consistency            # consistency.sh, at its end
"""
from __future__ import annotations

import argparse
import datetime
import json
import shutil
import subprocess
import sys
from dataclasses import dataclass, field
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
FLOOR = ROOT / "tools/quality/evaluation_floor.json"
# The run's own output: a working tree whose only change is this file is the commit it measured.
SCORECARD = "docs/quality/SCORECARD.md"

PASS, FAIL, NOT_RUN = "pass", "FAIL", "not run"
# A measure that is shown and never scored: it neither passes nor fails, and has no weight.
REPORTED = "reported"

# Extraction safety at 9.5 (RUBRIC.md): of the errors planted in the right readings, Review flags
# at least this share overall and of each kind, flags at most this share of right values, and lets
# no planted date through.
PLANTED_FLAGGED_MIN, PLANTED_KIND_MIN, FALSE_FLAGS_MAX = 0.95, 0.90, 0.05
DATE_FAULTS = ("DATE_DAY", "DATE_YEAR", "DATE_SWAPPED")
# Test effectiveness at 9.5: the share of PIT mutants killed in each of these packages.
MUTATION_PACKAGES, MUTATION_MIN = ("export", "parse", "model", "loinc"), 80
# A measure is as good as its worst part.
SEVERITY = {PASS: 0, NOT_RUN: 1, FAIL: 2}


@dataclass
class Part:
    status: str
    text: str


@dataclass
class Measure:
    section: str
    name: str
    weight: int
    threshold: str
    parts: list[Part] = field(default_factory=list)
    # What the script can't see: said, but it neither passes nor fails the measure.
    note: str = ""
    panel: bool = False
    reported: bool = False

    @property
    def status(self) -> str:
        if self.panel:
            return "panel"
        if self.reported:
            return REPORTED
        if not self.parts:
            return NOT_RUN
        return max((p.status for p in self.parts), key=SEVERITY.__getitem__)


class Inputs:
    """The JSON files of one collect.sh run; a missing or failed one is None, with the reason kept."""

    def __init__(self, folder: Path):
        self.folder = folder
        self.missing: dict[str, str] = {}

    def get(self, name: str) -> dict | None:
        path = self.folder / f"{name}.json"
        if not path.exists():
            self.missing[name] = "not run"
            return None
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
        except json.JSONDecodeError as error:
            self.missing[name] = f"unreadable ({error})"
            return None
        if isinstance(data, dict) and "error" in data:
            self.missing[name] = data["error"]
            return None
        return data

    def step(self, name: str) -> dict | None:
        path = self.folder / f"step-{name}.json"
        return json.loads(path.read_text()) if path.exists() else None


def pct(value: float | None) -> str:
    return "–" if value is None else f"{value:.1f}%"


def check(ok: bool, text: str) -> Part:
    return Part(PASS if ok else FAIL, text)


def not_run(what: str) -> Part:
    return Part(NOT_RUN, f"{what}: not run")


# --- Test suites -------------------------------------------------------------

def suite(inputs: Inputs, platform: str, name: str) -> Part:
    """One test class (JUnit) or suite (Xcode) by its simple name: ran, and nothing failed."""
    label = f"{name} ({platform})"
    if platform == "iOS":
        data = inputs.get("ios")
        if data is None:
            return not_run(label)
        counts = data.get("suites", {}).get(name)
        if not counts:
            return Part(FAIL, f"{label}: no such suite in the iOS results")
        failed = counts.get("Failed", 0)
        passed = counts.get("Passed", 0) + counts.get("Expected Failure", 0)
        if failed:
            return Part(FAIL, f"{label}: {failed} failed")
        return Part(PASS, f"{label}: {passed} passed") if passed else Part(NOT_RUN, f"{label}: all skipped")
    data = inputs.get("tests")
    results = data.get(platform) if data else None
    if not results or "error" in results:
        return not_run(label)
    matches = [counts for full, counts in results["suites"].items() if full.rsplit(".", 1)[-1] == name]
    if not matches:
        return Part(FAIL, f"{label}: no such test class ran")
    counts = {k: sum(m[k] for m in matches) for k in ("tests", "failures", "errors", "skipped")}
    if counts["failures"] or counts["errors"]:
        return Part(FAIL, f"{label}: {counts['failures'] + counts['errors']} of {counts['tests']} failed")
    ran = counts["tests"] - counts["skipped"]
    return Part(PASS, f"{label}: {ran} passed") if ran else Part(NOT_RUN, f"{label}: all skipped")


# --- The measures --------------------------------------------------------------

def code_quality(inputs: Inputs, pr_checks=None) -> list[Measure]:
    """[pr_checks]: what reads the pull request's CI checks (`gh` by default)."""
    static = inputs.get("static")
    evaluation = inputs.get("evaluation")
    mutation = inputs.get("mutation")
    measures = []

    m = Measure("Code quality", "Write-path safety", 25, "The old or the new record always survives")
    m.parts = [
        suite(inputs, "android", "ReportSavingFaultTest"),
        suite(inputs, "android", "ReportRepositoryTest"),
        suite(inputs, "android", "HealthConnectReportStoreTest"),
        suite(inputs, "shared", "TransferTest"),
        # The iOS fault injection: saving, replacing, deleting and importing against a store that fails on purpose.
        suite(inputs, "iOS", "ReportWriterFaultTests"),
        suite(inputs, "iOS", "SharedCoreTests"),
        suite(inputs, "iOS", "ShareInboxTests"),
    ]
    m.note = ("Fault injection covers Android's save, replace and move, iOS's save, replace, delete and import "
              "(ReportWriterFaultTests, against a Health store that fails on purpose), and both apps' transfer import.")
    measures.append(m)

    m = Measure("Code quality", "Extraction safety", 15,
                f"At least {PLANTED_FLAGGED_MIN:.0%} of planted errors flagged, each kind at least {PLANTED_KIND_MIN:.0%}; "
                f"at most {FALSE_FLAGS_MAX:.0%} of right values flagged; every wrong value of the replay flagged; "
                "no planted or read date wrong without a flag")
    if evaluation is None:
        m.parts = [not_run("evaluation replay")]
    elif "plantedErrors" not in evaluation:
        m.parts = [not_run("planted errors (the evaluation summary has none)")]
    else:
        m.parts = planted_parts(evaluation) + replay_parts(evaluation)
        wrong = evaluation["wrongValuesFlagged"] + evaluation["wrongValuesNotFlagged"]
        m.note = (f"Planted errors: every right reading of the committed replies made wrong (tenfold, a tenth, a digit dropped or doubled, "
                  f"the neighbouring test's value, \"<\"/\">\" dropped, another test's unit, a changed date), through the shared post-processing. "
                  f"Replay of the replies as read: recall {pct(100 * evaluation['recall'])}, precision {pct(100 * evaluation['precision'])}, "
                  f"LOINC {evaluation['loincRight']}/{evaluation['loincExpected']}, text results {evaluation['textKept']}/{evaluation['textExpected']}, "
                  f"wrong values flagged {evaluation['wrongValuesFlagged']}/{wrong}, false flags {evaluation['falseFlags']}/{evaluation['rightValues']}. "
                  "A model run is done by hand (evaluation/README.md).")
    measures.append(m)
    measures.append(held_out_measure(inputs.get("evaluation_heldout")))

    m = Measure("Code quality", "Test effectiveness", 15, f"At least {MUTATION_MIN}% of mutants killed in export, parse, model and loinc")
    if mutation is None:
        m.parts = [not_run("mutation testing (collect.sh --mutation)")]
    else:
        m.parts = mutation_parts(mutation)
        total = mutation["total"]
        m.note = (f"All of shared/ that was mutated: {pct(total['score_pct'])}; inconclusive (PIT's RUN_ERROR, counted as not killed): "
                  f"{total.get('inconclusive', 0)}. In CI weekly, and on pull requests that change export or parse (quality.yml).")
    measures.append(m)

    m = Measure("Code quality", "Round trips", 10, "No value, unit or date lost")
    m.parts = [suite(inputs, "shared", "RoundTripPropertyTest")]
    measures.append(m)

    m = Measure("Code quality", "Error visibility", 10, "None without a stated reason (four words or more); failed reads never look empty")
    if static is None:
        m.parts = [not_run("static metrics")]
    else:
        unexplained = static["swallowed_errors"]["unexplained"]
        m.parts = [check(not unexplained, f"swallowed errors where reports are read, saved, deleted or imported, without a reason: "
                                          f"{len(unexplained)} ({static['swallowed_errors']['explained']} with one)")]
    m.parts += [suite(inputs, "iOS", "FlowTests"), suite(inputs, "android", "ScreensTest"), suite(inputs, "android", "ReportRepositoryTest")]
    measures.append(m)

    m = Measure("Code quality", "Privacy guardrails", 10, "Nothing leaves the device; no leftover medical files")
    m.parts = [suite(inputs, "android", "NetworkGuardrailTest")]
    if static is None:
        m.parts.append(not_run("static metrics"))
    else:
        privacy = static["privacy"]
        m.parts += [
            check(not privacy["ios_network_calls"], f"iOS network APIs: {len(privacy['ios_network_calls'])}"),
            check(not privacy["ios_print_statements"], f"iOS print/NSLog: {len(privacy['ios_print_statements'])}"),
            check(not privacy["privacy_manifest_problems"],
                  "privacy manifests in the sources: " + ("both fine" if not privacy["privacy_manifest_problems"] else "; ".join(privacy["privacy_manifest_problems"]))),
        ]
    bundles = inputs.get("ios_bundles")
    if bundles is None:
        m.parts.append(not_run("privacy manifests in the built .app and .appex"))
    else:
        missing = [name for name, b in bundles.items() if not b.get("privacy_manifest")]
        m.parts.append(check(not missing, "privacy manifests in the built bundles: " + (", ".join(missing) + " missing" if missing else "both present")))
    m.parts.append(suite(inputs, "iOS", "ExportFilesTests"))
    measures.append(m)

    m = Measure("Code quality", "Static gates", 5, "No violations; no function over complexity 15 or 100 lines without a reason; duplication under 3%")
    swiftlint = inputs.get("swiftlint")
    m.parts.append(check(swiftlint["violations"] == 0, f"SwiftLint strict: {swiftlint['violations']} violations") if swiftlint else not_run("SwiftLint"))
    analyze = inputs.get("swiftlint_analyze")
    m.parts.append(check(analyze["violations"] == 0, f"SwiftLint analyze: {analyze['violations']} violations") if analyze else not_run("SwiftLint analyze (collect.sh --ios)"))
    lint = inputs.get("lint")
    m.parts.append(check(lint["errors"] + lint["warnings"] == 0, f"Android lint: {lint['errors']} errors, {lint['warnings']} warnings"
                         + (f" ({', '.join(lint['by_id'])})" if lint["by_id"] else "")) if lint else not_run("Android lint"))
    m.parts.append(kotlin_warnings(inputs))
    if static is None:
        m.parts.append(not_run("complexity and duplication"))
    else:
        c, d = static["complexity"], static["duplication"]
        if "error" in c:
            m.parts.append(Part(NOT_RUN, f"complexity: {c['error']}"))
        else:
            m.parts.append(check(not c["over_limit_without_reason"] and not c["stale_allowlist_entries"],
                                 f"over complexity 15 or 100 lines without a reason: {len(c['over_limit_without_reason'])} "
                                 f"({c['over_limit_allowlisted']} allowlisted with one)"))
        if "error" in d:
            m.parts.append(Part(NOT_RUN, f"duplication: {d['error']}"))
        else:
            m.parts.append(check(d["duplicate_rate_pct"] < 3, f"duplication {d['duplicate_rate_pct']}%"))
    measures.append(m)

    m = Measure("Code quality", "Build and release safety", 5, "All pinned; all green")
    if static is None or "ci" not in static:
        m.parts = [not_run("static metrics")]
    else:
        ci = static["ci"]
        m.parts = [
            check(not ci["unpinned"], f"third-party actions and images pinned to a commit SHA or digest: {ci['pinned_actions']} pinned, {len(ci['unpinned'])} not"),
            check({"github-actions", "gradle"} <= set(ci["dependabot_ecosystems"]),
                  f"dependency updates: {', '.join(ci['dependabot_ecosystems']) or 'none'}"),
        ]
    m.parts.append(pr_checks() if pr_checks else pull_request_checks(*measured_commit(inputs)))
    measures.append(m)
    return measures


def planted_parts(evaluation: dict) -> list[Part]:
    """Extraction safety: the planted errors Review flagged, by kind, and the right values it flagged anyway."""
    planted, flagged = evaluation["plantedErrors"], evaluation["plantedFlagged"]
    by_kind = evaluation.get("plantedByKind", {})
    rate = flagged / planted if planted else 0.0
    weak = [f"{kind.lower().replace('_', ' ')} {k['flagged']}/{k['planted']}" for kind, k in by_kind.items()
            if k["planted"] and k["flagged"] / k["planted"] < PLANTED_KIND_MIN]
    dates = [k for kind, k in by_kind.items() if kind in DATE_FAULTS]
    dates_missed = sum(k["planted"] - k["flagged"] for k in dates)
    controls, false_flags = evaluation["plantedControls"], evaluation["plantedFalseFlags"]
    return [
        check(planted > 0 and rate >= PLANTED_FLAGGED_MIN, f"planted errors flagged {flagged}/{planted} ({rate:.1%})"),
        check(planted > 0 and not weak, "kinds under " + f"{PLANTED_KIND_MIN:.0%}: " + (", ".join(weak) if weak else "none")),
        check(controls > 0 and false_flags / controls <= FALSE_FLAGS_MAX,
              f"right values falsely flagged {false_flags}/{controls}" + (f" ({false_flags / controls:.1%})" if controls else "")),
        check(bool(dates) and dates_missed == 0,
              f"planted dates let through: {dates_missed} of {sum(k['planted'] for k in dates)}"),
    ]


def replay_parts(evaluation: dict) -> list[Part]:
    """Extraction safety: the committed replies as the model read them. A wrong value there is a real one, planted or not."""
    wrong = evaluation["wrongValuesFlagged"] + evaluation["wrongValuesNotFlagged"]
    return [
        check(evaluation["wrongValuesNotFlagged"] == 0,
              f"replay: wrong values not flagged {evaluation['wrongValuesNotFlagged']} of {wrong}"),
        check(evaluation["datesWrong"] == 0,
              f"replay: silent wrong dates {evaluation['datesWrong']} (dates right {evaluation['datesRight']}/{evaluation['datesExpected']})"),
    ]


def held_out_measure(held_out: dict | None) -> Measure:
    """Extraction safety on the held-out set (evaluation/heldout): reported beside the measure, never scored.

    The tuning set's numbers show how well the Review check and the LOINC ranking fit the reports they were
    built against; these show how they do on reports nothing was tuned on. Not a gate (yet): a held-out score
    that becomes one starts being tuned against. Each part says whether it would meet the 9.5 threshold.
    """
    m = Measure("Code quality", "Extraction safety, held-out set", 0,
                "Reported, not scored: never used to change the Review check or the LOINC ranking", reported=True)
    if held_out is None:
        m.parts = [not_run("held-out replay (evaluation/heldout)")]
        return m
    if held_out.get("set") != "heldout" or "plantedErrors" not in held_out:
        m.parts = [Part(NOT_RUN, "held-out replay: the summary is not the held-out set's (`-Pheldout=true`)")]
        return m
    gated = planted_parts(held_out) + replay_parts(held_out)
    m.parts = [Part(REPORTED, p.text + ("" if p.status == PASS else " (under the 9.5 threshold)")) for p in gated]
    wrong = held_out["wrongValuesFlagged"] + held_out["wrongValuesNotFlagged"]
    m.note = (f"{held_out['reports']} reports. Replay: recall {pct(100 * held_out['recall'])}, precision {pct(100 * held_out['precision'])}, "
              f"LOINC {held_out['loincRight']}/{held_out['loincExpected']}, text results {held_out['textKept']}/{held_out['textExpected']}, "
              f"wrong values flagged {held_out['wrongValuesFlagged']}/{wrong}, false flags {held_out['falseFlags']}/{held_out['rightValues']}.")
    return m


def mutation_parts(mutation: dict) -> list[Part]:
    parts = []
    for package in MUTATION_PACKAGES:
        score = mutation["packages"].get(package)
        text = f"{package} {pct(score['score_pct']) if score else 'no mutants'}"
        if score:
            text += f" ({score['killed']}/{score['total']}" + (f", {score['inconclusive']} inconclusive" if score.get("inconclusive") else "") + ")"
        parts.append(check(bool(score) and score["score_pct"] >= MUTATION_MIN, text))
    return parts


def pull_request_checks(commit: str, clean: bool) -> Part:
    """"All green on a clean machine": the CI checks of this branch's pull request, for the measured [commit].

    Without a pull request, without `gh`, or with checks for another commit or uncommitted changes,
    nothing says CI ran on what this scorecard measured: "not run", never a pass.
    """
    label = "CI checks of the pull request"
    if not shutil.which("gh"):
        return Part(NOT_RUN, f"{label}: not run (gh not installed)")
    view = subprocess.run(["gh", "pr", "view", "--json", "number,headRefOid"], cwd=ROOT, capture_output=True, text=True)
    if view.returncode != 0:
        return Part(NOT_RUN, f"{label}: not run (no pull request for this branch)")
    pr = json.loads(view.stdout)
    if pr["headRefOid"] != commit or not clean:
        return Part(NOT_RUN, f"{label}: not run for this code (#{pr['number']} checks {pr['headRefOid'][:7]}; this run is "
                             f"{commit[:7]}{'' if clean else ' with uncommitted changes'})")
    checks = subprocess.run(["gh", "pr", "checks", str(pr["number"]), "--json", "name,bucket"], cwd=ROOT, capture_output=True, text=True)
    if not checks.stdout.strip().startswith("["):
        return Part(NOT_RUN, f"{label}: not run (#{pr['number']} has no checks)")
    return checks_part(label, pr["number"], json.loads(checks.stdout))


def checks_part(label: str, number: int, checks: list[dict]) -> Part:
    """`gh pr checks --json name,bucket`: all passed (or skipped) passes, any failure fails, any still running is "not run"."""
    if not checks:
        return Part(NOT_RUN, f"{label}: not run (#{number} has no checks)")
    buckets = {c["bucket"] for c in checks}
    failed = sorted(c["name"] for c in checks if c["bucket"] in ("fail", "cancel"))
    if failed:
        return Part(FAIL, f"{label}: #{number}, {len(failed)} of {len(checks)} failed ({', '.join(failed)})")
    if buckets - {"pass", "skipping"}:
        return Part(NOT_RUN, f"{label}: #{number} still running")
    return Part(PASS, f"{label}: #{number}, {len(checks)} green")


def kotlin_warnings(inputs: Inputs) -> Part:
    """Warnings are errors in shared/ and androidApp/, so a passing build has none; a failing one says nothing."""
    gradle = inputs.step("gradle-tests")
    if gradle is None:
        return not_run("Kotlin compiler warnings")
    as_errors = all("allWarningsAsErrors.set(true)" in (ROOT / f).read_text() for f in ("shared/build.gradle.kts", "androidApp/build.gradle.kts"))
    counted = inputs.get("kotlin_warnings")
    count = counted["kotlin_warnings"] if counted else 0
    if gradle["exit_code"] != 0:
        return Part(FAIL, "Kotlin compiler warnings: the Gradle build failed (warnings are errors)")
    return check(as_errors and count == 0, f"Kotlin compiler warnings: {count}" + (" (warnings are errors)" if as_errors else " (warnings are NOT errors)"))


def audit_run_part(audit: dict | None) -> Part:
    """The exemptions the iOS audit applied on its run: measured (contrast on a named element, its pixels 4.5:1),
    unmeasured and of those the ones a rule granted (`reports.py audit`, from its log).

    The audit fails a named contrast exemption it couldn't measure; the counts must add up, or the line is wrong.
    """
    label = "iOS audit exemptions at runtime"
    if audit is None:
        return not_run(label)
    tests = audit.get("tests", {})
    if not tests:
        return Part(FAIL, f"{label}: no AUDIT exemptions line in the log")
    applied, measured, unmeasured, rules = (sum(t[k] for t in tests.values()) for k in ("applied", "measured", "unmeasured", "rules"))
    pages = sum(s["pages"] for s in audit.get("screens", {}).values())
    mode = "every page" if audit.get("all_pages") else "the pages each screen audits"
    return check(applied == measured + unmeasured and rules <= unmeasured,
                 f"{label}: {applied} applied, {measured} measured on the run, {unmeasured} unmeasured "
                 f"({rules} by a rule); {pages} pages at the largest text ({mode})")


def design_quality(inputs: Inputs) -> list[Measure]:
    static = inputs.get("static")
    measures = []

    m = Measure("Design quality", "Accessibility", 20, "No issues without a stated reason; a contrast exemption naming its element measured on the run (4.5:1); no unmeasured exemption the baseline doesn't list, none wider, no more rules")
    m.parts = [suite(inputs, "iOS", "AccessibilityAuditUITests"), audit_run_part(inputs.get("audit")),
               suite(inputs, "android", "AccessibilityAuditTest")]
    if static:
        nulls = static["accessibility"]["android_null_descriptions_in_icon_buttons"]
        m.parts.append(check(not nulls, f"Android icon buttons without a spoken name: {len(nulls)}"))
        e = static["accessibility"].get("ios_audit_exemptions")
        if e is None:
            m.parts.append(not_run("iOS audit exemptions"))
        else:
            outside = len(e.get("not_in_baseline", [])) + e.get("unreadable", 0)
            m.parts.append(check(not e["above_baseline"] and not outside,
                                 f"iOS audit exemptions (source, second line): {e['measured']} contrast on a named element, "
                                 f"{e['unmeasured']} unmeasured ({e['without_named_element']} without a named element: "
                                 f"{e['no_element']} for an issue without one, {e['any_element']} for any element) and "
                                 f"{e.get('rules', 0)} rules; baseline {e['baseline']['unmeasured']} unmeasured, "
                                 f"{e['baseline']['without_named_element']} and {e['baseline'].get('rules')}; "
                                 f"{outside} unmeasured not in the baseline (new or widened)"))
        p = static["accessibility"].get("ios_audit_pages")
        if p is None:
            m.parts.append(not_run("iOS audit pages"))
        else:
            m.parts.append(check(not p["problems"] and not p["unreadable"],
                                 f"iOS audit pages: {p['audited_pages']} at the largest text over {p['screens']} screens, "
                                 f"{p['screens_leaving_later_pages']} leaving later pages to the weekly run; "
                                 f"{len(p['problems'])} differing from the baseline"))
    measures.append(m)

    m = Measure("Design quality", "Review safety", 20, "Each planted error is flagged where the user is looking; values on screen when Review opens")
    m.parts = [suite(inputs, "iOS", "EndToEndUITests"), suite(inputs, "iOS", "ReviewCheckTests"), suite(inputs, "android", "ReviewCheckTest")]
    measures.append(m)

    m = Measure("Design quality", "Clinical readability", 15, "No LOINC syntax in a primary label; 4.5:1 for text")
    m.parts = [suite(inputs, "shared", "PlainNameTest"), suite(inputs, "android", "DesignContrastTest")]
    m.note = "The iOS contrast check is part of the accessibility audit."
    measures.append(m)

    m = Measure("Design quality", "Screenshot matrix", 15, "No clipped label or value", panel=True)
    m.note = "Captured by ScreenshotMatrixUITests (iOS) and ScreenMatrixTest (Android, -PqualityScreens); judged by the panel."
    measures.append(m)

    m = Measure("Design quality", "State coverage", 10, "Every state designed and covered by a preview or test")
    if static is None:
        m.parts = [not_run("static metrics")]
    else:
        p = static["previews"]
        m.parts = [
            check(not p["swift_files_without_preview"], f"SwiftUI view files without previews: {len(p['swift_files_without_preview'])} of {p['swift_views']} views"),
            check(not p["android_screens_without_preview"], f"Android screens without previews: {len(p['android_screens_without_preview'])} of {p['android_screens']}"),
        ]
    m.parts += [suite(inputs, "android", "ScreensTest"), suite(inputs, "iOS", "FlowTests")]
    measures.append(m)

    m = Measure("Design quality", "Cross-platform parity", 10, "Same axis, time scale and range result")
    m.parts = [suite(inputs, "shared", "ChartAxisTest"), suite(inputs, "shared", "DashboardTest"), suite(inputs, "iOS", "SharedCoreTests")]
    measures.append(m)

    m = Measure("Design quality", "Localization", 5, "No missing or stale key; no file-format jargon in main actions")
    if static is None:
        m.parts = [not_run("static metrics")]
    else:
        for name, label in (("ios_strings", "iOS"), ("android_strings", "Android")):
            s = static[name]
            android_only = len(s.get("android_only_keys", []))
            shared = f" ({android_only} used by Android only)" if android_only else ""
            m.parts.append(check(not s["missing_translations"] and not s["stale_keys"],
                                 f"{label}: {len(s['missing_translations'])} missing, {len(s['stale_keys'])} stale of {s['keys']} keys{shared}"))
    m.note = "File-format jargon in the main actions is judged by the panel."
    measures.append(m)

    m = Measure("Design quality", "Heuristic panel", 5, "No severity 3 or 4 finding", panel=True)
    m.note = "Independent council reviewers with the screenshots; not a script."
    measures.append(m)
    return measures


# --- Orientation: reported, never scored ------------------------------------------

def orientation(inputs: Inputs) -> list[str]:
    lines = []
    coverage = inputs.get("coverage")
    if coverage:
        for name, label in (("shared", "shared/ (JVM tests)"), ("android", "androidApp/ (debug unit tests)")):
            c = coverage.get(name, {})
            if "line" in c:
                lines.append(f"- Kover, {label}: lines {pct(c['line']['pct'])} ({c['line']['covered']}/{c['line']['total']}), "
                             f"branches {pct(c['branch']['pct'])} ({c['branch']['covered']}/{c['branch']['total']})")
            else:
                lines.append(f"- Kover, {label}: not run")
    else:
        lines.append("- Kover coverage: not run")
    ios = inputs.get("ios_coverage")
    if ios:
        for target, c in ios.items():
            lines.append(f"- Xcode coverage, {target}: lines {pct(c['line_coverage_pct'])} of {c['executable_lines']}")
    else:
        lines.append("- Xcode coverage: not run (collect.sh --ios)")
    tests = inputs.get("tests")
    ios_tests = inputs.get("ios")
    counts = []
    for name in ("shared", "android"):
        t = (tests or {}).get(name)
        if t and "error" not in t:
            counts.append(f"{name} {t['tests'] - t['skipped']} ran, {t['failures'] + t['errors']} failed, {t['skipped']} skipped")
    if ios_tests:
        counts.append(f"iOS {ios_tests['passed']} passed, {ios_tests['failed']} failed, {ios_tests['skipped']} skipped")
    lines.append("- Tests: " + ("; ".join(counts) if counts else "not run"))
    mutation = inputs.get("mutation")
    if mutation:
        packages = ", ".join(f"{p} {pct(s['score_pct'])}" for p, s in mutation["packages"].items())
        lines.append(f"- Mutation score by package: {packages}; {len(mutation['clinical_survivors'])} surviving mutants in "
                     "CdaExport, CdaParser, FhirLabCodec, Ucum, ReportSourceText, LabParsePipeline and NumberFormatting "
                     "(listed in build/quality/mutation.json)")
    static = inputs.get("static")
    if static and "error" not in static["complexity"]:
        c = static["complexity"]
        lines.append(f"- Complexity: {c['functions']} functions, mean {c['ccn_mean']}, 95th percentile {c['ccn_p95']}, max {c['ccn_max']}; "
                     f"length 95th percentile {c['nloc_p95']} lines, max {c['nloc_max']}")
    return lines


# --- Writing it ------------------------------------------------------------------

def git(*args: str, cwd: Path = ROOT) -> str:
    return subprocess.run(["git", *args], cwd=cwd, capture_output=True, text=True).stdout.strip()


def uncommitted(root: Path = ROOT) -> list[str]:
    """The working tree's changes, but for the scorecard itself: committing a run's scorecard leaves the commit
    it measured with that one file changed, and a second run there measures the same code."""
    return git("status", "--porcelain", "--", ".", f":(exclude){SCORECARD}", cwd=root).splitlines()


def record_run(path: Path, finished: bool = False, root: Path = ROOT) -> None:
    """collect.sh, at its start: the commit it measures and whether anything but the scorecard was uncommitted.
    With [finished], at its end: the same again beside them, since the tree can change while it runs."""
    now = {"commit": git("rev-parse", "HEAD", cwd=root), "uncommitted": len(uncommitted(root))}
    if finished:
        start = json.loads(path.read_text()) if path.exists() else {}
        now = {**start, "finished_commit": now["commit"], "finished_uncommitted": now["uncommitted"]}
    path.write_text(json.dumps(now))


def measured_commit(inputs: Inputs) -> tuple[str, bool]:
    """The commit collect.sh measured and whether the tree was clean then (`run.json`): clean at its start and at
    its end, at the same commit; a run that didn't record its end isn't. Without it, the checkout's now."""
    run = inputs.get("run")
    if not run:
        return git("rev-parse", "HEAD"), not uncommitted()
    clean = run["uncommitted"] == 0 and run.get("finished_uncommitted") == 0 and run.get("finished_commit") == run["commit"]
    return run["commit"], clean


def table(measures: list[Measure]) -> list[str]:
    rows = ["| Measure | Weight | Measured | 9.5 threshold | Result |", "|---|---:|---|---|---|"]
    for m in measures:
        measured = "<br>".join(f"{'✗ ' if p.status == FAIL else '– ' if p.status == NOT_RUN else ''}{p.text}" for p in m.parts)
        if m.note:
            measured = f"{measured}<br>*{m.note}*" if measured else f"*{m.note}*"
        result = {PASS: "pass", FAIL: "**FAIL**", NOT_RUN: "not run", "panel": "panel", REPORTED: "reported"}[m.status]
        rows.append(f"| {m.name} | {m.weight} | {measured} | {m.threshold} | {result} |")
    return rows


def score(measures: list[Measure]) -> float:
    """The weighted share of the scripted measures that pass, out of 10. "not run" counts as not passing, and the
    panel's and the reported measures aren't scripted. The 9.5 itself also needs the council (RUBRIC.md)."""
    scripted = [m for m in measures if not m.panel and not m.reported]
    total = sum(m.weight for m in scripted)
    return 10 * sum(m.weight for m in scripted if m.status == PASS) / total if total else 0.0


def summary(measures: list[Measure]) -> str:
    scored = [m for m in measures if not m.panel and not m.reported]
    by = {s: [m for m in scored if m.status == s] for s in (PASS, FAIL, NOT_RUN)}
    weight = lambda ms: sum(m.weight for m in ms)  # noqa: E731
    panel = [m for m in measures if m.panel]
    reported = [m for m in measures if m.reported]
    return (f"{len(by[PASS])} pass (weight {weight(by[PASS])}), {len(by[FAIL])} fail (weight {weight(by[FAIL])}), "
            f"{len(by[NOT_RUN])} not run (weight {weight(by[NOT_RUN])}); "
            f"{len(panel)} judged by the panel (weight {weight(panel)})"
            + (f"; {len(reported)} reported, not scored" if reported else ""))


STEPS = ["static", "swiftlint", "gradle-tests", "android-lint", "evaluation", "evaluation-heldout", "ios-tests",
         "swiftlint-analyze", "mutation"]


def steps(inputs: Inputs) -> list[str]:
    rows = ["| Step | Exit | Time |", "|---|---:|---:|"]
    for name in STEPS:
        s = inputs.step(name)
        rows.append(f"| {name} | {s['exit_code']} | {s['seconds']} s |" if s else f"| {name} | not run | |")
    return rows


def consistency(folder: Path) -> dict:
    """consistency.sh: the runs it kept in [folder]/run-<n> (each run's `scorecard.json` and collect.sh's exit code),
    and whether every one was green: collect.sh and each step exited 0, every scripted measure passed, and all ran
    on the first run's commit with nothing but the scorecard uncommitted."""
    runs, commit = [], None
    for run_folder in sorted(folder.glob("run-*"), key=lambda f: int(f.name.split("-")[1])):
        read = lambda name: (run_folder / name).read_text() if (run_folder / name).exists() else None  # noqa: E731
        card = json.loads(read("scorecard.json") or "{}")
        exit_code = int(read("exit") or 1)
        commit = commit or card.get("commit")
        problems = ([] if exit_code == 0 else [f"collect.sh exit {exit_code}"]) + ([] if card else ["no scorecard"])
        problems += [f"step {name} exit {code}" for name, code in card.get("steps", {}).items() if code not in (0, None)]
        problems += [f"{name} {status}" for name, status in card.get("measures", {}).items() if status not in (PASS, "panel", REPORTED)]
        if card and card["commit"] != commit:
            problems.append(f"at {card['commit'][:7]}, not {commit[:7]}")
        if card and not card["clean"]:
            problems.append("with uncommitted changes")
        runs.append({"run": int(run_folder.name.split("-")[1]), "commit": card.get("commit"), "collect_exit": exit_code,
                     "steps": card.get("steps", {}), "measures": card.get("measures", {}), "green": not problems, "problems": problems})
    return {"commit": commit, "runs": runs, "all_green": bool(runs) and all(r["green"] for r in runs)}


def consistency_line(data: dict | None, commit: str) -> str:
    """The scorecard's line on consistency.sh: how many of its consecutive runs were green at this commit."""
    if data is None or not data["runs"]:
        return "not run (`tools/quality/consistency.sh`)"
    if data["commit"] != commit:
        return f"not run at this commit (the last runs were at {str(data['commit'])[:7]})"
    runs = data["runs"]
    line = f"{sum(r['green'] for r in runs)} of {len(runs)} consecutive runs green at `{commit[:7]}`"
    failed = [f"run {r['run']}: {', '.join(r['problems'])}" for r in runs if not r["green"]]
    return line + ("; " + "; ".join(failed) if failed else "")


def write(folder: Path, output: Path, pr_checks=None) -> None:
    inputs = Inputs(folder)
    code, design = code_quality(inputs, pr_checks), design_quality(inputs)
    full_commit, clean = measured_commit(inputs)
    commit = full_commit[:7] or "unknown"
    dirty = "" if clean else " with uncommitted changes"
    generated = (folder / "step-static.json")
    when = datetime.datetime.fromtimestamp(generated.stat().st_mtime) if generated.exists() else datetime.datetime.now()
    rubric = (ROOT / "docs/quality/RUBRIC.md").read_text(encoding="utf-8")
    unmeasured = rubric.split("## What isn't measured", 1)[1].split("\n## ", 1)[0].strip() if "## What isn't measured" in rubric else ""
    text = [
        "# Quality scorecard",
        "",
        f"Generated by `tools/quality/collect.sh` on {when:%Y-%m-%d %H:%M} at commit `{commit}`{dirty}. "
        "Every measure of [RUBRIC.md](RUBRIC.md) with what was measured; a measure whose input is missing "
        "says \"not run\", never \"pass\". Regenerate it with `tools/quality/collect.sh --ios --mutation`.",
        "",
        "A 9.5 also needs every gate to pass three runs in a row, two fresh council rounds at 9.5 or higher, "
        "and no open data-loss defect (RUBRIC.md, \"What 9.5 means\"). This page is one run; the scripted score "
        "is the weighted share of scripted measures that pass, \"not run\" counted as not passing.",
        "",
        f"**Code quality:** scripted score {score(code):.1f} of 10; {summary(code)}.  ",
        f"**Design quality:** scripted score {score(design):.1f} of 10; {summary(design)}.  ",
        f"**Three runs in a row:** {consistency_line(inputs.get('consistency'), full_commit)}.",
        "",
        "## Code quality",
        "",
        *table(code),
        "",
        "## Design quality",
        "",
        *table(design),
        "",
        "## Reported, not scored",
        "",
        "Coverage shows what ran, not what a test would notice (RUBRIC.md, \"Rejected as vanity metrics\").",
        "",
        *orientation(inputs),
        "",
        "## Not measured",
        "",
        unmeasured,
        "",
        "## This run",
        "",
        *steps(inputs),
        "",
    ]
    if inputs.missing:
        text += ["Inputs that were missing: " + ", ".join(f"`{name}.json` ({why})" for name, why in sorted(inputs.missing.items())) + ".", ""]
    output.write_text("\n".join(text), encoding="utf-8")
    # What consistency.sh keeps of each run.
    (folder / "scorecard.json").write_text(json.dumps({
        "commit": full_commit, "clean": clean, "score": {"code": score(code), "design": score(design)},
        "measures": {m.name: m.status for m in code + design},
        "steps": {name: (inputs.step(name) or {}).get("exit_code") for name in STEPS},
    }, indent=2))


def check_evaluation(path: Path) -> int:
    """CI: the replay's numbers against tools/quality/evaluation_floor.json."""
    summary_data = json.loads(path.read_text())
    floor = {k: v for k, v in json.loads(FLOOR.read_text()).items() if not k.startswith("_")}
    return 1 if evaluation_floor_failures(summary_data, floor) else 0


def evaluation_floor_failures(summary_data: dict, floor: dict) -> int:
    """How many of [floor]'s bounds [summary_data] misses, each printed. `plantedByKind` bounds each kind on its own
    (`planted`, and `notFlagged` = planted - flagged): a kind that reads worse fails even when another makes up the total."""
    checks = [(key, summary_data.get(key), bound) for key, bound in floor.items() if key != "plantedByKind"]
    by_kind = summary_data.get("plantedByKind", {})
    for kind, bounds in floor.get("plantedByKind", {}).items():
        score = by_kind.get(kind)
        values = {"planted": score["planted"], "notFlagged": score["planted"] - score["flagged"]} if score else {}
        checks += [(f"plantedByKind.{kind}.{name}", values.get(name), bound) for name, bound in bounds.items()]
    failures = 0
    for key, value, bound in checks:
        ok = value is not None and ("min" not in bound or value >= bound["min"]) and ("max" not in bound or value <= bound["max"])
        failures += not ok
        print(f"{'ok  ' if ok else 'FAIL'} {key} = {value} ({', '.join(f'{k} {v}' for k, v in bound.items())})")
    return failures


def check_mutation(path: Path) -> int:
    """CI (weekly): every package of the rubric at or above its mutation score."""
    failures = 0
    for part in mutation_parts(json.loads(path.read_text())):
        failures += part.status != PASS
        print(f"{'ok  ' if part.status == PASS else 'FAIL'} {part.text} (min {MUTATION_MIN}%)")
    return 1 if failures else 0


def audit_screens() -> list[str]:
    """Every screen of `QualityScreen.all` (LabImporterUITests/QualityScreens.swift)."""
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    import static_metrics  # noqa: E402 — only for --check-audit

    return list(static_metrics.audited_pages(static_metrics.QUALITY_SCREENS.read_text(encoding="utf-8"))[0])


def check_audit(audit: dict, all_pages: bool, screens: list[str]) -> int:
    """CI (weekly): the audit ran to its end, its counts add up, every screen of [screens] printed its AUDIT screen
    line, and with [all_pages] none stopped at a limit."""
    part = audit_run_part(None if "error" in audit else audit)
    print(f"{'ok  ' if part.status == PASS else 'FAIL'} {part.text}" + (f" ({audit['error']})" if "error" in audit else ""))
    failures = part.status != PASS
    printed = audit.get("screens", {})
    for name, screen in sorted(printed.items()):
        limited = screen["limit"] is not None
        print(f"{'FAIL' if all_pages and limited else 'ok  '} {name}: {screen['pages']} pages" + (f" of {screen['limit']}" if limited else ""))
        failures += all_pages and limited
    missing = [name for name in screens if name not in printed]
    for name in missing:
        print(f"FAIL {name}: no AUDIT screen line in the log (not audited, or stopped before its end)")
    if not screens:
        print("FAIL no screens to check the log against")
    return 1 if failures or missing or not screens else 0


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--input", default=str(ROOT / "build/quality"))
    parser.add_argument("--output", default=str(ROOT / "docs/quality/SCORECARD.md"))
    parser.add_argument("--check-evaluation", metavar="SUMMARY")
    parser.add_argument("--check-mutation", metavar="MUTATION")
    parser.add_argument("--check-audit", metavar="AUDIT")
    parser.add_argument("--all-pages", action="store_true", help="with --check-audit: every screen audited without a page limit")
    parser.add_argument("--record-run", metavar="RUN")
    parser.add_argument("--finish-run", metavar="RUN", help="collect.sh, at its end: adds the tree's state then to RUN")
    parser.add_argument("--consistency", metavar="RUNS_FOLDER", help="prints consistency.json from consistency.sh's runs")
    args = parser.parse_args()
    if args.record_run:
        return record_run(Path(args.record_run))
    if args.finish_run:
        return record_run(Path(args.finish_run), finished=True)
    if args.consistency:
        data = consistency(Path(args.consistency))
        print(json.dumps(data, indent=2))
        sys.exit(0 if data["all_green"] else 1)
    if args.check_audit:
        sys.exit(check_audit(json.loads(Path(args.check_audit).read_text()), args.all_pages, audit_screens()))
    if args.check_evaluation:
        sys.exit(check_evaluation(Path(args.check_evaluation)))
    if args.check_mutation:
        sys.exit(check_mutation(Path(args.check_mutation)))
    write(Path(args.input), Path(args.output))


if __name__ == "__main__":
    main()
