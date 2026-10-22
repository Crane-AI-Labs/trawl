package com.craneai.findr.ui.index

import androidx.annotation.StringRes
import com.craneai.findr.R
import com.craneai.findr.core.FileKind
import com.craneai.findr.core.IndexPhase
import com.craneai.findr.core.IndexStatus
import com.craneai.findr.core.PauseReason
import com.craneai.findr.ui.common.fraction

/** One line of "progress by kind". Documents and PDFs read as one thing to the user. */
data class KindRow(@param:StringRes val label: Int, val known: Int, val understood: Int) {
  val fraction: Float get() = fraction(understood, known)
}

/** Reading order from the research: screenshots, photos, documents, videos, audio. Kinds with no files yet are left out. */
fun IndexStatus.kindRows(): List<KindRow> {
  fun sum(vararg kinds: FileKind): Pair<Int, Int> {
    var k = 0
    var u = 0
    for (kind in kinds) byKind[kind]?.let { k += it.known; u += it.understood }
    return k to u
  }
  return listOf(
    R.string.kind_plural_screenshots to sum(FileKind.SCREENSHOT),
    R.string.kind_plural_photos to sum(FileKind.IMAGE),
    R.string.kind_plural_documents to sum(FileKind.DOCUMENT, FileKind.PDF),
    R.string.kind_plural_videos to sum(FileKind.VIDEO),
    R.string.kind_plural_audio to sum(FileKind.AUDIO),
  ).filter { it.second.first > 0 }.map { (label, ku) -> KindRow(label, ku.first, ku.second) }
}

val IndexStatus.searchableFraction: Float get() = fraction(filesSearchable, filesKnown)
val IndexStatus.understoodFraction: Float get() = fraction(filesUnderstood, filesReadable)   // files that cannot be read are final: the bar ends at 100% without them

/** The quick-look line earns its place only while some photos still wait for full detail. */
val IndexStatus.showQuickLook: Boolean get() = quickLook?.let { it.known > 0 && it.full < it.known } ?: false

/** Is there still reading to do? (Results may be incomplete, and the meter earns its place on screen.) */
val IndexStatus.isWorking: Boolean get() = phase == IndexPhase.SCANNING || phase == IndexPhase.INDEXING

@get:StringRes
val PauseReason.titleRes: Int
  get() = when (this) {
    PauseReason.NOT_CHARGING -> R.string.pause_not_charging
    PauseReason.TOO_HOT -> R.string.pause_too_hot
    PauseReason.BATTERY_LOW -> R.string.pause_battery_low
    PauseReason.USER -> R.string.pause_user
    PauseReason.NO_PERMISSION -> R.string.pause_no_permission
    PauseReason.STORAGE_LOW -> R.string.pause_storage_low
  }

@get:StringRes
val PauseReason.detailRes: Int
  get() = when (this) {
    PauseReason.NOT_CHARGING -> R.string.pause_not_charging_detail
    PauseReason.TOO_HOT -> R.string.pause_too_hot_detail
    PauseReason.BATTERY_LOW -> R.string.pause_battery_low_detail
    PauseReason.USER -> R.string.pause_user_detail
    PauseReason.NO_PERMISSION -> R.string.pause_no_permission_detail
    PauseReason.STORAGE_LOW -> R.string.pause_storage_low_detail
  }

/** Every pause has a next step. The step Findr can offer, if any. */
enum class NextStep { RESUME, READ_ON_BATTERY, ALLOW_ACCESS, FREE_SPACE, NONE }

fun PauseReason.nextStep(): NextStep = when (this) {
  PauseReason.USER -> NextStep.RESUME
  PauseReason.NOT_CHARGING -> NextStep.READ_ON_BATTERY
  PauseReason.NO_PERMISSION -> NextStep.ALLOW_ACCESS
  PauseReason.STORAGE_LOW -> NextStep.FREE_SPACE
  PauseReason.TOO_HOT, PauseReason.BATTERY_LOW -> NextStep.NONE   // resumes by itself
}
