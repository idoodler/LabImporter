#!/usr/bin/env python3
"""Tests of the quality scripts themselves: a gate that measures the wrong thing passes for the wrong reason.

    python3 -m unittest discover -s tools/quality -p 'test_*.py'
"""
from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import ios_coverage  # noqa: E402
import reports  # noqa: E402
import scorecard  # noqa: E402
import static_metrics  # noqa: E402


def mutation_xml(statuses: list[str]) -> str:
    rows = "".join(
        f'<mutation status="{s}"><mutatedClass>dev.idoodler.labimporter.core.loinc.LoincDirectory</mutatedClass>'
        f"<mutatedMethod>term</mutatedMethod><lineNumber>{i}</lineNumber><mutator>x.NegateConditionals</mutator>"
        f"<description>negated conditional</description></mutation>"
        for i, s in enumerate(statuses)
    )
    return f"<mutations>{rows}</mutations>"


class MutationTest(unittest.TestCase):
    def test_a_run_error_is_inconclusive_not_killed(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder, "mutations.xml")
            path.write_text(mutation_xml(["KILLED", "TIMED_OUT", "SURVIVED", "RUN_ERROR"]))
            report = reports.mutation([str(path)])
        self.assertEqual(report["packages"]["loinc"], {"killed": 2, "total": 4, "inconclusive": 1, "score_pct": 50.0})
        self.assertEqual(report["total"]["inconclusive"], 1)

    def test_loinc_is_held_to_the_same_threshold(self):
        packages = {p: {"killed": 9, "total": 10, "inconclusive": 0, "score_pct": 90.0} for p in ("export", "parse", "model")}
        packages["loinc"] = {"killed": 7, "total": 10, "inconclusive": 0, "score_pct": 70.0}
        parts = scorecard.mutation_parts({"packages": packages})
        self.assertEqual([p.status for p in parts], [scorecard.PASS] * 3 + [scorecard.FAIL])


class CoverageTest(unittest.TestCase):
    def test_folders_are_relative_to_the_repository_root(self):
        root = Path("/Users/someone/LabImporter/.claude/worktrees/branch")
        self.assertEqual(ios_coverage.folder_of(f"{root}/LabImporterShare/ShareViewController.swift", root), "LabImporterShare")
        self.assertEqual(ios_coverage.folder_of(f"{root}/LabImporter/Views/Home/HomeView.swift", root), "LabImporter/Views")
        self.assertEqual(ios_coverage.folder_of("/elsewhere/Generated.swift", root), "(outside the repository)")


class SwallowedErrorTest(unittest.TestCase):
    def test_a_reason_says_why_in_four_words(self):
        self.assertFalse(static_metrics.stated_reason(" ignore"))
        self.assertFalse(static_metrics.stated_reason(" best effort"))
        self.assertFalse(static_metrics.stated_reason(""))
        self.assertTrue(static_metrics.stated_reason(" the file may already be gone"))

    def test_a_bare_comment_is_no_reason(self):
        lines = ["let x = try? load() //", "// ignore", "let y = try? load()"]
        self.assertFalse(static_metrics.site_reason(lines, 0, 0))
        self.assertFalse(static_metrics.site_reason(lines, 2, 2))
        lines = ["// Only a suggestion: the user", "// types it when Health doesn't answer.", "let y = try? load()"]
        self.assertTrue(static_metrics.site_reason(lines, 2, 2))
        self.assertTrue(static_metrics.site_reason(['let x = try? load() // the row stays as it was'], 0, 0))

    def test_a_lint_directive_is_no_reason(self):
        self.assertFalse(static_metrics.stated_reason(" swiftlint:disable:this force_try"))
        self.assertFalse(static_metrics.stated_reason(" swiftlint:disable:next force_unwrapping identifier_name"))
        self.assertFalse(static_metrics.site_reason(["let x = try! load() // swiftlint:disable:this force_try"], 0, 0))
        self.assertTrue(static_metrics.stated_reason(" swiftlint:disable:this force_try - the bundled file always parses"))

    def test_a_doc_comment_is_no_reason(self):
        lines = ["/// Deletes the report from Health.", "func delete(_ id: UUID) async { try? await store.delete(id) }"]
        self.assertFalse(static_metrics.site_reason(lines, 1, 1))
        self.assertFalse(static_metrics.site_reason(["func delete() { try? store.delete(id) } /// Deletes the report from Health."], 0, 0))
        lines = ["/// Deletes the report from Health.", "func delete(_ id: UUID) async {",
                 "    // Already gone is what the user asked for.", "    try? await store.delete(id)", "}"]
        self.assertTrue(static_metrics.site_reason(lines, 3, 3))

    def test_a_url_in_a_string_is_no_comment(self):
        self.assertIsNone(static_metrics.comment_of('let url = "https://loinc.org/" + code'))

    def test_a_failure_handled_after_a_nested_lambda_is_not_swallowed(self):
        text = "runCatching {\n  save()\n}.onSuccess { outcome ->\n  if (outcome) { done() }\n}.onFailure {\n  show(it)\n}\n"
        self.assertEqual(static_metrics.swallow_sites(text, ".kt"), [])
        self.assertEqual(static_metrics.swallow_sites("val x = runCatching { load() }.getOrNull()\n", ".kt"), [(0, 0)])

    def test_review_history_and_the_android_view_model_are_scanned(self):
        swift, kotlin = static_metrics.SWALLOW_PATHS
        self.assertIn("LabImporter/Views/Review", swift)
        self.assertIn("LabImporter/Views/History", swift)
        self.assertTrue(any(p.endswith("ui/AppViewModel.kt") for p in kotlin))


class ExemptionTest(unittest.TestCase):
    SAMPLE = """
    private static let entries: [Entry] = [
        Entry(condition: "a/dark/standard/1", type: .contrast, element: "Total", reason: measured),
        // Measured when it was flagged.
        Entry(condition: "b/light/ax5", type: .contrast, element: nil, reason: unpointed),
        Entry(
            condition: "c/light/standard/1", type: .elementDetection, element: "", limit: 1,
            reason: "the chart's dates (its first and last points)"
        ),
    ]
"""

    REASON = """
    static func reason(for issue: Issue, element: XCUIElement?, on page: inout Page) -> String? {
        if let element, issue.auditType == .contrast {
            if !page.visibleArea.contains(element.frame) {
                return "partly scrolled under a bar"
            }
            if !element.isEnabled { return "WCAG 1.4.3 excepts a control that's disabled" }
        }
        guard let index = entries.indices.first(where: { index in
            return entries[index].type == issue.auditType
        }) else { return nil }
        let entry = entries[index]
        if element == nil { return nil }
        return "\\(entry.reason) (\\(String(format: "%.1f", 1.0)):1)"
    }
"""

    def test_exemptions_are_counted_with_those_without_a_named_element(self):
        self.assertEqual(static_metrics.audit_exemptions(self.SAMPLE),
                         {"entries": 3, "without_named_element": 2, "no_element": 1, "any_element": 1, "rules": 0})

    def test_every_entry_counts_however_it_is_written(self):
        text = """
    private static let entries: [Entry] = [
        Entry(condition: "a/dark/standard/1", type: .contrast, element: "Total", reason: measured),
        .init(condition: "b/light/ax5", type: .contrast, element: nil, reason: unpointed),
        .init(condition: "c/light/ax5", type: .contrast, element: "", reason: "a reason, with (a comma)"),
    ]
"""
        self.assertEqual(static_metrics.audit_exemptions(text)["entries"], 3)
        self.assertEqual(static_metrics.audit_exemptions(text)["without_named_element"], 2)
        # One the script can't read counts, and is never covered by the baseline.
        self.assertEqual(static_metrics.audit_entries("static let entries: [Entry] = [Entry(condition: screen, type: .contrast, element: nil, reason: x)]"), [None])

    def test_rules_in_reason_count_as_exemptions(self):
        self.assertEqual(static_metrics.exemption_rules(self.REASON), 2)
        added = self.REASON.replace("        guard let", "        if issue.auditType == .hitRegion { return measured }\n        guard let")
        self.assertEqual(static_metrics.exemption_rules(added), 3)
        self.assertEqual(static_metrics.exemption_rules(static_metrics.AUDIT_TESTS.read_text(encoding="utf-8")), 3)

    def test_a_widened_exemption_raises_the_baseline(self):
        narrow = {"condition": "dashboard/dark/standard/1", "type": "contrast", "element": "Cholesterol", "limit": 2}
        base = {"rules": 3, "exemptions": [narrow]}

        def raised(**change):
            return static_metrics.baseline_raised({"rules": 3, "exemptions": [{**narrow, **change}]}, base)

        self.assertEqual(raised(), [])
        self.assertEqual(raised(condition="dashboard/dark/standard/1/2", element="Cholesterol in HDL", limit=1), [])
        self.assertTrue(raised(condition="dashboard"))
        self.assertTrue(raised(condition="dashboard/dark/standard"))
        self.assertTrue(raised(element="Chol"))
        self.assertTrue(raised(element=""))
        self.assertTrue(raised(element=None))
        self.assertTrue(raised(limit=3))
        self.assertTrue(raised(type="hitRegion"))
        self.assertTrue(raised(perElement="review-include-button"))
        unlimited = {k: v for k, v in narrow.items() if k != "limit"}
        self.assertTrue(static_metrics.baseline_raised({"rules": 3, "exemptions": [unlimited]}, base))
        # Each base exemption stands for one: the same one twice lets go twice as much.
        self.assertTrue(static_metrics.baseline_raised({"rules": 3, "exemptions": [narrow, narrow]}, base))
        self.assertTrue(static_metrics.baseline_raised({"rules": 4, "exemptions": [narrow]}, base))

    def test_the_committed_baseline_matches_the_audit(self):
        ratchet = static_metrics.exemption_ratchet()
        self.assertEqual(ratchet["above_baseline"], [])
        self.assertEqual(ratchet["below_baseline"], [])
        self.assertEqual((ratchet["not_in_baseline"], ratchet["not_in_audit"], ratchet["unreadable"]), ([], [], 0))


class ScorecardTest(unittest.TestCase):
    def test_pull_request_checks(self):
        part = scorecard.checks_part("CI", 7, [{"name": "lint", "bucket": "pass"}, {"name": "ui", "bucket": "skipping"}])
        self.assertEqual(part.status, scorecard.PASS)
        part = scorecard.checks_part("CI", 7, [{"name": "lint", "bucket": "pass"}, {"name": "ui", "bucket": "fail"}])
        self.assertEqual(part.status, scorecard.FAIL)
        self.assertEqual(scorecard.checks_part("CI", 7, [{"name": "ui", "bucket": "pending"}]).status, scorecard.NOT_RUN)
        self.assertEqual(scorecard.checks_part("CI", 7, []).status, scorecard.NOT_RUN)

    def test_extraction_safety_scores_the_planted_errors(self):
        summary = {
            "plantedErrors": 100, "plantedFlagged": 96, "plantedControls": 50, "plantedFalseFlags": 1,
            "plantedByKind": {"TENFOLD": {"planted": 50, "flagged": 50}, "WRONG_UNIT": {"planted": 44, "flagged": 40},
                              "DATE_DAY": {"planted": 6, "flagged": 6}},
        }
        self.assertTrue(all(p.status == scorecard.PASS for p in scorecard.planted_parts(summary)))
        summary["plantedByKind"]["WRONG_UNIT"] = {"planted": 44, "flagged": 39}
        self.assertEqual([p.status for p in scorecard.planted_parts(summary)].count(scorecard.FAIL), 1)
        summary["plantedByKind"]["DATE_DAY"] = {"planted": 6, "flagged": 5}
        self.assertEqual([p.status for p in scorecard.planted_parts(summary)].count(scorecard.FAIL), 2)

    EVALUATION = {
        "recall": 1.0, "precision": 1.0, "loincRight": 10, "loincExpected": 10, "textKept": 2, "textExpected": 2,
        "rightValues": 10, "falseFlags": 0, "wrongValuesFlagged": 0, "wrongValuesNotFlagged": 0,
        "datesWrong": 0, "datesRight": 1, "datesExpected": 1,
        "plantedErrors": 100, "plantedFlagged": 100, "plantedNotFlagged": 0, "plantedControls": 50, "plantedFalseFlags": 0,
        "plantedByKind": {"TENFOLD": {"planted": 60, "flagged": 60}, "NEIGHBOUR": {"planted": 34, "flagged": 34},
                          "DATE_DAY": {"planted": 6, "flagged": 6}},
    }

    def extraction_safety(self, evaluation: dict) -> scorecard.Measure:
        with tempfile.TemporaryDirectory() as folder:
            Path(folder, "evaluation.json").write_text(json.dumps(evaluation))
            measures = scorecard.code_quality(scorecard.Inputs(Path(folder)), pr_checks=lambda: scorecard.not_run("CI"))
        return next(m for m in measures if m.name == "Extraction safety")

    def test_an_unflagged_wrong_value_in_the_replay_fails_extraction_safety(self):
        self.assertEqual(self.extraction_safety(self.EVALUATION).status, scorecard.PASS)
        measure = self.extraction_safety({**self.EVALUATION, "wrongValuesFlagged": 1, "wrongValuesNotFlagged": 1})
        self.assertEqual(measure.status, scorecard.FAIL)
        self.assertIn("replay: wrong values not flagged 1 of 2", "\n".join(p.text for p in measure.parts))

    def test_the_evaluation_floor_holds_each_kind(self):
        floor = {"plantedNotFlagged": {"max": 0},
                 "plantedByKind": {"TENFOLD": {"planted": {"min": 60}, "notFlagged": {"max": 0}},
                                   "NEIGHBOUR": {"planted": {"min": 30}, "notFlagged": {"max": 4}}}}
        self.assertEqual(scorecard.evaluation_floor_failures(self.EVALUATION, floor), 0)
        floor["plantedNotFlagged"] = {"max": 15}
        # Tenfold reads worse by 15 and the neighbour's value better: the total is still within its bound, the kind isn't.
        worse = {**self.EVALUATION, "plantedNotFlagged": 15,
                 "plantedByKind": {**self.EVALUATION["plantedByKind"], "TENFOLD": {"planted": 60, "flagged": 45}}}
        self.assertEqual(scorecard.evaluation_floor_failures(worse, floor), 1)
        # A kind no longer planted fails too.
        gone = {**self.EVALUATION, "plantedByKind": {k: v for k, v in self.EVALUATION["plantedByKind"].items() if k != "NEIGHBOUR"}}
        self.assertEqual(scorecard.evaluation_floor_failures(gone, floor), 2)

    def test_the_committed_floor_bounds_every_kind(self):
        floor = json.loads(scorecard.FLOOR.read_text())
        kinds = {"TENFOLD", "TENTH", "DIGIT_DROPPED", "DIGIT_DOUBLED", "NEIGHBOUR", "QUALIFIER_DROPPED", "WRONG_UNIT",
                 "DATE_DAY", "DATE_YEAR", "DATE_SWAPPED"}
        self.assertEqual(set(floor["plantedByKind"]), kinds)
        self.assertTrue(all({"planted", "notFlagged"} <= set(b) for b in floor["plantedByKind"].values()))

    def test_the_write_path_measure_needs_the_ios_fault_tests(self):
        with tempfile.TemporaryDirectory() as folder:
            Path(folder, "ios.json").write_text(json.dumps({"suites": {"SharedCoreTests": {"Passed": 3}}}))
            measure = scorecard.code_quality(scorecard.Inputs(Path(folder)), pr_checks=lambda: scorecard.not_run("CI"))[0]
        self.assertEqual(measure.name, "Write-path safety")
        self.assertEqual(measure.status, scorecard.FAIL)
        self.assertIn("ReportWriterFaultTests (iOS): no such suite", "\n".join(p.text for p in measure.parts))


if __name__ == "__main__":
    unittest.main()
