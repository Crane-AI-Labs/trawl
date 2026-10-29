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
import com.craneai.findr.core.PauseReason
import com.craneai.findr.index.IndexStore
import com.craneai.findr.index.PendingFile
import com.craneai.findr.index.SegmentKind
import java.util.concurrent.Executors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

private const val TAG = "FindrQuickLook"

/**
 * The quick-look pass (decision D2): every photo, screenshot and video (its first frame) gets a small CLIP vector on the
 * CPU while EmbeddingGemma works through the same files on the GPU, ~0.1 s each against ~4.2 s on the Galaxy A06, so the
 * whole gallery is searchable by meaning within minutes of charging instead of hours. EmbeddingGemma's vectors replace
 * each quick vector as they arrive (IndexStore.saveSegments drops it), so the end state is EmbeddingGemma's quality.
 *
 * Runs inside the index worker, so it runs when IndexPolicy allows the light tier (on the charger, or on battery at 30% or more and not hot, and not paused), plus: one thread of its own at a
 * lowered priority, with the image tower's 2 ONNX Runtime threads (never more), and it waits while the user scrolls.
 * Newest photos and screenshots first, then videos newest first; files EmbeddingGemma already understood are skipped.
 */
class QuickLookPass(
  private val context: Context,
  private val engine: FindrEngine,
  private val stopped: () -> Boolean,
) {
  /** Files given a vector (or found unreadable) this run. */
  var done = 0
    private set

  suspend fun run() = withContext(dispatcher) {
    val store = engine.store
    if (store.ensureQuickFingerprint(QuickLookPack.FINGERPRINT)) engine.quickVectors.clear()
    val started = System.nanoTime()
    var batchStart = started
    var prepMs = 0L; var modelMs = 0L; var decodeMs = 0L; var inBatch = 0
    try {
      while (!stopped() && engine.quickLookShouldRun()) {
        while (engine.userInteracting && !stopped()) delay(250)   // the grid gets the CPU while the user scrolls
        if (engine.workerState.pauseReason == PauseReason.TOO_HOT) { delay(1_000); continue }   // the worker is cooling the phone
        val batch = store.nextForQuickLook(BATCH, engine.enabledKinds(), Tuning.current.quickVideoShare)
        if (batch.isEmpty()) break
        for (p in batch) {
          if (stopped()) break
          while (engine.userInteracting && !stopped()) delay(250)
          // Speech recognition beside EmbeddingGemma's video frames leaves no CPU to spare: a quick look then cost EG +13% per
          // frame on the A06 (vision agent, 25-video mix), so the pass waits until the soundtrack is done (the research notes).
          while (engine.speechActive.get() > 0 && !stopped()) delay(250)
          coroutineContext.ensureActive()
          val t0 = System.nanoTime()
          val ok = one(p)
          if (ok == null) return@withContext   // the model itself won't run: stop, don't mark every file unreadable
          val workMs = (System.nanoTime() - t0) / 1_000_000
          Log.i(TAG, "quick ${redact(p.file.name)}: ${p.file.kind} ok in $workMs ms")   // log-safe: the file name is redacted, the kind is an enum
          decodeMs += workMs - engine.clip.lastPrepMs - engine.clip.lastModelMs
          prepMs += engine.clip.lastPrepMs; modelMs += engine.clip.lastModelMs
          done++; inBatch++
          // While EmbeddingGemma is embedding, run only part of the time: the CPU is no longer idle beside its GPU encoder
          // (OCR runs there now), and a full-time lane cost it 8-15% per photo on the A06 against a 10% budget (the research notes).
          // During the words pass the share is [wordsDuty] (D10: the lane delays OCR text, so the two are balanced).
          val duty = when {
            engine.embeddingNow -> Tuning.current.quickDuty
            engine.wordsPassNow -> wordsDuty(context)
            else -> 1.0
          }
          if (duty < 1.0) delay((workMs * (1 / duty - 1)).toLong())
        }
        val now = System.nanoTime()
        Log.i(TAG, "quick look: $inBatch files in ${ms(now - batchStart)} ms (per file: decode ${decodeMs / maxOf(inBatch, 1)}, " +
          "prep ${prepMs / maxOf(inBatch, 1)}, model ${modelMs / maxOf(inBatch, 1)} ms); $done this run")
        batchStart = now; prepMs = 0; modelMs = 0; decodeMs = 0; inBatch = 0
      }
    } finally {
      if (!engine.keepModelsResident()) engine.clip.releaseImage()   // a roomy phone keeps the picture model for the next run
      if (done > 0) Log.i(TAG, "quick look done: $done files in ${ms(System.nanoTime() - started) / 1000} s")
    }
  }

  /** True: saved (a vector, or a mark that the file can't be read). Null: the model failed to run, so the pass stops. */
  private fun one(p: PendingFile): Boolean? {
    val file = p.file
    val uri = Uri.parse(file.uri)
    val video = file.kind == FileKind.VIDEO
    var seekMs = 0L
    val bitmap = try {
      if (video) frameAt(context, uri, Tuning.current.quickVideoSeekMs).also { seekMs = it.second }.first else decode(context, uri)
    } catch (e: CancellationException) {
      throw e
    } catch (t: Throwable) {
      Log.w(TAG, "can't read ${redact(file.name)}: ${t.javaClass.simpleName}")
      com.craneai.findr.diag.Diag.handled(com.craneai.findr.diag.HandledKind.DECODER, t)
      null
    }
    val vector = if (bitmap == null) null else try {
      engine.clip.image(bitmap)
    } catch (t: Throwable) {
      Log.w(TAG, "the quick-look model failed on ${redact(file.name)}; stopping the pass", t)
      com.craneai.findr.diag.Diag.handled(com.craneai.findr.diag.HandledKind.WORKER, t)
      return null
    } finally {
      bitmap.recycle()
    }
    val id = engine.store.saveQuick(file.id, if (video) SegmentKind.FRAME else SegmentKind.IMAGE, vector, locStart = if (video) seekMs else null)
    if (id >= 0 && vector != null) engine.onQuickIndexed(file.id)
    return true
  }

  private fun ms(nanos: Long) = nanos / 1_000_000

  companion object {
    private const val BATCH = 16

    /**
     * The share of the time the pass works while EmbeddingGemma is embedding. Measured on the A06 (`d2-bench.sh cost`, A/B/A
     * in one run, the research notes D10): with TinyCLIP's faster image tower 60% cost EmbeddingGemma +10.3% and +11.9% per photo
     * (4.2-4.3 quick photos per EmbeddingGemma photo), 50% cost +5.5% (3.2); D2's laion was +1.6% / +6.4% at 60%.
     */
    const val BUSY_DUTY = 0.5

    /** The share of the time the pass works while the words pass runs (build-time, `-PquickWordsDuty`; 1.0 = full speed). */
    val WORDS_DUTY: Double = com.craneai.findr.BuildConfig.QUICK_WORDS_DUTY

    /** [WORDS_DUTY], unless a bench run set `quickWordsDuty` in the `findr_tuning` preferences (scripts/d2-bench.sh). */
    fun wordsDuty(context: Context): Double =
      if (!com.craneai.findr.BuildConfig.DEBUG) WORDS_DUTY   // a release build never reads a bench's leftover value
      else context.getSharedPreferences(TUNING, Context.MODE_PRIVATE).getFloat("quickWordsDuty", WORDS_DUTY.toFloat()).toDouble()
        .coerceIn(0.1, 1.0)

    const val TUNING = "findr_tuning"
    private const val FRAME_SIDE = 512

    /** A picture decoded near twice the model's input size: the JPEG decoder shrinks by powers of two almost for free. */
    fun decode(context: Context, uri: Uri): Bitmap? {
      val resolver = context.contentResolver
      val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
      resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
      if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
      val options = BitmapFactory.Options().apply { inSampleSize = ClipImage.sampleSize(bounds.outWidth, bounds.outHeight) }
      return resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    }

    /** A video's first frame (the nearest key frame to 0, which is cheap to fetch), scaled to fit 512 px. */
    fun firstFrame(context: Context, uri: Uri): Bitmap? = frameAt(context, uri, 0L, timed = false).first

    /**
     * A video's picture for quick look, scaled to fit 512 px, and the moment it was taken at (ms): the nearest key frame to [seekMs]
     * but no later than a quarter of the way in (a short clip is not read past its start). [seekMs] 0 is the very first frame, which
     * in a phone video is often a black or half-faded one that says nothing about the clip. With [timed] the moment is the key frame's
     * own (found in the container's index); without, the one asked for (a caller that only wants the picture).
     */
    fun frameAt(context: Context, uri: Uri, seekMs: Long, timed: Boolean = true): Pair<Bitmap?, Long> {
      val r = MediaMetadataRetriever()
      try {
        r.setDataSource(context, uri)
        val duration = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        val asked = seekMsFor(seekMs, duration)
        // The picture is the keyframe nearest the moment asked for; the time kept is that keyframe's, so the result's time is its own.
        val at = if (!timed) asked else com.craneai.findr.extract.KeyframeClock.open(context, uri)?.use { it.nearestMs(asked) } ?: asked
        return r.getScaledFrameAtTime(at * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, FRAME_SIDE, FRAME_SIDE) to at
      } finally {
        r.release()
      }
    }

    /** The moment to take a video's frame: [seekMs], at most a quarter of the [durationMs] (unknown length: the start). Pure. */
    fun seekMsFor(seekMs: Long, durationMs: Long): Long = if (durationMs <= 0 || seekMs <= 0) 0L else minOf(seekMs, durationMs / 4)

    /**
     * The pass's one thread. Nice 5 (not Android's "background" level, which would also confine it to the slow cores)
     * gives EmbeddingGemma's CPU threads the larger share when they compete; the image tower's pool thread is created
     * from this thread and inherits it.
     */
    internal val dispatcher = Executors.newSingleThreadExecutor { r ->
      Thread({ Process.setThreadPriority(5); r.run() }, "findr-quicklook").apply { isDaemon = true }
    }.asCoroutineDispatcher()
  }
}
