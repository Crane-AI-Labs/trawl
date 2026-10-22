#!/bin/bash
# jpackage writes Contents/app/.package into the .app AFTER it has ad-hoc signed it, so the seal no longer matches and
# `codesign --verify --deep --strict` fails on the app inside the .dmg. This re-signs the app inside the finished .dmg (last, so nothing
# is added after the seal) and rewrites the .dmg. Ad-hoc only: there is no Developer ID, so the build is not notarised.
set -euo pipefail
DMG="$1"
W="$(mktemp -d /tmp/trawl-reseal.XXXXXX)"
MNT="$W/mnt"; mkdir -p "$MNT"
cleanup() { hdiutil detach "$MNT" -force >/dev/null 2>&1 || true; rm -rf "$W"; }
trap cleanup EXIT
hdiutil convert "$DMG" -format UDRW -o "$W/rw.dmg" -quiet
hdiutil attach "$W/rw.dmg" -nobrowse -noverify -mountpoint "$MNT" -quiet
APP="$(ls -d "$MNT"/*.app | head -1)"
# Same entitlements jpackage signed with (the JVM needs them; without disable-library-validation the app cannot load its own ad-hoc libjli).
# No --deep: the nested runtime and libraries keep the signatures jpackage gave them; only the outer seal is redone, to cover .package.
codesign --force --options runtime --entitlements "$(dirname "$0")/trawl.entitlements" --sign - "$APP"
codesign --verify --deep --strict --verbose=2 "$APP"
sync
hdiutil detach "$MNT" -quiet
rm -f "$W/out.dmg"
hdiutil convert "$W/rw.dmg" -format UDZO -imagekey zlib-level=9 -o "$W/out.dmg" -quiet
mv -f "$W/out.dmg" "$DMG"
echo "resealed $DMG"
