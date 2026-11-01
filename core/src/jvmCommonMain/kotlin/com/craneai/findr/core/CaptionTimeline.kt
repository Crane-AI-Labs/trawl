package com.craneai.findr.core

/**
 * The words read off a video's frames, each line with the moment its frame is in the video, so a search on a caption can say WHEN
 * it is on screen. A meme's caption or a slide's title repeats in every frame it stays on, so each distinct line is kept once, at
 * the EARLIEST frame it was read in (the moment it appeared).
 *
 * The piece's text is the lines joined by newlines, in the order first seen. The times travel with it in the same encoding the
 * transcripts use ([TranscriptMarks]: `offset:startMs:endMs` per line, both times the frame's), so a hit places the matched line
 * through [TranscriptMarks.timeAt] exactly as it places a spoken word, and the piece itself spans [startMs] to [endMs].
 */
class CaptionTimeline {
  private val firstSeen = LinkedHashMap<String, Long>()

  /** Adds [line] as read in the frame at [atMs]; a line already known keeps the earlier time. Blank lines are ignored. */
  fun add(line: String, atMs: Long) {
    val clean = line.trim()
    if (clean.isEmpty()) return
    val known = firstSeen[clean]
    if (known == null || atMs < known) firstSeen[clean] = atMs
  }

  fun isEmpty(): Boolean = firstSeen.isEmpty()

  /** The lines, one per row of the text, in the order first seen. */
  fun lines(): List<String> = firstSeen.keys.toList()

  fun text(): String = firstSeen.keys.joinToString("\n")

  /** When the first and the last line appeared (null with no lines). */
  val startMs: Long? get() = firstSeen.values.minOrNull()
  val endMs: Long? get() = firstSeen.values.maxOrNull()

  /** The per-line times for [text], in [TranscriptMarks]' encoding; null with no lines. */
  fun marks(): String? {
    var offset = 0
    val marks = ArrayList<TranscriptMarks.Mark>(firstSeen.size)
    for ((line, at) in firstSeen) {
      marks += TranscriptMarks.Mark(offset, at, at)
      offset += line.length + 1   // the newline
    }
    return TranscriptMarks.encode(marks)
  }
}
