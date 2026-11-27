package com.craneai.findr.core

import com.craneai.findr.index.Fp16
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp

class SeesHead private constructor(
  private val vocabulary: List<String>,
  private val weights: FloatArray,
  private val bias: FloatArray,
) {
  fun line(frames: Iterable<FloatArray>, beforeFrame: () -> Unit = {}): String? {
    val best = LinkedHashMap<String, Double>()
    for (frame in frames) {
      beforeFrame()
      require(frame.size == Fp16.DIM)
      require(frame.all { it.isFinite() })
      val stored = FloatArray(frame.size) { Fp16.toFloat(Fp16.toHalf(frame[it])) }
      val scores = FloatArray(vocabulary.size)
      for (tag in vocabulary.indices) {
        var sum = 0f
        val offset = tag * Fp16.DIM
        for (component in stored.indices) sum += weights[offset + component] * stored[component]
        scores[tag] = sum + bias[tag]
      }
      val top = scores.indices.sortedWith(compareByDescending<Int> { scores[it] }.thenBy { it }).take(PER_FRAME)
      for (tag in top) {
        val probability = 1.0 / (1.0 + exp(-scores[tag].toDouble()))
        val word = vocabulary[tag]
        if (probability >= THRESHOLD && !word.startsWith("no ")) best[word] = maxOf(best[word] ?: 0.0, probability)
      }
    }
    val tags = best.keys.sortedByDescending { best.getValue(it) }.take(MAX_TAGS)
    return tags.takeIf { it.isNotEmpty() }?.joinToString(", ", "Sees: ")
  }

  companion object {
    const val THRESHOLD = 0.3
    const val PER_FRAME = 5
    const val MAX_TAGS = 8
    const val TAGS = 1136

    fun fromAssets(assets: AssetSource): SeesHead {
      val vocabulary = assets.open("sees/head_vocab.txt").bufferedReader().use { it.readLines() }
      require(vocabulary.size == TAGS && vocabulary.all { it.isNotBlank() } && vocabulary.distinct().size == TAGS)
      val weights = assets.open("sees/head_W.f16").use { it.readBytes() }
      val bias = assets.open("sees/head_b.f32").use { it.readBytes() }
      require(weights.size == TAGS * Fp16.DIM * 2 && bias.size == TAGS * 4)
      val values = Fp16.blobToFloats(weights)
      val buffer = ByteBuffer.wrap(bias).order(ByteOrder.LITTLE_ENDIAN)
      val biases = FloatArray(TAGS) { buffer.float }
      require(values.all { it.isFinite() } && biases.all { it.isFinite() })
      return SeesHead(vocabulary, values, biases)
    }
  }
}
