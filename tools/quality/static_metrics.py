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
AUDIT_TESTS = ROOT / "LabImporterUITests/AccessibilityAuditUITests.swift"
EXEMPTION_BASELINE = ROOT / "tools/quality/accessibility_exemptions_baseline.json"
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


def android_uses_ios_keys() -> set[str]:
    """iOS catalog keys an Android string takes its translations from (tools/xcstrings_to_android.py)."""
    sys.path.insert(0, str(ROOT / "tools"))
    try:
        import xcstrings_to_android
    finally:
        sys.path.pop(0)
    return xcstrings_to_android.ios_keys_android_uses()


def catalog(path: str, fallback: str | None = None, used_by_android: set[str] | None = None) -> dict:
    """Translation completeness of one String Catalog; `stale` keys are unused.

    With a [fallback] catalog, a language missing here counts only if that one lacks it
    too: Android.xcstrings may override single languages of the iOS wording (CLAUDE.md).
    Xcode marks a key stale when no Swift source uses it; one in [used_by_android] still
    translates an Android string, so it isn't stale (listed as `android_only_keys`).
    """
    strings = json.loads((ROOT / path).read_text(encoding="utf-8"))["strings"]
    shared = json.loads((ROOT / fallback).read_text(encoding="utf-8"))["strings"] if fallback else {}
    missing, stale, android_only = [], [], []
    for key, entry in strings.items():
        if entry.get("shouldTranslate") is False or nothing_to_translate(key):
            continue
        if entry.get("extractionState") == "stale":
            (android_only if key in (used_by_android or set()) else stale).append(key)
        localizations = {**shared.get(key, {}).get("localizations", {}), **entry.get("localizations", {})}
        # English is the source: a key without an `en` entry falls back to itself.
        absent = [language for language in LANGUAGES if language != "en" and language not in localizations]
        if absent:
            missing.append({"key": key, "languages": absent})
    return {"keys": len(strings), "missing_translations": missing, "stale_keys": stale, "android_only_keys": android_only}


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


def swift_split(text: str, start: int, open_char: str, close_char: str) -> tuple[list[str], int]:
    """The top-level comma-separated items of the bracket opened at [start], comments dropped, and where it closes.

    String literals (with their escapes) and nested brackets are skipped, so a `,` or `)` inside them
    doesn't split; an item is whatever sits between two top-level commas, however it is written.
    """
    items, current, depth, i = [], [], 0, start + 1
    while i < len(text):
        char = text[i]
        if text.startswith("//", i):
            i = text.find("\n", i) if "\n" in text[i:] else len(text)
            continue
        if text.startswith("/*", i):
            i = text.find("*/", i) + 2 if "*/" in text[i:] else len(text)
            continue
        if char == '"':
            end = i + 1
            while end < len(text) and text[end] != '"':
                end += 2 if text[end] == "\\" else 1
            current.append(text[i:end + 1])
            i = end + 1
            continue
        if char in "([{":
            depth += 1
        elif char in ")]}":
            if depth == 0 and char == close_char:
                items.append("".join(current))
                return [s.strip() for s in items if s.strip()], i
            depth -= 1
        elif char == "," and depth == 0:
            items.append("".join(current))
            current = []
            i += 1
            continue
        current.append(char)
        i += 1
    return [s.strip() for s in items + ["".join(current)] if s.strip()], len(text)


def exemption_of(item: str) -> dict | None:
    """One exemption as what it covers, or None when it isn't written with literals the script can read.

    `limit` absent is no limit (`Int.max`); `element` None is an issue without an element, "" any element.
    """
    condition = re.search(r'\bcondition:\s*"([^"\\]*)"', item)
    kind = re.search(r"\btype:\s*\.(\w+)", item)
    element = re.search(r'\belement:\s*(?:(nil)\b|"([^"\\]*)")', item)
    limit = re.search(r"\blimit:\s*([^,)]+)", item)
    per_element = re.search(r"\bperElement:\s*([^,)]+)", item)
    if not (condition and kind and element) or (limit and not limit.group(1).strip().isdigit()):
        return None
    exemption = {"condition": condition.group(1), "type": kind.group(1), "element": None if element.group(1) else element.group(2)}
    if limit:
        exemption["limit"] = int(limit.group(1).strip())
    if per_element:
        exemption["perElement"] = per_element.group(1).strip()
    return exemption


def audit_entries(text: str) -> list[dict | None]:
    """Every item of `AuditExemption.entries`, `Entry(…)`, `.init(…)` or however else it's written."""
    start = re.search(r"\bstatic\s+(?:let|var)\s+entries\b[^=\n]*=\s*\[", text)
    return [exemption_of(item) for item in swift_split(text, start.end() - 1, "[", "]")[0]] if start else []


def exemption_rules(text: str) -> int:
    """The exemptions `AuditExemption.reason` grants by rule, before any entry: each a `return` of a reason not taken from an entry."""
    start = re.search(r"\bstatic\s+func\s+reason\s*\(", text)
    if not start:
        return 0
    body_start = text.index("{", swift_split(text, start.end() - 1, "(", ")")[1])
    body_end = swift_split(text, body_start, "{", "}")[1]
    # A reason is a string literal or a named one (`return measured`); `nil`, an entry's reason and a closure's `return (…)` aren't rules.
    returns = re.findall(r'\breturn\s+("[^\n]*|[A-Za-z_]\w*\s*(?:\}|\n))', text[body_start:body_end])
    return sum(1 for r in returns if not r.startswith("nil") and "entry.reason" not in r)


def audit_exemptions(text: str) -> dict:
    """The exemptions of the iOS audit: each a finding let go.

    One without a named element covers an issue the audit found without one (`element: nil`)
    or any element on its page (`element: ""`): the widest kind, listed apart.
    """
    entries = audit_entries(text)
    no_element = sum(1 for e in entries if e and e["element"] is None)
    any_element = sum(1 for e in entries if e and e["element"] == "")
    return {"entries": len(entries), "without_named_element": no_element + any_element, "no_element": no_element,
            "any_element": any_element, "rules": exemption_rules(text)}


def exemption_key(e: dict) -> str:
    return json.dumps(e, sort_keys=True, ensure_ascii=False)


def covers(wide: dict, narrow: dict) -> bool:
    """Whether exemption [wide] lets go at least every issue [narrow] does (`AuditExemption.reason`'s matching)."""
    element_covered = (narrow["element"] is None) if wide["element"] is None else (
        narrow["element"] is not None and narrow["element"].startswith(wide["element"]))
    return (wide["type"] == narrow["type"] and (narrow["condition"] + "/").startswith(wide["condition"] + "/") and element_covered
            and wide.get("perElement") == narrow.get("perElement")
            and ("limit" not in wide or narrow.get("limit", float("inf")) <= wide["limit"]))


def uncovered(exemptions: list[dict], allowed: list[dict]) -> list[dict]:
    """The [exemptions] no exemption of [allowed] covers, each of [allowed] standing for one at most (a matching)."""
    owner: dict[int, int] = {}

    def assign(index: int, seen: set[int]) -> bool:
        for a, wide in enumerate(allowed):
            if a not in seen and covers(wide, exemptions[index]):
                seen.add(a)
                if a not in owner or assign(owner[a], seen):
                    owner[a] = index
                    return True
        return False

    return [e for i, e in enumerate(exemptions) if not assign(i, set())]


def exemption_ratchet() -> dict:
    """The audit's exemptions against the committed baseline, which lists each one and may only go down.

    The audit must match the baseline exactly: one added, widened (a shorter condition or element
    prefix, a higher or no limit) or written so the script can't read it isn't in it. Whether the
    baseline itself went down is `ratchet_raised`'s job (CI compares it with the base branch's).
    """
    text = AUDIT_TESTS.read_text(encoding="utf-8") if AUDIT_TESTS.exists() else ""
    counts = audit_exemptions(text)
    entries = audit_entries(text)
    baseline = json.loads(EXEMPTION_BASELINE.read_text(encoding="utf-8")) if EXEMPTION_BASELINE.exists() else {}
    listed = baseline.get("exemptions")
    base_counts = audit_exemptions("") if listed is None else {
        "entries": len(listed), "without_named_element": sum(1 for e in listed if not e["element"]), "rules": baseline.get("rules")}
    current_keys = [exemption_key(e) for e in entries if e]
    listed_keys = [exemption_key(e) for e in listed or []]
    keys = ("entries", "without_named_element", "rules")
    return {
        **counts,
        "baseline": {k: (base_counts[k] if listed is not None else None) for k in keys},
        # More than the baseline allows (or no baseline): the gate fails.
        "above_baseline": [k for k in keys if listed is None or base_counts[k] is None or counts[k] > base_counts[k]],
        # Fewer: the baseline must come down with them, so it can't be spent again later.
        "below_baseline": [k for k in keys if listed is not None and base_counts[k] is not None and counts[k] < base_counts[k]],
        "unreadable": sum(1 for e in entries if e is None),
        "not_in_baseline": [k for k in dict.fromkeys(current_keys) if current_keys.count(k) > listed_keys.count(k)],
        "not_in_audit": [k for k in dict.fromkeys(listed_keys) if listed_keys.count(k) > current_keys.count(k)],
    }


def accessibility() -> dict:
    """Interactive icons with no spoken name are the one thing a grep catches reliably."""
    compose = sources(["androidApp/src/main/kotlin"], ".kt")
    modifiers = r"\.accessibility(Label|Hint|Value|Element|AddTraits|HeadingLevel|Hidden|Representation)"
    return {
        "ios_accessibility_modifiers": len(grep(sources(["LabImporter/Views"], ".swift"), modifiers)),
        "android_null_descriptions_in_icon_buttons": [
            f"{rel(p)}:{line}" for p in compose for line in icon_buttons_without_description(p)
        ],
        "ios_audit_exemptions": exemption_ratchet(),
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
    def block_end(index: int) -> int:
        """The offset after the block whose `{` is at [index], nested blocks included."""
        depth = 0
        for offset in range(index, len(text)):
            depth += {"{": 1, "}": -1}.get(text[offset], 0)
            if depth == 0:
                return offset + 1
        return len(text)

    chains = []
    chain = re.compile(r"(?:[ \t]*\n(?:[ \t]*//[^\n]*\n)*[ \t]*(?=\??\.)|[ \t]*)\??\.\w+(?:\([^()\n]*\))?")
    # A link's lambda, whatever it nests: `.onSuccess { if (…) { … } }.onFailure { … }`.
    lambda_start = re.compile(r"\s*(?=\{)")
    for match in re.finditer(r"\brunCatching\s*\{", text):
        index = block_end(match.end() - 1)
        while (link := chain.match(text, index)) and link.end() > index:
            index = link.end()
            if lam := lambda_start.match(text, index):
                index = block_end(lam.end())
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


# The places that read, save, delete and import reports.
SWALLOW_PATHS = (
    ["LabImporter/Services", "LabImporter/Views/Home", "LabImporter/Views/Import", "LabImporter/Views/Review",
     "LabImporter/Views/History", "LabImporterShare"],
    ["androidApp/src/main/kotlin/dev/idoodler/labimporter/data", "androidApp/src/main/kotlin/dev/idoodler/labimporter/ui/AppViewModel.kt",
     "shared/src/commonMain/kotlin/dev/idoodler/labimporter/core/export", "shared/src/commonMain/kotlin/dev/idoodler/labimporter/core/transfer"],
)
REASON_WORDS = 4


def stated_reason(comment: str) -> bool:
    """A comment that says why: at least four words, not a bare marker ("// ignore", "// TODO").

    A lint directive's parts aren't words: `swiftlint:disable:this` and a rule name (`force_try`)
    say what is silenced, not why.
    """
    prose = " ".join(t for t in comment.split() if not re.search(r"\S:\S|_", t))
    return len(re.findall(r"[^\W\d_][\w'’-]*", prose)) >= REASON_WORDS


def comment_of(line: str) -> str | None:
    """The `//` comment on a line (outside a string literal), or None."""
    in_string = False
    for index, char in enumerate(line):
        if char == '"' and (index == 0 or line[index - 1] != "\\"):
            in_string = not in_string
        elif not in_string and line.startswith("//", index):
            return line[index + 2:]
    return None


def site_reason(lines: list[str], first: int, last: int) -> bool:
    """A swallowed error's reason: a comment on its lines, or the comment block right above it, of four words or more.

    A `///` doc comment documents the declaration below it, not the error it drops: it's never the reason.
    """
    comments = [c for i in range(first, last + 1) if (c := comment_of(lines[i])) is not None and not c.startswith("/")]
    above, i = [], first - 1
    while i >= 0 and lines[i].lstrip().startswith("//") and not lines[i].lstrip().startswith("///"):
        above.insert(0, lines[i].lstrip()[2:])
        i -= 1
    return any(stated_reason(c) for c in comments) or (bool(above) and stated_reason(" ".join(above)))


def swallowed_errors() -> dict:
    """Errors dropped in the paths that read, save, delete and import reports, each needing a stated reason.

    A reason is a comment of at least four words on the site's lines or right above it; a bare
    `//` or "// ignore" isn't one. Only these paths count: a failed read here looks like "you
    have no reports", and a failed write or delete like one that worked.
    """
    swift_roots, kotlin_roots = SWALLOW_PATHS
    paths = sources(swift_roots, ".swift") + sorted(
        p for r in kotlin_roots for p in ([ROOT / r] if r.endswith(".kt") else (ROOT / r).rglob("*.kt")) if p.is_file()
    )
    explained, unexplained = [], []
    for path in paths:
        text = path.read_text(encoding="utf-8")
        lines = text.splitlines()
        for first, last in swallow_sites(text, path.suffix):
            if lines[first].lstrip().startswith(("//", "*", "/*")):
                continue
            (explained if site_reason(lines, first, last) else unexplained).append(f"{rel(path)}:{first + 1}")
    return {"explained": len(explained), "unexplained": unexplained, "paths": swift_roots + kotlin_roots}


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
    exemptions = report["accessibility"]["ios_audit_exemptions"]
    failures += [f"iOS audit exemptions: {k} {exemptions[k]} is above the baseline {exemptions['baseline'][k]} in {rel(EXEMPTION_BASELINE)}"
                 for k in exemptions["above_baseline"]]
    failures += [f"iOS audit exemptions: {k} came down to {exemptions[k]}; lower the baseline in {rel(EXEMPTION_BASELINE)} to match"
                 for k in exemptions["below_baseline"]]
    if exemptions["unreadable"]:
        failures.append(f"iOS audit exemptions: {exemptions['unreadable']} not written with literal condition, type, element and limit")
    failures += [f"iOS audit exemption not in {rel(EXEMPTION_BASELINE)} (new or widened): {e}" for e in exemptions["not_in_baseline"]]
    failures += [f"iOS audit exemption gone or narrowed; update {rel(EXEMPTION_BASELINE)} to match: {e}" for e in exemptions["not_in_audit"]]
    failures += ratchet_raised(json.loads(EXEMPTION_BASELINE.read_text(encoding="utf-8")) if EXEMPTION_BASELINE.exists() else {})
    return failures


def baseline_raised(baseline: dict, base: dict) -> list[str]:
    """What [baseline] lets go that the base branch's [base] didn't: an exemption no base one covers, or more rules."""
    if "exemptions" not in base:
        # The base counted its exemptions only.
        return [f"iOS audit exemptions: the baseline's {k} rose from {base[k]} to {n} (it may only go down)"
                for k, n in (("entries", len(baseline.get("exemptions", []))),
                             ("without_named_element", sum(1 for e in baseline.get("exemptions", []) if not e["element"])))
                if k in base and n > base[k]]
    raised = [f"iOS audit exemptions: the baseline lets go more than the base's (it may only go down): {exemption_key(e)}"
              for e in uncovered(baseline.get("exemptions", []), base["exemptions"])]
    if baseline.get("rules", 0) > base.get("rules", 0):
        raised.append(f"iOS audit exemptions: the baseline's rules rose from {base.get('rules', 0)} to {baseline['rules']} (it may only go down)")
    return raised


def ratchet_raised(baseline: dict) -> list[str]:
    """The baseline itself may only go down: compared with the one on `--ratchet-ref <git ref>` (CI: the pull request's base)."""
    ref = next((a.split("=", 1)[1] for a in sys.argv if a.startswith("--ratchet-ref=")), None)
    if not ref:
        return []
    shown = subprocess.run(["git", "show", f"{ref}:{rel(EXEMPTION_BASELINE)}"], cwd=ROOT, capture_output=True, text=True)
    if shown.returncode != 0:
        return []  # the base has no baseline yet: this pull request starts it
    return baseline_raised(baseline, json.loads(shown.stdout))


def main() -> None:
    report = {
        "complexity": complexity(),
        "duplication": duplication(),
        "file_lengths": file_lengths(),
        "ios_strings": catalog("LabImporter/Localizable.xcstrings", used_by_android=android_uses_ios_keys()),
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
