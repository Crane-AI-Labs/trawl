package com.craneai.findr.ui.fake

import com.craneai.findr.core.FileKind
import com.craneai.findr.core.IndexPhase
import com.craneai.findr.core.IndexStatus
import com.craneai.findr.core.KindProgress
import com.craneai.findr.core.PauseReason
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Plays an indexing run on fast-forward so the meter can be seen moving: scan, then names and words become
 * searchable quickly, then meaning fills in kind by kind, with a couple of demo pauses (charger, heat).
 * The counts race ahead, but the reported rate and ETA use realistic budget-phone numbers (~640 files an hour).
 */
internal class FakeIndexingSim(
  private val scope: CoroutineScope,
  private val onlyWhileCharging: () -> Boolean,
  private val sampleNames: Map<FileKind, List<String>>,
  private val tickMs: Long = 600,
) {
  private val _status = MutableStateFlow(IndexStatus(modelBytes = MODEL_BYTES))
  val status: StateFlow<IndexStatus> = _status

  private var job: Job? = null
  private var userPaused = false

  // Reading order from the research: screenshots, photos, documents, videos, audio.
  private val totals = linkedMapOf(
    FileKind.SCREENSHOT to 640,
    FileKind.IMAGE to 1_210,
    FileKind.DOCUMENT to 96,
    FileKind.PDF to 214,
    FileKind.VIDEO to 138,
    FileKind.AUDIO to 182,
  )
  private val grandTotal = totals.values.sum()

  fun start() {
    if (job?.isActive == true) return
    job = scope.launch { run() }
  }

  fun pause() {
    userPaused = true
    _status.value = _status.value.copy(phase = IndexPhase.PAUSED, pauseReason = PauseReason.USER)
  }

  fun resume() {
    userPaused = false
    // the loop sets the phase back on its next tick; make the button feel instant
    if (_status.value.phase == IndexPhase.PAUSED && _status.value.pauseReason == PauseReason.USER) {
      _status.value = _status.value.copy(phase = IndexPhase.INDEXING, pauseReason = null)
    }
  }

  fun reset() {
    job?.cancel()
    job = null
    userPaused = false
    _status.value = IndexStatus(modelBytes = MODEL_BYTES)
  }

  private suspend fun run() {
    var known = 0
    var searchable = 0
    var understood = 0
    var tick = 0
    var demoChargerLeft = 0
    var demoHotLeft = 0
    _status.value = IndexStatus(phase = IndexPhase.SCANNING, modelBytes = MODEL_BYTES)

    while (true) {
      delay(tickMs)
      tick++
      if (userPaused) continue

      // Demo pauses, so every kind of waiting can be seen once.
      if (understood in 40..60 && demoChargerLeft == 0 && onlyWhileCharging() && tick % 2 == 0 && !chargerShown) {
        chargerShown = true
        demoChargerLeft = 14
      }
      if (understood in 900..930 && !hotShown) {
        hotShown = true
        demoHotLeft = 12
      }
      if (demoChargerLeft > 0) {
        demoChargerLeft--
        _status.value = snapshot(known, searchable, understood, IndexPhase.PAUSED, PauseReason.NOT_CHARGING)
        continue
      }
      if (demoHotLeft > 0) {
        demoHotLeft--
        _status.value = snapshot(known, searchable, understood, IndexPhase.PAUSED, PauseReason.TOO_HOT)
        continue
      }

      when {
        known < grandTotal -> {
          known = (known + 260).coerceAtMost(grandTotal)
          searchable = (known - 60).coerceAtLeast(0)
          _status.value = snapshot(known, searchable, understood, IndexPhase.SCANNING, null)
        }
        searchable < grandTotal -> {
          searchable = (searchable + 420).coerceAtMost(grandTotal)
          _status.value = snapshot(known, searchable, understood, IndexPhase.INDEXING, null)
        }
        understood < grandTotal -> {
          understood = (understood + 9).coerceAtMost(grandTotal)
          _status.value = snapshot(known, searchable, understood, IndexPhase.INDEXING, null)
        }
        else -> {
          _status.value = snapshot(known, searchable, understood, IndexPhase.UP_TO_DATE, null)
          return
        }
      }
    }
  }

  private var chargerShown = false
  private var hotShown = false

  private fun snapshot(known: Int, searchable: Int, understood: Int, phase: IndexPhase, reason: PauseReason?): IndexStatus {
    // Distribute "understood" over the kinds in reading order; "known"/"searchable" proportionally.
    var understoodLeft = understood
    val byKind = LinkedHashMap<FileKind, KindProgress>()
    var currentKind: FileKind? = null
    for ((kind, total) in totals) {
      val knownK = (total.toLong() * known / grandTotal).toInt()
      val doneK = understoodLeft.coerceAtMost(knownK).coerceAtLeast(0)
      understoodLeft -= doneK
      byKind[kind] = KindProgress(known = knownK, understood = doneK)
      if (currentKind == null && doneK < total) currentKind = kind
    }
    val remaining = grandTotal - understood
    val rate = RATE_PER_HOUR
    val currentName = if (phase == IndexPhase.INDEXING || phase == IndexPhase.SCANNING) {
      val names = sampleNames[currentKind ?: FileKind.IMAGE].orEmpty()
      names.takeIf { it.isNotEmpty() }?.get((understood / 3 + known / 200) % names.size)
    } else null
    return IndexStatus(
      phase = phase,
      pauseReason = reason,
      filesKnown = known,
      filesSearchable = searchable,
      filesUnderstood = understood,
      byKind = byKind,
      etaSeconds = if (understood >= 20 && phase != IndexPhase.UP_TO_DATE) (remaining / rate * 3600).toLong() else null,
      ratePerHour = if (understood >= 20) rate else null,
      indexBytes = searchable * 1_800L + understood * 3_100L + 512_000,
      modelBytes = MODEL_BYTES,
      currentFile = currentName,
    )
  }

  private companion object {
    const val MODEL_BYTES = 388_000_000L
    const val RATE_PER_HOUR = 640.0
  }
}
