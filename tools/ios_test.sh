#!/bin/bash
# Runs the iOS test bundles on a simulator in a fixed language/region, so
# results never depend on how the machine's simulator is configured.
#   tools/ios_test.sh [unit|ui|all] [simulator-udid-or-name]
#   tools/ios_test.sh audit
#
# `ui` and `all` leave out the accessibility audit (AccessibilityAuditUITests): its exemptions
# and page counts hold on one device and OS only, so `audit` runs it there and nowhere else —
# the simulator tools/quality/audit_simulator.sh names (it fails when there's none).
#
# Optional, for the quality scripts and CI (all paths relative to the repo root):
#   IOS_BUILD_LOG=build/xcodebuild.log   also writes xcodebuild's output there:
#                                         `swiftlint analyze --compiler-log-path` reads it
#   IOS_RESULT_BUNDLE=build/ios.xcresult  keeps the result bundle, with code coverage
#   IOS_CLEAN=1                           cleans first, so the log holds every compile
#                                         command (analyze only checks files it sees compiled)
set -e -o pipefail
cd "$(dirname "$0")/.."
WHICH="${1:-all}"
AUDIT=LabImporterUITests/AccessibilityAuditUITests
if [ "$WHICH" = "audit" ]; then
  [ -z "${2:-}" ] || { echo "ios_test.sh: the audit runs on its pinned simulator only (tools/quality/audit_simulator.sh)" >&2; exit 2; }
  DEVICE=$(tools/quality/audit_simulator.sh)
else
  DEVICE="${2:-iPhone 17 Pro}"
fi
case "$DEVICE" in
  *-*-*-*-*) DESTINATION="id=$DEVICE" ;;
  *) DESTINATION="platform=iOS Simulator,name=$DEVICE" ;;
esac
ARGS=()
case "$WHICH" in
  unit) ARGS+=(-only-testing:LabImporterTests) ;;
  ui) ARGS+=(-only-testing:LabImporterUITests "-skip-testing:$AUDIT") ;;
  all) ARGS+=("-skip-testing:$AUDIT") ;;
  audit) ARGS+=("-only-testing:$AUDIT") ;;
  *) echo "ios_test.sh: unknown suite $WHICH (unit, ui, all or audit)" >&2; exit 2 ;;
esac
if [ -n "$IOS_RESULT_BUNDLE" ]; then
  rm -rf "$IOS_RESULT_BUNDLE"
  ARGS+=(-resultBundlePath "$IOS_RESULT_BUNDLE" -enableCodeCoverage YES)
fi
ACTIONS=(test)
[ "$IOS_CLEAN" = "1" ] && ACTIONS=(clean test)
run() {
  xcodebuild "${ACTIONS[@]}" \
    -project LabImporter.xcodeproj -scheme LabImporter \
    -destination "$DESTINATION" "${ARGS[@]}" \
    -testLanguage en -testRegion en_US \
    -collect-test-diagnostics never \
    -derivedDataPath build/ios-dd \
    CODE_SIGNING_ALLOWED=NO
}
if [ -n "$IOS_BUILD_LOG" ]; then
  mkdir -p "$(dirname "$IOS_BUILD_LOG")"
  run 2>&1 | tee "$IOS_BUILD_LOG"
else
  run
fi
