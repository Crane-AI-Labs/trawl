package com.craneai.findr.engine

import com.craneai.findr.search.Fusion
import com.craneai.findr.search.LocalKeywords
import com.craneai.findr.search.LocalLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.GZIPInputStream

/**
 * The shipped local-language -> English keyword table (assets/local_keywords.txt.gz): Luganda and Swahili
 * (scripts/build_local_keywords_clean.py) plus Runyankole, Kinyarwanda and Acholi (scripts/build_local_keywords_more.py). Small,
 * attributed (the Makerere corpora, MPTC, BUAIR and TICO-19, CC BY 4.0; no SALT), and every English word in it is a plain search word the
 * words side can use as it is.
 */
class LocalKeywordsAssetTest {
  private val file = File("src/main/assets/${LocalKeywords.ASSET}")
  private val lines = GZIPInputStream(file.inputStream()).bufferedReader().readLines()
  private val table = file.inputStream().use { LocalKeywords.parse(it) }

  @Test fun smallAndAttributed() {
    assertTrue("${file.length()} bytes", file.length() < 200_000)
    assertTrue(table.size in 20_000..30_000)
    val header = lines.filter { it.startsWith("#") }.joinToString("\n")   // the lg/sw header, and the other languages' after their rows
    for (s in listOf("no SALT", "Common Voice", "DeepSeek-V4-Pro (MIT)", "Makerere MT Corpus", "CC BY 4.0", "zenodo.org/records/5089560", "TICO-19",
      "no sentence", "Runyankole (nyn)", "Kinyarwanda (kin)", "Acholi (ach)", "doi:10.7910/DVN/BEROE0", "BUAIR", "Busitema"))
      assertTrue("header lacks $s", header.contains(s))
    assertTrue("no ShareAlike source", !header.contains("BY-SA") && !header.contains("Sunbird"))
  }

  @Test fun everydaySearchWordsAreThere() {
    fun en(word: String) = table.english(listOf(Fusion.nounClassForms(word))).single()
    assertTrue(en("lisiiti").contains("receipt"))
    assertTrue(en("embaga").contains("wedding"))
    assertTrue(en("ddwaliro").contains("hospital"))
    assertTrue(en("ebisale").contains("fees"))
    assertTrue(en("ada").contains("fee"))
    assertTrue(en("harusi").contains("wedding"))
    assertTrue(en("hospitali").contains("hospital"))
    assertTrue(en("shule").contains("school"))
    fun en(language: LocalLanguage, word: String) = table.english(language, listOf(table.forms(word, language))).single()
    assertTrue(en(LocalLanguage.NYN, "ishomero").contains("school"))
    assertTrue(en(LocalLanguage.NYN, "irwariro").contains("hospital"))
    assertTrue(en(LocalLanguage.KIN, "ubukwe").contains("wedding"))
    assertTrue(en(LocalLanguage.KIN, "ibitaro").contains("hospital"))
    assertTrue(en(LocalLanguage.ACH, "cente").contains("money"))
  }

  @Test fun theOtherLanguagesRowsNeverHoldACommonEnglishWordOrAGrammarWord() {
    val common = GZIPInputStream(File("src/main/assets/${SpeechLanguageGate.COMMON_ASSET}").inputStream()).bufferedReader().readLines().toSet()
    val rows = lines.filter { !it.startsWith("#") && it.isNotBlank() }.map { it.split('\t') }
    val codes = rows.map { it[0] }.toSet()
    assertEquals(setOf("lg", "sw", "nyn", "kin", "ach"), codes)   // no Ateso (measured, did not help) and no Lugbara (no clean source)
    for ((lang, word) in rows.filter { it[0] !in setOf("lg", "sw") }) {
      val l = LocalLanguage.of(lang)!!
      assertTrue("$lang $word", word !in common && word !in l.grammar)
    }
  }

  @Test fun aSwahiliApostropheWordIsGlossedWholeNotByItsFragment() {
    // "ng'ombe" (cow) is one Swahili word: the table holds it whole, however the keyboard types the apostrophe, and the query reads it
    // whole ("ombe" alone, the fragment the old split made, was glossed "pray")
    for (q in listOf("picha za ng'ombe shambani", "picha za ng\u2019ombe shambani", "picha za ng`ombe shambani")) {
      assertEquals(q, listOf("ng'ombe", "shambani"), table.terms(q))
      val gloss = table.gloss(q).orEmpty().split(' ')
      assertTrue("$q: $gloss", "cow" in gloss || "cattle" in gloss)
      assertTrue("$q: $gloss", "pray" !in gloss)
    }
    // the local words of every entry: one table word each (an apostrophe only inside a Swahili word, between two letters)
    for (l in lines.filter { !it.startsWith("#") && it.isNotBlank() }) {
      val (lang, word) = l.split('\t')
      if ('\'' in word) {
        assertEquals(l, "sw", lang)
        assertTrue(l, Regex("[\\p{L}\\p{N}]+('[\\p{L}\\p{N}]+)+").matches(word))
      } else assertEquals(l, listOf(word), LocalKeywords(emptyMap(), emptyMap()).words(word))   // Fusion.words, as a query splits
    }
  }

  @Test fun everyEnglishWordIsOneSearchWordAndNoStopword() {
    val english = lines.filter { !it.startsWith("#") && it.isNotBlank() }.flatMap { it.split('\t')[2].split(' ') }.toSet()
    assertTrue(english.size > 1000)
    for (w in english) assertEquals(w, listOf(w), Fusion.terms(w))
  }
}
