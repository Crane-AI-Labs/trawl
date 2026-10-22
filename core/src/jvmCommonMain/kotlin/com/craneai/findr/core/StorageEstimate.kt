package com.craneai.findr.core

/**
 * How much room the search index will take, before it is built: the files counted by kind, times what each kind costs.
 * Pure, so it is unit-tested; the costs are measured numbers, kept here once.
 *
 * Measured 2026-10-15 on the Infinix X6885: a finished index of 250 files (116 photos, 48 screenshots, 58 text documents,
 * 4 PDFs, 9 videos of 10.9 minutes in all, 22 other files known by name) was 1,306,624 bytes. SQLite's own page accounting
 * (dbstat) was split across the kinds by who owns each row. Per file that was 3.7 KB a photo, 4.1 KB a screenshot (more OCR
 * text), 3.5 KB a document chunk (about 340 characters of text: the vector row, its text, its word-index entries and the
 * file's own row), 7.1 KB a minute of video (2.75 vectors a minute) and 0.6 KB for a file known by name. A small index pays
 * more in half-empty pages than a big one, so for a big phone these run a little high, which is the safe side for a warning.
 * The A06's earlier 2.5 KB a photo came from a different accounting and is superseded.
 *
 * The memory figure follows what VectorIndex really holds. The vectors themselves are in memory-mapped files (4,096-row
 * chunks, so they cost a whole chunk each time they grow, plus a 4 KB header; no file at all until the first vector), and the
 * row facts are on the Java heap in arrays that start at 1,024 rows (256 for the quick-look index) and grow by half, plus
 * the segment lookup. The mapped files count twice: as search memory (the kernel keeps the pages in use and may drop them)
 * and as disk, on top of the database. A test holds this estimate against a real VectorIndex.
 */
object StorageEstimate {
  /** A fully indexed photo or screenshot: file row + one vector segment + OCR text and word index. Measured: photos 3.7 KB, screenshots 4.1 KB. */
  const val IMAGE_BYTES = 3_800L

  /** One text-document chunk, with the file's own cost spread over its chunks. Measured: 3.5 KB. */
  const val DOC_CHUNK_BYTES = 3_500L

  /** Video and recordings, per minute of footage, on top of the file's own row. Measured on 9 clips: 7.1 KB. */
  const val MEDIA_BYTES_PER_MINUTE = 7_100L

  /** Vectors per minute of video or recording. Measured 2.75 (frames, sound labels, transcript); rounded up. */
  const val MEDIA_VECTORS_PER_MINUTE = 3

  /** A file known by name only (a row and its name in the word index). Measured 0.6 KB. */
  const val NAME_ONLY_BYTES = 600L

  /** How many bytes of a text document make one chunk. Measured on 58 text files: 380 bytes (342 characters of text). */
  const val DOC_BYTES_PER_CHUNK = 400L

  /** PDFs carry layout and fonts, so a chunk of text is far more file. Measured on 4 PDFs only: 7.8 KB a chunk. Low confidence. */
  const val PDF_BYTES_PER_CHUNK = 8_000L

  /** No document makes more chunks than this (the average file of the kind is clamped). */
  const val DOC_MAX_CHUNKS = 60

  /** Per row on the heap, apart from the vector: segment id, file id, kind, modality, late flag. */
  private const val ROW_ARRAYS_BYTES = 8 + 8 + 1 + 1 + 1L

  /** The segment lookup (LongIntMap): 12 bytes a slot, a power of two of at least 32 slots, at most half full. */
  private const val LOOKUP_SLOT_BYTES = 12L
  private const val LOOKUP_MIN_SLOTS = 32L

  /** The vector file (VectorFile): a 4 KB header, then chunks of 4,096 rows of 24 bytes of facts and the vector. */
  private const val FILE_HEADER_BYTES = 4_096L
  private const val FILE_ROWS_PER_CHUNK = 4_096L
  private const val FILE_FACTS_BYTES = 24L

  /** EmbeddingGemma vectors: 768 fp16 values, 1,024 rows to start. */
  private const val VECTOR_BYTES = 768 * 2L
  private const val MATRIX_START_ROWS = 1_024L

  /** The quick-look matrix: 512 fp16 values a row, 256 rows to start. Photos leave it once they are fully read. */
  private const val QUICK_VECTOR_BYTES = 512 * 2L
  private const val QUICK_START_ROWS = 256L

  /** What the crawl knows about the files of one kind (skipped files left out). */
  data class KindTotals(val kind: FileKind, val files: Int, val bytes: Long, val durationMs: Long)

  /** [indexBytes]: the database when everything is read. [vectors] and [ramBytes]: the matrices searching holds in memory. */
  data class Result(val indexBytes: Long, val vectors: Long, val ramBytes: Long)

  /** [indexed]: the kinds Findr will read (the settings); other kinds cost only their names. [withVectors] is false in Trawl Go. */
  fun estimate(totals: List<KindTotals>, indexed: Set<FileKind>, withVectors: Boolean = true): Result {
    var bytes = 0L
    var vectors = 0L
    for (t in totals) {
      if (t.files <= 0) continue
      if (t.kind !in indexed) {
        bytes += t.files * NAME_ONLY_BYTES
        continue
      }
      when (t.kind) {
        FileKind.IMAGE, FileKind.SCREENSHOT -> {
          bytes += t.files * IMAGE_BYTES
          vectors += t.files
        }
        FileKind.DOCUMENT, FileKind.PDF -> {
          val chunks = docChunks(t, if (t.kind == FileKind.PDF) PDF_BYTES_PER_CHUNK else DOC_BYTES_PER_CHUNK)
          bytes += chunks * DOC_CHUNK_BYTES
          vectors += chunks
        }
        FileKind.VIDEO, FileKind.AUDIO -> {
          val minutes = t.durationMs / 60_000.0
          bytes += t.files * NAME_ONLY_BYTES + (minutes * MEDIA_BYTES_PER_MINUTE).toLong()
          vectors += (minutes * MEDIA_VECTORS_PER_MINUTE).toLong()
        }
        FileKind.OTHER -> bytes += t.files * NAME_ONLY_BYTES
      }
    }
    if (!withVectors) return Result(bytes, 0, 0)   // Trawl Go keeps no vectors: no mapped files and no matrices
    val files = mappedBytes(vectors, VECTOR_BYTES) + mappedBytes(0, QUICK_VECTOR_BYTES)
    val heap = heapBytes(vectors, MATRIX_START_ROWS) + heapBytes(0, QUICK_START_ROWS)
    return Result(bytes + files, vectors, heap + files)
  }

  /** Chunks for a kind of documents: each file makes size / [bytesPerChunk] chunks (rounded up), between 1 and [DOC_MAX_CHUNKS], summed from the average. */
  private fun docChunks(t: KindTotals, bytesPerChunk: Long): Long {
    val average = t.bytes / t.files
    return t.files * ((average + bytesPerChunk - 1) / bytesPerChunk).coerceIn(1L, DOC_MAX_CHUNKS.toLong())   // a part-filled last chunk still costs a chunk
  }

  /** What VectorIndex keeps on the heap for [rows] vectors: capacity starts at [start] and grows by half (at least 1,024 rows) when full. */
  private fun heapBytes(rows: Long, start: Long): Long {
    var capacity = start
    while (capacity < rows) capacity = maxOf(capacity * 3 / 2, capacity + 1_024)
    var slots = LOOKUP_MIN_SLOTS
    while (slots < rows * 2) slots = slots shl 1
    return capacity * ROW_ARRAYS_BYTES + slots * LOOKUP_SLOT_BYTES
  }

  /** The vector file for [rows] vectors of [vectorBytes] each: nothing until the first one, then the header and whole chunks. */
  private fun mappedBytes(rows: Long, vectorBytes: Long): Long {
    if (rows <= 0) return 0
    val chunks = (rows + FILE_ROWS_PER_CHUNK - 1) / FILE_ROWS_PER_CHUNK
    return FILE_HEADER_BYTES + chunks * FILE_ROWS_PER_CHUNK * (FILE_FACTS_BYTES + vectorBytes)
  }
}
