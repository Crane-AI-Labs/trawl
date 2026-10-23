package com.craneai.findr.engine

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.craneai.findr.FindrApp
import com.craneai.findr.R
import com.craneai.findr.core.FileKind
import com.craneai.findr.core.FindrLog as Log
import com.craneai.findr.core.FindrLog.redact
import com.craneai.findr.extract.DocumentTextExtractor
import com.craneai.findr.core.PauseReason
import com.craneai.findr.index.FileState
import com.craneai.findr.index.IndexStore
import com.craneai.findr.index.Modality
import com.craneai.findr.index.PendingFile
import com.craneai.findr.index.Rules
import com.craneai.findr.index.Segment
import com.craneai.findr.index.SegmentKind
import com.craneai.findr.ui.common.appString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "FindrWorker"

/**
 * Understands files in the background: one file at a time, saved in one transaction each, so it can be stopped at
 * any moment (Android 15's 6-hour data-sync limit, the user unplugging, the system) and pick up where it left off.
 *
 * Paced by heat, not by Android's thermal status: the A06 drops its clocks from 2.0 to 0.85 GHz without ever
 * reporting throttling (the build notes, M1), so the battery temperature and measured speed decide when to rest.
 */
class IndexWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

  /** Expedited work needs this before Android 12; on 12+ the worker calls setForeground itself once it is running. */
  override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(R.string.notif_getting_ready)

  override suspend fun doWork(): Result {
    val engine = (applicationContext as FindrApp).engine as? FindrEngine ?: return Result.success()
    if (engine.paused || !engine.hasAllFilesAccess()) return Result.success()
    // The index is not open (busy, or the disk is full): the engine keeps trying and redoes what it missed when the index opens. This run
    // does nothing, takes no lock and no foreground service, and asks WorkManager for another later; it never crashes.
    if (engine.awaitStore() == null) {
      Log.i(TAG, "the index is not open yet: this run is skipped and asked again later")
      return Result.retry()
    }
    // Another run is already at it. A resume request comes right after a run the system stopped, which may still be winding down
    // (a file in the middle of a model call), so it waits for that run's turn instead of giving up.
    val resuming = RESUME in tags
    val locked = if (resuming) withTimeoutOrNull(RESUME_LOCK_WAIT_MS) { engine.workerLock.lock(); true } ?: false else engine.workerLock.tryLock()
    if (!locked) {
      if (resuming) Log.i(TAG, "resume: the earlier run is still winding down; the next scheduled run picks up")
      return Result.success()
    }
    if (resuming) Log.i(TAG, "resume: running as plain work")
    // The system's stop reaches this guard the moment it cancels the worker, not when the (possibly mid-file) run unwinds. That
    // matters on phones that freeze a background app a second after its service ends (Transsion Hiber): the resume request has to
    // be queued before that, or nothing ever runs again (the research notes).
    val guard = CoroutineScope(currentCoroutineContext()).launch(Dispatchers.IO) {
      try { awaitCancellation() } finally { if (isStopped) resumeIfForegroundLimit(engine) }
    }
    // Does this phone let us work with the screen off? A one-second tick, kept while the run lives; a late tick is a freeze.
    val meter = ScreenOffMeter()
    val screenOffTicker = CoroutineScope(currentCoroutineContext()).launch(Dispatchers.IO) {
      var ticks = 0
      try {
        while (true) {
          meter.tick(android.os.SystemClock.elapsedRealtime(), engine.screenOff())
          if (++ticks % SCREEN_OFF_SAVE_TICKS == 0) saveScreenOff(engine, meter)
          delay(ScreenOffMeter.TICK_MS)
        }
      } finally {
        saveScreenOff(engine, meter)
      }
    }
    try {
      return run(engine)
    } finally {
      screenOffTicker.cancel()
      guard.cancel()
      engine.workerLock.unlock()
    }
  }

  /**
   * Between two files of the meaning pass: files that landed meanwhile get their words (and an incremental crawl finds them first,
   * in case ChangeWorker is late). Runs in this coroutine, so it is the one writer and the meaning pass is only paused for it
   * ([MidRunWords]). Skipped while the phone is polite, hot or the run is stopping: those rules hold the words pass back too.
   */
  private suspend fun wordsForNewFiles(engine: FindrEngine, processor: FileProcessor, watch: MidRunWords) {
    if (isStopped || engine.paused || engine.polite() || batteryTemperature() >= Tuning.current.hotStopC) return
    val n = watch.between(
      crawl = { engine.crawl(forceFull = false) },
      wordsWaiting = { engine.wordsWaiting() },
      // wait = false: ChangeWorker's or the in-app pass is already at it (and takes these files); look again in a few seconds.
      words = { limit -> engine.wordsPass(processor, limit = limit, stop = { isStopped || engine.polite() }, wait = false) },
    )
    if (n > 0) Log.i(TAG, "mid-run words: $n files that landed during the run are searchable by their words")
  }

  /** Adds what the meter has seen to the totals and says where the totals stand (only when this stretch had screen-off time). */
  private fun saveScreenOff(engine: FindrEngine, meter: ScreenOffMeter) {
    val part = meter.drain()
    if (part.observedMs <= 0) return
    engine.addScreenOff(part)
    engine.screenOffRecord().let { Log.i(TAG, "screenoff: watched ${it.observedMs / 1000} s, ran ${it.ranMs / 1000} s (${(it.share * 100).toInt()}%): ${it.verdict}") }
  }

  /**
   * Trawl Go: names, then the words of documents and pictures, and that is all of it. No model to wait for or load, no quick look,
   * privacy or speech passes, no embedding loop, so no charger needed: reading text is cheap. The words pass saves each file as
   * understood the moment its words are in, and a file the pass cannot read finishes by its name (FindrEngine.wordsPass).
   */
  private suspend fun runGo(engine: FindrEngine): Result {
    startForeground(R.string.notif_getting_ready)
    engine.workerState = engine.workerState.copy(running = true, pauseReason = null, lastError = null)
    try {
      engine.crawl(forceFull = false)
      retryFailuresAfterUpdate(engine)
      rereadDocumentsAfterExtractorChange(engine)
      val processor = FileProcessor(applicationContext, engine.embedder,
        pauseForUser = { maxMs, gpu ->
          val until = System.currentTimeMillis() + maxMs
          while ((if (gpu) engine.gpuShouldWait else engine.userInteracting) && !isStopped && System.currentTimeMillis() < until) delay(250)
        }, userBusy = { gpu -> if (gpu) engine.gpuShouldWait else engine.userInteracting })
      // Reading the words in photos is the heavy part of Go, so a low battery or a hot phone holds it (the meter says why) and it is asked again later.
      val held = engine.policy().heldBy
      if (held == PauseReason.BATTERY_LOW || held == PauseReason.TOO_HOT) {
        Log.i(TAG, "go: held by $held (battery ${engine.batteryPercent()}%, thermal ${engine.thermalStatus()})")
        engine.scheduleRetryLater()
        return Result.success()
      }
      engine.wordsPass(processor, stop = { isStopped })
      engine.store.trimWal(WAL_TRIM_BYTES)
      return Result.success()
    } finally {
      if (isStopped) {
        runCatching { engine.store.setMeta("last_worker_stop", "stopped by the system (reason $stopReason) at ${java.time.Instant.now()}") }
      }
      engine.workerState = engine.workerState.copy(running = false, currentFile = null)
    }
  }

  /**
   * The full edition with no embedding model on the phone: the Go run (names, then the words of documents and pictures, no charger
   * needed: reading text is cheap) plus the quick-look picture pass when its pack is installed. Files finish as SEARCHABLE, not
   * understood (FindrEngine.wordsPass), so the embedding loop picks every one of them up once a model is installed. Nothing here
   * touches the embedder: it has no file to load.
   */
  private suspend fun runWithoutModel(engine: FindrEngine): Result {
    startForeground(R.string.notif_getting_ready)
    engine.workerState = engine.workerState.copy(running = true, pauseReason = null, lastError = null)
    val quickScope = CoroutineScope(currentCoroutineContext() + SupervisorJob(currentCoroutineContext()[Job]))
    var quickJob: Job? = null
    try {
      engine.crawl(forceFull = false)
      retryFailuresAfterUpdate(engine)
      rereadDocumentsAfterExtractorChange(engine)
      val processor = FileProcessor(applicationContext, engine.embedder,
        pauseForUser = { maxMs, gpu ->
          val until = System.currentTimeMillis() + maxMs
          while (userBusy(engine, gpu) && !isStopped && System.currentTimeMillis() < until) delay(250)
        }, userBusy = { gpu -> userBusy(engine, gpu) })
      val held = engine.policy().heldBy
      if (held == PauseReason.BATTERY_LOW || held == PauseReason.TOO_HOT) {
        Log.i(TAG, "no model: held by $held (battery ${engine.batteryPercent()}%, thermal ${engine.thermalStatus()})")
        engine.scheduleRetryLater()
        return Result.success()
      }
      if (engine.quickLookShouldRun()) quickJob = quickScope.launch {
        try {
          QuickLookPass(applicationContext, engine) { isStopped }.run()
        } catch (e: CancellationException) {
          throw e
        } catch (t: Throwable) {
          Log.w(TAG, "quick look failed", t)
        }
      }
      Log.i(TAG, "no model: reading names and words" + if (quickJob != null) " and pictures (quick look)" else "")
      engine.wordsPass(processor, stop = { isStopped }, holdWhilePolite = true,
        onPoliteHold = { startForeground(R.string.notif_waiting_polite) })
      if (!isStopped && engine.polite()) {
        handOff(engine, "the words pass waited out a polite phone")
        return Result.success()
      }
      return Result.success()
    } finally {
      withContext(NonCancellable) { quickJob?.let { if (isStopped) it.cancelAndJoin() else it.join() } }
      engine.workerState = engine.workerState.copy(running = false, currentFile = null)
      withContext(NonCancellable) { engine.store.trimWal() }
      engine.refreshStatus()
    }
  }

  /** Set once the system has refused this run a foreground service; the run then goes on as plain work. */
  private var foregroundDenied = false

  /**
   * The foreground service itself, if the system lets us have one. It can refuse (the 6-hour data-sync budget is spent and the app is
   * not in front: ForegroundServiceStartNotAllowedException), and then the run goes on as plain work. Asked again only by a later
   * run: after a refusal the per-file notification updates are skipped too.
   */
  private suspend fun startForeground(@StringRes line: Int) {
    if (foregroundDenied) return
    try {
      val t0 = android.os.SystemClock.elapsedRealtime()
      setForeground(foregroundInfo(line))
      val took = android.os.SystemClock.elapsedRealtime() - t0
      if (took >= 1_000) Log.i(TAG, "the foreground notice took $took ms to post")   // a slow one here delays a polite wait's own clock
    } catch (e: CancellationException) {
      throw e
    } catch (t: Throwable) {
      foregroundDenied = true
      Log.w(TAG, "no foreground service; running as plain work", t)   // the data-sync budget is spent, or started from the background on 12+
    }
  }

  /** Android 15's data-sync limit ended this run: nothing else starts it again until the app is opened, so ask for a plain rerun (RunHandoff). */
  private suspend fun resumeIfForegroundLimit(engine: FindrEngine) {
    if (!RunHandoff.resumeAfterStop(stopReason)) return
    Log.i(TAG, "the foreground-service limit ended this run: asking for a plain rerun, expedited")
    withContext(NonCancellable) { engine.scheduleResume(0) }
  }

  /**
   * Polite mode ended the wait for good: let go of the foreground service by ending the run, and ask to be started again when the
   * screen goes off (the process watches for it) or Trawl opens, with a timer behind that. Only when something is left to do.
   */
  private suspend fun handOff(engine: FindrEngine, why: String) {
    val waiting = engine.workWaiting()
    Log.i(TAG, "polite: ending this run ($why); " + if (waiting) "it resumes when the screen goes off or Trawl opens" else "nothing is waiting")
    if (waiting) withContext(NonCancellable) { engine.scheduleResume(RunHandoff.RESUME_DELAY_MIN) }
  }

  private suspend fun run(engine: FindrEngine): Result {
    if (Edition.GO) return runGo(engine)
    // Started while the person is in another app: nothing heavy may run, so do not become a foreground service (it would spend the
    // 6-hour data-sync budget on waiting). Ask to be started when the screen goes off or Trawl opens.
    if (RunHandoff.startsWithoutService(engine.polite())) {
      handOff(engine, "started while polite")
      return Result.success()
    }
    // The model, then the default packs (quick look, speech), from the bundle or the separate files in Downloads. All of it is a
    // no-op once in place or declined; the re-read below picks up what was added.
    val modelInPlace = engine.modelInstaller().installDefaultsFromDownloads()
    // No model yet (the person has not fetched one, or chose the smallest start): names, document text and the words in pictures
    // need no model, so they are read now, and the picture pass runs if its pack is in. The model, when it arrives, finds those
    // files already searchable and only adds meaning (lean first download, the research notes).
    if (engine.embedder.modelFile == null && !modelInPlace) return runWithoutModel(engine)
    startForeground(R.string.notif_getting_ready)
    engine.workerState = engine.workerState.copy(running = true, pauseReason = null, lastError = null)
    // The quick-look pass (D2) runs beside the words pass and the embedding loop, on its own thread (QuickLookPass).
    val quickScope = CoroutineScope(currentCoroutineContext() + SupervisorJob(currentCoroutineContext()[Job]))
    var quickJob: Job? = null
    var quickEndedAt = 0L
    fun startQuickLook() {
      if (quickJob?.isActive == true || System.currentTimeMillis() - quickEndedAt < QUICK_RECHECK_MS || !engine.quickLookShouldRun()) return
      quickJob = quickScope.launch {
        try {
          QuickLookPass(applicationContext, engine) { isStopped }.run()
        } catch (e: CancellationException) {
          throw e
        } catch (t: Throwable) {
          Log.w(TAG, "quick look failed", t)
          com.craneai.findr.diag.Diag.handled(com.craneai.findr.diag.HandledKind.WORKER, t)
        } finally {
          quickEndedAt = System.currentTimeMillis()
        }
      }
    }
    // The privacy-score pass (PrivateLook) runs the same way, beside the quick-look pass.
    var privacyJob: Job? = null
    var privacyEndedAt = 0L
    fun startPrivacy() {
      if (privacyJob?.isActive == true || System.currentTimeMillis() - privacyEndedAt < QUICK_RECHECK_MS || !engine.privacyShouldRun()) return
      privacyJob = quickScope.launch {
        try {
          PrivacyPass(applicationContext, engine) { isStopped }.run()
        } catch (e: CancellationException) {
          throw e
        } catch (t: Throwable) {
          Log.w(TAG, "privacy scores failed", t)
        } finally {
          privacyEndedAt = System.currentTimeMillis()
        }
      }
    }
    try {
      // After the rules for kinds changed, one full crawl so files already known get their new kind (Rules.VERSION).
      val reclassify = engine.store.meta(RULES_KEY) != Rules.VERSION.toString() && engine.hasAllFilesAccess() && !engine.paused
      engine.crawl(forceFull = reclassify)
      if (reclassify) engine.store.setMeta(RULES_KEY, Rules.VERSION.toString())
      retryFailuresAfterUpdate(engine)
      engine.store.recoverInflight()?.let {
        Log.w(TAG, "the process died twice inside step ${it.step} of file #${it.fileId}: " +
          if (it.skippedWindow) "that window is left out, the rest is read" else "file marked failed (too large for this phone's memory)")
      }
      rereadDocumentsAfterExtractorChange(engine)
      if (engine.settings.value.speechToText && engine.asr.available()) {
        val n = engine.store.retryMissingSpeech()
        if (n > 0) Log.i(TAG, "speech models are in: $n files get their soundtrack read")
      }
      judgeSpeechAgainAfterGateChange(engine)
      readSoundtracksWhenSpeechTurnsOn(engine)
      readSoundsWhenDeeperTurnsOn(engine)
      val lite = !engine.embedder.imagesByEmbeddingGemma
      // The lite profile keeps pictures' quick-look vectors for good: a different quick-look model drops them all first (the
      // image fingerprint below then names that model too, so every file is read again).
      if (lite && engine.store.ensureQuickFingerprint(QuickLookPack.FINGERPRINT)) engine.quickVectors.clear()
      val fingerprint = engine.embedder.imageFingerprint!!
      // Stale carry-over only between EmbeddingGemma image spaces: a change into, out of or within the lite profile reads
      // every file again (lite pictures hold no EmbeddingGemma image vectors to keep, so nothing would be queued otherwise).
      val keepStale = Embedder.keepStaleOnChange(engine.store.meta(com.craneai.findr.index.Schema.Meta.MODEL_FINGERPRINT), fingerprint)
      if (engine.store.ensureModelFingerprint(fingerprint, keepStale = keepStale)) {
        if (keepStale) {
          // The old image, frame and audio vectors stay searchable as stale until each file is read again, so there is no gap.
          val n = engine.vectors.markStale(setOf(Modality.IMAGE, Modality.AUDIO))
          Log.i(TAG, "model changed: $n vectors kept as stale until their files are read again")
        } else {
          engine.vectors.clear()   // a different model's vectors aren't comparable: everything is understood again
        }
      }
      if (lite && engine.liteQuickImages.ready) {
        val n = engine.store.retryMissingQuick()
        if (n > 0) Log.i(TAG, "the quick-look model is in: $n pictures and videos get their picture vectors")
      }
      // A text-only model change (a language-tuned backbone that keeps the image space, D4): documents are read again and
      // recordings' and videos' text is re-embedded below; photos and frames keep their vectors.
      engine.store.ensureTextFingerprint(engine.embedder.textFingerprint!!).let { (docs, segments) ->
        if (docs + segments > 0) Log.i(TAG, "text model changed: $docs documents queued, $segments transcript and sound vectors to redo")
      }
      val tight = engine.memoryTight().also { engine.runTight = it }   // 4 GB-class phones: speech and audio encoder are loaded only while a recording or video needs them
      val processor = FileProcessor(applicationContext, engine.embedder, asr = engine.asr, speechActive = engine.speechActive,
        quick = engine.liteQuickImages.takeIf { lite }, oneHeavyModelAtATime = { tight },
        storedChunks = { id, from, limit -> engine.store.storedChunks(id, from, limit) to engine.store.storedChunkCount(id) },
        pauseForUser = { maxMs, gpu ->
        val until = System.currentTimeMillis() + maxMs   // inside a file too: one video holds the CPU for seconds (the research notes)
        while (userBusy(engine, gpu) && !isStopped && System.currentTimeMillis() < until) delay(250)
      }, userBusy = { gpu -> userBusy(engine, gpu) })
      startQuickLook()
      startPrivacy()
      engine.wordsPass(processor, stop = { isStopped }, holdWhilePolite = true,   // gives up after a polite wait (RunHandoff.HAND_OFF_AFTER_MS)
        onPoliteHold = { startForeground(R.string.notif_waiting_polite) })
      if (!isStopped && engine.polite()) {
        handOff(engine, "the words pass waited out a polite phone")
        return Result.success()
      }
      var newFiles = 0
      var newEncodeMs = 0L
      var capNoted = false
      val mediaShare = MediaShare(Tuning.current.mediaShare)
      var lastTierLine = ""
      val midRun = MidRunWords(clock = { android.os.SystemClock.elapsedRealtime() })
      while (!isStopped) {
        startPrivacy()
        startQuickLook()   // new photos since it finished, or quick look just turned on: back within a minute
        val settings = engine.settings.value
        if (engine.paused) return Result.success()
        wordsForNewFiles(engine, processor, midRun)   // a file that landed since the last look is searchable by its words now
        val decision = engine.policy()
        engine.embeddingNow = false
        // The mode the app believes it is in (read by the learn-control study): once per change, not once per loop.
        val tierLine = "tier: ${decision.tier} speed=${settings.learningSpeed.key} charging=${engine.isCharging()} battery=${engine.batteryPercent()}% " +
          "thermal=${engine.thermalStatus()} polite=${engine.polite()}"
        if (tierLine != lastTierLine) { lastTierLine = tierLine; Log.i(TAG, tierLine) }
        if (decision.tier == IndexPolicy.Tier.WORDS_ONLY) {
          // Names and words are in. Everything heavier waits: for the charger (the charging-constrained requests, the hourly
          // light crawl and opening the app start the next run; not retry(): each retry doubled WorkManager's backoff, up to
          // 5 h, and a kept request then sat out that wait after the phone was plugged back in), or for the battery or the
          // phone to recover (looked at again in a few minutes).
          Log.i(TAG, "words only: ${decision.heldBy} (battery ${engine.batteryPercent()}%, thermal ${engine.thermalStatus()})")
          if (decision.heldBy == PauseReason.BATTERY_LOW || decision.heldBy == PauseReason.TOO_HOT) engine.scheduleRetryLater()
          return Result.success()
        }
        val temp = batteryTemperature()
        if (temp >= Tuning.current.hotStopC) {
          engine.workerState = engine.workerState.copy(pauseReason = PauseReason.TOO_HOT, currentFile = null)
          Log.i(TAG, "battery at $temp °C: cooling down")
          delay(Tuning.current.coolDownMs)
          continue
        }
        engine.workerState = engine.workerState.copy(pauseReason = null)
        if (engine.polite()) {   // the person is in another app: nothing heavy runs until the screen goes off or Trawl opens
          if (waitWhilePolite(engine)) {   // a long wait: leave, and with the run the foreground service
            handOff(engine, "polite for ${RunHandoff.HAND_OFF_AFTER_MS / 1000} s")
            return Result.success()
          }
          continue
        }
        if (decision.tier == IndexPolicy.Tier.LIGHT) {
          // On battery: full quality only for files that arrived since the backlog was counted, a few per run. The photo that
          // came at noon is searchable at full quality at noon; the gallery's backlog waits for the charger (quick look has it).
          val left = Tuning.current.newFilesPerRun - newFiles
          if (decision.newFilesFullQuality && left > 0 && newEncodeMs < Tuning.current.newEncodeMsPerRun) {
            val fresh = engine.nextNewFiles(minOf(4, left))
            if (fresh.isNotEmpty()) {
              engine.embeddingNow = true
              for ((i, p) in fresh.withIndex()) {
                if (isStopped || engine.polite() || engine.policy().let { it.tier != IndexPolicy.Tier.LIGHT || !it.newFilesFullQuality }) break
                while (engine.userInteracting && !isStopped) delay(250)
                val t0 = System.nanoTime()
                understand(engine, processor, p, settings.indexAudio, settings.speechToText)
                newEncodeMs += (System.nanoTime() - t0) / 1_000_000
                newFiles++
                if (tight) unloadSoundModels(engine, p, fresh.getOrNull(i + 1), endOfMediaTurn = i == fresh.lastIndex)
                rest(engine)
                wordsForNewFiles(engine, processor, midRun)
              }
              engine.store.trimWal(WAL_TRIM_BYTES)
              continue
            }
          } else if (!capNoted && decision.newFilesFullQuality) {
            capNoted = true
            Log.i(TAG, "on battery: $newFiles new files in ${newEncodeMs / 1000} s, the per-run cap; the rest wait for the next run or the charger")
          }
          // Nothing (more) to read at full quality on battery. While quick look is still at the backlog, stay and watch: a
          // plug-in then moves this same run on to the bulk (a second request would find the worker busy and give up).
          if (quickJob?.isActive == true) {
            delay(LIGHT_POLL_MS)
            continue
          }
          break
        }
        // Bulk: the whole backlog, on the charger (or on battery when the person asked for full indexing there).
        engine.embeddingNow = true   // from here the quick-look pass shares the CPU with EmbeddingGemma: it works part time
        if (redoText(engine)) continue
        val kinds = engine.enabledKinds()
        // Fair turns ([MediaShare]): photos and documents are most of the backlog, but videos and recordings get a share of the busy
        // time, so they are not all waiting behind the last photo. A share of 0 is the old order (the one queue, kind by kind).
        val mediaKinds = kinds.intersect(MediaShare.MEDIA_KINDS)
        val otherKinds = kinds - MediaShare.MEDIA_KINDS
        val media = if (mediaKinds.isEmpty() || mediaShare.noShare) emptyList()
        else mediaShare.mediaOrder(mediaKinds).firstNotNullOfOrNull { k -> engine.store.nextPending(if (tight) TIGHT_MEDIA_BURST else 1, setOf(k)).takeIf { it.isNotEmpty() } } ?: emptyList()
        val other = if (mediaShare.noShare) engine.store.nextPending(8, kinds) else if (otherKinds.isEmpty()) emptyList() else engine.store.nextPending(8, otherKinds)
        val mediaTurn = media.isNotEmpty() && mediaShare.mediaTurn(true, other.isNotEmpty())
        val batch = (if (mediaTurn) media else other).ifEmpty { engine.store.nextPartial(4, kinds) }
        if (batch.isEmpty()) break
        Log.i(TAG, "pick: ${batch.size} ${kindCounts(batch.map { it.file.kind })} left ${kindCounts(null, engine)}")   // log-safe: counts per kind, no names
        for ((i, p) in batch.withIndex()) {
          if (isStopped || engine.polite()) break   // polite: the loop above waits it out
          while (engine.userInteracting && !isStopped) delay(250)   // the user is scrolling: let the UI have the CPU
          val t0 = System.nanoTime()
          understand(engine, processor, p, settings.indexAudio, settings.speechToText)
          mediaShare.record(p.file.kind in MediaShare.MEDIA_KINDS, (System.nanoTime() - t0) / 1_000_000, p.file.kind)
          if (tight) unloadSoundModels(engine, p, batch.getOrNull(i + 1), endOfMediaTurn = mediaTurn && i == batch.lastIndex)
          rest(engine)
          wordsForNewFiles(engine, processor, midRun)   // between files, not between batches: a batch of photos takes a minute or more
          // A video or recording is owed its turn: end this batch of photos early rather than after all eight.
          if (!mediaTurn && media.isNotEmpty() && mediaShare.mediaTurn(true, true)) break
        }
        engine.store.trimWal(WAL_TRIM_BYTES)
      }
      if (mediaShare.achieved > 0.0) Log.i(TAG, "bulk turns: videos and recordings took ${(mediaShare.achieved * 100).toInt()}% of the busy time (share ${Tuning.current.mediaShare})")
      // Ended at the cap with new files still waiting, on battery: ask for the next batch (D12), not only the charger.
      if (!isStopped) engine.followUpNewFiles()
      return Result.success()
    } catch (e: EngineUnhealthyException) {
      // The embedding engine failed its self-test twice: no file is marked failed (it is not their fault); look again later.
      engine.workerState = engine.workerState.copy(lastError = "Search is repairing itself")
      Log.w(TAG, "the embedding engine is unusable: ending this run, trying again later")
      engine.scheduleRetryLater()
      return Result.success()
    } finally {
      // Stopped: the quick pass stops too. Otherwise it finishes its batch (it checks charging and pause itself), at full speed.
      engine.embeddingNow = false
      withContext(NonCancellable) {
        quickJob?.let { if (isStopped) it.cancelAndJoin() else it.join() }
        privacyJob?.let { if (isStopped) it.cancelAndJoin() else it.join() }
      }
      if (isStopped) {
        // Why the system stopped us (quota, FGS timeout, constraints…): recorded so overnight runs can be diagnosed.
        val why = "stopped by the system (reason $stopReason) at ${java.time.Instant.now()}"
        Log.i(TAG, why)
        runCatching { engine.store.setMeta("last_worker_stop", why) }
      }
      engine.workerState = engine.workerState.copy(running = false, currentFile = null)
      withContext(NonCancellable) { engine.store.trimWal() }   // the run's last writes are in the log: fold them in and give the space back
      if (engine.keepModelsResident()) {
        Log.i(TAG, "run over: memory is roomy, models stay loaded for the next search or run")
      } else {
        engine.releaseModels()   // give the memory back; the next search reloads text-only
        Log.i(TAG, "run over: memory is tight, models released")
      }
      engine.refreshStatus()
    }
  }

  /** Polite mode's heavy-work gate for waits inside a file ([UserYield]): the user is scrolling Trawl's own lists, or is in another app. */
  /** "[photo=a video=b audio=c doc=d]": what a batch holds (kinds given), or what is still waiting (kinds null). Counts only, no names. */
  private fun kindCounts(kinds: List<FileKind>?, engine: FindrEngine? = null): String {
    fun n(group: Set<FileKind>) = if (kinds != null) kinds.count { it in group } else engine!!.store.waiting(group)
    return "[photo=${n(setOf(FileKind.IMAGE, FileKind.SCREENSHOT))} video=${n(setOf(FileKind.VIDEO))} audio=${n(setOf(FileKind.AUDIO))} " +
      "doc=${n(setOf(FileKind.DOCUMENT, FileKind.PDF))}]"
  }

  private fun userBusy(engine: FindrEngine, gpu: Boolean) = (if (gpu) engine.gpuShouldWait else engine.userInteracting) || engine.polite()

  /**
   * Polite mode ([PolitePolicy]): waits here, between files, while the screen is on and Trawl is not in front. After
   * [PolitePolicy.RELEASE_AFTER_MS] the models are given back when the phone is short of memory (the 4 GB phones need that memory
   * for the app in front; a phone with room keeps them, [PolitePolicy.releaseDue]) or at once when the system trims memory. Released
   * models reload by themselves when work resumes, which is the moment the screen goes off or Trawl opens.
   *
   * It does not wait for ever: a foreground service that only waits still spends Android 15's 6-hour data-sync budget. After
   * [RunHandoff.HAND_OFF_AFTER_MS] it returns true and the run ends (the caller hands off, [handOff]). While it waits, the
   * notification says so instead of keeping the last "Reading a photo". Returns false when polite ended by itself.
   */
  private suspend fun waitWhilePolite(engine: FindrEngine): Boolean {
    engine.embeddingNow = false
    engine.workerState = engine.workerState.copy(currentFile = null)
    val since = android.os.SystemClock.elapsedRealtime()
    var released = false
    var tight = false      // the memory rule, read once when the settle time is up (a phone with room keeps its models)
    var judged = false
    Log.i(TAG, "polite: the screen is on and Trawl is not in front, so heavy work waits")
    startForeground(R.string.notif_waiting_polite)   // asked again each wait: only the text changes while the service runs
    while (!isStopped && !engine.paused && engine.polite()) {
      val waited = android.os.SystemClock.elapsedRealtime() - since
      if (!judged && waited >= PolitePolicy.RELEASE_AFTER_MS) {
        judged = true
        tight = engine.memoryTight() && !Tuning.current.keepModelsWhenPolite
        if (!tight) Log.i(TAG, "polite for ${waited / 1000} s: " + (if (Tuning.current.keepModelsWhenPolite) "this profile keeps the models loaded" else "memory is roomy, models stay loaded"))
      }
      val pressure = engine.memoryPressureSince(since)
      if (PolitePolicy.releaseDue(waited, released, tight, pressure)) {
        released = true
        engine.releaseModels()
        Log.i(TAG, "polite for ${waited / 1000} s: " + (if (pressure) "the system is short of memory" else "memory is tight") + ", models released")
      }
      if (RunHandoff.handOffDue(waited)) return true
      delay(PolitePolicy.POLL_MS)
    }
    Log.i(TAG, "polite is over after ${(android.os.SystemClock.elapsedRealtime() - since) / 1000} s")
    return false
  }

  /**
   * Speech to text applies to files read from now on; when it is switched on, videos and audio already read without it
   * are read again once (their frames stay searchable meanwhile). Re-reading frames too is the simple, one-off cost.
   */
  private fun readSoundtracksWhenSpeechTurnsOn(engine: FindrEngine) {
    val on = engine.settings.value.speechToText && engine.asr.available()
    val was = engine.store.meta(SPEECH_KEY) == "on"
    if (on && !was) {
      val n = engine.store.requeueRead(listOf(FileKind.VIDEO, FileKind.AUDIO), detailLacks = "speech")
      if (n > 0) Log.i(TAG, "speech to text is on: reading $n videos and recordings again for their words")
    }
    if (on != was) engine.store.setMeta(SPEECH_KEY, if (on) "on" else "off")
  }

  /**
   * When the speech language gate changes (SpeechLanguageGate.VERSION), videos and recordings whose speech the old rule judged are read again once,
   * so the junk the old rule let through is dropped and the English parts of mixed-language notes are kept. Waits until speech can run: a
   * file read without speech has nothing to re-judge, and the marker is only set once the new rule can actually be applied.
   */
  private fun judgeSpeechAgainAfterGateChange(engine: FindrEngine) {
    if (!(engine.settings.value.speechToText && engine.asr.available())) return
    val version = SpeechLanguageGate.VERSION.toString()
    if (engine.store.meta(GATE_KEY) == version) return
    val n = engine.store.requeueSpeechJudgedAgain()
    engine.store.setMeta(GATE_KEY, version)
    if (n > 0) Log.i(TAG, "speech language gate v$version: judging the speech of $n videos and recordings again")
  }

  /** "Deeper sound search" switched on: the recordings that were left with only their name (music, podcasts) are read now. */
  private fun readSoundsWhenDeeperTurnsOn(engine: FindrEngine) {
    val on = engine.settings.value.indexAudio
    val was = engine.store.meta(DEEPER_KEY) == "on"
    if (on && !was) {
      val n = engine.store.retryAudioOff()
      if (n > 0) Log.i(TAG, "deeper sound search is on: $n long recordings get read")
    }
    if (on != was) engine.store.setMeta(DEEPER_KEY, if (on) "on" else "off")
  }

  /** When the document reader changes, documents already read are read again (their words and vectors stay meanwhile). */
  private fun rereadDocumentsAfterExtractorChange(engine: FindrEngine) {
    val version = DocumentTextExtractor.VERSION.toString()
    if (engine.store.meta(EXTRACTOR_KEY) == version) return
    val n = engine.store.requeueRead(listOf(FileKind.DOCUMENT, FileKind.PDF), DocumentTextExtractor.VERSION_AFFECTS, mimePrefix = "text/")
    engine.store.setMeta(EXTRACTOR_KEY, version)
    if (n > 0) Log.i(TAG, "document reader v$version: reading $n documents again")
  }

  /** Once per installed build: files that failed under an earlier build get another go (the bug may be fixed). */
  private fun retryFailuresAfterUpdate(engine: FindrEngine) {
    val build = applicationContext.packageManager.getPackageInfo(applicationContext.packageName, 0).lastUpdateTime.toString()
    if (engine.store.meta(RETRY_KEY) == build) return
    val n = engine.store.retryFailed()
    engine.store.setMeta(RETRY_KEY, build)
    if (n > 0) Log.i(TAG, "retrying $n files that failed under an earlier build")
  }

  /**
   * Reads one file. A long video or recording is read a window at a time (see [FileProcessor.MEDIA_WINDOW_MS]): each window is saved as it
   * is done, and the next one follows at once, so a kill loses only the window in hand and the restart goes on from there.
   * Documents keep their old pace: a long one's later steps wait until nothing else is.
   */
  private suspend fun understand(engine: FindrEngine, processor: FileProcessor, found: PendingFile, withAudio: Boolean, withSpeech: Boolean) {
    // MediaStore has no length for some recordings (a voice note it never scanned): ask the file itself, or it would be read as one
    // five-minute step and the rest of a lecture never heard.
    val p = if (found.file.durationMs == null && (found.file.kind == FileKind.AUDIO || found.file.kind == FileKind.VIDEO))
      probedLength(found.file.uri)?.let { found.copy(file = found.file.copy(durationMs = it)) } ?: found else found
    var step = p
    var total = 0.0
    val windowed = FileProcessor.isWindowed(p.file.kind, p.file.durationMs)
    val startedAtZero = p.detail?.let { NEXT_CHUNK.containsMatchIn(it) } != true
    while (true) {
      val done = understandStep(engine, processor, step, withAudio, withSpeech, windowed)
      total += done.seconds
      if (done.next == null || !windowed || isStopped) {
        // A windowed file's pace is the whole file's time, once, not each minute of it as if it were a file.
        if (windowed && done.finished && startedAtZero) Pace.record(p.file.kind, total)
        return
      }
      while (engine.userInteracting && !isStopped) delay(250)
      if (isStopped) return
      // A recording can take hours: the charger coming out, a low battery or a hot phone ends the run between two windows (D12). The
      // windows saved so far stay, and the file goes on from the next one in the next bulk run.
      if (engine.paused || !engine.policy().bulkAllowed) return
      rest(engine)
      step = PendingFile(p.file, FileState.PARTIAL, listOfNotNull("next=${done.next}", done.carry).joinToString(" "))
    }
  }

  /**
   * Tight phones (MemoryBudget): when a video or recording was just read and the next file is a picture or a document, the speech
   * model and the audio encoder go, so a stretch of photos runs with ~0.3-0.5 GB less. A next file of the same kind keeps them:
   * reloading costs 5-10 s and a stretch of videos would pay it every time. The end of a media turn ([endOfMediaTurn]) gives them back
   * too: the bulk loop alternates single media turns with batches of photos, so "the next file is unknown" used to mean "keep" for the
   * whole run, and the speech arena (0.4-0.8 GB, native-mem study) stayed through every stretch of photos.
   */
  private suspend fun unloadSoundModels(engine: FindrEngine, done: PendingFile, next: PendingFile?, endOfMediaTurn: Boolean = false) {
    if (!soundModelsCanLeave(done.file.kind, next?.file?.kind, endOfMediaTurn)) return
    engine.asr.release()
    engine.embedder.dropAudioEncoder()
  }

  private fun probedLength(uri: String): Long? {
    val r = android.media.MediaMetadataRetriever()
    return try {
      r.setDataSource(applicationContext, android.net.Uri.parse(uri))
      r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.takeIf { it > 0 }
    } catch (t: Throwable) { null } finally { runCatching { r.release() } }
  }

  private class Step(val next: Int?, val seconds: Double, val finished: Boolean, val carry: String?)

  private suspend fun understandStep(
    engine: FindrEngine, processor: FileProcessor, p: PendingFile, withAudio: Boolean, withSpeech: Boolean, windowed: Boolean,
  ): Step {
    val file = p.file
    val from = p.detail?.let { NEXT_CHUNK.find(it)?.groupValues?.get(1)?.toIntOrNull() } ?: 0   // "words" → 0
    val carry = p.detail?.let { SKIPPED_WINDOWS.find(it)?.value }   // a window left out after two kills stays noted in the file's detail
    engine.workerState = engine.workerState.copy(currentFile = if (engine.store.isSensitive(file.id)) null else file.name)
    if (!foregroundDenied) runCatching { setForeground(foregroundInfo(notificationLine(file.kind))) }   // never the name: it can show on the lock screen
    val started = System.nanoTime()
    engine.store.markInflight(file.id, from, windowed)   // a process death inside this step is counted at the next start (kill-loop breaker)
    try {
      val result = withTimeout(timeoutFor(file.kind)) { processor.process(file, from, withAudio, withSpeech) }
      val segments = result.pieces.map { it.toSegment(late = from > 0) }
      val state = if (result.nextChunk != null) FileState.PARTIAL else FileState.UNDERSTOOD
      val detail = listOfNotNull(result.detail, result.nextChunk?.let { "next=$it" }, carry).joinToString(" ").ifEmpty { null }
      val tdb = System.nanoTime()
      engine.store.saveSegments(file.id, segments, state, detail, replace = from == 0,
        keepQuick = keepsQuick(segments, engine.embedder.imagesByEmbeddingGemma))
      engine.onFileIndexed(file.id)
      val dbMs = (System.nanoTime() - tdb) / 1_000_000
      val waited = processor.waitedMs / 1e3   // time spent letting the user scroll isn't indexing time
      val seconds = (System.nanoTime() - started) / 1e9 - waited
      if (!windowed) Pace.record(file.kind, seconds)
      Log.i(TAG, "understood ${redact(file.name)} in ${"%.1f".format(seconds)} s: ${segments.size} pieces${detail?.let { ", $it" } ?: ""}" +
        (if (waited > 0) " (waited ${"%.1f".format(waited)} s for the user)" else "") + ", db write $dbMs ms")
      return Step(result.nextChunk, seconds, finished = result.nextChunk == null, carry)
    } catch (e: UserYield.DeferredForUser) {
      Log.i(TAG, "deferred ${redact(file.name)}: the user kept scrolling or is in another app")   // stays pending, picked up when they stop
    } catch (e: TimeoutCancellationException) {
      engine.store.setState(file.id, FileState.FAILED, "took too long")
    } catch (e: CancellationException) {
      throw e   // stopped: the file stays as it was and is picked up next time
    } catch (e: EngineUnhealthyException) {
      throw e   // the model, not the file: it stays pending and the run ends (run() catches this)
    } catch (e: com.craneai.findr.core.FormatNotReadable) {
      // The phone has no decoder for this format (or the file is damaged beyond reading): the policy is to attempt every
      // format and fail safely. It stays searchable by name, is not retried until an app update, and the queue moves on.
      val ext = com.craneai.findr.index.Rules.extensionOf(file.name).ifEmpty { "(none)" }
      val kind = file.kind
      Log.i(TAG, "can't open $kind .$ext: ${e.why}")
      engine.store.setState(file.id, FileState.FAILED, com.craneai.findr.index.IndexStore.CANT_OPEN)
    } catch (t: Throwable) {
      Log.w(TAG, "failed: ${redact(file.relativePath + file.name)}", t)
      com.craneai.findr.diag.Diag.handled(com.craneai.findr.diag.HandledKind.FILE_FAILED, t)
      engine.store.setState(file.id, FileState.FAILED, t.javaClass.simpleName + (t.message?.let { ": $it" } ?: ""))
    } finally {
      runCatching { engine.store.clearInflight() }
    }
    return Step(null, (System.nanoTime() - started) / 1e9, finished = false, carry = carry)
  }

  /** After a text-model change: re-embeds a batch of transcript and sound vectors from their words. False when none are left. */
  private suspend fun redoText(engine: FindrEngine): Boolean {
    val batch = engine.store.textToRedo(REDO_BATCH)
    if (batch.isEmpty()) return false
    engine.workerState = engine.workerState.copy(currentFile = "text of recordings and videos")
    while (engine.userInteracting && !isStopped) delay(250)
    val vectors = batch.map { it.segmentId to engine.embedder.document(it.text) }
    engine.store.replaceTextVectors(vectors, through = batch.last().segmentId)
    batch.map { it.fileId }.distinct().forEach(engine::onFileIndexed)
    rest(engine)
    return true
  }

  /**
   * Between files: a short rest when the battery is warm, so the phone doesn't cook on the charger. Temperature only:
   * per-file times can't signal slowdown (a 10-chunk document is slower than a 2-chunk one, which made the worker
   * rest after nearly every document and say "Cooling down" at 38 °C), and the A06 reports battery heat reliably.
   */
  private suspend fun rest(engine: FindrEngine) {
    val temp = batteryTemperature()
    val ms = when {
      temp >= Tuning.current.hotStopC -> 0L   // the loop's cool-down handles it
      temp >= Tuning.current.warmC -> Tuning.current.warmRestMs
      else -> 0L
    }
    if (ms > 0) {
      engine.workerState = engine.workerState.copy(pauseReason = PauseReason.TOO_HOT)
      delay(ms)
      engine.workerState = engine.workerState.copy(pauseReason = null)
    }
  }

  private fun batteryTemperature(): Double {
    val intent = applicationContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return 0.0
    return intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0
  }

  private fun foregroundInfo(@StringRes text: Int): ForegroundInfo {
    val nm = applicationContext.getSystemService(NotificationManager::class.java)
    if (nm.getNotificationChannel(CHANNEL) == null) {
      nm.createNotificationChannel(NotificationChannel(CHANNEL, applicationContext.getString(R.string.notif_channel_name), NotificationManager.IMPORTANCE_LOW))
    }
    val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
      .setSmallIcon(android.R.drawable.ic_popup_sync)
      .setContentTitle(applicationContext.appString(R.string.notif_title))
      .setContentText(applicationContext.getString(text))
      .setOngoing(true)
      .setSilent(true)
      .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)   // details hidden on a locked screen
      .build()
    return ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
  }

  companion object {
    private const val SCREEN_OFF_SAVE_TICKS = 60

    /** The speech model and audio encoder can leave after a [done] video or recording when the file after it ([next]) needs neither. */
    internal fun soundModelsCanLeave(done: FileKind, next: FileKind?, endOfMediaTurn: Boolean = false): Boolean =
      (done == FileKind.VIDEO || done == FileKind.AUDIO) &&
        if (next == null) endOfMediaTurn else next != FileKind.VIDEO && next != FileKind.AUDIO

    /**
     * On a tight phone a media turn takes up to this many videos or recordings of one kind at once ([MediaShare] still balances by
     * time), so the speech model and audio encoder, which are given back at the end of each turn, load once per burst and not once per file.
     */
    internal const val TIGHT_MEDIA_BURST = 4

    const val NAME = "findr-index"
    const val NOW = "findr-index-now"   // expedited start when the app is open and conditions hold
    const val BULK = "findr-index-bulk"   // the whole backlog: needs the charger (unless full indexing on battery is on)
    const val LATER = "findr-index-later"   // a look again after low battery or heat held the light work back
    const val NEXT = "findr-index-next"   // the next capped batch of new files on battery (IndexPolicy.followUp)
    const val RESUME = "findr-index-resume"   // a run that left its foreground service (polite wait, 6-hour limit) asks to be run again (RunHandoff)
    /** After the system ended a run for the foreground-service limit: how soon the plain rerun is asked for. */
    private const val RESUME_LOCK_WAIT_MS = 120_000L
    private const val RETRY_KEY = "retried_failures_for_build"
    private const val EXTRACTOR_KEY = "document_reader_version"
    private const val GATE_KEY = "speech_language_gate_version"
    private const val RULES_KEY = "file_kind_rules_version"
    private const val SPEECH_KEY = "speech_to_text"
    private const val DEEPER_KEY = "deeper_sound_search"
    const val PERIODIC = "findr-index-periodic"
    private const val CHANNEL = "indexing"
    private const val NOTIFICATION_ID = 7
    private val NEXT_CHUNK = Regex("next=(\\d+)")
    private val SKIPPED_WINDOWS = Regex("skipped=\\d+")
    private const val REDO_BATCH = 16   // ~8 s of text embedding on the A06 between checks for pause, charging and heat
    const val WARM_C = 41.0          // the A06 sat at 40–41 °C on the charger under load (M1)
    const val HOT_STOP_C = 44.0
    const val WARM_REST_MS = 20_000L
    const val COOL_DOWN_MS = 120_000L
    /** How often a light-tier run, waiting for quick look to finish, checks whether the phone was plugged in. */
    const val LIGHT_POLL_MS = 2_000L

    /** After a batch, a write-ahead log bigger than this is folded back into the database and shrunk (the A06's had reached 10.6 MB beside a 2.2 MB database). */
    const val WAL_TRIM_BYTES = 2L * 1024 * 1024
    /** How soon a finished quick-look pass looks again for new photos. */
    const val QUICK_RECHECK_MS = 60_000L

    /**
     * Whether a file's quick-look vector survives saving what the worker read. Standard: EmbeddingGemma's vectors replace it;
     * if it produced none, the quick one is all there is, so it stays. Lite: it stays unless the file got a new picture vector,
     * because the text vectors of a picture's words don't stand in for the picture.
     */
    fun keepsQuick(segments: List<Segment>, imagesByEmbeddingGemma: Boolean): Boolean =
      if (imagesByEmbeddingGemma) segments.none { it.vector != null }
      else segments.none { it.vector != null && it.modality == Modality.CLIP }

    fun timeoutFor(kind: FileKind): Long = when (kind) {
      FileKind.VIDEO, FileKind.AUDIO -> 10 * 60_000L
      FileKind.DOCUMENT, FileKind.PDF -> 5 * 60_000L
      else -> 2 * 60_000L
    }

    /**
     * The ongoing notification while a file is read: its kind, never its name. A notification can show on the lock
     * screen and in a screenshot of the shade, and a file name alone can say a lot ("HIV result.pdf").
     */
    @StringRes fun notificationLine(kind: FileKind): Int = when (kind) {
      FileKind.IMAGE -> R.string.notif_reading_photo
      FileKind.SCREENSHOT -> R.string.notif_reading_screenshot
      FileKind.VIDEO -> R.string.notif_reading_video
      FileKind.DOCUMENT, FileKind.PDF -> R.string.notif_reading_document
      FileKind.AUDIO -> R.string.notif_reading_recording
      FileKind.OTHER -> R.string.notif_reading_file
    }
  }
}
