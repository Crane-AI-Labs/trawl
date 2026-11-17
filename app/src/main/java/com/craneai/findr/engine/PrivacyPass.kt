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
import kotlinx.coroutines.yield
import com.craneai.findr.index.IndexStore
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

  /**
   * [pace]: how fast to score while the phone is still making photos searchable ([PrivacyPace]); one per worker run, so an episode's
   * 15 min carry over when the pass ends and restarts within the run.
   */
  suspend fun run(pace: PrivacyPace = engine.newPrivacyPace()) = withContext(dispatcher) {
    val store = engine.store
    if (store.ensurePrivateModel(PrivateLook.MODEL)) engine.privacyOnDemand.forget()
    engine.privacyPassRunning = true
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
          // A thumbnail on screen asked for its score (blur until scored): it goes first, and is not scored twice.
          yield()
          if (engine.privacyOnDemand.known(file.id)) continue
          while (engine.userInteracting && !stopped()) delay(250)
          while (engine.speechActive.get() > 0 && !stopped()) delay(250)
          coroutineContext.ensureActive()
          val t0 = System.nanoTime()
          if (scoreAndSave(context, engine, file, "pass") == null) return@withContext   // the model itself won't run: stop, don't mark every file unreadable
          val workMs = (System.nanoTime() - t0) / 1_000_000
          decodeMs += workMs - engine.privacy.lastPrepMs - engine.privacy.lastModelMs
          prepMs += engine.privacy.lastPrepMs; modelMs += engine.privacy.lastModelMs
          done++; inBatch++
          // The usual share while EmbeddingGemma embeds, plus the rest while photos are still being made searchable, or none at all
          // once they are (PrivacyPace). Only this background pass rests: a thumbnail on screen is scored on demand, never paced.
          val before = pace.last
          val pacedMs = pace.pacedMs()
          val mode = pace.mode()
          if (mode != before) Log.i(TAG, "privacy pace: $mode (episode paced ${maxOf(pacedMs, pace.pacedMs()) / 1000} s, $done scored this run)")
          pace.restMs(mode, workMs, engine.embeddingNow, Tuning.current.privacyDuty).let { if (it > 0) delay(it) }
          // Heat pacing (a study knob, off in every shipped build): once the phone is warm, a further rest in proportion to the work.
          if (Tuning.current.paces) HeatPace.restMs(workMs, engine.heatPaced(), Tuning.current.pacePrivacyDuty).let { if (it > 0) delay(it) }
        }
        val now = System.nanoTime()
        Log.i(TAG, "privacy scores: $inBatch files in ${(now - batchStart) / 1_000_000} ms (per file: decode " +
          "${decodeMs / maxOf(inBatch, 1)}, prep ${prepMs / maxOf(inBatch, 1)}, model ${modelMs / maxOf(inBatch, 1)} ms); $done this run")
        batchStart = now; prepMs = 0; modelMs = 0; decodeMs = 0; inBatch = 0
      }
    } finally {
      engine.privacyPassRunning = false
      // A roomy phone keeps the small scorer for the next run; so does a phone still scoring thumbnails on screen.
      if (!engine.keepModelsResident() && !engine.privacyOnDemand.busy) engine.privacy.release()
      if (done > 0) Log.i(TAG, "privacy scores done: $done files in ${(System.nanoTime() - started) / 1_000_000_000} s")
    }
  }

  companion object {
    /**
     * Scores one picture and saves it, and tells the thumbnails waiting for it ([PrivacyOnDemand.saved]). The score, or
     * [IndexStore.UNREADABLE_SCORE] when the file can't be read (saved too, so it isn't retried every run); null when the model itself
     * failed to run (nothing saved). Blocking: call it on [dispatcher]. [via] names the caller in the detail log ("pass", "demand").
     */
    internal fun scoreAndSave(context: Context, engine: FindrEngine, file: IndexedFile, via: String): Float? {
      val uri = Uri.parse(file.uri)
      val scores = ArrayList<Float>(3)
      try {
        for (bitmap in pictures(context, file, uri)) {
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
          Log.w(TAG, "the privacy model failed on ${redact(file.name)} ($via)", t)
          return null
        }
        Log.w(TAG, "can't read ${redact(file.name)}: ${t.javaClass.simpleName}")
      }
      val score = PrivateLook.ofFrames(scores)
      engine.store.saveLooksPrivate(file.id, score)
      val saved = score ?: IndexStore.UNREADABLE_SCORE
      engine.privacyOnDemand.saved(file.id, saved, via)
      return saved
    }

    private fun pictures(context: Context, file: IndexedFile, uri: Uri): Sequence<Bitmap> =
      if (file.kind == FileKind.VIDEO) frames(context, uri, file.durationMs) else listOfNotNull(decode(context, uri)).asSequence()

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
