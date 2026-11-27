package com.craneai.findr.engine

import com.craneai.findr.core.AudioTranscript
import com.craneai.findr.core.SpeechSegment
import kotlinx.coroutines.ensureActive

/**
 * Speech in a Ugandan language: a second recogniser (a [SpeechPack]) for the stretches the English one cannot read.
 *
 * Parakeet reads every speech segment, as it always has. When the language gate ([SpeechLanguageGate]) says a window's spoken words are
 * not English, [Router] picks the segments worth a second read, the engine reads those again with the pack's model, ONE segment at a
 * time ([readAgain]: a student model takes 321-519 MB per segment on the KN4, so segments are never batched), and [keep] decides,
 * segment by segment, which words become the window's transcript. An English window never reaches the second model: the verdict is the
 * same one that was always made, so nothing about English changes by construction (and the unit tests hold it). Sung stretches are not
 * read again (the packs learnt speech, not song): they keep the gate's own rule ([SpeechLanguageGate.keep]).
 *
 * Pure JVM and no model: the Android engine, the desktop engine and the tests all go through these.
 */
object LocalSpeech {
  /**
   * A segment with at least this many words that the gate calls English is kept as Parakeet heard it, inside a window that is not
   * English overall. Fewer words prove nothing ("is the" is English-looking junk a Luganda clip can produce), so those are read again.
   */
  const val SURE_ENGLISH_WORDS = 5

  /** A segment Parakeet heard in English, in words: sure enough to leave alone. */
  fun keepsFirstReading(v: SpeechLanguageGate.Verdict) = v.english && v.wordCount >= SURE_ENGLISH_WORDS

  /**
   * Is a pack's reading of one segment worth keeping? A CTC model writes something for any sound, so music, a crowd and wind come out
   * as short, sparse or looping strings. Kept when it has at least [MIN_WORDS] words, comes at a speaking pace ([MIN_WORDS_PER_SECOND]
   * and up; [com.craneai.findr.core.WordTimes.clean] has already dropped the too-fast and cut repeats) and is not a few words said
   * over and over ([MIN_DISTINCT] of the words differ). Thresholds from the October add-on's measured data (local-speech-addon.md).
   */
  fun accept(read: SpeechSegment): Boolean {
    val words = read.text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (words.size < MIN_WORDS) return false
    val seconds = maxOf((read.endMs - read.startMs) / 1000.0, 0.5)
    if (words.size / seconds < MIN_WORDS_PER_SECOND) return false
    return words.map { it.lowercase() }.toSet().size.toDouble() / words.size >= MIN_DISTINCT
  }

  const val MIN_WORDS = 2
  const val MIN_WORDS_PER_SECOND = 0.4
  const val MIN_DISTINCT = 0.5

  /**
   * Whole-note reading (on; internal/research/speech-packs-1.2.md, arm G): once a window is judged not English the pack reads it all and
   * every reading it makes is indexed, with the English words the segment rule would keep ([WHOLE_ENGLISH]). Off: the segment-by-segment
   * rule above ([Router] picks only what the gate drops, [merge] keeps Parakeet's sure English and its English runs).
   * WAXAL-H502 2-word found first 54.4 (segment rule) -> 76.5; FLEURS English 76.9 -> 77.4 (+0.5 [-1.0, +2.0]).
   */
  const val WHOLE_NOTE = true

  /**
   * Whole-window reading (0 = off): with [WHOLE_NOTE], the pack reads ALL of a routed window's sound, cut at quiet points into pieces of
   * at most this many seconds, instead of its VAD segments, one piece at a time. The VAD drops speech the pack can read (arm C, every VAD
   * segment: 59.4; arm D, the whole window: 76.3): 291 -> 404 of 502 notes then hold both query words.
   */
  const val WHOLE_WINDOW_MAX_S = 30

  /**
   * English-judged windows too (measured, not shipped: arm E, H502 77.1 but FLEURS -2.0 [-5.0, +0.5] and the pack reads every English
   * soundtrack, 1.47 s per audio minute on FLEURS vs 0.10): with a pack in use the pack also reads the windows the gate calls English, and its
   * reading is indexed BESIDE the English model's words when it [looksLocal] (Parakeet writes coherent-looking English for some Luganda
   * notes, "o Ch Chagaang K are do you", which the gate passes). The English words are always kept, so an English note loses nothing.
   */
  const val ENGLISH_TOO = false

  /**
   * In a whole-note window, which of the English model's words stay beside the pack's ([WHOLE_NOTE]). The gate can call
   * an English note not English (14 of 199 FLEURS clips): dropping its English text for the pack's gibberish lost English found-firsts.
   *  - [ENGLISH_NONE]: none (arm D);
   *  - [ENGLISH_ALL]: all of them (arm F);
   *  - [ENGLISH_SURE]: what the segment rule keeps: segments the gate is sure are English ([keepsFirstReading]) and the English runs
   *    of the others ([SpeechLanguageGate.Verdict.partial]) (arm G).
   */
  const val ENGLISH_NONE = 0
  const val ENGLISH_ALL = 1
  const val ENGLISH_SURE = 2
  const val WHOLE_ENGLISH = ENGLISH_SURE

  /** A pack's reading looks like a local language: at least [LOCAL_MIN_HITS] of its words, and [LOCAL_MIN_SHARE] of them, are Luganda or Swahili grammar words. */
  fun looksLocal(text: String): Boolean {
    val words = SpeechLanguageGate.tokens(text)
    if (words.isEmpty()) return false
    val hits = words.count { it in com.craneai.findr.search.LocalStopwords.WORDS }
    return hits >= LOCAL_MIN_HITS && hits.toDouble() / words.size >= LOCAL_MIN_SHARE
  }

  const val LOCAL_MIN_HITS = 2
  const val LOCAL_MIN_SHARE = 0.15

  /** What the caller of the engine decides: which segments of a first pass to read again, and what to do before each read. */
  interface Reading {
    /** The indexes in [first] to read again with the pack's model; empty when none. */
    fun route(first: List<SpeechSegment>): Set<Int>

    /** Called before each re-read, so the caller can wait for the user (the polite rules) or stop. */
    suspend fun beforeEachRead() {}
  }

  /**
   * The routing rule over the language gate: nothing when the window's spoken words are English (or there are none); otherwise every
   * spoken segment that is not [keepsFirstReading]. Sung segments are never picked.
   */
  class Router(
    private val gate: SpeechLanguageGate,
    private val wholeNote: Boolean = WHOLE_NOTE,
    private val englishToo: Boolean = ENGLISH_TOO,
    private val before: suspend () -> Unit = {},
  ) : Reading {
    override fun route(first: List<SpeechSegment>): Set<Int> {
      val spoken = first.indices.filter { !first[it].sung }
      if (spoken.isEmpty()) return emptySet()
      if (gate.judge(spoken.joinToString(" ") { first[it].text }).english) return if (englishToo && spoken.any { first[it].text.isNotBlank() }) spoken.toSet() else emptySet()
      if (wholeNote) return spoken.toSet()
      return spoken.filter { i -> !keepsFirstReading(gate.judge(first[i].text)) }.toSet()
    }

    override suspend fun beforeEachRead() = before()
  }

  /**
   * The second read, for an engine that has the first pass ([speech]) and can read one segment again ([read]: its index and the segment;
   * the engine holds the samples). [reading] picks the segments; each is read in turn, one at a time, checking for a stop and calling
   * [Reading.beforeEachRead] first. A reading rides on its segment as [SpeechSegment.local]. Returns [speech] without the blank entries
   * that nothing read (an engine keeps Parakeet's blank segments while a second read is in view, so they can be read again).
   */
  suspend fun readAgain(speech: List<SpeechSegment>, reading: Reading, read: suspend (Int, SpeechSegment) -> SpeechSegment?): List<SpeechSegment> {
    val picked = reading.route(speech)
    if (picked.isEmpty()) return speech.filter { it.text.isNotBlank() }
    val out = ArrayList<SpeechSegment>(speech.size)
    for ((i, s) in speech.withIndex()) {
      var local: SpeechSegment? = null
      if (i in picked) {
        kotlin.coroutines.coroutineContext.ensureActive()
        reading.beforeEachRead()
        local = read(i, s)?.takeIf { it.text.isNotBlank() }
      }
      if (s.text.isNotBlank() || local != null) out += SpeechSegment(s.startMs, s.endMs, s.text, s.words, s.sung, local)
    }
    return out
  }

  /**
   * What one stretch's spoken words become after the second read: [timed] holds the segments that keep their times (the pack's
   * readings, and the English segments Parakeet was sure of), [englishRuns] the English runs of the segments that were re-read, which
   * have no time of their own ([TranscriptPieces.ofText]). [localSegments] is how many readings were kept.
   */
  class Merged(val timed: List<SpeechSegment>, val englishRuns: String, val localSegments: Int)

  /**
   * Per spoken segment of a window that is not English: Parakeet's own text when it was sure ([keepsFirstReading]); else the pack's
   * reading when it passes [accept], plus the segment's English runs if it had any (a note that switches language); else only the
   * English runs, as the gate always kept them.
   */
  fun merge(spoken: List<SpeechSegment>, gate: SpeechLanguageGate): Merged {
    val timed = ArrayList<SpeechSegment>()
    val runs = StringBuilder()
    var kept = 0
    fun addRuns(r: String) {
      if (r.isEmpty()) return
      if (runs.isNotEmpty()) runs.append(' ')
      runs.append(r)
    }
    for (s in spoken) {
      val v = gate.judge(s.text)
      val local = s.local
      when {
        keepsFirstReading(v) -> timed += s
        local != null && accept(local) -> { timed += local; kept++; addRuns(v.partial) }
        else -> addRuns(v.partial)
      }
    }
    return Merged(timed, runs.toString(), kept)
  }

  /** [wholeNote]: every reading the pack made, in order, and nothing of the English model's words. */
  fun wholeMerge(spoken: List<SpeechSegment>, gate: SpeechLanguageGate? = null, english: Int = ENGLISH_NONE): Merged {
    val pack = spoken.mapNotNull { s -> s.local?.takeIf { it.text.isNotBlank() } }
    val first = spoken.filter { it.text.isNotBlank() }.map { SpeechSegment(it.startMs, it.endMs, it.text, it.words, it.sung) }
    val runs = StringBuilder()
    val kept = when {
      english == ENGLISH_ALL -> first
      english == ENGLISH_SURE && gate != null -> first.filter { s ->
        val v = gate.judge(s.text)
        keepsFirstReading(v).also { sure -> if (!sure && v.partial.isNotEmpty()) { if (runs.isNotEmpty()) runs.append(' '); runs.append(v.partial) } }
      }
      else -> emptyList()
    }
    return Merged((pack + kept).sortedBy { it.startMs }, runs.toString(), pack.size)
  }

  /** What to index of a window's speech: the gate's [verdict] (on what Parakeet heard), the [speech] pieces, and [localKept] readings kept (null: none was asked for). */
  class Kept(val verdict: SpeechLanguageGate.Verdict, val speech: List<TranscriptText>, val localKept: Int?)

  /**
   * [SpeechLanguageGate.keep], with the pack's readings where there are any. A window in which no segment was read again is exactly
   * the gate's judgement (so English, and every phone without a pack, is unchanged). Otherwise the spoken pool is [merge]d and the sung
   * pool keeps the gate's own rule; the verdict is the gate's over both pools, as [SpeechLanguageGate.keep] makes it.
   */
  fun keep(t: AudioTranscript, gate: SpeechLanguageGate, wholeNote: Boolean = WHOLE_NOTE, english: Int = WHOLE_ENGLISH): Kept {
    if (t.speech.none { it.local != null }) gate.keep(t).let { return Kept(it.verdict, it.speech, null) }
    val spoken = t.speech.filterNot { it.sung }
    val sung = t.speech.filter { it.sung }
    if (gate.judge(spoken.joinToString(" ") { it.text }).english) {
      // An English-judged window the pack read too ([ENGLISH_TOO]): the gate's words always, the pack's beside them only when they look local.
      val plain = gate.keep(AudioTranscript(t.speech.map { SpeechSegment(it.startMs, it.endMs, it.text, it.words, it.sung) }.filter { it.text.isNotBlank() }, t.sounds, t.seconds))
      val pack = spoken.mapNotNull { s -> s.local?.takeIf { it.text.isNotBlank() } }
      if (!looksLocal(pack.joinToString(" ") { it.text })) return Kept(plain.verdict, plain.speech, 0)
      return Kept(plain.verdict, plain.speech + TranscriptPieces.speech(AudioTranscript(pack, emptyList(), t.seconds)), pack.size)
    }
    val merged = if (wholeNote) wholeMerge(spoken, gate, english) else merge(spoken, gate)
    val pieces = TranscriptPieces.speech(AudioTranscript(merged.timed, emptyList(), t.seconds)) + TranscriptPieces.ofText(merged.englishRuns)
    val vp = gate.judge(spoken.joinToString(" ") { it.text })
    if (sung.isEmpty()) return Kept(vp, pieces, merged.localSegments)
    val sungKept = gate.keep(AudioTranscript(sung, emptyList(), t.seconds))
    val vs = sungKept.verdict
    val n = vp.wordCount + vs.wordCount
    val share = if (n == 0) 0.0 else (vp.share * vp.wordCount + vs.share * vs.wordCount) / n
    return Kept(SpeechLanguageGate.Verdict(share, n, vp.english && vs.english), pieces + sungKept.speech, merged.localSegments)
  }
}
