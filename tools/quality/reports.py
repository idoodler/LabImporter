#!/usr/bin/env python3
"""Turns the build tools' own reports into the small JSON files scorecard.py reads.

Standard library only. Each subcommand prints JSON:

    reports.py kover shared=shared/build/reports/kover/reportJvm.xml android=…
    reports.py junit shared=shared/build/test-results/jvmTest android=…
    reports.py lint androidApp/build/reports/lint-results-release.xml
    reports.py mutation build/quality/mutation/mutations.xml
    reports.py warnings gradle.log           # Kotlin compiler warnings ("w: ") in a Gradle log
    reports.py xcresult ios.xcresult         # iOS test counts and the failing tests
"""
from __future__ import annotations

import collections
import json
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

# Where a surviving mutant matters most: a wrong value, unit or date in a saved record.
CLINICAL = {
    "dev.idoodler.labimporter.core.export.CdaExport",
    "dev.idoodler.labimporter.core.export.CdaParser",
    "dev.idoodler.labimporter.core.export.FhirLabCodec",
    "dev.idoodler.labimporter.core.export.Ucum",
    "dev.idoodler.labimporter.core.parse.ReportSourceText",
    "dev.idoodler.labimporter.core.parse.LabParsePipeline",
    "dev.idoodler.labimporter.core.platform.NumberFormatting",
}


def pairs(args: list[str]) -> list[tuple[str, Path]]:
    return [(name, Path(path)) for name, _, path in (a.partition("=") for a in args)]


def pct(covered: int, total: int) -> float | None:
    return round(100 * covered / total, 1) if total else None


def kover(args: list[str]) -> dict:
    """Line and branch coverage from Kover's (JaCoCo-format) XML report: the report-level counters."""
    out = {}
    for name, path in pairs(args):
        if not path.exists():
            out[name] = {"error": f"no report at {path}"}
            continue
        root = ET.parse(path).getroot()
        counters = {c.get("type"): (int(c.get("covered")), int(c.get("missed"))) for c in root.findall("counter")}
        entry = {}
        for kind in ("LINE", "BRANCH"):
            covered, missed = counters.get(kind, (0, 0))
            entry[kind.lower()] = {"covered": covered, "total": covered + missed, "pct": pct(covered, covered + missed)}
        out[name] = entry
    return out


def junit(args: list[str]) -> dict:
    """Test counts per suite (class) from a folder of JUnit XML results."""
    out = {}
    for name, folder in pairs(args):
        files = sorted(folder.glob("TEST-*.xml")) if folder.is_dir() else []
        if not files:
            out[name] = {"error": f"no results in {folder}"}
            continue
        suites, failing = {}, []
        for file in files:
            suite = ET.parse(file).getroot()
            counts = {k: int(suite.get(k, 0)) for k in ("tests", "failures", "errors", "skipped")}
            # Kotlin Multiplatform names a suite after its target too: "ExportTest[jvm]".
            suites[re.sub(r"\[\w+\]$", "", suite.get("name"))] = counts
            for case in suite.findall("testcase"):
                if case.find("failure") is not None or case.find("error") is not None:
                    failing.append(f"{case.get('classname')}.{case.get('name')}")
        totals = {k: sum(s[k] for s in suites.values()) for k in ("tests", "failures", "errors", "skipped")}
        out[name] = {**totals, "failing": failing, "suites": suites}
    return out


def lint(args: list[str]) -> dict:
    """Android lint issues by severity and id."""
    path = Path(args[0])
    if not path.exists():
        return {"error": f"no lint report at {path}"}
    issues = ET.parse(path).getroot().findall("issue")
    by_severity = collections.Counter(i.get("severity") for i in issues)
    return {
        "errors": by_severity.get("Error", 0) + by_severity.get("Fatal", 0),
        "warnings": by_severity.get("Warning", 0),
        "informational": by_severity.get("Information", 0),
        "by_id": dict(collections.Counter(f"{i.get('severity')}: {i.get('id')}" for i in issues).most_common()),
    }


def mutation(args: list[str]) -> dict:
    """PIT's mutation score per package and class, and the survivors in clinical code.

    The score is killed / (all mutants), so a mutant no test reaches counts against it:
    code without tests can't look well tested. TIMED_OUT and MEMORY_ERROR mean the mutant
    broke the code badly enough to hang or blow up, which a test run notices, so they count
    as killed — PIT's own convention.
    """
    path = Path(args[0])
    if not path.exists():
        return {"error": f"no PIT report at {path}"}
    detected = {"KILLED", "TIMED_OUT", "MEMORY_ERROR", "RUN_ERROR"}
    packages: dict = collections.defaultdict(lambda: [0, 0])
    classes: dict = collections.defaultdict(lambda: [0, 0])
    statuses: collections.Counter = collections.Counter()
    survivors = []
    for m in ET.parse(path).getroot().findall("mutation"):
        cls = m.findtext("mutatedClass").split("$")[0]
        package = cls.rsplit(".", 1)[0]
        status = m.get("status")
        statuses[status] += 1
        killed = status in detected
        for bucket in (packages[package], classes[cls]):
            bucket[0] += killed
            bucket[1] += 1
        if not killed and cls in CLINICAL:
            survivors.append({
                "class": cls.rsplit(".", 1)[1],
                "method": m.findtext("mutatedMethod"),
                "line": int(m.findtext("lineNumber")),
                "status": status,
                "mutator": m.findtext("mutator").rsplit(".", 1)[-1],
                "description": m.findtext("description"),
            })

    def score(bucket: list[int]) -> dict:
        return {"killed": bucket[0], "total": bucket[1], "score_pct": pct(bucket[0], bucket[1])}

    killed = sum(b[0] for b in packages.values())
    total = sum(b[1] for b in packages.values())
    return {
        "total": score([killed, total]),
        "statuses": dict(statuses),
        "packages": {p.rsplit(".", 1)[1]: score(b) for p, b in sorted(packages.items())},
        "classes": {c.split(".core.", 1)[1]: score(b) for c, b in sorted(classes.items())},
        "clinical_survivors": sorted(survivors, key=lambda s: (s["class"], s["line"])),
    }


def warnings(args: list[str]) -> dict:
    """Kotlin compiler warnings in a Gradle log (with warnings as errors, they'd be `e: ` instead)."""
    path = Path(args[0])
    if not path.exists():
        return {"error": f"no log at {path}"}
    lines = sorted({line.strip() for line in path.read_text(errors="replace").splitlines() if line.startswith("w: ")})
    return {"kotlin_warnings": len(lines), "lines": lines[:50]}


def xcresult(args: list[str]) -> dict:
    """iOS test counts and failures from an .xcresult bundle (xcresulttool, Xcode 16+)."""
    path = Path(args[0])
    if not path.exists():
        return {"error": f"no result bundle at {path}"}
    run = subprocess.run(
        ["xcrun", "xcresulttool", "get", "test-results", "summary", "--path", str(path), "--compact"],
        capture_output=True, text=True,
    )
    if run.returncode != 0:
        return {"error": run.stderr.strip()[:500]}
    summary = json.loads(run.stdout)
    tests = subprocess.run(
        ["xcrun", "xcresulttool", "get", "test-results", "tests", "--path", str(path), "--compact"],
        capture_output=True, text=True,
    )
    suites: dict = collections.defaultdict(lambda: collections.Counter())

    def walk(node: dict, suite: str | None) -> None:
        kind = node.get("nodeType")
        if kind == "Test Suite":
            suite = node.get("name")
        if kind == "Test Case":
            suites[suite or "?"][node.get("result", "?")] += 1
        for child in node.get("children", []):
            walk(child, suite)

    if tests.returncode == 0:
        for node in json.loads(tests.stdout).get("testNodes", []):
            walk(node, None)
    return {
        "result": summary.get("result"),
        "tests": summary.get("totalTestCount"),
        "passed": summary.get("passedTests"),
        "failed": summary.get("failedTests"),
        "skipped": summary.get("skippedTests"),
        "failing": [f"{f.get('testIdentifierString') or f.get('testName')}: {f.get('failureText', '')[:200]}" for f in summary.get("testFailures", [])],
        "suites": {name: dict(counts) for name, counts in sorted(suites.items())},
    }


def main() -> None:
    commands = {"kover": kover, "junit": junit, "lint": lint, "mutation": mutation, "warnings": warnings, "xcresult": xcresult}
    if len(sys.argv) < 3 or sys.argv[1] not in commands:
        sys.exit(__doc__)
    json.dump(commands[sys.argv[1]](sys.argv[2:]), sys.stdout, indent=2, ensure_ascii=False)
    print()


if __name__ == "__main__":
    main()
