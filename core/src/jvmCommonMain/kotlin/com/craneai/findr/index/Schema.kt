package com.craneai.findr.index

import com.craneai.findr.core.FileKind

/**
 * The index file's layout. One SQLite file holds everything, so a crash can never leave vectors out of step with the
 * rows that describe them. Differences from the first sketch, and why:
 *  - `files` is unique on (volume, mediastore_id): MediaStore ids are only unique per volume, and an SD card has its own.
 *  - `segments.text` is gone. The words live once, in `fts.body`, and snippets read them back from there; keeping a
 *    second copy would double the text on disk for nothing.
 *  - `segments.late` flags vectors from the later steps of a long file (see [Segment]).
 *  - A trigger drops the keyword row when a segment goes, so a cascade from `files` can never leave words behind.
 */
object Schema {
  const val VERSION = 6L

  /**
   * `meta.vec_gen` (EmbeddingGemma's modalities) and `meta.vec_gen_quick` (the quick-look pass's) count every change to what
   * the matching in-memory vector index holds: a vector added, replaced, re-flagged or removed, and a file that becomes or
   * stops being skipped. Each index keeps its vectors in a memory-mapped file ([com.craneai.findr.search.VectorFile]) and
   * trusts it on the next start only if the generation it last settled at is the generation now: a change the file did not
   * see (the app was killed between the database commit and the file update) can therefore never be missed, and the vectors
   * are never re-read from SQLite when nothing changed. Two counters, because the quick-look vectors come and go all day and
   * must not make the other file look out of date.
   */
  private fun bump(key: String) =
    "INSERT INTO meta(key, value) VALUES('$key', '1') ON CONFLICT(key) DO UPDATE SET value = CAST(value AS INTEGER) + 1;"

  private fun vecGenTriggers(name: String, key: String, own: (String) -> String): List<String> = listOf(
    """
    CREATE TRIGGER ${name}_insert AFTER INSERT ON segments WHEN new.vec IS NOT NULL AND ${own("new.modality")} BEGIN
      ${bump(key)}
    END
    """,
    """
    CREATE TRIGGER ${name}_update AFTER UPDATE OF vec, late, file, modality ON segments WHEN ${own("new.modality")} OR ${own("old.modality")} BEGIN
      ${bump(key)}
    END
    """,
    """
    CREATE TRIGGER ${name}_delete AFTER DELETE ON segments WHEN old.vec IS NOT NULL AND ${own("old.modality")} BEGIN
      ${bump(key)}
    END
    """,
  )

  val VEC_GEN_TRIGGERS: List<String> =
    vecGenTriggers("vec_gen", Meta.VEC_GEN) { "$it != 'clip'" } +
      vecGenTriggers("vec_gen_quick", Meta.VEC_GEN_QUICK) { "$it = 'clip'" } +
      """
    CREATE TRIGGER vec_gen_file AFTER UPDATE OF state, kind ON files WHEN (new.state = 'skipped') != (old.state = 'skipped') OR new.kind != old.kind BEGIN
      ${bump(Meta.VEC_GEN)}
      ${bump(Meta.VEC_GEN_QUICK)}
    END
    """

  /**
   * Steps from one schema version to the next: MIGRATIONS[0] takes an index from version 1 to 2, and so on. An app
   * update upgrades the index in place, one transaction per step, instead of throwing a user's index away. To change
   * the layout: bump VERSION, change CREATE, add the step here, and freeze the old layout as
   * src/test/resources/schema/v<old>.sql (kotlin-fhir-engine's schema discipline, the research notes).
   * SchemaMigrationTest fails if CREATE changes without that.
   */
  val MIGRATIONS: List<List<String>> = listOf(
    // 1 -> 2: `files.sensitive`, the privacy flag bits (see [Sensitive]). SQLite splices an added column in right after the
    // last column definition, so CREATE below spells it the same way and a fresh index matches an upgraded one.
    listOf("ALTER TABLE files ADD COLUMN sensitive INTEGER NOT NULL DEFAULT 0"),
    // 2 -> 3: the "looks private" score of a picture (see PrivateLook). NULL: not scored yet; negative: tried, the picture can't be read.
    listOf("ALTER TABLE files ADD COLUMN looks_private REAL"),
    // 3 -> 4: the vector generation counters (see [VEC_GEN_TRIGGERS]). Triggers only: no column, no data moves.
    VEC_GEN_TRIGGERS,
    // 4 -> 5: `files.date_ms`, the date a person would give a file: the EXIF capture date of a photo (MediaStore DATE_TAKEN) when
    // it has one, otherwise the modified time. Every row starts at its mtime; clearing the MediaStore generations makes the next
    // crawl read every row again, which brings in the capture dates (the unchanged branch of upsertOne refreshes only this column).
    listOf(
      "ALTER TABLE files ADD COLUMN date_ms INTEGER NOT NULL DEFAULT 0",
      "UPDATE files SET date_ms = mtime",
      "CREATE INDEX files_date ON files(date_ms DESC)",
      "DELETE FROM meta WHERE key LIKE 'ms_generation:%'",
    ),
    // 5 -> 6: `segments.marks`, where each spoken segment sits inside a transcript piece (see [com.craneai.findr.core.TranscriptMarks]),
    // so a hit can say when the matched words were said. NULL on every old row, which keeps working (placed by character position).
    listOf("ALTER TABLE segments ADD COLUMN marks TEXT"),
  )

  /** FTS5 table: file names in `name`, folder words and extracted/OCR text in `body`. Porter stems word endings. */
  const val FTS_TOKENIZER = "porter unicode61 remove_diacritics 2"

  val CREATE = listOf(
    """
    CREATE TABLE meta(
      key TEXT PRIMARY KEY,
      value TEXT NOT NULL
    )
    """,
    """
    CREATE TABLE files(
      id INTEGER PRIMARY KEY,
      volume TEXT NOT NULL,
      mediastore_id INTEGER NOT NULL,
      uri TEXT NOT NULL,
      rel_path TEXT NOT NULL,
      name TEXT NOT NULL,
      kind TEXT NOT NULL,
      mime TEXT,
      size INTEGER NOT NULL,
      mtime INTEGER NOT NULL,
      width INTEGER,
      height INTEGER,
      duration_ms INTEGER,
      pages INTEGER,
      state TEXT NOT NULL DEFAULT 'new',
      detail TEXT,
      fingerprint TEXT NOT NULL,
      priority INTEGER NOT NULL DEFAULT 9,
      info TEXT,
      indexed_at INTEGER, sensitive INTEGER NOT NULL DEFAULT 0, looks_private REAL, date_ms INTEGER NOT NULL DEFAULT 0,
      UNIQUE(volume, mediastore_id)
    )
    """,
    "CREATE INDEX files_work ON files(state, priority, mtime DESC)",
    "CREATE INDEX files_kind_mtime ON files(kind, mtime DESC)",
    "CREATE INDEX files_date ON files(date_ms DESC)",
    """
    CREATE TABLE segments(
      id INTEGER PRIMARY KEY,
      file INTEGER NOT NULL REFERENCES files(id) ON DELETE CASCADE,
      kind TEXT NOT NULL,
      modality TEXT NOT NULL DEFAULT 'none',
      loc_start INTEGER,
      loc_end INTEGER,
      late INTEGER NOT NULL DEFAULT 0,
      vec BLOB
    , marks TEXT)
    """,
    "CREATE INDEX segments_file ON segments(file)",
    "CREATE VIRTUAL TABLE fts USING fts5(name, body, tokenize = '$FTS_TOKENIZER')",
    """
    CREATE TRIGGER segments_gone AFTER DELETE ON segments BEGIN
      DELETE FROM fts WHERE rowid = old.id;
    END
    """,
  ) + VEC_GEN_TRIGGERS

  /**
   * `files.sensitive` is a set of bits, so each reason a file is hidden can be added and removed on its own. A file is
   * sensitive while any bit is set. Only the person sets them: there is no classifier (the research notes).
   */
  object Sensitive {
    /** The person marked this file ("Mark as sensitive"). */
    const val MARKED = 1
    /** The file sits in a folder the person marked private. */
    const val FOLDER = 2
  }

  /**
   * What `segments.late` holds (an INTEGER, so this adds no column and needs no schema version). 0: a current vector that
   * sets the z-scale. [LATER_STEP]: a vector from a later step of a long file, scored on the scale but not moving it.
   * [STALE]: a vector made by the previous model, kept searchable until its file is read again with the new one (the
   * progressive re-embed, [IndexStore.ensureModelFingerprint]); it too is scored but does not move the scale. A new read
   * replaces the whole file's segments in one transaction, so a stale vector disappears exactly when its successor appears.
   */
  object Late {
    const val CURRENT = 0
    const val LATER_STEP = 1
    const val STALE = 2
  }

  /** A file modified within this many days runs before the older backlog, whatever its kind. */
  const val RECENT_DAYS = 14L
  const val RECENT_MS = RECENT_DAYS * 24 * 60 * 60 * 1000

  /**
   * The order of the work queue, stored with the row so the queue stays an index scan: files from the last [RECENT_DAYS] days
   * come first (0, newest first by mtime), then the older backlog by kind (1 + [kindPriority]). A voice note received today
   * used to wait behind the whole photo backlog. A file stays at the front after it ages past the window: that's fine, it
   * only reorders the backlog. 'Prioritize' sets -1 and is kept by [IndexStore.reprioritise].
   */
  fun priority(kind: FileKind, mtimeMs: Long, nowMs: Long): Int =
    if (mtimeMs >= nowMs - RECENT_MS) 0 else 1 + kindPriority(kind)

  /**
   * The words pass's own order (document text, text read from images): recent and prioritized files keep their place, then the
   * older backlog runs documents first, then screenshots, then photos, then big documents. Measured on the TECNO KN3 with a
   * realistic phone (internal/research/usage-sim.md): a document's text takes a median 0.05 s, a photo's OCR 0.7 s, so in
   * [kindPriority]'s order 1,000 old photos kept every old document unsearchable by its words for about 12 minutes (5,000 for an
   * hour), for work that cost the documents 3 s in all. A document over [WORDS_BIG_DOC_BYTES] (the 1,161-page clinical guidelines
   * took 49 s) goes after the photos, so a few long PDFs can't hold up everyone's screenshots and receipts.
   * An ORDER BY expression, not a column: the queue is fetched 8 files at a time, so a sort per fetch is noise.
   */
  const val WORDS_BIG_DOC_BYTES = 5L * 1024 * 1024
  val WORDS_ORDER = "CASE WHEN priority <= 0 THEN priority ELSE CASE " +
    "WHEN kind IN ('DOCUMENT','PDF') AND size <= $WORDS_BIG_DOC_BYTES THEN 1 WHEN kind = 'SCREENSHOT' THEN 2 " +
    "WHEN kind = 'IMAGE' THEN 3 WHEN kind IN ('DOCUMENT','PDF') THEN 4 ELSE 5 END END"

  /** Within the older backlog: what people look for first. Lower runs earlier. */
  fun kindPriority(kind: FileKind): Int = when (kind) {
    FileKind.SCREENSHOT -> 0
    FileKind.IMAGE -> 1
    FileKind.DOCUMENT, FileKind.PDF -> 2
    FileKind.VIDEO -> 3
    FileKind.AUDIO -> 4
    FileKind.OTHER -> 9
  }

  object Meta {
    const val MODEL_FINGERPRINT = "model_fingerprint"
    /** The text model alone (D4): when only it changes, text is re-embedded and image/audio vectors stay. */
    const val TEXT_FINGERPRINT = "text_fingerprint"
    /** "after:until": text vectors of recordings and videos still to re-embed after a text-model change. */
    const val TEXT_REDO = "text_redo"
    /** How many files the last model upgrade queued for a progressive re-embed: the M of "N of M" on the meter. */
    const val REEMBED_TOTAL = "reembed_total"
    const val SCHEMA = "schema_version"
    /** Bumped by triggers on every change to EmbeddingGemma's searchable vectors ([VEC_GEN_TRIGGERS]). Absent means none yet. */
    const val VEC_GEN = "vec_gen"
    /** The same for the quick-look vectors (modality `clip`). */
    const val VEC_GEN_QUICK = "vec_gen_quick"
    /** Which model wrote the `files.looks_private` scores; a different one clears them so the pictures are scored again. */
    const val PRIVATE_MODEL = "private_model"
    fun mediaVersion(volume: String) = "ms_version:$volume"
    fun mediaGeneration(volume: String) = "ms_generation:$volume"
  }
}
