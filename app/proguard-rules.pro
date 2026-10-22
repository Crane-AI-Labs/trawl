# R8 rules for the release build. Everything here is something reached by reflection or from native code, which R8
# cannot see. Verified on a phone: search, indexing (text, OCR, images) and thumbnails all still work (the research notes).

# Problem reports carry stack traces: keep file names and line numbers so the release build's frames can be read with its mapping.txt.
-keepattributes SourceFile,LineNumberTable

# LiteRT-LM: the Kotlin API wraps JNI; native code looks classes, fields and methods up by name.
-keep class com.google.ai.edge.litertlm.** { *; }
-keepclasseswithmembernames class * { native <methods>; }

# SQLite (bundled, with FTS5): JNI entry points and the driver classes the native library calls back into.
-keep class androidx.sqlite.driver.bundled.** { *; }

# ML Kit text recognition (on-device, bundled model). Its MlKitInitProvider builds a component graph by class identity
# (ComponentRegistrar -> Component<T> -> required Dependency<T>); renaming those classes crashed the first release build at
# launch with "Unsatisfied dependency for component" (found on the Infinix, 2026-10-15). Keep the whole ML Kit / component tree.
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_** { *; }
-keep class com.google.android.gms.tasks.** { *; }
-keep class com.google.firebase.components.** { *; }
-keep class * implements com.google.firebase.components.ComponentRegistrar { *; }
-keep class * implements com.google.mlkit.common.sdkinternal.* { *; }
-dontwarn com.google.mlkit.**

# pdfbox-android: loads fonts, glyph lists and codecs by name from its resources.
-keep class com.tom_roush.pdfbox.** { *; }
-dontwarn com.tom_roush.pdfbox.**
-dontwarn com.gemalto.jp2.**
-dontwarn org.bouncycastle.**

# WorkManager: workers are created by class name from its database. (Its AAR keeps ListenableWorker subclasses; this is a
# second line for ours.)
-keep class com.craneai.findr.engine.IndexWorker { *; }

# sherpa-onnx (speech to words, VAD, sound labels): its native code reads the Kotlin config classes' fields by name, and
# its AAR ships an empty proguard.txt, so R8 would rename them. Keep the whole package.
-keep class com.k2fsa.sherpa.onnx.** { *; }
-dontwarn com.k2fsa.sherpa.onnx.**

# ONNX Runtime (Java API, the quick-look image model): its JNI code builds OnnxTensor, OrtSession results and OrtException
# and reads their fields by class and member name.
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**
