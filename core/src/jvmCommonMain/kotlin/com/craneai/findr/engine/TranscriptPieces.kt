package com.craneai.findr.engine

import com.craneai.findr.core.AudioTranscript
import com.craneai.findr.core.TranscriptMarks

/**
 * A stretch of a transcript sized for the keyword index and the text embedder: ~400 characters, with the time it covers and where
 * each spoken segment sits in it ([marks], see [TranscriptMarks]). Null times: the words can't be placed in the file.
 */
class TranscriptText(val startMs: Long?, val endMs: Long?, val text: String, val marks: String? = null)

/**
 * Turns what the speech model heard into pieces. Speech segments are joined, in order, until a piece reaches [maxChars]
 * (the same ~400 characters documents use, so EmbeddingGemma sees what it saw in the eval). The sounds of the whole file
 * become one "Sounds: a, b" line, the form the retrieval eval measured (the experiment code, arm e): each label once, in the
 * order first heard, so a clip with ten barks says "Dog" once and the tags don't drown the words.
 */
object TranscriptPieces {
  const val MAX_CHARS = 400
  const val MAX_LABELS = 12

  fun speech(t: AudioTranscript, maxChars: Int = MAX_CHARS): List<TranscriptText> {
    val out = ArrayList<TranscriptText>()
    var start = 0L
    var end = 0L
    val buf = StringBuilder()
    val marks = ArrayList<TranscriptMarks.Mark>()
    fun flush() {
      if (buf.isNotEmpty()) out += TranscriptText(start, end, buf.toString(), TranscriptMarks.encode(marks))
      buf.setLength(0)
      marks.clear()
    }
    for (s in t.speech) {
      val text = s.text.trim()
      if (text.isEmpty()) continue
      if (buf.isNotEmpty() && buf.length + 1 + text.length > maxChars) flush()
      if (buf.isEmpty()) start = s.startMs else buf.append(' ')
      marks += TranscriptMarks.Mark(buf.length, s.startMs, s.endMs)
      buf.append(text)
      end = s.endMs
    }
    flush()
    return out
  }

  /**
   * Plain text (the English runs the language gate kept out of a mixed-language transcript) as pieces of up to [maxChars], cut between
   * words. The runs are the gate's lower-cased, re-joined words, no longer tied to the segments they were heard in, so they carry NO
   * time: stamping them with the speech's first start (as this used to) made every match read "at 0:00".
   */
  fun ofText(text: String, maxChars: Int = MAX_CHARS): List<TranscriptText> {
    val out = ArrayList<TranscriptText>()
    val buf = StringBuilder()
    for (w in text.split(' ')) {
      if (w.isEmpty()) continue
      if (buf.isNotEmpty() && buf.length + 1 + w.length > maxChars) { out += TranscriptText(null, null, buf.toString()); buf.setLength(0) }
      if (buf.isNotEmpty()) buf.append(' ')
      buf.append(w)
    }
    if (buf.isNotEmpty()) out += TranscriptText(null, null, buf.toString())
    return out
  }

  /** The "Sounds: …" line, or null when nothing was labelled. */
  fun sounds(t: AudioTranscript): TranscriptText? {
    val labels = t.sounds.flatMap { it.labels }.distinct().take(MAX_LABELS)
    if (labels.isEmpty()) return null
    // Each label once, in the order first heard: the line stands for the whole file, so it points at no moment.
    return TranscriptText(null, null, "Sounds: " + labels.joinToString(", "))
  }
}
