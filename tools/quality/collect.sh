#!/bin/bash
# Collects every measure of docs/quality/RUBRIC.md that a script can, then writes
# docs/quality/SCORECARD.md from them. Each step writes JSON to build/quality/;
# a step that fails is recorded as failed and the next one still runs.
#
#   tools/quality/collect.sh [--ios] [--mutation] [--simulator <udid-or-name>]
#
#   (always)    static_metrics.py (+ SwiftLint when installed), the Gradle tests
#               with Kover coverage, Android lintRelease, the evaluation replay
#               with its planted errors
#   --ios       the iOS unit and UI tests with coverage (the accessibility audit
#               is one of the UI test suites), `swiftlint analyze` on that build's
#               log, and the privacy manifests in the built .app and .appex
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
SIMULATOR="${IOS_SIMULATOR:-iPhone 17 Pro}"
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
  ./gradlew --console=plain --continue \
    :shared:jvmTest :shared:koverXmlReportJvm \
    :androidApp:testDebugUnitTest :androidApp:koverXmlReportDebug
}
step gradle-tests gradle_tests
python3 tools/quality/reports.py junit shared=shared/build/test-results/jvmTest android=androidApp/build/test-results/testDebugUnitTest > "$OUT/tests.json"
python3 tools/quality/reports.py kover shared=shared/build/reports/kover/reportJvm.xml android=androidApp/build/reports/kover/reportDebug.xml > "$OUT/coverage.json"
python3 tools/quality/reports.py warnings "$LOGS/gradle-tests.log" > "$OUT/kotlin_warnings.json"

# --- 3. Android lint ---------------------------------------------------------
rm -f androidApp/build/reports/lint-results-release.*
step android-lint ./gradlew --console=plain :androidApp:lintRelease
python3 tools/quality/reports.py lint androidApp/build/reports/lint-results-release.xml > "$OUT/lint.json"

# --- 4. Evaluation replay ----------------------------------------------------
# With the planted errors (evaluation/…/PlantedErrors.kt) and the evaluation's own tests.
step evaluation ./gradlew --console=plain :evaluation:test :evaluation:run --args=replay "-Psummary=$OUT/evaluation.json"

# --- 5. iOS (optional) -------------------------------------------------------
if [ $IOS -eq 1 ]; then
  ./gradlew --stop > /dev/null 2>&1
  case "$SIMULATOR" in
    *-*-*-*-*) xcrun simctl boot "$SIMULATOR" > /dev/null 2>&1; xcrun simctl bootstatus "$SIMULATOR" -b > /dev/null 2>&1 ;;
  esac
  ios_tests() {
    IOS_CLEAN=1 IOS_BUILD_LOG="$LOGS/xcodebuild.log" IOS_RESULT_BUNDLE="$OUT/ios.xcresult" tools/ios_test.sh all "$SIMULATOR"
  }
  step ios-tests ios_tests
  python3 tools/quality/reports.py xcresult "$OUT/ios.xcresult" > "$OUT/ios.json"
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
python3 tools/quality/scorecard.py --input "$OUT" --output docs/quality/SCORECARD.md
echo "Scorecard: $ROOT/docs/quality/SCORECARD.md"
if [ ${#FAILED[@]} -gt 0 ]; then
  echo "Failed steps: ${FAILED[*]}"
  exit 1
fi
