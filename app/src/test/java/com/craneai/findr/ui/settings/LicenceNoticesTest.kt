package com.craneai.findr.ui.settings

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** The notices the licences require must be on the screen, word for word. THIRD_PARTY_NOTICES.md says what they are. */
class LicenceNoticesTest {
  @Test fun seesHeadCarriesTeacherAndPhotographProvenance() {
    val notice = LicenceNotices.required.single { it.name.contains("Sees head") }
    for (word in listOf("Crane AI Labs", "Qwen3-VL-8B-Instruct", "Qwen team", "Apache-2.0", "Open Images", "credited photographers",
      "CC BY 2.0", "creativecommons.org/licenses/by/2.0", "validation-images-with-rotation.csv", "Changes:", "No teacher model or photographs", "do not endorse")) {
      assertTrue("missing $word", notice.text.contains(word))
    }
  }
  private val all = (LicenceNotices.required + LicenceNotices.components).joinToString("\n") { "${it.name} ${it.licence} ${it.text}" }

  @Test fun embeddingGemmaIsApacheWithTheProhibitedUsePolicy() {
    val n = LicenceNotices.required.single { it.name.startsWith("EmbeddingGemma") }
    assertTrue(n.licence == "Apache 2.0")
    assertTrue(n.text.contains("Deployments must adhere to the Gemma Prohibited Use Policy"))
    assertTrue(n.text.contains("ai.google.dev/gemma/prohibited_use_policy"))
    assertTrue(n.text.contains("Changes:"))
    assertTrue("Apache text is shown", LicenceNotices.texts.any { it.asset == "licenses/Apache-2.0.txt" })
  }

  /** EmbeddingGemma 2 is not under the Gemma Terms of Use any more (that was EmbeddingGemma 1); no screen may still say so. */
  @Test fun noStaleGemmaTermsClaim() {
    assertTrue(!all.contains("Gemma Terms of Use"))
    assertTrue(!all.contains("ai.google.dev/gemma/terms"))
    val res = listOf(File("src/main/res"), File("app/src/main/res")).first { it.isDirectory }
    val about = Regex("""name="settings_about_model">([^<]*)<""")
    val stale = res.walkTopDown().filter { it.isFile && it.name.startsWith("strings") && it.extension == "xml" }
      .filter { f -> about.find(f.readText())?.groupValues?.get(1)?.let { !it.contains("Apache 2.0") || it.contains("Terms", ignoreCase = true) } == true }.toList()
    assertTrue("About still cites the Gemma terms in: $stale", stale.isEmpty())
  }

  /** Apache 2.0 section 4(d): PDFBox's NOTICE travels with PdfBox-Android, word for word. */
  @Test fun pdfBoxNoticeIsReproduced() {
    val n = LicenceNotices.components.single { it.name.startsWith("PdfBox") }
    for (line in listOf("Apache PDFBox", "Copyright 2014 The Apache Software Foundation", "Copyright (c) 2002-2007, www.pdfbox.org",
      "Copyright (c) 2010 Atos Worldline SAS", "Includes the Adobe Glyph List", "Includes the Zapf Dingbats Glyph List",
      "Includes the Script Property (Scripts-10.0.0.txt)", "Copyright 2017 Unicode, Inc.")) {
      assertTrue("missing: $line", n.text.contains(line))
    }
  }

  @Test fun onnxRuntimeLinksItsThirdPartyNotices() {
    assertTrue(LicenceNotices.components.single { it.name.startsWith("ONNX Runtime") }.text.contains("onnxruntime/blob/main/ThirdPartyNotices.txt"))
  }

  @Test fun parakeetAttributionNamesAuthorLicenceLinkAndChanges() {
    val n = LicenceNotices.required.single { it.name.startsWith("Parakeet") }
    assertTrue(n.name.contains("NVIDIA"))
    assertTrue(n.licence == "CC BY 4.0")
    assertTrue(n.text.contains("creativecommons.org/licenses/by/4.0"))
    assertTrue("CC BY asks that changes are indicated", n.text.contains("Changes:"))
  }

  /** CC BY 4.0 for the corpora the keyword table aligns: creators, licence and links, the changes (languages-keywords-clean.md). No SALT. */
  @Test fun keywordTableNamesItsSourcesLicenceAndChanges() {
    val n = LicenceNotices.required.single { it.name.startsWith("Luganda and Swahili keyword table") }
    assertTrue(n.licence == "CC BY 4.0")
    for (s in listOf("Makerere MT Corpus", "Makerere Gendered Corpus", "Jonathan Mukiibi", "Makerere University AI Lab", "zenodo.org/records/5089560",
      "zenodo.org/records/5864560", "TICO-19", "creativecommons.org/licenses/by/4.0", "Changes:", "Common Voice", "DeepSeek-V4-Pro",
      "do not endorse")) assertTrue("missing: $s", n.text.contains(s))
    assertTrue(n.link == "zenodo.org/records/5089560")
    // The opt-in speech packs are CC BY-SA by decision (D46: WAXAL ships in the add-on, not in the app); nothing else may be.
    assertTrue("no ShareAlike source", LicenceNotices.required.filterNot { it.name.startsWith("Speech packs") }.none { it.licence.contains("SA") || it.text.contains("Sunbird") })
  }

  @Test fun moreLanguagesKeywordTableNamesItsSourcesLicenceAndChanges() {
    val n = LicenceNotices.required.single { it.name.startsWith("Runyankole, Kinyarwanda and Acholi keyword table") }
    assertTrue(n.licence == "CC BY 4.0")
    for (s in listOf("Multilingual Parallel Text Corpora for East African Languages", "Claire Babirye", "Makerere University",
      "doi.org/10.7910/DVN/BEROE0", "BUAIR", "Busitema University", "TICO-19", "creativecommons.org/licenses/by/4.0", "Changes:",
      "Common Voice", "DeepSeek-V4-Pro", "do not endorse")) assertTrue("missing: $s", n.text.contains(s))
    assertTrue(n.link == "doi.org/10.7910/DVN/BEROE0")
  }

  @Test fun mlKitTermsAreLinked() {
    val t = LicenceNotices.required.single { it.name.startsWith("ML Kit") }.text
    assertTrue(t.contains("developers.google.com/ml-kit/terms"))
    // ML Kit may send usage metrics; what stops it is the missing internet permission, and the notice says so.
    assertTrue(t.contains("ML Kit can send usage metrics"))
    assertTrue(t.contains("no internet permission"))
    assertTrue(!t.contains("Nothing is sent to Google."))
  }

  @Test fun everyShippedComponentFromTheNoticesFileIsListed() {
    for (word in listOf("sherpa-onnx", "LiteRT-LM", "CED-mini", "Silero", "ONNX Runtime", "AndroidX", "Kotlin", "Coil", "PdfBox", "SQLite", "Atkinson Hyperlegible", "word list",
      "TinyCLIP", "CLIP tokenizer")) {
      assertTrue("missing $word", all.contains(word, ignoreCase = true))
    }
  }

  /**
   * MIT asks that the copyright notice travels with the weights and the vocabulary (the MIT text is on the screen as an asset).
   * No laion weights any more (D10: their model card rules out deployed use).
   */
  @Test fun quickLookCarriesItsMitCopyrightLinesAndChanges() {
    val q = LicenceNotices.components.single { it.name.startsWith("TinyCLIP") }
    assertTrue(q.licence == "MIT")
    assertTrue(q.text.contains("Copyright (c) Microsoft Corporation"))
    assertTrue(q.text.contains("huggingface.co/wkcn/TinyCLIP-ViT-61M-32-Text-29M-LAION400M"))
    assertTrue(q.text.contains("Changes:"))
    assertTrue(LicenceNotices.components.single { it.name.startsWith("CLIP tokenizer") }.text.contains("Copyright (c) 2021 OpenAI"))
    assertTrue(LicenceNotices.texts.any { it.asset == "licenses/MIT.txt" })
    assertTrue(!all.contains("OpenCLIP") && !all.contains("laion2b", ignoreCase = true) && !all.contains("Romain Beaumont"))
  }

  /** One entry per component: no name twice, every entry names its licence, and everything but a model file or a word list has a link. */
  @Test fun everyEntryIsUniqueAndComplete() {
    val list = LicenceNotices.required + LicenceNotices.components
    val names = list.map { it.name.lowercase() }
    assertTrue("duplicate entries: ${names.groupingBy { it }.eachCount().filter { it.value > 1 }.keys}", names.size == names.toSet().size)
    for (n in list) {
      assertTrue("${n.name}: licence", n.licence.isNotBlank())
      assertTrue("${n.name}: text", n.text.isNotBlank())
      assertTrue("${n.name}: link must not carry a scheme", !n.link.startsWith("http"))
    }
    val noLink = list.filter { it.link.isEmpty() }.map { it.name }
    assertTrue("only these may lack a link: $noLink", noLink.all { it.startsWith("English word list") || it.startsWith("Small support") })
    assertTrue(LicenceNotices.required.all { it.link.isNotEmpty() })
    assertTrue("1.13.8 · Apache 2.0" == LicenceNotices.components.single { it.name.startsWith("sherpa-onnx") }.meta)
  }

  /** Moonshine has its own entry: it is not a footnote of ONNX Runtime. */
  @Test fun moonshineHasItsOwnEntry() {
    assertTrue(LicenceNotices.components.any { it.name.startsWith("Moonshine") && it.licence == "MIT" })
    assertTrue(!LicenceNotices.components.single { it.name.startsWith("ONNX Runtime") }.text.contains("Moonshine"))
  }

  /** Bouncy Castle comes with PdfBox-Android (checked with ./gradlew :app:dependencies); its copyright line is in the MIT text. */
  @Test fun bouncyCastleIsCredited() {
    val n = LicenceNotices.components.single { it.name.startsWith("Bouncy Castle") }
    assertTrue(n.text.contains("The Legion of the Bouncy Castle Inc."))
    val dir = listOf(File("src/main/assets"), File("app/src/main/assets")).first { it.isDirectory }
    assertTrue(File(dir, "licenses/MIT.txt").readText().contains("Legion of the Bouncy Castle"))
  }

  @Test fun everyFullTextIsInTheAssets() {
    val dir = listOf(File("src/main/assets"), File("app/src/main/assets")).first { it.isDirectory }
    for (t in LicenceNotices.texts) {
      val f = File(dir, t.asset)
      assertTrue("${t.asset} missing", f.isFile && f.length() > 500)
    }
  }

  /** D46: the speech packs carry WAXAL (CC BY-SA 4.0): Makerere University and Google WaxalNLP credited, ShareAlike said, Parakeet's CC BY kept. */
  @Test fun speechPacksCreditWaxalAndParakeetAndSayShareAlike() {
    val n = LicenceNotices.required.single { it.name.startsWith("Speech packs") }
    assertTrue(n.licence.contains("CC BY-SA 4.0"))
    for (s in listOf("Makerere University", "Google (WaxalNLP)", "huggingface.co/datasets/google/WaxalNLP", "creativecommons.org/licenses/by-sa/4.0",
      "ShareAlike", "Parakeet-TDT-CTC-110M by NVIDIA", "creativecommons.org/licenses/by/4.0", "Changes:", "do not endorse")) assertTrue("missing: $s", n.text.contains(s))
  }
}
