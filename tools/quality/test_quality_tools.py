#!/usr/bin/env python3
"""Tests of the quality scripts themselves: a gate that measures the wrong thing passes for the wrong reason.

    python3 -m unittest discover -s tools/quality -p 'test_*.py'
"""
from __future__ import annotations

import json
import re
import subprocess
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


class FreshGradleResultsTest(unittest.TestCase):
    """collect.sh fails a step whose test or lint result came from the build cache or was up to date."""

    LOG = "\n".join([
        "> Task :shared:compileTestKotlinJvm FROM-CACHE",
        "> Task :shared:jvmTestProcessResources NO-SOURCE",
        "> Task :shared:jvmTest",
        "> Task :shared:koverXmlReportJvm FROM-CACHE",
        "> Task :androidApp:testDebugUnitTest",
        "> Task :androidApp:lintAnalyzeRelease",
        "> Task :evaluation:test",
        "> Task :evaluation:run",
    ])

    def test_tests_that_ran_now_pass(self):
        required = [":shared:jvmTest", ":androidApp:testDebugUnitTest", ":evaluation:test", ":evaluation:run"]
        self.assertEqual(reports.fresh_log(self.LOG, required), {"stale": [], "missing": []})

    def test_a_cached_or_up_to_date_test_is_stale(self):
        for outcome in ("FROM-CACHE", "UP-TO-DATE"):
            for task in (":shared:jvmTest", ":androidApp:testDebugUnitTest", ":evaluation:test", ":androidApp:lintAnalyzeRelease"):
                log = self.LOG.replace(f"> Task {task}\n", f"> Task {task} {outcome}\n")
                # Named or not: any test or lint analysis task counts.
                self.assertEqual(reports.fresh_log(log, [])["stale"], [f"{task} {outcome}"])

    def test_a_required_task_that_never_ran_is_missing(self):
        self.assertEqual(reports.fresh_log(self.LOG, [":androidApp:lintRelease"])["missing"], [":androidApp:lintRelease"])

    def test_the_command_fails_the_step(self):
        with tempfile.TemporaryDirectory() as folder:
            log = Path(folder, "gradle.log")
            log.write_text(self.LOG.replace("> Task :shared:jvmTest\n", "> Task :shared:jvmTest FROM-CACHE\n"))
            script = Path(__file__).resolve().parent / "reports.py"
            run = subprocess.run([sys.executable, str(script), "fresh", str(log), ":shared:jvmTest"], capture_output=True, text=True)
            self.assertEqual(run.returncode, 1)
            run = subprocess.run([sys.executable, str(script), "fresh", str(log), ":androidApp:testDebugUnitTest"], capture_output=True, text=True)
            self.assertEqual(run.returncode, 1)
            log.write_text(self.LOG)
            run = subprocess.run([sys.executable, str(script), "fresh", str(log), ":shared:jvmTest"], capture_output=True, text=True)
            self.assertEqual(run.returncode, 0)

    def test_collect_reruns_every_test_task(self):
        collect = (Path(__file__).resolve().parent / "collect.sh").read_text()
        for task in (":shared:jvmTest", ":androidApp:testDebugUnitTest", ":evaluation:test", ":androidApp:lintAnalyzeRelease"):
            self.assertIn(f"{task} --rerun", collect)
        self.assertEqual(collect.count("reports.py fresh"), 4)


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
        # Siri, the dashboard, Settings and the PDF export read reports too.
        for path in ["LabImporter/Intents", "LabImporter/Views/Dashboard", "LabImporter/Views/Settings", "LabImporter/Views/Export"]:
            self.assertIn(path, swift)
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
                         {"entries": 3, "measured": 1, "unmeasured": 2, "without_named_element": 2, "no_element": 1,
                          "any_element": 1, "rules": 0})

    def test_every_entry_counts_however_it_is_written(self):
        text = """
    private static let entries: [Entry] = [
        Entry(condition: "a/dark/standard/1", type: .contrast, element: "Total", reason: measured),
        .init(condition: "b/light/ax5", type: .contrast, element: nil, reason: unpointed),
        .init(condition: "c/light/ax5", type: .hitRegion, element: "", reason: "a reason, with (a comma)"),
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

    def test_only_contrast_on_a_named_element_is_measured(self):
        def measured(kind: str, element: str | None) -> bool:
            return static_metrics.is_measured({"condition": "a/light/ax5", "type": kind, "element": element})

        self.assertTrue(measured("contrast", "On-Device AI"))
        # Any element: checked against whichever element the audit flags, so it says nothing about a given
        # text. It's unmeasured and listed in the baseline.
        self.assertFalse(measured("contrast", ""))
        self.assertFalse(measured("contrast", None))
        self.assertFalse(measured("hitRegion", ""))
        self.assertFalse(measured("hitRegion", "On-Device AI"))
        self.assertFalse(measured("textClipped", None))
        self.assertFalse(measured("elementDetection", "Total"))

    def test_a_measured_exemption_may_be_added_or_changed(self):
        # The case that once failed: a verified false positive, measured 16.6:1, that needed a new entry.
        old = {"condition": "onboarding-welcome/light/ax5/2", "type": "contrast", "element": "Welcome to"}
        new = {**old, "element": "On-Device AI"}
        unmeasured = {"condition": "settings/light/ax5", "type": "contrast", "element": None}
        base = {"rules": 3, "exemptions": [old, unmeasured]}
        self.assertEqual(static_metrics.baseline_raised({"rules": 3, "unmeasured": [unmeasured]}, base), [])
        # Against a base in either format, and in the old format too (a list of every exemption).
        self.assertEqual(static_metrics.baseline_raised({"rules": 3, "exemptions": [new, {**new, "condition": "dashboard"}, unmeasured]}, base), [])
        self.assertEqual(static_metrics.baseline_raised({"rules": 3, "unmeasured": [unmeasured]}, {"rules": 3, "unmeasured": [unmeasured]}), [])
        # The audit's source with a new measured entry still matches a baseline that lists only the unmeasured ones.
        audit = """
    private static let entries: [Entry] = [
        Entry(condition: "settings/light/ax5", type: .contrast, element: nil, reason: unpointed),
        Entry(condition: "onboarding-welcome/light/ax5/2", type: .contrast, element: "On-Device AI", reason: measured),
    ]
"""
        compared = static_metrics.compare_with_baseline(audit, {"rules": 0, "unmeasured": [unmeasured]})
        self.assertEqual((compared["not_in_baseline"], compared["not_in_audit"], compared["above_baseline"]), ([], [], []))
        self.assertEqual((compared["measured"], compared["unmeasured"]), (1, 1))

    def test_an_unmeasured_exemption_may_not_be_added(self):
        unmeasured = {"condition": "settings/light/ax5", "type": "contrast", "element": None}
        base = {"rules": 3, "unmeasured": [unmeasured]}
        for added in ({"condition": "dashboard/light/ax5", "type": "contrast", "element": None},
                      {"condition": "dashboard/light/ax5", "type": "contrast", "element": ""},
                      {"condition": "dashboard/light/ax5", "type": "hitRegion", "element": ""},
                      {"condition": "dashboard/light/ax5", "type": "hitRegion", "element": "Glucose"},
                      {"condition": "dashboard/light/ax5", "type": "textClipped", "element": None, "limit": 1},
                      {"condition": "trends/light/standard/1", "type": "elementDetection", "element": None, "limit": 1}):
            self.assertTrue(static_metrics.baseline_raised({"rules": 3, "unmeasured": [unmeasured, added]}, base), added)
            # Listed as measured in the old format, it's still found.
            self.assertTrue(static_metrics.baseline_raised({"rules": 3, "exemptions": [unmeasured, added]}, base), added)
        audit = """
    private static let entries: [Entry] = [
        Entry(condition: "settings/light/ax5", type: .contrast, element: nil, reason: unpointed),
        Entry(condition: "dashboard/light/ax5", type: .hitRegion, element: "Glucose", reason: "small"),
    ]
"""
        compared = static_metrics.compare_with_baseline(audit, {"rules": 0, "unmeasured": [unmeasured]})
        self.assertEqual(len(compared["not_in_baseline"]), 1)
        self.assertEqual(compared["above_baseline"], ["unmeasured"])
        # A contrast entry for any element ("") is no measured one: added, it's outside the baseline.
        anywhere = audit.replace('type: .hitRegion, element: "Glucose", reason: "small"', 'type: .contrast, element: "", reason: measured')
        compared = static_metrics.compare_with_baseline(anywhere, {"rules": 0, "unmeasured": [unmeasured]})
        self.assertEqual(len(compared["not_in_baseline"]), 1)
        self.assertIn("unmeasured", compared["above_baseline"])

    def test_a_widened_exemption_raises_the_baseline(self):
        narrow = {"condition": "dashboard/dark/standard/1", "type": "textClipped", "element": "Cholesterol", "limit": 2}
        base = {"rules": 3, "unmeasured": [narrow]}

        def raised(**change):
            return static_metrics.baseline_raised({"rules": 3, "unmeasured": [{**narrow, **change}]}, base)

        self.assertEqual(raised(), [])
        self.assertEqual(raised(condition="dashboard/dark/standard/1/2", limit=1), [])
        self.assertTrue(raised(condition="dashboard"))
        self.assertTrue(raised(condition="dashboard/dark/standard"))
        self.assertTrue(raised(element="Chol"))
        self.assertTrue(raised(element=""))
        self.assertTrue(raised(element=None))
        self.assertTrue(raised(limit=3))
        self.assertTrue(raised(type="hitRegion"))
        self.assertTrue(raised(perElement="review-include-button"))
        unlimited = {k: v for k, v in narrow.items() if k != "limit"}
        self.assertTrue(static_metrics.baseline_raised({"rules": 3, "unmeasured": [unlimited]}, base))
        # Each base exemption stands for one: the same one twice lets go twice as much.
        self.assertTrue(static_metrics.baseline_raised({"rules": 3, "unmeasured": [narrow, narrow]}, base))
        self.assertTrue(static_metrics.baseline_raised({"rules": 4, "unmeasured": [narrow]}, base))

    def test_an_element_is_matched_by_its_full_label(self):
        # The audit matches the whole label: a name is no prefix of the labels it starts.
        named = {"condition": "report-detail/light/ax5/2", "type": "textClipped", "element": "Cholesterol"}
        base = {"rules": 3, "unmeasured": [named]}
        for longer in ("Cholesterol in HDL", "Cholesterol in LDL (calculated)", "Cholesterol "):
            self.assertTrue(static_metrics.baseline_raised({"rules": 3, "unmeasured": [{**named, "element": longer}]}, base), longer)
        self.assertFalse(static_metrics.element_covers("C", "Cholesterol"))
        self.assertFalse(static_metrics.element_covers("1", "13457-7"))
        self.assertTrue(static_metrics.element_covers("1", "1"))
        self.assertTrue(static_metrics.element_covers("", "Cholesterol"))
        self.assertFalse(static_metrics.element_covers("", None))
        # One for an issue without an element gives way to one exact label, and that lets go only that label.
        self.assertTrue(static_metrics.element_covers(None, "C"))
        self.assertFalse(static_metrics.element_covers(None, ""))
        unnamed = {**named, "element": None}
        self.assertEqual(static_metrics.baseline_raised({"rules": 3, "unmeasured": [{**unnamed, "element": "C"}]}, {"rules": 3, "unmeasured": [unnamed]}), [])
        self.assertTrue(static_metrics.baseline_raised({"rules": 3, "unmeasured": [{**unnamed, "element": "Cholesterol"}]},
                                                       {"rules": 3, "unmeasured": [{**unnamed, "element": "C"}]}))
        # And the audit itself: an entry's element is compared whole, never with hasPrefix.
        source = static_metrics.AUDIT_TESTS.read_text(encoding="utf-8")
        matches = re.search(r"static func matches\([^)]*\)[^{]*\{(.*?)\n    \}", source, re.S)
        self.assertIsNotNone(matches, "AuditExemption.matches is gone")
        self.assertNotIn("hasPrefix", matches.group(1))
        self.assertIn("name == entryElement", matches.group(1))
        self.assertIn("matches(entry.element, name)", source)

    def test_every_named_contrast_entry_is_measured_in_the_audit(self):
        # `reason` measures every contrast entry with an element before it lets an issue go: no path skips it.
        source = static_metrics.AUDIT_TESTS.read_text(encoding="utf-8")
        body = source[source.index("static func reason("):source.index("static func matches(")]
        self.assertIn("if entry.type == .contrast, entry.element != nil {", body)
        measured = body[body.index("if entry.type == .contrast, entry.element != nil {"):]
        self.assertLess(measured.index("PixelContrast.of(element)"), measured.index("page.used[index, default: 0] += 1"))
        self.assertIn("ratio >= minimumContrast else { return nil }", measured)

    def test_every_measured_exemption_needs_4_5_to_1_whatever_its_size(self):
        # A frame's height isn't its type's size: a two-line paragraph is 24 pt tall too. No 3:1 bar anywhere.
        source = static_metrics.AUDIT_TESTS.read_text(encoding="utf-8")
        measurements = static_metrics.AUDIT_TESTS.with_name("AuditMeasurements.swift").read_text(encoding="utf-8")
        self.assertIn("static let minimumContrast = TextContrast.minimum", source)
        self.assertIn("static let minimum = 4.5", measurements)
        for text in (source, measurements):
            self.assertNotIn("requiredContrast", text)
            self.assertNotRegex(text, r"\b3\.0\b|height\s*>=\s*24")
        # Each text on a page whose issue without an element is let go, and that page's exemption.
        self.assertIn("if ratio < minimum {", measurements)
        self.assertIn("guard let lowest = page.texts?.lowest, lowest >= minimumContrast else { return nil }", source)

    def test_the_audit_itself_fails_a_contrast_exemption_it_did_not_measure(self):
        # The runtime check sits in the caller, not in `reason`: an early return there can't switch it off.
        source = static_metrics.AUDIT_TESTS.read_text(encoding="utf-8")
        count = source[source.index("private func count(_ granted: AuditExemption.Granted?"):source.index("/// The audit gives up on a long screen")]
        self.assertIn("if granted.namesContrastElement {", count)
        self.assertIn("guard let ratio = granted.ratio, ratio >= AuditExemption.minimumContrast else {", count)
        self.assertIn("XCTFail(", count)
        self.assertIn("count(AuditExemption.reason(for: issue, element: element, on: &page)", source)

    def test_only_a_named_element_counts_as_measured_at_runtime(self):
        # "" (any element) and nil are let go on the flagged element or the page's texts: unmeasured.
        source = static_metrics.AUDIT_TESTS.read_text(encoding="utf-8")
        self.assertIn("namesContrastElement: entry.element?.isEmpty == false", source)
        count = source[source.index("private func count(_ granted: AuditExemption.Granted?"):source.index("/// The audit gives up on a long screen")]
        measured = count.index("tally.measured += 1")
        self.assertLess(count.index("if granted.namesContrastElement {"), measured)
        self.assertLess(measured, count.index("} else {"))

    def test_every_rule_application_counts_as_unmeasured(self):
        # A rule of `reason` lets an issue go with no entry and no measure: the tally counts it with the unmeasured.
        source = static_metrics.AUDIT_TESTS.read_text(encoding="utf-8")
        count = source[source.index("private func count(_ granted: AuditExemption.Granted?"):source.index("/// The audit gives up on a long screen")]
        unmeasured = count[count.index("} else {"):]
        self.assertLess(unmeasured.index("tally.unmeasured += 1"), unmeasured.index("if granted.isRule { tally.rules += 1 }"))
        self.assertIn("Granted(reason: reason, isRule: true)", source)
        self.assertNotIn("unmeasuredContrast", source)
        # Its one line a test, in the format reports.py reads.
        line = re.search(r'print\("(AUDIT exemptions [^"]*)"\)', source)
        self.assertIsNotNone(line, "the AUDIT exemptions line is gone")
        printed = re.sub(r"\\\([^)]*\)", "7", line.group(1)).replace("7 applied", "testEveryScreen applied", 1)
        self.assertRegex(printed, reports.AUDIT_TALLY)
        self.assertIn("rules 7", printed)
        pages = re.search(r'print\("(AUDIT screen .*)"\)$', source, re.M)
        self.assertIsNotNone(pages, "the AUDIT screen line is gone")
        self.assertIn("pages \\(last) of \\(Self.allPages ? \"all\" : String(screen.auditedPages))", pages.group(1))


class AuditRunTest(unittest.TestCase):
    SOURCE = static_metrics.AUDIT_TESTS.read_text(encoding="utf-8")

    def run_audit(self) -> str:
        return self.SOURCE[self.SOURCE.index("private func runAudit("):self.SOURCE.index("/// Audit findings that are false positives")]

    def test_a_contrast_check_that_gives_up_fails_the_test(self):
        # Nothing stands in for Apple's check: no page is judged by measuring its texts instead.
        body = self.run_audit()
        self.assertNotIn("TextContrast", body)
        self.assertNotIn("types == .contrast", body)
        last = body[body.index("catch let error as NSError where error.code == -56 {"):]
        self.assertLess(last.index("XCTFail("), last.index("return nil"))
        # Every type that gives up fails: none is logged and let go.
        self.assertEqual(body.count("catch let error as NSError where error.code == -56"), 3)
        self.assertNotIn("undocumented", body)

    def test_only_the_documented_types_are_audited(self):
        # `.all` also sets bits Apple names no check for: what they find, or a time-out, no one could look into.
        self.assertNotIn("XCUIAccessibilityAuditType.all", self.SOURCE)
        self.assertNotIn("for: .all", self.SOURCE)
        types = re.search(r"private static let auditTypes: XCUIAccessibilityAuditType = \[(.*?)\]", self.SOURCE, re.S)
        self.assertIsNotNone(types)
        self.assertEqual({t.strip() for t in types.group(1).split(",") if t.strip()},
                         {".contrast", ".elementDetection", ".hitRegion", ".sufficientElementDescription", ".textClipped", ".trait"})

    def test_every_page_mode_audits_every_condition_to_its_end(self):
        test = self.SOURCE[self.SOURCE.index("func testEveryScreen()"):self.SOURCE.index("private func printTally()")]
        # The standard size in light and dark: no page limit in the run of every page.
        self.assertEqual(test.count("limit: Self.allPages ? Int.max : 1"), 2)
        self.assertIn("limit: Self.allPages ? Int.max : screen.auditedPages", test)
        self.assertNotIn('label: "\\(screen.name)/light/standard/1"', test)
        self.assertNotIn('label: "\\(screen.name)/dark/standard/1"', test)
        # Each condition's pages go through one function, which pages with no stated rest in that run.
        self.assertIn("rest: Self.allPages ? nil : rest", test)
        # And clipped text from every page, not from where a pull request's run starts it.
        self.assertIn("for start in 1...last {", test)
        self.assertNotIn("screen.auditedPages, rest: screen.laterPages) { _ in }", test)


class AuditLogTest(unittest.TestCase):
    LOG = """Test Case '-[LabImporterUITests.AccessibilityAuditUITests testEveryScreen]' started.
AUDIT page 1 f82a49ec038200bc
AUDIT exempt (its pixels measure over 4.5:1 (16.6:1)) dashboard/light/ax5/1 [1] Contrast failed — 24 @ (0, 0, 1, 1)
AUDIT screen dashboard pages 2 of 2
AUDIT screen settings pages 14 of 14
AUDIT exemptions testEveryScreen applied 134 measured 120 unmeasured 14 rules 5
"""
    SCREENS = ["dashboard", "settings"]

    def test_the_counts_come_from_the_run(self):
        audit = reports.audit_log(self.LOG)
        self.assertEqual(audit["tests"], {"testEveryScreen": {"applied": 134, "measured": 120, "unmeasured": 14, "rules": 5}})
        self.assertEqual(audit["screens"]["settings"], {"pages": 14, "limit": 14})
        self.assertFalse(audit["all_pages"])
        part = scorecard.audit_run_part(audit)
        self.assertEqual(part.status, scorecard.PASS)
        # The scorecard says how many went unmeasured, and how many of those by a rule.
        self.assertIn("14 unmeasured (5 by a rule)", part.text)

    def test_counts_that_do_not_add_up_fail_the_scorecard(self):
        for wrong in ("unmeasured 13 rules 5", "unmeasured 14 rules 15"):
            audit = reports.audit_log(self.LOG.replace("unmeasured 14 rules 5", wrong))
            self.assertEqual(scorecard.audit_run_part(audit).status, scorecard.FAIL, wrong)
        # The old line, without the rules, isn't read as a pass.
        self.assertIn("error", reports.audit_log(self.LOG.replace("unmeasured 14 rules 5", "unmeasured-contrast 0")))

    def test_a_log_without_the_counts_is_not_a_pass(self):
        audit = reports.audit_log(self.LOG.replace("AUDIT exemptions", "AUDIT something"))
        self.assertIn("error", audit)
        self.assertEqual(scorecard.audit_run_part(None).status, scorecard.NOT_RUN)
        self.assertEqual(scorecard.audit_run_part({"tests": {}}).status, scorecard.FAIL)
        self.assertEqual(scorecard.check_audit(audit, all_pages=False, screens=self.SCREENS), 1)

    def test_the_weekly_run_audits_every_page(self):
        limited = reports.audit_log(self.LOG)
        self.assertEqual(scorecard.check_audit(limited, all_pages=False, screens=self.SCREENS), 0)
        self.assertEqual(scorecard.check_audit(limited, all_pages=True, screens=self.SCREENS), 1)
        full = reports.audit_log(self.LOG.replace("of 2", "of all").replace("of 14", "of all"))
        self.assertTrue(full["all_pages"])
        self.assertEqual(scorecard.check_audit(full, all_pages=True, screens=self.SCREENS), 0)

    def test_a_screen_without_its_line_fails_the_check(self):
        # A screen the run never reached (it stopped early, or a QUALITY_SCREENS filter left it out) isn't a pass.
        full = reports.audit_log(self.LOG.replace("of 2", "of all").replace("of 14", "of all"))
        for mode in (False, True):
            self.assertEqual(scorecard.check_audit(full, all_pages=mode, screens=self.SCREENS + ["trends"]), 1)
            self.assertEqual(scorecard.check_audit(full, all_pages=mode, screens=[]), 1)
        # The check reads the screens from QualityScreen.all.
        self.assertIn("review-planted-errors", scorecard.audit_screens())
        self.assertGreater(len(scorecard.audit_screens()), 20)


class AuditPagesTest(unittest.TestCase):
    SCREENS = """
    var auditedPages = 1
    static let all: [QualityScreen] = [
        QualityScreen(name: "dashboard", shows: .bar("Lab Results"), hasTints: true, auditedPages: 2, laterPages: moreCards),
        QualityScreen(
            name: "settings", shows: .bar("Settings"), arguments: ["--ss-screen", "settings"], auditedPages: 14
        ),
        QualityScreen(name: "load-failed", shows: .text("Couldn't Load Your Reports"), arguments: ["--ss-data", "load-failed"]),
    ]
"""
    BASELINE = {"pages": {"dashboard": {"audited": 2, "laterPages": True}, "settings": {"audited": 14}, "load-failed": {"audited": 1}}}

    def test_each_screen_is_read_with_its_pages(self):
        screens, unreadable = static_metrics.audited_pages(self.SCREENS)
        self.assertEqual(unreadable, 0)
        self.assertEqual(screens, {"dashboard": {"audited": 2, "laterPages": True}, "settings": {"audited": 14, "laterPages": False},
                                   "load-failed": {"audited": 1, "laterPages": False}})
        self.assertEqual(static_metrics.pages_against(screens, self.BASELINE), [])
        self.assertEqual(static_metrics.audited_pages(self.SCREENS.replace("auditedPages: 14", "auditedPages: Self.pages"))[1], 1)

    def test_fewer_audited_pages_fail_the_gate(self):
        for change in (("auditedPages: 14", "auditedPages: 13"), ("auditedPages: 2, ", ""), ("auditedPages: 14", "auditedPages: 14, laterPages: moreRows"),
                       ('QualityScreen(name: "load-failed"', 'QualityScreen(name: "load-failed-2"')):
            screens, _ = static_metrics.audited_pages(self.SCREENS.replace(*change))
            self.assertTrue(static_metrics.pages_against(screens, self.BASELINE), change)
        # One more must be written down too, so it can't be spent again unnoticed.
        screens, _ = static_metrics.audited_pages(self.SCREENS.replace("auditedPages: 14", "auditedPages: 15"))
        self.assertTrue(static_metrics.pages_against(screens, self.BASELINE))
        self.assertTrue(static_metrics.pages_against(screens, {}))

    def test_the_baseline_pages_may_only_go_up(self):
        base = self.BASELINE
        self.assertEqual(static_metrics.pages_lowered(base, base), [])
        self.assertEqual(static_metrics.pages_lowered({"pages": {**base["pages"], "settings": {"audited": 15}}}, base), [])
        self.assertEqual(static_metrics.pages_lowered({"pages": {**base["pages"], "dashboard": {"audited": 2}}}, base), [])
        self.assertTrue(static_metrics.pages_lowered({"pages": {**base["pages"], "settings": {"audited": 13}}}, base))
        self.assertTrue(static_metrics.pages_lowered({"pages": {**base["pages"], "settings": {"audited": 14, "laterPages": True}}}, base))
        self.assertTrue(static_metrics.pages_lowered({"pages": {k: v for k, v in base["pages"].items() if k != "settings"}}, base))
        self.assertEqual(static_metrics.pages_lowered({"pages": {}}, {"rules": 3}), [])

    def test_the_committed_pages_match_the_screens(self):
        ratchet = static_metrics.pages_ratchet()
        self.assertEqual((ratchet["problems"], ratchet["unreadable"]), ([], 0))
        self.assertGreater(ratchet["screens"], 20)

    def test_an_exemption_without_an_element_may_be_replaced_by_one_naming_it(self):
        unnamed = {"condition": "review/light/ax5/1", "type": "textClipped", "element": None}

        def raised(base: dict, *replacements: dict) -> list[str]:
            return static_metrics.baseline_raised({"rules": 3, "unmeasured": list(replacements)}, {"rules": 3, "unmeasured": [base]})

        # Strictly narrower: the element named, on the same or a narrower page, with the same or a lower limit.
        self.assertEqual(raised(unnamed, {**unnamed, "element": "1"}), [])
        self.assertEqual(raised({**unnamed, "condition": "settings/light/ax5"}, {**unnamed, "condition": "settings/light/ax5/5", "element": "About"}), [])
        self.assertEqual(raised({**unnamed, "limit": 9}, {**unnamed, "element": "Total", "limit": 9}), [])
        self.assertEqual(raised({**unnamed, "limit": 9}, {**unnamed, "element": "Total", "limit": 2}), [])
        self.assertEqual(raised({**unnamed, "perElement": "valueRow"}, {**unnamed, "element": "mg/dL", "perElement": "valueRow"}), [])
        # Wider or new: any element, a wider page, a higher or no limit, another type, a budget of its own, or one more.
        self.assertTrue(raised(unnamed, {**unnamed, "element": ""}))
        self.assertTrue(raised(unnamed, {**unnamed, "element": "1", "condition": "review/light/ax5"}))
        self.assertTrue(raised(unnamed, {**unnamed, "element": "1", "condition": "review"}))
        self.assertTrue(raised({**unnamed, "limit": 9}, {**unnamed, "element": "Total", "limit": 10}))
        self.assertTrue(raised({**unnamed, "limit": 9}, {**unnamed, "element": "Total"}))
        self.assertTrue(raised(unnamed, {**unnamed, "element": "1", "type": "hitRegion"}))
        self.assertTrue(raised({**unnamed, "perElement": "valueRow"}, {**unnamed, "element": "mg/dL"}))
        self.assertTrue(raised({**unnamed, "perElement": "valueRow"}, {**unnamed, "element": "mg/dL", "limit": 3}))
        self.assertTrue(raised(unnamed, {**unnamed, "element": "1"}, {**unnamed, "element": "2"}))
        self.assertTrue(raised(unnamed, unnamed, {**unnamed, "element": "1"}))
        # A named one never goes back to none, nor to another name.
        named = {**unnamed, "element": "1"}
        self.assertTrue(raised(named, unnamed))
        self.assertTrue(raised(named, {**unnamed, "element": "2"}))

    def test_the_committed_baseline_matches_the_audit(self):
        ratchet = static_metrics.exemption_ratchet()
        self.assertEqual(ratchet["above_baseline"], [])
        self.assertEqual(ratchet["below_baseline"], [])
        self.assertEqual((ratchet["not_in_baseline"], ratchet["not_in_audit"], ratchet["unreadable"]), ([], [], 0))


class ScorecardTest(unittest.TestCase):
    def test_pull_request_checks(self):
        part = scorecard.checks_part("CI", 7, [{"name": "lint", "bucket": "pass"}, {"name": "ui", "bucket": "pass"}])
        self.assertEqual(part.status, scorecard.PASS)
        # A skipped check didn't run: never green.
        part = scorecard.checks_part("CI", 7, [{"name": "lint", "bucket": "pass"}, {"name": "ui", "bucket": "skipping"}])
        self.assertEqual(part.status, scorecard.NOT_RUN)
        self.assertIn("ui", part.text)
        # The weekly audit is skipped on every pull request by design: said, and counted neither way.
        weekly = {"name": "Accessibility audit of every page", "bucket": "skipping"}
        part = scorecard.checks_part("CI", 7, [{"name": "lint", "bucket": "pass"}, weekly])
        self.assertEqual(part.status, scorecard.PASS)
        self.assertIn("1 green", part.text)
        self.assertIn("runs weekly, not on pull requests", part.text)
        self.assertEqual(scorecard.checks_part("CI", 7, [weekly]).status, scorecard.NOT_RUN)
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

    def code_measures(self, files: dict) -> list[scorecard.Measure]:
        with tempfile.TemporaryDirectory() as folder:
            for name, data in files.items():
                Path(folder, f"{name}.json").write_text(json.dumps(data))
            return scorecard.code_quality(scorecard.Inputs(Path(folder)), pr_checks=lambda: scorecard.not_run("CI"))

    def test_the_held_out_set_is_reported_beside_extraction_safety_never_scored(self):
        weak = {**self.EVALUATION, "set": "heldout", "reports": 10, "wrongValuesFlagged": 1, "wrongValuesNotFlagged": 3,
                "plantedFlagged": 80, "plantedNotFlagged": 20}
        measures = self.code_measures({"evaluation": {**self.EVALUATION, "set": "tuning"}, "evaluation_heldout": weak})
        names = [m.name for m in measures]
        self.assertEqual(names.index("Extraction safety, held-out set"), names.index("Extraction safety") + 1)
        held_out = measures[names.index("Extraction safety, held-out set")]
        # Numbers under the 9.5 threshold are said, but neither fail it nor weigh in: a gate would be tuned against.
        self.assertEqual(held_out.status, scorecard.REPORTED)
        self.assertEqual(held_out.weight, 0)
        self.assertNotIn(scorecard.FAIL, [p.status for p in held_out.parts])
        text = "\n".join(p.text for p in held_out.parts)
        self.assertIn("planted errors flagged 80/100 (80.0%) (under the 9.5 threshold)", text)
        self.assertIn("replay: wrong values not flagged 3 of 4 (under the 9.5 threshold)", text)
        self.assertEqual(measures[names.index("Extraction safety")].status, scorecard.PASS)
        self.assertNotIn("reported", scorecard.summary([m for m in measures if m.name != held_out.name]))
        self.assertIn("1 reported, not scored", scorecard.summary(measures))

    def test_a_held_out_measure_needs_the_held_out_summary(self):
        def held_out(files: dict) -> scorecard.Measure:
            return next(m for m in self.code_measures(files) if m.name == "Extraction safety, held-out set")
        self.assertEqual([p.status for p in held_out({}).parts], [scorecard.NOT_RUN])
        # The tuning set's summary in its place would pass the fit for generalisation.
        self.assertEqual([p.status for p in held_out({"evaluation_heldout": {**self.EVALUATION, "set": "tuning"}}).parts],
                         [scorecard.NOT_RUN])
        self.assertEqual([p.status for p in held_out({"evaluation_heldout": self.EVALUATION}).parts], [scorecard.NOT_RUN])

    def test_collect_writes_the_held_out_summary(self):
        collect = (scorecard.ROOT / "tools/quality/collect.sh").read_text()
        self.assertRegex(collect, r"-Pheldout=true \"-Psummary=\$OUT/evaluation_heldout\.json\"")

    def test_the_audit_runs_on_its_pinned_simulator_only(self):
        # Its exemptions and page counts hold on one device and OS: every place that runs it goes through
        # `ios_test.sh audit` (the simulator audit_simulator.sh names), and every other run leaves it out.
        root = scorecard.ROOT
        ios_test = (root / "tools/ios_test.sh").read_text()
        self.assertIn('DEVICE=$(tools/quality/audit_simulator.sh)', ios_test)
        self.assertRegex(ios_test, r'all\) ARGS\+=\("-skip-testing:\$AUDIT"\)')
        self.assertRegex(ios_test, r'ui\) ARGS\+=\(-only-testing:LabImporterUITests "-skip-testing:\$AUDIT"\)')
        for workflow in ("build_pr.yml", "quality.yml"):
            text = (root / ".github/workflows" / workflow).read_text()
            self.assertIn("tools/quality/audit_simulator.sh --create", text, workflow)
            self.assertIn("tools/ios_test.sh audit", text, workflow)
            self.assertNotIn("-only-testing:LabImporterUITests/AccessibilityAuditUITests", text, workflow)
        collect = (root / "tools/quality/collect.sh").read_text()
        self.assertIn("tools/ios_test.sh audit", collect)
        self.assertRegex(collect, r'reports\.py audit "\$LOGS/ios-audit\.log"')

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
        # Every kind PlantedErrors.kt plants, read from its enum: a new kind without a bound could read worse unseen.
        planted = (scorecard.ROOT / "evaluation/src/main/kotlin/dev/idoodler/labimporter/evaluation/PlantedErrors.kt").read_text()
        enum = planted.split("enum class Fault", 1)[1].split("}", 1)[0]
        kinds = set(re.findall(r"^\s+([A-Z_]+)\(", enum, re.MULTILINE))
        self.assertGreaterEqual(kinds, {"TENFOLD", "TENTH", "DIGIT_DROPPED", "DIGIT_DOUBLED", "NEIGHBOUR", "QUALIFIER_DROPPED",
                                        "WRONG_UNIT", "DATE_COLUMN", "DATE_DAY", "DATE_YEAR", "DATE_SWAPPED"})
        self.assertEqual(set(floor["plantedByKind"]), kinds)
        self.assertTrue(all({"planted", "notFlagged"} <= set(b) for b in floor["plantedByKind"].values()))

    def test_the_scripted_score_counts_not_run_as_not_passing(self):
        def measure(weight, status=None, **kind):
            m = scorecard.Measure("Code quality", f"m{weight}", weight, "", **kind)
            m.parts = [scorecard.Part(status, "")] if status else []
            return m
        measures = [measure(25, scorecard.PASS), measure(15, scorecard.FAIL), measure(10), measure(15, panel=True),
                    measure(0, scorecard.REPORTED, reported=True)]
        # 25 of the 50 scripted weight passes: the panel's and the reported don't count, the one not run does.
        self.assertEqual(scorecard.score(measures), 5.0)
        self.assertEqual(scorecard.score([measure(10), measure(15, panel=True)]), 0.0)

    def test_only_a_changed_scorecard_leaves_the_tree_clean(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            run = lambda *args: scorecard.git(*args, cwd=root)  # noqa: E731
            run("init", "-q")
            (root / "docs/quality").mkdir(parents=True)
            (root / scorecard.SCORECARD).write_text("one run")
            (root / "code.py").write_text("x = 1")
            run("add", ".")
            run("-c", "user.email=q@example.com", "-c", "user.name=q", "commit", "-qm", "measured")
            (root / scorecard.SCORECARD).write_text("the next run")
            self.assertEqual(scorecard.uncommitted(root), [])
            (root / "code.py").write_text("x = 2")
            self.assertEqual([line.split()[-1] for line in scorecard.uncommitted(root)], ["code.py"])
        self.assertRegex((scorecard.ROOT / "tools/quality/collect.sh").read_text(), r"scorecard\.py --record-run \"\$OUT/run\.json\"")

    def test_a_tree_changed_while_the_run_ran_is_not_clean(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            run = lambda *args: scorecard.git(*args, cwd=root)  # noqa: E731
            run("init", "-q")
            (root / "code.py").write_text("x = 1")
            run("add", ".")
            run("-c", "user.email=q@example.com", "-c", "user.name=q", "commit", "-qm", "measured")
            # Where collect.sh keeps it, build/, isn't tracked either.
            (root / ".gitignore").write_text("run.json\n.gitignore\n")
            record = root / "run.json"
            scorecard.record_run(record, root=root)
            (root / "code.py").write_text("x = 2")  # edited while the steps run
            scorecard.record_run(record, finished=True, root=root)
            data = json.loads(record.read_text())
        self.assertEqual((data["uncommitted"], data["finished_uncommitted"]), (0, 1))
        clean = lambda run: scorecard.measured_commit({"run": run})[1]  # noqa: E731
        self.assertFalse(clean(data))
        self.assertTrue(clean({**data, "finished_uncommitted": 0}))
        self.assertFalse(clean({"commit": data["commit"], "uncommitted": 0}), "a run that didn't record its end")
        self.assertFalse(clean({**data, "finished_uncommitted": 0, "finished_commit": "0" * 40}), "committed while it ran")
        collect = (scorecard.ROOT / "tools/quality/collect.sh").read_text()
        self.assertRegex(collect, r"scorecard\.py --finish-run \"\$OUT/run\.json\"\n.*scorecard\.py --input")

    def test_the_scorecard_reports_the_measured_commit(self):
        with tempfile.TemporaryDirectory() as folder:
            Path(folder, "run.json").write_text(json.dumps({
                "commit": "abcdef1234567", "uncommitted": 0, "finished_commit": "abcdef1234567", "finished_uncommitted": 0,
            }))
            output = Path(folder, "SCORECARD.md")
            scorecard.write(Path(folder), output, pr_checks=lambda: scorecard.not_run("CI"))
            text = output.read_text()
            card = json.loads(Path(folder, "scorecard.json").read_text())
        self.assertIn("at commit `abcdef1`. ", text)
        self.assertIn("**Code quality:** scripted score 0.0 of 10;", text)
        self.assertIn("**Three runs in a row:** not run (`tools/quality/consistency.sh`).", text)
        self.assertEqual((card["commit"], card["clean"], card["measures"]["Write-path safety"]), ("abcdef1234567", True, scorecard.NOT_RUN))
        self.assertEqual(card["steps"]["static"], None)

    def consistency(self, runs: list[tuple[int, dict | None]]) -> dict:
        with tempfile.TemporaryDirectory() as folder:
            for number, (exit_code, card) in enumerate(runs, 1):
                run = Path(folder, f"run-{number}")
                run.mkdir()
                Path(run, "exit").write_text(f"{exit_code}\n")
                if card is not None:
                    Path(run, "scorecard.json").write_text(json.dumps(card))
            return scorecard.consistency(Path(folder))

    GREEN = {"commit": "abcdef1234567", "clean": True, "steps": {"static": 0, "ios-tests": 0, "mutation": None},
             "measures": {"Write-path safety": "pass", "Screenshot matrix": "panel", "Extraction safety, held-out set": "reported"}}

    def test_three_green_runs_in_a_row(self):
        data = self.consistency([(0, self.GREEN)] * 3)
        self.assertTrue(data["all_green"])
        self.assertEqual(scorecard.consistency_line(data, "abcdef1234567"), "3 of 3 consecutive runs green at `abcdef1`")
        self.assertEqual(scorecard.consistency_line(data, "1234567abcdef"), "not run at this commit (the last runs were at abcdef1)")
        self.assertEqual(scorecard.consistency_line(None, "abcdef1234567"), "not run (`tools/quality/consistency.sh`)")

    def test_a_run_that_is_not_green_says_what_failed(self):
        flaky = {**self.GREEN, "steps": {"static": 0, "ios-tests": 65}, "measures": {"Accessibility": "FAIL", "Round trips": "not run"}}
        moved = {**self.GREEN, "commit": "9999999000000"}
        data = self.consistency([(0, self.GREEN), (1, flaky), (0, moved), (0, {**self.GREEN, "clean": False}), (1, None)])
        self.assertFalse(data["all_green"])
        self.assertEqual([r["green"] for r in data["runs"]], [True, False, False, False, False])
        self.assertEqual(scorecard.consistency_line(data, "abcdef1234567"),
                         "1 of 5 consecutive runs green at `abcdef1`; run 2: collect.sh exit 1, step ios-tests exit 65, "
                         "Accessibility FAIL, Round trips not run; run 3: at 9999999, not abcdef1; run 4: with uncommitted changes; "
                         "run 5: collect.sh exit 1, no scorecard")
        self.assertRegex((scorecard.ROOT / "tools/quality/consistency.sh").read_text(), r"\nRUNS=3\n")

    def test_the_panel_judges_screenshots_of_the_measured_commit_only(self):
        commit, older = "abcdef1234567", "1234567abcdef"
        taken = lambda at, dirty=0: {"commit": at, "uncommitted": 0, "finished_commit": at, "finished_uncommitted": dirty}  # noqa: E731

        def panel(records: dict) -> list[str]:
            with tempfile.TemporaryDirectory() as folder:
                Path(folder, "run.json").write_text(json.dumps(taken(commit)))
                for name, record in records.items():
                    Path(folder, name).mkdir()
                    Path(folder, name, "a.png").write_bytes(b"png")
                    if record is not None:
                        Path(folder, name, "commit.json").write_text(json.dumps(record))
                measures = scorecard.design_quality(scorecard.Inputs(Path(folder)))
            return [m.status for m in measures if m.panel]

        self.assertEqual(panel({"ios-screens": taken(commit), "android-screens": taken(commit)}), ["panel", "panel"])
        # Days older than the measured commit, from uncommitted code, without a record or missing: nothing to judge.
        for records in ({"ios-screens": taken(older), "android-screens": taken(commit)},
                        {"ios-screens": taken(commit), "android-screens": taken(commit, dirty=2)},
                        {"ios-screens": taken(commit), "android-screens": None},
                        {"ios-screens": taken(commit)}):
            self.assertEqual(panel(records), [scorecard.NOT_RUN, scorecard.NOT_RUN], records)
        parts = scorecard.screenshots_parts(Path("/nonexistent"), commit)
        self.assertTrue(all(p.status == scorecard.NOT_RUN for p in parts))
        # A panel not run keeps a consistency run from being green.
        data = self.consistency([(0, {**self.GREEN, "measures": {"Screenshot matrix": scorecard.NOT_RUN}})])
        self.assertFalse(data["all_green"])
        collect = (scorecard.ROOT / "tools/quality/collect.sh").read_text()
        self.assertIn("step ios-screens ios_screens", collect)
        self.assertIn("step android-screens android_screens", collect)
        self.assertIn("ScreenMatrixTest", collect)

    def test_the_write_path_measure_needs_the_ios_fault_tests(self):
        with tempfile.TemporaryDirectory() as folder:
            Path(folder, "ios.json").write_text(json.dumps({"suites": {"SharedCoreTests": {"Passed": 3}}}))
            measure = scorecard.code_quality(scorecard.Inputs(Path(folder)), pr_checks=lambda: scorecard.not_run("CI"))[0]
        self.assertEqual(measure.name, "Write-path safety")
        self.assertEqual(measure.status, scorecard.FAIL)
        self.assertIn("ReportWriterFaultTests (iOS): no such suite", "\n".join(p.text for p in measure.parts))


if __name__ == "__main__":
    unittest.main()
