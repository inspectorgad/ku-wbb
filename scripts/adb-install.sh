#!/usr/bin/env bash
# Installs the latest KU WBB APK onto a USB-connected Android phone via adb.
#
# Android's Advanced Protection mode blocks browser/sideload APK installs but
# allows installs from a computer over ADB, so this is the supported path.
#
# One-time phone setup:
#   1. Settings > About phone > tap "Build number" 7 times (enables Developer options)
#   2. Settings > System > Developer options > enable "USB debugging"
#   3. Plug the phone into this computer and accept the "Allow USB debugging?" prompt
#
# Requires adb (Android platform-tools): https://developer.android.com/tools/releases/platform-tools
# On macOS: brew install android-platform-tools
set -euo pipefail

APK_URL="https://github.com/inspectorgad/ku-wbb/releases/latest/download/app-debug.apk"
APK_PATH="$(mktemp -d)/ku-wbb.apk"

if ! command -v adb > /dev/null; then
  echo "adb not found. Install Android platform-tools first:"
  echo "  macOS:   brew install android-platform-tools"
  echo "  Windows/Linux: https://developer.android.com/tools/releases/platform-tools"
  exit 1
fi

echo "Downloading latest KU WBB APK..."
curl -fsSL -o "$APK_PATH" "$APK_URL"

echo "Waiting for a connected device (accept the USB debugging prompt on the phone)..."
adb wait-for-device

echo "Installing..."
# -r reinstalls over an existing copy, keeping the app's data.
adb install -r "$APK_PATH"

echo "Done — KU WBB is installed. You can unplug the phone."
