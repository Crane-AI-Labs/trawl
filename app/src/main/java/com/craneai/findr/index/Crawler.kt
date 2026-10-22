package com.craneai.findr.index

import android.content.Context
import android.provider.MediaStore
import com.craneai.findr.core.FileKind
import com.craneai.findr.core.FindrSettings
import java.io.File

/** One row of `MediaStore.Files`, reduced to what indexing needs. Times are as MediaStore stores them. */
data class MediaRow(
  val volume: String,
  val id: Long,
  val uri: String,
  val name: String,
  val relativePath: String,     // "Download/WhatsApp Documents/" ("" at the volume root)
  val mimeType: String?,
  val sizeBytes: Long,
  val dateModifiedSec: Long,
  val width: Int?,
  val height: Int?,
  val durationMs: Long?,
  val absolutePath: String?,    // for the .nomedia check; needs all-files access
  val dateTakenMs: Long = 0,    // MediaStore DATE_TAKEN (EXIF capture time, ms); 0 when unknown
)

/** Where file facts come from. The real one is MediaStore; tests feed a fake so the sync logic runs on the JVM. */
interface MediaSource {
  fun volumes(): List<String>
  /** Changes when MediaStore rebuilt its database (generations may have restarted): then everything is read again. */
  fun version(volume: String): String?
  /** The journal position: every change bumps it, and rows carry the generation they were last modified at. */
  fun generation(volume: String): Long
  /** Rows modified after [sinceGeneration] (all rows for a negative value). */
  fun forEachChanged(volume: String, sinceGeneration: Long, sink: (MediaRow) -> Unit)
  /** Every id on the volume, for finding deletes (MediaStore cannot list them). */
  fun allIds(volume: String): LongArray
}

data class SyncReport(
  val scanned: Int = 0,
  val added: Int = 0,
  val changed: Int = 0,
  val unchanged: Int = 0,
  val skipped: Int = 0,
  /** Ids of files removed from the index, so the engine can drop their vectors from memory. */
  val deletedFileIds: List<Long> = emptyList(),
  /** Ids of files that stay on the list but were taken out of the search (their folder was switched off or excluded). */
  val newlySkippedFileIds: List<Long> = emptyList(),
  val fullResync: Boolean = false,
  /** Ids of files whose content changed: the index dropped their segments, so the engine drops their old vectors from memory. */
  val resetFileIds: List<Long> = emptyList(),
)

/**
 * Keeps the index's file list in step with the phone, using MediaStore as the change journal (Android's USN):
 * after the first full read, only rows with `generation_modified` past the last seen position are visited. A
 * different MediaStore version means the generations may have restarted, so everything is read again. Deletes are
 * found by diffing ids, because MediaStore can't list them. Names go into the keyword index as files arrive, so
 * the phone is searchable by name right after the first pass.
 */
class Crawler(
  private val store: IndexStore,
  private val source: MediaSource,
  private val settings: () -> FindrSettings = { FindrSettings() },
  /** True if this absolute folder holds a `.nomedia`. */
  private val hasNoMedia: (String) -> Boolean = { File(it, ".nomedia").exists() },
  private val batchSize: Int = 500,
  private val log: (String) -> Unit = {},
  /** The first [Rules.SNIFF_BYTES] bytes of a file by absolute path, or null if unreadable. Only asked for files whose name and type told us nothing. */
  private val header: (String) -> ByteArray? = ::readHeader,
  /**
   * Reads the capture date out of the picture itself (EXIF), for pictures MediaStore has no date for. Null in tests that don't
   * need it. The first sync with a reader is a full one, so files indexed before it existed get their dates ([EXIF_DATES_KEY]).
   */
  private val exifDate: ((MediaRow) -> Long)? = null,
) {
  /**
   * Runs one pass over every mounted volume. Cheap when nothing changed (two numbers compared per volume).
   * [forceFull] re-reads every row, for when skip rules changed (excluded folders) or as a periodic safety net.
   *
   * [pruneOnly] is the pass while the person has paused Findr (or just deleted everything it knew): it may only take
   * things out of the index, never put anything in. Files gone from the phone are removed, and files that now sit in a
   * skipped folder lose what was read from them. Nothing is added, renamed or re-read, and the journal position is not
   * moved on, so the first full pass after Resume still sees every change made during the pause.
   */
  fun sync(forceFull: Boolean = false, pruneOnly: Boolean = false): SyncReport {
    var report = SyncReport()
    for (volume in source.volumes()) report = merge(report, syncVolume(volume, forceFull, pruneOnly))
    return report
  }

  private fun syncVolume(volume: String, forceFull: Boolean, pruneOnly: Boolean): SyncReport {
    val version = source.version(volume)
    val generation = source.generation(volume)   // read before the query, so a change during it is picked up next time
    val storedVersion = store.meta(Schema.Meta.mediaVersion(volume))
    val storedGeneration = store.meta(Schema.Meta.mediaGeneration(volume))?.toLongOrNull()
    val exifBackfill = exifDate != null && !pruneOnly && store.meta(EXIF_DATES_KEY) != EXIF_DATES_VERSION
    val full = forceFull || storedGeneration == null || version != storedVersion || exifBackfill
    // Paused: deletions can't be read off the journal, so the id diff below runs every time; it is one id query.
    if (!full && generation == storedGeneration && !pruneOnly) return SyncReport()

    val current = settings()
    val excluded = current.excludedFolders
    val scope = current.folderScope()
    val noMediaCache = HashMap<String, Boolean>()
    val cachedNoMedia: (String) -> Boolean = { dir -> noMediaCache.getOrPut(dir) { runCatching { hasNoMedia(dir) }.getOrDefault(false) } }

    var totals = IndexStore.UpsertResult()
    var scanned = 0
    val batch = ArrayList<CrawlRecord>(batchSize)
    fun flush() {
      if (batch.isEmpty()) return
      totals += if (pruneOnly) store.forgetNowSkipped(batch) else store.upsertFromCrawl(batch)
      batch.clear()
    }
    // A prune pass with no stored position (an empty index, as after "Delete everything") has nothing to take out.
    if (!pruneOnly || storedGeneration != null) {
      source.forEachChanged(volume, if (full) -1 else (storedGeneration ?: -1)) { row ->
        scanned++
        // The same rules either way (exclusions, folder scope, private places, .nomedia). Paused, only what a rule now
        // covers goes on, to the same "now skipped: forget what was read" step a normal pass uses (IndexStore.upsertOne).
        toRecord(row, excluded, cachedNoMedia, scope, exifDate)?.let { if (!pruneOnly || it.skipReason != null) batch += it }
        if (batch.size >= batchSize) flush()
      }
      flush()
    }

    // Deletes: ids the store has that MediaStore no longer lists. An empty listing while we hold files looks like
    // an unmounted volume or a lost permission, not a wiped phone, so it deletes nothing.
    val present = source.allIds(volume)
    val known = store.knownMediaStoreIds(volume)
    val deleted = if (present.isEmpty() && known.isNotEmpty()) {
      log("MediaStore listed no files on $volume but the index has ${known.size}; keeping them")
      emptyList()
    } else store.deleteMissing(volume, present.toHashSet())

    if (!pruneOnly) {
      store.setMeta(Schema.Meta.mediaVersion(volume), version)
      store.setMeta(Schema.Meta.mediaGeneration(volume), generation.toString())
      if (exifBackfill) store.setMeta(EXIF_DATES_KEY, EXIF_DATES_VERSION)
    }
    log("synced $volume: scanned $scanned, +${totals.added} ~${totals.changed} =${totals.unchanged} skipped ${totals.skipped}, deleted ${deleted.size}" +
      (if (full) " (full)" else "") + (if (totals.newlySkipped.isNotEmpty()) ", taken out of search ${totals.newlySkipped.size}" else "") +
      if (pruneOnly) " (paused: removals only)" else "")
    return SyncReport(scanned, totals.added, totals.changed, totals.unchanged, totals.skipped, deleted, totals.newlySkipped, full, totals.reset)
  }

  /**
   * The date a person would give this file, in this order: MediaStore's DATE_TAKEN for pictures and videos when it is set and
   * plausible (after 1990, not in the future); else the picture's own EXIF capture date ([exifMs], read by the caller only
   * when needed, see [needsExif]); else the modified time (documents and recordings have no capture date).
   */
  internal fun effectiveDate(row: MediaRow, nowMs: Long = System.currentTimeMillis(), exifMs: Long = 0): Long {
    val modified = row.dateModifiedSec * 1000
    val visual = row.mimeType?.let { it.startsWith("image/") || it.startsWith("video/") } == true
    if (!visual) return modified
    if (hasRealTaken(row, nowMs)) return row.dateTakenMs
    return if (exifMs > CaptureDate.MIN_PLAUSIBLE_MS && exifMs <= nowMs + 86_400_000L) exifMs else modified
  }

  /** MediaStore's DATE_TAKEN is set, plausible, and not just the file time copied into the column (some builds do that). */
  private fun hasRealTaken(row: MediaRow, nowMs: Long): Boolean {
    val taken = row.dateTakenMs
    return taken > CaptureDate.MIN_PLAUSIBLE_MS && taken <= nowMs + 86_400_000L && Math.abs(taken - row.dateModifiedSec * 1000) > 2_000
  }

  /** Whether [effectiveDate] needs the picture's EXIF: an image that can hold some, with no real DATE_TAKEN to go by. */
  internal fun needsExif(row: MediaRow, nowMs: Long = System.currentTimeMillis()): Boolean =
    CaptureDate.mayHaveExif(row.mimeType) && !hasRealTaken(row, nowMs)

  /** Null for rows that are not files (folders). Everything else becomes a record, skipped or not. */
  internal fun toRecord(
    row: MediaRow, excluded: Set<String>, noMedia: (String) -> Boolean, scope: FolderScope = FolderScope(),
    exif: ((MediaRow) -> Long)? = null,
  ): CrawlRecord? {
    // No type and no extension: a folder, or a file saved without a name ending ("IMG_0001", "notes"). Only a readable file counts.
    val firstBytes = if (row.mimeType == null && Rules.extensionOf(row.name).isEmpty()) (row.absolutePath?.let(header) ?: return null) else null
    var kind = Rules.classify(row.name, row.mimeType, row.relativePath)
    var reason = Rules.skipReason(row.relativePath, row.name, kind, row.sizeBytes, row.width, row.height, excluded, scope = scope)
    // A file the name and type could not place (no extension, or one nobody knows) is recognised by its first bytes, so
    // a photo saved as "IMG_0001" or a video renamed ".dat" is still attempted. Skipped files are never opened.
    if (reason == null && row.absolutePath != null && Rules.shouldSniff(row.name, kind)) {
      val sniffed = (firstBytes ?: header(row.absolutePath))?.let { Rules.sniffKind(it, extensionless = Rules.extensionOf(row.name).isEmpty()) }
      if (sniffed != null && sniffed != FileKind.OTHER) {
        kind = if ((sniffed == FileKind.IMAGE) && Rules.isScreenshot(row.relativePath, row.name)) FileKind.SCREENSHOT else sniffed
        reason = Rules.skipReason(row.relativePath, row.name, kind, row.sizeBytes, row.width, row.height, excluded, scope = scope)
      }
    }
    if (reason == null) {
      val folder = row.absolutePath?.substringBeforeLast('/', "")
      if (!folder.isNullOrEmpty() && Rules.underNoMedia(folder, noMedia)) reason = ".nomedia folder"
    }
    return CrawlRecord(
      volume = row.volume, mediaStoreId = row.id, uri = row.uri, relativePath = row.relativePath, name = row.name,
      kind = kind, mimeType = row.mimeType, sizeBytes = row.sizeBytes, modifiedMillis = row.dateModifiedSec * 1000,
      // EXIF only for a file that will be searched (a skipped one's date is never shown) and that MediaStore has no date for.
      dateMillis = effectiveDate(row, exifMs = if (exif != null && reason == null && needsExif(row)) exif(row) else 0),
      width = row.width?.takeIf { it > 0 }, height = row.height?.takeIf { it > 0 },
      durationMs = row.durationMs?.takeIf { it > 0 }, skipReason = reason,
    )
  }

  companion object {
    /** Set once a full sync has run with the EXIF reader on: files indexed before it existed have their capture dates by then. */
    const val EXIF_DATES_KEY = "exif_dates"
    const val EXIF_DATES_VERSION = "1"
  }

  private fun merge(a: SyncReport, b: SyncReport) = SyncReport(
    a.scanned + b.scanned, a.added + b.added, a.changed + b.changed, a.unchanged + b.unchanged, a.skipped + b.skipped,
    a.deletedFileIds + b.deletedFileIds, a.newlySkippedFileIds + b.newlySkippedFileIds, a.fullResync || b.fullResync, a.resetFileIds + b.resetFileIds,
  )

  /**
   * How many files each folder group holds, for the "What should Findr search?" screen: what the phone has that
   * Findr would read if the group were on (private places, junk and excluded folders already left out). Reads
   * MediaStore only, so it works before anything is indexed and does not depend on the switches.
   */
  fun countByGroup(): Map<String, Int> {
    val excluded = settings().excludedFolders
    val noMediaCache = HashMap<String, Boolean>()
    val cachedNoMedia: (String) -> Boolean = { dir -> noMediaCache.getOrPut(dir) { runCatching { hasNoMedia(dir) }.getOrDefault(false) } }
    val counts = HashMap<String, Int>()
    for (volume in source.volumes()) {
      source.forEachChanged(volume, -1) { row ->
        val record = toRecord(row, excluded, cachedNoMedia, FolderScope.EVERYTHING) ?: return@forEachChanged
        if (record.skipReason != null) return@forEachChanged
        val id = FolderGroups.groupOf(record.relativePath, record.kind).id
        counts[id] = (counts[id] ?: 0) + 1
      }
    }
    return counts
  }
}

/** The first [Rules.SNIFF_BYTES] bytes of a file, or null when it cannot be opened (permission, vanished). */
internal fun readHeader(path: String): ByteArray? = try {
  java.io.FileInputStream(path).use { input ->
    val buf = ByteArray(Rules.SNIFF_BYTES)
    var n = 0
    while (n < buf.size) {
      val r = input.read(buf, n, buf.size - n)
      if (r < 0) break
      n += r
    }
    if (n == buf.size) buf else buf.copyOf(n)
  }
} catch (e: java.io.IOException) {
  null
} catch (e: SecurityException) {
  null
}

/**
 * MediaStore.Files on every mounted external volume. With MANAGE_EXTERNAL_STORAGE it lists non-media files too
 * (PDFs, docx); with only media permissions it lists just that media, and the same code still works.
 */
class MediaStoreSource(private val context: Context) : MediaSource {
  private val resolver get() = context.contentResolver

  override fun volumes(): List<String> = MediaStore.getExternalVolumeNames(context).toList().sorted()

  override fun version(volume: String): String? = runCatching { MediaStore.getVersion(context, volume) }.getOrNull()

  override fun generation(volume: String): Long = runCatching { MediaStore.getGeneration(context, volume) }.getOrDefault(0L)

  override fun forEachChanged(volume: String, sinceGeneration: Long, sink: (MediaRow) -> Unit) {
    val collection = MediaStore.Files.getContentUri(volume)
    val projection = arrayOf(
      MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.RELATIVE_PATH,
      MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED,
      MediaStore.MediaColumns.WIDTH, MediaStore.MediaColumns.HEIGHT, MediaStore.MediaColumns.DURATION,
      @Suppress("DEPRECATION") MediaStore.MediaColumns.DATA, MediaStore.MediaColumns.DATE_TAKEN,
    )
    // Added or modified since the last pass (the research notes: GENERATION_ADDED too, not only modified).
    val selection = if (sinceGeneration >= 0)
      "${MediaStore.MediaColumns.GENERATION_MODIFIED} > ? OR ${MediaStore.MediaColumns.GENERATION_ADDED} > ?" else null
    val args = if (sinceGeneration >= 0) arrayOf(sinceGeneration.toString(), sinceGeneration.toString()) else null
    resolver.query(collection, projection, selection, args, "${MediaStore.MediaColumns._ID} ASC")?.use { cur ->
      while (cur.moveToNext()) {
        val id = cur.getLong(0)
        sink(
          MediaRow(
            volume = volume, id = id,
            uri = android.content.ContentUris.withAppendedId(collection, id).toString(),
            name = cur.getString(1) ?: continue,
            relativePath = cur.getString(2) ?: "",
            mimeType = cur.getString(3),
            sizeBytes = if (cur.isNull(4)) -1 else cur.getLong(4),   // -1: unknown, 0: empty
            dateModifiedSec = cur.getLong(5),
            width = if (cur.isNull(6)) null else cur.getInt(6),
            height = if (cur.isNull(7)) null else cur.getInt(7),
            durationMs = if (cur.isNull(8)) null else cur.getLong(8),
            absolutePath = cur.getString(9),
            dateTakenMs = if (cur.isNull(10)) 0 else cur.getLong(10),
          ),
        )
      }
    }
  }

  override fun allIds(volume: String): LongArray {
    val out = ArrayList<Long>()
    resolver.query(MediaStore.Files.getContentUri(volume), arrayOf(MediaStore.MediaColumns._ID), null, null, null)?.use { cur ->
      while (cur.moveToNext()) out += cur.getLong(0)
    }
    return out.toLongArray()
  }
}
