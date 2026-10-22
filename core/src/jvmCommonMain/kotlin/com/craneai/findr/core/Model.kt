package com.craneai.findr.core

/**
 * The shared vocabulary of Findr. Everything the UI shows and everything the engine produces is one of these types,
 * so the packages (ui, extract, index, search, engine) can be built separately and meet here.
 */

/** What a file is, as the user thinks of it. A screenshot is an image, but people search for it differently. */
enum class FileKind { DOCUMENT, PDF, IMAGE, SCREENSHOT, VIDEO, AUDIO, OTHER }

/** What a search can be narrowed to (the chips under the search box). */
enum class KindFilter(val kinds: Set<FileKind>) {
  ALL(FileKind.entries.toSet()),
  PHOTOS(setOf(FileKind.IMAGE)),
  SCREENSHOTS(setOf(FileKind.SCREENSHOT)),
  VIDEOS(setOf(FileKind.VIDEO)),
  DOCUMENTS(setOf(FileKind.DOCUMENT, FileKind.PDF)),
  AUDIO(setOf(FileKind.AUDIO)),
}

/** A file Findr knows about. `uri` is a content:// URI the UI can open, share or thumbnail. */
data class IndexedFile(
  val id: Long,
  val uri: String,
  val relativePath: String,        // e.g. "Download/WhatsApp Documents/receipt.pdf", for display
  val name: String,
  val kind: FileKind,
  val mimeType: String?,
  val sizeBytes: Long,
  val modifiedMillis: Long,
  val width: Int? = null,
  val height: Int? = null,
  val durationMs: Long? = null,
  val pageCount: Int? = null,
  /**
   * `files.sensitive` bits (index.Schema.Sensitive): 1 the person marked this file, 2 it sits in a folder they marked private.
   * Set only by them, never by a classifier. Sensitive files are hidden from results unless revealed.
   */
  val sensitiveFlags: Int = 0,
  /**
   * How private-looking the picture is, 0..1, from an on-device classifier (null: not scored, or not a picture). It is
   * only ever used to blur a preview, never to rank a result or hide a file: the classifier is biased and can be wrong.
   */
  val looksPrivate: Float? = null,
  /** The date a person would give this file: the capture date of a photo or video when it has one, else [modifiedMillis]. What the date filter and the age label use. */
  val dateMillis: Long = modifiedMillis,
) {
  val sensitive: Boolean get() = sensitiveFlags != 0
  val markedSensitive: Boolean get() = sensitiveFlags and 1 != 0
  val inPrivateFolder: Boolean get() = sensitiveFlags and 2 != 0
}

/**
 * How a list is ordered. [BEST] is the relevance order and only means something with a query (browsing treats it as [NEWEST]).
 * The others order the files a query already found (the strong matches first, then the weaker ones: a sort never lifts a weak match
 * above a strong one) or, when browsing, every file.
 */
enum class SortOrder { BEST, NEWEST, OLDEST, LARGEST, NAME }

/** A span of time on the file date: [fromMs] inclusive, [untilMs] exclusive, either side open when null. */
data class DateRange(val fromMs: Long? = null, val untilMs: Long? = null) {
  init { require(fromMs == null || untilMs == null || fromMs < untilMs) { "empty date range" } }
  fun contains(ms: Long) = (fromMs == null || ms >= fromMs) && (untilMs == null || ms < untilMs)
}

/**
 * What narrows and orders a list beyond the words typed: the sort and the date range. The default changes nothing.
 * [datePhrase] is the words the range was read from ("oct 2026") when it came from the query: a file that has those words in its
 * name or text is not hidden by the range, because the date is then part of what is looked for.
 */
data class ResultView(val sort: SortOrder = SortOrder.BEST, val dates: DateRange? = null, val datePhrase: String? = null) {
  companion object { val DEFAULT = ResultView() }
}

/** Where inside a file a hit points: a PDF page, or a moment in a video or recording. */
sealed interface Locator {
  data class Page(val page: Int) : Locator                        // 1-based
  data class Time(val startMs: Long, val endMs: Long? = null) : Locator
}

/** Why a file matched: by meaning (embedding), by its words (keyword index), or both. */
enum class MatchReason { MEANING, WORDS, BOTH }

data class SearchHit(
  val file: IndexedFile,
  val score: Double,               // for ordering only
  val reason: MatchReason,
  val snippet: String? = null,     // matched words wrapped in « » (documents, text read from images)
  val locator: Locator? = null,
)

data class SearchResults(
  val query: String,
  val hits: List<SearchHit>,
  val strongCount: Int,            // hits before this index are strong; the rest are "weaker matches" (DigUp)
  val tookMs: Long,
  val indexIncomplete: Boolean,    // true while files are still being understood: say so, don't pretend
  val complete: Boolean = true,    // false: a first, words-only answer; meaning matches are still coming
  /** Files that matched but are marked sensitive and so left out. The "N hidden" chip counts these. */
  val hiddenCount: Int = 0,
)

enum class IndexPhase { NOT_STARTED, SCANNING, INDEXING, UP_TO_DATE, PAUSED, ERROR }

/**
 * Why the index file could not be opened right now (see `IndexOpenProblems.classify`). Only a reason class: never a file name,
 * never the raw error. The app keeps trying and shows plain copy for each.
 */
enum class IndexOpenProblem { BUSY, STORAGE_FULL, IO, MEMORY, OTHER }

/** Why indexing is waiting. Each one maps to plain copy in the UI ("Waiting for the charger"…). */
enum class PauseReason { NOT_CHARGING, TOO_HOT, BATTERY_LOW, USER, NO_PERMISSION, STORAGE_LOW }

data class KindProgress(val known: Int, val understood: Int)

/**
 * The indexing meter. Two levels of "ready": a file is searchable by name and words almost at once (cheap), and
 * understood by meaning once it has been embedded (slow, overnight on a budget phone).
 */
data class IndexStatus(
  val phase: IndexPhase = IndexPhase.NOT_STARTED,
  val pauseReason: PauseReason? = null,
  val filesKnown: Int = 0,
  val filesSearchable: Int = 0,    // name + words indexed
  val filesUnderstood: Int = 0,    // embedded
  /** Files that ended as "couldn't be read" (damaged, no decoder here, too large): final, so the meter does not wait for them. Still searchable by name. */
  val filesUnreadable: Int = 0,
  val byKind: Map<FileKind, KindProgress> = emptyMap(),
  val etaSeconds: Long? = null,    // from the measured rate on this phone, null until known
  /** The phone is on a charger right now. */
  val charging: Boolean = true,
  /** Full quality is waiting for the charger (phone on battery, files still waiting, the charger is what is missing): no finish time, say so plainly. */
  val needsCharger: Boolean = false,
  /** On battery, the light work (quick look, new files) is running or allowed: the meter can say search already works by meaning. */
  val lightOnBattery: Boolean = false,
  val ratePerHour: Double? = null, // files understood per hour, measured
  val indexBytes: Long = 0,        // the index on disk (database and write-ahead log)
  /** What the index will take when every file is read, from the files counted so far (0: nothing counted yet). */
  val estimatedIndexBytes: Long = 0,
  /** The vector matrix in memory while searching, now. */
  val searchMemoryBytes: Long = 0,
  /** What the matrix will need once every file is read. */
  val estimatedSearchMemoryBytes: Long = 0,
  val modelBytes: Long = 0,        // the embedding model on disk
  val speechReady: Boolean = true, // the speech models are installed (false: the speech setting is on but has nothing to run yet)
  val currentFile: String? = null,
  val lastError: String? = null,
  /** Set while the index file cannot be opened (the phase is then [IndexPhase.ERROR]); the app retries on its own. Null once it is open. */
  val openProblem: IndexOpenProblem? = null,
  /** Files this phone could not read because its memory ran out twice on them: shown in the index sheet, retried only after an update. */
  val tooLargeFiles: Int = 0,
  /** The two passes over photos, screenshots and videos (D2). Null when quick look is off or its models aren't installed. */
  val quickLook: QuickLookProgress? = null,
  /** After a model upgrade, the files whose old vectors are being replaced: [ReembedMeter.done] of [ReembedMeter.total]. Null when none are left. */
  val reembed: ReembedMeter? = null,
) {
  /** The files that can still be understood: [filesKnown] less the ones that ended as unreadable. The denominator of "understood". */
  val filesReadable: Int get() = (filesKnown - filesUnreadable).coerceAtLeast(0)
}

/** The progressive re-embed after a model upgrade: files done of files the upgrade queued. Search works throughout. */
data class ReembedMeter(val done: Int, val total: Int)

/**
 * Photos, screenshots and videos, by how well they can be found by meaning. [ready]: by the quick model or by
 * EmbeddingGemma ("Quick look"); [full]: by EmbeddingGemma ("Full detail"). Both out of [known].
 */
data class QuickLookProgress(val known: Int, val ready: Int, val full: Int, val unreadable: Int = 0)

/**
 * "How hard should Trawl work": one choice that replaces the old pair of switches ("only while charging", "full detail on battery").
 * [BALANCED] is how Trawl has always behaved. The mapping onto the existing knobs is [com.craneai.findr.engine.LearningProfile].
 */
enum class LearningSpeed(val key: String) {
  /** Only while charging, fewer threads, rests earlier when warm. */
  GENTLE("gentle"),
  /** The measured default: the charger does the backlog, battery handles new files lightly. */
  BALANCED("balanced"),
  /** The whole backlog on battery too (the 30% and heat limits still apply); steps aside only when the phone is unlocked in another app. */
  FAST("fast");

  companion object {
    fun of(key: String?): LearningSpeed? = entries.firstOrNull { it.key == key }
  }
}

data class FindrSettings(
  /** How hard Trawl works ([LearningSpeed]). The two derived flags below are what the power policy reads. */
  val learningSpeed: LearningSpeed = LearningSpeed.BALANCED,
  val indexPhotos: Boolean = true,
  val indexScreenshots: Boolean = true,
  val indexVideos: Boolean = true,
  val indexDocuments: Boolean = true,
  val indexRecordings: Boolean = true,     // voice notes and recordings are read (words and sounds, or the audio vector): on by default, see engine/AudioPolicy
  val indexAudio: Boolean = false,         // "Deeper sound search": EmbeddingGemma's audio vector per 10 s for every recording and video, music too: slow, opt-in
  val speechToText: Boolean = true,        // on by default; works once the speech models are installed. Words + sound labels from the speech model (sherpa-onnx): see SherpaAsrEngine
  val quickLook: Boolean = true,           // on by default; works once the quick-look models are installed. A fast CPU first pass over photos (D2): see QuickLookPass
  /** Blur thumbnails that look private (PrivateLook) in the main app. On by default (a fresh install; a saved choice stays), at the stricter [PrivateLook.MAIN_APP_THRESHOLD]; the quick-search panel has its own switch and threshold. Blur only: never hides or reorders. */
  val blurPrivatePreviews: Boolean = true,
  /**
   * The "Blur private-looking previews" add-on's own switch (a pack, not in the APK): while the pack is installed and this is on,
   * the scoring pass reads photos in the background. It is not the blur setting: installing the pack must not turn the main app's blur on.
   */
  val privateScoring: Boolean = true,
  val excludedFolders: Set<String> = emptySet(),  // relative paths, e.g. "DCIM/Private"
  /** "Show sensitive results": off by default. On, marked files and files in private folders appear in results like any other. */
  val showSensitive: Boolean = false,
  /** Folders the person marked private (still searchable once revealed; unlike [excludedFolders], which are not read at all). */
  val privateFolders: Set<String> = emptySet(),
  /** Files the person marked sensitive one by one, as "volume:mediastoreId". Kept here so rebuilding the index never un-hides them. */
  val markedFiles: Set<String> = emptySet(),
  /** "What should Findr search?": folder-group id -> on/off. A group with no entry follows its default (index/FolderGroups.kt). */
  val groupOverrides: Map<String, Boolean> = emptyMap(),
  /** Folders added by hand ("+ Add a folder"); read even when their group is off. Relative paths like [excludedFolders]. */
  val includedFolders: Set<String> = emptySet(),
) {
  /** Strict: on battery, read nothing heavier than names and words (no quick look, no new files). [LearningSpeed.GENTLE]. */
  val onlyWhileCharging: Boolean get() = learningSpeed == LearningSpeed.GENTLE

  /** Full-quality indexing of the whole backlog also on battery (the battery level and heat limits still apply). [LearningSpeed.FAST]. */
  val fullOnBattery: Boolean get() = learningSpeed == LearningSpeed.FAST
}
