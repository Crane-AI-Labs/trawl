package com.craneai.findr.engine

import android.content.Context
import com.craneai.findr.core.FindrLog as Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.craneai.findr.FindrApp

/**
 * Runs when MediaStore reports new or changed files (content-URI triggers, armed by [FindrEngine.watchForChanges]).
 * Cheap on purpose, so it may run on battery: crawl (names), then the words pass for what arrived (document text,
 * image text), then hand the slow part to [IndexWorker], which waits for the charger. Found on the A06: before this,
 * a photo taken in the evening stayed unsearchable until the app was reopened or the 6-hour job ran.
 */
class ChangeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
  override suspend fun doWork(): Result {
    val engine = (applicationContext as FindrApp).engine as? FindrEngine ?: return Result.success()
    try {
      if (!engine.hasAllFilesAccess()) return Result.success()
      if (engine.paused) {   // paused: a file deleted meanwhile still leaves the index (the crawl only removes while paused)
        engine.crawl(forceFull = false)
        return Result.success()
      }
      // The index is not open: nothing to crawl into. The engine crawls once it opens, and the hourly heartbeat is the net under that.
      if (engine.awaitStore() == null) return Result.success()
      val started = System.nanoTime()
      val changed = engine.crawl(forceFull = false)
      // Polite mode: someone is using another app, so no OCR now. The names are in (the crawl); the words come when the screen goes
      // off or Findr opens (the in-app pass and IndexWorker both start with the words pass).
      val words = if (engine.polite()) 0 else engine.wordsPass(FileProcessor(applicationContext, engine.embedder), limit = WORDS_LIMIT, stop = { isStopped })
      // The slow pass, under its charging constraint; only when this run found something (a MediaStore notification with nothing
      // new, e.g. a thumbnail being made, used to enqueue the index work again every time: the first-run storm).
      if (Edition.GO) {   // Trawl Go has no slow pass: only a burst bigger than this run's limit needs the main worker to finish it
        if (words >= WORDS_LIMIT) engine.schedule()
      } else if (changed || words > 0) engine.schedule()
      else engine.followUpNewFiles()   // D12: new files the last (capped) run left, picked up in the next window Android gives
      Log.i("FindrChange", "media changed: crawl + words for $words files in ${(System.nanoTime() - started) / 1_000_000} ms")
      return Result.success()
    } finally {
      // Triggers fire once: arm the next one. The hourly heartbeat run isn't a trigger, so it leaves the watcher alone.
      if (triggeredContentUris.isNotEmpty() || triggeredContentAuthorities.isNotEmpty()) engine.watchForChanges(fromRunningTrigger = true)
    }
  }

  companion object {
    const val NAME = "findr-media-changes"
    const val HEARTBEAT = "findr-light-crawl"
    const val WORDS_LIMIT = 300   // a burst (a WhatsApp backup restore) is finished by the main worker
  }
}
