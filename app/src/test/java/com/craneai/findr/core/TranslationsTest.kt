package com.craneai.findr.core

import com.craneai.findr.core.TranslationCheck.problems
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Every translation (res/values-<language>/strings*.xml) must keep what the code depends on: the same placeholders as English,
 * the app-name span, valid plural keywords, escaped apostrophes, and no literal app name. A bad translation fails here rather
 * than as a crash or a "%1$s" on a screen. With no translations present the test passes. The rules live in [TranslationCheck];
 * scripts/i18n_import.py applies the same ones before a file enters res/.
 */
class TranslationsTest {
  @get:Rule val tmp = TemporaryFolder()

  private val res = File("src/main/res")

  @Test fun everyTranslationFollowsTheRules() {
    assertTrue("run from the app module: ${res.absolutePath}", res.isDirectory)
    val english = TranslationCheck.parseFolder(File(res, "values"))
    assertTrue("English strings not found", english.isNotEmpty())
    val appName = english["app_name"]?.piece?.text.orEmpty()
    assertTrue("app_name must be a translatable=\"false\" string", english["app_name"]?.translatable == false)
    val all = TranslationCheck.languageFolders(res).flatMap { (tag, dir) ->
      problems(english, TranslationCheck.parseFolder(dir), appName).map { "$tag: $it" }
    }
    assertTrue("Translation problems:\n" + all.joinToString("\n"), all.isEmpty())
  }

  /** Every English string that mentions the app name carries it as the xliff placeholder: the literal never appears in a sentence. */
  @Test fun englishNeverWritesTheAppNameInASentence() {
    val english = TranslationCheck.parseFolder(File(res, "values"))
    val appName = english["app_name"]!!.piece!!.text
    val bad = english.values.filter { it.name != "app_name" && it.translatable }.flatMap { e ->
      (if (e.plural) e.items.values else listOfNotNull(e.piece)).filter { p ->
        // Remove the span contents (the "example" attribute is not text) and look for the bare name.
        Regex("\\b" + Regex.escape(appName) + "\\b").containsMatchIn(p.text.replace(p.appNameSlots.joinToString("|").ifEmpty { "\u0000" }.toRegex(), ""))
      }.map { e.name }
    }
    assertTrue("These English strings write \"$appName\" instead of the placeholder: $bad", bad.isEmpty())
  }

  // ---- the checker itself, on synthetic files ---------------------------------------------------------------------

  private val xl = "xmlns:xliff=\"urn:oasis:names:tc:xliff:document:1.2\""

  private fun file(name: String, body: String): File =
    tmp.newFile(name).also { it.writeText("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources $xl>\n$body\n</resources>\n") }

  private val englishBody = """
    <string name="app_name" translatable="false">Trawl</string>
    <string name="plain">Hello</string>
    <string name="with_count">%1${'$'}d files in %2${'$'}s</string>
    <string name="with_app"><xliff:g id="app_name" example="Trawl">%1${'$'}s</xliff:g> is ready</string>
    <string name="with_both">Found %1${'$'}d in <xliff:g id="app_name" example="Trawl">%2${'$'}s</xliff:g></string>
    <string name="percent">%1${'$'}d%% done</string>
    <string name="unnumbered">%d of %s</string>
    <string name="keep" translatable="false">https://example.org</string>
    <plurals name="files">
      <item quantity="one">%1${'$'}d file</item>
      <item quantity="other">%1${'$'}d files</item>
    </plurals>
  """.trimIndent()

  private fun check(translationBody: String): List<String> {
    val en = TranslationCheck.parse(file("en-${System.nanoTime()}.xml", englishBody))
    val tr = TranslationCheck.parse(file("tr-${System.nanoTime()}.xml", translationBody))
    return problems(en, tr, "Trawl")
  }

  @Test fun aGoodTranslationPasses() {
    val good = """
      <string name="plain">Bonjour l\'ami</string>
      <string name="with_count">%2${'$'}s contient %1${'$'}d fichiers</string>
      <string name="with_app"><xliff:g id="app_name" example="Trawl">%1${'$'}s</xliff:g> est prêt</string>
      <string name="with_both">%1${'$'}d trouvés dans <xliff:g id="app_name" example="Trawl">%2${'$'}s</xliff:g></string>
      <string name="percent">%1${'$'}d%% terminé</string>
      <string name="unnumbered">%s sur %d</string>
      <plurals name="files">
        <item quantity="one">%1${'$'}d fichier</item>
        <item quantity="many">%1${'$'}d de fichiers</item>
        <item quantity="other">%1${'$'}d fichiers</item>
      </plurals>
    """.trimIndent()
    assertEquals(emptyList<String>(), check(good))
  }

  @Test fun anEmptyTranslationPasses() = assertEquals(emptyList<String>(), check(""))

  @Test fun unknownAndNonTranslatableNamesFail() {
    assertTrue(check("""<string name="nope">x</string>""").single().contains("not in the English"))
    assertTrue(check("""<string name="keep">x</string>""").single().contains("translatable"))
    assertTrue(check("""<string name="app_name">Foo</string>""").single().contains("translatable"))
  }

  @Test fun placeholdersMustMatchEnglish() {
    assertTrue(check("""<string name="with_count">%1${'$'}d fichiers</string>""").single().contains("placeholders"))
    assertTrue(check("""<string name="with_count">%1${'$'}s fichiers dans %2${'$'}s</string>""").single().contains("placeholders"))
    assertTrue(check("""<string name="percent">%1${'$'}d pour cent</string>""").single().contains("placeholders"))
    assertTrue(check("""<string name="percent">%1${'$'}d% done</string>""").isNotEmpty())
    assertTrue(check("""<string name="unnumbered">%d sur %d</string>""").single().contains("placeholders"))
    assertTrue(check("""<string name="plain">Bonjour %s</string>""").single().contains("placeholders"))
  }

  @Test fun theAppNameSpanMustStayAndKeepItsIndex() {
    assertTrue(check("""<string name="with_app">%1${'$'}s est prêt</string>""").single().contains("app name span"))
    assertTrue(check("""<string name="with_app"><xliff:g id="app_name" example="Trawl">%2${'$'}s</xliff:g> est prêt</string>""").isNotEmpty())
    assertTrue(check("""<string name="with_both">%1${'$'}d dans <xliff:g id="app_name" example="Trawl">%1${'$'}s</xliff:g></string>""").isNotEmpty())
  }

  @Test fun theLiteralAppNameIsRefused() {
    val p = check("""<string name="plain">Bienvenue dans Trawl</string>""")
    assertTrue(p.single().contains("app name"))
    // Even inside the span's example attribute-free text: the span holds a placeholder, never the name.
    assertTrue(check("""<string name="with_app"><xliff:g id="app_name">Trawl</xliff:g> est prêt</string>""").isNotEmpty())
  }

  @Test fun apostrophesAndQuotesMustBeEscaped() {
    assertTrue(check("""<string name="plain">l'ami</string>""").single().contains("apostrophe"))
    // A doubled backslash escapes itself, so the quote after it is bare (aapt rejects it): Hausa "ma\\'ana", 2026-10-15.
    assertTrue(check("""<string name="plain">ma\\'ana</string>""").single().contains("apostrophe"))
    assertTrue(check("""<string name="plain">l\'ami</string>""").isEmpty())
    assertTrue(check("""<string name="plain">"l'ami"</string>""").isEmpty())   // wholly quoted is fine
    assertTrue(check("""<string name="plain">dit "oui"</string>""").single().contains("double quote"))
    assertTrue(check("""<string name="plain">@oui</string>""").single().contains("@"))
  }

  @Test fun pluralsFollowTheirOwnRules() {
    fun plural(items: String) = check("""<plurals name="files">$items</plurals>""")
    assertTrue(plural("""<item quantity="one">un</item><item quantity="other">%1${'$'}d</item>""").isEmpty())
    assertTrue(plural("""<item quantity="other">fichiers</item>""").single().contains("other"))
    assertTrue(plural("""<item quantity="one">%1${'$'}d</item>""").single().contains("no \"other\""))
    assertTrue(plural("""<item quantity="several">%1${'$'}d</item><item quantity="other">%1${'$'}d</item>""").single().contains("keyword"))
    // An item may use fewer placeholders than English, never one English does not have.
    assertTrue(plural("""<item quantity="one">un fichier</item><item quantity="other">%1${'$'}d fichiers</item>""").isEmpty())
    assertTrue(plural("""<item quantity="one">%2${'$'}s</item><item quantity="other">%1${'$'}d fichiers</item>""").single().contains("English does not"))
    assertTrue(plural("""<item quantity="other">%1${'$'}d fichiers</item>""").isEmpty())
  }

  @Test fun aStringCannotBecomeAPlural() {
    assertTrue(check("""<plurals name="plain"><item quantity="other">x</item></plurals>""").single().contains("<plurals>"))
  }

  @Test fun folderNamesMapToLanguageTags() {
    assertEquals("fr", TranslationCheck.languageOfFolder("values-fr"))
    assertEquals("nyn", TranslationCheck.languageOfFolder("values-b+nyn"))
    assertEquals("pt-BR", TranslationCheck.languageOfFolder("values-pt-rBR"))
    assertEquals("sr-Latn", TranslationCheck.languageOfFolder("values-b+sr+Latn"))
    for (n in listOf("values", "values-night", "values-sw600dp", "values-v31", "values-land", "drawable-hdpi", "xml")) {
      assertEquals(n, null, TranslationCheck.languageOfFolder(n))
    }
  }

  @Test fun placeholderParsing() {
    assertEquals(listOf("%1\$s", "%2\$d", "%%"), TranslationCheck.placeholders("a %1\$s b %2\$d c 100%%"))
    assertEquals(listOf("%d", "%s"), TranslationCheck.placeholders("%d of %s"))
    assertEquals(setOf(1, 2), TranslationCheck.argumentIndexes("%d of %s"))
    assertEquals(setOf(2), TranslationCheck.argumentIndexes("50%% of %2\$s"))
  }

  @Test fun theFolderParserMergesEveryStringsFileAndSkipsOthers() {
    val dir = tmp.newFolder("values-fr")
    File(dir, "strings.xml").writeText("<resources $xl><string name=\"a\">A</string></resources>")
    File(dir, "strings_more.xml").writeText("<resources $xl><string name=\"b\">B</string></resources>")
    File(dir, "colors.xml").writeText("<resources><color name=\"c\">#fff</color></resources>")
    assertEquals(setOf("a", "b"), TranslationCheck.parseFolder(dir).keys)
  }
}
