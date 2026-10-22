package com.craneai.findr.core

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow

/** What the UI talks to. The real implementation lives in `engine`; `ui/fake` has a stand-in with sample data. */
interface SearchEngine {
  val status: StateFlow<IndexStatus>
  val settings: StateFlow<FindrSettings>

  /** Optional model packs (Settings → Add-ons, and the onboarding step). The fake engine has none. */
  val packs: PackManager get() = NoPacks

  /** Getting the model files onto the phone: the signed bundle, its first-run card and the update notice (core/Delivery.kt). */
  val delivery: DeliveryService get() = NoDelivery

  /** Meaning + words, fused (DigUp's method). Works on a partial index: name/word hits first, meaning fills in. */
  suspend fun search(query: String, filter: KindFilter = KindFilter.ALL, limit: Int = 60, view: ResultView = ResultView.DEFAULT): SearchResults

  /**
   * Whether any of [files] has every content word of [phrase] in its text or name. The search screen asks it when a date phrase read
   * from the query leaves nothing: a date written in the files ("your PAYE return for September ...") is part of what is looked for,
   * not a filter. An engine without an index says no.
   */
  suspend fun anyMentions(phrase: String, files: List<IndexedFile>): Boolean = false

  /**
   * The dates of the [limit] newest files dated no later than [notAfterMs], newest first: the evidence [ClockSense] weighs against the
   * phone's clock ("in October" on a phone whose clock reads August, with files dated up to October). An engine without an index has none.
   */
  suspend fun newestDates(limit: Int, notAfterMs: Long): List<Long> = emptyList()

  /**
   * "Find similar" on one picture or video: the files whose vectors sit closest to this file's own, in the same vector space (the
   * file's stored vectors are the query). While EmbeddingGemma has understood under half of the pictures the quick-look space is
   * the one searched, and a seed without a quick-look vector gets one made (once, ~0.1 s). The file itself is not in the answer. [filter], [limit] and [view]
   * mean what they mean for [search]; so does the hidden-file rule. Answers with no hits when no picture vector can be had for it.
   * `query` of the result is the file's name.
   */
  suspend fun similar(file: IndexedFile, filter: KindFilter = KindFilter.ALL, limit: Int = 60, view: ResultView = ResultView.DEFAULT): SearchResults =
    SearchResults(file.name, emptyList(), 0, 0, indexIncomplete = false)

  /**
   * The same search, answered in steps: word matches at once (no model needed), then the fused result once the query
   * has been embedded. While the indexer is understanding an image the model is busy for up to ~5 s on a budget
   * phone, and search must not freeze for that. The last emission has `complete = true`.
   *
   * [meaningDelayMs] is for type-to-filter: the words answer comes at once on every keystroke, and the meaning step (the
   * query embed) starts only after that pause, so cancelling the flow on the next keystroke wastes no model call.
   */
  fun searchProgressive(query: String, filter: KindFilter = KindFilter.ALL, limit: Int = 60, meaningDelayMs: Long = 0, view: ResultView = ResultView.DEFAULT): Flow<SearchResults> =
    flow { emit(search(query, filter, limit, view)) }

  /**
   * The user is about to search (the search box got focus): get the query model ready now, so the ~0.7 s load on a
   * budget phone overlaps their typing instead of following it. The claude.ai speed work did the same by prefetching
   * on hover. Cheap and idempotent; it does nothing if the model is already loaded.
   */
  fun prewarm() {}

  /**
   * All files access was just turned on (onboarding): look in Downloads now for the model and the default packs (the
   * one-file bundle or the separate files) and install them, so they are ready by the time the person taps Start.
   */
  fun filesAccessGranted() {}

  /**
   * The user is scrolling or typing: background indexing pauses between files until a moment after it stops, so the
   * grid gets the CPU (Google AI Edge Gallery's SmartAlbumIndexingCoordinator does the same; the research notes).
   */
  fun setUserInteracting(active: Boolean) {}

  /**
   * A scrollable grid or result list is on screen (true) or gone or covered (false), balanced per list. While one is, the
   * index worker starts no GPU image encode: an encode can't be interrupted, and on a budget GPU it freezes the list
   * (the research notes). The progress meter and onboarding screens don't count, so a first run never looks stuck.
   */
  fun setListVisible(visible: Boolean) {}

  /**
   * What the home screen shows before anything is typed: the newest files of a kind. [view] reorders it ([SortOrder.BEST]
   * means newest) and narrows it to a date range.
   */
  suspend fun recent(filter: KindFilter = KindFilter.ALL, limit: Int = 60, view: ResultView = ResultView.DEFAULT): List<IndexedFile>

  /**
   * Sensitive files (marked by the person, or inside a folder they marked private) are left out of [search] and [recent]
   * unless [FindrSettings.showSensitive] is on or the person revealed them for this visit ([setSensitiveRevealed], which
   * the privacy gate calls after the phone's own unlock). Every caller, the quick panel included, gets this for free.
   */
  val sensitiveRevealed: StateFlow<Boolean> get() = NEVER_REVEALED

  /**
   * Forget the query vectors kept in memory for speed (queries are sensitive). Called when the app lock closes and when the
   * system trims memory; deleting the index does it too.
   */
  fun clearQueryCache() {}

  /** The system trimmed memory ([android.content.ComponentCallbacks2.onTrimMemory]); the indexer gives its models back on real pressure. */
  fun noteMemoryTrim(level: Int) {}

  /**
   * True while the embedding engine has failed its self-test even after a fresh cache (EngineSelfTest): search answers from words
   * alone and the screen says "Search is repairing itself". Goes false again by itself once the engine passes.
   */
  val searchRepairing: StateFlow<Boolean> get() = NOT_REPAIRING

  /** The privacy gate reveals sensitive results (after the device credential) or hides them again. */
  fun setSensitiveRevealed(revealed: Boolean) {}

  /** How many sensitive files [recent] leaves out right now, for the "N hidden" chip on the home list. */
  suspend fun recentHiddenCount(filter: KindFilter = KindFilter.ALL, dates: DateRange? = null): Int = 0

  /** "Mark as sensitive" / "Not sensitive" on one file. */
  suspend fun setSensitive(file: IndexedFile, sensitive: Boolean) {}

  /**
   * "What should Findr search?": how many files each folder group holds (group id to count), as the phone has them now
   * and independent of the switches, so a group that is off still shows what turning it on would add. Empty when the
   * phone cannot be listed yet (no file access).
   */
  suspend fun folderCounts(): Map<String, Int> = emptyMap()

  fun startIndexing()
  fun pauseIndexing()
  fun resumeIndexing()
  /** "Delete everything Findr knows": the index, caches and temporary files; indexing stays paused until resumed. */
  suspend fun deleteIndex(): Forgotten
  suspend fun updateSettings(transform: (FindrSettings) -> FindrSettings)
}

private val NEVER_REVEALED: StateFlow<Boolean> = kotlinx.coroutines.flow.MutableStateFlow(false)
private val NOT_REPAIRING: StateFlow<Boolean> = kotlinx.coroutines.flow.MutableStateFlow(false)

// ---- Extraction data (package `extract` reads and writes these; the Android-typed seams that return them stay in :app) ----

/** Text from a file, page by page (a PDF's pages; a single page for plain documents). */
data class ExtractedText(val pages: List<String>, val truncated: Boolean = false)

/**
 * A picture, video or sound the phone's own decoders could not open (no codec for the format, or damaged beyond reading).
 * The worker marks the file FAILED with [com.craneai.findr.index.IndexStore.CANT_OPEN]: it stays searchable by name, is
 * not retried until the app is updated, and the queue moves on. [why] is for the log only (never a file name).
 */
class FormatNotReadable(val why: String, cause: Throwable? = null) : java.io.IOException(why, cause)

/** A window of a soundtrack as 16 kHz mono 16-bit WAV bytes, for the audio encoder. */
class AudioWindow(val startMs: Long, val endMs: Long, val wav: ByteArray)

/** One stretch of speech: when it starts and ends in the file, and what was said. */
class SpeechSegment(val startMs: Long, val endMs: Long, val text: String)

/** What a soundtrack is made of, in words: the speech, and the AudioSet sound labels heard in each window. */
class SoundTags(val startMs: Long, val endMs: Long, val labels: List<String>)

class AudioTranscript(val speech: List<SpeechSegment>, val sounds: List<SoundTags>, val seconds: Double)
