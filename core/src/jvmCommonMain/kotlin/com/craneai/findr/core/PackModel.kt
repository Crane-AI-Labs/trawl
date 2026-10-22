package com.craneai.findr.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Optional model packs ("Add-ons"): extra models Findr works better with, each a one-time file the user adds because
 * the app has no internet permission. This file holds everything the UI and the tests need to know about them and
 * nothing that touches storage (that is `engine/Pack*.kt`), so it is plain JVM and unit-tested.
 *
 * A new pack (a language pack, say) is one [PackDef] in [PackCatalog.all] plus one entry in `engine/PackFiles.kt`.
 */

/**
 * A piece of the packs' copy, as a string resource plus the numbers it quotes, so the words live in `res/values*` (translated)
 * and the numbers stay in [PackCatalog.Facts]. [res] is a `<string>`, or a `<plurals>` when [quantity] is set (the count that
 * picks the plural form). [args] are the positional arguments in order; the app's name is added after them by the reader
 * (`ui/addons/PackText.kt`), as everywhere. An argument may be a [Grouped] number or another [PackCopy]. Plain data, so the
 * JVM tests can check which numbers each sentence quotes.
 */
data class PackCopy(val res: Int, val args: List<Any> = emptyList(), val quantity: Int? = null) {
  /** A whole number shown with the locale's digit grouping ("7,800"); passed to the string as `%s`. */
  data class Grouped(val n: Int)
}

/**
 * What a pack is, in the words the user reads (as resources: see [PackCopy]). [enabledIn] and [withEnabled] say which setting
 * the pack's switch drives, so the screens never name a setting.
 */
data class PackDef(
  val id: String,
  /** The pack's title: a `<string>` resource. */
  val nameRes: Int,
  /** What it does for the user, plain language. */
  val benefit: PackCopy,
  /** What it costs or where it stops, said honestly. */
  val cost: PackCopy,
  val sizeBytes: Long,
  /** The file to look for in Downloads. */
  val archiveName: String,
  /** False while the pack's files do not exist yet: shown as "Coming soon" in Settings and left out of onboarding. */
  val available: Boolean,
  val enabledIn: (FindrSettings) -> Boolean,
  val withEnabled: (FindrSettings, Boolean) -> FindrSettings,
  /** Who should add it, when that is a clear group ("Recommended if ..."); null when the pack is just an option. */
  val recommendedFor: PackCopy? = null,
  /**
   * On by default: installed without a question from the models bundle or its own file in Downloads (ModelInstaller), so
   * onboarding does not ask about it. Settings → Add-ons still switches it off or removes it, and that choice is kept.
   */
  val byDefault: Boolean = false,
) {
  /** Whole megabytes, rounded down, as the install log and the docs say it. */
  val sizeMb: Long get() = sizeBytes / 1_000_000

  /** The title as a [PackCopy], for use as an argument of another sentence. */
  val nameCopy: PackCopy get() = PackCopy(nameRes)
}

/** The ids of the packs, shared by the catalog (in :app, with its string resources) and the stand-in manager. */
object PackIds {
  const val SPEECH = "speech"
  const val QUICK_LOOK = "quicklook"
  const val PRIVACY = "privacy"
  val ALL: List<String> = listOf(SPEECH, QUICK_LOOK, PRIVACY)
}

// ---- State ---------------------------------------------------------------------------------------------------------

/** Why a pack could not be added. The copy comes from the kind; the detail is for the log and the "damaged file" case. */
enum class PackProblem { NOT_FOUND, NEEDS_FILE_ACCESS, BAD_FILES, UNAVAILABLE }

sealed interface PackState {
  data object NotInstalled : PackState
  data class Installing(val doneBytes: Long, val totalBytes: Long) : PackState {
    /** 0..1, or null while the size is not known yet. */
    val fraction: Float? get() = if (totalBytes > 0) (doneBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else null
  }
  data class Installed(val bytes: Long) : PackState
  /** Not installed, and the last try failed. Shown until the next try or until the pack turns up. */
  data class Failed(val problem: PackProblem, val detail: String? = null) : PackState
}

/** What the screens use to add and remove packs. The engine implements it; [NoPacks] stands in for the fake engine. */
interface PackManager {
  val states: StateFlow<Map<String, PackState>>
  /** Find the pack file, check it, copy it in and switch the pack on. Progress shows as [PackState.Installing]. */
  fun install(id: String)
  /** Delete the pack's files, switch it off, and do not install it again behind the user's back. */
  fun remove(id: String)
  /** "Not now": remember the choice, so a file that happens to sit in Downloads is not installed unasked. */
  fun decline(id: String)
  /** One or two sentences on how this build gets the file (today: copy a .zip into Downloads), null for an unknown pack. */
  fun howToAdd(id: String): PackCopy?
}

object NoPacks : PackManager {
  override val states: StateFlow<Map<String, PackState>> =
    MutableStateFlow(PackIds.ALL.associateWith { PackState.NotInstalled })
  override fun install(id: String) {}
  override fun remove(id: String) {}
  override fun decline(id: String) {}
  override fun howToAdd(id: String): PackCopy? = null
}

// ---- What a card shows ---------------------------------------------------------------------------------------------

enum class PackStatus { COMING_SOON, NOT_INSTALLED, INSTALLING, INSTALLED }

/** One pack as a card shows it. Pure data, so the rules below are unit-tested. */
data class PackRow(
  val def: PackDef,
  val status: PackStatus,
  /** The pack's switch (only meaningful when [status] is INSTALLED). */
  val on: Boolean,
  /** 0..1 while INSTALLING, null when unknown. */
  val progress: Float?,
  /** Set when the last try failed and the pack is still not installed. */
  val problem: PackProblem?,
  val problemDetail: String?,
  /** What is on disk for this pack. */
  val installedBytes: Long,
)

fun packRows(catalog: List<PackDef>, states: Map<String, PackState>, settings: FindrSettings): List<PackRow> =
  catalog.map { def ->
    val state = states[def.id] ?: PackState.NotInstalled
    val status = when {
      state is PackState.Installed -> PackStatus.INSTALLED   // files win: a pack already on the phone shows even if "unavailable"
      state is PackState.Installing -> PackStatus.INSTALLING
      !def.available -> PackStatus.COMING_SOON
      else -> PackStatus.NOT_INSTALLED
    }
    PackRow(
      def = def,
      status = status,
      on = status == PackStatus.INSTALLED && def.enabledIn(settings),
      progress = (state as? PackState.Installing)?.fraction,
      problem = (state as? PackState.Failed)?.problem,
      problemDetail = (state as? PackState.Failed)?.detail,
      installedBytes = (state as? PackState.Installed)?.bytes ?: 0,
    )
  }

/**
 * Packs onboarding can still ask about: the opt-in ones that can be added today. The default packs ([PackDef.byDefault]) are
 * installed without a question when their files are in Downloads and are never nagged about when they are not.
 */
fun offeredAtOnboarding(rows: List<PackRow>): List<PackRow> = rows.filter { it.status != PackStatus.COMING_SOON && !it.def.byDefault }

/**
 * The ids onboarding asks about when it opens: [offeredAtOnboarding] minus what is already on the phone. Taken once, so a
 * card the person just acted on stays to show "Added" instead of vanishing under their finger; empty means no page at all.
 */
fun idsToOfferAtOnboarding(rows: List<PackRow>): List<String> =
  offeredAtOnboarding(rows).filter { it.status != PackStatus.INSTALLED }.map { it.def.id }
