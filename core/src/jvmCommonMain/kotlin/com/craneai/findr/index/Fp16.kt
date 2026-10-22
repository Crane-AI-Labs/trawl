package com.craneai.findr.index

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * IEEE half-precision conversion, written by hand. `android.util.Half` returns zeros in JVM unit tests (the stub
 * jar) and `Float.floatToFloat16` needs a recent JDK/ART, so the same code runs on the phone and in the tests.
 * Vectors are stored as fp16: half the size of float32 and, for unit vectors, a rounding error near 1e-4 per
 * component, far below the gaps between cosine scores that matter.
 */
object Fp16 {
  const val DIM = 768
  const val BYTES_PER_VECTOR = DIM * 2

  /** Rounds to nearest even. Values beyond the half range become infinity, which never happens for unit vectors. */
  fun toHalf(value: Float): Short {
    val bits = java.lang.Float.floatToRawIntBits(value)
    val sign = (bits ushr 16) and 0x8000
    val exp = (bits ushr 23) and 0xFF
    val mant = bits and 0x7FFFFF
    if (exp == 0xFF) return (sign or 0x7C00 or (if (mant != 0) 0x200 else 0)).toShort()   // inf / NaN
    val e = exp - 127 + 15
    if (e >= 0x1F) return (sign or 0x7C00).toShort()
    if (e <= 0) {
      if (e < -10) return sign.toShort()   // underflows to zero
      val m = mant or 0x800000
      val shift = 14 - e
      var half = m ushr shift
      val rem = m and ((1 shl shift) - 1)
      val mid = 1 shl (shift - 1)
      if (rem > mid || (rem == mid && (half and 1) == 1)) half++
      return (sign or half).toShort()
    }
    var half = (e shl 10) or (mant ushr 13)
    val rem = mant and 0x1FFF
    if (rem > 0x1000 || (rem == 0x1000 && (half and 1) == 1)) half++   // a carry into the exponent is correct
    return (sign or half).toShort()
  }

  fun toFloat(half: Short): Float {
    val h = half.toInt() and 0xFFFF
    val sign = (h and 0x8000) shl 16
    val exp = (h ushr 10) and 0x1F
    val mant = h and 0x3FF
    val bits = when {
      exp == 0 -> {
        if (mant == 0) sign
        else {   // subnormal half: normalise
          var m = mant
          var e = -1
          while (m and 0x400 == 0) { m = m shl 1; e-- }
          sign or ((e + 127 - 14 + 1) shl 23) or ((m and 0x3FF) shl 13)
        }
      }
      exp == 0x1F -> sign or 0x7F800000 or (mant shl 13)
      else -> sign or ((exp - 15 + 127) shl 23) or (mant shl 13)
    }
    return java.lang.Float.intBitsToFloat(bits)
  }

  /** Little-endian bytes, the BLOB layout in `segments.vec`. */
  fun toBlob(vector: FloatArray): ByteArray {
    val buffer = ByteBuffer.allocate(vector.size * 2).order(ByteOrder.LITTLE_ENDIAN)
    for (v in vector) buffer.putShort(toHalf(v))
    return buffer.array()
  }

  fun blobToHalves(blob: ByteArray): ShortArray {
    val out = ShortArray(blob.size / 2)
    ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(out)
    return out
  }

  fun blobToFloats(blob: ByteArray): FloatArray {
    val halves = blobToHalves(blob)
    return FloatArray(halves.size) { toFloat(halves[it]) }
  }
}
