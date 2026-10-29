package com.craneai.findr.extract

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.SystemClock
import com.craneai.findr.core.FindrLog as Log
import com.craneai.findr.core.FormatNotReadable
import com.craneai.findr.core.VideoFrame
import com.craneai.findr.core.VideoSampler
import java.io.ByteArrayOutputStream
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

private const val TAG = "FindrVideo"

/**
 * Picks one representative frame per shot, for a video the embedder will see as a handful of images.
 *
 * Meme videos are mostly a few shots with burned-in captions, so a scan at ~1 frame/second of 64x64 thumbnails
 * ([ShotDetector]) finds the shots cheaply, and only the chosen mid-shot frames are fetched at up to 768 px. Each
 * [VideoFrame] carries the JPEG for the embedder and the Bitmap for OCR of the captions; the caller owns (and should
 * recycle) the bitmaps, at most `maxFrames` of them.
 *
 * Videos under 3 s get one frame, audio-only files get none, and a failure logs and keeps the frames fetched before it.
 * Each frame's `timeMs` is the keyframe's own time: the picture is fetched at the keyframe nearest the middle of its slot and that
 * keyframe's time is what the result shows.
 */
class MediaVideoSampler : VideoSampler {

  override suspend fun sample(context: Context, uri: Uri, maxFrames: Int): List<VideoFrame> =
    ArrayList<VideoFrame>().also { frames -> frames(context, uri, maxFrames, 0, Long.MAX_VALUE) { frames.add(it) } }

  /** Each frame goes to [onFrame] as soon as it is fetched, so the embedder can work while the next one decodes. */
  override suspend fun stream(context: Context, uri: Uri, maxFrames: Int, fromMs: Long, spanMs: Long, onFrame: suspend (VideoFrame) -> Unit) {
    frames(context, uri, maxFrames, fromMs, spanMs, onFrame)
  }

  private suspend fun frames(context: Context, uri: Uri, maxFrames: Int, fromMs: Long, spanMs: Long, onFrame: suspend (VideoFrame) -> Unit): Unit =
    withContext(Dispatchers.IO) {
      val started = SystemClock.elapsedRealtime()
      val ctx = currentCoroutineContext()
      val retriever = MediaMetadataRetriever()
      var kept = 0
      try {
        retriever.setDataSource(context, uri)
        val hasVideo = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO)
        if (hasVideo != null && hasVideo != "yes") return@withContext
        fun meta(key: Int) = retriever.extractMetadata(key)?.toLongOrNull() ?: 0L
        // One window of a long video: frames come only from [fromMs, fromMs + span), shot times are counted from the window's start.
        val duration = minOf(meta(MediaMetadataRetriever.METADATA_KEY_DURATION) - fromMs, spanMs)
        if (duration <= 0 && fromMs > 0) return@withContext
        val rotation = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION).toInt()
        val shown = displaySize(
          meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH).toInt(),
          meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT).toInt(), rotation,
        )

        // 1. Cheap scan: tiny frames about once a second, for short videos only. Each tiny frame is a seek, ~180 ms on
        // the A06, so a 4-minute video spent 43 s here (the build notes, video sweep); past a minute, evenly spaced frames
        // cover it about as well for what people search.
        val times = if (duration <= SHOT_SCAN_MAX_MS) ShotDetector.sampleTimes(duration) else LongArray(0)
        val lumas = ArrayList<IntArray>()
        val sampled = ArrayList<Long>()
        if (times.size > 1) {
          for (t in times) {
            ctx.ensureActive()
            val tiny = retriever.getScaledFrameAtTime(
              (fromMs + t) * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, TINY, TINY,
            ) ?: continue
            lumas.add(lumaOf(tiny))
            sampled.add(t)
            tiny.recycle()
          }
        }

        // 2. Shots, and the mid-shot moment of each one we keep.
        val shots = if (lumas.size > 1) {
          val spans = ShotDetector.spans(ShotDetector.boundaries(lumas), sampled.toLongArray(), duration)
          ShotDetector.pick(spans, maxFrames.coerceAtLeast(1))
        } else {
          val n = if (duration > SHOT_SCAN_MAX_MS) maxFrames.coerceAtLeast(1) else 1
          (0 until n).map { i -> ShotSpan(duration * i / n, maxOf(duration * (i + 1) / n, 1)) }
        }

        // 3. Fetch just those frames, at embedder size, handing each one over as soon as it is ready. The keyframe nearest the middle of a
        // slot is looked up first (the container's index, nothing decoded) and the picture fetched AT that time, so the frame carries the
        // time it really comes from, not the time it was asked for ([KeyframeClock]).
        val clock = if (shots.isNotEmpty()) KeyframeClock.open(context, uri) else null
        var clockNs = 0L
        try {
          for (shot in shots) {
            ctx.ensureActive()
            val asked = fromMs + shot.midMs
            val t0 = System.nanoTime()
            val at = clock?.nearestMs(asked) ?: asked
            clockNs += System.nanoTime() - t0
            val bitmap = fetch(retriever, at, shown, rotation) ?: continue
            val jpeg = ByteArrayOutputStream(64 * 1024).also { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            onFrame(VideoFrame(at, fromMs + shot.startMs, fromMs + shot.endMs, jpeg.toByteArray(), bitmap))
            kept++
          }
        } finally {
          clock?.close()
        }
        Log.i(
          TAG,
          "sampled ${duration / 1000.0}s video: ${lumas.size} tiny frames, ${shots.size} shots, " +
            "$kept kept in ${SystemClock.elapsedRealtime() - started} ms (keyframe times ${clockNs / 1_000_000} ms)",
        )
        // The container opened and says it has pictures, but the phone's decoders gave none: not a video it can read.
        if (kept == 0 && shots.isNotEmpty()) throw FormatNotReadable("no video frame could be decoded")
      } catch (e: CancellationException) {
        throw e
      } catch (t: Throwable) {
        Log.w(TAG, "video not sampled (${Log.brief(t)}) after ${SystemClock.elapsedRealtime() - started} ms")
        com.craneai.findr.diag.Diag.handled(com.craneai.findr.diag.HandledKind.DECODER, t)
        // Frames fetched before a failure are kept (the caller has them already); with none, the caller is told the file can't be opened.
        if (kept == 0) throw (t as? FormatNotReadable ?: FormatNotReadable("video not opened: ${Log.brief(t)}", t))
      } finally {
        try {
          retriever.release()
        } catch (_: Exception) {
        }
      }
    }

  /**
   * The keyframe nearest `timeMs`, long side at most [MAX_SIDE], upright. Null if the decoder gives nothing.
   * Nearest *keyframe*, not the exact frame: an exact frame is decoded from the previous keyframe (~10 ms per frame on
   * the A06, ~125 frames with x264's default 8.3 s keyframe spacing), and encoders put keyframes at scene cuts anyway.
   * Video retrieval is unchanged on the eval (the research notes); 60 s video ≈ 52 s → 29 s modelled.
   */
  private fun fetch(r: MediaMetadataRetriever, timeMs: Long, shown: Pair<Int, Int>?, rotation: Int): Bitmap? {
    val option = MediaMetadataRetriever.OPTION_CLOSEST_SYNC
    var bitmap = if (shown != null) {
      val (w, h) = fitLongSide(shown.first, shown.second, MAX_SIDE)
      r.getScaledFrameAtTime(timeMs * 1000, option, w, h)
    } else {
      r.getFrameAtTime(timeMs * 1000, option)?.let { shrink(it) }
    } ?: return null
    // The platform hands frames over upright; this only fires if a device's decoder leaves them turned.
    if (shown != null && needsRotation(bitmap.width, bitmap.height, shown.first, shown.second, rotation)) {
      val turned = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(rotation.toFloat()) }, true)
      if (turned !== bitmap) bitmap.recycle()
      bitmap = turned
    }
    return bitmap
  }

  private fun shrink(bitmap: Bitmap): Bitmap {
    val (w, h) = fitLongSide(bitmap.width, bitmap.height, MAX_SIDE)
    if (w == bitmap.width && h == bitmap.height) return bitmap
    return Bitmap.createScaledBitmap(bitmap, w, h, true).also { bitmap.recycle() }
  }

  private fun lumaOf(bitmap: Bitmap): IntArray {
    val w = bitmap.width
    val h = bitmap.height
    val pixels = IntArray(w * h)
    bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
    return IntArray(pixels.size) {
      val p = pixels[it]
      (299 * ((p shr 16) and 0xFF) + 587 * ((p shr 8) and 0xFF) + 114 * (p and 0xFF)) / 1000
    }
  }

  companion object {
    private const val TINY = 64
    private const val MAX_SIDE = 768
    // Shot detection off: evenly spaced frames beat one-per-shot on the video eval (visual r@1 0.96 vs 0.84,
    // the research notes) and skip the ~180 ms/s scan. ShotDetector stays for a later experiment.
    private const val SHOT_SCAN_MAX_MS = 0L

    /** Coded size turned by the rotation tag into what the viewer sees. Null when the size is unknown. */
    internal fun displaySize(width: Int, height: Int, rotation: Int): Pair<Int, Int>? {
      if (width <= 0 || height <= 0) return null
      return if (rotation == 90 || rotation == 270) height to width else width to height
    }

    /** `w` x `h` shrunk to a long side of at most `max`, never enlarged. */
    internal fun fitLongSide(w: Int, h: Int, max: Int): Pair<Int, Int> {
      val long = maxOf(w, h)
      if (long <= max) return w to h
      val f = max.toDouble() / long
      return Math.round(w * f).toInt().coerceAtLeast(1) to Math.round(h * f).toInt().coerceAtLeast(1)
    }

    /** True when a frame came back lying on its side: its orientation disagrees with the displayed one. */
    internal fun needsRotation(frameW: Int, frameH: Int, shownW: Int, shownH: Int, rotation: Int): Boolean {
      if (rotation != 90 && rotation != 270) return false
      if (shownW == shownH || frameW == frameH) return false
      return (frameW > frameH) != (shownW > shownH)
    }
  }
}
