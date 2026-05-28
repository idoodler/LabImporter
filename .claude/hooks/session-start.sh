#!/bin/bash
# SessionStart hook: install SwiftLint so the pre-push lint hook can run.
# Only runs in Claude Code on the web (Linux containers); local macOS dev
# installs SwiftLint via brew.

set -euo pipefail

if [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
    exit 0
fi

SWIFTLINT_VERSION="0.63.2"
SWIFTLINT_BIN="/usr/local/bin/swiftlint"

if [ -x "$SWIFTLINT_BIN" ] \
    && "$SWIFTLINT_BIN" --version 2>/dev/null | grep -qx "$SWIFTLINT_VERSION"; then
    echo "SwiftLint $SWIFTLINT_VERSION already installed; skipping."
    exit 0
fi

ARCH=$(uname -m)
case "$ARCH" in
    x86_64) ASSET="swiftlint_linux_amd64.zip" ;;
    aarch64|arm64) ASSET="swiftlint_linux_arm64.zip" ;;
    *)
        echo "Unsupported architecture '$ARCH'; skipping SwiftLint install." >&2
        exit 0
        ;;
esac

URL="https://github.com/realm/SwiftLint/releases/download/${SWIFTLINT_VERSION}/${ASSET}"
TMPDIR=$(mktemp -d)
trap 'rm -rf "$TMPDIR"' EXIT

echo "Installing SwiftLint $SWIFTLINT_VERSION ($ARCH)…"
curl -fsSL -o "$TMPDIR/swiftlint.zip" "$URL"
unzip -o -q "$TMPDIR/swiftlint.zip" -d "$TMPDIR"

SUDO=""
if [ "$(id -u)" != "0" ] && command -v sudo >/dev/null 2>&1; then
    SUDO="sudo"
fi
$SUDO install -m 0755 "$TMPDIR/swiftlint-static" "$SWIFTLINT_BIN"

"$SWIFTLINT_BIN" --version
echo "SwiftLint installed at $SWIFTLINT_BIN"
