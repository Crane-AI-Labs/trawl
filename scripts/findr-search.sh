#!/bin/bash
# Types a query into Findr's search box on the phone and screenshots the results, refusing to type anywhere else.
#   scripts/findr-search.sh "<query>" <screenshot.png>
ADB="adb ${ANDROID_SERIAL:+-s $ANDROID_SERIAL}"
focus() { $ADB shell dumpsys window | grep -E 'mCurrentFocus' | grep -q 'com.craneai.findr/'; }
$ADB shell input keyevent 3; sleep 0.5   # home, then bring Findr up fresh
$ADB shell am start -n com.craneai.findr/.MainActivity >/dev/null
for _ in $(seq 1 20); do focus && break; sleep 0.5; done   # a cold start after an install takes a few seconds
focus || { echo "Findr is not in the foreground; not typing"; exit 1; }
sleep 1
# The search field: an EditText if one is showing, else the "Search" bar at the bottom.
read x y < <($ADB shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; $ADB shell cat /sdcard/ui.xml | python3 -c "
import sys, re, xml.etree.ElementTree as ET
nodes = list(ET.fromstring(sys.stdin.read()).iter('node'))
pick = [n for n in nodes if n.get('class','').endswith('EditText')] or [n for n in nodes if (n.get('text') or '').startswith('Search')]
x1, y1, x2, y2 = map(int, re.findall(r'\d+', pick[0].get('bounds'))); print((x1+x2)//2, (y1+y2)//2)")
$ADB shell input tap $x $y; sleep 0.8
focus || { echo "focus left Findr; not typing"; exit 1; }
$ADB shell input keyevent 123; for i in $(seq 1 40); do $ADB shell input keyevent 67; done   # end, then clear
$ADB shell input text "$(printf '%s' "$1" | sed 's/ /%s/g')"; sleep 4
$ADB shell input keyevent 111; sleep 0.3   # escape: hides the keyboard without leaving the app
focus && $ADB exec-out screencap -p > "$2" && echo "captured $2"
