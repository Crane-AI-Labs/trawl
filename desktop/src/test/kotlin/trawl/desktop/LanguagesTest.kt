package trawl.desktop

import java.io.InputStreamReader
import java.util.Locale
import java.util.Properties
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** The language picker and the word files behind it. */
class LanguagesTest {
  @AfterTest fun backToEnglish() = Strings.use(Locale.ENGLISH)

  private fun props(tag: String): Properties? =
    LanguagesTest::class.java.classLoader.getResourceAsStream("trawl/strings_$tag.properties")?.use { Properties().apply { load(InputStreamReader(it, Charsets.UTF_8)) } }

  private val translated = Languages.shipped.filter { it.tag != "en" }
  private val placeholder = Regex("""%(?:\d+\$)?[sdf]""")

  @Test fun theListIsTheFourteenTheAndroidAppShips() {
    assertEquals(
      listOf("en", "fr", "pt", "ar", "sw", "lg", "rw", "nyn", "ach", "am", "ha", "yo", "so", "zu").sorted(),
      Languages.shipped.map { it.tag }.sorted(),
    )
    assertEquals(setOf("nyn", "ach"), Languages.shipped.filter { it.preview }.map { it.tag }.toSet(), "the two the phone calls a preview")
    assertEquals(Languages.shipped.size, Languages.shipped.map { it.tag }.toSet().size)
    assertTrue(Languages.shipped.all { it.nativeName.isNotBlank() })
  }

  @Test fun everyShippedLanguageHasAWordFileThatLoads() {
    for (l in translated) {
      val p = props(l.tag)
      assertTrue(p != null && p.size >= 40, "${l.tag}: ${p?.size} lines")
    }
  }

  @Test fun aTranslationNamesNoKeyEnglishLacksAndKeepsEnglishsPlaceholders() {
    val eng = Properties().apply { LanguagesTest::class.java.classLoader.getResourceAsStream("trawl/strings.properties")!!.use { load(InputStreamReader(it, Charsets.UTF_8)) } }
    for (l in translated) {
      val p = props(l.tag)!!
      for (k in p.stringPropertyNames()) {
        // A language with more counted forms than English (Arabic: zero, two, few, many) writes them beside English's "_other".
        val englishKey = if (k in eng.stringPropertyNames()) k else k.replace(Regex("_(zero|two|few|many)$"), "_other")
        assertTrue(englishKey in eng.stringPropertyNames(), "${l.tag}: '$k' is not an English key")
        assertEquals(
          placeholder.findAll(eng.getProperty(englishKey)).map { it.value.replace(Regex("""^%\d+\$"""), "%") }.sorted().toList(),
          placeholder.findAll(p.getProperty(k)).map { it.value.replace(Regex("""^%\d+\$"""), "%") }.sorted().toList(),
          "${l.tag}: placeholders of '$k' differ from English",
        )
        // The app's name is written out in the file, never left as a placeholder for it.
        assertTrue(!p.getProperty(k).contains("\u0000"), "${l.tag}: $k")
      }
    }
  }

  @Test fun eachLanguageAnswersInItsOwnWordsAndFallsBackToEnglishForTheRest() {
    val english = Strings.keys().associateWith { k -> Strings.use(Locale.ENGLISH); t(k, 1, 2, 3, 4) }
    for (l in translated) {
      Strings.use(Locale.forLanguageTag(l.tag))
      assertEquals(l.tag, Strings.language)
      val differing = english.keys.count { k -> t(k, 1, 2, 3, 4) != english.getValue(k) }
      assertTrue(differing >= 40, "${l.tag}: only $differing words differ from English")
      // A key with no translation shows English, never "[key]" and never blank.
      for ((k, en) in english) {
        val shown = t(k, 1, 2, 3, 4)
        assertTrue(shown.isNotBlank() && !shown.startsWith("[$k]"), "${l.tag}: '$k' shows '$shown'")
        if (props(l.tag)!!.getProperty(k) == null) assertEquals(en, shown, "${l.tag}: untranslated '$k' must show English")
      }
    }
  }

  @Test fun theSameKeyReadsDifferentlyInFrenchAndEnglish() {
    Strings.use(Locale.ENGLISH); val en = t("filter_all")
    Strings.use(Locale.FRENCH); val fr = t("filter_all")
    assertEquals("All", en)
    assertEquals("Tout", fr)
    assertNotEquals(en, fr)
    assertEquals("Version 1.0.0", run { Strings.use(Locale.ENGLISH); t("settings_about_version", "1.0.0") })
  }

  @Test fun anUnknownLanguageLeavesEnglish() {
    Strings.use(Locale.forLanguageTag("xx"))
    assertEquals("All", t("filter_all"))
  }
}
