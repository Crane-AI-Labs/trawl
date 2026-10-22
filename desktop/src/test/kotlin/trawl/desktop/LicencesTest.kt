package trawl.desktop

import java.io.File
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LicencesTest {
  private fun mod(group: String, artifact: String, version: String, licence: String = "Apache-2.0", notice: String? = null) =
    ModuleLicence(group, artifact, version, listOf(licence), "example.org/$artifact", notice)

  private val real: List<ModuleLicence> by lazy {
    val tsv = Licences.read(Licences.GENERATED)
    assertNotNull(tsv, "trawl/licences.tsv is not on the classpath: generateLicences did not run")
    Licences.parseModules(tsv) { Licences.read(Licences.NOTICES_DIR + it) }
  }

  // ---- the generated file, against the build's real runtime dependencies -------------------------------------------------

  @Test fun generatedListCoversTheLibrariesTheAppShips() {
    assertTrue(real.size > 20, "only ${real.size} modules")
    val keys = real.map { it.key }.toSet()
    for (k in listOf("org.jetbrains.kotlin:kotlin-stdlib", "androidx.sqlite:sqlite-bundled-jvm", "com.vaadin.external.google:android-json", "net.sf.kxml:kxml2", "net.java.dev.jna:jna", "org.apache.pdfbox:pdfbox", "org.apache.pdfbox:fontbox", "org.jetbrains.skiko:skiko-awt")) {
      assertTrue(k in keys, "missing $k")
    }
    for (g in listOf("com.google.ai.edge.litertlm", "org.jetbrains.compose.runtime", "org.jetbrains.compose.ui", "org.jetbrains.kotlinx")) {
      assertTrue(real.any { it.group == g }, "missing group $g")
    }
  }

  @Test fun everyJarOnTheClasspathIsInTheList() {
    val testOnly = listOf("junit", "opentest4j", "apiguardian", "kotlin-test", "org.jetbrains.kotlin.test",
      // pulled in by compose.uiTest (the accessibility tests); they are on the test classpath only and never ship
      "ui-test", "kotlinx-coroutines-test", "hamcrest")
    val jars = System.getProperty("java.class.path").split(File.pathSeparator).filter { it.endsWith(".jar") }
      .map { File(it).name }.filter { n -> n != "gradle-worker.jar" && testOnly.none { n.startsWith(it) } }
    assertTrue(jars.size > 20, "only ${jars.size} jars on the classpath")
    val missing = jars.filter { jar -> real.none { m -> jar.startsWith("${m.artifact}-${m.version}") } }
    assertTrue(missing.isEmpty(), "jars on the runtime classpath with no licence row (regenerate, or a classifier changed the name): $missing")
  }

  @Test fun noModuleLacksALicenceName() {
    for (m in real) assertTrue(m.licences.isNotEmpty() && m.licences.all { it.isNotBlank() }, "${m.key} has no licence name")
  }

  @Test fun everyModuleBelongsToAFamilyOrIsNamedByAHuman() {
    val (_, loose) = Licences.fold(real)
    assertTrue(loose.isEmpty(), "a new dependency has no family in Licences.kt (it would show under its raw coordinates): ${loose.map { it.key }}")
  }

  @Test fun screenShowsAboutTwentyToThirtyRows() {
    val book = Licences.load()
    val rows = book.required.size + book.components.size
    assertTrue(rows in 15..35, "$rows rows")
    assertTrue(real.size > rows + 20, "${real.size} jars should fold into far fewer than $rows rows")
  }

  // ---- notices the licences require ---------------------------------------------------------------------------------------

  @Test fun requiredNoticesArePresent() {
    val required = Licences.load().required
    val gemma = required.first { it.name.startsWith("EmbeddingGemma 2") }
    assertTrue("Deployments must adhere to the Gemma Prohibited Use Policy (ai.google.dev/gemma/prohibited_use_policy)" in gemma.text)
    assertEquals("Apache 2.0", gemma.licence)
    val pdfbox = required.first { it.name == "Apache PDFBox" }
    assertTrue(pdfbox.text.contains("Apache PDFBox\nCopyright 2002-") || pdfbox.text.contains("Apache PDFBox\r\nCopyright 2002-"))
    assertTrue("Includes the Adobe Glyph List" in pdfbox.text)
  }

  @Test fun pdfboxNoticeIsTheJarsOwnNoticeWordForWord() {
    val jar = System.getProperty("java.class.path").split(File.pathSeparator).map(::File)
      .first { it.name.startsWith("pdfbox-") && !it.name.startsWith("pdfbox-io") }
    val own = ZipFile(jar).use { z -> z.getInputStream(z.getEntry("META-INF/NOTICE")).readBytes().toString(Charsets.UTF_8) }.trim()
    val shown = Licences.load().required.first { it.name == "Apache PDFBox" }.text
    assertTrue(own in shown, "the PDFBox NOTICE shown differs from the one inside ${jar.name}")
  }

  @Test fun phoneOnlyComponentsDoNotAppear() {
    val book = Licences.load()
    val all = (book.required + book.components).joinToString("\n") { "${it.name}\n${it.text}\n${it.includes}" }
    for (word in listOf("ML Kit", "Moonshine", "Bouncy", "TinyCLIP", "PdfBox-Android", "Coil")) {
      assertFalse(word in all, "$word is phone-only and must not be on the desktop screen")
    }
    val texts = book.texts.joinToString("\n") { Licences.read(it.resource).orEmpty() }
    for (word in listOf("Moonshine", "Bouncy Castle", "TinyCLIP")) assertFalse(word in texts, "$word in a full text")
  }

  @Test fun speechComponentsAreListedWithTheirLicences() {
    val book = Licences.load()
    val sherpa = (book.required + book.components).first { it.name.startsWith("sherpa-onnx") }
    assertEquals("1.13.8", sherpa.version)
    assertTrue("Apache 2.0" in sherpa.licence && "MIT" in sherpa.licence, sherpa.licence)
    val parakeet = book.required.first { it.name.startsWith("Parakeet") }
    assertEquals("CC BY 4.0", parakeet.licence)
    assertTrue("NVIDIA does not endorse this app" in parakeet.text && "creativecommons.org/licenses/by/4.0" in parakeet.text)
    val names = book.components.map { it.name }
    assertTrue(names.any { it.startsWith("Silero VAD") } && names.any { it.startsWith("CED-mini") })
    val mit = Licences.read("trawl/licences/MIT.txt").orEmpty()
    assertTrue("Silero VAD" in mit && "ONNX Runtime" in mit)
  }

  @Test fun fullTextsAreOnTheClasspath() {
    for (t in Licences.texts) assertTrue(Licences.read(t.resource).orEmpty().length > 500, "${t.resource} is missing or empty")
    val mit = Licences.read("trawl/licences/MIT.txt").orEmpty()
    assertTrue("CLIP tokenizer vocabulary" in mit && "Stefan Haustein" in mit && "Permission is hereby granted" in mit)
  }

  @Test fun theHandWrittenFileParsesAndMatchesTheNoticesFile() {
    val extra = Licences.parseExtra(Licences.read(Licences.EXTRA).orEmpty())
    assertTrue(extra.size >= 8)
    assertTrue(extra.all { it.second.name.isNotBlank() && it.second.licence.isNotBlank() })
    val names = extra.map { it.second.name }
    for (n in listOf("SQLite", "Atkinson Hyperlegible, by the Braille Institute", "CLIP tokenizer vocabulary, by OpenAI", "English word list")) assertTrue(n in names, n)
    assertTrue(extra.any { it.second.name.startsWith("Tesseract") && "installed yourself" in it.second.text })
  }

  // ---- grouping, on small fixtures ---------------------------------------------------------------------------------------------

  @Test fun skikoJarsBecomeOneRow() {
    val (rows, loose) = Licences.fold(listOf(mod("org.jetbrains.skiko", "skiko-awt", "0.150.2"), mod("org.jetbrains.skiko", "skiko-awt-runtime-linux-x64", "0.150.2")))
    assertEquals(1, rows.size)
    assertTrue(loose.isEmpty())
    val skiko = rows.single().second
    assertEquals("Skiko, by JetBrains", skiko.name)
    assertEquals("0.150.2", skiko.version)
    assertEquals("0.150.2: skiko-awt, skiko-awt-runtime-linux-x64", skiko.includes)
  }

  @Test fun composeArtifactsGroupUnderTheHeadlineVersion() {
    val (rows, _) = Licences.fold(listOf(
      mod("org.jetbrains.compose.runtime", "runtime-desktop", "1.12.2"),
      mod("org.jetbrains.compose.ui", "ui-desktop", "1.12.2"),
      mod("org.jetbrains.compose.material3", "material3-desktop", "1.9.0"),
      mod("androidx.compose.runtime", "runtime-desktop", "1.12.1"),
      mod("org.jetbrains.androidx.lifecycle", "lifecycle-runtime-compose-desktop", "2.9.6"),
    ))
    assertEquals(1, rows.size)
    val c = rows.single().second
    assertTrue(c.name.startsWith("Compose Multiplatform"))
    assertEquals("1.12.2", c.version)
    assertTrue(c.includes.startsWith("1.12.2: "), c.includes)
    assertTrue("1.9.0: material3" in c.includes)
  }

  @Test fun androidxSqliteIsNotSwallowedByTheGenericAndroidxRow() {
    val (rows, _) = Licences.fold(listOf(mod("androidx.sqlite", "sqlite-bundled-jvm", "2.7.1"), mod("androidx.sqlite", "sqlite-jvm", "2.7.1"), mod("androidx.collection", "collection-jvm", "1.5.0")))
    assertEquals(setOf("androidx.sqlite, by Google", "AndroidX libraries, by Google"), rows.map { it.second.name }.toSet())
  }

  @Test fun aFamilyWithANoticeFileGoesWithTheRequiredNotices() {
    val (rows, _) = Licences.fold(listOf(
      mod("org.apache.pdfbox", "pdfbox", "3.0.8", notice = "Apache PDFBox\nCopyright 2002-2026"),
      mod("org.apache.pdfbox", "fontbox", "3.0.8", notice = "Apache FontBox\nCopyright 2008-2026"),
      mod("org.apache.pdfbox", "pdfbox-io", "3.0.8", notice = "Apache FontBox\nCopyright 2008-2026"),
      mod("org.jetbrains.kotlin", "kotlin-stdlib", "2.4.0"),
    ))
    val (required, other) = rows.partition { it.first }
    assertEquals(listOf("Apache PDFBox"), required.map { it.second.name })
    assertEquals(listOf("Kotlin and kotlinx, by JetBrains"), other.map { it.second.name })
    val text = required.single().second.text
    assertTrue(text.startsWith("Reads the text in PDF files. Its NOTICE files:\n\nApache PDFBox\nCopyright 2002-2026"))
    assertEquals(1, Regex("Apache FontBox").findAll(text).count(), "an identical notice is shown once")
  }

  @Test fun aJarNoFamilyClaimsStillShowsUnderItsCoordinates() {
    val (rows, loose) = Licences.fold(listOf(mod("org.example", "mystery", "9.9")))
    assertTrue(rows.isEmpty())
    assertEquals(listOf("org.example:mystery"), loose.map { it.key })
  }

  @Test fun licenceNamesAreNormalised() {
    assertEquals("Apache 2.0", Licences.normaliseLicence("The Apache Software License, Version 2.0"))
    assertEquals("Apache 2.0", Licences.normaliseLicence("Apache-2.0"))
    assertEquals("Apache 2.0", Licences.normaliseLicence("The Apache License, Version 2.0"))
    assertEquals("LGPL 2.1 or later", Licences.normaliseLicence("LGPL-2.1-or-later"))
    assertEquals("Public domain", Licences.normaliseLicence("Public Domain"))
    assertEquals("BSD style", Licences.normaliseLicence("BSD style"))
    assertEquals("Something Else 3", Licences.normaliseLicence(" Something Else 3 "))
  }

  @Test fun parsingReadsTheColumnsAndSkipsComments() {
    val tsv = "# c\ng\ta\t1.0\tApache-2.0 | MIT\texample.org\tn.txt\n\ng2\tb\t2\tMIT\t\t-\n"
    val ms = Licences.parseModules(tsv) { if (it == "n.txt") "NOTICE TEXT" else null }
    assertEquals(2, ms.size)
    assertEquals(listOf("Apache-2.0", "MIT"), ms[0].licences)
    assertEquals("NOTICE TEXT", ms[0].notice)
    assertEquals(null, ms[1].notice)
    assertEquals("g2:b", ms[1].key)
  }

  @Test fun theLicenceStringsExist() {
    for (k in listOf("licences_title", "licences_intro", "licences_section_required", "licences_section_components", "licences_section_texts",
      "licences_show", "licences_hide", "licences_show_named", "licences_hide_named", "licences_includes")) assertTrue(Strings.has(k), k)
    assertEquals("Trawl is built on the work below. These notices are required by the licences, and they are also how we say thank you.", t("licences_intro", "Trawl"))
  }
}
