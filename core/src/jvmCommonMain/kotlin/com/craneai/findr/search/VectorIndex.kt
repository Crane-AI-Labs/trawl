package com.craneai.findr.search

import com.craneai.findr.core.FileKind
import com.craneai.findr.core.FindrLog as Log
import com.craneai.findr.index.Fp16
import com.craneai.findr.index.IndexStore
import com.craneai.findr.index.Modality
import com.craneai.findr.index.Schema
import java.io.File
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Every embedding, ready to be scored against a query in tens of milliseconds.
 *
 * Vectors are stored in either [VectorFormat.FP16] (v2, 2 B/dim) or [VectorFormat.SQ8] (v3, 4 B float32 scale +
 * 1 B signed int8/dim, ~50–62% smaller and optionally accelerated by ARM64 NEON `SDOT`). They live **off the Java heap**:
 * with a [backing] file they are in a memory-mapped file that is the index's own store ([VectorFile]), so 100k vectors
 * cost the heap nothing, a cold start maps the file instead of reading every vector out of SQLite, and the kernel can
 * drop the pages under memory pressure. Without one (unit tests, and the fallback when the file cannot be made or grown)
 * they live in primitive arrays on the heap, scored by the same arithmetic.
 *
 * Only the per-row facts search needs without touching SQLite (segment, file, kind, modality, whether it is a "late"
 * vector) stay on the heap, about 23 bytes a row plus a single file->headRow lookup (segment dedup walks the file's
 * short intrusive list via [headOfFile]/[fileNext], saving ~50 MB on large indexes compared to a separate segment map).
 * The same facts are mirrored in the file, which is what lets the next start skip SQLite.
 *
 * Changes are incremental: a file that was just understood adds its rows, a deleted or re-read file removes
 * its rows by swapping the last row into the hole (so removing costs one row copy, not a rebuild). Row order is
 * therefore not stable; nothing may depend on it. Scoring and changes are guarded by a read/write lock.
 *
 * The file is a cache of SQLite, which stays the truth. It is trusted at start only if it was settled (every change flushed,
 * see [VectorFile]) at the database's current vector generation; otherwise [load] rebuilds it from SQLite, so a crash, a
 * corrupt or missing file, or a change made behind its back costs one slow start and never a wrong answer.
 */
class VectorIndex(
  val dim: Int = Fp16.DIM,
  initialCapacity: Int = 1024,
  /** Threads used by [scores]. 1 scores on the calling thread; more split the rows into blocks. */
  parallelism: Int = defaultParallelism(),
  /**
   * The modalities this index holds. Vectors from different models live in different spaces and are scored against
   * different query vectors, so each model gets its own index: EmbeddingGemma's (the default) and the quick-look one.
   */
  val holds: Set<Modality> = EMBEDDING_GEMMA,
  /** The file the vectors are mapped from, or null to keep them on the heap. The directory is created; the file on first use. */
  private val backing: File? = null,
  /** How long after the last change the file is flushed and marked settled; negative: only [settleNow] does it (tests). */
  private val settleDelayMs: Long = SETTLE_DELAY_MS,
  /** True when nothing is in flight that the file has not seen (the indexing worker and the crawl are idle). Settling waits for it. */
  private val quiet: () -> Boolean = { true },
  private val rowsPerChunk: Int = VectorFile.ROWS_PER_CHUNK,
  /** The two-stage scan (see [TwoStage]), or null for an index that is only ever scanned exactly (the quick-look one). */
  val twoStage: TwoStage? = null,
  /** On-disk and heap storage format: [VectorFormat.FP16] (v2) or [VectorFormat.SQ8] (v3). */
  val format: VectorFormat = VectorFormat.FP16,
  /** Whether to use ARM64 NEON `SDOT` when [format] is [VectorFormat.SQ8] and [Sq8Native.available] is true. */
  private val useNative: Boolean = true,
) {
  private val lock = ReentrantReadWriteLock()
  /** Width of the stage-1 blocks kept beside the vectors (0: none). */
  private val stageDim: Int = twoStage?.stageDim?.also { require(it in 1 until dim) { "stage width $it must be in 1 until $dim" } } ?: 0
  /** The fp16 vectors when on the heap (empty while [file] holds them or in [VectorFormat.SQ8]). */
  private var halves = ShortArray(if (backing == null && format == VectorFormat.FP16) initialCapacity * dim else 0)
  /** The fp16 stage-1 blocks when on the heap, row r at `r * stageDim`. */
  private var stageHalves = ShortArray(if (backing == null && format == VectorFormat.FP16) initialCapacity * stageDim else 0)
  /** The SQ8 vectors and scales when on the heap (empty while [file] holds them or in [VectorFormat.FP16]). */
  private var q8Vecs = ByteArray(if (backing == null && format == VectorFormat.SQ8) initialCapacity * dim else 0)
  private var q8Scales = FloatArray(if (backing == null && format == VectorFormat.SQ8) initialCapacity else 0)
  private var stageQ8Vecs = ByteArray(if (backing == null && format == VectorFormat.SQ8) initialCapacity * stageDim else 0)
  private var stageQ8Scales = FloatArray(if (backing == null && format == VectorFormat.SQ8 && stageDim > 0) initialCapacity else 0)

  private var segments = LongArray(initialCapacity)
  private var files = LongArray(initialCapacity)
  private var kinds = ByteArray(initialCapacity)
  private var modalities = ByteArray(initialCapacity)
  /** [Schema.Late] per row: 0 current, 1 a later step of a long file, 2 stale (made by the previous model, awaiting a re-read). */
  private var lates = ByteArray(initialCapacity)
  /** Intrusive singly-linked list per fileId (parallel to rows, -1 = end): 4 B/row, turns removeFile / rowsOfFiles / segment dedup from O(N) into O(k). */
  private var fileNext = IntArray(initialCapacity) { -1 }
  private val headOfFile = LongIntMap()
  private var count = 0
  /** Highest segmentId currently or previously inserted since last clear/load: O(1) fast-path skip for monotonic SQLite AUTOINCREMENT ids. */
  private var maxSegmentId = Long.MIN_VALUE

  /** The mapped store, when the vectors are off the heap. */
  @Volatile private var file: VectorFile? = null
  /** True once the file could not be made or grown: the index went back to the heap and stays there. */
  @Volatile private var heapFallback = false
  @Volatile private var generationNow: (() -> Long)? = null
  @Volatile private var cachedCount = 0
  @Volatile private var cachedBytes = 0L
  private val sampleLock = Any()
  @Volatile private var cachedSample: ScaleSample? = null
  private val rowScratch = ShortArray(dim)
  private val stageScratch = ShortArray(stageDim)
  private val scanBuffer = ThreadLocal.withInitial { ShortArray(dim * SCAN_ROWS) }
  private val stageBuffer = ThreadLocal.withInitial { ShortArray(stageDim * STAGE_SCAN_ROWS) }
  private val sq8ScanBuffer = ThreadLocal.withInitial { ByteArray(dim * SQ8_SCAN_ROWS) }
  private val sq8ScaleBuffer = ThreadLocal.withInitial { FloatArray(SQ8_SCAN_ROWS) }
  private val sq8StageBuffer = ThreadLocal.withInitial { ByteArray(stageDim * STAGE_SCAN_ROWS) }
  private val sq8StageScaleBuffer = ThreadLocal.withInitial { FloatArray(STAGE_SCAN_ROWS) }
  private val nativeStageScoreBuffer = ThreadLocal.withInitial { FloatArray(rowsPerChunk) }

  private val threads = parallelism.coerceAtLeast(1)
  private val pool: ExecutorService? = if (threads > 1) Executors.newFixedThreadPool(threads) { r ->
    Thread(r, "findr-vector-score").apply { isDaemon = true }
  } else null

  init {
    publishStats()
  }

  /** True when the live storage (mapped file or heap) holds SQ8 quantized vectors. */
  val isQuantized: Boolean get() = (file?.format ?: format) == VectorFormat.SQ8

  /** True when SQ8 scans will dispatch through the ARM64 NEON `SDOT` native library. */
  val nativeAccelerated: Boolean get() = isQuantized && useNative && Sq8Native.available

  private fun heapBytesLocked(): Long =
    (halves.size + stageHalves.size) * 2L +
      q8Vecs.size.toLong() + stageQ8Vecs.size.toLong() +
      (q8Scales.size + stageQ8Scales.size) * 4L +
      segments.size * 8L + files.size * 8L + kinds.size + modalities.size + lates.size +
      fileNext.size * 4L + headOfFile.bytes()

  private fun publishStats() {
    cachedCount = count
    cachedBytes = heapBytesLocked() + (file?.fileBytes ?: 0L)
  }

  val size: Int get() = cachedCount

  /** True when the vectors are in a mapped file rather than on the heap. */
  val offHeap: Boolean get() = file != null

  /** What the index holds on the Java heap: the vectors only in heap mode, always the row facts and the file lookup. */
  fun heapBytes(): Long = lock.read { heapBytesLocked() }

  /** What the mapped file spans (virtual: the kernel keeps only the pages in use, and may drop them, clean). */
  fun mappedBytes(): Long = lock.read { file?.fileBytes ?: 0L }

  /** What the index holds, heap and mapped together (the figure the status screen shows). */
  fun memoryBytes(): Long = cachedBytes

  // ---- changing --------------------------------------------------------------------------------------------------

  /** Adds one vector. [vector] must already be L2-normalised, as the embedder produces it. */
  fun add(segmentId: Long, fileId: Long, kind: FileKind, modality: Modality, late: Boolean, vector: FloatArray, stale: Boolean = false) {
    if (modality !in holds) return
    require(vector.size == dim) { "vector has ${vector.size} dimensions, index uses $dim" }
    val l = lateness(late, stale)
    changing {
      val f = file
      if (format == VectorFormat.SQ8 || f?.format == VectorFormat.SQ8) {
        addFloatLocked(segmentId, fileId, kind, modality, l, vector)
      } else {
        addHalvesLocked(segmentId, fileId, kind, modality, l) { dst -> for (i in 0 until dim) dst[i] = Fp16.toHalf(vector[i]) }
      }
    }
  }

  /** Adds a vector as stored in the database (fp16, little endian). [late] is the stored [Schema.Late] value. */
  fun addBlob(segmentId: Long, fileId: Long, kind: FileKind, modality: Modality, late: Int, blob: ByteArray) {
    if (modality !in holds) return   // another model's vector: it lives in the other index
    if (blob.size != dim * 2) return   // a vector of another size cannot be scored against this query
    addHalves(segmentId, fileId, kind, modality, late.coerceIn(0, Schema.Late.STALE).toByte()) { dst ->
      for (i in 0 until dim) dst[i] = ((blob[2 * i].toInt() and 0xFF) or (blob[2 * i + 1].toInt() shl 8)).toShort()
    }
  }

  private fun invalidateSampleCache() { cachedSample = null }

  /** Runs [block] as a change: the lock held for writing, the file marked open before anything in it moves, a settle scheduled after. */
  private inline fun <T> changing(block: () -> T): T = lock.write {
    ensureFile()
    file?.markOpen()
    try { block() } finally { invalidateSampleCache(); file?.setCount(count); publishStats(); scheduleSettle() }
  }

  /** Like [changing], but skips [ensureFile] when the index is empty and unattached so removals/clears never clobber an existing file on disk. */
  private inline fun <T> changingIfPresent(default: T, block: () -> T): T = lock.write {
    if (file == null && count == 0) return@write default
    ensureFile()
    file?.markOpen()
    try { block() } finally { invalidateSampleCache(); file?.setCount(count); publishStats(); scheduleSettle() }
  }

  private inline fun addHalves(segmentId: Long, fileId: Long, kind: FileKind, modality: Modality, late: Byte, fill: (ShortArray) -> Unit) =
    changing { addHalvesLocked(segmentId, fileId, kind, modality, late, fill) }

  /**
   * Finds an existing row for [segmentId] without maintaining a 50 MB segment->row hash table:
   *  1) O(1) fast path: monotonic SQLite AUTOINCREMENT ids (`segmentId > maxSegmentId`) cannot exist yet.
   *  2) O(k) normal replacement: walk [fileId]'s short intrusive list via [headOfFile]/[fileNext].
   *  3) Fallback for cross-file segment id reuse (e.g. synthetic tests): scan primitive [segments] array.
   */
  private fun findSegmentRow(fileId: Long, segmentId: Long): Int {
    if (segmentId > maxSegmentId) return -1
    var cur = headOfFile.get(fileId)
    while (cur >= 0) {
      if (segments[cur] == segmentId) return cur
      cur = fileNext[cur]
    }
    for (row in 0 until count) {
      if (segments[row] == segmentId) return row
    }
    return -1
  }

  private inline fun addHalvesLocked(segmentId: Long, fileId: Long, kind: FileKind, modality: Modality, late: Byte, fill: (ShortArray) -> Unit) {
    val replaced = findSegmentRow(fileId, segmentId)
    if (replaced >= 0) removeRow(replaced)   // replaced
    reserveRow()
    fill(rowScratch)
    writeVector(count, rowScratch)
    setFacts(count, segmentId, fileId, kind.ordinal, modality.ordinal, late.toInt())
    linkFileRow(fileId, count)
    if (segmentId > maxSegmentId) maxSegmentId = segmentId
    count++
  }

  private fun addFloatLocked(segmentId: Long, fileId: Long, kind: FileKind, modality: Modality, late: Byte, vector: FloatArray) {
    val replaced = findSegmentRow(fileId, segmentId)
    if (replaced >= 0) removeRow(replaced)
    reserveRow()
    writeFloatVector(count, vector)
    setFacts(count, segmentId, fileId, kind.ordinal, modality.ordinal, late.toInt())
    linkFileRow(fileId, count)
    if (segmentId > maxSegmentId) maxSegmentId = segmentId
    count++
  }

  private fun addBlobLocked(segmentId: Long, fileId: Long, kind: FileKind, modality: Modality, late: Int, blob: ByteArray) {
    if (modality !in holds) return
    if (blob.size != dim * 2) return
    addHalvesLocked(segmentId, fileId, kind, modality, late.coerceIn(0, Schema.Late.STALE).toByte()) { dst ->
      for (i in 0 until dim) dst[i] = ((blob[2 * i].toInt() and 0xFF) or (blob[2 * i + 1].toInt() shl 8)).toShort()
    }
  }

  private fun linkFileRow(fileId: Long, row: Int) {
    val oldHead = headOfFile.get(fileId)
    fileNext[row] = oldHead
    headOfFile.put(fileId, row)
  }

  private fun unlinkFileRow(fileId: Long, row: Int) {
    var cur = headOfFile.get(fileId)
    var prev = -1
    while (cur >= 0) {
      if (cur == row) {
        val next = fileNext[row]
        if (prev >= 0) fileNext[prev] = next
        else if (next >= 0) headOfFile.put(fileId, next)
        else headOfFile.remove(fileId)
        fileNext[row] = -1
        return
      }
      prev = cur
      cur = fileNext[cur]
    }
  }

  private fun moveFileRow(fileId: Long, fromRow: Int, toRow: Int) {
    var cur = headOfFile.get(fileId)
    var prev = -1
    while (cur >= 0) {
      if (cur == fromRow) {
        if (prev >= 0) fileNext[prev] = toRow else headOfFile.put(fileId, toRow)
        fileNext[toRow] = fileNext[fromRow]
        fileNext[fromRow] = -1
        return
      }
      prev = cur
      cur = fileNext[cur]
    }
  }

  /** Smallest row index belonging to [fileId], or -1. Removing min-row first preserves exact legacy swap-with-last row order. */
  private fun minRowOfFile(fileId: Long): Int {
    var cur = headOfFile.get(fileId)
    if (cur < 0) return -1
    var best = cur
    cur = fileNext[cur]
    while (cur >= 0) {
      if (cur < best) best = cur
      cur = fileNext[cur]
    }
    return best
  }

  private fun lateness(late: Boolean, stale: Boolean): Byte =
    (if (stale) Schema.Late.STALE else if (late) Schema.Late.LATER_STEP else Schema.Late.CURRENT).toByte()

  /**
   * A model upgrade kept the stored vectors but they now come from the previous model ([IndexStore.ensureModelFingerprint]
   * with `keepStale`): flags the rows of these modalities as stale, in place and in one step, so search never sees a gap.
   * Returns how many rows changed.
   */
  fun markStale(which: Set<Modality>): Int = changingIfPresent(0) {
    val ordinals = which.map { it.ordinal }.toSet()
    var n = 0
    for (row in 0 until count) {
      if (modalities[row].toInt() in ordinals && lates[row].toInt() != Schema.Late.STALE) {
        lates[row] = Schema.Late.STALE.toByte()
        file?.putLate(row, Schema.Late.STALE)
        n++
      }
    }
    n
  }

  /** Drops every vector of a file (it was deleted, or is about to be read again) in O(k) via [headOfFile]. Returns how many went. */
  fun removeFile(fileId: Long): Int = changingIfPresent(0) { removeFileLocked(fileId) }

  private fun removeFileLocked(fileId: Long): Int {
    var removed = 0
    while (true) {
      val row = minRowOfFile(fileId)
      if (row < 0) break
      removeRow(row)
      removed++
    }
    return removed
  }

  fun removeFiles(fileIds: Collection<Long>): Int {
    if (fileIds.isEmpty()) return 0
    val unique = fileIds.toLongArray().also { it.sort() }
    var uniqueCount = 0
    for (id in unique) if (uniqueCount == 0 || unique[uniqueCount - 1] != id) unique[uniqueCount++] = id
    return changingIfPresent(0) {
      // Removing the smallest matching row each iteration matches LegacyHeapVectorIndex's row order bit-for-bit.
      var removed = 0
      while (true) {
        var best = Int.MAX_VALUE
        for (i in 0 until uniqueCount) {
          val r = minRowOfFile(unique[i])
          if (r in 0 until best) best = r
        }
        if (best == Int.MAX_VALUE) break
        removeRow(best)
        removed++
      }
      removed
    }
  }

  /** Empties the index. In a file the emptied rows are overwritten with zeros, so nothing removed lingers in it. */
  fun clear() = changingIfPresent(Unit) { emptyRows() }

  /**
   * Cuts empty chunks off the end of the mapped file after a big purge (rows stay dense, so no data moves; see [VectorFile.shrinkTo]).
   * Returns how many bytes the file lost, or 0 when nothing was freed (heap mode, no spare chunks, or Windows refusing to truncate a mapping).
   */
  fun compact(): Long = changingIfPresent(0L) { file?.shrinkTo(count) ?: 0L }

  private fun emptyRows() {
    val f = file
    if (f != null) for (row in 0 until count) { f.zeroVector(row); f.clearMeta(row) }
    count = 0
    maxSegmentId = Long.MIN_VALUE
    headOfFile.clear()
  }

  /**
   * Starts the index from [store]: when the mapped file is there, settled and at the database's vector generation, its rows
   * are adopted as they are (the facts are read from the file, the vectors not at all). Otherwise every vector is read from
   * SQLite and the file written afresh. Called once at start and after a model change.
   */
  fun load(store: IndexStore) {
    val quick = Modality.CLIP in holds   // the quick-look vectors are counted apart from EmbeddingGemma's
    loadFrom({ store.vectorGeneration(quick) }) { sink -> store.forEachVector(sink) }
  }

  fun loadFrom(
    generation: () -> Long,
    rows: ((Long, Long, FileKind, Modality, Int, ByteArray) -> Unit) -> Unit,
  ) {
    val started = System.nanoTime()
    val at = generation()
    generationNow = generation
    if (backing != null && !heapFallback && tryAttach(at)) {
      Log.i(TAG, "vector file adopted ($format): $size rows in ${(System.nanoTime() - started) / 1_000_000} ms")
      scheduleSettle()
      return
    }
    // Single write-lock bulk rebuild: avoids per-row lock acquisition, header count writes, and settle rescheduling.
    // The mapped file is created lazily on the first matching row so an empty/freshly-wiped database leaves no empty vectors.bin behind.
    lock.write {
      file?.close()
      file = null
      count = 0
      maxSegmentId = Long.MIN_VALUE
      headOfFile.clear()
      var opened = false
      try {
        rows { segment, fileId, kind, modality, late, blob ->
          if (modality !in holds || blob.size != dim * 2) return@rows
          if (!opened) {
            ensureFile()
            file?.markOpen()
            opened = true
          }
          addBlobLocked(segment, fileId, kind, modality, late, blob)
        }
      } finally {
        invalidateSampleCache()
        file?.setCount(count)
        publishStats()
      }
    }
    if (backing != null) Log.i(TAG, "vector file rebuilt ($format) from the database: $size rows in ${(System.nanoTime() - started) / 1_000_000} ms")
    scheduleSettle()
  }

  /** Adopts the file if it can be trusted; false leaves the index untouched. */
  private fun tryAttach(expectedGeneration: Long): Boolean {
    val f = VectorFile.openExisting(backing!!, dim, rowsPerChunk, stageDim, format) ?: return false
    if (f.generation != expectedGeneration) { f.close(); return false }
    lock.write {
      if (file != null) { f.close(); return false }
      val n = f.count
      val cap = n + SLACK
      val s = LongArray(cap); val fl = LongArray(cap)
      val k = ByteArray(cap); val m = ByteArray(cap); val l = ByteArray(cap)
      val next = IntArray(cap) { -1 }
      val fileHeads = LongIntMap(maxOf(16, n / 32))
      var maxSeg = Long.MIN_VALUE
      var ok = true
      f.forEachMeta(n) { row, segment, fileId, kind, modality, late ->
        if (!ok) return@forEachMeta
        if (kind !in KINDS || modality !in holdsOrdinals || late !in 0..Schema.Late.STALE) { ok = false; return@forEachMeta }
        var cur = fileHeads.get(fileId)
        while (cur >= 0) {
          if (s[cur] == segment) { ok = false; return@forEachMeta }
          cur = next[cur]
        }
        s[row] = segment; fl[row] = fileId; k[row] = kind.toByte(); m[row] = modality.toByte(); l[row] = late.toByte()
        if (segment > maxSeg) maxSeg = segment
        next[row] = fileHeads.get(fileId)
        fileHeads.put(fileId, row)
      }
      if (!ok) { f.close(); return false }
      segments = s; files = fl; kinds = k; modalities = m; lates = l
      fileNext = next
      halves = ShortArray(0); stageHalves = ShortArray(0)
      q8Vecs = ByteArray(0); q8Scales = FloatArray(0)
      stageQ8Vecs = ByteArray(0); stageQ8Scales = FloatArray(0)
      headOfFile.become(fileHeads)
      count = n
      maxSegmentId = maxSeg
      file = f
      invalidateSampleCache()
      publishStats()
    }
    return true
  }

  private val holdsOrdinals: Set<Int> = holds.map { it.ordinal }.toSet()

  /** Adds the vectors of one file from the store (after the engine saved its segments). */
  fun loadFile(store: IndexStore, fileId: Long) {
    // Read first, then swap under one write lock and one settle: a search never sees the file between its old vectors and its new ones.
    class Row(val segment: Long, val kind: FileKind, val modality: Modality, val late: Int, val blob: ByteArray)
    val rows = ArrayList<Row>()
    store.forEachVectorOfFile(fileId) { segment, _, kind, modality, late, blob -> rows += Row(segment, kind, modality, late, blob) }
    changing {
      removeFileLocked(fileId)
      for (r in rows) addBlobLocked(r.segment, fileId, r.kind, r.modality, r.late, r.blob)
    }
  }

  // ---- storage ---------------------------------------------------------------------------------------------------

  /** Opens the file on the first change when there is a backing and no file yet (an index used without [load]). */
  private fun ensureFile() {
    if (file != null || backing == null || heapFallback) return
    try {
      file = VectorFile.create(backing, dim, rowsPerChunk, stageDim, format)
      halves = ShortArray(0); stageHalves = ShortArray(0)
      q8Vecs = ByteArray(0); q8Scales = FloatArray(0)
      stageQ8Vecs = ByteArray(0); stageQ8Scales = FloatArray(0)
    } catch (e: IOException) {
      fallBackToHeap(e)
    }
  }

  /** Makes room for one more row in the facts and in the store. */
  private fun reserveRow() {
    if (count == segments.size) grow()   // heap mode: grows the vectors too
    val f = file ?: return
    try { f.ensureCapacity(count + 1) } catch (e: IOException) { fallBackToHeap(e) }
  }

  private fun writeVector(row: Int, src: ShortArray) {
    val f = file
    if (f != null) { f.putVector(row, src); return }
    if (format == VectorFormat.FP16) {
      System.arraycopy(src, 0, halves, row * dim, dim)
      if (stageDim > 0) { Halves.stageOf(src, stageDim, stageScratch); System.arraycopy(stageScratch, 0, stageHalves, row * stageDim, stageDim) }
    } else {
      q8Scales[row] = Sq8.quantizeHalves(src, dim, q8Vecs, row * dim)
      if (stageDim > 0) stageQ8Scales[row] = Sq8.quantizeStageFromHalves(src, stageDim, stageQ8Vecs, row * stageDim)
    }
  }

  private fun writeFloatVector(row: Int, src: FloatArray) {
    val f = file
    if (f != null) { f.putFloatVector(row, src); return }
    if (format == VectorFormat.SQ8) {
      q8Scales[row] = Sq8.quantizeFloats(src, dim, q8Vecs, row * dim)
      if (stageDim > 0) stageQ8Scales[row] = Sq8.quantizeStageFromFloats(src, stageDim, stageQ8Vecs, row * stageDim)
    } else {
      for (i in 0 until dim) rowScratch[i] = Fp16.toHalf(src[i])
      writeVector(row, rowScratch)
    }
  }

  private fun setFacts(row: Int, segment: Long, fileId: Long, kind: Int, modality: Int, late: Int) {
    segments[row] = segment; files[row] = fileId; kinds[row] = kind.toByte(); modalities[row] = modality.toByte(); lates[row] = late.toByte()
    file?.putMeta(row, segment, fileId, kind, modality, late)
  }

  private fun removeRow(row: Int) {
    val last = count - 1
    unlinkFileRow(files[row], row)
    val f = file
    if (row != last) {
      if (f != null) {
        f.copyVector(last, row)
      } else if (format == VectorFormat.FP16) {
        System.arraycopy(halves, last * dim, halves, row * dim, dim)
        if (stageDim > 0) System.arraycopy(stageHalves, last * stageDim, stageHalves, row * stageDim, stageDim)
      } else {
        System.arraycopy(q8Vecs, last * dim, q8Vecs, row * dim, dim)
        q8Scales[row] = q8Scales[last]
        if (stageDim > 0) {
          System.arraycopy(stageQ8Vecs, last * stageDim, stageQ8Vecs, row * stageDim, stageDim)
          stageQ8Scales[row] = stageQ8Scales[last]
        }
      }
      val movedFile = files[last]
      setFacts(row, segments[last], movedFile, kinds[last].toInt(), modalities[last].toInt(), lates[last].toInt())
      moveFileRow(movedFile, last, row)
    }
    if (f != null) { f.zeroVector(last); f.clearMeta(last) }   // a removed vector does not linger in the file
    count = last
  }

  /**
   * Grows all backing arrays atomically: every new array is allocated into locals first, and fields are updated only
   * after every allocation succeeds. An OutOfMemoryError on heap-mode vector growth therefore leaves the index whole
   * instead of torn (cpu-heat-audit section 2). Also guards capacity * dim against 32-bit Int overflow.
   */
  private fun grow() {
    val maxRows = if (file == null) Int.MAX_VALUE / dim else Int.MAX_VALUE - 8
    val cur = segments.size
    check(cur < maxRows) { "VectorIndex capacity overflow: $cur rows for dim=$dim" }
    val capacity = (cur.toLong() * 3L / 2L).coerceAtLeast(cur.toLong() + 1024L).coerceAtMost(maxRows.toLong()).toInt()
    val newSegments = segments.copyOf(capacity)
    val newFiles = files.copyOf(capacity)
    val newKinds = kinds.copyOf(capacity)
    val newModalities = modalities.copyOf(capacity)
    val newLates = lates.copyOf(capacity)
    val newFileNext = fileNext.copyOf(capacity).also { java.util.Arrays.fill(it, cur, capacity, -1) }
    val heapMode = file == null
    val newHalves = if (heapMode && format == VectorFormat.FP16) halves.copyOf(capacity * dim) else halves
    val newStageHalves = if (heapMode && format == VectorFormat.FP16) stageHalves.copyOf(capacity * stageDim) else stageHalves
    val newQ8Vecs = if (heapMode && format == VectorFormat.SQ8) q8Vecs.copyOf(capacity * dim) else q8Vecs
    val newQ8Scales = if (heapMode && format == VectorFormat.SQ8) q8Scales.copyOf(capacity) else q8Scales
    val newStageQ8Vecs = if (heapMode && format == VectorFormat.SQ8) stageQ8Vecs.copyOf(capacity * stageDim) else stageQ8Vecs
    val newStageQ8Scales = if (heapMode && format == VectorFormat.SQ8 && stageDim > 0) stageQ8Scales.copyOf(capacity) else stageQ8Scales
    segments = newSegments; files = newFiles; kinds = newKinds; modalities = newModalities; lates = newLates
    fileNext = newFileNext
    halves = newHalves; stageHalves = newStageHalves
    q8Vecs = newQ8Vecs; q8Scales = newQ8Scales
    stageQ8Vecs = newStageQ8Vecs; stageQ8Scales = newStageQ8Scales
  }

  /** The file cannot be made or grown (disk full, say): carry on from the heap, as an index without a backing does. */
  private fun fallBackToHeap(cause: IOException) {
    Log.w(TAG, "vector file unavailable (${cause.javaClass.simpleName}); keeping vectors on the heap")
    val f = file
    val capacity = maxOf(segments.size, count + 1)
    if (format == VectorFormat.SQ8 || f?.format == VectorFormat.SQ8) {
      val allVecs = ByteArray(capacity * dim)
      val allScales = FloatArray(capacity)
      val allStageVecs = ByteArray(capacity * stageDim)
      val allStageScales = FloatArray(if (stageDim > 0) capacity else 0)
      if (f != null && f.format == VectorFormat.SQ8) {
        val reader = f.reader()
        val vBuf = ByteArray(dim * SQ8_SCAN_ROWS)
        val sBuf = FloatArray(SQ8_SCAN_ROWS)
        var row = 0
        while (row < count) {
          val n = reader.fetchSq8(row, count - row, vBuf, sBuf)
          System.arraycopy(vBuf, 0, allVecs, row * dim, n * dim)
          System.arraycopy(sBuf, 0, allScales, row, n)
          row += n
        }
        if (stageDim > 0) {
          val svBuf = ByteArray(stageDim * STAGE_SCAN_ROWS)
          val ssBuf = FloatArray(STAGE_SCAN_ROWS)
          var sRow = 0
          while (sRow < count) {
            val n = reader.fetchSq8Stage(sRow, count - sRow, svBuf, ssBuf)
            System.arraycopy(svBuf, 0, allStageVecs, sRow * stageDim, n * stageDim)
            System.arraycopy(ssBuf, 0, allStageScales, sRow, n)
            sRow += n
          }
        }
      }
      f?.close()
      heapFallback = true
      q8Vecs = allVecs; q8Scales = allScales
      stageQ8Vecs = allStageVecs; stageQ8Scales = allStageScales
      halves = ShortArray(0); stageHalves = ShortArray(0)
      file = null
      publishStats()
      backing?.let { runCatching { VectorFile.shred(it); it.delete() } }
      return
    }
    val all = ShortArray(capacity * dim)
    val newStageHalves = ShortArray(capacity * stageDim)
    if (f != null) {
      val reader = f.reader()
      var row = 0
      val buf = ShortArray(dim * SCAN_ROWS)
      while (row < count) {
        val n = reader.fetch(row, count - row, buf)
        System.arraycopy(buf, 0, all, row * dim, n * dim)
        row += n
      }
    }
    if (stageDim > 0) {
      val row = ShortArray(dim); val stage = ShortArray(stageDim)
      for (r in 0 until count) {
        System.arraycopy(all, r * dim, row, 0, dim)
        Halves.stageOf(row, stageDim, stage)
        System.arraycopy(stage, 0, newStageHalves, r * stageDim, stageDim)
      }
    }
    f?.close()
    heapFallback = true
    halves = all
    stageHalves = newStageHalves
    file = null
    publishStats()
    backing?.let { runCatching { VectorFile.shred(it); it.delete() } }
  }

  // ---- settling --------------------------------------------------------------------------------------------------

  private val settleLock = Any()
  private val settleArmed = AtomicBoolean(false)
  private val settlerCreated = AtomicBoolean(false)
  @Volatile private var lastChangeNanos = 0L
  private val settler: ScheduledThreadPoolExecutor by lazy {
    settlerCreated.set(true)
    ScheduledThreadPoolExecutor(1) { r -> Thread(r, "findr-vector-settle").apply { isDaemon = true } }.also { it.removeOnCancelPolicy = true }
  }

  /** After a change the file settles once things have been quiet for [settleDelayMs]: one flush instead of one per vector. */
  private fun scheduleSettle() {
    if (backing == null || settleDelayMs < 0 || file == null) return
    lastChangeNanos = System.nanoTime()
    if (settleArmed.compareAndSet(false, true)) {
      try { settler.schedule({ settleTick() }, settleDelayMs, TimeUnit.MILLISECONDS) } catch (e: java.util.concurrent.RejectedExecutionException) { settleArmed.set(false) }
    }
  }

  private fun settleTick() {
    val waitMs = settleDelayMs - (System.nanoTime() - lastChangeNanos) / 1_000_000
    if (waitMs > 0) {
      try { settler.schedule({ settleTick() }, waitMs, TimeUnit.MILLISECONDS) } catch (e: java.util.concurrent.RejectedExecutionException) { settleArmed.set(false) }
      return
    }
    settleArmed.set(false)
    val settled = try { settleNow() } catch (e: Exception) { true }   // a store that went away is not worth retrying
    if (!settled) scheduleSettle()
  }

  /**
   * Flushes the file and marks it settled at the database's current vector generation, if the app is [quiet] (a change the
   * file has not seen yet could otherwise be recorded as seen). Returns false when it was not quiet and should be retried.
   */
  fun settleNow(): Boolean {
    val f0 = lock.read { file } ?: return true
    val read = generationNow ?: return true
    if (!quiet()) return false
    val gen = read()
    if (!quiet()) return false
    synchronized(settleLock) {
      lock.read {
        val f = file
        // Only a file this process changed is settled. One that is not open but is behind the database missed a change made
        // elsewhere; leaving it as it is makes the next start rebuild it rather than vouch for it.
        if (f === f0 && f.open) f.settle(gen)
      }
    }
    return true
  }

  // ---- ending ----------------------------------------------------------------------------------------------------

  /**
   * "Delete everything": overwrites the file with zeros, flushes it and lets go of it, leaving the (now blank) file for the
   * caller to delete along with the rest of the index ([com.craneai.findr.core.LocalData.wipe]). The index is empty afterwards
   * and starts a new file if used again.
   */
  fun shred(): Long = lock.write {
    val f = file
    f?.close()
    file = null
    count = 0
    maxSegmentId = Long.MIN_VALUE
    headOfFile.clear()
    halves = if (backing == null && format == VectorFormat.FP16) halves else ShortArray(0)
    stageHalves = if (backing == null && format == VectorFormat.FP16) stageHalves else ShortArray(0)
    q8Vecs = if (backing == null && format == VectorFormat.SQ8) q8Vecs else ByteArray(0)
    q8Scales = if (backing == null && format == VectorFormat.SQ8) q8Scales else FloatArray(0)
    stageQ8Vecs = if (backing == null && format == VectorFormat.SQ8) stageQ8Vecs else ByteArray(0)
    stageQ8Scales = if (backing == null && format == VectorFormat.SQ8 && stageDim > 0) stageQ8Scales else FloatArray(0)
    heapFallback = false   // a new file may work where the old one did not (space freed by the wipe)
    generationNow = null
    invalidateSampleCache()
    publishStats()
    backing?.let { VectorFile.shred(it) } ?: 0L
  }

  /**
   * Drops the mapped file without touching SQLite: used when [load] keeps failing, so the next try rebuilds the cache from the
   * database rather than reopening whatever went wrong. Does not overwrite the file with zeros (nothing is being forgotten).
   */
  fun discardFile() = lock.write {
    file?.close()
    file = null
    count = 0
    maxSegmentId = Long.MIN_VALUE
    headOfFile.clear()
    halves = if (backing == null && format == VectorFormat.FP16) halves else ShortArray(0)
    stageHalves = if (backing == null && format == VectorFormat.FP16) stageHalves else ShortArray(0)
    q8Vecs = if (backing == null && format == VectorFormat.SQ8) q8Vecs else ByteArray(0)
    q8Scales = if (backing == null && format == VectorFormat.SQ8) q8Scales else FloatArray(0)
    stageQ8Vecs = if (backing == null && format == VectorFormat.SQ8) stageQ8Vecs else ByteArray(0)
    stageQ8Scales = if (backing == null && format == VectorFormat.SQ8 && stageDim > 0) stageQ8Scales else FloatArray(0)
    heapFallback = false
    generationNow = null
    invalidateSampleCache()
    publishStats()
    backing?.delete()
  }

  /** [shred] and delete the file. */
  fun destroy() {
    shred()
    backing?.delete()
  }

  fun close() {
    if (backing != null && settleDelayMs >= 0) {
      runCatching { settleNow() }
      if (settlerCreated.get()) settler.shutdownNow()
    }
    lock.write {
      pool?.shutdown()
      file?.close()
      file = null
      invalidateSampleCache()
      publishStats()
    }
  }

  // ---- scoring ---------------------------------------------------------------------------------------------------

  /**
   * Cosine similarity of [query] with every vector, in row order (see the row accessors, which must be used inside
   * [read] so a change cannot shift the rows between scoring and looking them up). The query is normalised here, so
   * a caller cannot skew the scale by forgetting to.
   */
  fun scores(query: FloatArray): FloatArray = lock.read {
    require(query.size == dim) { "query has ${query.size} dimensions, index uses $dim" }
    val out = FloatArray(count)
    if (count == 0) return@read out
    val q = normalised(query)
    val qNative = if (nativeAccelerated) Sq8.quantizeQuery(q) else null
    val pool = pool
    if (pool == null || count < PARALLEL_MIN_ROWS) {
      scoreRows(q, qNative, 0, count, out)
    } else {
      val step = (count + threads - 1) / threads
      val jobs = (0 until threads).mapNotNull { t ->
        val from = t * step
        val to = minOf(count, from + step)
        if (from >= to) null else Callable { scoreRows(q, qNative, from, to, out) }
      }
      pool.invokeAll(jobs).forEach { it.get() }
    }
    out
  }

  private fun scoreRows(q: FloatArray, qNative: Sq8.QuantizedQuery?, from: Int, to: Int, out: FloatArray) {
    val f = file
    if (f == null) {
      if (format == VectorFormat.FP16) {
        scoreBlock(q, halves, from * dim, to - from, out, from)
      } else if (qNative != null) {
        Sq8Native.scoreHeap(q8Vecs, q8Scales, from, to, dim, qNative.hi, qNative.hiScale, qNative.lo, qNative.loScale, out, from)
      } else {
        Sq8.scoreBlock(q, q8Vecs, from * dim, q8Scales, from, dim, to - from, out, from)
      }
      return
    }
    if (f.format == VectorFormat.SQ8) {
      if (qNative != null) {
        var row = from
        while (row < to) {
          val c = row / rowsPerChunk
          val at = row % rowsPerChunk
          val n = minOf(to - row, rowsPerChunk - at)
          Sq8Native.scoreChunkDirect(
            f.chunkBuffer(c), f.vectorBase, f.fullScaleBase, at, at + n, dim,
            qNative.hi, qNative.hiScale, qNative.lo, qNative.loScale, out, row,
          )
          row += n
        }
      } else {
        val reader = f.reader()
        val buf = sq8ScanBuffer.get()!!
        val scBuf = sq8ScaleBuffer.get()!!
        var row = from
        while (row < to) {
          val n = reader.fetchSq8(row, to - row, buf, scBuf)
          Sq8.scoreBlock(q, buf, 0, scBuf, 0, dim, n, out, row)
          row += n
        }
      }
      return
    }
    // FP16 off-heap: a few rows at a time are copied from the mapping into a small array that stays in cache, then scored by the same loop.
    val reader = f.reader()
    val buf = scanBuffer.get()!!
    var row = from
    while (row < to) {
      val n = reader.fetch(row, to - row, buf)
      scoreBlock(q, buf, 0, n, out, row)
      row += n
    }
  }

  /** Scores [n] rows whose halves start at [start] in [halves], writing [out] from index [firstRow]. */
  private fun scoreBlock(q: FloatArray, halves: ShortArray, start: Int, n: Int, out: FloatArray, firstRow: Int) {
    val table = HALF_TO_FLOAT
    val dim = dim
    val q = q
    for (r in 0 until n) {
      var base = start + r * dim
      var a0 = 0f; var a1 = 0f; var a2 = 0f; var a3 = 0f
      var i = 0
      while (i + 4 <= dim) {
        a0 += table[halves[base].toInt() and 0xFFFF] * q[i]
        a1 += table[halves[base + 1].toInt() and 0xFFFF] * q[i + 1]
        a2 += table[halves[base + 2].toInt() and 0xFFFF] * q[i + 2]
        a3 += table[halves[base + 3].toInt() and 0xFFFF] * q[i + 3]
        base += 4; i += 4
      }
      while (i < dim) { a0 += table[halves[base].toInt() and 0xFFFF] * q[i]; base++; i++ }
      out[firstRow + r] = (a0 + a1) + (a2 + a3)
    }
  }

  // ---- two-stage scan ----------------------------------------------------------------------------------------------

  /**
   * True when a query should scan in two stages: configured, and enough rows for it to pay (exact below
   * [TwoStage.minRows]). Call inside [read], with the rows [stageCandidates] and [scoresAt] will see.
   */
  fun twoStageApplies(): Boolean {
    val t = twoStage ?: return false
    return count >= t.minRows && count > t.candidates
  }

  /** Cached scale-eligible counts and systematic sample rows for two-stage search. */
  class ScaleSample(val groupRows: IntArray, val pickedRows: IntArray, val pickedModalities: IntArray)

  /**
   * Per-modality scale-eligible row counts and systematic sample row indices for [TwoStage.scaleSample].
   * The unfiltered case ([only] < 0 and [skip] < 0, used by normal search) is cached until the next write mutation,
   * eliminating per-query O(N) array allocations and full-row passes. Call inside [read].
   */
  fun scaleSample(only: Int = -1, skip: Long = -1L): ScaleSample {
    if (only < 0 && skip < 0) {
      cachedSample?.let { return it }
      return synchronized(sampleLock) {
        cachedSample?.let { return@synchronized it }
        buildScaleSampleLocked(-1, -1L).also { cachedSample = it }
      }
    }
    return buildScaleSampleLocked(only, skip)
  }

  private fun buildScaleSampleLocked(only: Int, skip: Long): ScaleSample {
    val sample = checkNotNull(twoStage) { "no two-stage scan configured" }.scaleSample
    val n = count
    val groups = Modality.entries.size
    val currentAll = IntArray(groups)
    val currentKept = IntArray(groups)
    val staleKept = IntArray(groups)
    val filtered = only >= 0 || skip >= 0
    for (row in 0 until n) {
      val late = lates[row].toInt()
      if (late == Schema.Late.LATER_STEP) continue
      val g = modalities[row].toInt()
      if (late == Schema.Late.CURRENT) currentAll[g]++
      if (filtered && ((only >= 0 && g != only) || (skip >= 0 && files[row] == skip))) continue
      if (late == Schema.Late.CURRENT) currentKept[g]++ else staleKept[g]++
    }
    val includeStale = BooleanArray(groups) { currentAll[it] < Fusion.MIN_GROUP }
    val groupRows = IntArray(groups) { g -> currentKept[g] + if (includeStale[g]) staleKept[g] else 0 }
    var totalPicked = 0
    for (g in 0 until groups) totalPicked += minOf(groupRows[g], sample)
    val pickedRows = IntArray(totalPicked)
    val pickedModalities = IntArray(totalPicked)
    val seen = IntArray(groups)
    var taken = 0
    for (row in 0 until n) {
      val late = lates[row].toInt()
      if (late == Schema.Late.LATER_STEP) continue
      val g = modalities[row].toInt()
      if (late == Schema.Late.STALE && !includeStale[g]) continue
      if (filtered && ((only >= 0 && g != only) || (skip >= 0 && files[row] == skip))) continue
      val s = seen[g]
      if ((s + 1).toLong() * sample / groupRows[g] > s.toLong() * sample / groupRows[g]) {
        pickedRows[taken] = row
        pickedModalities[taken] = g
        taken++
      }
      seen[g] = s + 1
    }
    return ScaleSample(groupRows, pickedRows, pickedModalities)
  }

  /**
   * Stage 1: the rows with the best score on the first [TwoStage.stageDim] dims — up to [TwoStage.candidates] per
   * modality (text, image, audio, sound) so cross-modal cosine-level differences ("modality gap") never starve photos
   * or recordings behind text chunks before per-modality z-centering in [Searcher.twoStageMeaning]. Only rows whose
   * kind (ordinal) is true in [allowedKinds] are considered, returned in ascending row order. Reads [stageDim]/[dim]
   * of the bytes an exact scan reads. Call inside [read]; the rows are valid in the same [read].
   */
  fun stageCandidates(query: FloatArray, allowedKinds: BooleanArray): IntArray = lock.read {
    val t = checkNotNull(twoStage) { "no two-stage scan configured" }
    require(query.size == dim) { "query has ${query.size} dimensions, index uses $dim" }
    val n = count
    val k = minOf(t.candidates, n)
    if (k <= 0) return@read IntArray(0)
    val q = stageQuery(query)
    val qNative = if (nativeAccelerated) Sq8.quantizeQuery(q) else null
    val groups = Modality.entries.size
    val pool = pool
    val merged = Array(groups) { TopK(k) }
    if (pool == null || n < PARALLEL_MIN_ROWS) {
      scanStage(q, qNative, 0, n, allowedKinds, merged)
    } else {
      val step = (n + threads - 1) / threads
      val jobs = (0 until threads).mapNotNull { th ->
        val from = th * step
        val to = minOf(n, from + step)
        if (from >= to) null else Callable {
          Array(groups) { TopK(k) }.also { scanStage(q, qNative, from, to, allowedKinds, it) }
        }
      }
      for (f in pool.invokeAll(jobs)) {
        val local = f.get()
        for (g in 0 until groups) local[g].drainInto(merged[g])
      }
    }
    var total = 0
    for (g in 0 until groups) total += merged[g].count
    val out = IntArray(total)
    var pos = 0
    for (g in 0 until groups) {
      val rows = merged[g].rows()
      System.arraycopy(rows, 0, out, pos, rows.size)
      pos += rows.size
    }
    out.sort()
    out
  }

  /** The first [stageDim] dims of [query], scaled to unit length: the stage-1 query. Non-finite or zero queries yield all zeros. */
  private fun stageQuery(query: FloatArray): FloatArray {
    var sum = 0.0
    for (i in 0 until stageDim) {
      val x = query[i]
      if (!x.isFinite()) return FloatArray(stageDim)
      sum += x.toDouble() * x
    }
    if (!sum.isFinite() || sum <= 0.0) return FloatArray(stageDim)
    val inv = (1.0 / Math.sqrt(sum)).toFloat()
    return FloatArray(stageDim) { query[it] * inv }
  }

  private fun scanStage(q: FloatArray, qNative: Sq8.QuantizedQuery?, from: Int, to: Int, allowed: BooleanArray, tops: Array<TopK>) {
    val f = file
    if (f == null) {
      if (format == VectorFormat.FP16) {
        stageBlock(q, stageHalves, from * stageDim, to - from, from, allowed, tops)
      } else {
        stageSq8Block(q, stageQ8Vecs, from * stageDim, stageQ8Scales, from, to - from, from, allowed, tops)
      }
      return
    }
    if (f.format == VectorFormat.SQ8) {
      if (qNative != null) {
        val scoreBuf = nativeStageScoreBuffer.get()!!
        var row = from
        while (row < to) {
          val c = row / rowsPerChunk
          val at = row % rowsPerChunk
          val n = minOf(to - row, rowsPerChunk - at)
          Sq8Native.scoreChunkDirectFiltered(
            f.chunkBuffer(c), f.stageBase, f.stageScaleBase, at, at + n, stageDim,
            qNative.hi, qNative.hiScale, qNative.lo, qNative.loScale,
            kinds, row, allowed, scoreBuf, 0,
          )
          for (r in 0 until n) {
            val absRow = row + r
            if (allowed[kinds[absRow].toInt()]) tops[modalities[absRow].toInt()].offer(absRow, scoreBuf[r])
          }
          row += n
        }
      } else {
        val reader = f.reader()
        val buf = sq8StageBuffer.get()!!
        val scBuf = sq8StageScaleBuffer.get()!!
        var row = from
        while (row < to) {
          val n = reader.fetchSq8Stage(row, to - row, buf, scBuf)
          stageSq8Block(q, buf, 0, scBuf, 0, n, row, allowed, tops)
          row += n
        }
      }
      return
    }
    val reader = f.reader()
    val buf = stageBuffer.get()!!
    var row = from
    while (row < to) {
      val n = reader.fetchStage(row, to - row, buf)
      stageBlock(q, buf, 0, n, row, allowed, tops)
      row += n
    }
  }

  /** Scores [n] fp16 stage-1 blocks starting at [start] against [q]; rows of disallowed kinds are skipped. */
  private fun stageBlock(q: FloatArray, halves: ShortArray, start: Int, n: Int, firstRow: Int, allowed: BooleanArray, tops: Array<TopK>) {
    val table = HALF_TO_FLOAT
    val d = stageDim
    for (r in 0 until n) {
      val row = firstRow + r
      if (!allowed[kinds[row].toInt()]) continue
      var base = start + r * d
      var a0 = 0f; var a1 = 0f; var a2 = 0f; var a3 = 0f
      var i = 0
      while (i + 4 <= d) {
        a0 += table[halves[base].toInt() and 0xFFFF] * q[i]
        a1 += table[halves[base + 1].toInt() and 0xFFFF] * q[i + 1]
        a2 += table[halves[base + 2].toInt() and 0xFFFF] * q[i + 2]
        a3 += table[halves[base + 3].toInt() and 0xFFFF] * q[i + 3]
        base += 4; i += 4
      }
      while (i < d) { a0 += table[halves[base].toInt() and 0xFFFF] * q[i]; base++; i++ }
      tops[modalities[row].toInt()].offer(row, (a0 + a1) + (a2 + a3))
    }
  }

  /** Pure-JVM SQ8 stage-1 scoring of [n] rows; rows of disallowed kinds are skipped without reading their vector bytes. */
  private fun stageSq8Block(
    q: FloatArray,
    vecs: ByteArray,
    vecStart: Int,
    scales: FloatArray,
    scaleStart: Int,
    n: Int,
    firstRow: Int,
    allowed: BooleanArray,
    tops: Array<TopK>,
  ) {
    val d = stageDim
    for (r in 0 until n) {
      val row = firstRow + r
      if (!allowed[kinds[row].toInt()]) continue
      val score = Sq8.scoreOne(q, vecs, vecStart + r * d, scales[scaleStart + r], d)
      tops[modalities[row].toInt()].offer(row, score)
    }
  }

  /**
   * Stage 2 and the scale sample: the full-width cosine of [query] with each of [rows], in that order. The arithmetic is
   * [scores]'s, so a row scores what it would in a full scan. Call inside [read].
   */
  fun scoresAt(query: FloatArray, rows: IntArray): FloatArray = lock.read {
    require(query.size == dim) { "query has ${query.size} dimensions, index uses $dim" }
    val out = FloatArray(rows.size)
    if (rows.isEmpty()) return@read out
    val q = normalised(query)
    val qNative = if (nativeAccelerated) Sq8.quantizeQuery(q) else null
    val pool = pool
    if (pool == null || rows.size < PARALLEL_MIN_ROWS / 2) {
      scoreRowList(q, qNative, rows, 0, rows.size, out)
    } else {
      val step = (rows.size + threads - 1) / threads
      val jobs = (0 until threads).mapNotNull { th ->
        val from = th * step
        val to = minOf(rows.size, from + step)
        if (from >= to) null else Callable { scoreRowList(q, qNative, rows, from, to, out) }
      }
      pool.invokeAll(jobs).forEach { it.get() }
    }
    out
  }

  private fun scoreRowList(q: FloatArray, qNative: Sq8.QuantizedQuery?, rows: IntArray, from: Int, to: Int, out: FloatArray) {
    val f = file
    val one = FloatArray(1)
    if (f == null) {
      if (format == VectorFormat.FP16) {
        for (i in from until to) { scoreBlock(q, halves, rows[i] * dim, 1, one, 0); out[i] = one[0] }
      } else if (qNative != null) {
        for (i in from until to) {
          val r = rows[i]
          Sq8Native.scoreHeap(q8Vecs, q8Scales, r, r + 1, dim, qNative.hi, qNative.hiScale, qNative.lo, qNative.loScale, one, 0)
          out[i] = one[0]
        }
      } else {
        for (i in from until to) {
          val r = rows[i]
          out[i] = Sq8.scoreOne(q, q8Vecs, r * dim, q8Scales[r], dim)
        }
      }
      return
    }
    if (f.format == VectorFormat.SQ8) {
      if (qNative != null) {
        val localRows = IntArray(minOf(to - from, 1024))
        var i = from
        while (i < to) {
          val c = rows[i] / rowsPerChunk
          val runStart = i
          var runLen = 0
          while (i < to && runLen < localRows.size && rows[i] / rowsPerChunk == c) {
            localRows[runLen++] = rows[i] % rowsPerChunk
            i++
          }
          Sq8Native.scoreRowsDirect(
            f.chunkBuffer(c), f.vectorBase, f.fullScaleBase, localRows, 0, runLen, dim,
            qNative.hi, qNative.hiScale, qNative.lo, qNative.loScale, out, runStart,
          )
        }
      } else {
        val reader = f.reader()
        val buf = sq8ScanBuffer.get()!!
        val scBuf = sq8ScaleBuffer.get()!!
        for (i in from until to) {
          reader.fetchSq8(rows[i], 1, buf, scBuf)
          out[i] = Sq8.scoreOne(q, buf, 0, scBuf[0], dim)
        }
      }
      return
    }
    val reader = f.reader()
    val buf = scanBuffer.get()!!
    for (i in from until to) {
      reader.fetch(rows[i], 1, buf)
      scoreBlock(q, buf, 0, 1, one, 0)
      out[i] = one[0]
    }
  }

  /** The rows whose file is in [sortedFileIds] (ascending), returned in ascending row order in O(sum k_i). Call inside [read]. */
  fun rowsOfFiles(sortedFileIds: LongArray): IntArray {
    if (sortedFileIds.isEmpty()) return IntArray(0)
    var out = IntArray(64)
    var n = 0
    var prevId = Long.MIN_VALUE
    var first = true
    for (fileId in sortedFileIds) {
      if (!first && fileId == prevId) continue   // deduplicate in case caller passed duplicate ids
      first = false; prevId = fileId
      var row = headOfFile.get(fileId)
      while (row >= 0) {
        if (n == out.size) out = out.copyOf(n * 2)
        out[n++] = row
        row = fileNext[row]
      }
    }
    if (n == 0) return IntArray(0)
    val result = out.copyOf(n)
    result.sort()
    return result
  }

  private fun normalised(v: FloatArray): FloatArray {
    var sum = 0.0
    for (x in v) {
      if (!x.isFinite()) return FloatArray(v.size)
      sum += x.toDouble() * x
    }
    if (!sum.isFinite() || sum <= 0.0) return FloatArray(v.size)
    val norm = Math.sqrt(sum).toFloat()
    return if (Math.abs(norm - 1f) < 1e-6f) v else FloatArray(v.size) { v[it] / norm }
  }

  // ---- row facts (call inside read) --------------------------------------------------------------------------------

  /** Holds the read lock, so rows cannot move while [block] looks at what [scores] returned. */
  fun <T> read(block: () -> T): T = lock.read(block)

  fun segmentId(row: Int): Long = segments[row]
  fun fileId(row: Int): Long = files[row]
  fun kindOrdinal(row: Int): Int = kinds[row].toInt()
  fun modalityOrdinal(row: Int): Int = modalities[row].toInt()
  fun isLate(row: Int): Boolean = lates[row].toInt() == Schema.Late.LATER_STEP

  /** The row came from the previous model and awaits a re-read (it is still scored, but does not set the z-scale). */
  fun isStale(row: Int): Boolean = lates[row].toInt() == Schema.Late.STALE

  /** Size of the vectors and their facts (heap or mapped), for the status screen. */
  fun bytesInMemory(): Long {
    val vecBytes = if (isQuantized) dim + 4 else dim * 2
    return cachedCount.toLong() * (vecBytes + 8 + 8 + 1 + 1 + 1)
  }

  companion object {
    private const val TAG = "VectorIndex"

    /** Below this many rows, splitting across threads costs more than it saves. */
    private const val PARALLEL_MIN_ROWS = 4096

    /** Rows copied out of the mapping at a time while scoring fp16 (32 x 768 halves = 48 KB: stays in L1/L2). */
    private const val SCAN_ROWS = 32

    /** Rows copied out of the mapping at a time while scoring SQ8 on pure JVM (64 x 768 bytes = 48 KB: stays in L1/L2). */
    private const val SQ8_SCAN_ROWS = 64

    /** Stage-1 blocks copied out of the mapping at a time (256 x 256 bytes = 64 KB). */
    private const val STAGE_SCAN_ROWS = 256

    /** Spare room in the facts arrays after adopting a file, so the first adds do not grow them. */
    private const val SLACK = 1024

    /**
     * Quiet time after the last change before the file is flushed and settled. 15 s (up from 2 s) avoids triggering a full
     * 6-8 MB chunk msync() between every file while Politeness / HeatPace rests (2-10 s); close() still settles immediately.
     */
    const val SETTLE_DELAY_MS = 15_000L

    private val KINDS = 0 until FileKind.entries.size

    fun defaultParallelism(): Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)

    /** Everything EmbeddingGemma makes (text, image, audio, sound lines): one space, one query vector. */
    val EMBEDDING_GEMMA: Set<Modality> = Modality.entries.toSet() - Modality.CLIP

    /** The quick-look pass's vectors (TinyCLIP-61M/32, 512-d). */
    val QUICK_LOOK: Set<Modality> = setOf(Modality.CLIP)

    /** Every fp16 bit pattern as a float (lazy via [Halves.TO_FLOAT] so Go never allocates 256 KB). */
    private val HALF_TO_FLOAT: FloatArray get() = Halves.TO_FLOAT
  }
}

/**
 * Primitive long->int open-addressing map without boxing (about 12 bytes a slot, kept at most 2/3 full).
 * A boxed `HashMap<Long, Int>` cost ~48 bytes an entry and a garbage object per add.
 */
class LongIntMap(expected: Int = 16) {
  private var keys: LongArray
  private var values: IntArray   // -1: empty slot
  private var mask: Int
  var size = 0
    private set

  init {
    var cap = 16
    while (cap * 2 < expected * 3) cap = cap shl 1
    keys = LongArray(cap); values = IntArray(cap) { -1 }; mask = cap - 1
  }

  private fun slot(key: Long): Int {
    var h = key * -7046029254386353131L
    h = h xor (h ushr 32)
    return h.toInt() and mask
  }

  /** The row for [key], or -1. */
  fun get(key: Long): Int {
    var i = slot(key)
    while (true) {
      val v = values[i]
      if (v < 0) return -1
      if (keys[i] == key) return v
      i = (i + 1) and mask
    }
  }

  fun put(key: Long, value: Int) {
    if ((size + 1) * 3 > keys.size * 2) resize(keys.size shl 1)
    var i = slot(key)
    while (true) {
      if (values[i] < 0) { keys[i] = key; values[i] = value; size++; return }
      if (keys[i] == key) { values[i] = value; return }
      i = (i + 1) and mask
    }
  }

  fun remove(key: Long) {
    var i = slot(key)
    while (true) {
      if (values[i] < 0) return
      if (keys[i] == key) break
      i = (i + 1) and mask
    }
    size--
    // Backward-shift deletion: keeps the probe chains intact with no tombstones to leak over time.
    var gap = i
    while (true) {
      i = (i + 1) and mask
      if (values[i] < 0) { values[gap] = -1; return }
      val home = slot(keys[i])
      if (((i - home) and mask) >= ((i - gap) and mask)) {
        keys[gap] = keys[i]; values[gap] = values[i]
        gap = i
      }
    }
  }

  fun clear() { values.fill(-1); size = 0 }

  fun bytes(): Long = keys.size * 12L

  fun become(other: LongIntMap) { keys = other.keys; values = other.values; mask = other.mask; size = other.size }

  private fun resize(newCap: Int) {
    val oldK = keys; val oldV = values
    keys = LongArray(newCap); values = IntArray(newCap) { -1 }; mask = newCap - 1; size = 0
    for (i in oldK.indices) if (oldV[i] >= 0) put(oldK[i], oldV[i])
  }
}

/**
 * How a big index is scanned in two stages: first the leading [stageDim] dims of every row to keep the best [candidates],
 * then full-width scoring only on those (plus the files the words found) and on [scaleSample] evenly spaced rows per
 * modality for the z-scale. Below [minRows] every row is scored exactly.
 */
class TwoStage(val stageDim: Int, val candidates: Int, val minRows: Int, val scaleSample: Int = 2048) {
  override fun toString() = "TwoStage(stageDim=$stageDim, candidates=$candidates, minRows=$minRows, scaleSample=$scaleSample)"

  companion object {
    /**
     * Tuned on a 100k-vector mixed index: 256-d stage 1 keeps the top 500 candidates per modality so cross-modal cosine
     * differences never starve photos behind text; below 20k rows the full scan is already < 25 ms so exact runs.
     */
    val DEFAULT = TwoStage(stageDim = 256, candidates = 500, minRows = 20_000, scaleSample = 8_192)
  }
}

/** Fixed-size min-heap keeping the [capacity] highest-scoring rows seen so far (zero boxing). */
internal class TopK(private val capacity: Int) {
  private val rows = IntArray(capacity)
  private val scores = FloatArray(capacity)
  var count = 0
    private set

  fun offer(row: Int, score: Float) {
    if (capacity <= 0) return
    if (count < capacity) {
      var i = count++
      rows[i] = row; scores[i] = score
      while (i > 0) {
        val p = (i - 1) ushr 1
        if (scores[p] <= score) break
        rows[i] = rows[p]; scores[i] = scores[p]
        i = p
      }
      rows[i] = row; scores[i] = score
    } else if (score > scores[0]) {
      siftDown(0, row, score)
    }
  }

  fun drainInto(other: TopK) {
    for (i in 0 until count) other.offer(rows[i], scores[i])
  }

  fun rows(): IntArray = rows.copyOf(count)

  private fun siftDown(start: Int, row: Int, score: Float) {
    var i = start
    val half = count ushr 1
    while (i < half) {
      var c = (i shl 1) + 1
      val right = c + 1
      if (right < count && scores[right] < scores[c]) c = right
      if (score <= scores[c]) break
      rows[i] = rows[c]; scores[i] = scores[c]
      i = c
    }
    rows[i] = row; scores[i] = score
  }
}
