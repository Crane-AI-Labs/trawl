package com.craneai.findr.engine

import com.craneai.findr.core.FindrLog as Log
import com.craneai.findr.search.Fusion
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Which word weight a query gets (the research notes D19c, the research notes 10i). The language of the query is read from the query vector
 * the app computes anyway ([LanguageCentroids]); the local and "other" languages get 4.0, English and the big languages 2.0.
 *
 * Order matters, and it is the harness's order: the words-first answer is typed text only, so it has no vector and runs at the stock
 * [Fusion.WORD_WEIGHT]. The meaning answer ranks with the class weight of its own query vector. A query whose embed gave no vector
 * (the model is not there, quick look only) stays at the stock weight and is counted.
 *
 * Fallback: a model with no centroid file (a fine-tuned bundle, whose text space is its own; or the assets cannot be read) keeps the
 * D19 word-list gate ([QueryLanguage]) for both answers, exactly as before. Each fallback is counted.
 *
 * Only counts leave this class: how many queries went to which class code, how many had no vector, how many fell back. Never query text.
 *
 * @param wordList the D19 word-list rule, the fallback
 * @param centroidsFor the centroids for the text model now installed (its file name), or null when it has none; looked up per query
 *   because the installed model can change
 * @param modelName the installed text model's file name ([Embedder.textFingerprint]), null when there is no model
 */
class WordWeightPolicy(
  private val wordList: QueryLanguage,
  private val centroidsFor: (modelName: String) -> LanguageCentroids?,
  private val modelName: () -> String?,
) {
  private val byClass = ConcurrentHashMap<String, AtomicLong>()
  private val noVector = AtomicLong()
  private val fallbacks = AtomicLong()
  private val resolved = ConcurrentHashMap<String, Resolved>()

  /** A cache of [centroidsFor] per model name (an asset read), including the answer "none". */
  private class Resolved(val centroids: LanguageCentroids?)

  private fun centroids(): LanguageCentroids? {
    val name = modelName() ?: return null
    return resolved.getOrPut(name) { Resolved(centroidsFor(name)) }.centroids
  }

  /** Counts for the log: queries per class code, then the no-vector and fallback counts. No query text. */
  val counts: String
    get() = "classes ${byClass.entries.sortedBy { it.key }.joinToString(", ") { "${it.key} ${it.value.get()}" }.ifEmpty { "none" }}; " +
      "no vector ${noVector.get()}; word-list fallback ${fallbacks.get()}" + if (fallbacks.get() > 0) " (${wordList.counts})" else ""

  /** The count of queries classified into [lang] so far (the Infinix check and the tests read this). */
  fun classCount(lang: String): Long = byClass[lang]?.get() ?: 0

  /** True when the centroids drive the weight for the installed model; false when the word-list gate does. */
  val usesCentroids: Boolean get() = centroids() != null

  /**
   * The weight for the words-first answer, which has only the typed text. With centroids that is the stock weight; without, the D19
   * word-list answer ([mayLoad] false: it never waits for the list).
   */
  fun wordsWeight(text: String, mayLoad: Boolean = false): Double =
    if (centroids() != null) Fusion.WORD_WEIGHT else wordList.wordWeight(text, mayLoad)

  /**
   * The weight for the meaning answer, with the query's own vector ([vector], null when the embed gave none). Counted once per call.
   */
  fun meaningWeight(text: String, vector: FloatArray?): Double {
    val c = centroids()
    if (c == null) {
      fallbacks.incrementAndGet()
      return wordList.wordWeight(text, true)
    }
    if (vector == null) {
      noVector.incrementAndGet()
      return Fusion.WORD_WEIGHT
    }
    val lang = c.languageOf(vector)
    byClass.getOrPut(lang) { AtomicLong() }.incrementAndGet()
    return LanguageCentroids.weightOf(lang)
  }

  /** The class code the policy would give this vector, or null with no centroids; for the tests and the harness, not counted. */
  fun classOf(vector: FloatArray): String? = centroids()?.languageOf(vector)

  companion object {
    private const val TAG = "FindrLang"

    /** The production wiring: centroids from the APK's assets, keyed by the model file name. */
    fun fromAssets(assets: com.craneai.findr.core.AssetSource, wordList: QueryLanguage, modelName: () -> String?) =
      WordWeightPolicy(wordList, { name ->
        LanguageCentroids.fromAssets(assets, name).also {
          Log.i(TAG, if (it != null) "query language: ${it.langs.size} centroids for the installed model" else "query language: no centroids for the installed model, the word-list gate stays")
        }
      }, modelName)
  }
}
