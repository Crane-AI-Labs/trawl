package com.craneai.findr.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Why a models bundle was refused. The screens turn each into a plain, translated sentence; the detail stays in the log. */
enum class Refusal {
  /** No signed manifest at the front of the file: an old-style bundle, a loose zip, or not our file at all. */
  NOT_SIGNED,
  /** The manifest is not signed by a key this app trusts, or was changed after signing. */
  BAD_SIGNATURE,
  /** The manifest is signed but this app cannot read it (a newer format, or nonsense). */
  BAD_MANIFEST,
  /** Built for the other phone class (a 2 GB phone's bundle on a bigger phone, or the reverse). */
  WRONG_CLASS,
  /** A file is missing, extra, the wrong size or fails its SHA-256; or the zip is broken (one that ends too soon is [INCOMPLETE]). */
  DAMAGED,
  /** The same version is already installed. */
  ALREADY_INSTALLED,
  /** An older version than the one installed. */
  OLDER_VERSION,
  /** Not enough free space to hold the models while they are checked. */
  NO_SPACE,
  /** Stopped by the person. */
  CANCELLED,
  /**
   * The file ends too soon: a download that stopped before it finished (the zip has no end record, or its data runs out).
   * Not damage: getting the file again fixes it. The card says how much arrived against the full size.
   */
  INCOMPLETE,
  /** The file could not be opened or read at all (access taken away, the file moved or deleted): nothing is known about its contents. */
  UNREADABLE,
}

enum class DeliveryPhase {
  /** Nothing found yet; the card says what to do. */
  IDLE,
  /** A download is under way in the browser (a partial file is growing in Downloads): size so far. */
  DOWNLOADING,
  /** A bundle is being checked and copied in: [DeliveryState.doneBytes] of [DeliveryState.totalBytes]. */
  INSTALLING,
  /** Installed and verified. */
  DONE,
  /** Refused or failed: [DeliveryState.refusal]. */
  REFUSED,
}

/** The file the person can be offered to delete after a verified install: exactly the one that was verified. */
data class RemovableFile(val name: String, val bytes: Long)

/** How a newer bundle affects a phone that already has models (the research notes 5.4). */
enum class UpdateKind(val id: String) {
  PACK_ONLY("pack-only"), QUERY_ONLY("query-only"), TEXT_REEMBED("text-reembed"), FULL_REEMBED("full-reembed"), CONFIG("config");

  /** Whether the phone has to read the person's files again. Query-only, pack-only and config changes do not touch the index. */
  val rereadsFiles: Boolean get() = this == TEXT_REEMBED || this == FULL_REEMBED

  companion object {
    /** An unknown kind is treated as the costly one: the notice never promises a cheaper update than it can prove. */
    fun parse(id: String?): UpdateKind = entries.firstOrNull { it.id == id } ?: FULL_REEMBED
  }
}

/** A better model bundle than the one on the phone, known to this app release. */
data class ModelUpdate(val latestVersion: Int, val installedVersion: Int, val sizeMb: Int, val kind: UpdateKind)

data class DeliveryState(
  val phase: DeliveryPhase = DeliveryPhase.IDLE,
  val doneBytes: Long = 0,
  val totalBytes: Long = 0,
  val refusal: Refusal? = null,
  /** The size of the bundle for this phone's class, for the button ("Get the models (670 MB)"). */
  val sizeMb: Int = 0,
  /** Set once after a verified install: offer to free the space. Null once answered. */
  val removable: RemovableFile? = null,
  /** Set when the installed bundle is older than the newest this release knows and the person has not dismissed it. */
  val update: ModelUpdate? = null,
  /** The model pack in force ([ModelPlan.effective]): what Get downloads and what the first-run choice shows as selected. */
  val choice: ModelChoice = ModelChoice.STANDARD,
  /** Whether the person is asked at all: false on a 2 GB phone, which always gets the small pack. */
  val choiceOffered: Boolean = false,
  /** The sizes of the two packs, for the choice rows ("670 MB", "304 MB"); 0 when the release does not know them. */
  val standardMb: Int = 0,
  val saveDataMb: Int = 0,
  /** The pack installed now, from its signed record; null when there is none or the model came from loose files. */
  val installedChoice: ModelChoice? = null,
  /**
   * A bundle just took this phone from the small pack to the standard one (this session). Files are read again with the better
   * model, and the app has to be closed and opened once so it starts the full stack.
   */
  val upgradedToStandard: Boolean = false,
) {
  /**
   * How far a stopped download got, as whole MB (arrived, expected) for "300 of 670 MB", or null when either is not known (a file
   * chosen from elsewhere, an add-on of unknown size). The arrived part is never shown above the expected one.
   */
  val incompleteMb: Pair<Int, Int>?
    get() = if (doneBytes > 0 && totalBytes > 0) (minOf(doneBytes, totalBytes) / 1_000_000).toInt() to (totalBytes / 1_000_000).toInt() else null
}

/**
 * Getting the model files onto a phone that has no internet permission: the person downloads one signed bundle with a browser
 * (or receives the file) and Trawl verifies and installs it (engine/SignedBundle.kt). The fake engine has none.
 */
interface DeliveryService {
  val state: StateFlow<DeliveryState>

  /** Where the Get button sends the browser: the bundle for this phone's class (BuildConfig.MODELS_BASE_URL). */
  fun downloadUrl(): String

  /** The person picked a pack (first run, or Settings to get the standard pack on top of Save data). Remembered; ignored on a 2 GB phone. */
  fun setChoice(choice: ModelChoice)

  /** Watch Downloads for the bundle while a card that asks for it is on screen (counted, so two cards can share it). */
  fun watch(on: Boolean)

  /** The person chose a file (ACTION_OPEN_DOCUMENT): verify and install it. */
  fun installFrom(uri: String)

  fun cancel()

  /** Clears a refusal or the done message back to the idle card. */
  fun acknowledge()

  /** The person tapped yes: delete the verified file (and only it) and say how much was freed through [state]. */
  fun removeDownloaded()

  /** The person tapped no: remember the answer, never ask about this file again. */
  fun keepDownloaded()

  /** The update card was dismissed: not shown again for this version. */
  fun dismissUpdate()
}

object NoDelivery : DeliveryService {
  override val state: StateFlow<DeliveryState> = MutableStateFlow(DeliveryState())
  override fun downloadUrl(): String = ""
  override fun setChoice(choice: ModelChoice) {}
  override fun watch(on: Boolean) {}
  override fun installFrom(uri: String) {}
  override fun cancel() {}
  override fun acknowledge() {}
  override fun removeDownloaded() {}
  override fun keepDownloaded() {}
  override fun dismissUpdate() {}
}
