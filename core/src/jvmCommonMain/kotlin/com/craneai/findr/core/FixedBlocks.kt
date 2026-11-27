package com.craneai.findr.core

/**
 * Sound arriving in pieces of any size, handed on in blocks of exactly [size] samples (the last block, from [finish], may be shorter).
 * The Silero voice detector's output depends on how the audio is cut into the calls that feed it: the same file fed in pieces of 4096,
 * 8000 or 8192 samples (or any mix of multiples of 512) gives different speech segments, so a decoder that hands over whatever its pipe
 * read gives different words from one run to the next (sung-words port, internal/research: Happy Birthday 18, 36 or 62 words on the same
 * file). Cutting the stream into the same blocks every time makes the reading repeatable.
 */
class FixedBlocks(private val size: Int) {
  private val buf = FloatArray(size)
  private var n = 0

  fun add(x: FloatArray, sink: (FloatArray) -> Unit) {
    var i = 0
    while (i < x.size) {
      val c = minOf(size - n, x.size - i)
      System.arraycopy(x, i, buf, n, c)
      n += c; i += c
      if (n == size) { sink(buf.copyOf()); n = 0 }
    }
  }

  /** Hands on what is left, if anything. */
  fun finish(sink: (FloatArray) -> Unit) {
    if (n > 0) { sink(buf.copyOf(n)); n = 0 }
  }
}
