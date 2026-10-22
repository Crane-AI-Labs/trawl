package com.craneai.findr.extract

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Streaming sample-rate converter: a polyphase windowed-sinc low-pass (Blackman window, ~10 zero crossings) with the
 * cutoff just under the lower of the two Nyquist frequencies. Plain decimation would fold everything above 8 kHz
 * back into the speech band and hurt the audio encoder; this attenuates it by roughly 70 dB.
 *
 * Feed it chunks with [process] and finish with [finish]. State carries across chunks, so memory stays at a few
 * hundred samples however long the audio is. Input and output are 16-bit-scaled (`-32768..32767`).
 */
class Resampler(inRate: Int, outRate: Int) {
  private val up: Int   // output samples per `down` input samples, reduced
  private val down: Int
  private val half: Int // filter taps on each side of the output instant, in input samples
  private val cutoff: Double

  init {
    require(inRate > 0 && outRate > 0)
    val g = gcd(inRate, outRate)
    up = outRate / g
    down = inRate / g
    cutoff = CUTOFF_FRACTION * min(1.0, outRate.toDouble() / inRate)
    half = ceil(ZERO_CROSSINGS / cutoff).toInt()
  }

  /** Coefficients per fractional phase, built on first use (a 44.1 kHz source needs 160 of them, 48 kHz just one). */
  private val phases = arrayOfNulls<FloatArray>(up)
  private var buf = FloatArray(4096)
  private var bufLen = 0
  private var bufStart = 0L     // global input index of buf[0]
  private var inCount = 0L      // input samples received so far
  private var nextOut = 0L      // index of the next output sample

  fun process(input: FloatArray, len: Int): ShortArray {
    append(input, len)
    inCount += len
    return produce(limitToSignal = false)
  }

  /** Outputs the samples still waiting for filter lookahead. Call once, after the last chunk. */
  fun finish(): ShortArray {
    val real = inCount
    append(FloatArray(half + 1), half + 1)
    inCount += half + 1
    return produce(limitToSignal = true, realCount = real)
  }

  private fun produce(limitToSignal: Boolean, realCount: Long = 0): ShortArray {
    var out = ShortArray(maxOf(16, (bufLen.toLong() * up / down).toInt() + 4))
    var n = 0
    while (true) {
      val pos = nextOut * down
      if (limitToSignal && pos >= realCount * up) break
      val base = pos / up
      if (base + half > inCount - 1) break
      val coefficients = phases[(pos % up).toInt()] ?: makePhase((pos % up).toInt())
      val first = base - half + 1 - bufStart   // buf index of the oldest tap; negative before the signal began
      var acc = 0.0f
      var k = 0
      var i = first.toInt()
      if (i < 0) {
        k = -i
        i = 0
      }
      while (k < coefficients.size) {
        acc += buf[i++] * coefficients[k++]
      }
      if (n == out.size) out = out.copyOf(n * 2)
      out[n++] = acc.roundToInt().coerceIn(-32768, 32767).toShort()
      nextOut++
    }
    trim()
    return out.copyOf(n)
  }

  /** Taps for the output instant `phase/up` of the way between input samples: sinc * Blackman, summing to 1. */
  private fun makePhase(phase: Int): FloatArray {
    val frac = phase.toDouble() / up
    val taps = DoubleArray(2 * half)
    var sum = 0.0
    for (j in taps.indices) {
      val x = (j - half + 1) - frac   // distance from the output instant, in input samples
      val u = x / half
      val window = if (u <= -1.0 || u >= 1.0) 0.0 else 0.42 + 0.5 * cos(PI * u) + 0.08 * cos(2 * PI * u)
      val arg = PI * cutoff * x
      val sinc = if (x == 0.0) 1.0 else sin(arg) / arg
      taps[j] = cutoff * sinc * window
      sum += taps[j]
    }
    val result = FloatArray(taps.size) { (taps[it] / sum).toFloat() }
    phases[phase] = result
    return result
  }

  private fun append(input: FloatArray, len: Int) {
    if (bufLen + len > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, bufLen + len))
    System.arraycopy(input, 0, buf, bufLen, len)
    bufLen += len
  }

  /** Drops the samples no future output can touch. */
  private fun trim() {
    val keepFrom = (nextOut * down / up) - half + 1
    val drop = (keepFrom - bufStart).coerceIn(0, bufLen.toLong()).toInt()
    if (drop > 0) {
      System.arraycopy(buf, drop, buf, 0, bufLen - drop)
      bufLen -= drop
      bufStart += drop
    }
  }

  companion object {
    private const val ZERO_CROSSINGS = 10.0
    private const val CUTOFF_FRACTION = 0.94

    private fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
  }
}

/**
 * Cuts a stream of samples into overlapping windows (`window` long, a new one every `hop`), at most `maxWindows`.
 * A short tail is emitted too when it holds at least `minTail` samples that no earlier window covered. Memory is one
 * window, whatever the length of the stream.
 */
class WindowCutter(
  private val window: Int,
  private val hop: Int,
  private val maxWindows: Int,
  private val minTail: Int,
  private val emit: (startSample: Long, samples: ShortArray) -> Unit,
) {
  private var buf = ShortArray(window)
  private var len = 0
  private var bufStart = 0L
  private var pendingDrop = 0
  private var emitted = 0

  val full get() = emitted >= maxWindows

  fun push(samples: ShortArray, n: Int = samples.size) {
    var off = 0
    while (off < n && !full) {
      if (pendingDrop > 0) {   // hop longer than the window: skip the gap
        val d = min(pendingDrop, n - off)
        pendingDrop -= d
        bufStart += d
        off += d
        continue
      }
      val take = min(n - off, window - len)
      System.arraycopy(samples, off, buf, len, take)
      len += take
      off += take
      if (len == window) {
        emit(bufStart, buf.copyOf(window))
        emitted++
        if (hop >= window) {
          pendingDrop = hop - window
          bufStart += window
          len = 0
        } else {
          System.arraycopy(buf, hop, buf, 0, window - hop)
          len = window - hop
          bufStart += hop
        }
      }
    }
  }

  fun finish() {
    if (full || pendingDrop > 0 || len == 0) return
    val covered = if (emitted == 0) 0 else maxOf(0, window - hop)
    if (len - covered >= minTail) emit(bufStart, buf.copyOf(len))
  }
}

/** The 44-byte RIFF/WAVE header for 16-bit PCM, followed by the samples. */
object Wav {
  fun encode(samples: ShortArray, sampleRate: Int = 16_000, channels: Int = 1): ByteArray {
    val dataBytes = samples.size * 2
    val out = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
    out.put("RIFF".toByteArray(Charsets.US_ASCII))
    out.putInt(36 + dataBytes)
    out.put("WAVE".toByteArray(Charsets.US_ASCII))
    out.put("fmt ".toByteArray(Charsets.US_ASCII))
    out.putInt(16)                        // PCM fmt chunk size
    out.putShort(1)                       // format 1 = PCM
    out.putShort(channels.toShort())
    out.putInt(sampleRate)
    out.putInt(sampleRate * channels * 2) // byte rate
    out.putShort((channels * 2).toShort()) // block align
    out.putShort(16)                      // bits per sample
    out.put("data".toByteArray(Charsets.US_ASCII))
    out.putInt(dataBytes)
    for (s in samples) out.putShort(s)
    return out.array()
  }
}
