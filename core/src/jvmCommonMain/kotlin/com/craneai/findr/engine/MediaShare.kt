package com.craneai.findr.engine

/**
 * Fair turns between photos/documents and the slow kinds (videos, recordings) in the bulk pass. Pure, so the rule is unit-tested.
 *
 * Before: the backlog was one queue, kind by kind (screenshots, photos, documents, then videos, then audio) after the last 14 days.
 * A 16,000-photo gallery is ~19 hours of photos, so a video from last year, and every recording, waited for all of them. Now the
 * slow kinds get a share of the BUSY TIME, not of the file count (a video costs several photos, a recording more):
 * [share] = 0.2 means about one second in five goes to videos and recordings while photos wait. When one side has nothing waiting
 * the other takes everything, so no time is wasted, and with [share] 0 the old order is restored exactly.
 */
class MediaShare(private val share: Double) {
  /** True when the share is 0: the old single queue, no turns. */
  val noShare: Boolean get() = share <= 0.0

  private var mediaMs = 0L
  private var otherMs = 0L

  /** True when the next file should be a video or recording: one is waiting and it is owed time (or nothing else is waiting). */
  fun mediaTurn(mediaWaiting: Boolean, otherWaiting: Boolean): Boolean = when {
    !mediaWaiting -> false
    !otherWaiting -> true
    share <= 0.0 -> false
    else -> mediaMs < share * (mediaMs + otherMs)
  }

  private var lastMedia: com.craneai.findr.core.FileKind? = null

  /**
   * Inside the media share, videos and recordings take turns: a recording (tens of seconds of speech) is not left behind twenty
   * videos. Returns [kinds] in the order to try them: the kind that did not have the last media turn first.
   */
  fun mediaOrder(kinds: Set<com.craneai.findr.core.FileKind>): List<com.craneai.findr.core.FileKind> {
    val order = if (lastMedia == com.craneai.findr.core.FileKind.VIDEO) listOf(com.craneai.findr.core.FileKind.AUDIO, com.craneai.findr.core.FileKind.VIDEO)
    else listOf(com.craneai.findr.core.FileKind.VIDEO, com.craneai.findr.core.FileKind.AUDIO)
    return order.filter { it in kinds }
  }

  /** Records the time a file took, so the next turn is owed fairly ([kind]: which media kind had the turn). */
  fun record(media: Boolean, ms: Long, kind: com.craneai.findr.core.FileKind? = null) {
    if (media) { mediaMs += ms; if (kind != null) lastMedia = kind } else otherMs += ms
  }

  /** The share of the recorded time that went to videos and recordings (0 before anything ran). */
  val achieved: Double get() = (mediaMs + otherMs).let { if (it == 0L) 0.0 else mediaMs.toDouble() / it }

  companion object {
    /** Kinds that cost many photos each: a video is windowed frames plus a soundtrack, a recording is speech. */
    val MEDIA_KINDS = setOf(com.craneai.findr.core.FileKind.VIDEO, com.craneai.findr.core.FileKind.AUDIO)
  }
}

/** The quick-look queue's interleave: how many videos ride in each batch of photos. Pure. */
object QuickMix {
  /**
   * [batch] files out of [photos] (newest first) and [videos] (newest first): about [videoShare] of the batch are videos (at least
   * one when any is waiting and the share is above 0), the rest photos; a side that runs short is topped up from the other.
   * The order inside a batch is photos first then videos; callers treat a batch as a unit. With [videoShare] 0, photos only until
   * they run out (the old order).
   */
  fun <T> pick(photos: List<T>, videos: List<T>, batch: Int, videoShare: Double): List<T> {
    if (batch <= 0) return emptyList()
    val wantVideos = when {
      videos.isEmpty() -> 0
      videoShare <= 0.0 -> 0
      else -> maxOf(1, Math.round(batch * videoShare).toInt()).coerceAtMost(batch)
    }
    val v = videos.take(wantVideos)
    val p = photos.take(batch - v.size)
    val fill = batch - v.size - p.size
    val extraVideos = if (fill > 0) videos.drop(v.size).take(fill) else emptyList()
    return p + v + extraVideos
  }
}
