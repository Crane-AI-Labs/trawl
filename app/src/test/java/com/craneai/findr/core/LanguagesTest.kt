package com.craneai.findr.core

import com.craneai.findr.ui.language.AppLanguages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * The three places that list the app's languages must agree: [AppLanguages.shipped], res/xml/locales_config.xml (what Android's
 * per-app language setting offers) and the res/values-<language> folders that hold the strings. A language is added in all
 * three at once, and only when its translation is complete enough to ship. English is the default `values` folder.
 */
class LanguagesTest {
  private val res = File("src/main/res")

  private fun localesConfig(): List<String> {
    val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(File(res, "xml/locales_config.xml"))
    val nodes = doc.getElementsByTagName("locale")
    return (0 until nodes.length).map { (nodes.item(it) as Element).getAttributeNS("http://schemas.android.com/apk/res/android", "name") }
  }

  /** Language folders that really hold strings, plus English (the default `values`). */
  private fun foldersWithStrings(): Set<String> =
    TranslationCheck.languageFolders(res).filter { (_, dir) -> File(dir, "strings.xml").isFile }.keys + "en"

  @Test fun shippedMatchesLocalesConfig() {
    assertTrue(res.isDirectory)
    assertEquals(AppLanguages.shipped.map { it.tag }, localesConfig())
  }

  @Test fun shippedMatchesTheStringFolders() {
    assertEquals(AppLanguages.shipped.map { it.tag }.toSet(), foldersWithStrings())
  }

  @Test fun englishComesFirstAndEveryoneHasNames() {
    assertEquals("en", AppLanguages.shipped.first().tag)
    assertEquals("en", AppLanguages.planned.first().tag)
    for (l in AppLanguages.shipped + AppLanguages.planned) {
      assertTrue("${l.tag}: nativeName is empty", l.nativeName.isNotBlank())
      assertTrue("${l.tag}: englishName is empty", l.englishName.isNotBlank())
    }
  }

  @Test fun tagsAreValidBcp47AndUnique() {
    for (l in AppLanguages.planned) {
      val back = Locale.forLanguageTag(l.tag).toLanguageTag()
      assertEquals("${l.tag} does not round-trip as a BCP 47 tag", l.tag, back)
    }
    assertEquals(AppLanguages.shipped.size, AppLanguages.shipped.map { it.tag }.toSet().size)
    assertEquals(AppLanguages.planned.size, AppLanguages.planned.map { it.tag }.toSet().size)
  }

  @Test fun shippedIsASubsetOfPlannedWithTheSameNames() {
    val planned = AppLanguages.planned.associateBy { it.tag }
    for (l in AppLanguages.shipped) assertEquals("${l.tag} is shipped but not planned (or named differently)", l, planned[l.tag]?.copy(preview = l.preview))
  }

  @Test fun theValuesQualifierMatchesTheFolderName() {
    for (l in AppLanguages.planned) {
      val folder = "values-" + l.valuesQualifier
      assertEquals("${l.tag} -> $folder", l.tag, TranslationCheck.languageOfFolder(folder))
    }
  }
}
