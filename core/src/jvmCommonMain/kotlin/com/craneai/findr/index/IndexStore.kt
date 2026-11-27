package com.craneai.findr.index

import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.craneai.findr.core.ClockSense
import com.craneai.findr.core.DateRange
import com.craneai.findr.core.FileKind
import com.craneai.findr.core.IndexedFile
import com.craneai.findr.core.SortOrder
import com.craneai.findr.core.StorageEstimate
import com.craneai.findr.engine.QuickMix
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * The on-disk index: files, their segments (fp16 vectors) and an FTS5 table, in one WAL-mode SQLite file.
 *
 * One writer connection (serialised by a lock) and a small pool of read connections, so searching never waits for
 * the indexer's transactions. Every mutation is a single transaction: a kill at any point costs at most the file
 * being saved. The index is a cache of what is on the phone, so if it cannot be opened it is moved aside and rebuilt.
 *
 * Uses the bundled SQLite driver only: the phone's own SQLite has no FTS5.
 */
class IndexStore private constructor(
  val file: File,
  private val writer: Db,
  private val readers: ArrayBlockingQueue<Db>,
  private val dim: Int,
  private val log: (String) -> Unit,
  /** Dimension of [Modality.CLIP] vectors (the quick-look pass); every other modality is [dim]. */
  private val quickDim: Int = QUICK_DIM,
  cleanClose: Boolean = false,
) {
  private fun dimOf(modality: Modality) = if (modality == Modality.CLIP) quickDim else dim

  /** The time that decides which files are recent ([Schema.priority]); tests set it. */
  @Volatile var clock: () -> Long = System::currentTimeMillis
  private val writeLock = ReentrantLock()
  @Volatile private var closed = false
  @Volatile private var cleanCloseMarked = cleanClose
  /** What the person has marked private; stamped onto rows as the crawler writes them. Persisted by the engine, not here. */
  @Volatile var rules: PrivacyRules = PrivacyRules.NONE
  /**
   * The speech pack in use, as its stamp `<pack_id>@<pack_version>` (engine/SpeechPack.kt), or null when none is: while set, the
   * soundtrack catch-up also queues the not-English voice notes and videos that were not read with it ([SoundtrackCatchUp.PACK_KEY]),
   * and its saves stamp them. Set by the engine before each run; validated as one token.
   */
  @Volatile var speechPackStamp: String? = null
    set(v) { field = v?.takeIf { s -> s.isNotEmpty() && s.all { it.isLetterOrDigit() || it == '-' || it == '@' || it == '.' } } }
  @Volatile private var rulesDirty = false
    private set
  @Volatile private var modelFingerprint: String = writer.text("SELECT value FROM meta WHERE key = ?", Schema.Meta.MODEL_FINGERPRINT) ?: ""

  // ---- connections ---------------------------------------------------------------------------------------------

  /** Clears the clean-close marker before the first mutation after open (caller must hold [writeLock]). */
  private fun markDirty() {
    if (cleanCloseMarked) {
      setMetaIn(writer, Schema.Meta.CLEAN_CLOSE, "0")
      cleanCloseMarked = false
    }
  }

  fun <T> write(block: (Db) -> T): T = writeLock.withLock {
    markDirty()
    try { block(writer) } finally { changes.incrementAndGet() }
  }

  /** Runs the full table + FTS5 integrity check on demand (e.g. asynchronously after a `fastOpen`). */
  fun verifyIntegrity() = writeLock.withLock {
    if (IndexRecovery.check(writer, log)) {
      try {
        setMetaIn(writer, Schema.Meta.CLEAN_CLOSE, "0")
        cleanCloseMarked = false
      } finally {
        changes.incrementAndGet()
      }
    }
  }

  /** How many writes this store has finished. A count that has not moved since a value was worked out means that value is still true ([cached]). */
  private val changes = java.util.concurrent.atomic.AtomicLong()
  val changeCount: Long get() = changes.get()
  private val cache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Any?>>()

  /**
   * [block]'s answer from a read connection, worked out again only after a write has finished since it was last worked out. The count is
   * taken before the read, so a write that lands during it makes the next call work it out again: a cached answer is never newer than its
   * count, only possibly older than the data by a write that was still running, which the count then already shows.
   */
  @Suppress("UNCHECKED_CAST")
  fun <T> cached(key: String, block: (Db) -> T): T {
    val c = changes.get()
    cache[key]?.let { if (it.first == c) return it.second as T }
    val v = read(block)
    cache[key] = c to v
    return v
  }

  /** Runs [block] on a pooled read connection. Safe from any thread; blocks only if every reader is busy. */
  fun <T> read(block: (Db) -> T): T {
    var db: Db? = null
    while (db == null) {
      if (closed) throw IllegalStateException("IndexStore is closed")
      db = readers.poll() ?: readers.poll(50, java.util.concurrent.TimeUnit.MILLISECONDS)
    }
    if (closed) {
      runCatching { db.close() }
      throw IllegalStateException("IndexStore is closed")
    }
    try {
      return block(db)
    } finally {
      if (closed) {
        runCatching { db.close() }
      } else {
        readers.put(db)
        if (closed) {
          while (true) runCatching { (readers.poll() ?: break).close() }
        }
      }
    }
  }

  /**
   * Incrementally merges up to [pages] b-tree pages of FTS5 inverted-index segments (`INSERT INTO fts(fts, rank) VALUES('merge', ?)`).
   * Non-blocking (`tryLock`): skips immediately when another write holds [writeLock] or the store is closed, and WAL mode
   * keeps concurrent read searches completely unblocked. Returns true when a merge slice executed cleanly.
   */
  fun mergeFts(pages: Int = 64): Boolean {
    if (pages <= 0 || closed) return false
    if (!writeLock.tryLock()) return false
    return try {
      if (closed) return false
      markDirty()
      runCatching { writer.run("INSERT INTO fts(fts, rank) VALUES('merge', ?)", pages) }.isSuccess
    } finally {
      writeLock.unlock()
    }
  }

  fun close() {
    closed = true
    writeLock.withLock {
      // Bounded planner statistics refresh (400 rows/index) so sqlite_stat1 stays accurate for multi-join query plans.
      runCatching {
        writer.exec("PRAGMA analysis_limit = 400")
        writer.exec("PRAGMA optimize")
      }
      if (!cleanCloseMarked) {
        if (runCatching { setMetaIn(writer, Schema.Meta.CLEAN_CLOSE, "1") }.isSuccess) {
          cleanCloseMarked = true
        }
      }
      runCatching { writer.query("PRAGMA wal_checkpoint(TRUNCATE)") { } }
      runCatching { writer.close() }
    }
    while (true) runCatching { (readers.poll() ?: return).close() }
  }

  // ---- meta ----------------------------------------------------------------------------------------------------

  fun meta(key: String): String? = read { it.text("SELECT value FROM meta WHERE key = ?", key) }

  fun setMeta(key: String, value: String?) = write { db ->
    if (value == null) db.run("DELETE FROM meta WHERE key = ?", key)
    else db.run("INSERT INTO meta(key, value) VALUES(?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value", key, value)
  }

  /**
   * Tells the store which model and settings produced (or will produce) the vectors. If it differs from what the
   * index was built with, every file is queued to be understood again; names and words from extraction are kept
   * out too, since chunking may have changed. Returns true when that happened. Names are untouched.
   *
   * With [keepStale] (the tuned model, whose image vectors sit close to the old ones) nothing is deleted: every image, frame
   * and audio vector is marked [Schema.Late.STALE] and stays searchable, and only the files that hold one go back in the
   * queue, with their words kept (the words pass would otherwise replace the stale vectors before the new ones exist). Each
   * file's new read then replaces its stale vectors in one transaction ([saveSegments]), so photos are never missing from
   * search. Text vectors are left to [ensureTextFingerprint], which is what a text-model change is. [Schema.Meta.REEMBED_TOTAL]
   * records how many files were queued, for the meter.
   *
   * The first call on a fresh install has nothing to re-read, but the first crawl already stamped the files with an empty
   * model name, so they are restamped here too. Without that the second crawl saw every file as changed, threw away what
   * had been read and queued it all again (255 files "changed", 26 understood falling to 2).
   */
  fun ensureModelFingerprint(fingerprint: String, keepStale: Boolean = false): Boolean = write { db ->
    val old = db.text("SELECT value FROM meta WHERE key = ?", Schema.Meta.MODEL_FINGERPRINT)
    modelFingerprint = fingerprint
    if (old == fingerprint) return@write false
    db.transaction {
      if (old != null) {
        if (keepStale) {
          db.run("DELETE FROM segments WHERE kind = 'sees'")
          db.run("UPDATE segments SET late = ${Schema.Late.STALE} WHERE vec IS NOT NULL AND modality IN ('${Modality.IMAGE.db}', '${Modality.AUDIO.db}')")
          db.run(
            "UPDATE files SET state = 'searchable', detail = ?, info = NULL, indexed_at = NULL WHERE state IN ('understood', 'partial') AND $HAS_STALE",
            WORDS_DONE,
          )
          val queued = db.long("SELECT COUNT(*) FROM files WHERE state IN ('new', 'searchable', 'partial') AND $HAS_STALE") ?: 0
          setMetaIn(db, Schema.Meta.REEMBED_TOTAL, queued.toString())
        } else {
          // Quick-look vectors come from another model and stay valid; they keep photos searchable until they are understood again.
          db.run("DELETE FROM segments WHERE kind != 'name' AND modality != 'clip'")
          db.run("UPDATE files SET state = 'searchable', detail = NULL, info = NULL, indexed_at = NULL WHERE state NOT IN ('skipped', 'new')")
        }
      }
      db.run("UPDATE files SET fingerprint = size || ':' || mtime || ':' || ?", fingerprint)
      db.run("INSERT INTO meta(key, value) VALUES(?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value", Schema.Meta.MODEL_FINGERPRINT, fingerprint)
    }
    old != null
  }

  /**
   * The progressive re-embed after a model upgrade ([ensureModelFingerprint] with `keepStale`): per kind, the files that still
   * hold a stale vector and wait to be read again, and how many files the upgrade queued in all. A file that failed or was
   * switched off is not waiting. Empty once every stale vector has been replaced.
   */
  fun reembedProgress(): ReembedProgress = read { db ->
    val waiting = HashMap<FileKind, Int>()
    db.query("SELECT kind, COUNT(*) FROM files WHERE state IN ('new', 'searchable', 'partial') AND $HAS_STALE GROUP BY kind") {
      val kind = runCatching { FileKind.valueOf(it.getText(0)) }.getOrNull() ?: return@query
      waiting[kind] = it.getLong(1).toInt()
    }
    ReembedProgress(waiting, db.text("SELECT value FROM meta WHERE key = ?", Schema.Meta.REEMBED_TOTAL)?.toIntOrNull() ?: 0)
  }

  /**
   * The text model's own fingerprint, beside [ensureModelFingerprint] (which then names the image and audio space).
   * When only the text model changes (a language-tuned backbone trained to keep image and audio vectors where they
   * were, D4), photos and video frames keep their vectors: documents are queued again (their words and vectors stay
   * until then), and the text vectors of recordings and videos (transcripts, sound lines) are re-embedded in place from
   * their stored words, through [textToRedo] and [replaceTextVectors]. An index without the key records it and does
   * nothing. Returns (documents queued, segments to re-embed).
   */
  fun ensureTextFingerprint(fingerprint: String): Pair<Int, Int> = write { db ->
    val old = db.text("SELECT value FROM meta WHERE key = ?", Schema.Meta.TEXT_FINGERPRINT)
    if (old == fingerprint) return@write 0 to 0
    var docs = 0
    var segments = 0
    db.transaction {
      if (old != null) {
        val docWhere = "kind IN ('DOCUMENT', 'PDF') AND state IN ('understood', 'partial')"
        docs = (db.long("SELECT COUNT(*) FROM files WHERE $docWhere") ?: 0).toInt()
        db.run("UPDATE files SET state = 'searchable', detail = NULL WHERE $docWhere")
        // The lite profile embeds the text read from pictures (modality ocr_text): those pictures are read again too (OCR and
        // the quick-look model are fast). No standard-profile index has such rows.
        val pictureWhere = "state IN ('understood', 'partial') AND id IN (SELECT file FROM segments WHERE modality = 'ocr_text')"
        docs += (db.long("SELECT COUNT(*) FROM files WHERE $pictureWhere") ?: 0).toInt()
        db.run("UPDATE files SET state = 'searchable', detail = NULL WHERE $pictureWhere")
        val until = db.long("SELECT COALESCE(MAX(id), 0) FROM segments") ?: 0
        segments = (db.long("SELECT COUNT(*) $REDO_FROM", 0L, until) ?: 0).toInt()
        if (segments > 0) setMetaIn(db, Schema.Meta.TEXT_REDO, "0:$until")
      }
      setMetaIn(db, Schema.Meta.TEXT_FINGERPRINT, fingerprint)
    }
    docs to segments
  }

  /** The next text vectors to re-embed after a text-model change, oldest first: (segment, file, words). */
  fun textToRedo(limit: Int): List<TextToRedo> = read { db ->
    val range = db.text("SELECT value FROM meta WHERE key = ?", Schema.Meta.TEXT_REDO) ?: return@read emptyList()
    val (after, until) = range.split(':').map { it.toLong() }
    val out = ArrayList<TextToRedo>()
    db.query(
      "SELECT s.id, s.file, x.body $REDO_FROM ORDER BY s.id LIMIT ?", after, until, limit,
    ) { out += TextToRedo(it.getLong(0), it.getLong(1), if (it.isNull(2)) "" else it.getText(2)) }
    out
  }

  /** Stores re-embedded text vectors and moves the redo past `through`; the redo ends when nothing is left. */
  fun replaceTextVectors(vectors: List<Pair<Long, FloatArray>>, through: Long) = write { db ->
    db.transaction {
      for ((id, v) in vectors) {
        require(v.size == dim) { "vector has ${v.size} dimensions, index uses $dim" }
        db.run("UPDATE segments SET vec = ? WHERE id = ?", Fp16.toBlob(v), id)
      }
      val range = db.text("SELECT value FROM meta WHERE key = ?", Schema.Meta.TEXT_REDO)
      if (range != null) {
        val until = range.substringAfter(':').toLong()
        val left = db.long("SELECT COUNT(*) $REDO_FROM", through, until) ?: 0
        if (left == 0L) db.run("DELETE FROM meta WHERE key = ?", Schema.Meta.TEXT_REDO)
        else setMetaIn(db, Schema.Meta.TEXT_REDO, "$through:$until")
      }
    }
  }

  // ---- crawl ---------------------------------------------------------------------------------------------------

  data class UpsertResult(
    val added: Int = 0, val changed: Int = 0, val unchanged: Int = 0, val skipped: Int = 0,
    /** Files that were searchable and now are not (their folder was switched off or excluded): the engine drops their vectors from memory. */
    val newlySkipped: List<Long> = emptyList(),
    /** Files whose content changed: their segments (and vectors) were deleted here, so the engine drops the old vectors from memory too. */
    val reset: List<Long> = emptyList(),
  ) {
    operator fun plus(o: UpsertResult) = UpsertResult(added + o.added, changed + o.changed, unchanged + o.unchanged, skipped + o.skipped, newlySkipped + o.newlySkipped, reset + o.reset)
  }

  /**
   * Adds or updates files from a crawl, one transaction per call (the crawler sends batches). A new file gets its
   * name into the keyword index at once, so it is findable by name within minutes. A file whose size or date changed
   * loses what was extracted from it and is queued again; a file that only moved or was renamed keeps its vectors.
   * Skipped files are recorded with the reason but not made searchable.
   */
  fun upsertFromCrawl(records: List<CrawlRecord>): UpsertResult = write { db ->
    var result = UpsertResult()
    val rulesBefore = rules
    val dirtyBefore = rulesDirty
    try {
      db.transaction {
        val nowMs = provenNow(db, records.filter { it.skipReason == null })
        for (r in records) result += upsertOne(db, r, nowMs)
        syncRulesFingerprintIfTracked(db, rulesBefore, rules)
      }
    } catch (t: Throwable) {
      rules = rulesBefore
      rulesDirty = dirtyBefore
      throw t
    }
    result
  }

  /**
   * The "now" the recent-first priority counts from: the clock, unless the files prove it behind ([ClockSense]). The files being added
   * ([batch]) count as evidence with what the index already holds, so the first batches of a crawl on a phone with a wrong clock are
   * judged too; a file in both counts once (the batch's date wins), so a re-crawl cannot make one odd file look like three.
   * A right clock gives `clock()` itself.
   */
  private fun provenNow(db: Db, batch: List<CrawlRecord>): Long {
    val clockMs = clock()
    val dates = HashMap<Pair<String, Long>, Long>()
    db.query(
      "SELECT volume, mediastore_id, date_ms FROM files WHERE state != 'skipped' AND date_ms <= ? ORDER BY date_ms DESC LIMIT ?",
      ClockSense.ceiling(clockMs), ClockSense.EVIDENCE_FILES,
    ) { dates[it.getText(0) to it.getLong(1)] = it.getLong(2) }
    for (r in batch) dates[r.volume to r.mediaStoreId] = if (r.dateMillis > 0) r.dateMillis else r.modifiedMillis
    return ClockSense.nowMs(clockMs, dates.values)
  }

  /**
   * The dates of the [limit] newest files (not skipped) dated no later than [notAfterMs], newest first: what [ClockSense] weighs against
   * the phone's clock. Junk dates far in the future are left out by [notAfterMs] ([ClockSense.ceiling]). An index scan, so it is cheap.
   */
  fun newestDates(limit: Int, notAfterMs: Long): List<Long> = read { db ->
    val out = ArrayList<Long>(limit)
    db.query("SELECT date_ms FROM files WHERE state != 'skipped' AND date_ms <= ? ORDER BY date_ms DESC LIMIT ?", notAfterMs, limit) { out += it.getLong(0) }
    out
  }

  private fun fingerprintOf(r: CrawlRecord) = "${r.sizeBytes}:${r.modifiedMillis}:$modelFingerprint"

  private fun upsertOne(db: Db, r: CrawlRecord, nowMs: Long): UpsertResult {
    val skipped = r.skipReason != null
    var id = -1L; var oldFp = ""; var oldState = FileState.NEW; var oldRel = ""; var oldName = ""; var oldDate = 0L; var oldKind = ""
    db.query("SELECT id, fingerprint, state, rel_path, name, date_ms, kind FROM files WHERE volume = ? AND mediastore_id = ?", r.volume, r.mediaStoreId) {
      id = it.getLong(0); oldFp = it.getText(1); oldState = FileState.of(it.getText(2)); oldRel = it.getText(3); oldName = it.getText(4); oldDate = it.getLong(5); oldKind = it.getText(6)
    }
    val date = if (r.dateMillis > 0) r.dateMillis else r.modifiedMillis
    val fp = fingerprintOf(r)
    val state = if (skipped) FileState.SKIPPED else FileState.SEARCHABLE
    if (id < 0) {
      db.run(
        """
        INSERT INTO files(volume, mediastore_id, uri, rel_path, name, kind, mime, size, mtime, width, height, duration_ms, pages,
                          state, detail, fingerprint, priority, sensitive, date_ms)
        VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        r.volume, r.mediaStoreId, r.uri, r.relativePath, r.name, r.kind.name, r.mimeType, r.sizeBytes, r.modifiedMillis,
        r.width, r.height, r.durationMs, r.pageCount, state.db, r.skipReason, fp, Schema.priority(r.kind, r.modifiedMillis, nowMs),
        flagsFor(r), date,
      )
      id = db.lastInsertId()
      if (!skipped) addName(db, id, r)
      return if (skipped) UpsertResult(skipped = 1) else UpsertResult(added = 1)
    }
    val sameContent = oldFp == fp && (oldState == FileState.SKIPPED) == skipped
    if (sameContent) {
      // Same bytes, but the rules now call it something else (xlsx, pptx, epub and csv were name-only OTHER files before the
      // office readers): it keeps its name entry and is queued to be read as what it is now.
      if (!skipped && oldKind != r.kind.name) {
        db.run(
          "UPDATE files SET uri = ?, rel_path = ?, name = ?, kind = ?, mime = ?, state = ?, detail = NULL, priority = ?, indexed_at = NULL WHERE id = ?",
          r.uri, r.relativePath, r.name, r.kind.name, r.mimeType, state.db, Schema.priority(r.kind, r.modifiedMillis, nowMs), id,
        )
        return UpsertResult(changed = 1)
      }
      if (oldRel == r.relativePath && oldName == r.name) {
        // Same bytes: only the shown date may have changed (the migration to date_ms re-reads every row for the capture dates).
        if (oldDate != date) db.run("UPDATE files SET date_ms = ? WHERE id = ?", date, id)
        return UpsertResult(unchanged = 1, skipped = if (skipped) 1 else 0)
      }
      // Moved or renamed: same bytes, so keep the vectors and only fix the name.
      // A move can carry a file into or out of a private folder, so the flags are worked out again.
      db.run(
        "UPDATE files SET uri = ?, rel_path = ?, name = ?, sensitive = ?, date_ms = ? WHERE id = ?",
        r.uri, r.relativePath, r.name, flagsFor(r, oldRel, oldName), date, id,
      )
      if (!skipped) {
        db.run("DELETE FROM segments WHERE file = ? AND kind = 'name'", id)
        addName(db, id, r)
      }
      return UpsertResult(changed = 1, skipped = if (skipped) 1 else 0)
    }
    // Changed (or skip status flipped): forget what was read, start again from the name. Deleting the segments takes
    // the vectors with them and, through the trigger, the words; a file turned off here is gone from search at once.
    db.run("DELETE FROM segments WHERE file = ?", id)
    db.run(
      """
      UPDATE files SET uri = ?, rel_path = ?, name = ?, kind = ?, mime = ?, size = ?, mtime = ?, width = ?, height = ?,
        duration_ms = ?, pages = ?, state = ?, detail = ?, fingerprint = ?, priority = ?, info = NULL, indexed_at = NULL,
        looks_private = NULL, sensitive = ?, date_ms = ?
      WHERE id = ?
      """,
      r.uri, r.relativePath, r.name, r.kind.name, r.mimeType, r.sizeBytes, r.modifiedMillis, r.width, r.height,
      r.durationMs, r.pageCount, state.db, r.skipReason, fp, Schema.priority(r.kind, r.modifiedMillis, nowMs),
      flagsFor(r, oldRel, oldName), date, id,
    )
    if (!skipped) addName(db, id, r)
    return UpsertResult(changed = 1, skipped = if (skipped) 1 else 0, newlySkipped = if (skipped && oldState != FileState.SKIPPED) listOf(id) else emptyList(), reset = listOf(id))
  }

  /**
   * The paused crawl's only write ([Crawler.sync] with `pruneOnly`): files the index already holds that a skip rule now
   * covers (a folder the person skipped, a new `.nomedia`) lose what was read from them, as a normal crawl would do.
   * Records for files the index doesn't hold, or holds as skipped already, are ignored: nothing is added.
   */
  fun forgetNowSkipped(records: List<CrawlRecord>): UpsertResult = write { db ->
    var result = UpsertResult()
    val rulesBefore = rules
    val dirtyBefore = rulesDirty
    try {
      db.transaction {
        for (r in records) {
          if (r.skipReason == null) continue
          val state = db.text("SELECT state FROM files WHERE volume = ? AND mediastore_id = ?", r.volume, r.mediaStoreId) ?: continue
          if (FileState.of(state) == FileState.SKIPPED) continue
          result += upsertOne(db, r, clock())   // a skipped file's priority is not used
        }
        syncRulesFingerprintIfTracked(db, rulesBefore, rules)
      }
    } catch (t: Throwable) {
      rules = rulesBefore
      rulesDirty = dirtyBefore
      throw t
    }
    result
  }

  /** The name segment: file name in the `name` column (weighted up), the folders as low-weight words in `body`. */
  private fun addName(db: Db, fileId: Long, r: CrawlRecord) {
    db.run("INSERT INTO segments(file, kind, modality) VALUES(?, 'name', 'none')", fileId)
    val segment = db.lastInsertId()
    db.run("INSERT INTO fts(rowid, name, body) VALUES(?, ?, ?)", segment, r.name, r.relativePath.trim('/').replace('/', ' '))
  }

  /**
   * Removes files (and, through the cascade, their segments and words). Returns how many existed.
   *
   * Deleted means gone from the file too: secure_delete zeroes the freed rows, FTS5's secure-delete takes the words out
   * of its index at once, and a truncating checkpoint empties the write-ahead log, which would otherwise keep old copies
   * of the pages until they were overwritten (the research notes, "Deleted files").
   */
  fun markDeleted(fileIds: Collection<Long>, checkpoint: Boolean = true): Int = write { db ->
    var n = 0
    db.transaction {
      for (id in fileIds) {
        db.run("DELETE FROM files WHERE id = ?", id)
        n += (db.long("SELECT changes()") ?: 0L).toInt()   // the file row only: the cascade's rows are not counted
      }
    }
    if (n > 0 && checkpoint) truncateWal(db)
    n
  }

  /** Copies the write-ahead log into the database and empties it. Best effort: a reader mid-query makes it a no-op. */
  private fun truncateWal(db: Db) {
    runCatching { db.query("PRAGMA wal_checkpoint(TRUNCATE)") { } }
  }

  /** MediaStore ids this store holds for [volume], for the crawler's periodic diff (deletes cannot be queried). */
  fun knownMediaStoreIds(volume: String): Map<Long, Long> = read { db ->
    val out = HashMap<Long, Long>()
    db.query("SELECT mediastore_id, id FROM files WHERE volume = ?", volume) { out[it.getLong(0)] = it.getLong(1) }
    out
  }

  /** Deletes every file of [volume] whose MediaStore id is no longer present. Returns the deleted file ids. */
  fun deleteMissing(volume: String, present: Set<Long>): List<Long> {
    val gone = knownMediaStoreIds(volume).filterKeys { it !in present }.values.toList()
    markDeleted(gone)
    return gone
  }

  // ---- privacy -------------------------------------------------------------------------------------------------

  /**
   * Takes the person's privacy marks and brings every row in line with them. Only rows whose flags change are written,
   * and when [newRules] matches the stored [Schema.Meta.RULES_FINGERPRINT] this short-circuits in O(1) without scanning
   * `files` or writing to disk. Returns how many rows changed. The marks live in the settings, so this runs again after
   * "Delete index" or a rebuild, and nothing marked becomes findable by accident.
   */
  fun applyRules(newRules: PrivacyRules): Int = writeLock.withLock {
    val fp = rulesFingerprint(newRules)
    if (writer.text("SELECT value FROM meta WHERE key = ?", Schema.Meta.RULES_FINGERPRINT) == fp) {
      rules = newRules
      rulesDirty = false
      return@withLock 0
    }
    markDirty()
    try {
      val pending = ArrayList<Pair<Long, Int>>()
      var learned = newRules
      writer.query("SELECT id, volume, mediastore_id, rel_path, sensitive, name FROM files") {
        val want = newRules.flagsFor(it.getText(1), it.getLong(2), it.getText(3), it.getText(5))
        if (want != it.getLong(4).toInt()) pending += it.getLong(0) to want
        // A marked file found under a new MediaStore id keeps its mark: remember the new id too.
        learned = learned.learn(it.getText(1), it.getLong(2), it.getText(3), it.getText(5))
      }
      val learnedFp = if (learned === newRules) fp else rulesFingerprint(learned)
      writer.transaction {
        for ((id, flags) in pending) writer.run("UPDATE files SET sensitive = ? WHERE id = ?", flags, id)
        setMetaIn(writer, Schema.Meta.RULES_FINGERPRINT, learnedFp)
      }
      rules = learned
      rulesDirty = learned != newRules
      pending.size
    } finally {
      changes.incrementAndGet()
    }
  }

  /**
   * Marks or unmarks one file by hand. Returns the rules now in force (the engine keeps `markedFiles` in the settings), or null
   * when the file is gone. A file in a private folder stays hidden after being unmarked; only the folder mark lifts that.
   */
  fun setMarked(fileId: Long, on: Boolean): PrivacyRules? = write { db ->
    var found = false; var volume = ""; var msId = 0L; var rel = ""; var name = ""
    db.query("SELECT volume, mediastore_id, rel_path, name FROM files WHERE id = ?", fileId) {
      found = true; volume = it.getText(0); msId = it.getLong(1); rel = it.getText(2); name = it.getText(3)
    }
    if (!found) return@write null
    val rulesBefore = rules
    val updated = if (on) rulesBefore.withMark(volume, msId, rel, name) else rulesBefore.withoutMark(volume, msId, rel, name)
    db.transaction {
      db.run("UPDATE files SET sensitive = ? WHERE id = ?", updated.flagsFor(volume, msId, rel, name), fileId)
      syncRulesFingerprintIfTracked(db, rulesBefore, updated)
    }
    rules = updated
    rulesDirty = false
    updated
  }

  /** Advances [Schema.Meta.RULES_FINGERPRINT] when the store was already synced to [before] and mutated inline to [after]. */
  private fun syncRulesFingerprintIfTracked(db: Db, before: PrivacyRules, after: PrivacyRules) {
    if (after == before) return
    val current = db.text("SELECT value FROM meta WHERE key = ?", Schema.Meta.RULES_FINGERPRINT) ?: return
    if (current == rulesFingerprint(before)) {
      setMetaIn(db, Schema.Meta.RULES_FINGERPRINT, rulesFingerprint(after))
    }
  }

  /** The flags this crawled file gets; also lets the rules learn its new id or path ([PrivacyRules.learn]). */
  private fun flagsFor(r: CrawlRecord, oldRel: String? = null, oldName: String? = null): Int {
    val before = rules
    val after = before.learn(r.volume, r.mediaStoreId, r.relativePath, r.name, oldRel, oldName)
    if (after !== before) { rules = after; rulesDirty = true }
    return after.flagsFor(r.volume, r.mediaStoreId, r.relativePath, r.name)
  }

  /**
   * The rules, but only when a crawl or rule-apply taught them something new (a marked file's new id, or its new name) that the
   * settings do not hold yet. The engine saves them, then this returns null until the next change.
   */
  fun takeLearnedRules(): PrivacyRules? = writeLock.withLock {
    if (rulesDirty) {
      rulesDirty = false
      rules
    } else {
      null
    }
  }

  /** Whether one file carries any sensitive flag. The index sheet uses it to avoid naming the file being read. */
  fun isSensitive(fileId: Long): Boolean = read { (it.long("SELECT sensitive FROM files WHERE id = ?", fileId) ?: 0L) != 0L }

  /** How many files carry a sensitive flag (all kinds), for the settings line. */
  fun sensitiveCount(): Int = read { (it.long("SELECT COUNT(*) FROM files WHERE sensitive != 0 AND state != 'skipped'") ?: 0).toInt() }

  // ---- work queue ----------------------------------------------------------------------------------------------

  /**
   * Gives rows saved by an older build the recent-first priority ([Schema.priority]), once per scheme: the crawl only sets
   * priority when a file is added or changes, so without this an existing install would keep its kind-only order for
   * the whole backlog. Rows set to -1 by [prioritize] keep it. Returns the rows changed (0 when already done).
   */
  fun reprioritiseOnce(): Int = write { db ->
    if (db.text("SELECT value FROM meta WHERE key = ?", PRIORITY_SCHEME_KEY) == PRIORITY_SCHEME) return@write 0
    val byKind = FileKind.entries.joinToString(" ") { "WHEN '${it.name}' THEN ${1 + Schema.kindPriority(it)}" }
    val cutoff = provenNow(db, emptyList()) - Schema.RECENT_MS
    var n = 0
    db.transaction {
      n = (db.long("SELECT COUNT(*) FROM files WHERE priority >= 0") ?: 0).toInt()
      db.run("UPDATE files SET priority = CASE WHEN mtime >= ? THEN 0 ELSE CASE kind $byKind ELSE 10 END END WHERE priority >= 0", cutoff)
      setMetaIn(db, PRIORITY_SCHEME_KEY, PRIORITY_SCHEME)
    }
    n
  }

  /**
   * Files waiting to be understood, in the order people look for things ([Schema.QUEUE_ORDER]): files from the last
   * [Schema.RECENT_DAYS] days first (newest day first, cheap screenshots and small documents ahead of photos within a day),
   * then the older backlog (small documents, screenshots, photos, long documents, videos, audio), newest first within each. [includePartial] adds long files that were read in part, for the
   * later pass that runs once nothing else is waiting.
   */
  fun nextPending(limit: Int, kinds: Set<FileKind>, includePartial: Boolean = false): List<PendingFile> = read { db ->
    if (kinds.isEmpty()) return@read emptyList()
    val states = if (includePartial) "'new','searchable','partial'" else "'new','searchable'"
    val out = ArrayList<PendingFile>()
    db.query(
      "SELECT $FILE_COLUMNS, state, detail FROM files WHERE state IN ($states) AND kind IN (${kindList(kinds)}) ORDER BY ${Schema.QUEUE_ORDER}, mtime DESC, id LIMIT ?",
      limit,
    ) { out += PendingFile(fileOf(it), FileState.of(it.getText(12)), it.textOrNull(13)) }
    out
  }

  /**
   * Where "the backlog" ends: the id of the newest file known when the first crawl finished. Files with a higher id arrived
   * since, and are "new" (IndexPolicy: they get full quality at once on battery, a few at a time). Set once, then fixed;
   * an index with no files yet has none (returns 0 and stores nothing, so the first crawl that finds files sets it).
   */
  fun backlogMark(): Long = write { db ->
    db.text("SELECT value FROM meta WHERE key = ?", BACKLOG_MARK)?.toLongOrNull()?.let { return@write it }
    val newest = db.long("SELECT MAX(id) FROM files") ?: 0L
    if (newest > 0) setMetaIn(db, BACKLOG_MARK, newest.toString())
    newest
  }

  /** Files that arrived after [afterId] (see [backlogMark]) and still wait for full quality, the kinds that are cheap enough, newest first. */
  fun nextNewPending(
    limit: Int, kinds: Set<FileKind>, afterId: Long, maxVideoBytes: Long = Long.MAX_VALUE, maxAudioBytes: Long = Long.MAX_VALUE,
  ): List<PendingFile> = read { db ->
    if (kinds.isEmpty() || afterId <= 0) return@read emptyList()
    val out = ArrayList<PendingFile>()
    // A video or recording longer than the caps (judged by size) is not "new work on battery": it waits for the charger.
    db.query(
      "SELECT $FILE_COLUMNS, state, detail FROM files WHERE state IN ('new','searchable') AND id > ? AND kind IN (${kindList(kinds)}) " +
        "AND (kind != 'VIDEO' OR size <= ?) AND (kind != 'AUDIO' OR size <= ?) ORDER BY priority, mtime DESC, id LIMIT ?",
      afterId, maxVideoBytes, maxAudioBytes, limit,
    ) { out += PendingFile(fileOf(it), FileState.of(it.getText(12)), it.textOrNull(13)) }
    out
  }

  /** Long files read in part, waiting for their later steps. */
  fun nextPartial(limit: Int, kinds: Set<FileKind>): List<PendingFile> = read { db ->
    if (kinds.isEmpty()) return@read emptyList()
    val out = ArrayList<PendingFile>()
    db.query(
      "SELECT $FILE_COLUMNS, state, detail FROM files WHERE state = 'partial' AND kind IN (${kindList(kinds)}) ORDER BY priority, mtime DESC, id LIMIT ?",
      limit,
    ) { out += PendingFile(fileOf(it), FileState.of(it.getText(12)), it.textOrNull(13)) }
    out
  }

  /**
   * Files whose words aren't in yet: the fast pass (document text, text read from images) that runs before anything
   * is embedded, so a phone is searchable by its words within minutes. Marked done by detail = [WORDS_DONE].
   * Its own order ([Schema.WORDS_ORDER]): in the older backlog, documents' text before photos' OCR.
   */
  fun nextWithoutWords(limit: Int, kinds: Set<FileKind>): List<PendingFile> = read { db ->
    if (kinds.isEmpty()) return@read emptyList()
    val out = ArrayList<PendingFile>()
    db.query(
      "SELECT $FILE_COLUMNS, state, detail FROM files WHERE state IN ('new','searchable') AND detail IS NULL AND kind IN (${kindList(kinds)}) ORDER BY ${Schema.WORDS_ORDER}, mtime DESC, id LIMIT ?",
      limit,
    ) { out += PendingFile(fileOf(it), FileState.of(it.getText(12)), it.textOrNull(13)) }
    out
  }

  // ---- kill-loop breaker -----------------------------------------------------------------------------------------
  //
  // A file that makes the phone's low-memory killer (or a native crash) take the whole process down would be picked up again
  // by the restarted worker, forever. The worker marks the step it is reading (the file, and which step of it: a long video
  // is read a window at a time) and clears the mark when the step is over, however it went. A mark still there at the next
  // start means the process died inside that step. The mark carries the count of deaths, so the second death in the same step
  // ends it. Only deaths with memory pressure recorded during that attempt can label the file too large or skip a window.
  // Other repeated interruptions fail neutrally and wait for an app update, without discarding any windows.

  /** What [recoverInflight] did: [skippedWindow] true: only [step] was left out and the file goes on; false: the file was failed. */
  data class Recovery(val fileId: Long, val step: Int, val skippedWindow: Boolean, val detail: String? = null)

  @Volatile private var inflightActive = false

  /** Marks step [step] of [fileId] as the one being read. The death count survives only while it is the same step of the same file. */
  fun markInflight(fileId: Long, step: Int = 0, windowed: Boolean = false) = write { db ->
    val previous = inflight(db)?.takeIf { it.fileId == fileId && it.step == step }
    setMetaIn(db, INFLIGHT_KEY, Inflight(fileId, step, previous?.deaths ?: 0, windowed, false, previous?.memoryDeaths ?: 0).value)
    inflightActive = true
  }

  fun noteInflightMemoryPressure() {
    if (!inflightActive) return
    write { db ->
      if (!inflightActive) return@write
      val mark = inflight(db) ?: return@write
      if (!mark.memoryPressure) setMetaIn(db, INFLIGHT_KEY, mark.copy(memoryPressure = true).value)
    }
  }

  /** The worker is done with the step it marked (however that went): no process death happened inside it. */
  fun clearInflight() = write { db ->
    inflightActive = false
    db.run("DELETE FROM meta WHERE key = ?", INFLIGHT_KEY)
  }

  /**
   * Called when a worker starts. A mark left behind means the last process died inside that step: count it. At [maxDeaths]
   * the step is given up on (see above) and what was done is returned; below that it stays marked with the higher count and
   * gets another chance.
   */
  fun recoverInflight(maxDeaths: Int = 2): Recovery? = write { db ->
    inflightActive = false
    db.transaction {
      val mark = inflight(db) ?: return@transaction null
      val deaths = mark.deaths + 1
      val memoryDeaths = mark.memoryDeaths + if (mark.memoryPressure) 1 else 0
      if (deaths < maxDeaths) {
        setMetaIn(db, INFLIGHT_KEY, mark.copy(deaths = deaths, memoryPressure = false, memoryDeaths = memoryDeaths).value)
        return@transaction null
      }
      clearInflight()
      val memoryRelated = memoryDeaths >= maxDeaths
      val detail = db.text("SELECT detail FROM files WHERE id = ?", mark.fileId)
      val skipped = detail?.let { SKIPPED.find(it)?.groupValues?.get(1)?.toIntOrNull() } ?: 0
      if (memoryRelated && mark.windowed && skipped < 1) {
        setState(mark.fileId, FileState.PARTIAL, "next=${mark.step + 1} skipped=${skipped + 1}")
        return@transaction Recovery(mark.fileId, mark.step, skippedWindow = true)
      }
      val failure = if (memoryRelated) TOO_LARGE_DETAIL else INTERRUPTED_DETAIL
      setState(mark.fileId, FileState.FAILED, failure)
      Recovery(mark.fileId, mark.step, skippedWindow = false, detail = failure)
    }
  }

  private data class Inflight(
    val fileId: Long, val step: Int, val deaths: Int, val windowed: Boolean, val memoryPressure: Boolean, val memoryDeaths: Int,
  ) {
    val value get() = "$fileId:$step:$deaths:${if (windowed) "w" else "f"}:${if (memoryPressure) "p" else "-"}:$memoryDeaths"
  }

  private fun inflight(db: Db): Inflight? {
    val parts = db.text("SELECT value FROM meta WHERE key = ?", INFLIGHT_KEY)?.split(':') ?: return null
    val id = parts.getOrNull(0)?.toLongOrNull() ?: return null
    return Inflight(id, parts.getOrNull(1)?.toIntOrNull() ?: 0, parts.getOrNull(2)?.toIntOrNull() ?: 0,
      parts.getOrNull(3) == "w", parts.getOrNull(4) == "p", parts.getOrNull(5)?.toIntOrNull() ?: 0)
  }

  /**
   * Trawl Go: files of every kind except [readable] have nothing more to learn (no model reads videos or sound files), so they
   * finish as they are, found by their names. Returns how many were finished.
   */
  fun finishNameOnly(readable: Set<FileKind>): Int = write { db ->
    val where = "state IN ('new','searchable') AND kind NOT IN (${kindList(readable)})"
    val n = (db.long("SELECT COUNT(*) FROM files WHERE $where") ?: 0).toInt()
    if (n > 0) db.run("UPDATE files SET state = 'understood', detail = ?, indexed_at = ? WHERE $where", NAME_ONLY, System.currentTimeMillis())
    n
  }

  /**
   * Gives failed files another go: after an app update, a failure may have been the old build's bug (a model that
   * wouldn't load failed 38 documents in seconds once). They restart from the words pass. Returns how many.
   */
  fun retryFailed(): Int = write { db ->
    val n = (db.long("SELECT COUNT(*) FROM files WHERE state = 'failed'") ?: 0).toInt()
    if (n > 0) db.run("UPDATE files SET state = 'searchable', detail = NULL WHERE state = 'failed'")
    n
  }

  fun retryFailedAfterUpdate(build: String): Int = write { db ->
    db.transaction {
      if (db.text("SELECT value FROM meta WHERE key = ?", RETRY_KEY) == build) return@transaction 0
      val count = retryFailed()
      setMetaIn(db, RETRY_KEY, build)
      count
    }
  }

  fun retryTimedOut(): Int = write { db ->
    val where = "state = 'failed' AND detail = 'took too long'"
    val count = (db.long("SELECT COUNT(*) FROM files WHERE $where") ?: 0).toInt()
    if (count > 0) db.run("UPDATE files SET state = 'partial', detail = 'next=0 timeouts=1' WHERE $where")
    count
  }

  /**
   * Files understood while the speech models were missing (FileProcessor notes it in the detail): once the models are in,
   * they go back in the queue so their soundtrack gets read. Their frames stay searchable meanwhile.
   */
  fun retryMissingSpeech(): Int = write { db ->
    val where = "state = 'understood' AND detail LIKE '%$SPEECH_MISSING%'"
    val n = (db.long("SELECT COUNT(*) FROM files WHERE $where") ?: 0).toInt()
    if (n > 0) db.run("UPDATE files SET state = 'searchable', detail = NULL WHERE $where")
    n
  }

  /**
   * Files whose speech was judged by an older language gate ([com.craneai.findr.engine.SpeechLanguageGate.VERSION]; FileProcessor writes the
   * verdict in the detail, "N speech pieces" or "speech not English ..."): they go back in the queue so the current rule decides again.
   * Their words and vectors stay searchable until they are read again. Returns how many.
   */
  fun requeueSpeechJudgedAgain(): Int = write { db ->
    val where = "kind IN ('VIDEO', 'AUDIO') AND state IN ('understood', 'partial') AND " +
      "(detail LIKE '%${com.craneai.findr.engine.SpeechLanguageGate.NOTE_ENGLISH}%' OR detail LIKE '%${com.craneai.findr.engine.SpeechLanguageGate.NOTE_NOT_ENGLISH}%')"
    val n = (db.long("SELECT COUNT(*) FROM files WHERE $where") ?: 0).toInt()
    if (n > 0) db.run("UPDATE files SET state = 'searchable', detail = NULL WHERE $where")
    n
  }

  /**
   * The lite profile's pictures and videos read while the quick-look model was missing or off (detail [QUICK_MISSING]): once
   * it can run, they go back in the queue for their picture vectors. Their words and OCR vectors stay searchable meanwhile.
   */
  fun retryMissingQuick(): Int = write { db ->
    val where = "state = 'understood' AND detail LIKE '%$QUICK_MISSING%'"
    val n = (db.long("SELECT COUNT(*) FROM files WHERE $where") ?: 0).toInt()
    if (n > 0) db.run("UPDATE files SET state = 'searchable', detail = NULL WHERE $where")
    n
  }

  /**
   * Recordings without an audio vector go back in the queue when "Deeper sound search" turns on: left with only their name
   * ([AUDIO_OFF], or "speech models missing" on a long one), or read for speech and sound labels only (a long file, or any file
   * while speech to text worked). Reading again replaces their words, and the deeper switch adds the sound windows. A recording
   * that already has its audio windows (a short one read without speech models) stays as it is.
   */
  fun retryAudioOff(): Int = write { db ->
    val where = "kind = 'AUDIO' AND state = 'understood' AND NOT EXISTS (SELECT 1 FROM segments s WHERE s.file = files.id AND s.kind = '${SegmentKind.AUDIO.db}')"
    val n = (db.long("SELECT COUNT(*) FROM files WHERE $where") ?: 0).toInt()
    if (n > 0) db.run("UPDATE files SET state = 'searchable', detail = NULL WHERE $where")
    n
  }

  /**
   * Files of these kinds that were read (in full or in part) go back in the queue, keeping their words and vectors
   * until they are read again. For when the way they are read changes: a part-read document's later steps embed the
   * chunks its first step stored, so a fixed extractor would otherwise never reach it.
   */
  fun requeueRead(
    kinds: Collection<com.craneai.findr.core.FileKind>,
    extensions: Collection<String> = emptyList(),   // limit to these name endings (or a mime type starting `mimePrefix`)
    mimePrefix: String? = null,
    detailLacks: String? = null,                    // only files whose detail doesn't mention this (e.g. never read for speech)
  ): Int = write { db ->
    if (kinds.isEmpty()) return@write 0
    val inKinds = kinds.joinToString(",") { "'${it.name}'" }
    val args = extensions.map { "%.${it.lowercase()}" } + listOfNotNull(mimePrefix?.let { "$it%" })
    val which = if (args.isEmpty()) "" else " AND (" + (extensions.map { "lower(name) LIKE ?" } +
      listOfNotNull(mimePrefix?.let { "mime LIKE ?" })).joinToString(" OR ") + ")"
    val lacks = if (detailLacks == null) "" else " AND (detail IS NULL OR detail NOT LIKE ?)"
    val all = args + listOfNotNull(detailLacks?.let { "%$it%" })
    val where = "kind IN ($inKinds) AND state IN ('understood', 'partial')$which$lacks"
    val n = (db.long("SELECT COUNT(*) FROM files WHERE $where", *all.toTypedArray()) ?: 0).toInt()
    if (n > 0) db.run("UPDATE files SET state = 'searchable', detail = NULL WHERE $where", *all.toTypedArray())
    n
  }

  /** Moves files to the front of the queue (the user is looking at them). */
  fun prioritize(fileIds: Collection<Long>) = write { db ->
    db.transaction { for (id in fileIds) db.run("UPDATE files SET priority = -1 WHERE id = ? AND state IN ('new','searchable','partial')", id) }
  }

  fun setState(fileId: Long, state: FileState, detail: String? = null) = write { db ->
    // A worker that was mid-file when the file was switched off must not bring it back: only a crawl un-skips a file.
    val guard = if (state == FileState.SKIPPED) "" else " AND state != 'skipped'"
    db.run("UPDATE files SET state = ?, detail = ?, indexed_at = ? WHERE id = ?$guard", state.db, detail, System.currentTimeMillis(), fileId)
  }

  /**
   * Saves what was read from a file and moves it to [newState], in one transaction: a crash or an exception leaves
   * the file exactly as it was. By default the file's earlier non-name segments are replaced; pass
   * `replace = false` to append (the later steps of a long file). Returns the new segment ids in input order,
   * which the engine hands to the vector index.
   *
   * A replace also drops the file's quick-look vector (EmbeddingGemma's own vectors supersede it, so the end state is
   * EmbeddingGemma's quality), unless [keepQuick]: the words pass, which adds no vectors, keeps it.
   */
  fun saveSegments(
    fileId: Long,
    segments: List<Segment>,
    newState: FileState,
    detail: String? = null,
    replace: Boolean = true,
    info: String? = null,
    pageCount: Int? = null,
    keepQuick: Boolean = false,
  ): LongArray = write { db ->
    val ids = LongArray(segments.size)
    db.transaction {
      // Turned off while this file was being read: save nothing (see setState).
      if (FileState.of(db.text("SELECT state FROM files WHERE id = ?", fileId)) == FileState.SKIPPED) return@transaction
      if (replace) db.run("DELETE FROM segments WHERE file = ? AND kind != 'name'" + if (keepQuick) " AND modality != 'clip'" else "", fileId)
      segments.forEachIndexed { i, s -> ids[i] = insertSegment(db, fileId, s) }
      db.run(
        "UPDATE files SET state = ?, detail = ?, indexed_at = ?, info = COALESCE(?, info), pages = COALESCE(?, pages) WHERE id = ?",
        newState.db, detail, System.currentTimeMillis(), info, pageCount, fileId,
      )
    }
    ids
  }

  /** One segment row and its keyword row, inside the caller's transaction. Returns the segment id. */
  private fun insertSegment(db: Db, fileId: Long, s: Segment): Long {
    val vector = s.vector
    require(s.kind != SegmentKind.SEES || (vector == null && s.modality == Modality.NONE && s.locStart == null && s.locEnd == null && s.marks == null))
    val modality = if (vector == null) Modality.NONE else s.modality.takeIf { it != Modality.NONE } ?: Modality.TEXT
    require(vector == null || vector.size == dimOf(modality)) { "vector has ${vector?.size} dimensions, $modality uses ${dimOf(modality)}" }
    db.run(
      "INSERT INTO segments(file, kind, modality, loc_start, loc_end, late, vec, marks) VALUES(?, ?, ?, ?, ?, ?, ?, ?)",
      fileId, s.kind.db, modality.db, s.locStart, s.locEnd, s.late, vector?.let(Fp16::toBlob), s.marks,
    )
    val id = db.lastInsertId()
    if (!s.text.isNullOrBlank()) {
      if (s.kind == SegmentKind.SEES) db.run("INSERT INTO sees_fts(rowid, body) VALUES(?, ?)", id, s.text)
      else if (s.kind == SegmentKind.NAME) db.run("INSERT INTO fts(rowid, name, body) VALUES(?, ?, '')", id, s.text)
      else db.run("INSERT INTO fts(rowid, name, body) VALUES(?, '', ?)", id, s.text)
    }
    return id
  }

  fun nextOldSees(limit: Int): List<PendingFile> = read { db ->
    val out = ArrayList<PendingFile>()
    db.query("SELECT $FILE_COLUMNS, state, detail FROM files WHERE $OLD_SEES ORDER BY date_ms DESC, id LIMIT ?", limit) {
      out += PendingFile(fileOf(it), FileState.of(it.getText(12)), it.textOrNull(13))
    }
    out
  }

  fun seesFrames(fileId: Long): SeesCatchUp.Frames? = read { db ->
    val generation = db.long("SELECT value FROM meta WHERE key = ?", Schema.Meta.VEC_GEN) ?: 0L
    var fingerprint: String? = null
    var indexedAt: Long? = null
    db.query("SELECT fingerprint, indexed_at FROM files WHERE id = ? AND $OLD_SEES", fileId) {
      fingerprint = it.getText(0)
      indexedAt = it.longOrNull(1)
    }
    val readFingerprint = fingerprint ?: return@read null
    val frames = ArrayList<FloatArray>()
    db.query("SELECT vec FROM segments WHERE file = ? AND kind = 'frame' AND modality = 'image' AND vec IS NOT NULL AND late != ${Schema.Late.STALE} ORDER BY id", fileId) {
      frames += Fp16.blobToFloats(it.getBlob(0))
    }
    if (generation != (db.long("SELECT value FROM meta WHERE key = ?", Schema.Meta.VEC_GEN) ?: 0L)) return@read null
    SeesCatchUp.Frames(fileId, generation, readFingerprint, indexedAt, frames)
  }

  fun saveSeesCatchUp(frames: SeesCatchUp.Frames, line: String?): Boolean = write { db ->
    db.transaction {
      if (frames.generation != (db.long("SELECT value FROM meta WHERE key = ?", Schema.Meta.VEC_GEN) ?: 0L) ||
        (db.long("SELECT COUNT(*) FROM files WHERE id = ? AND fingerprint = ? AND indexed_at IS ? AND $OLD_SEES", frames.fileId, frames.fingerprint, frames.indexedAt) ?: 0L) == 0L) return@transaction false
      db.run("DELETE FROM segments WHERE file = ? AND kind = 'sees'", frames.fileId)
      if (!line.isNullOrBlank()) insertSegment(db, frames.fileId, Segment(SegmentKind.SEES, text = line))
      stampIn(db, frames.fileId, SeesCatchUp.KEY, SeesCatchUp.VERSION)
      true
    }
  }

  // ---- older videos (VideoCatchUp) ----------------------------------------------------------------------------

  /** Understood videos an earlier build read ([VideoCatchUp]: no stamp), newest first: the catch-up's queue. */
  fun nextOldVideos(limit: Int): List<PendingFile> = read { db ->
    val out = ArrayList<PendingFile>()
    db.query("SELECT $FILE_COLUMNS, state, detail FROM files WHERE $OLD_VIDEO ORDER BY date_ms DESC, id LIMIT ?", limit) {
      out += PendingFile(fileOf(it), FileState.of(it.getText(12)), it.textOrNull(13))
    }
    out
  }

  /** How many videos the catch-up has left, and their length in ms (a video with no known length counts 0). */
  fun oldVideosLeft(): Pair<Int, Long> = read { db ->
    var n = 0; var ms = 0L
    db.query("SELECT COUNT(*), COALESCE(SUM(duration_ms), 0) FROM files WHERE $OLD_VIDEO") { n = it.getLong(0).toInt(); ms = it.getLong(1) }
    n to ms
  }

  /** What a video holds before its catch-up: its picture rows, its words, line by line, and whether those words carry vectors. */
  fun videoRows(fileId: Long): VideoCatchUp.OldRows = read { db ->
    val frames = ArrayList<VideoCatchUp.FrameRow>()
    db.query("SELECT id, loc_start, loc_end, marks FROM segments WHERE file = ? AND kind = 'frame' AND loc_start IS NOT NULL ORDER BY id", fileId) {
      frames += VideoCatchUp.FrameRow(it.getLong(0), it.getLong(1), it.longOrNull(2), it.textOrNull(3))
    }
    val lines = ArrayList<String>()
    var vectors = false
    db.query("SELECT x.body, s.vec IS NOT NULL FROM segments s LEFT JOIN fts x ON x.rowid = s.id WHERE s.file = ? AND s.kind = 'ocr' ORDER BY s.id", fileId) {
      it.textOrNull(0)?.let { body -> lines += body.lines() }
      if (it.getLong(1) != 0L) vectors = true
    }
    VideoCatchUp.OldRows(frames, lines, vectors)
  }

  /**
   * Saves one video's catch-up in one transaction: the keyframe [marks] (segment id to mark) onto picture rows that have none, the
   * video's words as read today ([ocr]) in place of its old ones (beside them when [keepOld]), and the stamp. Nothing else of the file
   * is touched: not its picture vectors, speech, sounds or name, nor its state, detail or dates. False, with nothing written, when the
   * file was changed, switched off or read again since it was picked (that new read is today's already).
   */
  fun saveVideoCatchUp(fileId: Long, marks: Map<Long, String>, ocr: List<Segment>, keepOld: Boolean): Boolean = write { db ->
    require(ocr.all { it.kind == SegmentKind.OCR }) { "a catch-up writes words only" }
    db.transaction {
      if ((db.long("SELECT COUNT(*) FROM files WHERE id = ? AND $OLD_VIDEO", fileId) ?: 0L) == 0L) return@transaction false
      for ((id, mark) in marks) db.run("UPDATE segments SET marks = ? WHERE id = ? AND file = ? AND kind = 'frame' AND marks IS NULL", mark, id, fileId)
      if (!keepOld) db.run("DELETE FROM segments WHERE file = ? AND kind = 'ocr'", fileId)
      for (s in ocr) insertSegment(db, fileId, s)
      stampIn(db, fileId, VideoCatchUp.KEY, VideoCatchUp.VERSION)
      true
    }
  }

  /** The catch-up gives up on a video (it could not be read again, or took too long): it keeps the reading it has and leaves the queue. */
  fun stampVideo(fileId: Long) = write { db -> if ((db.long("SELECT COUNT(*) FROM files WHERE id = ? AND $OLD_VIDEO", fileId) ?: 0L) > 0L) stampIn(db, fileId, VideoCatchUp.KEY, VideoCatchUp.VERSION) }

  /**
   * Before a video's catch-up: notes it as in flight. False when the process already died inside this same video's catch-up
   * [maxDeaths] times; the video is then stamped as it is (it keeps its reading) so it can never hold the queue in a kill loop.
   */
  fun beginVideoCatchUp(fileId: Long, maxDeaths: Int = 2): Boolean = beginCatchUp(CATCHUP_INFLIGHT, fileId, maxDeaths) { stampVideo(fileId) }

  /** The video's catch-up is over, however it went: no process death happened inside it. */
  fun endVideoCatchUp() = setMeta(CATCHUP_INFLIGHT, null)

  /** The in-flight guard every catch-up shares: [giveUp] runs (stamping the file as it is) when the process already died inside this file [maxDeaths] times. */
  private fun beginCatchUp(key: String, fileId: Long, maxDeaths: Int, giveUp: () -> Unit): Boolean {
    val was = meta(key)?.split(':')
    val deaths = if (was?.getOrNull(0)?.toLongOrNull() == fileId) (was.getOrNull(1)?.toIntOrNull() ?: 0) + 1 else 0
    if (deaths >= maxDeaths) {
      giveUp()
      setMeta(key, null)
      return false
    }
    setMeta(key, "$fileId:$deaths")
    return true
  }

  /** Sets [key]'s stamp on one file, keeping its other stamps. */
  private fun stampIn(db: Db, fileId: Long, key: String, version: Int) {
    val now = db.text("SELECT info FROM files WHERE id = ?", fileId)
    db.run("UPDATE files SET info = ? WHERE id = ?", ReadingStamps.put(now, key, version), fileId)
  }

  // ---- older soundtracks (SoundtrackCatchUp) ------------------------------------------------------------------

  /**
   * The catch-up's queue: understood videos and recordings whose speech an earlier build read ([SoundtrackCatchUp]: no stamp) and,
   * while a speech pack is in use ([speechPackStamp]), those judged not English that were not read with it.
   */
  private fun oldSoundtrack(): String {
    val pack = speechPackStamp ?: return OLD_SOUNDTRACK
    return "$SOUNDTRACK_JUDGED AND (NOT (${ReadingStamps.sqlHas(SoundtrackCatchUp.KEY, SoundtrackCatchUp.VERSION)}) OR " +
      "(detail LIKE '%${com.craneai.findr.engine.SpeechLanguageGate.NOTE_NOT_ENGLISH}%' AND NOT (${ReadingStamps.sqlHas(SoundtrackCatchUp.PACK_KEY, pack)})))"
  }

  /** Sets the soundtrack stamps of one file: today's reading, and the speech pack in use when there is one. */
  private fun stampSoundtrackIn(db: Db, fileId: Long) {
    stampIn(db, fileId, SoundtrackCatchUp.KEY, SoundtrackCatchUp.VERSION)
    speechPackStamp?.let { pack ->
      val now = db.text("SELECT info FROM files WHERE id = ?", fileId)
      db.run("UPDATE files SET info = ? WHERE id = ?", ReadingStamps.put(now, SoundtrackCatchUp.PACK_KEY, pack), fileId)
    }
  }

  /** Understood videos and recordings the soundtrack catch-up has to read ([oldSoundtrack]), newest first, of [kinds]: its queue. */
  fun nextOldSoundtracks(limit: Int, kinds: Set<FileKind>): List<PendingFile> = read { db ->
    if (kinds.isEmpty()) return@read emptyList()
    val out = ArrayList<PendingFile>()
    val inList = kinds.joinToString(",") { "'${it.name}'" }
    db.query("SELECT $FILE_COLUMNS, state, detail FROM files WHERE ${oldSoundtrack()} AND kind IN ($inList) ORDER BY date_ms DESC, id LIMIT ?", limit) {
      out += PendingFile(fileOf(it), FileState.of(it.getText(12)), it.textOrNull(13))
    }
    out
  }

  /** How many files the soundtrack catch-up has left, and their length in ms. */
  fun oldSoundtracksLeft(): Pair<Int, Long> = read { db ->
    var n = 0; var ms = 0L
    db.query("SELECT COUNT(*), COALESCE(SUM(duration_ms), 0) FROM files WHERE ${oldSoundtrack()}") { n = it.getLong(0).toInt(); ms = it.getLong(1) }
    n to ms
  }

  /**
   * Stamps, without opening them, the queue's files the current change cannot alter ([SoundtrackCatchUp.needsRead] false: a complete
   * "Sounds:" line that names no singing). Returns how many. Files with no sounds line, a full line, or singing stay queued. Only v1's
   * stamp is written: a not-English file still waits for the speech pack in use, if any.
   */
  fun stampUnaffectedSoundtracks(): Int = write { db ->
    val ids = ArrayList<Long>()
    db.query("SELECT s.file, x.body FROM segments s JOIN fts x ON x.rowid = s.id WHERE s.kind = '${SegmentKind.SOUND.db}' AND s.file IN (SELECT id FROM files WHERE $OLD_SOUNDTRACK)") {
      if (!SoundtrackCatchUp.needsRead(it.textOrNull(1))) ids += it.getLong(0)
    }
    db.transaction { for (id in ids.distinct()) stampIn(db, id, SoundtrackCatchUp.KEY, SoundtrackCatchUp.VERSION) }
    ids.distinct().size
  }

  /**
   * Saves one file's soundtrack catch-up in one transaction: its transcript pieces ([transcript], words and text vectors) in place of the
   * old ones, the "Sounds:" lines too when [sounds] holds any (one per window of a long file, [SoundtrackCatchUp.REFRESH_SOUNDS]; a
   * reading with none keeps the old lines), and the stamp. Nothing else of the file
   * is touched: not its pictures, their vectors or words, its audio vectors or name, nor its state, detail or dates. False, with nothing
   * written, when the file was changed, switched off or read again since it was picked (that new read is today's already).
   */
  fun saveSoundtrackCatchUp(fileId: Long, transcript: List<Segment>, sounds: List<Segment> = emptyList()): Boolean = write { db ->
    require(transcript.all { it.kind == SegmentKind.TRANSCRIPT }) { "a soundtrack catch-up writes transcript pieces only" }
    require(sounds.all { it.kind == SegmentKind.SOUND })
    db.transaction {
      if ((db.long("SELECT COUNT(*) FROM files WHERE id = ? AND ${oldSoundtrack()}", fileId) ?: 0L) == 0L) return@transaction false
      db.run("DELETE FROM segments WHERE file = ? AND kind = '${SegmentKind.TRANSCRIPT.db}'", fileId)
      if (sounds.isNotEmpty()) db.run("DELETE FROM segments WHERE file = ? AND kind = '${SegmentKind.SOUND.db}'", fileId)
      for (t in transcript) insertSegment(db, fileId, t)
      for (s in sounds) insertSegment(db, fileId, s)
      stampSoundtrackIn(db, fileId)
      true
    }
  }

  /** The catch-up gives up on a file (it could not be read again, or took too long): it keeps the reading it has and leaves the queue. */
  fun stampSoundtrack(fileId: Long) = write { db -> if ((db.long("SELECT COUNT(*) FROM files WHERE id = ? AND ${oldSoundtrack()}", fileId) ?: 0L) > 0L) stampSoundtrackIn(db, fileId) }

  /** Before a soundtrack catch-up: false (the file stamped as it is) when the process already died twice inside this file's. */
  fun beginSoundtrackCatchUp(fileId: Long, maxDeaths: Int = 2): Boolean = beginCatchUp(SoundtrackCatchUp.INFLIGHT, fileId, maxDeaths) { stampSoundtrack(fileId) }

  fun endSoundtrackCatchUp() = setMeta(SoundtrackCatchUp.INFLIGHT, null)

  // ---- looks private (blur a preview; never rank or hide) -----------------------------------------------------

  /**
   * Pictures and videos that have not been scored yet, photos and screenshots first, newest first. Every file gets one
   * score for life (until it changes or the model does), so this queue only ever shrinks.
   */
  fun nextForPrivateScore(limit: Int, kinds: Set<FileKind>): List<IndexedFile> = read { db ->
    val visual = kinds.intersect(QUICK_KINDS)
    if (visual.isEmpty()) return@read emptyList()
    val out = ArrayList<IndexedFile>()
    db.query(
      "SELECT $FILE_COLUMNS FROM files WHERE looks_private IS NULL AND state != 'skipped' AND kind IN (${kindList(visual)}) " +
        "ORDER BY CASE WHEN kind = 'VIDEO' THEN 1 ELSE 0 END, mtime DESC, id LIMIT ?",
      limit,
    ) { out += fileOf(it) }
    out
  }

  /** Saves a picture's score (0..1). A null [score] records "tried, can't be read", so it isn't tried again on every run. */
  fun saveLooksPrivate(fileId: Long, score: Float?) = write { db ->
    db.run("UPDATE files SET looks_private = ? WHERE id = ?", (score ?: UNREADABLE_SCORE).toDouble(), fileId)
  }

  /** The scores a model wrote mean nothing to another model: a different [model] clears them (they are redone in the background). */
  fun ensurePrivateModel(model: String): Boolean = write { db ->
    if (db.text("SELECT value FROM meta WHERE key = ?", Schema.Meta.PRIVATE_MODEL) == model) return@write false
    db.transaction {
      db.run("UPDATE files SET looks_private = NULL WHERE looks_private IS NOT NULL")
      db.run("INSERT INTO meta(key, value) VALUES(?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value", Schema.Meta.PRIVATE_MODEL, model)
    }
    true
  }

  /**
   * Forgets every score. The "Blur private-looking previews" pack was removed: without it nothing is blurred, so no stale mark may
   * linger (adding the pack again scores the library afresh). Returns how many were cleared.
   */
  fun clearLooksPrivate(): Int = write { db ->
    var n = 0
    db.query("SELECT COUNT(looks_private) FROM files") { n = it.getLong(0).toInt() }
    if (n > 0) db.run("UPDATE files SET looks_private = NULL WHERE looks_private IS NOT NULL")
    n
  }

  /** How many pictures and videos have a score, out of how many can have one (for the status line and the tests). */
  fun privateScoreCounts(): Pair<Int, Int> = read { db ->
    var scored = 0; var all = 0
    db.query(
      "SELECT COUNT(looks_private), COUNT(*) FROM files WHERE state != 'skipped' AND kind IN (${kindList(QUICK_KINDS)})",
    ) { scored = it.getLong(0).toInt(); all = it.getLong(1).toInt() }
    scored to all
  }

  // ---- quick look (D2) -----------------------------------------------------------------------------------------

  /**
   * The quick-look pass's queue: pictures and videos that EmbeddingGemma hasn't understood and that have no quick-look
   * row yet, newest first. Videos ride along with the photos, about [videoShare] of each batch ([QuickMix]; before, they waited
   * for every photo, so a gallery of 16,000 photos left its videos findable by name only for hours); 0 puts them last again.
   * A file EmbeddingGemma understands leaves the queue, and so does one already tried.
   */
  fun nextForQuickLook(limit: Int, kinds: Set<FileKind>, videoShare: Double = 0.0): List<PendingFile> = read { db ->
    val visual = kinds.intersect(QUICK_KINDS)
    if (visual.isEmpty()) return@read emptyList()
    fun query(of: Set<FileKind>): List<PendingFile> {
      if (of.isEmpty()) return emptyList()
      val out = ArrayList<PendingFile>()
      db.query(
        "SELECT $FILE_COLUMNS, state, detail FROM files f WHERE f.state IN ('new','searchable','failed') AND f.kind IN (${kindList(of)}) " +
          "AND NOT EXISTS (SELECT 1 FROM segments s WHERE s.file = f.id AND s.modality = 'clip') " +
          "ORDER BY f.mtime DESC, f.id LIMIT ?",
        limit,
      ) { out += PendingFile(fileOf(it), FileState.of(it.getText(12)), it.textOrNull(13)) }
      return out
    }
    QuickMix.pick(query(visual - FileKind.VIDEO), query(visual.intersect(setOf(FileKind.VIDEO))), limit, videoShare)
  }

  /**
   * Saves a file's quick-look vector without touching its words or its state. A null [vector] records "tried, nothing
   * usable" (an unreadable picture), so the pass doesn't retry it on every run. Nothing is saved, and -1 returned, when
   * EmbeddingGemma has understood the file meanwhile: its own vectors supersede, and a late quick vector would sit beside
   * them. Returns the segment id.
   */
  fun saveQuick(fileId: Long, kind: SegmentKind, vector: FloatArray?, locStart: Long? = null, locEnd: Long? = null): Long = write { db ->
    require(vector == null || vector.size == quickDim) { "quick-look vector has ${vector?.size} dimensions, expected $quickDim" }
    db.transaction {
      val state = FileState.of(db.text("SELECT state FROM files WHERE id = ?", fileId))
      if (state !in QUICK_STATES) return@transaction -1L
      db.run("DELETE FROM segments WHERE file = ? AND modality = 'clip'", fileId)
      db.run(
        "INSERT INTO segments(file, kind, modality, loc_start, loc_end, late, vec) VALUES(?, ?, 'clip', ?, ?, 0, ?)",
        fileId, kind.db, locStart, locEnd, vector?.let(Fp16::toBlob),
      )
      db.lastInsertId()
    }
  }

  /** The file has a quick-look picture vector (not just the "tried, unreadable" mark). */
  fun hasQuickVector(fileId: Long): Boolean = read { db ->
    (db.long("SELECT COUNT(*) FROM segments WHERE file = ? AND modality = 'clip' AND vec IS NOT NULL", fileId) ?: 0) > 0
  }

  /**
   * Like [ensureModelFingerprint], for the quick-look model: a different export or preprocessing makes the stored quick
   * vectors incomparable with new queries, so they all go (the pass redoes them in minutes). Returns true when that happened.
   */
  fun ensureQuickFingerprint(fingerprint: String): Boolean = write { db ->
    val old = db.text("SELECT value FROM meta WHERE key = ?", QUICK_FINGERPRINT)
    if (old == fingerprint) return@write false
    db.transaction {
      if (old != null) db.run("DELETE FROM segments WHERE modality = 'clip'")
      db.run("INSERT INTO meta(key, value) VALUES(?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value", QUICK_FINGERPRINT, fingerprint)
    }
    old != null
  }

  // ---- reading -------------------------------------------------------------------------------------------------

  /**
   * A long document's chunks as its first step stored them (reading order; page and text), so later steps embed from
   * the index instead of reading the whole file again: a 592-page PDF took ~110 s to extract on every 40-chunk step.
   */
  fun storedChunks(fileId: Long, from: Int, limit: Int): List<Pair<Long?, String>> = read { db ->
    val out = ArrayList<Pair<Long?, String>>()
    db.query(
      "SELECT s.loc_start, f.body FROM segments s JOIN fts f ON f.rowid = s.id " +
        "WHERE s.file = ? AND s.kind = 'chunk' AND s.late = 0 ORDER BY s.id LIMIT ? OFFSET ?",
      fileId, limit, from,
    ) { out += (if (it.isNull(0)) null else it.getLong(0)) to it.getText(1) }
    out
  }

  fun storedChunkCount(fileId: Long): Int = read { db ->
    (db.long("SELECT COUNT(*) FROM segments WHERE file = ? AND kind = 'chunk' AND late = 0", fileId) ?: 0).toInt()
  }

  fun fileById(id: Long): IndexedFile? = filesByIds(listOf(id))[id]

  fun filesByIds(ids: Collection<Long>): Map<Long, IndexedFile> = read { db ->
    val out = LinkedHashMap<Long, IndexedFile>()
    ids.distinct().chunked(400).forEach { chunk ->
      db.query("SELECT $FILE_COLUMNS, sensitive, looks_private, date_ms FROM files WHERE id IN (${chunk.joinToString(",")}) AND state != 'skipped'") {
        out[it.getLong(0)] = fileOf(it, sensitiveAt = 12, scoredAt = 13, dateAt = 14)
      }
    }
    out
  }

  /**
   * Which of [ids] are genuinely mid-indexing: new, searchable or partial, the same states [IndexStats.waiting]
   * counts. Used only by [com.craneai.findr.search.Searcher] to tell a file that is still waiting for its meaning
   * vectors apart from one that will never have any (a name-only match, say) -- [com.craneai.findr.search.Fusion.score]'s
   * `pending` -- so a weak meaning-only match from an already-understood file does not bury a strong word match from
   * one still waiting its turn. Always empty once a file reaches `understood`, `failed` or `skipped`, so a fully
   * indexed corpus is never affected.
   */
  fun pendingMeaning(ids: Collection<Long>): Set<Long> = read { db ->
    if (ids.isEmpty()) return@read emptySet()
    val out = HashSet<Long>()
    ids.distinct().chunked(400).forEach { chunk ->
      db.query("SELECT id FROM files WHERE id IN (${chunk.joinToString(",")}) AND state IN ('new','searchable','partial')") { out += it.getLong(0) }
    }
    out
  }

  /**
   * The files of the given kinds, newest first by default (what the home screen shows before anything is typed). [order] reorders
   * them in SQL ([SortOrder.BEST] means newest; a junk far-future date sorts after the real ones, see [ClockSense.isJunkFuture]) and [dates] keeps only files whose `date_ms` falls in the range, so a few tens of
   * thousands of files never reach the UI to be filtered there.
   */
  fun recent(
    kinds: Set<FileKind>, limit: Int, includeSensitive: Boolean = false,
    order: SortOrder = SortOrder.NEWEST, dates: DateRange? = null, nowMs: Long = System.currentTimeMillis(),
  ): List<IndexedFile> = read { db ->
    val kinds = kinds - FileKind.OTHER   // browsing lists show real kinds only: APKs, zips and the like stay name-searchable but are never featured
    if (kinds.isEmpty()) return@read emptyList()
    val out = ArrayList<IndexedFile>()
    val visible = if (includeSensitive) "" else "AND sensitive = 0 "
    val by = when (order) {
      // A date past the clock's ceiling is junk (2099): after every real date, not before them (ClockSense.isJunkFuture). Longs only.
      SortOrder.BEST, SortOrder.NEWEST -> "(mtime > ${ClockSense.ceiling(nowMs)}), mtime DESC, id DESC"
      SortOrder.OLDEST -> "mtime, id"
      SortOrder.CREATED_NEWEST -> "(date_ms > ${ClockSense.ceiling(nowMs)}), date_ms DESC, id DESC"
      SortOrder.CREATED_OLDEST -> "date_ms, id"
      SortOrder.LARGEST -> "size DESC, id DESC"
      SortOrder.SMALLEST -> "size, id"
      SortOrder.NAME -> "name COLLATE NOCASE, id"
    }
    db.query(
      "SELECT $FILE_COLUMNS, sensitive, looks_private, date_ms FROM files WHERE state != 'skipped' AND kind IN (${kindList(kinds)}) ${dateClause(dates)}${visible}ORDER BY $by LIMIT ?",
      limit,
    ) { out += fileOf(it, sensitiveAt = 12, scoredAt = 13, dateAt = 14) }
    out
  }

  /** `AND date_ms >= from AND date_ms < until ` (with the trailing space), or nothing. Longs only, so nothing to escape. */
  private fun dateClause(dates: DateRange?): String =
    if (dates == null) "" else (dates.fromMs?.let { "AND date_ms >= $it " } ?: "") + (dates.untilMs?.let { "AND date_ms < $it " } ?: "")

  /** The ids of the files (not skipped) whose date falls in [dates]: the allow-list the meaning search checks its rows against. */
  fun idsInDateRange(dates: DateRange): Set<Long> = read { db ->
    val out = HashSet<Long>()
    db.query("SELECT id FROM files WHERE state != 'skipped' ${dateClause(dates)}") { out += it.getLong(0) }
    out
  }

  /** Sensitive files of the given kinds that [recent] leaves out. */
  fun recentHiddenCount(kinds: Set<FileKind>, dates: DateRange? = null): Int = read { db ->
    val kinds = kinds - FileKind.OTHER
    if (kinds.isEmpty()) return@read 0
    (db.long("SELECT COUNT(*) FROM files WHERE state != 'skipped' AND sensitive != 0 AND kind IN (${kindList(kinds)}) ${dateClause(dates)}") ?: 0).toInt()
  }

  fun stateOf(fileId: Long): Pair<FileState, String?>? = read { db ->
    var out: Pair<FileState, String?>? = null
    db.query("SELECT state, detail FROM files WHERE id = ?", fileId) { out = FileState.of(it.getText(0)) to it.textOrNull(1) }
    out
  }

  /** The text of a segment as stored for keyword search (page text, chunk, OCR). */
  fun textOf(segmentId: Long): String? = read { it.text("SELECT body FROM fts WHERE rowid = ?", segmentId) }

  /** The `info` string the engine stored with a file (where a long file's reading got to, for instance). */
  fun infoOf(fileId: Long): String? = read { it.text("SELECT info FROM files WHERE id = ?", fileId) }

  /** Files per kind and state: the first of [stats]' counts, alone, for callers that only need to know what is still waiting. */
  private fun fileCounts(db: Db): Map<FileKind, MutableMap<FileState, Int>> {
    val byKind = HashMap<FileKind, MutableMap<FileState, Int>>()
    db.query("SELECT kind, state, COUNT(*) FROM files GROUP BY kind, state") {
      val kind = runCatching { FileKind.valueOf(it.getText(0)) }.getOrNull() ?: return@query
      byKind.getOrPut(kind) { HashMap() }[FileState.of(it.getText(1))] = it.getLong(2).toInt()
    }
    return byKind
  }

  /**
   * Files of [kinds] that still need reading: exactly `stats().waiting(kinds)`, without the other counts [stats] makes. Those scan
   * every segment and its vector (about 0.8 s on a phone at 100,000 vectors), and a search asks this on every keystroke.
   */
  fun waiting(kinds: Set<FileKind>): Int = cachedFileCounts().waiting(kinds)

  private fun cachedFileCounts(): IndexStats = cached("fileStats") { db -> IndexStats(fileCounts(db), 0, 0, 0) }

  /**
   * The cheap half of [stats]: files per kind and state, nothing else ([IndexStats.segments], vectors and the quick-look counts are 0).
   * It reads the files table only and is kept until the next write finishes, so asking again and again (the desktop's status, the phone's
   * search on every keystroke) costs nothing while the index is quiet.
   */
  fun fileStats(): IndexStats = cachedFileCounts().copy(bytesOnDisk = bytesOnDisk())

  /** [stats]: the files' counts, with the size on disk as it is now. The heavy counts over every segment are kept until the next write. */
  fun stats(): IndexStats {
    val f = cachedFileCounts()
    val h = cached("segmentStats") { db -> segmentCounts(db) }
    return IndexStats(f.byKindAndState, h.segments, h.vectors, bytesOnDisk(), h.quickOnly, h.quickUnreadable, h.tooLarge)
  }

  private fun segmentCounts(db: Db): IndexStats {
    val byKind = emptyMap<FileKind, Map<FileState, Int>>()
    var segments = 0; var vectors = 0
    db.query("SELECT COUNT(*), COUNT(vec) FROM segments WHERE kind != 'name'") { segments = it.getLong(0).toInt(); vectors = it.getLong(1).toInt() }
    val quick = HashMap<FileKind, Int>()
    val unreadable = HashMap<FileKind, Int>()
    // Single pass over the covering partial index `segments_clip(file, vec IS NOT NULL) WHERE modality = 'clip'`.
    db.query(
      "SELECT f.kind, (s.vec IS NOT NULL), COUNT(DISTINCT s.file) FROM segments s JOIN files f ON f.id = s.file " +
        "WHERE s.modality = 'clip' AND f.state NOT IN ('understood', 'skipped', 'failed') GROUP BY f.kind, (s.vec IS NOT NULL)",
    ) {
      val kind = runCatching { FileKind.valueOf(it.getText(0)) }.getOrNull() ?: return@query
      val hasVec = it.getLong(1) != 0L
      val count = it.getLong(2).toInt()
      if (count > 0) {
        if (hasVec) quick[kind] = count else unreadable[kind] = count
      }
    }
    val tooLarge = (db.long("SELECT COUNT(*) FROM files WHERE state = 'failed' AND detail = ?", TOO_LARGE_DETAIL) ?: 0).toInt()
    return IndexStats(byKind, segments, vectors, 0, quick, unreadable, tooLarge)
  }

  /** What the crawl knows, per kind, of the files that will be read (skipped ones left out): the input of the storage estimate. */
  fun storageTotals(): List<StorageEstimate.KindTotals> = read { db ->
    val out = ArrayList<StorageEstimate.KindTotals>()
    db.query("SELECT kind, COUNT(*), COALESCE(SUM(size), 0), COALESCE(SUM(duration_ms), 0) FROM files WHERE state != 'skipped' GROUP BY kind") {
      val kind = runCatching { FileKind.valueOf(it.getText(0)) }.getOrNull() ?: return@query
      out += StorageEstimate.KindTotals(kind, it.getLong(1).toInt(), it.getLong(2), it.getLong(3))
    }
    out
  }

  /** The write-ahead log's size on disk. It grows with every batch and only shrinks when checkpointed with TRUNCATE. */
  fun walBytes(): Long = walBytesOf(file)

  /**
   * Folds the write-ahead log back into the database when it has grown past [aboveBytes] (0: always).
   *
   * Mid-run calls (`aboveBytes > 0`, `truncate = false`) use non-blocking `PASSIVE` checkpoints so concurrent search
   * readers are never stalled, escalating to `TRUNCATE` only when the WAL exceeds [WAL_TRUNCATE_HIGH_WATER_BYTES]
   * or when [truncate] is true (default when `aboveBytes == 0L`, e.g. end-of-run or after deletes). Returns the bytes
   * given back to disk.
   */
  fun trimWal(aboveBytes: Long = 0, truncate: Boolean = aboveBytes == 0L): Long {
    val before = walBytes()
    if (before <= aboveBytes) return 0
    val mode = if (truncate || before >= WAL_TRUNCATE_HIGH_WATER_BYTES) "TRUNCATE" else "PASSIVE"
    writeLock.withLock { runCatching { writer.query("PRAGMA wal_checkpoint($mode)") { } } }
    val after = walBytes()
    val freed = (before - after).coerceAtLeast(0)
    if (freed > 0) log("wal: checkpointed ${before / 1024} KB -> ${after / 1024} KB")
    return freed
  }

  fun bytesOnDisk(): Long = listOf("", "-wal", "-shm").sumOf { File(file.path + it).let { f -> if (f.exists()) f.length() else 0L } }

  /**
   * Streams every stored vector (id order) to [sink], for building the in-memory matrix. The blob is fp16, little
   * endian. Rows come from a read connection, so indexing can carry on meanwhile.
   */
  fun forEachVector(sink: (segmentId: Long, fileId: Long, fileKind: FileKind, modality: Modality, late: Int, blob: ByteArray) -> Unit) = read { db ->
    forEachVectorOn(db, "WHERE s.vec IS NOT NULL AND f.state != 'skipped'", emptyArray(), sink)
  }

  /** The vectors of one file, for refreshing the in-memory matrix after it was saved. */
  fun forEachVectorOfFile(fileId: Long, sink: (segmentId: Long, fileId: Long, fileKind: FileKind, modality: Modality, late: Int, blob: ByteArray) -> Unit) = read { db ->
    forEachVectorOn(db, "WHERE s.vec IS NOT NULL AND s.file = ? AND f.state != 'skipped'", arrayOf(fileId), sink)
  }

  /**
   * Fetches exact fp16 vector BLOBs for a bounded set of segment primary keys (e.g. Top-100 SQ8 rescore).
   * One primary-key lookup per 400-id batch (< 1 ms). Missing or null-vec ids are omitted.
   */
  fun vectorsBySegmentIds(ids: Collection<Long>): Map<Long, ByteArray> = read { db ->
    if (ids.isEmpty()) return@read emptyMap()
    val out = HashMap<Long, ByteArray>(ids.size * 2)
    ids.distinct().chunked(400).forEach { chunk ->
      db.query("SELECT id, vec FROM segments WHERE id IN (${chunk.joinToString(",")}) AND vec IS NOT NULL") {
        out[it.getLong(0)] = it.getBlob(1)
      }
    }
    out
  }

  /** True when at least one searchable vector is stored. One indexed probe: no scan, and no need to load the matrix first. */
  fun hasVectors(): Boolean = read { db ->
    var found = false
    db.query("SELECT 1 FROM segments s JOIN files f ON f.id = s.file WHERE s.vec IS NOT NULL AND f.state != 'skipped' LIMIT 1") { found = true }
    found
  }

  fun vectorCount(): Int = read { (it.long("SELECT COUNT(*) FROM segments s JOIN files f ON f.id = s.file WHERE s.vec IS NOT NULL AND f.state != 'skipped'") ?: 0).toInt() }

  /**
   * The vector generation: how many changes to the searchable vectors this index has seen (counted by triggers, see
   * [Schema.VEC_GEN_TRIGGERS]). One primary-key lookup. The vector file is trusted on the next start only if it settled at this number.
   */
  fun vectorGeneration(quickLook: Boolean = false): Long =
    read { it.long("SELECT CAST(value AS INTEGER) FROM meta WHERE key = ?", if (quickLook) Schema.Meta.VEC_GEN_QUICK else Schema.Meta.VEC_GEN) ?: 0L }

  private fun forEachVectorOn(
    db: Db, where: String, args: Array<Any?>,
    sink: (Long, Long, FileKind, Modality, Int, ByteArray) -> Unit,
  ) {
    db.query("SELECT s.id, s.file, f.kind, s.modality, s.late, s.vec FROM segments s JOIN files f ON f.id = s.file $where ORDER BY s.id", *args) {
      val kind = runCatching { FileKind.valueOf(it.getText(2)) }.getOrDefault(FileKind.OTHER)
      sink(it.getLong(0), it.getLong(1), kind, Modality.of(it.getText(3)), it.getLong(4).toInt(), it.getBlob(5))
    }
  }

  // ---- helpers -------------------------------------------------------------------------------------------------

  private fun kindList(kinds: Set<FileKind>) = kinds.joinToString(",") { "'${it.name}'" }

  companion object {
    /** Text vectors (transcripts, sound lines) of recordings and videos in a segment-id range: (after, until]. */
    private const val REDO_FROM = "FROM segments s JOIN files f ON f.id = s.file JOIN fts x ON x.rowid = s.id " +
      "WHERE s.id > ? AND s.id <= ? AND s.vec IS NOT NULL AND s.modality IN ('text', 'sound') AND f.kind IN ('VIDEO', 'AUDIO')"
    const val FILE_NAME = "findr-index.db"
    /** Opening: tries after a non-damage error, the first wait (it doubles), and how long SQLite itself waits on a lock. */
    const val OPEN_RETRIES = 5
    const val OPEN_BACKOFF_MS = 250L
    private const val OPEN_BUSY_MS = 10_000
    /** Page cache sizes (KB) and read mmap window for SQLite connections (bounded so 2 GB phones stay comfortably within RAM). */
    private const val WRITER_CACHE_KB = 8_000
    private const val READER_CACHE_KB = 4_000
    private const val MMAP_BYTES = 33_554_432L
    /** High-water mark where a mid-run PASSIVE [trimWal] escalates to TRUNCATE to cap WAL disk growth. */
    const val WAL_TRUNCATE_HIGH_WATER_BYTES = 16L * 1024 * 1024
    /** A file with at least one vector from the previous model (see [Schema.Late.STALE]). */
    private const val HAS_STALE = "EXISTS (SELECT 1 FROM segments s WHERE s.file = files.id AND s.late = ${Schema.Late.STALE})"
    private val OLD_SEES = "kind = 'VIDEO' AND state = 'understood' AND NOT (${ReadingStamps.sqlHas(SeesCatchUp.KEY, SeesCatchUp.VERSION)}) AND NOT ($HAS_STALE)"
    private const val BACKLOG_MARK = "backlog_mark_file_id"

    /** id, uri, rel_path, name, kind, mime, size, mtime, width, height, duration_ms, pages (state, detail follow when asked). */
    /** `files.detail` once the words pass has read a file (its meaning comes later). */
    const val WORDS_DONE = "words"
    /** Trawl Go: a file with nothing to read (a video, a sound file) is done, findable by its name. */
    const val NAME_ONLY = "name"
    /** In `files.detail` when a soundtrack was skipped because the speech models weren't installed yet. */
    const val SPEECH_MISSING = "speech models missing"
    /** In `files.detail` when the lite profile read a picture or video without the quick-look model (pack missing or off). */
    const val QUICK_MISSING = "no quick look"
    const val AUDIO_OFF = "audio off"
    /** The detail of a picture, video or sound this phone's decoders cannot open (FAILED, still found by name). */
    const val CANT_OPEN = "this phone can't open this format"
    private const val PRIORITY_SCHEME_KEY = "priority_scheme"
    private const val PRIORITY_SCHEME = "recent-${Schema.RECENT_DAYS}d-then-kind"
    /**
     * The quick-look model's vector size (TinyCLIP-61M/32: 512; equals QuickLookPack.DIM, a test keeps them equal). Rows of an
     * earlier quick-look model are dropped by the fingerprint check (FindrEngine, QuickLookPass), whatever their size.
     */
    const val QUICK_DIM = 512
    /** Kinds the quick-look pass covers: pictures, and a video's first frame. */
    val QUICK_KINDS = setOf(FileKind.IMAGE, FileKind.SCREENSHOT, FileKind.VIDEO)
    /** Files a quick-look vector may be saved for: not yet understood by EmbeddingGemma (a failed one too: it may never be). */
    /** Repeated deaths with memory pressure recorded during each reading attempt. */
    const val TOO_LARGE_DETAIL = "too large for this phone's memory"
    const val INTERRUPTED_DETAIL = "reading interrupted repeatedly; will retry after an app update"
    private const val INFLIGHT_KEY = "inflight_file"
    private const val RETRY_KEY = "retried_failures_for_build"
    /** "fileId:deaths": the video whose catch-up is running ([beginVideoCatchUp]). */
    private const val CATCHUP_INFLIGHT = "video_catchup_inflight"
    /** An understood video an earlier build read: the catch-up's queue ([VideoCatchUp]). */
    private val OLD_VIDEO = "kind = 'VIDEO' AND state = 'understood' AND NOT (${ReadingStamps.sqlHas(VideoCatchUp.KEY, VideoCatchUp.VERSION)})"
    /** Understood videos and recordings whose speech was judged (FileProcessor writes the verdict in the detail) by a build without the soundtrack stamp. */
    private val SOUNDTRACK_JUDGED = "kind IN ('VIDEO', 'AUDIO') AND state = 'understood' AND " +
      "(detail LIKE '%${com.craneai.findr.engine.SpeechLanguageGate.NOTE_ENGLISH}%' OR detail LIKE '%${com.craneai.findr.engine.SpeechLanguageGate.NOTE_NOT_ENGLISH}%')"
    private val OLD_SOUNDTRACK = "$SOUNDTRACK_JUDGED AND NOT (${ReadingStamps.sqlHas(SoundtrackCatchUp.KEY, SoundtrackCatchUp.VERSION)})"
    private val SKIPPED = Regex("skipped=(\\d+)")

    private val QUICK_STATES = setOf(FileState.NEW, FileState.SEARCHABLE, FileState.FAILED)
    private const val QUICK_FINGERPRINT = "quick_model_fingerprint"
    /** In `files.looks_private` for a picture that could not be read. */
    const val UNREADABLE_SCORE = -1f
    const val FILE_COLUMNS = "id, uri, rel_path, name, kind, mime, size, mtime, width, height, duration_ms, pages"

    /**
     * [sensitiveAt] / [scoredAt]: the column the query selected `sensitive` / `looks_private` as (the lists the UI shows; the
     * work queues don't need them).
     */
    private fun fileOf(s: androidx.sqlite.SQLiteStatement, sensitiveAt: Int = -1, scoredAt: Int = -1, dateAt: Int = -1) = IndexedFile(
      id = s.getLong(0),
      uri = s.getText(1),
      relativePath = s.getText(2),
      name = s.getText(3),
      kind = runCatching { FileKind.valueOf(s.getText(4)) }.getOrDefault(FileKind.OTHER),
      mimeType = s.textOrNull(5),
      sizeBytes = s.getLong(6),
      modifiedMillis = s.getLong(7),
      width = s.intOrNull(8),
      height = s.intOrNull(9),
      durationMs = s.longOrNull(10),
      pageCount = s.intOrNull(11),
      sensitiveFlags = if (sensitiveAt >= 0) s.getLong(sensitiveAt).toInt() else 0,
      looksPrivate = if (scoredAt >= 0 && !s.isNull(scoredAt)) s.getDouble(scoredAt).toFloat().takeIf { it >= 0f } else null,
      privacyUnscored = scoredAt >= 0 && s.isNull(scoredAt) && runCatching { FileKind.valueOf(s.getText(4)) }.getOrNull() in QUICK_KINDS,
      dateMillis = if (dateAt >= 0) s.getLong(dateAt).takeIf { it > 0 } ?: s.getLong(7) else s.getLong(7),
    )

    /**
     * Opens (creating if needed) the index in [dir], and never deletes learned data for a problem that can be mended
     * (the rules are in [IndexRecovery]; each outcome logs one line with the reason and no file name):
     *  - an error that is no proof of damage (busy, locked, out of memory or space, an I/O error) is retried [retries] times with
     *    a growing wait of [backoffMs], and then thrown with the file untouched;
     *  - a full-text index that fails its check is rebuilt in place by [create];
     *  - damage to a main table is confirmed by a second look, then every readable row is copied into a new file;
     *  - only an index with nothing readable, or one from a newer Trawl (a downgrade), is deleted and started again.
     * It used to be moved aside as `*.corrupt-<time>`, which kept a full copy of the user's text that nothing ever removed;
     * any such copy, and any half-made salvage file, is deleted here.
     */
    private fun walBytesOf(file: File): Long = File(file.path + "-wal").let { if (it.exists()) it.length() else 0L }

    /** Deterministic, order-independent SHA-256 fingerprint of [r] for O(1) [applyRules] short-circuiting. */
    internal fun rulesFingerprint(r: PrivacyRules): String {
      val md = java.security.MessageDigest.getInstance("SHA-256")
      fun feed(tag: String, items: List<String>) {
        md.update(tag.toByteArray(Charsets.UTF_8))
        md.update(0)
        for (s in items) {
          val b = s.toByteArray(Charsets.UTF_8)
          md.update(b.size.toString().toByteArray(Charsets.UTF_8))
          md.update(':'.code.toByte())
          md.update(b)
          md.update(0)
        }
      }
      feed("f", r.privateFolders.map { PrivacyRules.normalize(it) }.filter { it.isNotEmpty() }.distinct().sorted())
      feed("m", r.markedFiles.sorted())
      return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun open(
      dir: File,
      driver: SQLiteDriver = BundledSQLiteDriver(),
      dim: Int = Fp16.DIM,
      readers: Int = 2,
      log: (String) -> Unit = { com.craneai.findr.core.FindrLog.w("FindrIndex", it) },
      quickDim: Int = QUICK_DIM,
      retries: Int = OPEN_RETRIES,
      backoffMs: Long = OPEN_BACKOFF_MS,
      /** Called with the error each time a transient failure is about to be retried (before the wait), so a caller can say "trying again" at once. */
      onRetry: (Throwable) -> Unit = {},
      /**
       * When true, skips the blocking full-table + FTS5 [IndexRecovery.check] on open if the store was closed cleanly
       * ([Schema.Meta.CLEAN_CLOSE] == `'1'`), the WAL is empty, and no schema migration is needed. Callers may invoke
       * [verifyIntegrity] asynchronously off the cold-start critical path.
       */
      fastOpen: Boolean = false,
    ): IndexStore {
      dir.mkdirs()
      val file = File(dir, FILE_NAME)
      dir.listFiles { f -> f.name.startsWith("$FILE_NAME.corrupt-") }?.forEach { it.delete() }
      IndexRecovery.deleteWithSidecars(File(file.path + IndexRecovery.SALVAGE_SUFFIX))
      var tries = 0
      var looks = 0
      var salvaged = false
      while (true) {
        try {
          return create(file, driver, dim, readers, log, quickDim, fastOpen)
        } catch (t: Throwable) {
          val brief = com.craneai.findr.core.FindrLog.brief(t)
          when {
            t is IndexRecovery.TooNew -> return startFresh(file, driver, dim, readers, log, quickDim, "${t.message}", t)
            IndexRecovery.isDamage(t) || (t is androidx.sqlite.SQLiteException && IndexRecovery.notADatabase(file)) -> {
              if (salvaged) return startFresh(file, driver, dim, readers, log, quickDim, "still failing after a salvage: $brief", t)
              if (++looks == 1) {   // a second look first: a check that fails once may only have met a file another process was still closing
                log("index failed a check ($brief); looking again")
                Thread.sleep(backoffMs)
                continue
              }
              log("index is damaged ($brief); copying out everything readable")
              com.craneai.findr.diag.Diag.handled(com.craneai.findr.diag.HandledKind.INDEX_CORRUPT, t)
              val kept = IndexRecovery.salvage(file, driver, log)
              if (kept == null) return startFresh(file, driver, dim, readers, log, quickDim, "nothing readable: $brief", t)
              log("kept ${kept.files} files and ${kept.segments} segments; ${kept.unreadable} unreadable rows, their files will be read again")
              salvaged = true
            }
            t is androidx.sqlite.SQLiteException && tries < retries -> {
              tries++
              log("index could not be opened ($brief); keeping it, try $tries of $retries")
              runCatching { onRetry(t) }
              Thread.sleep(backoffMs * (1L shl (tries - 1)))
            }
            else -> {
              log("index could not be opened ($brief); keeping it untouched")
              throw t
            }
          }
        }
      }
    }

    /** The last resort: nothing in the file can be used. The reason is logged first; the deleted file leaves no copy behind. */
    private fun startFresh(
      file: File, driver: SQLiteDriver, dim: Int, readers: Int, log: (String) -> Unit, quickDim: Int, reason: String, cause: Throwable,
    ): IndexStore {
      log("index unusable ($reason); deleting it and starting fresh")
      com.craneai.findr.diag.Diag.handled(com.craneai.findr.diag.HandledKind.INDEX_CORRUPT, cause)
      IndexRecovery.deleteWithSidecars(file)
      IndexRecovery.deleteWithSidecars(File(file.path + IndexRecovery.SALVAGE_SUFFIX))
      return create(file, driver, dim, readers, log, quickDim, fastOpen = false)
    }

    private fun setMetaIn(db: Db, key: String, value: String) =
      db.run("INSERT INTO meta(key, value) VALUES(?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value", key, value)

    private fun create(
      file: File, driver: SQLiteDriver, dim: Int, readerCount: Int, log: (String) -> Unit, quickDim: Int, fastOpen: Boolean,
    ): IndexStore {
      val opened = ArrayList<Db>()
      try {
        val writer = Db(driver.open(file.path)).also { opened += it }
        writer.exec("PRAGMA busy_timeout = $OPEN_BUSY_MS")   // an open may have to wait for a process that is still closing the file
        writer.query("PRAGMA journal_mode = WAL") { }
        writer.exec("PRAGMA synchronous = NORMAL")
        writer.exec("PRAGMA foreign_keys = ON")
        // Deleted rows are zeroed on disk, not just unlinked, so a forensic copy can't read back a deleted file's words.
        writer.exec("PRAGMA secure_delete = ON")
        // Keep temp B-trees (FTS5 / ORDER BY / GROUP BY) off flash, expand page cache, and enable read mmap where supported.
        writer.exec("PRAGMA temp_store = MEMORY")
        writer.exec("PRAGMA cache_size = -$WRITER_CACHE_KB")
        runCatching { writer.query("PRAGMA mmap_size = $MMAP_BYTES") { } }
        val version = writer.long("PRAGMA user_version") ?: 0
        if (version > Schema.VERSION) throw IndexRecovery.TooNew("index was made by a newer Trawl (schema $version)")
        var cleanOnOpen = false
        if (version > 0) {
          val markedClean = writer.text("SELECT value FROM meta WHERE key = ?", Schema.Meta.CLEAN_CLOSE) == "1"
          val skipCheck = fastOpen && version == Schema.VERSION && walBytesOf(file) == 0L && markedClean
          if (skipCheck) {
            cleanOnOpen = true
          } else {
            val repaired = IndexRecovery.check(writer, log)
            if (repaired && markedClean) {
              setMetaIn(writer, Schema.Meta.CLEAN_CLOSE, "0")
            } else {
              cleanOnOpen = markedClean
            }
          }
        }
        if (version == 0L) {
          writer.transaction {
            Schema.CREATE.forEach { writer.exec(it) }
            writer.run("INSERT INTO meta(key, value) VALUES(?, ?)", Schema.Meta.SCHEMA, Schema.VERSION.toString())
            writer.exec("PRAGMA user_version = ${Schema.VERSION}")
          }
          log("created index schema ${Schema.VERSION}")
        }
        check(Schema.MIGRATIONS.size.toLong() == Schema.VERSION - 1) { "Schema.MIGRATIONS doesn't cover every version" }
        // A fresh index was just created at the current version, so there is nothing to migrate (until now no migration
        // existed, which hid that the loop used to start at 0 and would have run step 1 -> 2 on a brand-new database).
        for (from in (if (version == 0L) Schema.VERSION else version) until Schema.VERSION) {
          writer.transaction {
            setMetaIn(writer, Schema.Meta.CLEAN_CLOSE, "0")
            Schema.MIGRATIONS[(from - 1).toInt()].forEach { writer.exec(it) }
            writer.run("UPDATE meta SET value = ? WHERE key = ?", (from + 1).toString(), Schema.Meta.SCHEMA)
            writer.exec("PRAGMA user_version = ${from + 1}")
          }
          cleanOnOpen = false
          log("upgraded index schema $from -> ${from + 1}")
        }
        // FTS5 keeps a deleted row's words in its index until a merge, unless secure-delete is on (SQLite 3.42+). Stored
        // in the table's config, so it is set once per index; checking first keeps an open from writing.
        if (writer.long("SELECT v FROM fts_config WHERE k = 'secure-delete'") != 1L) {
          writer.transaction {
            setMetaIn(writer, Schema.Meta.CLEAN_CLOSE, "0")
            writer.run("INSERT INTO fts(fts, rank) VALUES('secure-delete', 1)")
          }
          cleanOnOpen = false
        }
        if (writer.long("SELECT v FROM sees_fts_config WHERE k = 'secure-delete'") != 1L) {
          writer.transaction {
            setMetaIn(writer, Schema.Meta.CLEAN_CLOSE, "0")
            writer.run("INSERT INTO sees_fts(sees_fts, rank) VALUES('secure-delete', 1)")
          }
          cleanOnOpen = false
        }
        val pool = ArrayBlockingQueue<Db>(readerCount)
        repeat(readerCount) {
          val reader = Db(driver.open(file.path)).also { opened += it }
          reader.exec("PRAGMA busy_timeout = 5000")
          reader.exec("PRAGMA query_only = ON")
          reader.exec("PRAGMA temp_store = MEMORY")
          reader.exec("PRAGMA cache_size = -$READER_CACHE_KB")
          runCatching { reader.query("PRAGMA mmap_size = $MMAP_BYTES") { } }
          pool.put(reader)
        }
        return IndexStore(file, writer, pool, dim, log, quickDim, cleanClose = cleanOnOpen)
      } catch (t: Throwable) {
        opened.forEach { runCatching { it.close() } }
        throw t
      }
    }
  }
}
