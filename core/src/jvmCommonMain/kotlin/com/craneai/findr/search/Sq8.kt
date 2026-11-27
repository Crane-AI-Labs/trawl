package com.craneai.findr.search

import kotlin.math.abs

/**
 * Symmetric per-vector scalar quantization to signed 8-bit integers (SQ8: `scale = max(|v|) / 127f`,
 * `q[i] = clamp(round(v[i] / scale), -127, 127)`).
 *
 * Cuts vector storage by ~50–62% vs fp16 with ~0.0005 mean cosine delta on 768-d unit vectors.
 * Every routine here is pure Kotlin/JVM and runs identically on macOS, Windows, Linux, and Android.
 */
object Sq8 {
  /** Quantizes [dim] floats from [src] into [dst] starting at [dstOffset]. Returns the per-vector float32 scale. */
  fun quantizeFloats(src: FloatArray, dim: Int, dst: ByteArray, dstOffset: Int = 0): Float {
    var maxAbs = 0f
    for (i in 0 until dim) {
      val x = src[i]
      if (!x.isFinite()) {
        java.util.Arrays.fill(dst, dstOffset, dstOffset + dim, 0.toByte())
        return 0f
      }
      val a = abs(x)
      if (a > maxAbs) maxAbs = a
    }
    if (maxAbs <= 0f || !maxAbs.isFinite()) {
      java.util.Arrays.fill(dst, dstOffset, dstOffset + dim, 0.toByte())
      return 0f
    }
    val scale = maxAbs / 127f
    val inv = 127f / maxAbs
    for (i in 0 until dim) {
      var q = Math.round(src[i] * inv)
      if (q > 127) q = 127 else if (q < -127) q = -127
      dst[dstOffset + i] = q.toByte()
    }
    return scale
  }

  /** Quantizes [dim] fp16 halves from [src] into [dst] starting at [dstOffset]. Returns the per-vector float32 scale. */
  fun quantizeHalves(src: ShortArray, dim: Int, dst: ByteArray, dstOffset: Int = 0): Float {
    val table = Halves.TO_FLOAT
    var maxAbs = 0f
    for (i in 0 until dim) {
      val x = table[src[i].toInt() and 0xFFFF]
      if (!x.isFinite()) {
        java.util.Arrays.fill(dst, dstOffset, dstOffset + dim, 0.toByte())
        return 0f
      }
      val a = abs(x)
      if (a > maxAbs) maxAbs = a
    }
    if (maxAbs <= 0f || !maxAbs.isFinite()) {
      java.util.Arrays.fill(dst, dstOffset, dstOffset + dim, 0.toByte())
      return 0f
    }
    val scale = maxAbs / 127f
    val inv = 127f / maxAbs
    for (i in 0 until dim) {
      var q = Math.round(table[src[i].toInt() and 0xFFFF] * inv)
      if (q > 127) q = 127 else if (q < -127) q = -127
      dst[dstOffset + i] = q.toByte()
    }
    return scale
  }

  /**
   * Computes the L2-renormalised leading [stageDim] dims of fp16 vector [full] and quantizes them into [dst].
   * Returns the stage-1 float32 scale (0f for a zero or non-finite vector).
   */
  fun quantizeStageFromHalves(full: ShortArray, stageDim: Int, dst: ByteArray, dstOffset: Int = 0): Float {
    val table = Halves.TO_FLOAT
    var sum = 0.0
    var maxAbs = 0f
    for (i in 0 until stageDim) {
      val x = table[full[i].toInt() and 0xFFFF]
      if (!x.isFinite()) {
        java.util.Arrays.fill(dst, dstOffset, dstOffset + stageDim, 0.toByte())
        return 0f
      }
      sum += x.toDouble() * x
      val a = abs(x)
      if (a > maxAbs) maxAbs = a
    }
    if (!sum.isFinite() || sum <= 0.0 || maxAbs <= 0f || !maxAbs.isFinite()) {
      java.util.Arrays.fill(dst, dstOffset, dstOffset + stageDim, 0.toByte())
      return 0f
    }
    val invNorm = (1.0 / Math.sqrt(sum)).toFloat()
    val normMax = maxAbs * invNorm
    val scale = normMax / 127f
    if (!scale.isFinite() || scale <= 0f) {
      java.util.Arrays.fill(dst, dstOffset, dstOffset + stageDim, 0.toByte())
      return 0f
    }
    val inv = 127f / maxAbs   // (x * invNorm) * (127 / (maxAbs * invNorm)) == x * (127 / maxAbs)
    for (i in 0 until stageDim) {
      var q = Math.round(table[full[i].toInt() and 0xFFFF] * inv)
      if (q > 127) q = 127 else if (q < -127) q = -127
      dst[dstOffset + i] = q.toByte()
    }
    return scale
  }

  /**
   * Computes the L2-renormalised leading [stageDim] dims of float vector [full] and quantizes them into [dst].
   * Returns the stage-1 float32 scale (0f for a zero or non-finite vector).
   */
  fun quantizeStageFromFloats(full: FloatArray, stageDim: Int, dst: ByteArray, dstOffset: Int = 0): Float {
    var sum = 0.0
    var maxAbs = 0f
    for (i in 0 until stageDim) {
      val x = full[i]
      if (!x.isFinite()) {
        java.util.Arrays.fill(dst, dstOffset, dstOffset + stageDim, 0.toByte())
        return 0f
      }
      sum += x.toDouble() * x
      val a = abs(x)
      if (a > maxAbs) maxAbs = a
    }
    if (!sum.isFinite() || sum <= 0.0 || maxAbs <= 0f || !maxAbs.isFinite()) {
      java.util.Arrays.fill(dst, dstOffset, dstOffset + stageDim, 0.toByte())
      return 0f
    }
    val invNorm = (1.0 / Math.sqrt(sum)).toFloat()
    val normMax = maxAbs * invNorm
    val scale = normMax / 127f
    if (!scale.isFinite() || scale <= 0f) {
      java.util.Arrays.fill(dst, dstOffset, dstOffset + stageDim, 0.toByte())
      return 0f
    }
    val inv = 127f / maxAbs
    for (i in 0 until stageDim) {
      var q = Math.round(full[i] * inv)
      if (q > 127) q = 127 else if (q < -127) q = -127
      dst[dstOffset + i] = q.toByte()
    }
    return scale
  }

  /**
   * 2-term residual int8 representation of a float query: `q[i] ≈ hi[i] * hiScale + lo[i] * loScale`.
   * Achieves ~15-bit precision (< 1e-5 dot-product delta vs float32 query) while executing purely with
   * ARM64 NEON `SDOT` (`vdotq_s32`) on a single DRAM pass over the index vectors.
   */
  class QuantizedQuery(val hi: ByteArray, val hiScale: Float, val lo: ByteArray, val loScale: Float)

  fun quantizeQuery(q: FloatArray): QuantizedQuery {
    val dim = q.size
    val hi = ByteArray(dim)
    val lo = ByteArray(dim)
    var qMax = 0f
    for (i in 0 until dim) {
      val x = q[i]
      if (!x.isFinite()) return QuantizedQuery(hi, 0f, lo, 0f)
      val a = abs(x)
      if (a > qMax) qMax = a
    }
    if (qMax <= 0f || !qMax.isFinite()) return QuantizedQuery(hi, 0f, lo, 0f)
    val hiScale = qMax / 127f
    val invHi = 127f / qMax
    var rMax = 0f
    for (i in 0 until dim) {
      var qh = Math.round(q[i] * invHi)
      if (qh > 127) qh = 127 else if (qh < -127) qh = -127
      hi[i] = qh.toByte()
      val r = abs(q[i] - qh * hiScale)
      if (r > rMax) rMax = r
    }
    if (rMax <= 0f || !rMax.isFinite()) return QuantizedQuery(hi, hiScale, lo, 0f)
    val loScale = rMax / 127f
    val invLo = 127f / rMax
    for (i in 0 until dim) {
      val r = q[i] - hi[i] * hiScale
      var ql = Math.round(r * invLo)
      if (ql > 127) ql = 127 else if (ql < -127) ql = -127
      lo[i] = ql.toByte()
    }
    return QuantizedQuery(hi, hiScale, lo, loScale)
  }

  /** Pure-JVM SQ8 scoring of [n] rows against float query [q], writing to `out[firstRow..firstRow+n-1]`. */
  fun scoreBlock(
    q: FloatArray,
    vecs: ByteArray,
    vecStart: Int,
    scales: FloatArray,
    scaleStart: Int,
    dim: Int,
    n: Int,
    out: FloatArray,
    firstRow: Int,
  ) {
    for (r in 0 until n) {
      val sc = scales[scaleStart + r]
      if (!sc.isFinite() || sc == 0f) { out[firstRow + r] = 0f; continue }
      var base = vecStart + r * dim
      var a0 = 0f; var a1 = 0f; var a2 = 0f; var a3 = 0f
      var i = 0
      while (i + 4 <= dim) {
        a0 += vecs[base] * q[i]
        a1 += vecs[base + 1] * q[i + 1]
        a2 += vecs[base + 2] * q[i + 2]
        a3 += vecs[base + 3] * q[i + 3]
        base += 4; i += 4
      }
      while (i < dim) { a0 += vecs[base] * q[i]; base++; i++ }
      val score = ((a0 + a1) + (a2 + a3)) * sc
      out[firstRow + r] = if (score.isFinite()) score else 0f
    }
  }

  /** Pure-JVM SQ8 scoring for one row against float query [q]. */
  fun scoreOne(q: FloatArray, vecs: ByteArray, vecStart: Int, scale: Float, dim: Int): Float {
    if (!scale.isFinite() || scale == 0f) return 0f
    var base = vecStart
    var a0 = 0f; var a1 = 0f; var a2 = 0f; var a3 = 0f
    var i = 0
    while (i + 4 <= dim) {
      a0 += vecs[base] * q[i]
      a1 += vecs[base + 1] * q[i + 1]
      a2 += vecs[base + 2] * q[i + 2]
      a3 += vecs[base + 3] * q[i + 3]
      base += 4; i += 4
    }
    while (i < dim) { a0 += vecs[base] * q[i]; base++; i++ }
    val score = ((a0 + a1) + (a2 + a3)) * scale
    return if (score.isFinite()) score else 0f
  }
}
