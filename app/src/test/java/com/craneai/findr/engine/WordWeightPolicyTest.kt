package com.craneai.findr.engine

import com.craneai.findr.findrSource
import com.craneai.findr.index.Fp16
import com.craneai.findr.search.Fusion
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The D19c policy around [LanguageCentroids] (the research notes D19c): the class of the query's vector picks the meaning answer's weight,
 * the words-first answer stays at 2.0, a model with no centroid file falls back to the D19 word-list gate, and only counts are kept.
 */
class WordWeightPolicyTest {
  private val assets = File("src/main/assets/${LanguageCentroids.ASSET_DIR}")
  private val index = File(assets, "index.json").readText()
  private val words = EnglishWords.fromGzip(File("src/main/assets/english_words.txt.gz").inputStream())
  private val stock = "embeddinggemma-2-text-vision-440m.litertlm"
  private val fineTuned = "findr-eg2-ft-740m.litertlm"
  private val luganda = "ekifaananyi ky'embuzi eky'omwana"   // the word-list gate calls this non-English
  private val english = "photos of the wedding"

  private fun load(name: String): LanguageCentroids? = LanguageCentroids.directoryFor(index, name)?.let { dir ->
    LanguageCentroids.fromBytes(File(assets, "$dir/${LanguageCentroids.CENTROIDS}").readBytes(), File(assets, "$dir/${LanguageCentroids.LANGS}").readText())
  }

  private fun policy(model: String?, loads: MutableList<String> = ArrayList()) =
    WordWeightPolicy(QueryLanguage(lazyOf(words)), { loads += it; load(it) }, { model })

  /** A query vector of one language: its own (unit) centroid. */
  private fun vectorOf(lang: String): FloatArray {
    val c = load(stock)!!
    val k = c.langs.indexOf(lang)
    val bytes = File(assets, "stock-eg2-text/${LanguageCentroids.CENTROIDS}").readBytes()
    return Fp16.blobToFloats(bytes.copyOfRange(k * Fp16.BYTES_PER_VECTOR, (k + 1) * Fp16.BYTES_PER_VECTOR))
  }

  @Test fun withCentroidsTheWordsAnswerIsAtTheStockWeightWhateverTheTypedText() {
    val p = policy(stock)
    assertEquals(Fusion.WORD_WEIGHT, p.wordsWeight(luganda), 0.0)
    assertEquals(Fusion.WORD_WEIGHT, p.wordsWeight(english), 0.0)
    assertTrue(p.usesCentroids)
  }

  @Test fun withCentroidsTheMeaningAnswerTakesTheWeightOfItsVectorsClass() {
    val p = policy(stock)
    assertEquals(4.0, p.meaningWeight(luganda, vectorOf("lug")), 0.0)
    assertEquals(2.0, p.meaningWeight(english, vectorOf("eng")), 0.0)
    assertEquals(2.0, p.meaningWeight("kitabu cha shule", vectorOf("swa")), 0.0)
    assertEquals(4.0, p.meaningWeight("x", vectorOf("amh")), 0.0)
    assertEquals(1L, p.classCount("lug"))
    assertEquals(1L, p.classCount("eng"))
    assertEquals(0L, p.classCount("kin"))
  }

  @Test fun theMeaningWeightIgnoresTheTypedTextWhenCentroidsExist() {
    val p = policy(stock)
    assertEquals("an English-looking text with a Luganda vector is Luganda", 4.0, p.meaningWeight(english, vectorOf("lug")), 0.0)
    assertEquals("a Luganda-looking text with an English vector is English", 2.0, p.meaningWeight(luganda, vectorOf("eng")), 0.0)
  }

  @Test fun withCentroidsButNoQueryVectorTheMeaningAnswerIsAtTheStockWeightAndCounted() {
    val p = policy(stock)
    assertEquals(Fusion.WORD_WEIGHT, p.meaningWeight(luganda, null), 0.0)
    assertTrue(p.counts, "no vector 1" in p.counts)
  }

  @Test fun aModelWithNoCentroidFileFallsBackToTheWordListGateInBothAnswers() {
    val p = policy(fineTuned)
    assertFalse(p.usesCentroids)
    assertEquals("words-first, Luganda text", 4.0, p.wordsWeight(luganda), 0.0)
    assertEquals("words-first, English text", 2.0, p.wordsWeight(english), 0.0)
    assertEquals("meaning, Luganda text (the vector is not read)", 4.0, p.meaningWeight(luganda, vectorOf("eng")), 0.0)
    assertEquals("meaning, English text", 2.0, p.meaningWeight(english, vectorOf("lug")), 0.0)
    assertTrue(p.counts, "word-list fallback 2" in p.counts)
  }

  @Test fun theFallbackAnswersExactlyAsTheD19GateDid() {
    val d19 = QueryLanguage(lazyOf(words))
    val p = policy(fineTuned)
    for (q in File("src/test/resources/query_language_golden.tsv").readLines().filter { it.isNotBlank() && !it.startsWith("#") }.map { it.substringAfter('\t') }) {
      assertEquals("meaning, \"$q\"", d19.wordWeight(q, true), p.meaningWeight(q, null), 0.0)
      assertEquals("words, \"$q\"", d19.wordWeight(q, true), p.wordsWeight(q, mayLoad = true), 0.0)
    }
  }

  @Test fun noModelAtAllIsTheFallbackToo() {
    val p = policy(null)
    assertFalse(p.usesCentroids)
    assertEquals(4.0, p.meaningWeight(luganda, null), 0.0)
  }

  @Test fun theCentroidFileIsLookedUpOncePerModel() {
    val loads = ArrayList<String>()
    val p = policy(stock, loads)
    repeat(5) { p.wordsWeight(english); p.meaningWeight(english, vectorOf("eng")) }
    assertEquals(listOf(stock), loads)
    val missing = ArrayList<String>()
    val q = policy(fineTuned, missing)
    repeat(5) { q.wordsWeight(english); q.meaningWeight(english, null) }
    assertEquals("the answer none is remembered too", listOf(fineTuned), missing)
  }

  @Test fun swappingTheModelSwitchesThePolicy() {
    var model: String? = stock
    val p = WordWeightPolicy(QueryLanguage(lazyOf(words)), { load(it) }, { model })
    assertEquals(2.0, p.meaningWeight(luganda, vectorOf("eng")), 0.0)
    model = fineTuned
    assertEquals(4.0, p.meaningWeight(luganda, vectorOf("eng")), 0.0)
  }

  @Test fun countsCarryClassCodesAndNumbersButNoQueryText() {
    val p = policy(stock)
    p.meaningWeight(luganda, vectorOf("lug"))
    p.meaningWeight(english, vectorOf("eng"))
    p.meaningWeight(english, vectorOf("eng"))
    assertEquals("classes eng 2, lug 1; no vector 0; word-list fallback 0", p.counts)
    for (text in listOf(luganda, english, "embuzi", "wedding")) assertFalse(p.counts.contains(text))
  }

  @Test fun noQueryTextOrVectorReachesAnyLog() {
    for (name in listOf("WordWeightPolicy.kt", "LanguageCentroids.kt")) {
      val src = findrSource("engine/$name").readText()
      assertFalse("$name uses the raw android log", "android.util.Log" in src)
      for (line in src.lines().filter { Regex("""\bLog\.[iwed]\(|println|\bd\(TAG""").containsMatchIn(it) }) {
        assertFalse("$name logs a variable: $line", Regex("""\$\{?(text|q|query|vector|v)\b""").containsMatchIn(line))
      }
    }
  }
}
