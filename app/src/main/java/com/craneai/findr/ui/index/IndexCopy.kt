package com.craneai.findr.ui.index

import com.craneai.findr.ui.common.appString
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.craneai.findr.R
import com.craneai.findr.core.IndexOpenProblem
import com.craneai.findr.core.IndexPhase
import com.craneai.findr.core.IndexStatus
import com.craneai.findr.core.PauseReason
import com.craneai.findr.ui.common.Eta
import com.craneai.findr.ui.common.etaOf
import com.craneai.findr.ui.common.formatCount

/** Plain-language copy for the indexing meter. Words live in strings.xml; this only chooses which ones. */

@Composable
fun IndexStatus.headline(): String = when (phase) {
  IndexPhase.NOT_STARTED -> stringResource(R.string.meter_title_not_started)
  IndexPhase.SCANNING -> stringResource(R.string.meter_title_scanning)
  IndexPhase.INDEXING -> stringResource(if (waitingForModels) R.string.meter_title_needs_models else R.string.meter_title_indexing)
  IndexPhase.PAUSED -> pauseReason?.let { stringResource(it.titleRes) } ?: stringResource(R.string.pause_user)
  IndexPhase.UP_TO_DATE -> stringResource(R.string.meter_title_up_to_date)
  IndexPhase.ERROR -> if (openProblem != null) appString(R.string.open_problem_title) else stringResource(R.string.meter_title_error)
}

/** The sentence for an index that cannot be opened right now: the reason class, then that it tries again by itself. */
@Composable
fun IndexOpenProblem.sentence(): String = stringResource(
  when (this) {
    IndexOpenProblem.BUSY -> R.string.open_problem_busy
    IndexOpenProblem.STORAGE_FULL -> R.string.open_problem_storage_full
    IndexOpenProblem.IO -> R.string.open_problem_io
    IndexOpenProblem.MEMORY -> R.string.open_problem_memory
    IndexOpenProblem.OTHER -> R.string.open_problem_other
  },
)

@Composable
fun etaLabel(etaSeconds: Long?): String? {
  val eta = etaSeconds?.let(::etaOf) ?: return null
  return when (eta) {
    Eta.UnderAMinute -> stringResource(R.string.eta_under_minute)
    is Eta.Minutes -> stringResource(R.string.eta_minutes, eta.minutes)
    is Eta.Hours ->
      if (eta.low == eta.high) stringResource(R.string.eta_hours_single, eta.low)
      else stringResource(R.string.eta_hours_range, eta.low, eta.high)
  }
}

/** The second line of the card: where we are, in one breath. */
@Composable
fun IndexStatus.subline(): String {
  val known = formatCount(filesKnown)
  val understood = formatCount(filesUnderstood)
  val readable = formatCount(filesReadable)   // what "understood" is out of: files that could not be read are final and counted apart
  return when (phase) {
    IndexPhase.NOT_STARTED -> appString(R.string.meter_sub_not_started)
    IndexPhase.SCANNING -> stringResource(R.string.meter_sub_scanning, known)
    IndexPhase.INDEXING -> {
      val eta = etaLabel(etaSeconds)
      val reembedNow = reembed   // a local copy: the property lives in :core now, so it cannot be smart-cast
      when {
        waitingForModels -> stringResource(R.string.meter_sub_needs_models)   // before the charger line: charging changes nothing until the models are here
        reembedNow != null -> reembedSubline(reembedNow, eta)
        needsCharger -> chargerSubline()
        filesSearchable < filesKnown -> stringResource(R.string.meter_sub_names_first)
        eta != null -> stringResource(R.string.meter_sub_progress_eta, understood, readable, eta)
        else -> stringResource(R.string.meter_sub_progress_estimating, understood, readable)
      }
    }
    IndexPhase.PAUSED ->
      if (pauseReason == PauseReason.NOT_CHARGING) reembed?.let { reembedSubline(it, null) } ?: chargerSubline()
      else stringResource(R.string.meter_sub_paused, understood, readable)
    IndexPhase.UP_TO_DATE ->
      if (filesUnreadable > 0) pluralStringResource(R.plurals.meter_sub_up_to_date_unreadable, filesUnreadable, known, filesUnreadable)
      else stringResource(R.string.meter_sub_up_to_date, known)
    IndexPhase.ERROR -> openProblem?.sentence() ?: lastError ?: stringResource(R.string.meter_sub_error)
  }
}

/**
 * After a model upgrade: "Updating photos for the new model: 120 of 3,000". Search works throughout (the old vectors stay until
 * each file is read again). On battery the rest waits for the charger and no finish time is promised.
 */
@Composable
private fun IndexStatus.reembedSubline(r: com.craneai.findr.core.ReembedMeter, eta: String?): String {
  val done = formatCount(r.done)
  val total = formatCount(r.total)
  return when {
    needsCharger || !charging -> stringResource(R.string.meter_sub_reembed_battery, done, total)
    eta != null -> stringResource(R.string.meter_sub_reembed_eta, done, total, eta)
    else -> stringResource(R.string.meter_sub_reembed, done, total)
  }
}

/**
 * On battery, full quality waits for the charger. What to say depends on what already works: with the light work on, photos get
 * a quick look (searchable by meaning) and, once that is done, everything is searchable; with the strict setting, only names and words.
 */
@Composable
private fun IndexStatus.chargerSubline(): String {
  val q = quickLook
  return when {
    !lightOnBattery || q == null -> stringResource(R.string.meter_sub_needs_charger)
    q.ready >= q.known -> stringResource(R.string.meter_sub_light_done)
    else -> stringResource(R.string.meter_sub_light_reading)
  }
}

/** "Quick look: 2,800 of 3,000 photos · Full detail: 300 of 3,000", or null when there's nothing to say. */
@Composable
fun IndexStatus.quickLookLine(): String? {
  val q = quickLook?.takeIf { showQuickLook } ?: return null
  return stringResource(R.string.meter_quicklook, formatCount(q.ready), formatCount(q.full), formatCount(q.known))
}

/** Whole-percent understood, for the card and TalkBack. */
fun IndexStatus.percentUnderstood(): Int = (understoodFraction * 100).toInt().coerceIn(0, 100)
