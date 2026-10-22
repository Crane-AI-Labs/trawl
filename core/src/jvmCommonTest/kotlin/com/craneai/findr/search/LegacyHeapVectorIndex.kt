package com.craneai.findr.search

import com.craneai.findr.core.FileKind
import com.craneai.findr.index.Fp16
import com.craneai.findr.index.IndexStore
import com.craneai.findr.index.Modality
import com.craneai.findr.index.Schema
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Every embedding in memory, so a query can be scored against all of them in tens of milliseconds.
 *
 * One contiguous fp16 array (row r is `halves[r * dim until (r + 1) * dim]`): half the memory of floats, no
 * per-vector objects for the GC, and a sequential scan the CPU prefetches well. 50k vectors are 77 MB. Rows carry
 * the facts search needs without touching SQLite (segment, file, kind, modality, whether it is a "late" vector).
 *
 * Changes are incremental: a file that was just understood adds its rows, a deleted or re-read file removes
 * its rows by swapping the last row into the hole (so removing costs one row copy, not a rebuild). Row order is
 * therefore not stable; nothing may depend on it. Scoring and changes are guarded by a read/write lock.
 *
 * fp16 to float is a 64k-entry lookup table (256 KB, stays in L2): the JVM and ART have no half-float intrinsics,
 * and the table beat bit-twiddling per element in the benchmark.
 */
class LegacyHeapVectorIndex(
  val dim: Int = Fp16.DIM,
  initialCapacity: Int = 1024,
  /** Threads used by [scores]. 1 scores on the calling thread; more split the rows into blocks. */
  parallelism: Int = defaultParallelism(),
  /**
   * The modalities this index holds. Vectors from different models live in different spaces and are scored against
   * different query vectors, so each model gets its own index: EmbeddingGemma's (the default) and the quick-look one.
   */
  val holds: Set<Modality> = EMBEDDING_GEMMA,
) {
  private val lock = ReentrantReadWriteLock()
  private var halves = ShortArray(initialCapacity * dim)
  private var segments = LongArray(initialCapacity)
  private var files = LongArray(initialCapacity)
  private var kinds = ByteArray(initialCapacity)
  private var modalities = ByteArray(initialCapacity)
  /** [Schema.Late] per row: 0 current, 1 a later step of a long file, 2 stale (made by the previous model, awaiting a re-read). */
  private var lates = ByteArray(initialCapacity)
  private var count = 0
  private val rowOfSegment = HashMap<Long, Int>()

  private val threads = parallelism.coerceAtLeast(1)
  private val pool: ExecutorService? = if (threads > 1) Executors.newFixedThreadPool(threads) { r ->
    Thread(r, "findr-vector-score").apply { isDaemon = true }
  } else null

  val size: Int get() = lock.read { count }

  /** What the matrix holds in memory right now: the arrays at their allocated size, and the segment lookup (~48 bytes an entry). */
  fun memoryBytes(): Long = lock.read {
    halves.size * 2L + segments.size * 8L + files.size * 8L + kinds.size + modalities.size + lates.size + count * 48L
  }

  // ---- changing --------------------------------------------------------------------------------------------------

  /** Adds one vector. [vector] must already be L2-normalised, as the embedder produces it. */
  fun add(segmentId: Long, fileId: Long, kind: FileKind, modality: Modality, late: Boolean, vector: FloatArray, stale: Boolean = false) {
    if (modality !in holds) return
    require(vector.size == dim) { "vector has ${vector.size} dimensions, index uses $dim" }
    addHalves(segmentId, fileId, kind, modality, lateness(late, stale)) { dst, at -> for (i in 0 until dim) dst[at + i] = Fp16.toHalf(vector[i]) }
  }

  /** Adds a vector as stored in the database (fp16, little endian). [late] is the stored [Schema.Late] value. */
  fun addBlob(segmentId: Long, fileId: Long, kind: FileKind, modality: Modality, late: Int, blob: ByteArray) {
    if (modality !in holds) return   // another model's vector: it lives in the other index
    if (blob.size != dim * 2) return   // a vector of another size cannot be scored against this query
    addHalves(segmentId, fileId, kind, modality, late.coerceIn(0, Schema.Late.STALE).toByte()) { dst, at ->
      for (i in 0 until dim) dst[at + i] = ((blob[2 * i].toInt() and 0xFF) or (blob[2 * i + 1].toInt() shl 8)).toShort()
    }
  }

  private inline fun addHalves(segmentId: Long, fileId: Long, kind: FileKind, modality: Modality, late: Byte, fill: (ShortArray, Int) -> Unit) =
    lock.write {
      if (rowOfSegment.containsKey(segmentId)) removeRow(rowOfSegment.getValue(segmentId))   // replaced
      if (count == segments.size) grow()
      fill(halves, count * dim)
      segments[count] = segmentId
      files[count] = fileId
      kinds[count] = kind.ordinal.toByte()
      modalities[count] = modality.ordinal.toByte()
      lates[count] = late
      rowOfSegment[segmentId] = count
      count++
    }

  private fun lateness(late: Boolean, stale: Boolean): Byte =
    (if (stale) Schema.Late.STALE else if (late) Schema.Late.LATER_STEP else Schema.Late.CURRENT).toByte()

  /**
   * A model upgrade kept the stored vectors but they now come from the previous model ([IndexStore.ensureModelFingerprint]
   * with `keepStale`): flags the rows of these modalities as stale, in place and in one step, so search never sees a gap.
   * Returns how many rows changed.
   */
  fun markStale(which: Set<Modality>): Int = lock.write {
    val ordinals = which.map { it.ordinal }.toSet()
    var n = 0
    for (row in 0 until count) {
      if (modalities[row].toInt() in ordinals && lates[row].toInt() != Schema.Late.STALE) { lates[row] = Schema.Late.STALE.toByte(); n++ }
    }
    n
  }

  /** Drops every vector of a file (it was deleted, or is about to be read again). Returns how many went. */
  fun removeFile(fileId: Long): Int = lock.write {
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
    return lock.write {
      var removed = 0
      var row = 0
      while (row < count) {
        if (files[row] in set) { removeRow(row); removed++ } else row++
      }
      removed
    }
  }

  fun clear() = lock.write {
    count = 0
    rowOfSegment.clear()
  }

  /** Reads every vector from [store] (replacing what is held). Called once at start and after a model change. */
  fun load(store: IndexStore) {
    clear()
    store.forEachVector { segment, file, kind, modality, late, blob -> addBlob(segment, file, kind, modality, late, blob) }
  }

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

  private fun removeRow(row: Int) {
    val last = count - 1
    rowOfSegment.remove(segments[row])
    if (row != last) {
      System.arraycopy(halves, last * dim, halves, row * dim, dim)
      segments[row] = segments[last]; files[row] = files[last]; kinds[row] = kinds[last]
      modalities[row] = modalities[last]; lates[row] = lates[last]
      rowOfSegment[segments[row]] = row
    }
    count = last
  }

  private fun grow() {
    val capacity = (segments.size * 3 / 2).coerceAtLeast(segments.size + 1024)
    halves = halves.copyOf(capacity * dim)
    segments = segments.copyOf(capacity); files = files.copyOf(capacity); kinds = kinds.copyOf(capacity)
    modalities = modalities.copyOf(capacity); lates = lates.copyOf(capacity)
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
    val table = HALF_TO_FLOAT
    val halves = halves
    val dim = dim
    val q = q
    for (row in from until to) {
      var base = row * dim
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
      out[row] = (a0 + a1) + (a2 + a3)
    }
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

  /** Memory held by the vectors and their facts, for the status screen. */
  fun bytesInMemory(): Long = lock.read { count.toLong() * (dim * 2 + 8 + 8 + 1 + 1 + 1) }

  fun close() {
    pool?.shutdown()
  }

  companion object {
    /** Below this many rows, splitting across threads costs more than it saves. */
    private const val PARALLEL_MIN_ROWS = 4096

    fun defaultParallelism(): Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)

    /** Everything EmbeddingGemma makes (text, image, audio, sound lines): one space, one query vector. */
    val EMBEDDING_GEMMA: Set<Modality> = Modality.entries.toSet() - Modality.CLIP

    /** The quick-look pass's vectors (TinyCLIP-61M/32, 512-d). */
    val QUICK_LOOK: Set<Modality> = setOf(Modality.CLIP)

    /** Every fp16 bit pattern as a float. */
    private val HALF_TO_FLOAT = FloatArray(65536) { Fp16.toFloat(it.toShort()) }
  }
}
