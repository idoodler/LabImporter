#!/usr/bin/env python3
"""Measures what can be read from the source alone, without building anything.

Prints JSON. Every number here is a count someone can reproduce with grep, so a
score built on it can't drift between reviewers. Needs only the standard
library, plus `lizard` (pip) for the complexity numbers.

    python3 tools/quality/static_metrics.py > build/quality/static.json
"""
from __future__ import annotations

import csv
import io
import json
import re
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SWIFT_ROOTS = ["LabImporter", "LabImporterShare"]
KOTLIN_ROOTS = ["shared/src/commonMain", "shared/src/androidMain", "shared/src/jvmMain", "shared/src/iosMain", "androidApp/src/main"]
LANGUAGES = "bg ca cs da de el en es fi fr hr hu it ja nl pl pt-BR pt-PT ro ru sk sv tr uk zh-Hans".split()


def sources(roots: list[str], suffix: str) -> list[Path]:
    return sorted(p for r in roots for p in (ROOT / r).rglob(f"*{suffix}") if p.is_file())


def rel(path: Path) -> str:
    return str(path.relative_to(ROOT))


def grep(files: list[Path], pattern: str, skip_comments: bool = True) -> list[str]:
    """Matching lines as `path:line`; comment lines don't count."""
    regex = re.compile(pattern)
    hits = []
    for path in files:
        for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
            if skip_comments and line.lstrip().startswith(("//", "*", "/*")):
                continue
            if regex.search(line):
                hits.append(f"{rel(path)}:{number}")
    return hits


def complexity() -> dict:
    """Cyclomatic complexity and length per function, from lizard."""
    lizard = shutil.which("lizard") or str(Path(sys.executable).parent / "lizard")
    if not Path(lizard).exists():
        return {"error": "lizard not installed (pip install lizard)"}
    paths = [r for r in SWIFT_ROOTS + KOTLIN_ROOTS if (ROOT / r).exists()]
    out = subprocess.run([lizard, "-l", "swift", "-l", "kotlin", "--csv", *paths], cwd=ROOT, capture_output=True, text=True).stdout
    functions = [
        {"nloc": int(r[0]), "ccn": int(r[1]), "file": r[6], "name": r[7], "line": int(r[9])}
        for r in csv.reader(io.StringIO(out))
    ]
    ccn = sorted(f["ccn"] for f in functions)
    nloc = sorted(f["nloc"] for f in functions)

    def site(f: dict) -> str:
        return f"{f['file']}:{f['line']} {f['name']} (ccn {f['ccn']}, {f['nloc']} lines)"

    return {
        "functions": len(functions),
        "ccn_mean": round(sum(ccn) / len(ccn), 2),
        "ccn_p95": ccn[int(0.95 * len(ccn))],
        "ccn_max": ccn[-1],
        "nloc_p95": nloc[int(0.95 * len(nloc))],
        "nloc_max": nloc[-1],
        "ccn_over_15": [site(f) for f in functions if f["ccn"] > 15],
        "nloc_over_100": [site(f) for f in functions if f["nloc"] > 100],
    }


def file_lengths() -> dict:
    files = sources(SWIFT_ROOTS, ".swift") + sources(KOTLIN_ROOTS, ".kt")
    lengths = sorted(((sum(1 for _ in f.open(encoding="utf-8")), rel(f)) for f in files), reverse=True)
    return {"files": len(files), "over_500_lines": [f"{path} ({count})" for count, path in lengths if count > 500]}


def catalog(path: str) -> dict:
    """Translation completeness of one String Catalog; `stale` keys are unused."""
    strings = json.loads((ROOT / path).read_text(encoding="utf-8"))["strings"]
    missing, stale = [], []
    for key, entry in strings.items():
        if entry.get("shouldTranslate") is False:
            continue
        if entry.get("extractionState") == "stale":
            stale.append(key)
        localizations = entry.get("localizations", {})
        # English is the source: a key without an `en` entry falls back to itself.
        absent = [language for language in LANGUAGES if language != "en" and language not in localizations]
        if absent:
            missing.append({"key": key, "languages": absent})
    return {"keys": len(strings), "missing_translations": missing, "stale_keys": stale}


def previews() -> dict:
    """Which files declare a SwiftUI view or composable screen without a preview."""
    view = re.compile(r"\bstruct\s+\w+[^{]*:\s*(?:[\w, ]*,\s*)?View\b")
    swift_missing, swift_views, swift_previews = [], 0, 0
    # A view's previews may live in a sibling extension file (`ReviewView+Preview.swift`).
    files = sources(["LabImporter/Views"], ".swift")
    text = {f: f.read_text(encoding="utf-8") for f in files}
    for path, body in text.items():
        count = len(view.findall(body))
        swift_views += count
        swift_previews += body.count("#Preview")
        if count and "#Preview" not in body:
            stem = path.stem.split("+")[0]
            if not any(o != path and o.stem.split("+")[0] == stem and "#Preview" in b for o, b in text.items()):
                swift_missing.append(rel(path))
    screens = sources(["androidApp/src/main/kotlin/dev/idoodler/labimporter/ui"], "Screen.kt")
    android_missing = [rel(p) for p in screens if "@Preview" not in p.read_text(encoding="utf-8")]
    return {
        "swift_views": swift_views,
        "swift_previews": swift_previews,
        "swift_files_without_preview": swift_missing,
        "android_screens": len(screens),
        "android_screens_without_preview": android_missing,
    }


def design_tokens() -> dict:
    """Things that break Dynamic Type or bypass the palette. PDF pages have a fixed size, so they don't count."""
    views = [p for p in sources(["LabImporter/Views"], ".swift") if "/Export/" not in str(p)]
    compose = [p for p in sources(["androidApp/src/main/kotlin"], ".kt") if "/export/" not in str(p)]
    return {
        "ios_fixed_font_sizes": grep(views, r"\.font\(\.system\(size:"),
        "ios_colour_literals": grep(views, r"Color\((red|hue|white):|Color\(\.sRGB|UIColor\(red"),
        "ios_dynamic_type_clamps": grep(views, r"dynamicTypeSize\("),
        "android_fixed_font_sizes": grep(compose, r"fontSize\s*=\s*[\d.]+\.sp"),
        "android_colour_literals_outside_design": [
            h for h in grep(compose, r"\bColor\(0x") if "/ui/design/" not in h and "/ui/theme/" not in h
        ],
    }


def icon_buttons_without_description(path: Path) -> list[int]:
    lines = path.read_text(encoding="utf-8").splitlines()
    return [
        i + 1
        for i, line in enumerate(lines)
        if "IconButton(" in line and any("contentDescription = null" in near for near in lines[i : i + 4])
    ]


def accessibility() -> dict:
    """Interactive icons with no spoken name are the one thing a grep catches reliably."""
    compose = sources(["androidApp/src/main/kotlin"], ".kt")
    modifiers = r"\.accessibility(Label|Hint|Value|Element|AddTraits|HeadingLevel|Hidden|Representation)"
    return {
        "ios_accessibility_modifiers": len(grep(sources(["LabImporter/Views"], ".swift"), modifiers)),
        "android_null_descriptions_in_icon_buttons": [
            f"{rel(p)}:{line}" for p in compose for line in icon_buttons_without_description(p)
        ],
    }


def privacy() -> dict:
    """The privacy promise, as greppable facts."""
    swift = sources(SWIFT_ROOTS, ".swift")
    kotlin = sources(KOTLIN_ROOTS, ".kt")
    return {
        "ios_network_calls": grep(swift, r"URLSession|NWConnection|URLRequest|WKWebView"),
        "ios_print_statements": grep(swift, r"(^|\s)(print|NSLog|debugPrint|dump)\("),
        "ios_privacy_manifest": any(True for _ in ROOT.glob("LabImporter/**/PrivacyInfo.xcprivacy")),
        "share_extension_privacy_manifest": any(True for _ in ROOT.glob("LabImporterShare/**/PrivacyInfo.xcprivacy")),
        "android_network_apis_outside_download": [
            h for h in grep(kotlin, r"HttpURLConnection|OkHttp|Retrofit|java\.net\.Socket|\.openConnection\(") if "ModelDownloads" not in h
        ],
    }


def safety() -> dict:
    """Crash sites: a force unwrap in a health app is a lost report."""
    swift = sources(SWIFT_ROOTS, ".swift")
    kotlin = sources(KOTLIN_ROOTS, ".kt")
    return {
        "swift_force_try_or_cast": grep(swift, r"\btry!|\bas!\s"),
        "swift_fatal_errors": grep(swift, r"\bfatalError\("),
        "kotlin_not_null_assertions": grep(kotlin, r"[\w\)\]]!!"),
        "todos": grep(swift + kotlin, r"\b(TODO|FIXME|HACK|XXX)\b", skip_comments=False),
    }


SWALLOW = {
    ".swift": re.compile(r"\btry\?\s|catch\s*\{\s*\}"),
    ".kt": re.compile(r"\.getOrNull\(\)|\.getOrDefault\(|catch\s*\([^)]*\)\s*\{\s*\}"),
}
"""An error dropped on the floor: `try?`, an empty catch, a `runCatching` result turned into a default."""


def swallowed_errors() -> dict:
    """Errors dropped in the paths that read and write reports, each needing a stated reason.

    A reason is a comment on the same line or the line above. Only these paths count:
    a failed read here looks like "you have no reports", and a failed write like a save.
    """
    paths = (
        sources(["LabImporter/Services", "LabImporter/Views/Home", "LabImporter/Views/Import", "LabImporterShare"], ".swift")
        + sources(
            ["androidApp/src/main/kotlin/dev/idoodler/labimporter/data", "shared/src/commonMain/kotlin/dev/idoodler/labimporter/core/export",
             "shared/src/commonMain/kotlin/dev/idoodler/labimporter/core/transfer"],
            ".kt",
        )
    )
    explained, unexplained = [], []
    for path in paths:
        lines = path.read_text(encoding="utf-8").splitlines()
        for index, line in enumerate(lines):
            if line.lstrip().startswith(("//", "*", "/*")) or not SWALLOW[path.suffix].search(line):
                continue
            reason = "//" in line or (index > 0 and lines[index - 1].lstrip().startswith("//"))
            (explained if reason else unexplained).append(f"{rel(path)}:{index + 1}")
    return {"explained": len(explained), "unexplained": unexplained}


def tests() -> dict:
    swift = sources(["LabImporterTests", "LabImporterUITests"], ".swift")
    return {
        "swift_tests": len(grep(swift, r"@Test\b|func test[A-Z]")),
        "kotlin_shared_tests": len(grep(sources(["shared/src/jvmTest"], ".kt"), r"@Test\b")),
        "kotlin_android_tests": len(grep(sources(["androidApp/src/test", "androidApp/src/testDebug"], ".kt"), r"@Test\b")),
    }


def main() -> None:
    report = {
        "complexity": complexity(),
        "file_lengths": file_lengths(),
        "ios_strings": catalog("LabImporter/Localizable.xcstrings"),
        "android_strings": catalog("androidApp/translations/Android.xcstrings"),
        "previews": previews(),
        "design_tokens": design_tokens(),
        "accessibility": accessibility(),
        "privacy": privacy(),
        "safety": safety(),
        "swallowed_errors": swallowed_errors(),
        "tests": tests(),
    }
    json.dump(report, sys.stdout, indent=2, ensure_ascii=False)
    print()


if __name__ == "__main__":
    main()
