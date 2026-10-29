package com.craneai.findr.core

/**
 * The moment a video's picture match really comes from. A FRAME segment stores the stretch of the video its picture stands for (the
 * sampler's slot, `loc_start` to `loc_end`, which look-alike frames grow), but the picture itself is the keyframe nearest the middle of
 * the slot, and that keyframe can sit several seconds from the slot's start (a forwarded video has one every ~8 s). A result that read
 * "at 0:40" for the slot start sent people to a moment that looks different from the picture that matched.
 *
 * So the segment also keeps the keyframe's own time in `segments.marks` as `@<ms>` (the column transcripts use for word times; no other
 * kind reads it for a FRAME). Rows indexed before that have no such mark: [at] then takes the middle of the slot's first stretch, which
 * is where the sampler asked for the picture, and is nearer the truth than the slot start.
 */
object FrameMoment {
  /** The widest a slot was when the sampler asked for a picture from its middle (10 s slots on long videos): half of it. */
  const val MAX_LEAD_MS = 5_000L

  fun encode(atMs: Long): String = "@$atMs"

  /** The keyframe time in [marks], or null when the row has none (indexed earlier) or it can't be read. */
  fun decode(marks: String?): Long? {
    if (marks == null || !marks.startsWith("@")) return null
    return marks.substring(1).toLongOrNull()?.takeIf { it >= 0 }
  }

  /**
   * The moment to show for a FRAME segment: its own keyframe time when stored, else (a row from before) the middle of the slot's
   * first stretch: a merged look-alike slot has grown past the original, so the lead is capped at [MAX_LEAD_MS], and a segment with
   * no end (the quick-look pass stores the moment it asked for) shows its start.
   */
  fun at(locStart: Long, locEnd: Long?, marks: String?): Long {
    decode(marks)?.let { return it }
    if (locEnd == null || locEnd <= locStart) return locStart
    return locStart + minOf((locEnd - locStart) / 2, MAX_LEAD_MS)
  }
}
