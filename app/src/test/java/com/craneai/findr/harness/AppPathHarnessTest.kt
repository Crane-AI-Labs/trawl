package com.craneai.findr.harness

import com.craneai.findr.engine.EnglishWords
import com.craneai.findr.engine.LanguageCentroids
import com.craneai.findr.engine.WordWeightPolicy
import com.craneai.findr.engine.QueryLanguage
import com.craneai.findr.engine.SpeechLanguageGate
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Entry point of the app-path benchmark. Does nothing unless FINDR_WORLD is set, so the normal test run is unaffected.
 *   FINDR_WORLD=<world dir> FINDR_OUT=<output dir> [FINDR_MODE=rank|chunk]  ./gradlew :app:testFullDebugUnitTest --tests '*AppPathHarnessTest*'
 * (scripts/app_harness.sh wraps this.) A world dir with a `worlds.txt` is a set: each listed sub-directory is ranked
 * on its own and written to <out>/<name>/.
 *
 * Word weight (the research notes 10e): FINDR_WW=<number> ranks with that fixed weight; FINDR_WW=app uses the app's QueryLanguage policy
 * (4.0 for a non-English query, 2.0 otherwise) with the word list at FINDR_WORDS (default app/src/main/assets/english_words.txt.gz).
 * FINDR_WW=d19c uses the app's WordWeightPolicy (the research notes 10i): the class of the query's own vector picks the weight, read from
 * the asset centroids of FINDR_MODEL (a model file name, default the stock 740m); a model with no entry falls back to D19, as the app does.
 * FINDR_SPLIT=test|dev ranks only that half of the D17 split (md5 of the smallest relevant id, as scripts/bench/ww_gate.py).
 * FINDR_DROP_VECTORS=KIND[,KIND...] (e.g. DOCUMENT or DOCUMENT,PDF) simulates a partial index: files of those kinds go in
 * with their text (words, FTS) but no vectors, as a real phone leaves them while the words pass has run and the meaning
 * pass has not (AppPathHarness.Policy.dropVectors). FINDR_DROP_VECTORS_EVERY=<n> does the same by file id (id % n == 0),
 * regardless of kind, for a world of one kind only (D17's, all DOCUMENT) where dropVectors has nothing to bite on.
 * FINDR_DATES=on|fallback reads date phrases in the queries the way SearchViewModel does (see AppPathHarness.DateMode); off (default) never does.
 */
class AppPathHarnessTest {
  @Test fun benchmark() {
    val world = System.getenv("FINDR_WORLD")
    assumeTrue("FINDR_WORLD not set", world != null)
    val out = File(System.getenv("FINDR_OUT") ?: error("FINDR_OUT not set"))
    val dir = File(world!!)
    val only = System.getenv("FINDR_ONLY")?.split(",")?.toSet()
    val ww = System.getenv("FINDR_WW")
    val words by lazy { File(System.getenv("FINDR_WORDS") ?: "src/main/assets/${SpeechLanguageGate.ASSET}").inputStream().use { EnglishWords.fromGzip(it) } }
    val split = System.getenv("FINDR_SPLIT")
    val weightOf: ((String) -> Double)? = when (ww) {
      null, "" -> null
      "d19c" -> null   // the policy below carries it
      "app" -> { val lang = QueryLanguage(lazyOf(words)); { q: String -> lang.wordWeight(q) } }
      else -> { val fixed = ww.toDouble(); { _: String -> fixed } }
    }
    val policyD19c: WordWeightPolicy? = if (ww == "d19c") {
      val assets = File("src/main/assets/${LanguageCentroids.ASSET_DIR}")
      val index = File(assets, "index.json").readText()
      WordWeightPolicy(QueryLanguage(lazy { words }), { name ->
        LanguageCentroids.directoryFor(index, name)?.let { dir ->
          LanguageCentroids.fromBytes(File(assets, "$dir/${LanguageCentroids.CENTROIDS}").readBytes(), File(assets, "$dir/${LanguageCentroids.LANGS}").readText())
        }
      }, { System.getenv("FINDR_MODEL") ?: "embeddinggemma-2-740m.litertlm" })
    } else null
    val keepOf: ((Long) -> Boolean)? = when (split) {
      null, "" -> null
      "test" -> { id: Long -> AppPathHarness.testHalf(id) }
      "dev" -> { id: Long -> !AppPathHarness.testHalf(id) }
      else -> error("FINDR_SPLIT must be test or dev")
    }
    // FINDR_DROP_VECTORS=DOCUMENT,PDF: simulates a partial index where those kinds have only had their words pass,
    // the way the worker reads pictures and screenshots before documents on a real phone (the research notes, item 2).
    val dropKinds = System.getenv("FINDR_DROP_VECTORS")?.split(",")?.filter { it.isNotBlank() }?.map { com.craneai.findr.core.FileKind.valueOf(it) }?.toSet()
    val dropEvery = System.getenv("FINDR_DROP_VECTORS_EVERY")?.toLong()
    val policy = AppPathHarness.Policy(
      weightOf, keepOf,
      meaningWeight = policyD19c?.let { p -> { q: String, v: FloatArray? -> p.meaningWeight(q, v) } },
      dropVectors = dropKinds?.let { ks -> { k: com.craneai.findr.core.FileKind -> k in ks } },
      dropVectorsForId = dropEvery?.let { n -> { id: Long -> id % n == 0L } },
      dates = when (System.getenv("FINDR_DATES")) {
        null, "", "off" -> null
        "on" -> AppPathHarness.DateMode(fallback = false)
        "fallback" -> AppPathHarness.DateMode(fallback = true)
        else -> error("FINDR_DATES must be off, on or fallback")
      },
    )
    policyD19c?.let { println("d19c policy: uses centroids ${it.usesCentroids}") }
    when (System.getenv("FINDR_MODE") ?: "rank") {
      "chunk" -> AppPathHarness.chunkMode(dir, File(out, "chunks.jsonl"))
      "rank" -> {
        val set = File(dir, "worlds.txt")
        if (set.exists()) {
          for (name in set.readLines().filter { it.isNotBlank() && (only == null || it in only) }) {
            println("== world $name")
            AppPathHarness.run(File(dir, name), File(out, name), policy = policy)
          }
        } else AppPathHarness.run(dir, out, policy = policy)
      }
      "datescan" -> AppPathHarness.dateScan(dir, File(out, "datescan.jsonl"))
      else -> error("FINDR_MODE must be rank, chunk or datescan")
    }
    policyD19c?.let { println("d19c policy counts: ${it.counts}") }
  }
}
