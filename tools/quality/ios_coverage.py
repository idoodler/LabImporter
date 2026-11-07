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
            # The path may sit in a worktree that itself lives under a LabImporter folder.
            parts = file["path"].rsplit("/LabImporter/", 1)[-1].split("/")
            folder = "/".join(parts[:2]) if len(parts) > 2 else parts[0]
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
