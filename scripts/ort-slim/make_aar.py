#!/usr/bin/env python3
"""Packs a slim ONNX Runtime build into an AAR the app can take from app/libs (D10 follow-up: quick look's APK cost).

  make_aar.py <out.aar> <onnxruntime.jar> <libonnxruntime.so> <libonnxruntime4j_jni.so> [--abi arm64-v8a] [--min-sdk 30]

The Java classes come from the same ORT tag's own build (the jar's native/ copies are dropped); the manifest has no
INTERNET permission and no telemetry provider, because the library is built with --no_telemetry. The libraries should
already be stripped.
"""
import argparse
import zipfile

MANIFEST = """<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="ai.onnxruntime" >

    <uses-sdk android:minSdkVersion="{min_sdk}" />

</manifest>
"""

ap = argparse.ArgumentParser()
ap.add_argument("out")
ap.add_argument("jar")
ap.add_argument("lib")
ap.add_argument("jni")
ap.add_argument("--abi", default="arm64-v8a")
ap.add_argument("--min-sdk", default="30")
a = ap.parse_args()

classes = a.out + ".classes.jar"
with zipfile.ZipFile(a.jar) as src, zipfile.ZipFile(classes, "w", zipfile.ZIP_DEFLATED) as dst:
    for n in src.namelist():
        if n.endswith(".class"):
            dst.writestr(n, src.read(n))
with zipfile.ZipFile(a.out, "w", zipfile.ZIP_DEFLATED) as z:
    z.writestr("AndroidManifest.xml", MANIFEST.format(min_sdk=a.min_sdk))
    z.write(classes, "classes.jar")
    z.writestr("R.txt", "")
    z.write(a.lib, f"jni/{a.abi}/libonnxruntime.so")
    z.write(a.jni, f"jni/{a.abi}/libonnxruntime4j_jni.so")
import os
os.remove(classes)
print(a.out, os.path.getsize(a.out))
