package trawl.desktop

/**
 * What the Licences screen shows, in English on purpose (these are legal notices). Two sources, both on the classpath:
 *  - `trawl/licences.tsv`, written by the `generateLicences` Gradle task from the jars the desktop really ships (licence read
 *    from each POM, NOTICE files copied out of the jars into `trawl/notices/`);
 *  - `trawl/licences-extra.tsv`, by hand, for what is not a Maven jar (the model, the font, the word lists, the OS parts).
 * The jars are then folded into a few families so a person sees one row for "Compose" and not 26.
 */
data class LicenceNotice(
  val name: String,
  val licence: String,
  val text: String,
  val version: String = "",
  /** Where the project lives, without "https://" (people read it; the app opens no browser link). */
  val link: String = "",
  /** For a family of jars: which libraries, by version ("1.12.2: runtime, ui; 1.9.0: material3"). */
  val includes: String = "",
) {
  val meta: String get() = listOf(version, licence).filter { it.isNotEmpty() }.joinToString(" · ")
}

/** A full licence text kept on the classpath, shown on demand. */
data class LicenceText(val title: String, val resource: String)

class LicenceBook(val required: List<LicenceNotice>, val components: List<LicenceNotice>, val texts: List<LicenceText>)

/** One row of the generated file: a jar module as the build resolved it. */
data class ModuleLicence(
  val group: String,
  val artifact: String,
  val version: String,
  val licences: List<String>,
  val url: String,
  val notice: String?,
) {
  val key: String get() = "$group:$artifact"
}

object Licences {
  const val GENERATED = "trawl/licences.tsv"
  const val EXTRA = "trawl/licences-extra.tsv"
  const val NOTICES_DIR = "trawl/notices/"

  val texts: List<LicenceText> = listOf(
    LicenceText("Apache License 2.0", "licenses/Apache-2.0.txt"),
    LicenceText("MIT License", "trawl/licences/MIT.txt"),
    LicenceText("BSD 3-Clause License (TwelveMonkeys ImageIO)", "trawl/licences/TwelveMonkeys-BSD-3-Clause.txt"),
    LicenceText("SIL Open Font License 1.1 (Atkinson Hyperlegible)", "licenses/AtkinsonHyperlegible-OFL.txt"),
  )

  fun read(resource: String): String? =
    Licences::class.java.classLoader.getResourceAsStream(resource)?.use { it.readBytes().toString(Charsets.UTF_8) }

  /** Everything the screen lists, from the files on the classpath. */
  fun load(): LicenceBook {
    val modules = parseModules(read(GENERATED).orEmpty()) { read(NOTICES_DIR + it) }
    val (families, loose) = fold(modules)
    val extra = parseExtra(read(EXTRA).orEmpty())
    val required = (extra.filter { it.first }.map { it.second } + families.filter { it.first }.map { it.second })
    val other = extra.filter { !it.first }.map { it.second } + families.filter { !it.first }.map { it.second } +
      loose.map { LicenceNotice(it.key, it.licences.joinToString(", ") { l -> normaliseLicence(l) }, "", it.version, it.url.link()) }
    return LicenceBook(required.sortedBy { it.name.lowercase() }, other.sortedBy { it.name.lowercase() }, texts)
  }

  /** The generated file: group, artifact, version, licences joined by " | ", project url, notice file or "-". [noticeText] reads one notice file. */
  fun parseModules(tsv: String, noticeText: (String) -> String?): List<ModuleLicence> =
    tsv.lineSequence().filter { it.isNotBlank() && !it.startsWith("#") }.map { line ->
      val f = line.split('\t')
      require(f.size >= 6) { "licences.tsv: bad line '$line'" }
      ModuleLicence(f[0], f[1], f[2], f[3].split(" | ").map { it.trim() }.filter { it.isNotEmpty() }, f[4], f[5].takeIf { it != "-" }?.let(noticeText))
    }.toList()

  /** The hand-written file: section (required|other), name, version, licence, link, text. Returns (isRequired, notice). */
  fun parseExtra(tsv: String): List<Pair<Boolean, LicenceNotice>> =
    tsv.lineSequence().filter { it.isNotBlank() && !it.startsWith("#") }.map { line ->
      val f = line.split('\t')
      require(f.size >= 6) { "licences-extra.tsv: bad line '$line'" }
      (f[0] == "required") to LicenceNotice(f[1], f[3], f[5].replace("\\n", "\n"), f[2], f[4])
    }.toList()

  /** "The Apache Software License, Version 2.0" and its many spellings become "Apache 2.0"; what is not recognised stays as the POM wrote it. */
  fun normaliseLicence(raw: String): String {
    val l = raw.trim().lowercase()
    return when {
      l.contains("apache") -> "Apache 2.0"
      l.startsWith("lgpl-2.1") || l.contains("lgpl") && l.contains("2.1") -> "LGPL 2.1 or later"
      l == "mit" || l.startsWith("mit ") || l.contains("mit license") -> "MIT"
      l.contains("public domain") -> "Public domain"
      l.startsWith("bsd") -> "BSD style"
      else -> raw.trim()
    }
  }

  /** What a family of jars is called and says. [headline] is the jar whose version stands for the family. */
  private class Family(
    val name: String,
    val text: String,
    val link: String,
    val headline: String?,
    val match: (ModuleLicence) -> Boolean,
  )

  private fun starts(m: ModuleLicence, vararg groups: String) = groups.any { m.group == it || m.group.startsWith("$it.") }

  private val families: List<Family> = listOf(
    Family(
      "Compose Multiplatform and Material 3, by JetBrains and Google",
      "The window and every screen in it.", "github.com/JetBrains/compose-multiplatform", "org.jetbrains.compose.runtime:runtime-desktop",
    ) { starts(it, "org.jetbrains.compose", "androidx.compose", "org.jetbrains.androidx") },
    Family(
      "androidx.sqlite, by Google",
      "Opens the search index. It brings its own copy of SQLite (public domain) with word search built in, the same driver the phone uses.",
      "developer.android.com/jetpack/androidx/releases/sqlite", "androidx.sqlite:sqlite-bundled-jvm",
    ) { it.group == "androidx.sqlite" },
    Family(
      "AndroidX libraries, by Google", "Support libraries Compose builds on: lifecycle, saved state, collections and annotations.",
      "developer.android.com/jetpack", null,
    ) { starts(it, "androidx") },
    Family(
      "Skiko, by JetBrains", "Draws the window. Comes with its native library for this computer.", "github.com/JetBrains/skiko", "org.jetbrains.skiko:skiko-awt",
    ) { starts(it, "org.jetbrains.skiko") },
    Family(
      "Kotlin and kotlinx, by JetBrains",
      "The language this app is written in, with kotlinx.coroutines for doing several things at once.", "kotlinlang.org", "org.jetbrains.kotlin:kotlin-stdlib",
    ) { it.group == "org.jetbrains.kotlin" || it.group == "org.jetbrains.kotlinx" },
    Family(
      "LiteRT-LM, by Google", "Runs EmbeddingGemma 2 on this computer.", "github.com/google-ai-edge/LiteRT-LM", "com.google.ai.edge.litertlm:litertlm-jvm",
    ) { it.group == "com.google.ai.edge.litertlm" },
    Family(
      "sherpa-onnx, by the k2-fsa project",
      "Turns speech into words and names sounds in videos and recordings, on this computer. Its native library has ONNX Runtime by Microsoft (MIT) linked inside.",
      "github.com/k2-fsa/sherpa-onnx", "com.k2fsa.sherpa.onnx:sherpa-onnx-jvm",
    ) { it.group == "com.k2fsa.sherpa.onnx" },
    Family(
      "Apache PDFBox", "Reads the text in PDF files. Its NOTICE files:", "pdfbox.apache.org", "org.apache.pdfbox:pdfbox",
    ) { it.group == "org.apache.pdfbox" },
    Family(
      "Apache Commons Logging", "Comes with PDFBox. Its NOTICE file:", "commons.apache.org/proper/commons-logging", null,
    ) { it.group == "commons-logging" },
    Family(
      "JNA, by the JNA project",
      "Asks the operating system questions Java cannot, such as whether the computer is on battery. Dual licensed; used under the Apache 2.0 terms.",
      "github.com/java-native-access/jna", "net.java.dev.jna:jna",
    ) { it.group == "net.java.dev.jna" },
    Family(
      "ONNX Runtime, by Microsoft",
      "Runs the Privacy add-on, the model that tells whether a picture looks private. Used only after the add-on is installed.",
      "onnxruntime.ai", "com.microsoft.onnxruntime:onnxruntime",
    ) { it.group == "com.microsoft.onnxruntime" },
    Family(
      "android-json (org.json from the Android SDK), packaged by Vaadin",
      "The org.json classes of the Android SDK, so the code shared with the phone reads JSON exactly as the phone does.",
      "central.sonatype.com/artifact/com.vaadin.external.google/android-json", "com.vaadin.external.google:android-json",
    ) { it.group == "com.vaadin.external.google" },
    Family(
      "kXML 2, by Stefan Haustein",
      "Reads the XML inside office documents. The kXML classes are under a BSD-style licence whose terms are the MIT licence (the copyright line is in the MIT text below); the XmlPull API classes in it are public domain.",
      "kxml.sourceforge.net", "net.sf.kxml:kxml2",
    ) { it.group == "net.sf.kxml" },
    Family(
      "TwelveMonkeys ImageIO, by Harald Kuhr",
      "Opens WebP, Photoshop, TGA, PNM, PCX and icon pictures. Its licence text is below.", "github.com/haraldk/TwelveMonkeys", "com.twelvemonkeys.imageio:imageio-webp",
    ) { it.group == "com.twelvemonkeys.imageio" || it.group == "com.twelvemonkeys.common" },
    Family(
      "Small support libraries", "Helpers the libraries above need: annotations, Gson, JSpecify and the JetBrains Runtime API.", "",
      null,
    ) { it.key in SUPPORT || starts(it, "org.jspecify", "com.google.errorprone", "com.google.code.gson", "org.jetbrains.runtime") },
  )

  private val SUPPORT = setOf("org.jetbrains:annotations")

  /**
   * Folds the jars into families. A family whose jars carry a NOTICE file goes with the notices the licences require (Apache 2.0
   * section 4(d)). Returns (isRequired, row) pairs, and the jars no family claims so they still show, under their own coordinates.
   */
  fun fold(modules: List<ModuleLicence>): Pair<List<Pair<Boolean, LicenceNotice>>, List<ModuleLicence>> {
    val claimed = HashSet<String>()
    val out = ArrayList<Pair<Boolean, LicenceNotice>>()
    for (fam in families) {
      val members = modules.filter { it.key !in claimed && fam.match(it) }
      if (members.isEmpty()) continue
      claimed += members.map { it.key }
      val notices = members.mapNotNull { it.notice?.trim() }.filter { it.isNotEmpty() }.distinct()
      val licence = members.flatMap { m -> m.licences.map { normaliseLicence(it) } }.distinct().joinToString(", ")
      val headline = members.firstOrNull { it.key == fam.headline }?.version
        ?: members.map { it.version }.distinct().singleOrNull().orEmpty()
      val text = if (notices.isEmpty()) fam.text else fam.text + "\n\n" + notices.joinToString("\n\n")
      out += notices.isNotEmpty() to LicenceNotice(fam.name, licence, text, headline, fam.link, includes(members))
    }
    return out to modules.filter { it.key !in claimed }
  }

  /** "1.12.2: runtime, foundation; 1.9.0: material3": the artifacts of a family, grouped by version, with the platform suffix dropped. */
  fun includes(members: List<ModuleLicence>): String {
    if (members.size < 2) return ""
    return members.groupBy { it.version }.entries.sortedWith(compareByDescending<Map.Entry<String, List<ModuleLicence>>> { it.value.size }.thenBy { it.key })
      .joinToString("; ") { (v, ms) ->
        v + ": " + ms.map { it.artifact.removeSuffix("-desktop").removeSuffix("-jvm") }.distinct().sorted().joinToString(", ")
      }
  }

  private fun String.link(): String = removePrefix("https://").removePrefix("http://").trimEnd('/')
}
