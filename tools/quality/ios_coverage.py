#!/usr/bin/env python3
"""Summarizes an iOS test run: coverage per target and folder, from an `.xcresult`.

    python3 tools/quality/ios_coverage.py path/to/ios.xcresult

Coverage is reported for orientation only (see docs/quality/RUBRIC.md): it shows
what the tests never reach, not that they'd notice a bug in what they do reach.
"""
from __future__ import annotations

import collections
import json
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def folder_of(path: str, root: Path = ROOT) -> str:
    """The folder a source file is counted under: its directory relative to the repository root,
    two levels deep ("LabImporter/Views", "LabImporterShare"). Splitting the absolute path on a
    folder name went wrong whenever the checkout itself sits under a folder of that name."""
    try:
        parts = Path(path).resolve().relative_to(root.resolve()).parts[:-1]
    except ValueError:
        return "(outside the repository)"
    return "/".join(parts[:2]) or "."


def main() -> None:
    raw = subprocess.run(
        ["xcrun", "xccov", "view", "--report", "--json", sys.argv[1]],
        capture_output=True, text=True, check=True,
    ).stdout
    report = json.loads(raw)
    summary: dict = {}
    for target in report["targets"]:
        name = target["name"].split(".")[0]
        if name.endswith("Tests") or name.endswith("Runner"):
            continue
        folders: dict = collections.defaultdict(lambda: [0, 0])
        for file in target["files"]:
            folder = folder_of(file["path"])
            folders[folder][0] += file["coveredLines"]
            folders[folder][1] += file["executableLines"]
        summary[name] = {
            "line_coverage_pct": round(target["lineCoverage"] * 100, 1),
            "executable_lines": target["executableLines"],
            "folders": {
                folder: round(covered * 100 / lines, 1)
                for folder, (covered, lines) in sorted(folders.items())
                if lines
            },
        }
    json.dump(summary, sys.stdout, indent=2)
    print()


if __name__ == "__main__":
    main()
