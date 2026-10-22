# Third-party notices

Trawl itself is licensed under the Apache License, Version 2.0 (`LICENSE`; `NOTICE` carries the notices that go with it). It is built on the work below. One row per component: its version in this app, its licence, where it lives, and the notice the
licence asks us to carry. The same list, in the same words, is on the app's Licences screen (Settings, "Licences and credits";
`app/src/main/java/com/craneai/findr/ui/settings/LicenceNotices.kt`), and a unit test keeps the required wording in place.
Update both together. The versions were read from `./gradlew :app:dependencies` (release runtime classpath) and from the model cards.

## Notices the licences require

| Component | Version | Licence | Link | Notice |
|---|---|---|---|---|
| EmbeddingGemma 2, by Google | 2 | Apache-2.0 | huggingface.co/google/embeddinggemma-2 | Used as published by `litert-community/embeddinggemma-2-*-litert-lm`; any file the app's makers fine-tuned or converted is marked as modified. Keep the model card's line: "Deployments must adhere to the Gemma Prohibited Use Policy" (ai.google.dev/gemma/prohibited_use_policy). The Gemma Terms of Use were EmbeddingGemma 1's licence, not this one |
| Parakeet-TDT-CTC-110M, by NVIDIA | 110M, 8-bit | CC-BY-4.0 | huggingface.co/nvidia/parakeet-tdt_ctc-110m | **Attribution required**: author, licence (creativecommons.org/licenses/by/4.0) and link. Change: converted to ONNX and quantised to 8-bit integers for sherpa-onnx. NVIDIA does not endorse the app |
| ML Kit Text Recognition, by Google | 16.0.1 | ML Kit Terms of Service | developers.google.com/ml-kit/vision/text-recognition | Google's terms (developers.google.com/ml-kit/terms), not an open-source licence. ML Kit can send usage metrics to Google (developers.google.com/ml-kit/android-data-disclosure); the app has no internet permission, so nothing leaves the phone. **ML Kit is a proprietary Google library, linked into the Android app at build time and not part of Trawl's source. Apache-2.0 has no copyleft, so linking it does not clash with Trawl's licence; the Android APK is a combination of Apache-2.0 code and this library under Google's terms.** The desktop app does not use it |

## Everything else

| Component | Version | Licence | Link | Notes |
|---|---|---|---|---|
| LiteRT-LM, by Google | 0.18.0 | Apache-2.0 | github.com/google-ai-edge/LiteRT-LM | Runs EmbeddingGemma 2 |
| sherpa-onnx, by the k2-fsa project | 1.13.8 | Apache-2.0 | github.com/k2-fsa/sherpa-onnx | Runs the speech models. An AAR in `app/libs`, not in git |
| ONNX Runtime, by Microsoft | 1.31.0 | MIT | github.com/microsoft/onnxruntime | Runs Quick look (`onnxruntime-android`), and is linked inside sherpa-onnx as a private copy. Copyright (c) Microsoft Corporation. Its notices for the code it bundles: github.com/microsoft/onnxruntime/blob/main/ThirdPartyNotices.txt |
| TinyCLIP ViT-61M/32 Text-29M, by Microsoft | 61M/32 | MIT | github.com/microsoft/Cream | Quick look weights (`wkcn/TinyCLIP-ViT-61M-32-Text-29M-LAION400M`). Copyright (c) Microsoft Corporation. Changes: converted to ONNX, weights stored as 8-bit integers. It replaced a laion CLIP model whose card rules out deployed use |
| nsfw-image-detection-384, by Marqo | 384 px | Apache-2.0 | huggingface.co/Marqo/nsfw-image-detection-384 | Fine-tune of the timm ViT-tiny (Apache-2.0). Change: converted to ONNX. The "Blur private-looking previews" add-on, not in the APK; built by `scripts/fetch-private-model.sh` and packed by `scripts/pack-private-models.sh` with its licence and a SOURCE.txt. Only blurs thumbnails, never hides or reorders a result |
| Silero VAD, by the Silero Team | | MIT | github.com/snakers4/silero-vad | Finds the parts of a recording where someone is speaking. Copyright (c) 2020-present Silero Team |
| CED-mini sound labels | | Apache-2.0 | huggingface.co/mispeech/ced-mini | Names sounds, as converted for sherpa-onnx. (The CED code repository is GPL-3.0; the model weights are Apache-2.0, and only the weights ship) |
| Moonshine tiny, by Useful Sensors | | MIT | github.com/moonshine-ai/moonshine | Optional alternative English speech model. Copyright (c) Useful Sensors, Inc. |
| CLIP tokenizer vocabulary, by OpenAI | | MIT | github.com/openai/CLIP | `assets/clip_bpe_merges.txt.gz`, from open_clip's `bpe_simple_vocab_16e6.txt.gz`. Copyright (c) 2021 OpenAI |
| English word list | | Public domain | | `assets/english_words.txt.gz`, from Webster's Second International Dictionary (1934) |
| Common English word list | | CC0 | | `assets/common_words.txt.gz`: words seen 30+ times in the Mozilla Common Voice 17 English text corpus (training sentences); no sentences are shipped. Used by the speech language gate |
| AndroidX and Jetpack Compose, by Google | see notes | Apache-2.0 | developer.android.com/jetpack | Compose 1.12.1, Material 3 1.4.0, Material icons 1.7.8, Core 1.19.1 (splash screen 1.2.0), AppCompat 1.7.1, Lifecycle 2.11.0, Navigation 2.10.2, WorkManager 2.12.0, Room 2.7.0, Activity 1.13.0, Fragment 1.9.1, Biometric 1.1.0, ExifInterface 1.4.2, SQLite 2.7.1, and the smaller AndroidX libraries they need |
| SQLite | | Public domain | sqlite.org | The copy bundled with androidx.sqlite. Stores the search index |
| Kotlin and kotlinx, by JetBrains | 2.4.10 | Apache-2.0 | kotlinlang.org | Standard library 2.4.10, coroutines 1.11.0, serialization 1.7.3, and the JetBrains Compose 1.12.0 runtime that Coil uses |
| Coil, by Coil Contributors | 3.6.3 | Apache-2.0 | coil-kt.github.io/coil | Picture and video thumbnails. Brings Accompanist DrawablePainter 0.37.3 (Apache-2.0) |
| PdfBox-Android, by Tom Roush | 2.0.27.0 | Apache-2.0 | github.com/TomRoush/PdfBox-Android | Reads the text in PDFs; a port of Apache PDFBox. Apache section 4(d): its NOTICE text (Apache PDFBox, Adobe Glyph List, Zapf Dingbats, Unicode Script Property) is reproduced word for word on the Licences screen (`LicenceNotices.PDFBOX_NOTICE`) |
| Bouncy Castle, by the Legion of the Bouncy Castle | 1.72 | Bouncy Castle Licence (MIT) | bouncycastle.org | Comes with PdfBox-Android (bcprov, bcpkix, bcutil, `jdk15to18`), for PDF encryption. Copyright (c) 2000 - 2023 The Legion of the Bouncy Castle Inc. |
| Google Play services client libraries | 18.5.0 | Android SDK Licence | developer.android.com/studio/terms | Code ML Kit builds on: play-services-base 18.5.0, basement 18.4.0, tasks 18.2.0, and the ML Kit image library 1.0.0-beta1 |
| Small support libraries | see notes | Apache-2.0 | | Gson 2.14.0 (Google), Okio 3.18.1 (Square), Guava ListenableFuture 1.0, Error Prone annotations 2.48.0, javax.inject 1, JetBrains annotations 23.0.0, JSpecify 1.0.0, and the Firebase components and Android datatransport libraries ML Kit builds on |
| Atkinson Hyperlegible, by the Braille Institute | | SIL Open Font License 1.1 | brailleinstitute.org/freefont | Regular and Bold (`res/font`), bundled. Copyright 2020 Braille Institute of America, Inc.; licence text in `assets/licenses/AtkinsonHyperlegible-OFL.txt` |
| DigUp, by ARahim3 | | MIT | github.com/ARahim3/DigUp | Design reference only: the way word and meaning matches are combined follows DigUp. No code was copied |

## Desktop app (`desktop/`, Windows and Linux)

The desktop build shares the phone's search, index and signed-bundle sources and adds the libraries below. Its own versions are in
`desktop/build.gradle.kts`; the Android components above that it also uses (LiteRT-LM, SQLite, PDFBox's text reading, Atkinson Hyperlegible) keep their rows.

| Component | Version | Licence | Link | Notes |
|---|---|---|---|---|
| Compose Multiplatform and Material 3, by JetBrains and Google | 1.12.2 / 1.9.0 | Apache-2.0 | github.com/JetBrains/compose-multiplatform | The window and the Sunlight UI. Brings Skiko (Apache-2.0) for drawing |
| kotlinx-coroutines (core, swing), by JetBrains | 1.11.0 | Apache-2.0 | github.com/Kotlin/kotlinx.coroutines | The swing module is the Main dispatcher on the window thread |
| androidx.sqlite (bundled), by Google | 2.7.1 | Apache-2.0 | developer.android.com/jetpack/androidx/releases/sqlite | The same SQLite with FTS5 the phone uses |
| Apache PDFBox | 3.0.8 | Apache-2.0 | pdfbox.apache.org | Reads PDF text on the desktop (the phone uses its Android port). Its NOTICE text applies as it does for the phone |
| JNA, by the JNA project | 5.18.1 | Apache-2.0 or LGPL-2.1 (dual; used under Apache-2.0) | github.com/java-native-access/jna | Windows desktop-window-manager bounds for the screenshot driver; native seams |
| android-json (org.json from the Android SDK), packaged by Vaadin | 0.0.20131108.vaadin1 | Apache-2.0 | central.sonatype.com/artifact/com.vaadin.external.google/android-json | The `org.json` classes of the Android SDK, so the shared signed-bundle and language-centroid sources parse JSON exactly as on the phone. **Replaces `org.json:json` (the "JSON licence", "shall be used for Good, not Evil"), which is not an OSI licence and clashes with GPL-3.0 and Apache-2.0.** Taken from the AOSP copy of 2013 (raw `Iterator` from `keys()`); the one shared file that needs the typed one, `core/SettingsCodec.kt`, is not compiled for desktop |
| Apache Commons Logging | 1.4.0 | Apache-2.0 | commons.apache.org/proper/commons-logging | Comes with PDFBox. Its NOTICE file is shown on the Licences screen |
| kXML 2, by Stefan Haustein | 2.3.0 | BSD-style (MIT terms) and public domain (XmlPull API) | kxml.sourceforge.net | Reads the XML inside office documents. Copyright (c) 2002, 2003 Stefan Haustein, Oberhausen, Rhld., Germany; the MIT text on the Licences screen carries it |
| JetBrains Runtime API (jbr-api) | 1.9.0 | Apache-2.0 | github.com/JetBrains/JetBrainsRuntimeApi | Brought in by Compose Desktop |
| sherpa-onnx, by the k2-fsa project (JVM jars) | 1.13.8 | Apache-2.0; ONNX Runtime inside the native jar is MIT | github.com/k2-fsa/sherpa-onnx | Reads speech in videos and recordings with the phone's models (Parakeet-TDT-CTC-110M, Silero VAD and CED-mini rows above apply to the desktop too). Release assets, not on Maven Central: the JVM jar is committed in `desktop/libs/repo`, the native jar for the build's OS is fetched from the pinned release tag and checked against `desktop/libs/SHA256SUMS` (the build fails on a mismatch). Nothing is fetched when the app runs |
| AVFoundation (macOS), Media Foundation (Windows) | system | Part of the operating system | | Decode video frames and sound; reached through a helper built into the app (macOS) or a script (Windows). Not shipped |
| ffmpeg or GStreamer (Linux) | system install | the user's own build | ffmpeg.org | Linux only: the desktop runs the user's installed `ffmpeg` and `ffprobe` to read frames and sound; not bundled, no ffmpeg code is linked |
| Tesseract OCR, by the Tesseract project | system install | Apache-2.0 | github.com/tesseract-ocr/tesseract | Linux only: the desktop runs the user's installed `tesseract` command to read text in images; not bundled |
| Welcome-demo stills, from Mixkit | | Mixkit Stock Video Free License | mixkit.co/license | Five small stills in `desktop/src/main/resources/trawl/demo/`, cropped from Mixkit videos 50051, 4781, 51536, 50686 and 50672 (the same files as `site/media`, see `site/CREDITS.md`). No attribution is required; they are credited on the Licences screen anyway. Free licence, but copies may not be sold unaltered |

The Windows OCR helper uses the operating system's own `Windows.Media.Ocr`; the macOS helper (built into the app) uses Apple's Vision framework.

## Licence texts

`app/src/main/assets/licenses/` holds the Apache-2.0, MIT (with the copyright lines of Silero, ONNX Runtime, TinyCLIP, the CLIP vocabulary,
Moonshine and Bouncy Castle) and SIL OFL 1.1 texts. They are shown on the Licences screen.

## Test data (not shipped)

FLEURS-derived test strings (`app/src/test/resources/speech_gate_golden.tsv`), CC BY 4.0, Conneau et al. 2022.
