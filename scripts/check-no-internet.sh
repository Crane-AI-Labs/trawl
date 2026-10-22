#!/bin/bash
# Findr never goes online: fails when a built APK declares android.permission.INTERNET (a library merged it back in).
#   scripts/check-no-internet.sh [apk]        (default: the release APK)
# It also fails when it cannot read the APK, or when the storage permission every Trawl APK declares is not found: a check that
# could not read the manifest must not report "ok".
set -uo pipefail
cd "$(dirname "$0")/.."
APK=${1:-app/build/outputs/apk/release/app-release.apk}
SDK=${ANDROID_HOME:-$HOME/Library/Android/sdk}
AAPT2=$(ls -d "$SDK"/build-tools/*/aapt2 2>/dev/null | sort -V | tail -1)
[ -f "$APK" ] || { echo "FAIL: $APK not found"; exit 1; }
[ -n "$AAPT2" ] || { echo "FAIL: aapt2 not found under $SDK/build-tools"; exit 1; }
PERMS=$("$AAPT2" dump permissions "$APK") || { echo "FAIL: aapt2 could not read $APK"; exit 1; }
echo "$PERMS" | grep -q "android.permission.MANAGE_EXTERNAL_STORAGE" || { echo "FAIL: $APK: manifest not read (storage permission not found)"; exit 1; }
if echo "$PERMS" | grep -q "android.permission.INTERNET"; then
  echo "FAIL: $APK declares android.permission.INTERNET"
  exit 1
fi
echo "ok: $APK has no INTERNET permission"
