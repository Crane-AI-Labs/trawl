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
 * On the charger everything runs, as before (heat is paced by the worker from the battery temperature). On battery the
 * tier falls with the battery level and the phone's heat: warm ([WARM_THERMAL_STATUS]) keeps quick look but holds full
 * quality back; below [MIN_BATTERY_PCT] or hot ([HOT_THERMAL_STATUS]) only words run, and the work resumes by itself
 * ([RETRY_LATER_MIN]).
 */
object IndexPolicy {
  enum class Tier { WORDS_ONLY, LIGHT, BULK }

  /**
   * [tier] is what may run now. [heldBy] is why the next tier up does not: null at [Tier.BULK]. NOT_CHARGING means the
   * charger would unlock more (the meter then says full quality finishes when the phone charges).
   */
  data class Decision(val tier: Tier, val heldBy: PauseReason?, val newFilesFullQuality: Boolean = tier != Tier.WORDS_ONLY) {
    val lightAllowed: Boolean get() = tier != Tier.WORDS_ONLY
    val bulkAllowed: Boolean get() = tier == Tier.BULK
  }

  /** On battery, below this percentage only names and words are read. */
  const val MIN_BATTERY_PCT = 30

  /**
   * Android's PowerManager.THERMAL_STATUS_SEVERE ("UX is largely impacted"): at this status or above, on battery, only names
   * and words are read. It was MODERATE (2), but that is Android's "reduce power" step, not "stop": flagships report it after a
   * warm download or a call on 4G, with nothing of ours running (a Galaxy S24 sat at "Cooling down", quick look 0 of 11,804). The
   * worker's own battery-temperature rest (IndexWorker.HOT_STOP_C) is the second, independent hot signal.
   */
  const val HOT_THERMAL_STATUS = 3

  /**
   * Android's PowerManager.THERMAL_STATUS_MODERATE: on battery, the heavy GPU/CPU work of full quality (new files, the backlog)
   * waits, and the light work (quick look: ~1 core at a lowered priority) goes on. From [HOT_THERMAL_STATUS] up, only words.
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

  fun decide(
    charging: Boolean,
    batteryPercent: Int,
    thermalStatus: Int,
    onlyWhileCharging: Boolean,
    fullOnBattery: Boolean,
  ): Decision = when {
    charging -> Decision(Tier.BULK, null)
    batteryPercent in 0 until MIN_BATTERY_PCT -> Decision(Tier.WORDS_ONLY, PauseReason.BATTERY_LOW)
    thermalStatus >= HOT_THERMAL_STATUS -> Decision(Tier.WORDS_ONLY, PauseReason.TOO_HOT)
    onlyWhileCharging -> Decision(Tier.WORDS_ONLY, PauseReason.NOT_CHARGING)   // the strict setting: nothing heavier than words on battery
    thermalStatus >= WARM_THERMAL_STATUS -> Decision(Tier.LIGHT, PauseReason.NOT_CHARGING, newFilesFullQuality = false)   // warm: quick look only
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
   *  - charging (the bulk pass takes new files too), the strict "only while charging" setting, paused, or nothing waiting: [FollowUp.NONE].
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
