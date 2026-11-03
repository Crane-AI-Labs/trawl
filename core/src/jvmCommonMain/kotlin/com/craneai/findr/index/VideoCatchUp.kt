package com.craneai.findr.index

import com.craneai.findr.core.FrameMoment

/**
 * Videos read by an earlier build, brought up to today's reading without reading them again from scratch (old-videos-catchup).
 *
 * Two things a video only learns when it is read changed after many videos were already in people's indexes:
 *  - 1.0.4: a picture match shows the keyframe's own time ([FrameMoment], `segments.marks = "@ms"`). Rows from before have no mark and
 *    show the middle of their slot, which on a forwarded video (a keyframe every ~8 s) can be a different scene;
 *  - 1.0.5: the words on screen are read with the short-word rule, merged frames whose words can differ are read too, and each caption
 *    line carries the moment of its frame (core.OnScreenText, engine.MergedFrameReads, core.CaptionTimeline).
 * A video already in the index kept its old reading until the file changed. Deleting the index would get the new one, at the price of
 * reading every photo, document and recording again (hours on a 4 GB phone) for a few minutes of video; and D42 says the index is never
 * wiped for a recoverable problem.
 *
 * So an old video's pictures are read again for their words only: the same sampler, the same look-alike merge and the same rules as a
 * fresh read, with no picture embedded and no soundtrack (the embedding is most of a video's time). The same pass meets every sampled
 * picture at its keyframe, and that time goes onto the old picture rows that have none ([retime]). Picture vectors, speech, sound labels
 * and the name stay exactly as they are, and so does every other file.
 *
 * Which videos: a video's reading is stamped in `files.info` with [STAMP] on the first step of a read by a build that has it (IndexWorker)
 * and by the catch-up itself. An understood video without the stamp was read by an earlier build, or begun by one (a long video that was
 * mid-read during an update keeps its first windows' old reading, so it is unstamped too), and that is the whole queue
 * ([IndexStore.nextOldVideos]). A changed file, or a model change, clears `info` and reads the file again, which stamps it.
 */
object VideoCatchUp {
  /** The video reading a stamped video has. Raise it when a change to how videos are read should reach the videos already in the index. */
  const val VERSION = 1
  const val STAMP = "video-reading=$VERSION"

  /** A picture row of a video, as stored: its slot ([locStart] to [locEnd], grown by look-alike frames) and its keyframe mark, if any. */
  class FrameRow(val segmentId: Long, val locStart: Long, val locEnd: Long?, val marks: String?)

  /** What a video holds before the catch-up: its picture rows, the lines of its words, and whether those words carry vectors (lite). */
  class OldRows(val frames: List<FrameRow>, val ocrLines: List<String>, val ocrVectors: Boolean)

  /** Picture rows the catch-up may give a time: the sampler's own rows (they have an end; a quick-look row has only the moment it asked for) with no mark yet. */
  fun untimed(rows: List<FrameRow>): List<FrameRow> = rows.filter { it.marks == null && it.locEnd != null }

  /**
   * The keyframe marks to write, segment id to [FrameMoment] mark: each untimed picture row ([untimed]) whose slot the catch-up sampled
   * again ([sampled]: slot start to the time of the keyframe its picture was fetched at). The row's picture came from that same keyframe:
   * the slots are the sampler's plan, unchanged since the first release, and both builds fetch the keyframe nearest the slot's middle.
   * A row that already has a time keeps it; a row whose slot was not met again keeps showing the middle of its slot, as before.
   */
  fun retime(rows: List<FrameRow>, sampled: Map<Long, Long>): Map<Long, String> =
    untimed(rows).mapNotNull { r -> sampled[r.locStart]?.let { r.segmentId to FrameMoment.encode(it) } }.toMap()

  /**
   * True when the new reading lacks a line the old one had (the pictures came out differently on this phone, or could not be opened
   * this time). The old words then stay beside the new ones, so nothing that was findable stops being findable. The long rule is the
   * same in both readings, so on the same pictures the new reading always holds every old line and replaces it.
   */
  fun keepOld(oldLines: Collection<String>, newLines: Collection<String>): Boolean {
    val now = newLines.map { it.trim() }.filter { it.isNotEmpty() }.toHashSet()
    return oldLines.map { it.trim() }.any { it.isNotEmpty() && it !in now }
  }
}
