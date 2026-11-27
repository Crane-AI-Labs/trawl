package com.craneai.findr.core

/**
 * Sung words. The Silero VAD is a speech detector: on singing it keeps solo voices but cuts a song into fragments and skips choirs, and
 * the fragments read worse than the whole line (internal/research/sung-words.md: on real sung clips, reading the song itself instead of
 * the VAD's pieces gave more words and a higher common-word share on every clip, e.g. 12 -> 37 words). So the stretches the sound
 * labeller calls singing are read by the recogniser directly, in pieces of at most [maxSamples] cut at the quietest point, and the VAD's
 * pieces that start inside them are not read. Everything else keeps the VAD path, so a file with no sung window is read as before.
 *
 * The engine calls [window] for every labelled window in order (times in samples since the start of this read), [covers] before reading
 * a VAD piece, and [finish] once the sound has ended; [segments] are the sung pieces read, marked [SpeechSegment.sung] so the language
 * gate judges them apart from speech.
 */
class SungReader(
  private val rate: Int,
  private val maxSamples: Int,
  private val minSamples: Int,
  /** [SpeechSplit.ranges]: the pieces of a buffer, each at most maxSamples, cut at a quiet point. */
  private val split: (FloatArray, Int, Int) -> List<IntRange>,
  /** Reads one piece that starts [startSample] samples into this read; null when the recogniser heard nothing. */
  private val read: (FloatArray, Long) -> SpeechSegment?,
) {
  private val runs = ArrayList<LongArray>()   // [from, to) sample ranges of consecutive sung windows
  private var pending = FloatArray(0)
  private var pendingStart = 0L
  val segments = ArrayList<SpeechSegment>()
  /** Samples handed to the recogniser here (the extra cost). */
  var readSamples = 0L
    private set

  fun window(startSample: Long, samples: FloatArray, labels: List<String>) {
    if (!isSung(labels)) { finish(); return }
    val last = runs.lastOrNull()
    if (last != null && last[1] == startSample) last[1] = startSample + samples.size else runs += longArrayOf(startSample, startSample + samples.size)
    if (pending.isEmpty()) pendingStart = startSample
    pending += samples
    // Read whole pieces as the run grows (a song can last minutes); keep enough back that the next cut can still look for a quiet spot.
    while (pending.size > maxSamples + LOOKAHEAD_S * rate) {
      val r = split(pending, pending.size, maxSamples).first()
      piece(r.first, r.last + 1)
      pending = pending.copyOfRange(r.last + 1, pending.size)
      pendingStart += r.last + 1
    }
  }

  /** Reads what is left of the current run. Called when a window is not sung, and once at the end. */
  fun finish() {
    if (pending.isEmpty()) return
    for (r in split(pending, pending.size, maxSamples)) piece(r.first, r.last + 1)
    pending = FloatArray(0)
  }

  /** Does a VAD piece starting [startSample] samples into this read fall in a sung stretch (so it is read there instead)? */
  fun covers(startSample: Long): Boolean = runs.any { startSample >= it[0] && startSample < it[1] }

  private fun piece(from: Int, to: Int) {
    if (to - from < minSamples) return
    val x = if (from == 0 && to == pending.size) pending else pending.copyOfRange(from, to)
    readSamples += x.size
    read(x, pendingStart + from)?.let { segments += it }
  }

  companion object {
    /** AudioSet labels (CED-mini, as the engines report them) that mean someone is singing. */
    val SUNG_LABELS = setOf("Singing", "Choir", "A capella", "Male singing", "Female singing", "Child singing", "Vocal music")
    private const val LOOKAHEAD_S = 3

    fun isSung(labels: List<String>): Boolean = labels.any { it in SUNG_LABELS }

    /** The VAD's speech pieces outside the sung stretches, and the sung pieces, in time order. */
    fun merge(vad: List<SpeechSegment>, reader: SungReader, offsetMs: Long, rate: Int): List<SpeechSegment> {
      if (reader.runs.isEmpty()) return vad
      val kept = vad.filterNot { reader.covers((it.startMs - offsetMs) * rate / 1000) }
      return (kept + reader.segments).sortedBy { it.startMs }
    }
  }
}
