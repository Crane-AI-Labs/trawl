package com.craneai.findr.engine

import com.craneai.findr.findrSource
import com.craneai.findr.search.Fusion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class QueryLanguageTest {
  private val words = File("src/main/assets/${SpeechLanguageGate.ASSET}").inputStream().use { EnglishWords.fromGzip(it) }
  private val lang = QueryLanguage(lazyOf(words))

  private fun non(q: String) = QueryLanguage.isNonEnglish(words, q)

  /** The app and scripts/bench/ww_gate.py (non_english at 0.6, names neutral) agree on every query of the golden file. */
  @Test fun `the classifier agrees with ww_gate on the golden D17 queries`() {
    val rows = javaClass.getResourceAsStream("/query_language_golden.tsv")!!.bufferedReader().readLines().filter { !it.startsWith("#") }
    assertTrue("golden file too small: ${rows.size}", rows.size >= 200)
    val wrong = ArrayList<String>()
    var n = 0; var e = 0
    for (row in rows) {
      val cls = row.substringBefore('\t'); val q = row.substringAfter('\t', "")
      if (cls == "N") n++ else e++
      if (non(q) != (cls == "N")) wrong += "expected $cls for query of ${q.length} chars"   // never print the text
    }
    assertTrue("both classes are covered ($n non-English, $e English)", n >= 40 && e >= 40)
    assertEquals("disagreements: $wrong", 0, wrong.size)
  }

  @Test fun `the policy is 4 for non-English and the stock 2 for English`() {
    assertEquals(4.0, lang.wordWeight("ekifaananyi ky'embuzi eky'omwana"), 0.0)
    assertEquals(Fusion.WORD_WEIGHT, lang.wordWeight("wedding photos from last year"), 0.0)
    assertEquals(2.0, Fusion.WORD_WEIGHT, 0.0)
    assertEquals(4.0, QueryLanguage.NON_ENGLISH_WORD_WEIGHT, 0.0)
  }

  @Test fun `names are neutral but a first-token name counts`() {
    assertFalse("a name after English words", non("invoice from Nakato"))
    assertFalse("names after an English first token", non("Receipt Kampala"))
    assertTrue("the first token is kept, and a name there is not English", non("Nakato Kampala Entebbe"))
    assertTrue("the first token is never dropped, even capitalised", non("Nakato nnyingi nnyo"))
    assertTrue(non("ekifaananyi Kampala ky'embuzi"))
  }

  @Test fun `digits and codes are neutral`() {
    assertFalse(non("2024 2025 12"))
    assertFalse(non("12345"))
    assertTrue("the letters of a code are tokens like any other (ww_gate does the same: D17 lists such codes as misreads)", non("KDC-260914-0072"))
    assertFalse(non("invoice 2024 12"))
  }

  @Test fun `a query with no a to z token is non-English only for another script`() {
    assertTrue(non("فاتورة الكهرباء"))
    assertTrue(non("電気代の請求書"))
    assertFalse(non(""))
    assertFalse(non("   "))
    assertFalse(non("12345"))
    assertFalse("another script mixed with a-z tokens is judged by the a-z tokens only", non("receipt فاتورة"))
  }

  @Test fun `contractions and curly apostrophes are English, Luganda apostrophes are not`() {
    assertFalse(non("don’t forget the milk"))
    assertFalse(non("he's gone"))
    assertTrue(non("ng'ombe n'amannyo"))
  }

  @Test fun `the threshold is strict at six tenths`() {
    // 3 of 5 tokens English = 0.6: not below the threshold, so English; 2 of 5 is non-English
    assertFalse(non("red car park xqzv wplk"))
    assertTrue(non("red car xqzv wplk ghtr"))
  }

  @Test fun `no word list means English and the stock weight`() {
    val none = QueryLanguage(lazyOf<EnglishWords?>(null))
    assertFalse(QueryLanguage.isNonEnglish(null, "ekifaananyi ky'embuzi eky'omwana"))
    assertEquals(Fusion.WORD_WEIGHT, none.wordWeight("ekifaananyi ky'embuzi eky'omwana"), 0.0)
    assertEquals(Fusion.WORD_WEIGHT, none.wordWeight("فاتورة الكهرباء"), 0.0)
  }

  @Test fun `a list that is not loaded yet is not waited for by the words-first answer`() {
    var loads = 0
    val cold = QueryLanguage(lazy { loads++; words })
    assertEquals("words-first: English, and the list is not loaded for it", Fusion.WORD_WEIGHT, cold.wordWeight("ekifaananyi ky'embuzi eky'omwana", mayLoad = false), 0.0)
    assertEquals(0, loads)
    assertEquals("the final answer loads it and uses it", 4.0, cold.wordWeight("ekifaananyi ky'embuzi eky'omwana", mayLoad = true), 0.0)
    assertEquals(1, loads)
    assertEquals("once loaded, the words-first answer uses it too", 4.0, cold.wordWeight("ekifaananyi ky'embuzi eky'omwana", mayLoad = false), 0.0)
    assertEquals("only the final answer is counted", "1 of 1 queries non-English", cold.counts)
  }

  @Test fun `only counts are kept and the class code logs no text`() {
    val src = findrSource("engine/QueryLanguage.kt").readText().substringAfter("class QueryLanguage")
    assertFalse("no logging in the classifier", Regex("""\bLog\b|println|FindrLog|Timber""").containsMatchIn(src))
    val c = QueryLanguage(lazyOf(words))
    c.wordWeight("ekifaananyi ky'embuzi eky'omwana"); c.wordWeight("wedding photos")
    assertEquals("1 of 2 queries non-English", c.counts)
    assertFalse("the counts carry no text", c.counts.contains("embuzi"))
  }

  @Test fun `the engine passes the policy weight to both answers and keeps the quick-look gate as it was`() {
    val engine = findrSource("engine/FindrEngine.kt").readText()
    assertTrue("progressive search is given both weights", "wordsWeight = { q -> wordWeights.wordsWeight(q) }" in engine && "meaningWeight = wordWeights::meaningWeight" in engine)
    assertTrue("the plain search weighs after its own embed", "wordWeights.meaningWeight(q, vector)" in engine && "wordWeight = weight, view = view)" in engine)
    assertTrue("quick look still uses isEnglishQuery", "queryGate.isEnglishQuery(q)" in engine)
    val gate = findrSource("engine/SpeechLanguageGate.kt").readText()
    assertTrue("quick look threshold unchanged", "const val QUERY_THRESHOLD = 0.5" in gate)
    val progressive = findrSource("engine/ProgressiveSearch.kt").readText()
    assertTrue("the words-first answer uses the words weight", "wordWeight = wordsWeight(q)" in progressive)
    val policy = findrSource("engine/WordWeightPolicy.kt").readText()
    assertTrue("words-first never waits for the list", "wordList.wordWeight(text, mayLoad)" in policy && "fun wordsWeight(text: String, mayLoad: Boolean = false)" in policy)
  }
}
