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
 * The vectors are fp16, row r being `dim` halves at `r * dim`, and a query scans them all sequentially (the CPU prefetches
 * it well). They live **off the Java heap**: with a [backing] file they are in a memory-mapped file that is the index's own
 * store ([VectorFile]), so 100k vectors (150 MB) cost the heap nothing, a cold start maps the file instead of reading every
 * vector out of SQLite, and the kernel can drop the pages under memory pressure. Without one (unit tests, and the fallback
 * when the file cannot be made or grown) they are a plain `ShortArray` on the heap, scored by exactly the same arithmetic.
 *
 * Only the per-row facts search needs without touching SQLite (segment, file, kind, modality, whether it is a "late"
 * vector) stay on the heap, about 19 bytes a row plus a primitive segment lookup: 100k rows are about 5 MB. The same facts
 * are mirrored in the file, which is what lets the next start skip SQLite.
 *
 * Changes are incremental: a file that was just understood adds its rows, a deleted or re-read file removes
 * its rows by swapping the last row into the hole (so removing costs one row copy, not a rebuild). Row order is
 * therefore not stable; nothing may depend on it. Scoring and changes are guarded by a read/write lock.
 *
 * The file is a cache of SQLite, which stays the truth. It is trusted at start only if it was settled (every change flushed,
 * see [VectorFile]) at the database's current vector generation; otherwise [load] rebuilds it from SQLite, so a crash, a
 * corrupt or missing file, or a change made behind its back costs one slow start and never a wrong answer.
 *
 * fp16 to float is a 64k-entry lookup table (256 KB, stays in L2): the JVM and ART have no half-float intrinsics,
 * and the table beat bit-twiddling per element in the benchmark.
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
) {
  private val lock = ReentrantReadWriteLock()
  /** Width of the stage-1 blocks kept beside the vectors (0: none). */
  private val stageDim: Int = twoStage?.stageDim?.also { require(it in 1 until dim) { "stage width $it must be in 1 until $dim" } } ?: 0
  /** The vectors when they are on the heap (empty while [file] holds them). */
  private var halves = ShortArray(if (backing == null) initialCapacity * dim else 0)
  /** The stage-1 blocks when the vectors are on the heap, row r at `r * stageDim`. */
  private var stageHalves = ShortArray(if (backing == null) initialCapacity * stageDim else 0)
  private var segments = LongArray(initialCapacity)
  private var files = LongArray(initialCapacity)
  private var kinds = ByteArray(initialCapacity)
  private var modalities = ByteArray(initialCapacity)
  /** [Schema.Late] per row: 0 current, 1 a later step of a long file, 2 stale (made by the previous model, awaiting a re-read). */
  private var lates = ByteArray(initialCapacity)
  private var count = 0
  private val rowOfSegment = LongIntMap()

  /** The mapped store, when the vectors are off the heap. */
  private var file: VectorFile? = null
  /** True once the file could not be made or grown: the index went back to the heap and stays there. */
  private var heapFallback = false
  private var generationNow: (() -> Long)? = null
  private val rowScratch = ShortArray(dim)
  private val stageScratch = ShortArray(stageDim)
  private val scanBuffer = ThreadLocal.withInitial { ShortArray(dim * SCAN_ROWS) }
  private val stageBuffer = ThreadLocal.withInitial { ShortArray(stageDim * STAGE_SCAN_ROWS) }

  private val threads = parallelism.coerceAtLeast(1)
  private val pool: ExecutorService? = if (threads > 1) Executors.newFixedThreadPool(threads) { r ->
    Thread(r, "findr-vector-score").apply { isDaemon = true }
  } else null

  val size: Int get() = lock.read { count }

  /** True when the vectors are in a mapped file rather than on the heap. */
  val offHeap: Boolean get() = lock.read { file != null }

  /** What the index holds on the Java heap: the vectors only in heap mode, always the row facts and the segment lookup. */
  fun heapBytes(): Long = lock.read {
    (halves.size + stageHalves.size) * 2L + segments.size * 8L + files.size * 8L + kinds.size + modalities.size + lates.size + rowOfSegment.bytes()
  }

  /** What the mapped file spans (virtual: the kernel keeps only the pages in use, and may drop them, clean). */
  fun mappedBytes(): Long = lock.read { file?.fileBytes ?: 0L }

  /** What the index holds, heap and mapped together (the figure the status screen shows). */
  fun memoryBytes(): Long = heapBytes() + mappedBytes()

  // ---- changing --------------------------------------------------------------------------------------------------

  /** Adds one vector. [vector] must already be L2-normalised, as the embedder produces it. */
  fun add(segmentId: Long, fileId: Long, kind: FileKind, modality: Modality, late: Boolean, vector: FloatArray, stale: Boolean = false) {
    if (modality !in holds) return
    require(vector.size == dim) { "vector has ${vector.size} dimensions, index uses $dim" }
    addHalves(segmentId, fileId, kind, modality, lateness(late, stale)) { dst -> for (i in 0 until dim) dst[i] = Fp16.toHalf(vector[i]) }
  }

  /** Adds a vector as stored in the database (fp16, little endian). [late] is the stored [Schema.Late] value. */
  fun addBlob(segmentId: Long, fileId: Long, kind: FileKind, modality: Modality, late: Int, blob: ByteArray) {
    if (modality !in holds) return   // another model's vector: it lives in the other index
    if (blob.size != dim * 2) return   // a vector of another size cannot be scored against this query
    addHalves(segmentId, fileId, kind, modality, late.coerceIn(0, Schema.Late.STALE).toByte()) { dst ->
      for (i in 0 until dim) dst[i] = ((blob[2 * i].toInt() and 0xFF) or (blob[2 * i + 1].toInt() shl 8)).toShort()
    }
  }

  /** Runs [block] as a change: the lock held for writing, the file marked open before anything in it moves, a settle scheduled after. */
  private inline fun <T> changing(block: () -> T): T = lock.write {
    ensureFile()
    file?.markOpen()
    try { block() } finally { file?.setCount(count); scheduleSettle() }
  }

  private inline fun addHalves(segmentId: Long, fileId: Long, kind: FileKind, modality: Modality, late: Byte, fill: (ShortArray) -> Unit) =
    changing {
      val replaced = rowOfSegment.get(segmentId)
      if (replaced >= 0) removeRow(replaced)   // replaced
      reserveRow()
      fill(rowScratch)
      writeVector(count, rowScratch)
      setFacts(count, segmentId, fileId, kind.ordinal, modality.ordinal, late.toInt())
      rowOfSegment.put(segmentId, count)
      count++
    }

  private fun lateness(late: Boolean, stale: Boolean): Byte =
    (if (stale) Schema.Late.STALE else if (late) Schema.Late.LATER_STEP else Schema.Late.CURRENT).toByte()

  /**
   * A model upgrade kept the stored vectors but they now come from the previous model ([IndexStore.ensureModelFingerprint]
   * with `keepStale`): flags the rows of these modalities as stale, in place and in one step, so search never sees a gap.
   * Returns how many rows changed.
   */
  fun markStale(which: Set<Modality>): Int = changing {
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

  /** Drops every vector of a file (it was deleted, or is about to be read again). Returns how many went. */
  fun removeFile(fileId: Long): Int = changing {
    var removed = 0
    var row = 0
    while (row < count) {
      if (files[row] == fileId) { removeRow(row); removed++ } else row++   // the swapped-in row is checked next
    }
    removed
  }

  fun removeFiles(fileIds: Collection<Long>): Int {
    if (fileIds.isEmpty()) return 0
    val set = fileIds.toHashSet()
    return changing {
      var removed = 0
      var row = 0
      while (row < count) {
        if (files[row] in set) { removeRow(row); removed++ } else row++
      }
      removed
    }
  }

  /** Empties the index. In a file the emptied rows are overwritten with zeros, so nothing removed lingers in it. */
  fun clear() = changing { emptyRows() }

  private fun emptyRows() {
    val f = file
    if (f != null) for (row in 0 until count) { f.zeroVector(row); f.clearMeta(row) }
    count = 0
    rowOfSegment.clear()
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
      Log.i(TAG, "vector file adopted: ${size} rows in ${(System.nanoTime() - started) / 1_000_000} ms")
      scheduleSettle()
      return
    }
    lock.write {
      file?.close()
      file = null
      count = 0
      rowOfSegment.clear()
    }
    rows { segment, fileId, kind, modality, late, blob -> addBlob(segment, fileId, kind, modality, late, blob) }
    if (backing != null) Log.i(TAG, "vector file rebuilt from the database: ${size} rows in ${(System.nanoTime() - started) / 1_000_000} ms")
    scheduleSettle()
  }

  /** Adopts the file if it can be trusted; false leaves the index untouched. */
  private fun tryAttach(expectedGeneration: Long): Boolean {
    val f = VectorFile.openExisting(backing!!, dim, rowsPerChunk, stageDim) ?: return false
    if (f.generation != expectedGeneration) { f.close(); return false }
    lock.write {
      val n = f.count
      val s = LongArray(n + SLACK); val fl = LongArray(n + SLACK)
      val k = ByteArray(n + SLACK); val m = ByteArray(n + SLACK); val l = ByteArray(n + SLACK)
      val map = LongIntMap(n)
      var ok = true
      f.forEachMeta(n) { row, segment, fileId, kind, modality, late ->
        if (!ok) return@forEachMeta
        if (kind !in KINDS || modality !in holdsOrdinals || late !in 0..Schema.Late.STALE || map.get(segment) >= 0) { ok = false; return@forEachMeta }
        s[row] = segment; fl[row] = fileId; k[row] = kind.toByte(); m[row] = modality.toByte(); l[row] = late.toByte()
        map.put(segment, row)
      }
      if (!ok) { f.close(); return false }
      segments = s; files = fl; kinds = k; modalities = m; lates = l
      halves = ShortArray(0); stageHalves = ShortArray(0)
      rowOfSegment.become(map)
      count = n
      file = f
    }
    return true
  }

  private val holdsOrdinals: Set<Int> = holds.map { it.ordinal }.toSet()

  /** Adds the vectors of one file from the store (after the engine saved its segments). */
  fun loadFile(store: IndexStore, fileId: Long) {
    // Read first, then swap under one write lock: a search never sees the file between its old vectors and its new ones.
    class Row(val segment: Long, val kind: FileKind, val modality: Modality, val late: Int, val blob: ByteArray)
    val rows = ArrayList<Row>()
    store.forEachVectorOfFile(fileId) { segment, _, kind, modality, late, blob -> rows += Row(segment, kind, modality, late, blob) }
    lock.write {
      removeFile(fileId)
      for (r in rows) addBlob(r.segment, fileId, r.kind, r.modality, r.late, r.blob)
    }
  }

  // ---- storage ---------------------------------------------------------------------------------------------------

  /** Opens the file on the first change when there is a backing and no file yet (an index used without [load]). */
  private fun ensureFile() {
    if (file != null || backing == null || heapFallback) return
    try {
      file = VectorFile.create(backing, dim, rowsPerChunk, stageDim)
      halves = ShortArray(0); stageHalves = ShortArray(0)
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
    System.arraycopy(src, 0, halves, row * dim, dim)
    if (stageDim > 0) { Halves.stageOf(src, stageDim, stageScratch); System.arraycopy(stageScratch, 0, stageHalves, row * stageDim, stageDim) }
  }

  private fun setFacts(row: Int, segment: Long, fileId: Long, kind: Int, modality: Int, late: Int) {
    segments[row] = segment; files[row] = fileId; kinds[row] = kind.toByte(); modalities[row] = modality.toByte(); lates[row] = late.toByte()
    file?.putMeta(row, segment, fileId, kind, modality, late)
  }

  private fun removeRow(row: Int) {
    val last = count - 1
    rowOfSegment.remove(segments[row])
    val f = file
    if (row != last) {
      if (f != null) f.copyVector(last, row) else {
        System.arraycopy(halves, last * dim, halves, row * dim, dim)
        if (stageDim > 0) System.arraycopy(stageHalves, last * stageDim, stageHalves, row * stageDim, stageDim)
      }
      setFacts(row, segments[last], files[last], kinds[last].toInt(), modalities[last].toInt(), lates[last].toInt())
      rowOfSegment.put(segments[row], row)
    }
    if (f != null) { f.zeroVector(last); f.clearMeta(last) }   // a removed vector does not linger in the file
    count = last
  }

  private fun grow() {
    val capacity = (segments.size * 3 / 2).coerceAtLeast(segments.size + 1024)
    segments = segments.copyOf(capacity); files = files.copyOf(capacity); kinds = kinds.copyOf(capacity)
    modalities = modalities.copyOf(capacity); lates = lates.copyOf(capacity)
    if (file == null) { halves = halves.copyOf(capacity * dim); stageHalves = stageHalves.copyOf(capacity * stageDim) }
  }

  /** The file cannot be made or grown (disk full, say): carry on from the heap, as an index without a backing does. */
  private fun fallBackToHeap(cause: IOException) {
    Log.w(TAG, "vector file unavailable (${cause.javaClass.simpleName}); keeping vectors on the heap")
    heapFallback = true
    val f = file
    val all = ShortArray(maxOf(segments.size, count + 1) * dim)
    if (f != null) {
      val reader = f.reader()
      var row = 0
      val buf = ShortArray(dim * SCAN_ROWS)
      while (row < count) {
        val n = reader.fetch(row, count - row, buf)
        System.arraycopy(buf, 0, all, row * dim, n * dim)
        row += n
      }
      f.close()
    }
    halves = all
    stageHalves = ShortArray(all.size / dim * stageDim)
    if (stageDim > 0) {
      val row = ShortArray(dim); val stage = ShortArray(stageDim)
      for (r in 0 until count) {
        System.arraycopy(all, r * dim, row, 0, dim)
        Halves.stageOf(row, stageDim, stage)
        System.arraycopy(stage, 0, stageHalves, r * stageDim, stageDim)
      }
    }
    file = null
    backing?.let { runCatching { VectorFile.shred(it); it.delete() } }
  }

  // ---- settling --------------------------------------------------------------------------------------------------

  private val settleArmed = AtomicBoolean(false)
  @Volatile private var lastChangeNanos = 0L
  private val settler: ScheduledThreadPoolExecutor by lazy {
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
    lock.read {
      val f = file
      // Only a file this process changed is settled. One that is not open but is behind the database missed a change made
      // elsewhere; leaving it as it is makes the next start rebuild it rather than vouch for it.
      if (f === f0 && f != null && f.open) f.settle(gen)
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
    rowOfSegment.clear()
    halves = if (backing == null) halves else ShortArray(0)
    stageHalves = if (backing == null) stageHalves else ShortArray(0)
    heapFallback = false   // a new file may work where the old one did not (space freed by the wipe)
    generationNow = null
    backing?.let { VectorFile.shred(it) } ?: 0L
  }

  /** [shred] and delete the file. */
  fun destroy() {
    shred()
    backing?.delete()
  }

  fun close() {
    pool?.shutdown()
    if (backing != null && settleDelayMs >= 0) {
      runCatching { settleNow() }
      settler.shutdown()
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
    val pool = pool
    if (pool == null || count < PARALLEL_MIN_ROWS) {
      scoreRows(q, 0, count, out)
    } else {
      val step = (count + threads - 1) / threads
      val jobs = (0 until threads).mapNotNull { t ->
        val from = t * step
        val to = minOf(count, from + step)
        if (from >= to) null else Callable { scoreRows(q, from, to, out) }
      }
      pool.invokeAll(jobs).forEach { it.get() }
    }
    out
  }

  private fun scoreRows(q: FloatArray, from: Int, to: Int, out: FloatArray) {
    val f = file
    if (f == null) { scoreBlock(q, halves, from * dim, to - from, out, from); return }
    // Off the heap: a few rows at a time are copied from the mapping into a small array that stays in cache, then scored by the same loop.
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

  /**
   * Stage 1: the rows with the best score on the first [TwoStage.stageDim] dims, at most [TwoStage.candidates] of them and only
   * among rows whose kind (ordinal) is true in [allowedKinds], in ascending row order. Reads [stageDim]/[dim] of the bytes an exact
   * scan reads. Call inside [read]; the rows are valid in the same [read].
   */
  fun stageCandidates(query: FloatArray, allowedKinds: BooleanArray): IntArray = lock.read {
    val t = checkNotNull(twoStage) { "no two-stage scan configured" }
    require(query.size == dim) { "query has ${query.size} dimensions, index uses $dim" }
    val n = count
    val k = minOf(t.candidates, n)
    if (k <= 0) return@read IntArray(0)
    val q = stageQuery(query)
    val pool = pool
    val merged = TopK(k)
    if (pool == null || n < PARALLEL_MIN_ROWS) {
      scanStage(q, 0, n, allowedKinds, merged)
    } else {
      val step = (n + threads - 1) / threads
      val jobs = (0 until threads).mapNotNull { th ->
        val from = th * step
        val to = minOf(n, from + step)
        if (from >= to) null else Callable { TopK(k).also { scanStage(q, from, to, allowedKinds, it) } }
      }
      for (f in pool.invokeAll(jobs)) f.get().drainInto(merged)
    }
    merged.rows().also { it.sort() }
  }

  /** The first [stageDim] dims of [query], scaled to unit length: the stage-1 query. */
  private fun stageQuery(query: FloatArray): FloatArray {
    var sum = 0.0
    for (i in 0 until stageDim) sum += query[i].toDouble() * query[i]
    val inv = if (sum > 0.0) (1.0 / Math.sqrt(sum)).toFloat() else 0f
    return FloatArray(stageDim) { query[it] * inv }
  }

  private fun scanStage(q: FloatArray, from: Int, to: Int, allowed: BooleanArray, top: TopK) {
    val f = file
    if (f == null) { stageBlock(q, stageHalves, from * stageDim, to - from, from, allowed, top); return }
    val reader = f.reader()
    val buf = stageBuffer.get()!!
    var row = from
    while (row < to) {
      val n = reader.fetchStage(row, to - row, buf)
      stageBlock(q, buf, 0, n, row, allowed, top)
      row += n
    }
  }

  /** Scores [n] stage-1 blocks starting at [start] against [q] (same four-accumulator loop as [scoreBlock]); rows of other kinds are skipped. */
  private fun stageBlock(q: FloatArray, halves: ShortArray, start: Int, n: Int, firstRow: Int, allowed: BooleanArray, top: TopK) {
    val table = HALF_TO_FLOAT
    val d = stageDim
    for (r in 0 until n) {
      if (!allowed[kinds[firstRow + r].toInt()]) continue
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
      top.offer(firstRow + r, (a0 + a1) + (a2 + a3))
    }
  }

  /**
   * Stage 2 and the scale sample: the exact (full-width) cosine of [query] with each of [rows], in that order. The arithmetic is
   * [scores]'s, so a row scores exactly what it would in a full scan. Call inside [read].
   */
  fun scoresAt(query: FloatArray, rows: IntArray): FloatArray = lock.read {
    require(query.size == dim) { "query has ${query.size} dimensions, index uses $dim" }
    val out = FloatArray(rows.size)
    if (rows.isEmpty()) return@read out
    val q = normalised(query)
    val pool = pool
    if (pool == null || rows.size < PARALLEL_MIN_ROWS / 2) {
      scoreRowList(q, rows, 0, rows.size, out)
    } else {
      val step = (rows.size + threads - 1) / threads
      val jobs = (0 until threads).mapNotNull { th ->
        val from = th * step
        val to = minOf(rows.size, from + step)
        if (from >= to) null else Callable { scoreRowList(q, rows, from, to, out) }
      }
      pool.invokeAll(jobs).forEach { it.get() }
    }
    out
  }

  private fun scoreRowList(q: FloatArray, rows: IntArray, from: Int, to: Int, out: FloatArray) {
    val f = file
    val one = FloatArray(1)
    if (f == null) {
      for (i in from until to) { scoreBlock(q, halves, rows[i] * dim, 1, one, 0); out[i] = one[0] }
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

  /** The rows whose file is in [sortedFileIds] (ascending). Call inside [read]. */
  fun rowsOfFiles(sortedFileIds: LongArray): IntArray {
    if (sortedFileIds.isEmpty()) return IntArray(0)
    var out = IntArray(64)
    var n = 0
    for (row in 0 until count) {
      if (java.util.Arrays.binarySearch(sortedFileIds, files[row]) >= 0) {
        if (n == out.size) out = out.copyOf(n * 2)
        out[n++] = row
      }
    }
    return out.copyOf(n)
  }

  private fun normalised(v: FloatArray): FloatArray {
    var sum = 0.0
    for (x in v) sum += x.toDouble() * x
    val norm = Math.sqrt(sum).toFloat()
    return if (norm == 0f || Math.abs(norm - 1f) < 1e-6f) v else FloatArray(v.size) { v[it] / norm }
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
  fun bytesInMemory(): Long = lock.read { count.toLong() * (dim * 2 + 8 + 8 + 1 + 1 + 1) }

  companion object {
    private const val TAG = "VectorIndex"

    /** Below this many rows, splitting across threads costs more than it saves. */
    private const val PARALLEL_MIN_ROWS = 4096

    /** Rows copied out of the mapping at a time while scoring (32 x 768 halves = 48 KB: stays in L1/L2). */
    private const val SCAN_ROWS = 32

    /** Stage-1 blocks copied out of the mapping at a time (256 x 128 halves = 64 KB). */
    private const val STAGE_SCAN_ROWS = 256

    /** Spare room in the facts arrays after adopting a file, so the first adds do not grow them. */
    private const val SLACK = 1024

    /** Quiet time after the last change before the file is flushed and settled. */
    const val SETTLE_DELAY_MS = 2_000L

    private val KINDS = 0 until FileKind.entries.size

    fun defaultParallelism(): Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)

    /** Everything EmbeddingGemma makes (text, image, audio, sound lines): one space, one query vector. */
    val EMBEDDING_GEMMA: Set<Modality> = Modality.entries.toSet() - Modality.CLIP

    /** The quick-look pass's vectors (TinyCLIP-61M/32, 512-d). */
    val QUICK_LOOK: Set<Modality> = setOf(Modality.CLIP)

    /** Every fp16 bit pattern as a float. */
    private val HALF_TO_FLOAT = Halves.TO_FLOAT
  }
}

/**
 * Segment id to row, without boxing: open addressing over two primitive arrays (about 12 bytes a slot, kept at most half full).
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
    while (cap < expected * 2) cap = cap shl 1
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
    if ((size + 1) * 2 > keys.size) resize(keys.size shl 1)
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
    // Backward-shift deletion: pull later members of the probe run into the hole so lookups never meet a false gap.
    var hole = i
    var j = (i + 1) and mask
    while (values[j] >= 0) {
      val home = slot(keys[j])
      if (((j - home) and mask) >= ((j - hole) and mask)) {
        keys[hole] = keys[j]; values[hole] = values[j]; hole = j
      }
      j = (j + 1) and mask
    }
    values[hole] = -1
    size--
  }

  fun clear() { java.util.Arrays.fill(values, -1); size = 0 }

  fun bytes(): Long = keys.size * 12L

  fun become(other: LongIntMap) { keys = other.keys; values = other.values; mask = other.mask; size = other.size }

  private fun resize(capacity: Int) {
    val oldKeys = keys; val oldValues = values
    keys = LongArray(capacity); values = IntArray(capacity) { -1 }; mask = capacity - 1; size = 0
    for (i in oldKeys.indices) if (oldValues[i] >= 0) put(oldKeys[i], oldValues[i])
  }
}

/**
 * The two-stage scan's settings. Stage 1 scores every row on the first [stageDim] dims (EmbeddingGemma is Matryoshka-trained: the
 * leading dims are a smaller embedding), kept as a separate block beside the full vectors so the scan streams a fraction of the
 * bytes; the best [candidates] rows are rescored at full width. An index with fewer than [minRows] rows is scanned exactly.
 */
class TwoStage(val stageDim: Int, val candidates: Int, val minRows: Int, val scaleSample: Int = 2048) {
  override fun toString() = "TwoStage(stageDim=$stageDim, candidates=$candidates, minRows=$minRows, scaleSample=$scaleSample)"

  companion object {
    /**
     * What ships (the build notes, 2026-10-15 "two-stage scan"): 256 dims, 500 candidates, exact below 20,000 rows, and a scale estimated from
     * 8,192 exactly scored rows a modality. 128 dims failed the known-item set's per-group gate (it loses the right file for some
     * low-resource-language queries); 256 dims passed every set at 50k and 100k rows.
     */
    val DEFAULT = TwoStage(stageDim = 256, candidates = 500, minRows = 20_000, scaleSample = 8_192)
  }
}

/** A fixed-size min-heap of the best (row, score) pairs offered. */
class TopK(private val k: Int) {
  private val rowOf = IntArray(k)
  private val scoreOf = FloatArray(k)
  private var size = 0

  fun offer(row: Int, score: Float) {
    if (size < k) {
      var i = size++
      while (i > 0) {
        val parent = (i - 1) / 2
        if (scoreOf[parent] <= score) break
        rowOf[i] = rowOf[parent]; scoreOf[i] = scoreOf[parent]; i = parent
      }
      rowOf[i] = row; scoreOf[i] = score
    } else if (score > scoreOf[0]) {
      var i = 0
      while (true) {
        var child = 2 * i + 1
        if (child >= size) break
        if (child + 1 < size && scoreOf[child + 1] < scoreOf[child]) child++
        if (scoreOf[child] >= score) break
        rowOf[i] = rowOf[child]; scoreOf[i] = scoreOf[child]; i = child
      }
      rowOf[i] = row; scoreOf[i] = score
    }
  }

  fun drainInto(other: TopK) { for (i in 0 until size) other.offer(rowOf[i], scoreOf[i]) }

  fun rows(): IntArray = rowOf.copyOf(size)
}
