package com.craneai.findr.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Trawl Go's own copy (src/go/res) replaces the full app's strings about meaning and models. Each one must override a real string,
 * and every language the app ships must have all of them, with the placeholders and app-name span of the Go English.
 */
class GoStringsTest {
  private val main = File("src/main/res")
  private val go = File("src/go/res")

  @Test fun everyGoStringOverridesARealOne() {
    val english = TranslationCheck.parseFolder(File(main, "values"))
    val goEnglish = TranslationCheck.parseFolder(File(go, "values"))
    assertTrue("no Go strings found at ${go.absolutePath}", goEnglish.isNotEmpty())
    val stray = goEnglish.keys.filter { it !in english }
    assertTrue("Go strings that override nothing: $stray", stray.isEmpty())
    for ((name, e) in goEnglish) {
      assertEquals("$name: same placeholders as the full app's English, or the code that fills it in breaks",
        TranslationCheck.argumentIndexes(english.getValue(name).piece!!.text), TranslationCheck.argumentIndexes(e.piece!!.text))
    }
  }

  @Test fun everyShippedLanguageHasAllOfThem() {
    val goEnglish = TranslationCheck.parseFolder(File(go, "values"))
    val appName = TranslationCheck.parseFolder(File(main, "values"))["app_name"]?.piece?.text.orEmpty()
    val shipped = TranslationCheck.languageFolders(main).keys
    val have = TranslationCheck.languageFolders(go)
    assertEquals("languages with Go strings", shipped, have.keys)
    val all = have.flatMap { (tag, dir) ->
      val tr = TranslationCheck.parseFolder(dir)
      val missing = goEnglish.keys.filter { it !in tr }.map { "$tag: missing $it" }
      missing + TranslationCheck.problems(goEnglish, tr, appName).map { "$tag: $it" }
    }
    assertTrue("Go translation problems:\n" + all.joinToString("\n"), all.isEmpty())
  }
}
