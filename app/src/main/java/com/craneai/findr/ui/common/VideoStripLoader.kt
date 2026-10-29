package com.craneai.findr.ui.common

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.SystemClock
import android.util.LruCache
import com.craneai.findr.core.FindrLog as Log
import com.craneai.findr.core.IndexedFile
import com.craneai.findr.core.VideoStrip
import com.craneai.findr.extract.MediaVideoSampler
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * The pictures of the preview strip under a video result with a matched moment ([VideoStrip] decides which). Platform decoder only:
 * MediaExtractor for where the key frames are (a read of the file's index, no decoding) and MediaMetadataRetriever for the frames.
 * No model, no GPU vision, no network.
 *
 *  - Memory only. Frames are never written to disk, to the index or to a shared folder; [clear] empties the cache when the app locks
 *    or the sensitive results are put away.
 *  - A picture that is blurred ("Blur private-looking previews", [PreviewBlur]) is blurred here, before it is kept, and only the shrunk
 *    copy is held. The blur flag is part of the cache key, so revealing a file fetches its frames again.
 *  - At most [WORKERS] frame fetches run at once across the whole screen, on the IO pool: a scroll past many videos never queues up
 *    more than that, and what scrolled away is dropped by cancellation (the caller's coroutine) between fetches.
 */
object VideoStripLoader {
  private const val TAG = "FindrPerf"

  /** Long side of a decoded frame in px: a tile is at most ~80 dp, so this is sharp up to 3x density. */
  const val SIDE_PX = 256

  /** How long a strip waits after its row appears before decoding, so a row flicked past decodes nothing. */
  const val DEBOUNCE_MS = 250L

  /** Concurrent frame fetches. Two: the reference phone has two big cores, and a fetch is mostly one core's work. */
  const val WORKERS = 2

  private val gate = Semaphore(WORKERS)
  private val frames = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
    override fun sizeOf(key: String, value: Bitmap) = value.byteCount
  }
  private val plans = LruCache<String, VideoStrip.Plan>(64)

  /** Empties the cache: nothing of a video stays in memory once the app is locked. */
  fun clear() {
    frames.evictAll()
    plans.evictAll()
  }

  fun cachedPlan(file: IndexedFile, momentMs: Long): VideoStrip.Plan? = plans.get(planKey(file, momentMs))

  fun cachedFrame(file: IndexedFile, slot: VideoStrip.Slot, hidden: Boolean): Bitmap? = frames.get(frameKey(file, slot, hidden))

  /** For screenshot tests: puts finished pictures in the cache, so a strip draws exactly as it does when it is back on screen. */
  internal fun seed(file: IndexedFile, momentMs: Long, hidden: Boolean, plan: VideoStrip.Plan, bitmaps: List<Bitmap>) {
    plans.put(planKey(file, momentMs), plan)
    plan.slots.forEachIndexed { i, slot -> frames.put(frameKey(file, slot, hidden), bitmaps[i]) }
  }

  private fun planKey(file: IndexedFile, momentMs: Long) = "${file.uri}|$momentMs"
  private fun frameKey(file: IndexedFile, slot: VideoStrip.Slot, hidden: Boolean) = "${file.uri}|${slot.atMs}|${slot.exact}|$hidden"

  /**
   * Plans and fetches the strip for [file] at [momentMs], calling [onPlan] once the pictures are chosen and [onFrame] for each as it
   * arrives (the matched moment's first, then outwards). Returns when all are there; cancel it to stop, as the strip leaves the screen.
   */
  suspend fun load(
    context: Context,
    file: IndexedFile,
    momentMs: Long,
    hidden: Boolean,
    onPlan: (VideoStrip.Plan) -> Unit,
    onFrame: (index: Int, frame: Bitmap) -> Unit,
  ) = coroutineScope {
    val t0 = SystemClock.elapsedRealtime()
    val cachedPlan = cachedPlan(file, momentMs)
    val plan = cachedPlan ?: withContext(Dispatchers.IO) { planOf(context, file, momentMs) }.also { plans.put(planKey(file, momentMs), it) }
    val planMs = SystemClock.elapsedRealtime() - t0
    onPlan(plan)
    if (!plan.worthShowing) {
      Log.i(TAG, "strip slots=${plan.slots.size} shown=false planMs=$planMs")
      return@coroutineScope
    }
    val order = Channel<Int>(Channel.UNLIMITED)
    plan.fetchOrder.forEach { order.trySend(it) }
    order.close()
    val fromCache = AtomicInteger()
    val decoded = AtomicInteger()
    val failed = AtomicInteger()
    val firstMs = AtomicLong(-1)
    val workers = minOf(WORKERS, plan.slots.size)
    val jobs = List(workers) {
      launch(Dispatchers.IO) {
        var retriever: Retriever? = null
        try {
          for (i in order) {
            val slot = plan.slots[i]
            val hit = cachedFrame(file, slot, hidden)
            val bitmap = if (hit != null) { fromCache.incrementAndGet(); hit } else {
              ensureActive()
              gate.withPermit {
                val r = retriever ?: Retriever.open(context, file.uri).also { retriever = it }
                r.frame(slot, hidden)
              }?.also { frames.put(frameKey(file, slot, hidden), it); decoded.incrementAndGet() } ?: run { failed.incrementAndGet(); null }
            }
            if (bitmap != null) {
              firstMs.compareAndSet(-1, SystemClock.elapsedRealtime() - t0)
              onFrame(i, bitmap)
            }
          }
        } finally {
          retriever?.release()
        }
      }
    }
    try {
      jobs.forEach { it.join() }
    } finally {
      // One line per strip, numbers only: how long the plan took, when the first and the last picture were there, what was decoded.
      Log.i(
        TAG,
        "strip slots=${plan.slots.size} exact=${plan.slots.count { it.exact }} hidden=$hidden planMs=$planMs firstMs=${firstMs.get()} " +
          "allMs=${SystemClock.elapsedRealtime() - t0} decoded=${decoded.get()} cached=${fromCache.get()} failed=${failed.get()} " +
          "modelledMs=${plan.workMs}",
      )
    }
  }

  /** Key frames and length, read with MediaExtractor; the plan is made while the file is open and the extractor released at once. */
  private fun planOf(context: Context, file: IndexedFile, momentMs: Long): VideoStrip.Plan {
    val ex = MediaExtractor()
    try {
      ex.setDataSource(context, Uri.parse(file.uri), null)
      val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
        ?: return VideoStrip.plan(momentMs, file.durationMs, null)
      val format = ex.getTrackFormat(track)
      ex.selectTrack(track)
      val duration = file.durationMs?.takeIf { it > 0 }
        ?: if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) / 1000 else null
      val fps = runCatching { format.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat() }.getOrNull()?.takeIf { it > 0 } ?: 30f
      val keys = object : VideoStrip.Keyframes {
        override fun atOrBefore(timeMs: Long): Long? {
          ex.seekTo(timeMs * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
          return ex.sampleTime.takeIf { it >= 0 }?.div(1000)
        }

        override fun after(timeMs: Long): Long? {
          ex.seekTo((timeMs + 1) * 1000, MediaExtractor.SEEK_TO_NEXT_SYNC)
          return ex.sampleTime.takeIf { it >= 0 }?.div(1000)
        }
      }
      return VideoStrip.plan(momentMs, duration, keys, fps)
    } catch (e: CancellationException) {
      throw e
    } catch (_: Exception) {
      // A file the extractor cannot read: the key frames are unknown, so every spot is one key-frame fetch. Not an error worth a line.
      return VideoStrip.plan(momentMs, file.durationMs, null)
    } finally {
      ex.release()
    }
  }

  /** One open MediaMetadataRetriever and the size its frames come out at. Used by one worker, never shared. */
  private class Retriever(private val mmr: MediaMetadataRetriever, private val size: Pair<Int, Int>?) {
    fun frame(slot: VideoStrip.Slot, hidden: Boolean): Bitmap? {
      val option = if (slot.exact) MediaMetadataRetriever.OPTION_CLOSEST else MediaMetadataRetriever.OPTION_CLOSEST_SYNC
      val raw = try {
        if (size != null) mmr.getScaledFrameAtTime(slot.atMs * 1000, option, size.first, size.second)
        else mmr.getFrameAtTime(slot.atMs * 1000, option)?.let { shrink(it) }
      } catch (_: RuntimeException) {
        null   // the decoder was busy or the spot is past the last frame: that picture is left empty
      } ?: return null
      if (!hidden) return raw
      return PrivacyBlurTransformation.blurred(raw).also { if (it !== raw) raw.recycle() }
    }

    fun release() = runCatching { mmr.release() }

    private fun shrink(bitmap: Bitmap): Bitmap {
      val (w, h) = MediaVideoSampler.fitLongSide(bitmap.width, bitmap.height, SIDE_PX)
      if (w == bitmap.width && h == bitmap.height) return bitmap
      return Bitmap.createScaledBitmap(bitmap, w, h, true).also { bitmap.recycle() }
    }

    companion object {
      fun open(context: Context, uri: String): Retriever {
        val mmr = MediaMetadataRetriever()
        try {
          mmr.setDataSource(context, Uri.parse(uri))
          fun meta(key: Int) = mmr.extractMetadata(key)?.toIntOrNull() ?: 0
          val shown = MediaVideoSampler.displaySize(
            meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH), meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT),
            meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION),
          )
          return Retriever(mmr, shown?.let { MediaVideoSampler.fitLongSide(it.first, it.second, SIDE_PX) })
        } catch (e: Throwable) {
          runCatching { mmr.release() }
          throw e
        }
      }
    }
  }
}
