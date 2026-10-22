package com.craneai.findr.ui.common

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The app's name lives in one string (app_name), so renaming the app is a one-file change. Copy that says the name carries an
 * `<xliff:g id="app_name">` placeholder as its LAST positional argument, which appString() fills in; this keeps it that way:
 *  - the name is written nowhere else in the English strings;
 *  - every app_name placeholder is the last argument of its string (appString passes the name after the caller's arguments);
 *  - Kotlin never reads such a string with plain stringResource/getString (it would show "%1$s").
 */
class AppNameTest {
  private val res = listOf(File("src/main/res"), File("app/src/main/res")).first { it.isDirectory }
  private val src = File(res.parentFile, "java")
  private val englishFiles = File(res, "values").listFiles { f -> f.name.startsWith("strings") && f.extension == "xml" }!!.toList()

  private val stringRe = Regex("""<string name="([a-z0-9_]+)"([^>]*)>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
  private val nameSlot = Regex("""<xliff:g id="app_name"[^>]*>%(\d+)\${'$'}s</xliff:g>""")
  private val positional = Regex("""%(\d+)\$""")

  private val appName: String by lazy {
    englishFiles.firstNotNullOf { f -> stringRe.findAll(f.readText()).firstOrNull { it.groupValues[1] == "app_name" }?.groupValues?.get(3) }
  }

  /** Strings whose copy says the app's name, with the index of the name's slot. */
  private val nameStrings: Map<String, Int> by lazy {
    englishFiles.flatMap { f ->
      stringRe.findAll(f.readText()).mapNotNull { m -> nameSlot.find(m.groupValues[3])?.let { m.groupValues[1] to it.groupValues[1].toInt() } }.toList()
    }.toMap()
  }

  @Test fun theNameIsWrittenOnlyInAppName() {
    assertTrue("app_name is set", appName.isNotBlank())
    for (f in englishFiles) for (m in stringRe.findAll(f.readText())) {
      val (name, _, body) = m.destructured
      if (name == "app_name") continue
      val withoutSlots = nameSlot.replace(body, "")
      assertTrue("${f.name}: $name writes \"$appName\" instead of the app_name placeholder", !withoutSlots.contains(appName))
    }
    assertTrue("some copy says the name", nameStrings.isNotEmpty())
  }

  @Test fun theNameIsAlwaysTheLastArgument() {
    for (f in englishFiles) for (m in stringRe.findAll(f.readText())) {
      val (name, _, body) = m.destructured
      val slot = nameSlot.find(body)?.groupValues?.get(1)?.toInt() ?: continue
      val highest = positional.findAll(body).maxOf { it.groupValues[1].toInt() }
      assertEquals("$name: the name must be the last argument (appString passes it after the caller's)", highest, slot)
      // Formatted strings must escape a literal percent sign.
      assertTrue("$name has a bare %", !Regex("""%(?![0-9]+\$|%)""").containsMatchIn(body.replace("%%", "")))
    }
  }

  @Test fun kotlinReadsNameStringsThroughAppString() {
    val names = nameStrings.keys
    val offenders = ArrayList<String>()
    src.walk().filter { it.isFile && it.extension == "kt" }.forEach { f ->
      f.readLines().forEachIndexed { i, line ->
        for (n in names) {
          if (!Regex("""R\.string\.$n\b""").containsMatchIn(line)) continue
          val plain = Regex("""(stringResource|getString|getText)\([^)]*R\.string\.$n\b""").containsMatchIn(line)
          if (plain && !line.contains("appString(")) offenders += "${f.path}:${i + 1}: ${line.trim()}"
        }
      }
    }
    assertTrue("Read these with appString(...), not stringResource/getString:\n" + offenders.joinToString("\n"), offenders.isEmpty())
  }

  @Test fun sideBuildsRelabelOnlyTheLauncher() {
    val manifest = File(res.parentFile, "AndroidManifest.xml").readText()
    assertTrue("the launcher shows launcher_label", manifest.contains("""android:label="@string/launcher_label""""))
    val launcher = englishFiles.firstNotNullOf { f -> stringRe.findAll(f.readText()).firstOrNull { it.groupValues[1] == "launcher_label" } }
    assertEquals("@string/app_name", launcher.groupValues[3])
  }
}
