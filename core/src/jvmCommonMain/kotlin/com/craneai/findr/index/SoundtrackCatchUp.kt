package com.craneai.findr.index

/**
 * Videos and recordings read by an earlier build, brought up to today's reading of their soundtrack without reading them again from
 * scratch. The soundtrack is the speech model's words, gated by language, and the sound labels; the pictures, their vectors and their
 * words on screen are not part of it and stay exactly as they are (a full re-read would embed every picture again: most of a video's time).
 *
 * v1: sung stretches are read whole and judged as their own pool ([com.craneai.findr.core.SungReader], SpeechLanguageGate.keep).
 * That changes the words only of a file in which the sound labeller heard singing, so every other file is stamped without being opened
 * ([needsRead]); the rest are read again, soundtrack only, one per turn on the charger (IndexWorker.catchUpOldSoundtrack).
 * v1 also writes their "Sounds:" lines again, so they get the plain words after a label ("Male singing (man singing)",
 * [com.craneai.findr.engine.TranscriptPieces.PLAIN_WORDS]) that the same read gives at no extra cost. A file stamped without being
 * opened keeps its line (one whose only plain-words label is Babbling gets the words when it is next read).
 *
 * To reuse this for another change to the soundtrack (a new sound-label set): raise [VERSION], say in
 * [needsRead] which files the change can alter (return true for all when unsure), and keep [REFRESH_SOUNDS] when the "Sounds:" line
 * changed. The queue, the stamp, the resume and the in-flight guard are the same.
 *
 * The speech add-on (engine/LocalSpeech.kt) rides on the same queue with a stamp of its own, [PACK_KEY] = `<pack_id>@<pack_version>`
 * of the pack in use: while a pack is on, an understood video or recording whose speech was judged not English (somewhere: a window's
 * note says [com.craneai.findr.engine.SpeechLanguageGate.NOTE_NOT_ENGLISH]) and that lacks the pack's stamp is read again, soundtrack
 * only. Installing a pack, or a new version of it, therefore brings the voice notes already in the index up to it; English files are
 * never queued for it (the pack never reads an English window), and with no pack on the queue is v1's alone.
 */
object SoundtrackCatchUp {
  const val KEY = "soundtrack-reading"
  /** The soundtrack reading a stamped file has. Raise it when a change to how soundtracks are read should reach the files already in the index. */
  const val VERSION = 1
  /** True when a catch-up also replaces the "Sounds:" lines, not just the transcript. v1: the lines gained the plain words. */
  const val REFRESH_SOUNDS = true
  /** The most labels the "Sounds:" line holds ([com.craneai.findr.engine.TranscriptPieces.MAX_LABELS]): a full line may have lost its later labels. */
  const val LINE_LABELS = 12
  /** A catch-up is the in-flight guard's key, so two kinds of catch-up never share it. */
  const val INFLIGHT = "catchup_inflight_soundtrack"
  /** The speech pack a file's soundtrack was read with: `speech-pack=lg-student@2026112201` in `files.info`. */
  const val PACK_KEY = "speech-pack"

  /** The singing labels, lower case, as the sounds line spells them. */
  val SINGING = com.craneai.findr.core.SungReader.SUNG_LABELS.map { it.lowercase() }

  /**
   * Can v1 change this file's words? [sounds] is its "Sounds: a, b, c" line (null: it has none, so the labeller never ran or labelled
   * nothing, and we cannot tell). Only a line that is complete (under [LINE_LABELS] labels) and names no singing is safe to skip.
   */
  fun needsRead(sounds: String?): Boolean {
    if (sounds == null) return true
    val labels = sounds.removePrefix("Sounds:").split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
    if (labels.size >= LINE_LABELS) return true
    return labels.any { l -> SINGING.any { l.contains(it) } }
  }
}
