package com.craneai.findr.engine

import com.craneai.findr.core.FileKind

/**
 * What Findr does with a sound file or a video's soundtrack: read its words with the speech model, and/or embed it with
 * EmbeddingGemma's audio encoder (slow: ~254 ms per second of sound on the A06). One pure function, so the table is
 * testable and the screen's promise ("voice notes and recordings are read") is the same rule the worker runs.
 *
 *  - Speech to text works (setting on and the speech pack installed): words and sound labels. Speech that is not English
 *    (the language gate) also gets the audio vector, decided later in [FileProcessor] because it needs the transcript.
 *  - No speech to text, a short recording (voice notes, 5 minutes or less): the audio vector, so it is findable by meaning.
 *  - No speech to text, a long recording (music, podcasts): nothing beyond its name, until "Deeper sound search" is on.
 *  - "Deeper sound search" on: the audio vector for every recording, as before.
 *  - Videos: unchanged. The vector only comes with "Deeper sound search".
 */
object AudioPolicy {
  /** Up to this long counts as a voice note or recording that is worth the audio vector by default. */
  const val SHORT_MS = 5 * 60_000L

  /** [speech]: read the words (a transcript). [vector]: embed the sound with the audio encoder. Neither: name only. */
  data class Plan(val speech: Boolean, val vector: Boolean) {
    val nothing: Boolean get() = !speech && !vector
  }

  /**
   * [speechToText]: the user's setting. [speechPack]: the speech models are installed. [deeper]: the "Deeper sound search"
   * setting. [durationMs] null (unknown) counts as short: the audio vector is capped at [FileProcessor.MAX_AUDIO_WINDOWS]
   * windows anyway, so an unknown length can't cost more than a short recording.
   */
  fun plan(kind: FileKind, durationMs: Long?, speechToText: Boolean, speechPack: Boolean, deeper: Boolean): Plan {
    val speech = speechToText      // asked for; with the pack missing it is noted and redone once the pack arrives
    val reads = speechToText && speechPack
    val short = durationMs == null || durationMs <= SHORT_MS
    val vector = when (kind) {
      FileKind.AUDIO -> deeper || (!reads && short)
      FileKind.VIDEO -> deeper
      else -> false
    }
    return Plan(speech = speech, vector = vector)
  }
}
