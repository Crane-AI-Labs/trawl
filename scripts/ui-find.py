#!/usr/bin/env python3
"""Print the centre "x y" of the first node in a uiautomator dump whose text or content-desc contains the needle.
A needle that starts with "=" must match the whole text or content-desc instead (to hit a "Remove" button, not a "Remove X?" title)."""
import re
import sys
import xml.etree.ElementTree as ET

path, needle = sys.argv[1], sys.argv[2].lower()
exact = needle.startswith("=")
if exact:
    needle = needle[1:]


def hit(s):
    s = (s or "").lower()
    return s == needle if exact else needle in s


for n in ET.parse(path).iter("node"):
    if hit(n.get("text")) or hit(n.get("content-desc")):
        a = list(map(int, re.findall(r"\d+", n.get("bounds"))))
        print((a[0] + a[2]) // 2, (a[1] + a[3]) // 2)
        break
