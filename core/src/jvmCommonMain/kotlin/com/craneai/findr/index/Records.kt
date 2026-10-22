package com.craneai.findr.index

import com.craneai.findr.core.FileKind
import com.craneai.findr.core.QuickLookProgress

/**
 * Where a file is in the pipeline. Names enter the keyword index at crawl time, so a file is `SEARCHABLE` (by name)
 * the moment the crawler has seen it; `NEW` exists for rows written without a name, and the engine never has to
 * tell the two apart when asking for work.
 */
enum class FileState(val db: String) {
  NEW("new"),
  SEARCHABLE("searchable"),
  /** Read in part (the first step of a long file); the rest is finished when nothing else is waiting. */
  PARTIAL("partial"),
  UNDERSTOOD("understood"),
  FAILED("failed"),
  SKIPPED("skipped");

  companion object {
    fun of(db: String?): FileState = entries.firstOrNull { it.db == db } ?: NEW
  }
}

enum class SegmentKind(val db: String) {
  NAME("name"), CHUNK("chunk"), PAGE("page"), IMAGE("image"), FRAME("frame"), AUDIO("audio"), OCR("ocr"),
  TRANSCRIPT("transcript"), SOUND("sound");   // what the speech model heard, and the sounds it labelled

  companion object {
    fun of(db: String?): SegmentKind = entries.firstOrNull { it.db == db } ?: NAME
  }
}

/** What produced a vector. Cosines differ in level by modality, so search centres each separately. NONE: no vector. */
enum class Modality(val db: String) {
  NONE("none"), TEXT("text"), IMAGE("image"), AUDIO("audio"),

  /** Text vectors of the "Sounds: ..." line. Their cosines sit lower than transcript ones, so they are centred apart (northstar, tags eval ii). */
  SOUND("sound"),

  /**
   * The quick-look pass (D2, D10): a 512-d TinyCLIP image vector, in its own space, scored against the query's TinyCLIP text
   * vector and kept in its own in-memory index. Held only until EmbeddingGemma understands the file, which drops it. (The
   * stored name stays "clip": a fingerprint, not the name, tells one quick-look model's rows from another's.)
   */
  CLIP("clip"),

  /**
   * The lite profile (2 GB phones, no vision encoder): the text read from a picture or video frame (OCR), embedded by
   * EmbeddingGemma's text model. Its own group, so documents keep their scale: sharing theirs cost documents 0.95 -> 0.89
   * r@1 on eval-en, as in M2 (the research notes).
   */
  OCR_TEXT("ocr_text");

  companion object {
    fun of(db: String?): Modality = entries.firstOrNull { it.db == db } ?: NONE
  }
}

/** What the crawler hands the store for one MediaStore row. `skipReason != null` records a file we won't read. */
data class CrawlRecord(
  val volume: String,
  val mediaStoreId: Long,
  val uri: String,
  val relativePath: String,       // folder, "Download/WhatsApp Documents/"
  val name: String,
  val kind: FileKind,
  val mimeType: String?,
  val sizeBytes: Long,
  val modifiedMillis: Long,
  /** The date shown and sorted on: the capture date when MediaStore has one, else [modifiedMillis]. 0 means "same as modified". */
  val dateMillis: Long = 0,
  val width: Int? = null,
  val height: Int? = null,
  val durationMs: Long? = null,
  val pageCount: Int? = null,
  val skipReason: String? = null,
)

/**
 * One searchable piece of a file, as the engine produces it.
 * `text` goes to the keyword index (and comes back for snippets); `vector` (768-d, L2-normalised, any modality) goes
 * to meaning search as fp16. `late` marks vectors from the later steps of a long file: scored like any other, but
 * kept out of the per-query scale so a book's hundreds of pages cannot shift it (DigUp learned this the hard way).
 */
class Segment(
  val kind: SegmentKind,
  val modality: Modality = Modality.NONE,
  val locStart: Long? = null,       // page number (1-based), or start ms
  val locEnd: Long? = null,
  val text: String? = null,
  val vector: FloatArray? = null,
  val late: Boolean = false,
  val marks: String? = null,        // transcript pieces: where each spoken segment sits in the text (core.TranscriptMarks)
)

/** A row of the work queue: just enough for the engine to open the file and know where it left off. */
data class PendingFile(val file: com.craneai.findr.core.IndexedFile, val state: FileState, val detail: String?)

/** A text vector to re-embed after a text-model change (IndexStore.textToRedo): the words it was made from. */
data class TextToRedo(val segmentId: Long, val fileId: Long, val text: String)

/**
 * Where a progressive re-embed stands ([IndexStore.reembedProgress]): [waiting] files per kind still hold a vector from the
 * previous model, out of [queued] files the upgrade put in the queue.
 */
data class ReembedProgress(val waiting: Map<FileKind, Int> = emptyMap(), val queued: Int = 0) {
  /** (done, total) over the kinds being read now, or null when nothing is left to replace. Files of a kind switched off are left out of both. */
  fun over(kinds: Set<FileKind>): Pair<Int, Int>? {
    val left = kinds.sumOf { waiting[it] ?: 0 }
    if (left == 0) return null
    val off = waiting.filterKeys { it !in kinds }.values.sum()
    val total = maxOf(queued - off, left)
    return (total - left) to total
  }
}

/** Counts for the index meter and the status screen. */
data class IndexStats(
  val byKindAndState: Map<FileKind, Map<FileState, Int>>,
  val segments: Int,
  val vectors: Int,
  val bytesOnDisk: Long,
  /** Files not yet understood by EmbeddingGemma that already have a quick-look vector, per kind. */
  val quickOnly: Map<FileKind, Int> = emptyMap(),
  /**
   * Files quick look tried and could not read (a "tried, nothing usable" row: no vector), per kind, that are still waiting for
   * EmbeddingGemma. Understood, skipped and failed files are left out: those are counted by their own state.
   */
  val quickUnreadable: Map<FileKind, Int> = emptyMap(),
  /** Files given up on because the process died inside them twice (see IndexStore.recoverInflight). */
  val tooLarge: Int = 0,
) {
  fun count(kind: FileKind, state: FileState): Int = byKindAndState[kind]?.get(state) ?: 0
  fun count(state: FileState): Int = byKindAndState.values.sumOf { it[state] ?: 0 }

  /** Files that still need reading, for the kinds the engine is allowed to index. */
  fun waiting(kinds: Set<FileKind>): Int = kinds.sumOf {
    count(it, FileState.NEW) + count(it, FileState.SEARCHABLE) + count(it, FileState.PARTIAL)
  }

  /** Everything not skipped. */
  val filesKnown: Int get() = byKindAndState.values.sumOf { m -> m.filter { it.key != FileState.SKIPPED }.values.sum() }
  /**
   * Name (and words, once read) are in: every file the crawler saw and kept, bar one not yet entered. A failed file stays here:
   * it keeps its name and whatever words it gave before it failed, and search lists it (a file moving to failed used to fall out of
   * this count while still being found by name, so the meter's "searchable" went down during a run).
   */
  val filesSearchable: Int get() = filesKnown - count(FileState.NEW)
  val filesUnderstood: Int get() = count(FileState.UNDERSTOOD)
  /** Files given up on for good (until an app update): damaged, a format this phone has no decoder for, or too large for its memory. */
  val filesUnreadable: Int get() = count(FileState.FAILED)
}

/**
 * The quick-look meter over [visual] kinds (photos, screenshots, videos), out of the files that can be read at all.
 *
 * Every non-skipped visual file is in exactly one place: understood by EmbeddingGemma ([QuickLookProgress.full]), holding a quick
 * vector ([QuickLookProgress.ready] includes full), still to be looked at, or one that cannot be read: quick look tried and found
 * no picture (a damaged file, a format this phone has no decoder for), or EmbeddingGemma gave up on it (state failed). Those last are
 * final, so they leave the denominator and are reported on their own ([QuickLookProgress.unreadable]); otherwise the meter stops one
 * short for every such file and never reaches 100%. Null when there is nothing visual to show.
 */
fun IndexStats.quickLookProgress(visual: Set<FileKind>): QuickLookProgress? {
  val known = visual.sumOf { k -> byKindAndState[k]?.filter { it.key != FileState.SKIPPED }?.values?.sum() ?: 0 }
  if (known == 0) return null
  val full = visual.sumOf { count(it, FileState.UNDERSTOOD) }
  val unreadable = visual.sumOf { (quickUnreadable[it] ?: 0) + count(it, FileState.FAILED) }
  return QuickLookProgress(known - unreadable, full + visual.sumOf { quickOnly[it] ?: 0 }, full, unreadable)
}
