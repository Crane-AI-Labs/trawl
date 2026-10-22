package trawl.desktop

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SettingsAndStringsTest {
  @Test fun settingsRoundTripAndSurviveGarbage() {
    val dir = Files.createTempDirectory("trawl-settings").toFile()
    val store = SettingsStore(dir)
    assertEquals(Settings(), store.load())   // no file: first run
    val s = Settings(onboarded = true, folders = listOf("/a", "/b c"), theme = ThemeChoice.DARK, modelsDeclined = true)
    store.save(s)
    assertEquals(s, store.load())
    File(dir, "settings.json").writeText("{ not json")
    assertEquals(Settings(), store.load())
    dir.deleteRecursively()
  }

  /** Every `t("key")` in the UI code names a key that the English file has: a typo would show "[key]" to a person. */
  @Test fun everyStringKeyTheCodeAsksForExists() {
    val root = File("src/main/kotlin")
    val literal = Regex("""\bt\("([a-z0-9_]+)"""")
    val used = root.walkTopDown().filter { it.extension == "kt" }.flatMap { f -> literal.findAll(f.readText()).map { it.groupValues[1] to f.name } }.toList()
    assertTrue(used.size > 50, "found only ${used.size} t(\"...\") calls; is the test run from desktop/?")
    val missing = used.filter { !Strings.has(it.first) }
    assertTrue(missing.isEmpty(), "missing keys: $missing")
  }

  /** Every counted `tn("key", n)` has the one and other forms in English, so no count can show a bare or missing form. */
  @Test fun everyCountedKeyHasOneAndOther() {
    val literal = Regex("""\btn\("([a-z0-9_]+)"""")
    val used = File("src/main/kotlin").walkTopDown().filter { it.extension == "kt" }.flatMap { f -> literal.findAll(f.readText()).map { it.groupValues[1] } }.toSet()
    assertTrue(used.size >= 8, "found only ${used.size} tn(\"...\") keys")
    for (k in used) assertTrue(Strings.has("${k}_one") && Strings.has("${k}_other"), "$k needs ${k}_one and ${k}_other")
  }

  /** The same rule for the same counts in a test of the lookup itself (the text carries the figure the caller formatted). */
  @Test fun pluralsPickTheRightFormForZeroOneTwoAndAThousand() {
    Strings.use(java.util.Locale.ENGLISH)
    assertEquals("0 photos", tn("count_photos", 0, "0"))
    assertEquals("1 photo", tn("count_photos", 1, "1"))
    assertEquals("2 photos", tn("count_photos", 2, "2"))
    assertEquals("1,000 photos", tn("count_photos", 1000, "1,000"))
    assertEquals("1 file", tn("search_count", 1, "1"))
    assertEquals("0 files", tn("search_count", 0, "0"))
    assertEquals("1 file is ready to search.", tn("meter_sub_up_to_date", 1, "1"))
    assertEquals("1,000 files are ready to search.", tn("meter_sub_up_to_date", 1000, "1,000"))
    assertEquals("Checking every second.", tn("settings_watch_polling", 1, 1))
    assertEquals("Checking every 30 seconds.", tn("settings_watch_polling", 30, 30))
    assertEquals("Files Trawl knows: 1 photo, 2 screenshots, 0 documents and PDFs", t("settings_storage_files", tn("count_photos", 1, "1"), tn("count_screenshots", 2, "2"), tn("count_documents_pdfs", 0, "0")))
    // with no figure given the count is written for the person, thousands separator included
    assertTrue(tn("count_photos", 1000).let { it.startsWith("1") && it.endsWith("photos") && it.length == "1,000 photos".length }, tn("count_photos", 1000))
  }

  /** Each language's own rule: French and Portuguese call 0 singular, Arabic has six forms, Yoruba one, and an unknown language uses English's. */
  @Test fun categoriesFollowEachLanguagesRule() {
    for (lang in listOf("en", "lg", "sw", "xx")) {
      assertEquals(listOf("other", "one", "other", "other"), listOf(0L, 1L, 2L, 1000L).map { Plurals.category(lang, it) }, lang)
    }
    assertEquals(listOf("one", "one", "other", "other"), listOf(0L, 1L, 2L, 1000L).map { Plurals.category("fr", it) })
    assertEquals(listOf("zero", "one", "two", "few", "many", "other"), listOf(0L, 1L, 2L, 5L, 11L, 100L).map { Plurals.category("ar", it) })
    assertEquals(setOf("other"), listOf(0L, 1L, 2L, 1000L).map { Plurals.category("yo", it) }.toSet())
  }

  @Test fun aLanguageWithAnOwnSingularUsesIt() {
    Strings.use(java.util.Locale.FRENCH)
    try {
      assertEquals("1 fichier est prêt pour la recherche.", tn("meter_sub_up_to_date", 1, "1"))
      assertEquals("0 fichier est prêt pour la recherche.", tn("meter_sub_up_to_date", 0, "0"))   // French counts 0 as singular
      assertEquals("2 fichiers sont prêts pour la recherche.", tn("meter_sub_up_to_date", 2, "2"))
    } finally { Strings.use(java.util.Locale.ENGLISH) }
  }

  /** A translation that gives one form for every count (every language file does today) still wins over English, and English fills what it lacks. */
  @Test fun aTranslationWithOneFormStillWorksAndEnglishFillsTheRest() {
    Strings.use(java.util.Locale.forLanguageTag("lg"))
    try {
      assertEquals("Fayiro 1 zeetegefu okunoonya.", tn("meter_sub_up_to_date", 1, "1"))   // only meter_sub_up_to_date_other is translated: it serves every count
      assertEquals("1 photo", tn("count_photos", 1, "1"))                                  // not translated: English, English's rule
      assertEquals("2 photos", tn("count_photos", 2, "2"))
    } finally { Strings.use(java.util.Locale.ENGLISH) }
  }

  @Test fun everyRefusalHasWords() {
    for (r in com.craneai.findr.core.Refusal.entries) assertTrue(Strings.has(ModelSetup.refusalKey(r)), "no text for $r")
  }

  /** The watcher lines are shown on every OS, so none of them names one OS's kernel feature (it said "inotify" on a Mac). */
  @Test fun watcherWordsNameNoOneOsFeature() {
    for (k in Strings.keys().filter { it.startsWith("settings_watch_") }) assertTrue(!t(k).contains("inotify", ignoreCase = true), "$k names inotify")
  }

  @Test fun missingKeysShowAsThemselves() {
    assertEquals("[no_such_key_xyz]", t("no_such_key_xyz"))
    assertTrue(Strings.keys().isNotEmpty())
  }
}
