package com.craneai.findr.engine

import android.content.Context
import android.os.SystemClock
import com.craneai.findr.core.FindrLog as Log
import com.craneai.findr.core.IndexedFile
import com.craneai.findr.core.PrivacyCheck
import com.craneai.findr.core.PrivateLook
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TAG = "FindrPrivacy"

/**
 * Blur until scored (internal/research/blur-until-scored.md): a picture with no privacy score yet is drawn blurred, and while it is
 * on screen it is scored here, ahead of the background [PrivacyPass]:
 *  - one picture at a time, on the pass's own thread ([PrivacyPass.dispatcher]), so it never runs beside the pass and never
 *    doubles the model's CPU; the pass yields after each picture, so an ask waits at most one;
 *  - the picture shown last goes first, and a thumbnail that leaves the screen drops its ask ([OnDemandQueue]);
 *  - it waits while the user scrolls (as the passes do), so scrolling stays smooth;
 *  - unlike the pass it runs on battery and while indexing is paused: it only scores what the person is looking at.
 * Every score saved, by the pass or here, is kept in [fresh] for this process and handed to the thumbnails waiting for it.
 */
class PrivacyOnDemand(private val context: Context, private val engine: FindrEngine) : PrivacyCheck {
  /** The queue's key: the file, equal by id, so a grid tile and a row of the same picture share one ask. */
  private class Ask(val id: Long, val file: IndexedFile?) {
    override fun equals(other: Any?) = other is Ask && other.id == id
    override fun hashCode() = id.hashCode()
  }

  private val scope = CoroutineScope(SupervisorJob() + PrivacyPass.dispatcher)
  private val fresh = ConcurrentHashMap<Long, Float>()
  // Waits for a scroll to stop, not for the search box's focus hold (userInteracting): the results being looked at must unblur.
  private val queue = OnDemandQueue<Ask, Float?>(scope, hold = { while (engine.userScrolling) delay(HOLD_POLL_MS) }) { scoreNow(it) }
  @Volatile private var availableAt = 0L
  @Volatile private var availableNow = false
  @Volatile private var modelChecked = false
  private var releaseJob: Job? = null

  /** Asks waiting or running: the pass then leaves the model loaded when it ends. */
  val busy: Boolean get() = queue.busy

  /** The blur setting's scorer is in and switched on (a file stat, read at most every [AVAILABLE_MS]). */
  override fun available(): Boolean {
    if (!Tuning.current.blurGate) return false
    val now = SystemClock.elapsedRealtime()
    if (availableAt == 0L || now - availableAt >= AVAILABLE_MS) {
      availableNow = engine.settings.value.privateScoring && engine.privacy.available()
      availableAt = now
    }
    return availableNow
  }

  override suspend fun score(file: IndexedFile): Float? {
    fresh[file.id]?.let { return it }
    if (!available()) return null
    return queue.get(Ask(file.id, file))
  }

  /** Scored this process (by the pass or here): the pass skips it. */
  fun known(id: Long): Boolean = fresh.containsKey(id)

  /** A score was saved for [id] (negative: unreadable): the thumbnails waiting for it get it now. */
  fun saved(id: Long, score: Float, via: String) {
    if (fresh.size >= MAX_FRESH) fresh.clear()
    fresh[id] = score
    if (Log.detail) Log.i(TAG, "privacy saved id=$id via=$via private=${PrivateLook.blurs(score)} unreadable=${score < 0f}")
    queue.offer(Ask(id, null), score)
  }

  /** Files were deleted, skipped, or reset during crawl: drop any cached privacy scores for those IDs immediately. */
  fun evict(ids: Collection<Long>) {
    for (id in ids) fresh.remove(id)
  }

  /** Every score was cleared (the pack removed, index deleted, or model changed): forget the ones kept here too. */
  fun forget() {
    fresh.clear()
    modelChecked = false
  }

  /** Runs on the privacy thread, one at a time. */
  private suspend fun scoreNow(ask: Ask): Float? {
    fresh[ask.id]?.let { return it }
    val file = ask.file ?: return null
    if (!available()) return null
    val store = engine.awaitStoreForPrivacy() ?: return null
    // Scores written before the pass has ever run must carry the model's name, or the pass's first start would clear them.
    if (!modelChecked) { if (store.ensurePrivateModel(PrivateLook.MODEL)) fresh.clear(); modelChecked = true }
    val t0 = System.nanoTime()
    val score = PrivacyPass.scoreAndSave(context, engine, file, "demand")
    if (Log.detail) Log.i(TAG, "privacy on demand: id=${ask.id} in ${(System.nanoTime() - t0) / 1_000_000} ms, ${queue.waiting} waiting")
    scheduleRelease()
    return score
  }

  /** The scorer holds ~60 MB: give it back a while after the last ask, unless the pass is using it or the phone keeps models. */
  private fun scheduleRelease() {
    releaseJob?.cancel()
    releaseJob = scope.launch {
      delay(RELEASE_AFTER_MS)
      if (!queue.busy && !engine.privacyPassRunning && !engine.keepModelsResident()) engine.privacy.release()
    }
  }

  companion object {
    const val HOLD_POLL_MS = 100L
    const val AVAILABLE_MS = 5_000L
    const val RELEASE_AFTER_MS = 30_000L
    const val MAX_FRESH = 2_048
  }
}
