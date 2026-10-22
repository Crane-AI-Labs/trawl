package com.craneai.findr.ui.settings

/**
 * What the Licences screen shows. Kept in English on purpose: these are legal notices (the PdfBox NOTICE must be verbatim), and
 * they say "this app" rather than the app's name, which lives only in app_name. One entry per component: name, version, licence, link, and the
 * notice the licence requires. The source of truth for what must be there is THIRD_PARTY_NOTICES.md; keep the two in step
 * (LicenceNoticesTest checks that the notices the licences require are present word for word).
 */
data class Notice(
  val name: String,
  val licence: String,
  val text: String,
  /** The release in this app, or empty when the project publishes none (a model file, a word list). */
  val version: String = "",
  /** Where the project lives, without "https://" (the app has no browser link; people copy it). */
  val link: String = "",
) {
  /** The one line under the name: "1.13.8 · Apache 2.0". */
  val meta: String get() = listOf(version, licence).filter { it.isNotEmpty() }.joinToString(" · ")
}

/** A full licence text kept in assets/licenses/, shown on demand. */
data class LicenceText(val title: String, val asset: String)

object LicenceNotices {
  /**
   * The EmbeddingGemma 2 bundles (litert-community/embeddinggemma-2-*-litert-lm, parent google/embeddinggemma-2) are Apache 2.0 on their
   * model cards (read live 2026-10-16). The Gemma Terms of Use were EmbeddingGemma 1's licence. The card still adds the Prohibited Use Policy.
   */
  const val GEMMA_PROHIBITED_USE = "Deployments must adhere to the Gemma Prohibited Use Policy (ai.google.dev/gemma/prohibited_use_policy)"

  /** Apache 2.0 section 4(d): the NOTICE text of Apache PDFBox as PdfBox-Android ships it, reproduced word for word. */
  const val PDFBOX_NOTICE = """Apache PDFBox
Copyright 2014 The Apache Software Foundation

This product includes software developed at The Apache Software Foundation (http://www.apache.org/).

Based on source code originally developed in the PDFBox and FontBox projects.
Copyright (c) 2002-2007, www.pdfbox.org

Based on source code originally developed in the PaDaF project.
Copyright (c) 2010 Atos Worldline SAS

Includes the Adobe Glyph List
Copyright 1997, 1998, 2002, 2007, 2010 Adobe Systems Incorporated.

Includes the Zapf Dingbats Glyph List
Copyright 2002, 2010 Adobe Systems Incorporated.

Includes the Script Property (Scripts-10.0.0.txt)
Copyright 2017 Unicode, Inc."""

  /** Notices a licence requires us to show: attribution, the model licences, and Google's ML Kit terms. */
  val required: List<Notice> = listOf(
    Notice(
      "EmbeddingGemma 2, by Google",
      "Apache 2.0",
      "This app understands your files with EmbeddingGemma 2, licensed under the Apache License 2.0 " +
        "(the full text is below). The model files this app uses are the LiteRT-LM packages published at " +
        "huggingface.co/litert-community. Changes: the models are used as published, apart from any file this app's makers " +
        "fine-tuned or converted, which are marked as such. $GEMMA_PROHIBITED_USE.",
      version = "2",
      link = "huggingface.co/google/embeddinggemma-2",
    ),
    Notice(
      "Parakeet-TDT-CTC-110M, by NVIDIA",
      "CC BY 4.0",
      "Speech recognition uses Parakeet-TDT-CTC-110M by NVIDIA, licensed under the Creative Commons Attribution 4.0 International " +
        "licence (CC BY 4.0, creativecommons.org/licenses/by/4.0). " +
        "Changes: it was converted to ONNX and quantised to 8-bit integers for sherpa-onnx. NVIDIA does not endorse this app.",
      version = "110M, 8-bit",
      link = "huggingface.co/nvidia/parakeet-tdt_ctc-110m",
    ),
    Notice(
      "ML Kit Text Recognition, by Google",
      "ML Kit Terms of Service",
      "This app reads the text in photos and screenshots with Google ML Kit Text Recognition, used under the ML Kit Terms of Service " +
        "(developers.google.com/ml-kit/terms). The recogniser runs on this phone. ML Kit can send usage metrics to Google, but this app has " +
        "no internet permission, so nothing leaves the phone.",
      version = "16.0.1",
      link = "developers.google.com/ml-kit/vision/text-recognition",
    ),
  )

  /** Everything else that ships in the app or beside it. */
  val components: List<Notice> = listOf(
    Notice("LiteRT-LM, by Google", "Apache 2.0", "Runs EmbeddingGemma 2 on the phone.", "0.18.0", "github.com/google-ai-edge/LiteRT-LM"),
    Notice("sherpa-onnx, by the k2-fsa project", "Apache 2.0", "Runs the speech models on the phone.", "1.13.8", "github.com/k2-fsa/sherpa-onnx"),
    Notice(
      "ONNX Runtime, by Microsoft", "MIT",
      "Runs Quick look and the private-look blur (a slim build of ONNX Runtime 1.31), and is linked inside sherpa-onnx as a private copy. Copyright (c) Microsoft Corporation. " +
        "Its third-party notices, for the code it bundles, are at github.com/microsoft/onnxruntime/blob/main/ThirdPartyNotices.txt.",
      "1.31.0", "github.com/microsoft/onnxruntime",
    ),
    Notice(
      "TinyCLIP ViT-61M/32 Text-29M, by Microsoft",
      "MIT",
      "Quick look finds photos by what is in them before EmbeddingGemma has read them. The weights are published by Microsoft " +
        "at huggingface.co/wkcn/TinyCLIP-ViT-61M-32-Text-29M-LAION400M (TinyCLIP, Wu et al., ICCV 2023; github.com/microsoft/Cream). " +
        "Copyright (c) Microsoft Corporation. Changes: converted to ONNX; the weights stored as 8-bit integers.",
      "61M/32, Text-29M", "github.com/microsoft/Cream",
    ),
    Notice(
      "nsfw-image-detection-384, by Marqo",
      "Apache 2.0",
      "The \"Blur private-looking previews\" add-on (not part of the app until you add it) blurs a thumbnail that looks private, on this phone. It is a fine-tune of the timm ViT-tiny (Apache 2.0). " +
        "Changes: converted to ONNX. It can be wrong and never hides or reorders a result.",
      "384 px", "huggingface.co/Marqo/nsfw-image-detection-384",
    ),
    Notice("Silero VAD, by the Silero Team", "MIT", "Finds the parts of a recording where someone is speaking. Copyright (c) 2020-present Silero Team.", "", "github.com/snakers4/silero-vad"),
    Notice("CED-mini sound labels", "Apache 2.0", "Names sounds such as music, applause or a dog barking. The model, as converted for sherpa-onnx.", "", "huggingface.co/mispeech/ced-mini"),
    Notice("Moonshine tiny, by Useful Sensors", "MIT", "An optional alternative English speech model. Copyright (c) Useful Sensors, Inc.", "", "github.com/moonshine-ai/moonshine"),
    Notice("CLIP tokenizer vocabulary, by OpenAI", "MIT", "Turns an English search into the Quick look model's words. Copyright (c) 2021 OpenAI.", "", "github.com/openai/CLIP"),
    Notice("English word list", "Public domain", "Built from Webster's Second International Dictionary (1934). Tells English speech from other languages.", "", ""),
    Notice(
      "AndroidX and Jetpack Compose, by Google", "Apache 2.0",
      "The app's framework and screens: Compose 1.12.1 with Material 3 1.4.0 and Material icons 1.7.8, Core 1.19.1 (and its splash screen 1.2.0), " +
        "AppCompat 1.7.1, Lifecycle 2.11.0, Navigation 2.10.2, WorkManager 2.12.0, Room 2.7.0, Activity 1.13.0, Fragment 1.9.1, Biometric 1.1.0, " +
        "ExifInterface 1.4.2, SQLite 2.7.1, and the smaller AndroidX libraries they need.",
      "see text", "developer.android.com/jetpack",
    ),
    Notice("SQLite", "Public domain", "Stores the search index on the phone (the copy bundled with androidx.sqlite).", "", "sqlite.org"),
    Notice(
      "Kotlin and kotlinx, by JetBrains", "Apache 2.0",
      "The language this app is written in: Kotlin standard library 2.4.10, kotlinx.coroutines 1.11.0, kotlinx.serialization 1.7.3, and the JetBrains Compose 1.12.0 runtime that Coil uses.",
      "2.4.10", "kotlinlang.org",
    ),
    Notice("Coil, by Coil Contributors", "Apache 2.0", "Shows picture and video thumbnails. Brings Accompanist DrawablePainter 0.37.3 (Apache 2.0) with it.", "3.6.3", "coil-kt.github.io/coil"),
    Notice("PdfBox-Android, by Tom Roush", "Apache 2.0", "Reads the text in PDF files. A port of Apache PDFBox. Its NOTICE file:\n\n$PDFBOX_NOTICE", "2.0.27.0", "github.com/TomRoush/PdfBox-Android"),
    Notice(
      "Bouncy Castle, by the Legion of the Bouncy Castle", "Bouncy Castle Licence (MIT)",
      "Comes with PdfBox-Android, for PDF encryption. Copyright (c) 2000 - 2023 The Legion of the Bouncy Castle Inc. (https://www.bouncycastle.org). " +
        "The licence is the MIT licence; its text is below.",
      "1.72", "bouncycastle.org",
    ),
    Notice(
      "Google Play services client libraries", "Android Software Development Kit License",
      "Code that ML Kit builds on: play-services-base 18.5.0, play-services-basement 18.4.0, play-services-tasks 18.2.0, and the ML Kit image library 1.0.0-beta1. " +
        "Used under developer.android.com/studio/terms.",
      "18.5.0", "developer.android.com/studio/terms",
    ),
    Notice(
      "Small support libraries", "Apache 2.0",
      "Gson 2.14.0 (Google), Okio 3.18.1 (Square), Guava ListenableFuture 1.0, Error Prone annotations 2.48.0, javax.inject 1, JetBrains annotations 23.0.0, " +
        "JSpecify 1.0.0, and the Firebase components and Android datatransport libraries that ML Kit builds on (16.1.0 to 17.1.0 and 2.2.6 to 2.3.3).",
      "see text", "",
    ),
    Notice("Atkinson Hyperlegible, by the Braille Institute", "SIL Open Font License 1.1", "The font this app uses, Regular and Bold. Copyright 2020 Braille Institute of America, Inc.", "", "brailleinstitute.org/freefont"),
    Notice("DigUp, by ARahim3", "MIT", "This app's way of combining word and meaning matches follows DigUp's design. No code was copied.", "", "github.com/ARahim3/DigUp"),
  )

  val texts: List<LicenceText> = listOf(
    LicenceText("Apache License 2.0", "licenses/Apache-2.0.txt"),
    LicenceText("MIT License", "licenses/MIT.txt"),
    LicenceText("SIL Open Font License 1.1 (Atkinson Hyperlegible)", "licenses/AtkinsonHyperlegible-OFL.txt"),
  )
}
