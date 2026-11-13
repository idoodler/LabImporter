#!/bin/bash
# Collects every measure of docs/quality/RUBRIC.md that a script can, then writes
# docs/quality/SCORECARD.md from them. Each step writes JSON to build/quality/;
# a step that fails is recorded as failed and the next one still runs.
#
#   tools/quality/collect.sh [--ios] [--mutation] [--simulator <udid-or-name>]
#
#   (always)    static_metrics.py (+ SwiftLint when installed), the Gradle tests
#               with Kover coverage, Android lintRelease, the evaluation replay
#               with its planted errors, and the same for the held-out set (reported only)
#   --ios       the iOS unit and UI tests with coverage, on --simulator (default: the
#               audit's), then the accessibility audit on its pinned simulator
#               (tools/quality/audit_simulator.sh; the step fails when there's none),
#               `swiftlint analyze` on the first build's log, the privacy manifests
#               in the built .app and .appex, and both screenshot matrices again
#               (build/quality/ios-screens, android-screens, each with the commit it shows)
#   --mutation  PIT mutation testing of shared/ (tools/quality/mutation.sh)
#
# Steps run one at a time, and Gradle is stopped before the simulator starts: on an
# 8 GB Mac the two don't fit side by side. Needs a JDK 17+ (JAVA_HOME) and, for
# Android, ANDROID_HOME. Python: standard library, plus lizard from requirements.txt
# (installed here into build/quality/venv).
set -u
cd "$(dirname "$0")/../.."
# The SDK where Android Studio installs it, when the shell doesn't say (local.properties
# isn't committed, so Gradle can't find it otherwise).
if [ -z "${ANDROID_HOME:-}" ] && [ -d "$HOME/Library/Android/sdk" ]; then
  export ANDROID_HOME="$HOME/Library/Android/sdk"
fi
ROOT=$(pwd)
OUT=build/quality
LOGS=$OUT/logs
IOS=0
MUTATION=0
SIMULATOR="${IOS_SIMULATOR:-}"
while [ $# -gt 0 ]; do
  case "$1" in
    --ios) IOS=1 ;;
    --mutation) MUTATION=1 ;;
    --simulator) SIMULATOR="$2"; shift ;;
    *) echo "unknown option $1"; exit 2 ;;
  esac
  shift
done

# Inputs of an earlier run must never pass for this one's.
rm -rf "$OUT"/*.json "$LOGS"
mkdir -p "$LOGS"
FAILED=()
# The commit this run measures, and whether anything but docs/quality/SCORECARD.md (an earlier run's output)
# was uncommitted: a committed scorecard's run is clean, and so is a second run at its commit.
python3 tools/quality/scorecard.py --record-run "$OUT/run.json"

# step <name> <command…>: runs it, logs to build/quality/logs/<name>.log, records the outcome.
step() {
  local name=$1; shift
  local start=$(date +%s)
  echo "==> $name"
  "$@" > "$LOGS/$name.log" 2>&1
  local code=$?
  local seconds=$(( $(date +%s) - start ))
  echo "    exit $code after ${seconds}s (log: $LOGS/$name.log)"
  python3 -c 'import json,sys; json.dump({"step": sys.argv[1], "exit_code": int(sys.argv[2]), "seconds": int(sys.argv[3])}, open(sys.argv[4], "w"))' \
    "$name" "$code" "$seconds" "$OUT/step-$name.json"
  [ $code -eq 0 ] || FAILED+=("$name")
  return $code
}

# --- 1. Static metrics -------------------------------------------------------
lizard_env() {
  if [ ! -x "$OUT/venv/bin/lizard" ]; then
    python3 -m venv "$OUT/venv" && "$OUT/venv/bin/pip" install --quiet -r tools/quality/requirements.txt
  fi
}
static() {
  # The scripts' own tests first: a gate that measures the wrong thing passes for the wrong reason.
  lizard_env && "$OUT/venv/bin/python3" -m unittest discover -s tools/quality -p 'test_*.py' \
    && "$OUT/venv/bin/python3" tools/quality/static_metrics.py > "$OUT/static.json"
}
step static static
swiftlint_lint() {
  if ! command -v swiftlint > /dev/null; then echo "swiftlint not installed"; return 1; fi
  swiftlint lint --strict --quiet --reporter json > "$LOGS/swiftlint.json"
  local code=$?
  python3 -c 'import json,sys; v=json.load(open(sys.argv[1])); json.dump({"violations": len(v), "errors": sum(1 for x in v if x.get("severity")=="Error"), "exit_code": int(sys.argv[3])}, open(sys.argv[2], "w"))' \
    "$LOGS/swiftlint.json" "$OUT/swiftlint.json" "$code"
  return $code
}
step swiftlint swiftlint_lint

# --- 2. Gradle tests with Kover ----------------------------------------------
gradle_tests() {
  # reports.py reads what Gradle leaves in build/: a run that fails before a Test task (a test
  # that doesn't compile) would leave the last run's results to be scored as this one's.
  rm -rf shared/build/test-results/jvmTest androidApp/build/test-results/testDebugUnitTest \
    shared/build/reports/kover androidApp/build/reports/kover
  # `--rerun` on each test task: the build cache (gradle.properties) stays on for compiling, but a test
  # result restored from it, or one Gradle thinks up to date, is an earlier run's (consistency.sh's
  # "three runs in a row" would run the tests once). `fresh` fails the step if one didn't run now.
  ./gradlew --console=plain --continue \
    :shared:jvmTest --rerun :shared:koverXmlReportJvm \
    :androidApp:testDebugUnitTest --rerun :androidApp:koverXmlReportDebug
  local code=$?
  python3 tools/quality/reports.py fresh "$LOGS/gradle-tests.log" :shared:jvmTest :androidApp:testDebugUnitTest || return 1
  return $code
}
step gradle-tests gradle_tests
python3 tools/quality/reports.py junit shared=shared/build/test-results/jvmTest android=androidApp/build/test-results/testDebugUnitTest > "$OUT/tests.json"
python3 tools/quality/reports.py kover shared=shared/build/reports/kover/reportJvm.xml android=androidApp/build/reports/kover/reportDebug.xml > "$OUT/coverage.json"
python3 tools/quality/reports.py warnings "$LOGS/gradle-tests.log" > "$OUT/kotlin_warnings.json"

# --- 3. Android lint ---------------------------------------------------------
rm -f androidApp/build/reports/lint-results-release.*
# The analysis reruns (an up-to-date or cached one would report the last run's findings); `fresh` checks it did.
android_lint() {
  ./gradlew --console=plain :androidApp:lintAnalyzeRelease --rerun :androidApp:lintRelease
  local code=$?
  python3 tools/quality/reports.py fresh "$LOGS/android-lint.log" :androidApp:lintAnalyzeRelease :androidApp:lintRelease || return 1
  return $code
}
step android-lint android_lint
python3 tools/quality/reports.py lint androidApp/build/reports/lint-results-release.xml > "$OUT/lint.json"

# --- 4. Evaluation replay ----------------------------------------------------
# With the planted errors (evaluation/…/PlantedErrors.kt) and the evaluation's own tests, which rerun
# like the others. The replay itself (`run`) has no outputs, so Gradle runs it every time; `fresh` checks both.
evaluation() {
  ./gradlew --console=plain :evaluation:test --rerun :evaluation:run --args=replay "-Psummary=$OUT/evaluation.json"
  local code=$?
  python3 tools/quality/reports.py fresh "$LOGS/evaluation.log" :evaluation:test :evaluation:run || return 1
  return $code
}
step evaluation evaluation
# The held-out set (evaluation/heldout): reported beside Extraction safety, never scored or gated, and never
# used to change the Review check or the LOINC ranking (evaluation/README.md, "Held-out set").
evaluation_heldout() {
  ./gradlew --console=plain :evaluation:run --args=replay -Pheldout=true "-Psummary=$OUT/evaluation_heldout.json"
  local code=$?
  python3 tools/quality/reports.py fresh "$LOGS/evaluation-heldout.log" :evaluation:run || return 1
  return $code
}
step evaluation-heldout evaluation_heldout

# --- Screenshot matrices (with --ios) -------------------------------------------
# What the panel judges (Screenshot matrix, Heuristic panel): taken again on every --ios run, with the commit
# they were taken at beside them (`commit.json`, as run.json). scorecard.py shows the panel's measures as
# "not run" when that isn't the measured commit, so screenshots of older code never stand in for this one's.
# screens_start <folder>: empties it and records the commit; screens_finish <folder> <count>: records the
# tree again at the end, or removes the record when the step took no screenshots.
screens_start() {
  rm -rf "$1" && mkdir -p "$1" && python3 tools/quality/scorecard.py --record-run "$1/commit.json"
}
screens_finish() {
  local count
  count=$(find "$1" -name '*.png' | wc -l | tr -d ' ')
  echo "$count screenshots in $1"
  if [ "$2" -ne 0 ] || [ "$count" -eq 0 ]; then rm -f "$1/commit.json"; return 1; fi
  python3 tools/quality/scorecard.py --finish-run "$1/commit.json"
}
android_screens() {
  screens_start "$OUT/android-screens" || return 1
  ./gradlew --console=plain :androidApp:testDebugUnitTest --tests '*.ScreenMatrixTest' --rerun "-PqualityScreens=$ROOT/$OUT/android-screens"
  screens_finish "$OUT/android-screens" $?
}
[ $IOS -eq 1 ] && step android-screens android_screens

# --- 5. iOS (optional) -------------------------------------------------------
if [ $IOS -eq 1 ]; then
  ./gradlew --stop > /dev/null 2>&1
  # The accessibility audit runs on one pinned simulator only (its exemptions and page counts were
  # measured there): without it, the audit's step fails and says how to create it.
  AUDIT_SIMULATOR=$(tools/quality/audit_simulator.sh 2> "$LOGS/audit-simulator.log") || cat "$LOGS/audit-simulator.log"
  # The other tests run there too unless --simulator names another: one simulator at a time on 8 GB.
  [ -n "$SIMULATOR" ] || SIMULATOR=$AUDIT_SIMULATOR
  boot() {
    case "$1" in
      *-*-*-*-*) xcrun simctl boot "$1" > /dev/null 2>&1; xcrun simctl bootstatus "$1" -b > /dev/null 2>&1 ;;
    esac
  }
  ios_tests() {
    [ -n "$SIMULATOR" ] || { echo "no simulator: pass --simulator, or create the audit's (see $LOGS/audit-simulator.log)"; return 1; }
    boot "$SIMULATOR"
    IOS_CLEAN=1 IOS_BUILD_LOG="$LOGS/xcodebuild.log" IOS_RESULT_BUNDLE="$OUT/ios-tests.xcresult" tools/ios_test.sh all "$SIMULATOR"
  }
  step ios-tests ios_tests
  ios_audit() {
    [ -n "$AUDIT_SIMULATOR" ] || { cat "$LOGS/audit-simulator.log"; return 1; }
    [ "$SIMULATOR" = "$AUDIT_SIMULATOR" ] || xcrun simctl shutdown "$SIMULATOR" > /dev/null 2>&1
    boot "$AUDIT_SIMULATOR"
    IOS_RESULT_BUNDLE="$OUT/ios-audit.xcresult" tools/ios_test.sh audit
  }
  step ios-audit ios_audit
  # On the simulator that's booted now (the audit's, unless there's none), with the build ios-tests left.
  ios_screens() {
    local device=${AUDIT_SIMULATOR:-$SIMULATOR}
    [ -n "$device" ] || { echo "no simulator"; return 1; }
    screens_start "$OUT/ios-screens" || return 1
    boot "$device"
    QUALITY_SCREENSHOT_DIR="$ROOT/$OUT/ios-screens" tools/ios_test.sh screens "$device"
    screens_finish "$OUT/ios-screens" $?
  }
  step ios-screens ios_screens
  # One result bundle for the scorecard and coverage: both runs' tests.
  rm -rf "$OUT/ios.xcresult"
  if [ -d "$OUT/ios-tests.xcresult" ] && [ -d "$OUT/ios-audit.xcresult" ]; then
    xcrun xcresulttool merge --output-path "$OUT/ios.xcresult" "$OUT/ios-tests.xcresult" "$OUT/ios-audit.xcresult" > /dev/null
  elif [ -d "$OUT/ios-tests.xcresult" ]; then
    cp -R "$OUT/ios-tests.xcresult" "$OUT/ios.xcresult"
  fi
  python3 tools/quality/reports.py xcresult "$OUT/ios.xcresult" > "$OUT/ios.json"
  # The exemptions the accessibility audit applied and measured on this run, from its AUDIT lines.
  python3 tools/quality/reports.py audit "$LOGS/ios-audit.log" > "$OUT/audit.json"
  python3 tools/quality/ios_coverage.py "$OUT/ios.xcresult" > "$OUT/ios_coverage.json" 2> "$LOGS/ios-coverage.log" || rm -f "$OUT/ios_coverage.json"
  # What App Store Connect reads: the manifests inside the built bundles, not the sources.
  python3 - "$OUT/ios_bundles.json" build/ios-dd/Build/Products/Debug-iphonesimulator/LabImporter.app <<'EOF'
import json, plistlib, sys
from pathlib import Path
app = Path(sys.argv[2])
bundles = {"LabImporter.app": app, "LabImporterShare.appex": app / "PlugIns/LabImporterShare.appex"}
result = {}
for name, path in bundles.items():
    manifest = path / "PrivacyInfo.xcprivacy"
    entry = {"built": path.exists(), "privacy_manifest": manifest.exists()}
    if manifest.exists():
        data = plistlib.loads(manifest.read_bytes())
        entry["accessed_api_types"] = sorted(a["NSPrivacyAccessedAPIType"] for a in data.get("NSPrivacyAccessedAPITypes", []))
        entry["tracking"] = data.get("NSPrivacyTracking")
    result[name] = entry
json.dump(result, open(sys.argv[1], "w"), indent=2)
EOF
  swiftlint_analyze() {
    if ! command -v swiftlint > /dev/null; then echo "swiftlint not installed"; return 1; fi
    swiftlint analyze --strict --quiet --reporter json --compiler-log-path "$LOGS/xcodebuild.log" > "$LOGS/swiftlint-analyze.json"
    local code=$?
    python3 -c 'import json,sys; v=json.load(open(sys.argv[1])); json.dump({"violations": len(v), "exit_code": int(sys.argv[3]), "rules": sorted({x["rule_id"] for x in v})}, open(sys.argv[2], "w"))' \
      "$LOGS/swiftlint-analyze.json" "$OUT/swiftlint_analyze.json" "$code"
    return $code
  }
  [ -s "$LOGS/xcodebuild.log" ] && step swiftlint-analyze swiftlint_analyze
fi

# --- 6. Mutation testing (optional) ------------------------------------------
if [ $MUTATION -eq 1 ]; then
  step mutation tools/quality/mutation.sh
fi

# --- Scorecard ---------------------------------------------------------------
# The tree again, now the steps are done: an edit made while they ran leaves the run unclean.
python3 tools/quality/scorecard.py --finish-run "$OUT/run.json"
python3 tools/quality/scorecard.py --input "$OUT" --output docs/quality/SCORECARD.md
echo "Scorecard: $ROOT/docs/quality/SCORECARD.md"
if [ ${#FAILED[@]} -gt 0 ]; then
  echo "Failed steps: ${FAILED[*]}"
  exit 1
fi
