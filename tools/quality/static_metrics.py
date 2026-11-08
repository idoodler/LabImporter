#!/usr/bin/env python3
"""Measures what can be read from the source alone, without building anything.

Prints JSON. Every number here is a count someone can reproduce with grep, so a
score built on it can't drift between reviewers. Needs only the standard
library, plus `lizard` (pip) for the complexity numbers.

    python3 tools/quality/static_metrics.py > build/quality/static.json
    python3 tools/quality/static_metrics.py --gate   # CI: exits 1 and lists what fails

`lizard` comes from tools/quality/requirements.txt (pinned); collect.sh installs it
into build/quality/venv, which is also looked in here.
"""
from __future__ import annotations

import csv
import io
import json
import os
import plistlib
import re
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
ALLOWLIST = ROOT / "tools/quality/complexity_allowlist.txt"
CCN_LIMIT, NLOC_LIMIT = 15, 100
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


def lizard_command() -> str | None:
    for candidate in (shutil.which("lizard"), Path(sys.executable).parent / "lizard", ROOT / "build/quality/venv/bin/lizard"):
        if candidate and Path(candidate).exists():
            return str(candidate)
    return None


def allowlist() -> dict[tuple[str, str], str]:
    """`path function — reason` lines; a line without a reason doesn't count."""
    entries = {}
    for line in ALLOWLIST.read_text(encoding="utf-8").splitlines() if ALLOWLIST.exists() else []:
        if not line.strip() or line.startswith("#"):
            continue
        site, _, reason = line.partition(" — ")
        path, _, name = site.strip().partition(" ")
        if reason.strip() and name:
            entries[(path, name.strip())] = reason.strip()
    return entries


def complexity() -> dict:
    """Cyclomatic complexity and length per function, from lizard."""
    lizard = lizard_command()
    if not lizard:
        return {"error": "lizard not installed (pip install -r tools/quality/requirements.txt)"}
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

    allowed = allowlist()
    over = [f for f in functions if f["ccn"] > CCN_LIMIT or f["nloc"] > NLOC_LIMIT]
    over_keys = {(f["file"], f["name"]) for f in over}
    return {
        "functions": len(functions),
        "ccn_mean": round(sum(ccn) / len(ccn), 2),
        "ccn_p95": ccn[int(0.95 * len(ccn))],
        "ccn_max": ccn[-1],
        "nloc_p95": nloc[int(0.95 * len(nloc))],
        "nloc_max": nloc[-1],
        "ccn_over_15": [site(f) for f in functions if f["ccn"] > CCN_LIMIT],
        "nloc_over_100": [site(f) for f in functions if f["nloc"] > NLOC_LIMIT],
        "over_limit_allowlisted": len([f for f in over if (f["file"], f["name"]) in allowed]),
        "over_limit_without_reason": [site(f) for f in over if (f["file"], f["name"]) not in allowed],
        # Listed, but gone or back under the limits: the entry should go.
        "stale_allowlist_entries": [f"{path} {name}" for path, name in allowed if (path, name) not in over_keys],
    }


def duplication() -> dict:
    """Share of duplicated code, from lizard's duplicate detector (same roots as complexity)."""
    lizard = lizard_command()
    if not lizard:
        return {"error": "lizard not installed (pip install -r tools/quality/requirements.txt)"}
    paths = [r for r in SWIFT_ROOTS + KOTLIN_ROOTS if (ROOT / r).exists()]
    out = subprocess.run([lizard, "-l", "swift", "-l", "kotlin", "-Eduplicate", *paths], cwd=ROOT, capture_output=True, text=True).stdout
    rate = re.search(r"Total duplicate rate:\s*([\d.]+)%", out)
    if not rate:
        return {"error": "lizard printed no duplicate rate"}
    return {"duplicate_rate_pct": float(rate.group(1)), "duplicate_blocks": out.count("Duplicate block:")}


def file_lengths() -> dict:
    files = sources(SWIFT_ROOTS, ".swift") + sources(KOTLIN_ROOTS, ".kt")
    lengths = sorted(((sum(1 for _ in f.open(encoding="utf-8")), rel(f)) for f in files), reverse=True)
    return {"files": len(files), "over_500_lines": [f"{path} ({count})" for count, path in lengths if count > 500]}


FORMAT_SPECIFIER = re.compile(r"%(\d+\$)?[-+ #0]*\d*(\.\d+)?(ll|l|h|hh|q|z|t|j)?[@dDuUxXoOfFeEgGcCsSpaA%]")
APP_NAME = "LabImporter"


def nothing_to_translate(key: str) -> bool:
    """"", "%lld" and the app's name read the same in every language."""
    return key == APP_NAME or not any(ch.isalpha() for ch in FORMAT_SPECIFIER.sub("", key))


def catalog(path: str, fallback: str | None = None) -> dict:
    """Translation completeness of one String Catalog; `stale` keys are unused.

    With a [fallback] catalog, a language missing here counts only if that one lacks it
    too: Android.xcstrings may override single languages of the iOS wording (CLAUDE.md).
    """
    strings = json.loads((ROOT / path).read_text(encoding="utf-8"))["strings"]
    shared = json.loads((ROOT / fallback).read_text(encoding="utf-8"))["strings"] if fallback else {}
    missing, stale = [], []
    for key, entry in strings.items():
        if entry.get("shouldTranslate") is False or nothing_to_translate(key):
            continue
        if entry.get("extractionState") == "stale":
            stale.append(key)
        localizations = {**shared.get(key, {}).get("localizations", {}), **entry.get("localizations", {})}
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


REQUIRED_REASON_APIS = {
    # Apple's required-reason API categories: what calls them in Swift (or Kotlin/Native), and the reasons there are.
    "NSPrivacyAccessedAPICategoryUserDefaults": (r"\bUserDefaults\b|@AppStorage\b|\bNSUserDefaults\b", {"CA92.1", "1C8F.1", "C56D.1", "AC6B.1"}),
    "NSPrivacyAccessedAPICategoryFileTimestamp": (
        r"\b(creation|contentModification|modification|contentAccess|addedToDirectory|attributeModification)Date(Key)?\b"
        r"|\battributesOfItem\b|\bfile(Creation|Modification)Date\b|\bgetattrlist\b",
        {"DDA9.1", "C617.1", "3B52.1", "0A2A.1"},
    ),
    "NSPrivacyAccessedAPICategorySystemBootTime": (r"\bsystemUptime\b|\bmach_absolute_time\b", {"35F9.1", "8FFB.1", "3D61.1"}),
    "NSPrivacyAccessedAPICategoryDiskSpace": (
        r"\bvolume(Available|Total)Capacity\w*|\bsystem(Free)?Size\b|\bstatv?fs\b|\bNSFileSystem(Free)?Size\b",
        {"85F4.1", "E174.1", "7D9E.1", "B728.1"},
    ),
    "NSPrivacyAccessedAPICategoryActiveKeyboards": (r"\bactiveInputModes\b", {"3EC4.1", "54BD.1"}),
}

# The sources compiled into each bundle: the extension also builds the app's ShareInbox.swift
# (tools/sync_xcode_project.rb), and the app links LabImporterCore, whose iOS code is iosMain.
BUNDLE_SOURCES = {
    "LabImporter": [("LabImporter", ".swift"), ("shared/src/iosMain", ".kt")],
    "LabImporterShare": [("LabImporterShare", ".swift"), ("LabImporter/Services/ShareInbox.swift", ".swift")],
}


def bundle_sources(folder: str) -> list[Path]:
    return sorted({p for root, suffix in BUNDLE_SOURCES[folder] for p in ([ROOT / root] if root.endswith(suffix) else sources([root], suffix))})


def privacy_manifest_problems(folder: str) -> list[str]:
    """What's wrong with a bundle's PrivacyInfo.xcprivacy; one entry "missing" if there is none.

    Besides its shape: every required-reason API the bundle's sources call must be declared, with
    reasons Apple lists for it. A declared one nothing calls isn't failed: the Kotlin runtime in
    LabImporterCore may call it without a line here (`nm -u` on the built binary is the check).
    """
    path = ROOT / folder / "PrivacyInfo.xcprivacy"
    if not path.exists():
        return ["missing"]
    try:
        manifest = plistlib.loads(path.read_bytes())
    except plistlib.InvalidFileException as error:
        return [f"unreadable: {error}"]
    problems = []
    # Nothing leaves the device, so all three are empty or false by design.
    if manifest.get("NSPrivacyTracking") is not False:
        problems.append("NSPrivacyTracking isn't false")
    for key in ("NSPrivacyTrackingDomains", "NSPrivacyCollectedDataTypes"):
        if manifest.get(key) != []:
            problems.append(f"{key} isn't an empty list")
    for api in manifest.get("NSPrivacyAccessedAPITypes", None) or []:
        if not api.get("NSPrivacyAccessedAPIType") or not api.get("NSPrivacyAccessedAPITypeReasons"):
            problems.append(f"API entry without a type or reason: {api}")
    if "NSPrivacyAccessedAPITypes" not in manifest:
        problems.append("NSPrivacyAccessedAPITypes missing")
    declared: dict[str, set[str]] = {}
    for api in manifest.get("NSPrivacyAccessedAPITypes", None) or []:
        declared.setdefault(api.get("NSPrivacyAccessedAPIType", ""), set()).update(api.get("NSPrivacyAccessedAPITypeReasons", None) or [])
    for category, reasons in declared.items():
        if category not in REQUIRED_REASON_APIS:
            problems.append(f"unknown API category {category}")
        elif wrong := sorted(reasons - REQUIRED_REASON_APIS[category][1]):
            problems.append(f"{category}: no such reason {', '.join(wrong)}")
    files = bundle_sources(folder)
    for category, (pattern, _) in REQUIRED_REASON_APIS.items():
        if category not in declared and (calls := grep(files, pattern)):
            problems.append(f"{category} not declared, but called at {', '.join(calls[:3])}" + (f" and {len(calls) - 3} more" if len(calls) > 3 else ""))
    return problems


def privacy() -> dict:
    """The privacy promise, as greppable facts."""
    swift = sources(SWIFT_ROOTS, ".swift")
    kotlin = sources(KOTLIN_ROOTS, ".kt")
    app_manifest, share_manifest = privacy_manifest_problems("LabImporter"), privacy_manifest_problems("LabImporterShare")
    return {
        "ios_network_calls": grep(swift, r"URLSession|NWConnection|URLRequest|WKWebView"),
        "ios_print_statements": grep(swift, r"(^|\s)(print|NSLog|debugPrint|dump)\("),
        "ios_privacy_manifest": "missing" not in app_manifest,
        "share_extension_privacy_manifest": "missing" not in share_manifest,
        "privacy_manifest_problems": [f"LabImporter: {p}" for p in app_manifest] + [f"LabImporterShare: {p}" for p in share_manifest],
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


# A block holding nothing but whitespace and line comments, over any number of lines.
EMPTY_BLOCK = r"\{(?:\s|//[^\n]*)*\}"
SWALLOW = {
    # `try?` also at the end of a line (`let x = try?` with the call on the next one).
    ".swift": re.compile(r"\btry\?(?=\s|$)|\bcatch\b[^{\n]*" + EMPTY_BLOCK, re.M),
    ".kt": re.compile(r"\bcatch\s*\([^)]*\)\s*" + EMPTY_BLOCK + r"|\.getOrNull\(\)|\.getOrDefault\(|\.getOrElse\b|\.isSuccess\b|\.isFailure\b"),
}
"""An error dropped on the floor: `try?`, an empty catch, a `runCatching` result turned into a default or a flag."""

# What a `runCatching` chain does with the failure: these look at it (or throw it on).
RESULT_HANDLED = re.compile(r"\.(onFailure|exceptionOrNull|getOrThrow|fold)\b")


def run_catching_chains(text: str) -> list[tuple[int, int]]:
    """Each `runCatching { … }` with the calls chained onto it, as (start, end) offsets.

    The chain is what follows the lambda's closing brace on its line, and on lines that go on
    with `.` or `?.`, comment lines between them included: `}.isSuccess`, `}\n    .getOrNull()`.
    """
    chains = []
    chain = re.compile(r"(?:[ \t]*\n(?:[ \t]*//[^\n]*\n)*[ \t]*(?=\??\.)|[ \t]*)\??\.\w+(?:\([^()\n]*\))?(?:\s*\{[^{}]*\})?")
    for match in re.finditer(r"\brunCatching\s*\{", text):
        depth, index = 1, match.end()
        while depth and index < len(text):
            depth += {"{": 1, "}": -1}.get(text[index], 0)
            index += 1
        while (link := chain.match(text, index)) and link.end() > index:
            index = link.end()
        chains.append((match.start(), index))
    return chains


def swallow_sites(text: str, suffix: str) -> list[tuple[int, int]]:
    """Where errors are dropped, as (first line, last line), 0-based.

    A `runCatching` counts as one site from its start to the end of its chain: dropped when
    nothing in the chain looks at the failure, `runCatching { close() }` alone included.
    """
    def line(offset: int) -> int:
        return text.count("\n", 0, offset)

    hits = [(m.start(), m.end()) for m in SWALLOW[suffix].finditer(text)]
    if suffix == ".kt":
        for start, end in run_catching_chains(text):
            inside = [h for h in hits if start <= h[0] < end]
            hits = [h for h in hits if h not in inside]
            if inside or not RESULT_HANDLED.search(text, start, end):
                hits.append((start, end))
    return sorted({(line(start), line(max(start, end - 1))) for start, end in hits})


def swallowed_errors() -> dict:
    """Errors dropped in the paths that read and write reports, each needing a stated reason.

    A reason is a comment on the site's lines or the line above it. Only these paths count:
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
        text = path.read_text(encoding="utf-8")
        lines = text.splitlines()
        for first, last in swallow_sites(text, path.suffix):
            if lines[first].lstrip().startswith(("//", "*", "/*")):
                continue
            reason = any("//" in lines[i] for i in range(first, last + 1)) or (first > 0 and lines[first - 1].lstrip().startswith("//"))
            (explained if reason else unexplained).append(f"{rel(path)}:{first + 1}")
    return {"explained": len(explained), "unexplained": unexplained}


def tests() -> dict:
    swift = sources(["LabImporterTests", "LabImporterUITests"], ".swift")
    return {
        "swift_tests": len(grep(swift, r"@Test\b|func test[A-Z]")),
        "kotlin_shared_tests": len(grep(sources(["shared/src/jvmTest"], ".kt"), r"@Test\b")),
        "kotlin_android_tests": len(grep(sources(["androidApp/src/test", "androidApp/src/testDebug"], ".kt"), r"@Test\b")),
    }


def ci() -> dict:
    """Build and release safety: third-party actions and images pinned, dependency updates on."""
    workflows = sorted((ROOT / ".github/workflows").glob("*.yml"))
    uses = re.compile(r"^\s*(?:-\s*)?uses:\s*([^\s#]+)")
    # A job's `container: <image>` or `container:`/`services:` `image: <image>`.
    image = re.compile(r"^\s*(?:-\s*)?(?:image|container):\s*['\"]?([^\s#'\"]+)")
    digest = re.compile(r"@sha256:[0-9a-f]{64}$")
    unpinned, pinned = [], 0
    for path in workflows:
        for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
            match = uses.match(line)
            if match and not match.group(1).startswith("./"):
                # A tag or branch can be moved to other code; a full commit SHA or an image digest can't.
                if re.search(r"@[0-9a-f]{40}$", match.group(1)) or (match.group(1).startswith("docker://") and digest.search(match.group(1))):
                    pinned += 1
                else:
                    unpinned.append(f"{rel(path)}:{number} {match.group(1)}")
            if (match := image.match(line)) and not match.group(1).startswith("${{"):
                # A tag (`:0.63.2`, `:latest`) can be pushed again; only a digest names one image.
                if digest.search(match.group(1)):
                    pinned += 1
                else:
                    unpinned.append(f"{rel(path)}:{number} {match.group(1)}")
    dependabot = ROOT / ".github/dependabot.yml"
    ecosystems = re.findall(r"package-ecosystem:\s*\"?([\w-]+)", dependabot.read_text(encoding="utf-8")) if dependabot.exists() else []
    return {"workflows": len(workflows), "pinned_actions": pinned, "unpinned": unpinned, "dependabot_ecosystems": sorted(set(ecosystems))}


def gate_failures(report: dict) -> list[str]:
    """What CI fails on (.github/workflows/quality.yml): each a promise the code makes today."""
    failures = []
    c = report["complexity"]
    if "error" in c:
        failures.append(f"complexity: {c['error']}")
    else:
        failures += [f"over complexity {CCN_LIMIT} or {NLOC_LIMIT} lines, not in {rel(ALLOWLIST)}: {s}" for s in c["over_limit_without_reason"]]
        failures += [f"stale entry in {rel(ALLOWLIST)}: {s}" for s in c["stale_allowlist_entries"]]
    failures += [f"swallowed error in a storage path without a stated reason: {s}" for s in report["swallowed_errors"]["unexplained"]]
    for name in ("ios_strings", "android_strings"):
        failures += [f"{name}: '{m['key']}' untranslated in {', '.join(m['languages'])}" for m in report[name]["missing_translations"]]
    failures += [f"iOS network API: {s}" for s in report["privacy"]["ios_network_calls"]]
    failures += [f"privacy manifest: {p}" for p in report["privacy"]["privacy_manifest_problems"]]
    failures += [f"SwiftUI view without #Preview: {s}" for s in report["previews"]["swift_files_without_preview"]]
    failures += [f"third-party action or image not pinned to a commit SHA or digest: {s}" for s in report["ci"]["unpinned"]]
    return failures


def main() -> None:
    report = {
        "complexity": complexity(),
        "duplication": duplication(),
        "file_lengths": file_lengths(),
        "ios_strings": catalog("LabImporter/Localizable.xcstrings"),
        "android_strings": catalog("androidApp/translations/Android.xcstrings", fallback="LabImporter/Localizable.xcstrings"),
        "previews": previews(),
        "design_tokens": design_tokens(),
        "accessibility": accessibility(),
        "privacy": privacy(),
        "safety": safety(),
        "swallowed_errors": swallowed_errors(),
        "tests": tests(),
        "ci": ci(),
    }
    report["gate_failures"] = gate_failures(report)
    if "--gate" in sys.argv:
        for failure in report["gate_failures"]:
            print(f"::error::{failure}" if "GITHUB_ACTIONS" in os.environ else failure)
        print(f"{len(report['gate_failures'])} static gate failure(s)")
        sys.exit(1 if report["gate_failures"] else 0)
    json.dump(report, sys.stdout, indent=2, ensure_ascii=False)
    print()


if __name__ == "__main__":
    main()
