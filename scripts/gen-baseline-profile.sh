#!/usr/bin/env bash
# Generates app/src/main/generated/baselineProfiles/ on ONE named phone, from a brand-new install of a side clone.
# Run it under the phone lock:  ANDROID_SERIAL=<serial> scripts/phone-lock.sh run bpgen scripts/gen-baseline-profile.sh
set -u
: "${ANDROID_SERIAL:?name the phone with ANDROID_SERIAL}"
cd "$(dirname "$0")/.."
SUFFIX=.bpgen
APP=com.craneai.findr$SUFFIX
TEST=com.craneai.findr.baselineprofile
# Start from nothing: the first iteration walks onboarding for real.
adb uninstall "$APP" >/dev/null 2>&1
adb uninstall "$TEST" >/dev/null 2>&1
./gradlew :app:generateReleaseBaselineProfile -PappIdSuffix=$SUFFIX
rc=$?
# Never leave clones behind.
adb uninstall "$APP" >/dev/null 2>&1
adb uninstall "$TEST" >/dev/null 2>&1
exit $rc
