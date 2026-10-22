#!/bin/bash
# Installs a side-by-side copy of Findr (applicationIdSuffix .quick) and makes it ready to search, without touching the real app:
# All files access granted, the embedding model copied into its private files, onboarding marked done. A debug build is
# needed for the run-as steps; a release build with the same id can be installed over it afterwards (`adb install -r` keeps data).
#   ANDROID_SERIAL=<serial> scripts/phone-lock.sh run <who> scripts/quick-setup.sh
set -uo pipefail
cd "$(dirname "$0")/.."
: "${ANDROID_SERIAL:?name the phone}"
ADB="adb -s $ANDROID_SERIAL"
PKG=com.craneai.findr.quick
MODEL=${MODEL:-bench-data/models/embeddinggemma-2-text-vision-440m.litertlm}
APK=app/build/outputs/apk/debug/app-debug.apk

$ADB install -r -g "$APK" | tail -1
$ADB shell appops set $PKG MANAGE_EXTERNAL_STORAGE allow
$ADB shell pm grant $PKG android.permission.POST_NOTIFICATIONS 2>/dev/null
if ! $ADB shell run-as $PKG ls files/models/embeddinggemma-2-text-vision-440m.litertlm >/dev/null 2>&1; then
  $ADB push "$MODEL" /data/local/tmp/quick-model.litertlm | tail -1
  $ADB shell "run-as $PKG sh -c 'mkdir -p files/models && cp /data/local/tmp/quick-model.litertlm files/models/embeddinggemma-2-text-vision-440m.litertlm'"
  $ADB shell rm -f /data/local/tmp/quick-model.litertlm
fi
$ADB shell "run-as $PKG sh -c 'mkdir -p shared_prefs && printf \"<?xml version=\\\"1.0\\\" encoding=\\\"utf-8\\\" standalone=\\\"yes\\\"?>\n<map><boolean name=\\\"onboarded\\\" value=\\\"true\\\" /></map>\n\" > shared_prefs/findr_ui.xml'"
$ADB shell run-as $PKG ls -l files/models shared_prefs
