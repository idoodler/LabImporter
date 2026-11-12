#!/bin/bash
# Prints the UDID of the one simulator the iOS accessibility audit runs on, here and in CI:
# an iPhone 17 Pro with iOS 27.0.
#
#   tools/quality/audit_simulator.sh [--create]
#
# The audit's exemptions and each screen's page count (AccessibilityAuditUITests,
# QualityScreens.swift, accessibility_exemptions_baseline.json) were measured on this device and
# OS: on another (the iPhone Duo's other layouts, another OS's rendering) the audit flags other
# texts and pages begin elsewhere, so it never runs anywhere else. Why this pair: the only iPhone
# and iOS both this Mac and CI's xcode-27 image (Xcode 27.1) can run — their iOS 27.1 runtimes
# run only the iPhone Duo, and the image has no iPhone 17 Pro until one is created.
#
# Fails, saying so, when there's no such simulator; --create makes one first when the runtime is
# installed (CI). Changing the pin means recalibrating the audit on the new device: every
# exemption checked against its screenshot, the page counts in the baseline updated.
set -e -o pipefail
NAME="iPhone 17 Pro"
DEVICE_TYPE="com.apple.CoreSimulator.SimDeviceType.iPhone-17-Pro"
OS="27.0"
RUNTIME="com.apple.CoreSimulator.SimRuntime.iOS-${OS//./-}"
CREATE=0
[ "${1:-}" = "--create" ] && CREATE=1

# Any simulator of this device type and OS renders the same. A "(Slim)" one (simslim, which only
# turns off background daemons the audit doesn't use) is preferred where it exists.
find_device() {
  xcrun simctl list devices available --json | python3 -c '
import json, sys
device_type, runtime = sys.argv[1], sys.argv[2]
devices = [d for d in json.load(sys.stdin)["devices"].get(runtime, []) if d.get("deviceTypeIdentifier") == device_type]
devices.sort(key=lambda d: "(Slim)" not in d["name"])
print(devices[0]["udid"] if devices else "")' "$DEVICE_TYPE" "$RUNTIME"
}

UDID=$(find_device)
if [ -z "$UDID" ] && [ $CREATE -eq 1 ]; then
  if ! xcrun simctl list runtimes available | grep -q "$RUNTIME"; then
    echo "audit_simulator.sh: iOS $OS isn't installed (runtime $RUNTIME), so there's no $NAME with iOS $OS to audit on" >&2
    exit 1
  fi
  UDID=$(xcrun simctl create "$NAME" "$DEVICE_TYPE" "$RUNTIME")
fi
if [ -z "$UDID" ]; then
  echo "audit_simulator.sh: no $NAME simulator with iOS $OS. The accessibility audit runs only there; create it with" >&2
  echo "  xcrun simctl create \"$NAME\" $DEVICE_TYPE $RUNTIME" >&2
  echo "(or run tools/quality/audit_simulator.sh --create)" >&2
  exit 1
fi
echo "$UDID"
