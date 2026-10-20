#!/bin/sh
# Runs the iOS test bundles on a simulator in a fixed language/region, so
# results never depend on how the machine's simulator is configured.
#   tools/ios_test.sh [unit|ui|all] [simulator-udid-or-name]
set -e
cd "$(dirname "$0")/.."
WHICH="${1:-all}"
DEVICE="${2:-iPhone 17 Pro}"
case "$DEVICE" in
  *-*-*-*-*) DESTINATION="id=$DEVICE" ;;
  *) DESTINATION="platform=iOS Simulator,name=$DEVICE" ;;
esac
ONLY=""
[ "$WHICH" = "unit" ] && ONLY="-only-testing:LabImporterTests"
[ "$WHICH" = "ui" ] && ONLY="-only-testing:LabImporterUITests"
exec xcodebuild test \
  -project LabImporter.xcodeproj -scheme LabImporter \
  -destination "$DESTINATION" $ONLY \
  -testLanguage en -testRegion en_US \
  -collect-test-diagnostics never \
  -derivedDataPath build/ios-dd \
  CODE_SIGNING_ALLOWED=NO
