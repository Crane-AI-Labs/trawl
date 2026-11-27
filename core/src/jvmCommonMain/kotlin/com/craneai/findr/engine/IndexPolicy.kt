package com.craneai.findr.engine

import com.craneai.findr.core.FileKind
import com.craneai.findr.core.PauseReason

/**
 * What may run, given the phone's power. Pure, so the whole table is unit-tested and the thresholds are named here once
 * (the unplugged battery runs will tune them).
 *
 * Three tiers, each including the one before:
 *  - [Tier.WORDS_ONLY]: names and words (no model; milliseconds per document, ~0.26 s per image). Always allowed.
 *  - [Tier.LIGHT]: adds quick look (CLIP, ~0.6 s of CPU per photo on the A06) over the whole backlog, and full-quality
 *    EmbeddingGemma for files that arrived since the backlog was counted, a few at a time. This is what makes the gallery
 *    searchable by meaning within minutes of install, without a charger.
 *  - [Tier.BULK]: adds EmbeddingGemma over the backlog (hours of GPU for a whole gallery). On the charger by default.
 *
 * On the charger, warm ([WARM_THERMAL_STATUS]) keeps bulk work, and reduces text and audio threads only when the phone measures
 * warm too ([measuredWarm]: battery or headroom; D51). Warm Ugandan rooms make MODERATE common: first-run speed is a North Star,
 * so it does not drop the tier. Hot ([HOT_THERMAL_STATUS]) permits only words,
 * even on the charger. The worker's battery-temperature rest is an independent heat limit. On battery, warm keeps quick look but
 * holds full quality back; below [MIN_BATTERY_PCT] or hot, only words run. Low battery and heat are retried by themselves
 * ([RETRY_LATER_MIN]).
 *
 * Android's status is believed at SEVERE and above only when a second sign agrees ([corroborate], D48): on the KN4 and the A06
 * (MT6769) it stayed at SEVERE for days at 29-37 C, which would have kept such a phone at words only for good. A stale status
 * counts as none (D51).
 */
object IndexPolicy {
  enum class Tier { WORDS_ONLY, LIGHT, BULK }

  /**
   * [tier] is what may run now. [heldBy] is why the next tier up does not: null at [Tier.BULK]. NOT_CHARGING means the
   * charger would unlock more (the meter then says full quality finishes when the phone charges).
   * [reducedThreads] caps text and audio CPU threads without holding full quality back on a warm charger.
   */
  data class Decision(
    val tier: Tier,
    val heldBy: PauseReason?,
    val newFilesFullQuality: Boolean = tier != Tier.WORDS_ONLY,
    val reducedThreads: Boolean = false,
    /** Android said SEVERE or worse, but neither the battery nor the headroom agreed: decided as no heat ([corroborate]). */
    val thermalStale: Boolean = false,
  ) {
    val lightAllowed: Boolean get() = tier != Tier.WORDS_ONLY
    val bulkAllowed: Boolean get() = tier == Tier.BULK
  }

  /** On battery, below this percentage only names and words are read. */
  const val MIN_BATTERY_PCT = 30

  /**
   * Android's PowerManager.THERMAL_STATUS_SEVERE ("UX is largely impacted"): at this status or above, even on the charger, only names
   * and words are read. It was MODERATE (2), but that is Android's "reduce power" step, not "stop": flagships report it after a
   * warm download or a call on 4G, with nothing of ours running (a Galaxy S24 sat at "Cooling down", quick look 0 of 11,804). The
   * worker's own battery-temperature rest (IndexWorker.HOT_STOP_C) is the second, independent hot signal.
   */
  const val HOT_THERMAL_STATUS = 3

  /**
   * Android's PowerManager.THERMAL_STATUS_MODERATE: on battery, the heavy GPU/CPU work of full quality (new files, the backlog)
   * waits, and the light work (quick look: ~1 core at a lowered priority) goes on. On the charger, bulk continues with fewer text
   * and audio threads, since MODERATE is common in warm Ugandan rooms and first-run speed matters. From [HOT_THERMAL_STATUS] up,
   * only words, regardless of the charger.
   */
  const val WARM_THERMAL_STATUS = 2

  /** New files (arrived since the backlog was counted) given full quality on battery: at most this many per worker run... */
  const val NEW_FILES_PER_RUN = 20

  /** ...or this much encode time per run, whichever comes first. */
  const val NEW_ENCODE_MS_PER_RUN = 2 * 60_000L

  /** When low battery or heat holds the work back, look again after this many minutes. */
  const val RETRY_LATER_MIN = 10L

  /** After a capped run, the next batch of new files is asked for this many minutes later; Android runs it in the next window it gives. */
  const val NEXT_BATCH_MIN = 10L

  /**
   * [batterySaver] is Android's Battery Saver (`PowerManager.isPowerSaveMode`): the person asked the phone to spend less, and the
   * system restricts background work itself. It narrows only the middle setting, and only on battery:
   *  - the strict setting (Gentle) is already held to words on battery, so nothing changes for it;
   *  - Fast ("index everything on battery") ignores it: the person chose that on purpose, and its low-battery and heat limits still apply;
   *  - Balanced keeps quick look (one core at a lowered priority) and holds the GPU work of new files back, as it does when warm.
   * On the charger Battery Saver holds nothing: one left on by hand stays on while the phone charges. Heat limits still apply.
   * When Battery Saver alone holds work back, the reason stays NOT_CHARGING (plugging in lifts it), so the meter says what is true.
   */
  fun decide(
    charging: Boolean,
    batteryPercent: Int,
    thermalStatus: Int,
    onlyWhileCharging: Boolean,
    fullOnBattery: Boolean,
    batterySaver: Boolean = false,
    batteryC: Double = Double.NaN,
    headroom: Float = Float.NaN,
  ): Decision {
    val status = corroborate(thermalStatus, batteryC, headroom)
    val stale = status != thermalStatus
    // Threads are cut on the charger only when the phone measures warm (D51), or when it measures nothing at all and Android says warm.
    val measured = !batteryC.isNaN() || !headroom.isNaN()
    val warm = (stale || status == WARM_THERMAL_STATUS) && (measuredWarm(batteryC, headroom) || !measured)
    val d = decideWith(charging, batteryPercent, status, onlyWhileCharging, fullOnBattery, batterySaver, warm)
    return if (stale) d.copy(thermalStale = true) else d
  }

  /** At or above this battery temperature the phone measures warm: on the charger, fewer threads (D51). */
  const val WARM_BATTERY_C = 38.0

  /** At or above this thermal headroom the phone measures warm: on the charger, fewer threads (D51). */
  const val WARM_HEADROOM = 0.85f

  /** Does a measured signal say warm: the battery at [WARM_BATTERY_C] or more, or the headroom at [WARM_HEADROOM] or more? */
  fun measuredWarm(batteryC: Double, headroom: Float): Boolean =
    (!batteryC.isNaN() && batteryC >= WARM_BATTERY_C) || (!headroom.isNaN() && headroom >= WARM_HEADROOM)

  /**
   * At or above this battery temperature Android's SEVERE is believed. Below the worker's own battery rest
   * (IndexWorker.HOT_STOP_C / Tuning.hotStopC), which stays as it was: a genuinely hot battery stops the work by itself too.
   */
  const val HOT_BATTERY_C = 40.0

  /** At or above this thermal headroom (`PowerManager.getThermalHeadroom`; 1.0 is where Android throttles hard) SEVERE is believed. */
  const val HOT_HEADROOM = 1.0f

  /**
   * Android's thermal status as the decision uses it (D48, D51). SEVERE or worse counts only when a second sign agrees: the battery at
   * [HOT_BATTERY_C] or more, or the headroom at [HOT_HEADROOM] or more. When the battery says it is cooler and the headroom is lower
   * (or the phone gives none), the status is stale and counts as none (0): the normal tiers, full threads unless the phone measures
   * warm ([measuredWarm]). Deciding it as MODERATE cut a KN4 stuck at SEVERE to 2 threads at 34-36 C: +13 % first-run time, no
   * cooler (D51). With no battery temperature at all ([batteryC] NaN) nothing can disagree, so the status stands. Below SEVERE it is
   * used as it is.
   */
  fun corroborate(thermalStatus: Int, batteryC: Double, headroom: Float): Int = when {
    thermalStatus < HOT_THERMAL_STATUS -> thermalStatus
    batteryC.isNaN() -> thermalStatus
    batteryC >= HOT_BATTERY_C -> thermalStatus
    !headroom.isNaN() && headroom >= HOT_HEADROOM -> thermalStatus
    else -> 0
  }

  private fun decideWith(
    charging: Boolean,
    batteryPercent: Int,
    thermalStatus: Int,
    onlyWhileCharging: Boolean,
    fullOnBattery: Boolean,
    batterySaver: Boolean,
    warm: Boolean,
  ): Decision = when {
    charging && thermalStatus >= HOT_THERMAL_STATUS -> Decision(Tier.WORDS_ONLY, PauseReason.TOO_HOT)
    charging -> Decision(Tier.BULK, null, reducedThreads = warm)
    batteryPercent in 0 until MIN_BATTERY_PCT -> Decision(Tier.WORDS_ONLY, PauseReason.BATTERY_LOW)
    thermalStatus >= HOT_THERMAL_STATUS -> Decision(Tier.WORDS_ONLY, PauseReason.TOO_HOT)
    onlyWhileCharging -> Decision(Tier.WORDS_ONLY, PauseReason.NOT_CHARGING)   // the strict setting: nothing heavier than words on battery
    thermalStatus >= WARM_THERMAL_STATUS -> Decision(Tier.LIGHT, PauseReason.NOT_CHARGING, newFilesFullQuality = false)   // warm: quick look only
    batterySaver && !fullOnBattery -> Decision(Tier.LIGHT, PauseReason.NOT_CHARGING, newFilesFullQuality = false)   // Battery Saver: the same light share
    fullOnBattery -> Decision(Tier.BULK, null)
    else -> Decision(Tier.LIGHT, PauseReason.NOT_CHARGING)
  }

  /** What to do after a run, or a Doze-window crawl, when files that arrived since the backlog was counted may still wait for full quality (D12). */
  enum class FollowUp { NONE, NEXT_BATCH, RETRY_LATER }

  /**
   * D12 promises that a new file is searchable at full quality soon after it arrives, ~[NEW_FILES_PER_RUN] files per run. A
   * run stops at its cap, so something has to ask for the next one: the 3 h A06 night had 80 new photos left after the first
   * run and nothing queued the rest. Pure: no new threshold, no new rule; it only says when the existing rule's next run is due.
   *  - light work allowed (on battery, level and heat fine): [FollowUp.NEXT_BATCH];
   *  - held back by low battery or heat: [FollowUp.RETRY_LATER] (as the worker already does);
   *  - charging below severe heat (the bulk pass takes new files too), the strict "only while charging" setting on battery,
   *    paused, or nothing waiting: [FollowUp.NONE].
   */
  fun followUp(decision: Decision, paused: Boolean, newFilesWaiting: Boolean): FollowUp = when {
    paused || !newFilesWaiting -> FollowUp.NONE
    decision.tier == Tier.LIGHT -> FollowUp.NEXT_BATCH
    decision.tier == Tier.WORDS_ONLY && (decision.heldBy == PauseReason.BATTERY_LOW || decision.heldBy == PauseReason.TOO_HOT) -> FollowUp.RETRY_LATER
    else -> FollowUp.NONE
  }

  /**
   * The kinds that get full quality at once on battery: small, bounded work. Long videos and recordings wait for the charger (quick
   * look reads a video's frame). Short ones that arrived since the backlog was counted are added by [newFileKinds]: a video filmed
   * this morning is searchable by what is said and shown in it this morning.
   */
  val NEW_FILE_KINDS = setOf(FileKind.IMAGE, FileKind.SCREENSHOT, FileKind.DOCUMENT, FileKind.PDF)

  /** [NEW_FILE_KINDS], plus videos and recordings when [newMedia] (the app's Tuning.newMediaOnBattery; the size caps are Tuning.newVideoMaxMb and Tuning.newAudioMaxMb). */
  fun newFileKinds(newMedia: Boolean): Set<FileKind> = if (newMedia) NEW_FILE_KINDS + FileKind.VIDEO + FileKind.AUDIO else NEW_FILE_KINDS
}
