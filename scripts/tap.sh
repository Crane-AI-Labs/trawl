#!/bin/bash
# Taps the on-screen element whose text (or content description) contains $1, via a UI Automator dump.
#   scripts/tap.sh "Start Findr"
ADB="adb ${ANDROID_SERIAL:+-s $ANDROID_SERIAL}"
$ADB shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
$ADB shell cat /sdcard/ui.xml | python3 -c "
import sys, re, xml.etree.ElementTree as ET
want = sys.argv[1].lower()
root = ET.fromstring(sys.stdin.read())
for n in root.iter('node'):
    label = (n.get('text') or '') + ' ' + (n.get('content-desc') or '')
    if want in label.lower():
        x1, y1, x2, y2 = map(int, re.findall(r'\d+', n.get('bounds')))
        print((x1 + x2) // 2, (y1 + y2) // 2); break
else:
    sys.exit('not on screen: ' + sys.argv[1])
" "$1" | { read x y && $ADB shell input tap $x $y && echo "tapped '$1' at $x,$y"; }
