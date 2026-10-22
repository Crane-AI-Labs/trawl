package com.craneai.findr.engine

import com.craneai.findr.findrSource
import com.craneai.findr.index.Fp16
import com.craneai.findr.search.Fusion
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D19c (the research notes D19c, the research notes 10i): the language of a query from its own vector. The golden is scripts/bench/d19c_app_parity.py
 * golden: about 200 queries from the three evaluation sets (D17, ki-dev, crossgen), each with the class ww_gate3.py gives it (nearest fp32
 * centroid, fitted the way ww_gate3 fits them) and its query vector. The app reads fp16 centroids, so this also proves the fp16 file
 * reaches the same class on every one of them.
 */
class LanguageCentroidsTest {
  private val assets = File("src/main/assets/${LanguageCentroids.ASSET_DIR}")
  private val dir = File(assets, "stock-eg2-text")

  private fun centroids() = LanguageCentroids.fromBytes(File(dir, LanguageCentroids.CENTROIDS).readBytes(), File(dir, LanguageCentroids.LANGS).readText())

  private class Golden(val id: String, val lang: String, val weight: Double, val vector: FloatArray)

  private fun golden(): List<Golden> {
    val rows = File("src/test/resources/d19c_golden.tsv").readLines().filter { it.isNotBlank() && !it.startsWith("#") }.map { it.split('\t') }
    val bytes = File("src/test/resources/d19c_golden_fp16.bin").readBytes()
    assertEquals("one vector per golden row", rows.size * Fp16.BYTES_PER_VECTOR, bytes.size)
    return rows.mapIndexed { n, r ->
      val v = Fp16.blobToFloats(bytes.copyOfRange(n * Fp16.BYTES_PER_VECTOR, (n + 1) * Fp16.BYTES_PER_VECTOR))
      Golden(r[0], r[1], r[2].toDouble(), v)
    }
  }

  @Test fun theShippedFileIs16By768Fp16AndTheLanguagesAreTheTrainedOnes() {
    assertEquals(24_576L, File(dir, LanguageCentroids.CENTROIDS).length())
    val c = centroids()
    assertEquals(listOf("ach", "amh", "ara", "eng", "fra", "hau", "kin", "lgg", "lug", "nyn", "por", "som", "swa", "teo", "yor", "zul"), c.langs)
  }

  @Test fun everyCentroidIsAUnitVector() {
    val bytes = File(dir, LanguageCentroids.CENTROIDS).readBytes()
    for (k in 0 until 16) {
      val v = Fp16.blobToFloats(bytes.copyOfRange(k * Fp16.BYTES_PER_VECTOR, (k + 1) * Fp16.BYTES_PER_VECTOR))
      assertEquals("centroid $k length", 1.0, Math.sqrt(v.sumOf { it.toDouble() * it }), 2e-3)
    }
  }

  @Test fun theGoldenClassesAndWeightsMatchWwGate3OnEveryQuery() {
    val c = centroids()
    val rows = golden()
    assertTrue("about 200 queries (${rows.size})", rows.size in 180..220)
    val wrong = rows.filter { c.languageOf(it.vector) != it.lang }.map { it.id }
    assertTrue("classes that differ from ww_gate3: $wrong", wrong.isEmpty())
    val wrongWeight = rows.filter { c.weight(it.vector) != it.weight }.map { it.id }
    assertTrue("weights that differ from ww_gate3: $wrongWeight", wrongWeight.isEmpty())
  }

  @Test fun theGoldenCoversEveryLanguageClassAndBothWeights() {
    val rows = golden()
    val seen = rows.map { it.lang }.toSet()
    for (lang in listOf("eng", "lug", "swa", "kin", "fra", "por", "ara", "amh")) assertTrue("$lang is in the golden", lang in seen)
    assertTrue(rows.any { it.weight == 4.0 } && rows.any { it.weight == 2.0 })
  }

  @Test fun classWeightsAreTheDecidedOnes() {
    for (lang in listOf("eng", "swa", "fra", "por", "ara")) assertEquals("$lang stays at the stock weight", Fusion.WORD_WEIGHT, LanguageCentroids.weightOf(lang), 0.0)
    for (lang in listOf("lug", "nyn", "kin", "ach", "teo", "lgg", "amh", "hau", "som", "yor", "zul")) assertEquals("$lang gets 4.0", 4.0, LanguageCentroids.weightOf(lang), 0.0)
    assertEquals("a code the policy does not know", Fusion.WORD_WEIGHT, LanguageCentroids.weightOf("xyz"), 0.0)
    assertEquals(2.0, Fusion.WORD_WEIGHT, 0.0)
  }

  @Test fun theScaleOfTheQueryVectorDoesNotChangeItsClass() {
    val c = centroids()
    for (g in golden().take(40)) {
      val big = FloatArray(g.vector.size) { g.vector[it] * 37f }
      assertEquals(g.id, c.nearest(g.vector), c.nearest(big))
    }
  }

  @Test fun aVectorWithANaNOrOfTheWrongSizeIsHandledWithoutACrash() {
    val c = centroids()
    val bad = FloatArray(Fp16.DIM) { if (it == 5) Float.NaN else 0.1f }
    assertEquals("a NaN vector gets the stock weight", Fusion.WORD_WEIGHT, c.weight(bad), 0.0)
    val short = runCatching { c.nearest(FloatArray(10)) }
    assertTrue("the wrong size is refused", short.isFailure)
    assertEquals("a zero vector scores nothing and still returns a class", 0, c.nearest(FloatArray(Fp16.DIM)))
  }

  @Test fun aCentroidFileThatDoesNotMatchItsLanguageListIsRefused() {
    val bin = File(dir, LanguageCentroids.CENTROIDS).readBytes()
    assertTrue(runCatching { LanguageCentroids.fromBytes(bin.copyOf(bin.size - 2), "[\"a\",\"b\",\"c\",\"d\",\"e\",\"f\",\"g\",\"h\",\"i\",\"j\",\"k\",\"l\",\"m\",\"n\",\"o\",\"p\"]") }.isFailure)
    assertTrue(runCatching { LanguageCentroids.fromBytes(bin, "[\"a\",\"b\"]") }.isFailure)
  }

  @Test fun theIndexNamesTheStockBundlesAndNotTheFineTunedOnes() {
    val index = File(assets, "index.json").readText()
    for (stock in listOf("embeddinggemma-2-740m.litertlm", "embeddinggemma-2-text-vision-440m.litertlm", Embedder.TEXT_ONLY_NAME)) {
      val d = LanguageCentroids.directoryFor(index, stock)
      assertNotNull("$stock has centroids", d)
      assertTrue(File(assets, "$d/${LanguageCentroids.CENTROIDS}").isFile && File(assets, "$d/${LanguageCentroids.LANGS}").isFile)
    }
    for (name in Embedder.FINETUNED_NAMES + listOf("findr-eg2-ft-740m-kd7-s3928.litertlm", "something-else.litertlm")) {
      assertNull("$name has no centroids and keeps the D19 gate", LanguageCentroids.directoryFor(index, name))
    }
  }

  @Test fun everyDirectoryTheIndexNamesExistsAndEveryShippedSetIsNamed() {
    val index = org.json.JSONObject(File(assets, "index.json").readText())
    val named = index.getJSONObject("models").keys().asSequence().map { index.getJSONObject("models").getString(it) }.toSet()
    val onDisk = assets.listFiles()!!.filter { it.isDirectory }.map { it.name }.toSet()
    assertEquals(onDisk, named)
  }

  @Test fun theClassifierLogsNothingAndTheFileHoldsNoQueryText() {
    val src = findrSource("engine/LanguageCentroids.kt").readText()
    val logs = src.lines().filter { Regex("""\bLog\.[iwed]\(|println""").containsMatchIn(it) }
    assertEquals("one warning on a failed read, with no query text: $logs", 1, logs.size)
    assertFalse("it interpolates nothing", logs.single().contains("\$"))
  }
}
