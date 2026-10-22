package com.craneai.findr.engine

import com.craneai.findr.core.AssetSource
import com.craneai.findr.core.FindrLog as Log
import com.craneai.findr.index.Fp16
import com.craneai.findr.search.Fusion
import org.json.JSONArray
import org.json.JSONObject

/**
 * D19c: which language a query is in, read from the query vector the app already computes (the research notes 10i, the research notes D19c;
 * the Python twin is scripts/bench/ww_gate3.py, arm C with margin 0).
 *
 * The file holds one unit-length centroid per language (16 x 768, fp16, 24,576 bytes): the renormalised mean of that language's
 * training-query vectors in the model's own query space. A query's language is the centroid with the highest dot product (the
 * query is normalised first, which cannot change the winner). There is no abstention: the pre-registered margin was 0.
 *
 * The centroids belong to one text space. The three stock bundles share it (the query probes in engine_probes.json have
 * cosine 1.0 across all three); a fine-tuned bundle has its own space, so [fromAssets] answers null for a model without an entry and the
 * caller falls back to the D19 word-list gate until that model has its own file.
 *
 * Only language codes and counts leave this class; a query's text or vector is never logged.
 */
class LanguageCentroids(val langs: List<String>, private val centroids: FloatArray) {
  init {
    require(langs.isNotEmpty() && centroids.size == langs.size * Fp16.DIM) { "centroids: ${centroids.size} numbers for ${langs.size} languages of ${Fp16.DIM} dims" }
  }

  /** Index into [langs] of the nearest centroid by dot product with [vector]; the first one wins a tie (numpy argmax). */
  fun nearest(vector: FloatArray): Int {
    require(vector.size == Fp16.DIM) { "query vector has ${vector.size} dims, not ${Fp16.DIM}" }
    var norm = 0.0
    for (v in vector) norm += v.toDouble() * v
    val scale = if (norm > 0.0) (1.0 / Math.sqrt(norm)).toFloat() else 1f
    var best = 0
    var bestScore = Float.NEGATIVE_INFINITY
    for (k in langs.indices) {
      var s = 0f
      val base = k * Fp16.DIM
      for (i in 0 until Fp16.DIM) s += vector[i] * scale * centroids[base + i]
      if (s > bestScore) { bestScore = s; best = k }
    }
    return best
  }

  /** The language code of the nearest centroid. */
  fun languageOf(vector: FloatArray): String = langs[nearest(vector)]

  /** The word weight for a query with this vector: [weightOf] its nearest language. A vector with a NaN scores nothing and gets the stock weight. */
  fun weight(vector: FloatArray): Double =
    if (vector.any { it.isNaN() || it.isInfinite() }) Fusion.WORD_WEIGHT else weightOf(languageOf(vector))

  companion object {
    /** The weight of the Ugandan local languages and the other languages (D19's 4.0, the only value D19c uses besides the stock 2.0). */
    const val LOCAL_WORD_WEIGHT = 4.0

    /**
     * ww_gate3.py classes: LOCAL {lug, nyn, kin, ach, teo, lgg} and OTHER {amh, hau, som, yor, zul} get 4.0. ENG and BIG {swa, fra, por, ara}
     * keep the stock 2.0 (the big languages lost at 4.0 in 10i), and so does a code the policy does not know.
     */
    private val FOUR = setOf("lug", "nyn", "kin", "ach", "teo", "lgg", "amh", "hau", "som", "yor", "zul")

    /** The class weight for a language code. */
    fun weightOf(lang: String): Double = if (lang in FOUR) LOCAL_WORD_WEIGHT else Fusion.WORD_WEIGHT

    const val ASSET_DIR = "query_language"
    const val INDEX = "$ASSET_DIR/index.json"
    const val CENTROIDS = "centroids_fp16.bin"
    const val LANGS = "langs.json"

    /** From the two files' bytes (the unit tests, the harness and [fromAssets] use this). */
    fun fromBytes(centroidsFp16: ByteArray, langsJson: String): LanguageCentroids {
      val arr = JSONArray(langsJson)
      val langs = List(arr.length()) { arr.getString(it) }
      require(centroidsFp16.size == langs.size * Fp16.BYTES_PER_VECTOR) { "centroid file is ${centroidsFp16.size} bytes for ${langs.size} languages" }
      return LanguageCentroids(langs, Fp16.blobToFloats(centroidsFp16))
    }

    /** The asset directory (under [ASSET_DIR]) holding the centroids for a model file, per `index.json`; null when the model has none. */
    fun directoryFor(indexJson: String, modelFile: String): String? {
      val models = JSONObject(indexJson).getJSONObject("models")
      return if (models.has(modelFile)) models.getString(modelFile) else null
    }

    /**
     * The centroids for [modelFile] (the text model's file name, which is its fingerprint), or null when the model has no entry
     * or the files cannot be read. A fine-tuned bundle has no entry until its own file is made.
     */
    fun fromAssets(assets: AssetSource, modelFile: String): LanguageCentroids? = try {
      val index = assets.open(INDEX).use { it.readBytes().toString(Charsets.UTF_8) }
      directoryFor(index, modelFile)?.let { dir ->
        val bin = assets.open("$ASSET_DIR/$dir/$CENTROIDS").use { it.readBytes() }
        val langs = assets.open("$ASSET_DIR/$dir/$LANGS").use { it.readBytes().toString(Charsets.UTF_8) }
        fromBytes(bin, langs)
      }
    } catch (e: Exception) {
      Log.w("FindrLang", "query language centroids unavailable: the word-list gate stays", e)
      null
    }
  }
}
