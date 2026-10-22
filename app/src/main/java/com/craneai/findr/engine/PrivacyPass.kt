package com.craneai.findr.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Process
import com.craneai.findr.core.FindrLog as Log
import com.craneai.findr.core.FindrLog.redact
import com.craneai.findr.core.FileKind
import com.craneai.findr.core.IndexedFile
import com.craneai.findr.core.PauseReason
import com.craneai.findr.core.PrivateLook
import java.util.concurrent.Executors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

private const val TAG = "FindrPrivacy"

/**
 * The "looks private" pass: every photo, screenshot and video gets one score, once, at index time (see [PrivateLook]: the
 * score only ever blurs a thumbnail). Runs inside the index worker beside the quick-look pass, with the same manners: its
 * own thread at a lowered priority, two ONNX Runtime threads, only while charging and not paused, waits while the user scrolls
 * or speech recognition runs, and works part of the time while EmbeddingGemma is embedding.
 *
 * Newest photos and screenshots first, then videos (the best of three keyframes). A file that can't be read is marked, so it
 * isn't retried every run; a model change ([PrivateLook.MODEL]) clears every score and the pass starts over.
 */
class PrivacyPass(
  private val context: Context,
  private val engine: FindrEngine,
  private val stopped: () -> Boolean,
) {
  /** Files scored (or marked unreadable) this run. */
  var done = 0
    private set

  suspend fun run() = withContext(dispatcher) {
    val store = engine.store
    store.ensurePrivateModel(PrivateLook.MODEL)
    val started = System.nanoTime()
    var batchStart = started
    var prepMs = 0L; var modelMs = 0L; var decodeMs = 0L; var inBatch = 0
    try {
      while (!stopped() && engine.privacyShouldRun()) {
        while (engine.userInteracting && !stopped()) delay(250)
        if (engine.workerState.pauseReason == PauseReason.TOO_HOT) { delay(1_000); continue }
        val batch = store.nextForPrivateScore(BATCH, engine.enabledKinds())
        if (batch.isEmpty()) break
        for (file in batch) {
          if (stopped()) break
          while (engine.userInteracting && !stopped()) delay(250)
          while (engine.speechActive.get() > 0 && !stopped()) delay(250)
          coroutineContext.ensureActive()
          val t0 = System.nanoTime()
          val ok = one(file)
          if (!ok) return@withContext   // the model itself won't run: stop, don't mark every file unreadable
          val workMs = (System.nanoTime() - t0) / 1_000_000
          decodeMs += workMs - engine.privacy.lastPrepMs - engine.privacy.lastModelMs
          prepMs += engine.privacy.lastPrepMs; modelMs += engine.privacy.lastModelMs
          done++; inBatch++
          if (engine.embeddingNow) delay((workMs * (1 / Tuning.current.privacyDuty - 1)).toLong())
        }
        val now = System.nanoTime()
        Log.i(TAG, "privacy scores: $inBatch files in ${(now - batchStart) / 1_000_000} ms (per file: decode " +
          "${decodeMs / maxOf(inBatch, 1)}, prep ${prepMs / maxOf(inBatch, 1)}, model ${modelMs / maxOf(inBatch, 1)} ms); $done this run")
        batchStart = now; prepMs = 0; modelMs = 0; decodeMs = 0; inBatch = 0
      }
    } finally {
      if (!engine.keepModelsResident()) engine.privacy.release()   // a roomy phone keeps the small scorer for the next run
      if (done > 0) Log.i(TAG, "privacy scores done: $done files in ${(System.nanoTime() - started) / 1_000_000_000} s")
    }
  }

  /** True: saved (a score, or a mark that the file can't be read). False: the model failed to run, so the pass stops. */
  private fun one(file: IndexedFile): Boolean {
    val uri = Uri.parse(file.uri)
    val scores = ArrayList<Float>(3)
    try {
      for (bitmap in pictures(file, uri)) {
        try {
          scores += engine.privacy.score(bitmap)
        } finally {
          bitmap.recycle()
        }
      }
    } catch (e: CancellationException) {
      throw e
    } catch (t: Throwable) {
      if (scores.isEmpty() && isModelRuntimeError(t)) {
        Log.w(TAG, "the privacy model failed on ${redact(file.name)}; stopping the pass", t)
        return false
      }
      Log.w(TAG, "can't read ${redact(file.name)}: ${t.javaClass.simpleName}")
    }
    engine.store.saveLooksPrivate(file.id, PrivateLook.ofFrames(scores))
    return true
  }

  private fun pictures(file: IndexedFile, uri: Uri): Sequence<Bitmap> =
    if (file.kind == FileKind.VIDEO) frames(context, uri, file.durationMs) else listOfNotNull(decode(context, uri)).asSequence()

  companion object {
    private const val BATCH = 16
    /**
     * Share of the time the pass works while EmbeddingGemma is embedding (it runs flat out when EmbeddingGemma is idle). 0.6, the
     * quick-look duty, slowed EmbeddingGemma by 15.7% on the A06; 0.2 slows it by about 5% (the research notes).
     */
    const val BUSY_DUTY = 0.2
    private const val FRAME_SIDE = 768
    /** Where in a video the three keyframes sit; a short or unknown-length clip uses its first frame. */
    private val FRAME_AT = doubleArrayOf(0.15, 0.5, 0.85)

    /** A picture decoded near twice the model's input size (the JPEG decoder shrinks by powers of two almost for free). */
    fun decode(context: Context, uri: Uri): Bitmap? {
      val resolver = context.contentResolver
      val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
      resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
      if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
      val options = BitmapFactory.Options().apply { inSampleSize = PrivateImage.sampleSize(bounds.outWidth, bounds.outHeight) }
      return resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    }

    /** Up to three keyframes (nearest sync frames, cheap to fetch), produced one at a time so only one bitmap lives at once. */
    fun frames(context: Context, uri: Uri, durationMs: Long?): Sequence<Bitmap> = sequence {
      val r = MediaMetadataRetriever()
      try {
        r.setDataSource(context, uri)
        val total = durationMs ?: r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        val at = if (total < 3_000) listOf(0L) else FRAME_AT.map { (total * it).toLong() * 1_000 }
        for (us in at) {
          val frame = r.getScaledFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, FRAME_SIDE, FRAME_SIDE) ?: continue
          yield(frame)
        }
      } finally {
        r.release()
      }
    }

    /** One thread, nice 5 like the quick-look pass: EmbeddingGemma's CPU threads get the larger share when they compete. */
    internal val dispatcher = Executors.newSingleThreadExecutor { r ->
      Thread({ Process.setThreadPriority(5); r.run() }, "findr-privacy").apply { isDaemon = true }
    }.asCoroutineDispatcher()
  }
}
