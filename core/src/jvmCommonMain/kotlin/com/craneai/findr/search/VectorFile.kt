package com.craneai.findr.search

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.ShortBuffer
import java.nio.channels.FileChannel
import java.util.BitSet
import java.util.zip.CRC32

/**
 * The vector index's own store: a file in the app's private storage, memory-mapped, so a 100k-vector index costs the Java
 * heap nothing (150 MB of fp16 would not fit a 192 MB heap, and even a few hundred MB heaps are a ceiling). The kernel
 * pages it in and out; the pages are clean file-backed memory it can drop under pressure, where a heap array is memory only
 * the low-memory killer can reclaim. A cold start maps the file instead of reading every vector out of SQLite.
 *
 * Layout (all little endian, the byte order the vectors have in the database, so a vector's bytes are the same everywhere):
 *  - one 4 KB header: magic, version, vector size, rows per chunk, whether the file is [settled][settle] or open for change,
 *    the row count, the database generation it settled at, a CRC of those fields;
 *  - then chunks of [rowsPerChunk] rows, each with the rows' facts first (segment id, file id, kind, modality, late flag:
 *    24 bytes a row), their fp16 vectors after, and then, when the index scans in two stages, the rows' stage-1 blocks (the
 *    first `stageDim` dims, renormalised, fp16), contiguous so stage 1 streams only those bytes. A chunk is mapped on its own,
 *    so growing appends one chunk and moves nothing, and the 2 GB limit of one mapping never applies. The stage width is in
 *    the header (version 2; a version 1 file is a version 2 file with no stage block): a file written with another width is rebuilt
 *    from SQLite like any other stale cache.
 *
 * Crash safety (the database stays the truth; this file is a cache of it):
 *  - the first change after a settle marks the header open and forces it to disk; [settle] forces the changed chunks first and
 *    only then writes "settled" with the database generation. A process killed (or a phone powered off) mid-change leaves the
 *    file open, and the next start rebuilds it from SQLite;
 *  - a settled file is trusted only if its generation is the database's ([com.craneai.findr.index.Schema.VEC_GEN_TRIGGERS]),
 *    its header CRC holds and its length is exactly the chunks it names;
 *  - a new chunk is written out as zeros before it is mapped, so a full disk surfaces as an [IOException] (and the index falls
 *    back to the heap) rather than as a SIGBUS when a mapped page cannot be backed.
 *
 * Not thread-safe: the owner ([VectorIndex]) calls everything but [Reader] under its write lock; each scoring thread has its
 * own [Reader]. Nothing here logs a path or a name.
 */
class VectorFile private constructor(
  private val path: File,
  val dim: Int,
  val rowsPerChunk: Int,
  /** Width of the stage-1 block of the two-stage scan (0: none). See [Halves.stageOf]. */
  val stageDim: Int,
  private val raf: RandomAccessFile,
) {
  private val channel: FileChannel = raf.channel
  private val chunks = ArrayList<MappedByteBuffer>()
  private val writeViews = ArrayList<ShortBuffer?>()
  private val dirty = BitSet()
  private var header: MappedByteBuffer? = null
  private val chunkBytes: Long = rowsPerChunk.toLong() * (META_ROW + (dim + stageDim) * 2L)
  private val vectorBase: Int = rowsPerChunk * META_ROW
  /** Where a chunk's stage-1 block starts: after its full vectors. */
  private val stageBase: Int = vectorBase + rowsPerChunk * dim * 2
  private val scratch = ShortArray(dim)
  private val zeros = ShortArray(dim)
  private val stageScratch = ShortArray(stageDim)
  private val writeStageViews = ArrayList<ShortBuffer?>()

  /** True while the header says "open for change": [markOpen] was called and [settle] has not run since. */
  var open: Boolean = false
    private set

  /** Rows the file says it holds. */
  var count: Int = 0
    private set

  /** The database generation this file was last settled at (see [settle]). */
  var generation: Long = 0
    private set

  val capacity: Int get() = chunks.size * rowsPerChunk

  /** What the file takes on disk (and, when every page is resident, in mapped memory). */
  val fileBytes: Long get() = HEADER + chunks.size * chunkBytes

  // ---- changing --------------------------------------------------------------------------------------------------

  /** Makes room for [rows] rows by appending chunks. Throws [IOException] when the disk cannot hold them. */
  fun ensureCapacity(rows: Int) {
    while (capacity < rows) appendChunk()
  }

  private fun appendChunk() {
    val start = HEADER + chunks.size * chunkBytes
    val end = start + chunkBytes
    try {
      val zero = ByteBuffer.allocate(ZERO_BLOCK)
      var at = start
      while (at < end) {
        zero.clear()
        zero.limit(minOf(ZERO_BLOCK.toLong(), end - at).toInt())
        while (zero.hasRemaining()) at += channel.write(zero, at)
      }
      val mapped = channel.map(FileChannel.MapMode.READ_WRITE, start, chunkBytes)
      mapped.order(ByteOrder.LITTLE_ENDIAN)
      chunks += mapped
      writeViews += null
      writeStageViews += null
    } catch (e: IOException) {
      runCatching { raf.setLength(start) }
      throw e
    }
  }

  /** Marks the file open for change. The first call after a settle forces the header to disk before anything else is touched. */
  fun markOpen() {
    if (open) return
    open = true
    writeHeader()
    header?.force()
  }

  fun setCount(n: Int) {
    if (count == n) return
    count = n
    writeHeader()
  }

  fun putMeta(row: Int, segment: Long, file: Long, kind: Int, modality: Int, late: Int) {
    val b = chunks[row / rowsPerChunk]
    val o = (row % rowsPerChunk) * META_ROW
    b.putLong(o, segment); b.putLong(o + 8, file)
    b.put(o + 16, kind.toByte()); b.put(o + 17, modality.toByte()); b.put(o + 18, late.toByte())
    dirty.set(row / rowsPerChunk)
  }

  fun putLate(row: Int, late: Int) {
    chunks[row / rowsPerChunk].put((row % rowsPerChunk) * META_ROW + 18, late.toByte())
    dirty.set(row / rowsPerChunk)
  }

  fun clearMeta(row: Int) = putMeta(row, 0, 0, 0, 0, 0)

  /** Writes [dim] halves of [src] as row [row]'s vector, and its stage-1 block (the first [stageDim] dims, renormalised). */
  fun putVector(row: Int, src: ShortArray) {
    val chunk = row / rowsPerChunk
    val view = writeView(chunk)
    view.position((row % rowsPerChunk) * dim)
    view.put(src, 0, dim)
    if (stageDim > 0) {
      Halves.stageOf(src, stageDim, stageScratch)
      putStage(row, stageScratch)
    }
    dirty.set(chunk)
  }

  private fun putStage(row: Int, stage: ShortArray) {
    val chunk = row / rowsPerChunk
    val view = writeStageViews[chunk] ?: stageViewOf(chunks[chunk]).also { writeStageViews[chunk] = it }
    view.position((row % rowsPerChunk) * stageDim)
    view.put(stage, 0, stageDim)
  }

  /** Moves a row's vector and stage block (the stage block is copied, not recomputed, so it stays what was written). */
  fun copyVector(from: Int, to: Int) {
    val view = writeView(from / rowsPerChunk)
    view.position((from % rowsPerChunk) * dim)
    view.get(scratch, 0, dim)
    val target = writeView(to / rowsPerChunk)
    target.position((to % rowsPerChunk) * dim)
    target.put(scratch, 0, dim)
    if (stageDim > 0) {
      val sv = writeStageViews[from / rowsPerChunk] ?: stageViewOf(chunks[from / rowsPerChunk]).also { writeStageViews[from / rowsPerChunk] = it }
      sv.position((from % rowsPerChunk) * stageDim)
      sv.get(stageScratch, 0, stageDim)
      putStage(to, stageScratch)
    }
    dirty.set(to / rowsPerChunk)
  }

  fun zeroVector(row: Int) = putVector(row, zeros)

  private fun writeView(chunk: Int): ShortBuffer =
    writeViews[chunk] ?: viewOf(chunks[chunk]).also { writeViews[chunk] = it }

  private fun viewOf(chunk: MappedByteBuffer): ShortBuffer {
    val dup = chunk.duplicate()
    dup.order(ByteOrder.LITTLE_ENDIAN)
    dup.position(vectorBase)
    return dup.asShortBuffer()
  }

  private fun stageViewOf(chunk: MappedByteBuffer): ShortBuffer {
    val dup = chunk.duplicate()
    dup.order(ByteOrder.LITTLE_ENDIAN)
    dup.position(stageBase)
    return dup.asShortBuffer()
  }

  // ---- reading ---------------------------------------------------------------------------------------------------

  /** Calls [sink] for every row below [count]: its facts, in row order. Reads each chunk's facts in one bulk copy. */
  fun forEachMeta(rows: Int, sink: (row: Int, segment: Long, file: Long, kind: Int, modality: Int, late: Int) -> Unit) {
    var row = 0
    val bytes = ByteArray(rowsPerChunk * META_ROW)
    for (chunk in chunks) {
      if (row >= rows) break
      val n = minOf(rowsPerChunk, rows - row)
      val dup = chunk.duplicate()
      dup.position(0)
      dup.get(bytes, 0, n * META_ROW)
      val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
      for (i in 0 until n) {
        val o = i * META_ROW
        sink(row + i, b.getLong(o), b.getLong(o + 8), b[o + 16].toInt(), b[o + 17].toInt(), b[o + 18].toInt())
      }
      row += n
    }
  }

  /** A reader for one scoring pass on one thread: it holds its own views of the chunks it touches. */
  fun reader(): Reader = Reader()

  inner class Reader {
    private val views = arrayOfNulls<ShortBuffer>(chunks.size)
    private val stageViews = arrayOfNulls<ShortBuffer>(chunks.size)

    /** Copies up to [maxRows] rows starting at [row] (never across a chunk) into [dst] from 0. Returns the rows copied. */
    fun fetch(row: Int, maxRows: Int, dst: ShortArray): Int {
      val chunk = row / rowsPerChunk
      val at = row % rowsPerChunk
      val n = minOf(maxRows, rowsPerChunk - at, dst.size / dim)
      val view = views[chunk] ?: viewOf(chunks[chunk]).also { views[chunk] = it }
      view.position(at * dim)
      view.get(dst, 0, n * dim)
      return n
    }

    /** Like [fetch], for the stage-1 blocks ([stageDim] halves a row). */
    fun fetchStage(row: Int, maxRows: Int, dst: ShortArray): Int {
      val chunk = row / rowsPerChunk
      val at = row % rowsPerChunk
      val n = minOf(maxRows, rowsPerChunk - at, dst.size / stageDim)
      val view = stageViews[chunk] ?: stageViewOf(chunks[chunk]).also { stageViews[chunk] = it }
      view.position(at * stageDim)
      view.get(dst, 0, n * stageDim)
      return n
    }
  }

  // ---- settling --------------------------------------------------------------------------------------------------

  /**
   * Forces every changed chunk to disk, then marks the file settled at database generation [gen]. The order is the point: a
   * "settled" header on disk means the data it describes got there first. Safe to call under the owner's read lock (changes
   * wait for it; scoring does not).
   */
  fun settle(gen: Long) {
    for (c in 0 until chunks.size) if (dirty.get(c)) chunks[c].force()
    dirty.clear()
    open = false
    generation = gen
    writeHeader()
    header?.force()
  }

  private fun writeHeader() {
    val h = header ?: return
    h.position(0)
    h.put(MAGIC)
    h.putInt(8, VERSION); h.putInt(12, dim); h.putInt(16, rowsPerChunk)
    h.putInt(20, if (open) 1 else 0)
    h.putLong(24, count.toLong()); h.putLong(32, generation)
    h.putInt(40, chunks.size)
    h.putInt(44, stageDim)
    h.putInt(48, crcOf(h))
  }

  // ---- ending ----------------------------------------------------------------------------------------------------

  /** Releases the file. The mappings stay valid until the garbage collector drops them; the data is in the page cache either way. */
  fun close() {
    runCatching { channel.close() }
    runCatching { raf.close() }
    chunks.clear(); writeViews.clear(); writeStageViews.clear(); header = null
  }

  companion object {
    const val HEADER = 4096
    const val META_ROW = 24
    const val ROWS_PER_CHUNK = 4096
    private const val VERSION = 2   // 2: the header names the stage width (byte 44) for the two-stage scan
    /** Version 1 had no stage block and left byte 44 zero: its layout is exactly a version 2 file with `stageDim = 0`, so it is adopted as one. */
    private const val VERSION_NO_STAGE = 1
    private const val ZERO_BLOCK = 1 shl 20
    private val MAGIC = "FNDRVEC1".toByteArray(Charsets.US_ASCII)

    private fun crcOf(h: ByteBuffer): Int {
      val bytes = ByteArray(48)
      val dup = h.duplicate()
      dup.position(0)
      dup.get(bytes)
      return CRC32().also { it.update(bytes) }.value.toInt()
    }

    /** Makes a new, empty, settled file at generation 0, replacing whatever was at [path]. Throws [IOException] if it cannot. */
    fun create(path: File, dim: Int, rowsPerChunk: Int = ROWS_PER_CHUNK, stageDim: Int = 0): VectorFile {
      path.parentFile?.mkdirs()
      path.delete()
      val raf = RandomAccessFile(path, "rw")
      try {
        val f = VectorFile(path, dim, rowsPerChunk, stageDim, raf)
        raf.setLength(0)
        val zero = ByteBuffer.allocate(HEADER)
        while (zero.hasRemaining()) f.channel.write(zero, zero.position().toLong())
        f.header = f.channel.map(FileChannel.MapMode.READ_WRITE, 0, HEADER.toLong()).also { it.order(ByteOrder.LITTLE_ENDIAN) }
        f.writeHeader()
        return f
      } catch (e: IOException) {
        runCatching { raf.close() }
        path.delete()
        throw e
      }
    }

    /**
     * Opens an existing file if it can be trusted as a cache of the database: right magic, version, vector size and chunk
     * size, a good header CRC, settled (not open for change), and exactly the length of the chunks it names. Returns null
     * otherwise (the caller rebuilds). The caller still compares [generation] with the database's.
     */
    fun openExisting(path: File, dim: Int, rowsPerChunk: Int = ROWS_PER_CHUNK, stageDim: Int = 0): VectorFile? {
      if (!path.isFile || path.length() < HEADER) return null
      val raf = try { RandomAccessFile(path, "rw") } catch (e: IOException) { return null }
      try {
        val f = VectorFile(path, dim, rowsPerChunk, stageDim, raf)
        val h = f.channel.map(FileChannel.MapMode.READ_WRITE, 0, HEADER.toLong())
        h.order(ByteOrder.LITTLE_ENDIAN)
        val magic = ByteArray(8)
        h.position(0); h.get(magic)
        if (!magic.contentEquals(MAGIC)) return null.also { f.close() }
        if ((h.getInt(8) != VERSION && h.getInt(8) != VERSION_NO_STAGE) || h.getInt(12) != dim || h.getInt(16) != rowsPerChunk) return null.also { f.close() }
        if (h.getInt(44) != stageDim) return null.also { f.close() }
        if (h.getInt(48) != crcOf(h)) return null.also { f.close() }
        if (h.getInt(20) != 0) return null.also { f.close() }   // open for change: the last session did not settle
        val count = h.getLong(24)
        val chunkCount = h.getInt(40)
        if (chunkCount < 0 || count < 0 || count > chunkCount.toLong() * rowsPerChunk) return null.also { f.close() }
        if (raf.length() != HEADER + chunkCount * f.chunkBytes) return null.also { f.close() }
        f.header = h
        for (c in 0 until chunkCount) {
          f.chunks += f.channel.map(FileChannel.MapMode.READ_WRITE, HEADER + c * f.chunkBytes, f.chunkBytes)
            .also { it.order(ByteOrder.LITTLE_ENDIAN) }
          f.writeViews += null
          f.writeStageViews += null
        }
        f.count = count.toInt()
        f.generation = h.getLong(32)
        return f
      } catch (e: IOException) {
        runCatching { raf.close() }
        return null
      } catch (e: RuntimeException) {   // a mapping the system refused: treat like a bad file
        runCatching { raf.close() }
        return null
      }
    }

    /**
     * Overwrites [path] with zeros and flushes, so deleted vectors do not linger in the file (the mappings of a live
     * [VectorFile] see the zeros too: same page cache). Returns the bytes overwritten. The caller deletes the file.
     */
    fun shred(path: File): Long {
      if (!path.isFile) return 0
      val length = path.length()
      RandomAccessFile(path, "rw").use { raf ->
        val zero = ByteBuffer.allocate(ZERO_BLOCK)
        var at = 0L
        while (at < length) {
          zero.clear()
          zero.limit(minOf(ZERO_BLOCK.toLong(), length - at).toInt())
          while (zero.hasRemaining()) at += raf.channel.write(zero, at)
        }
        raf.channel.force(true)
      }
      return length
    }
  }
}

/** fp16 helpers shared by the index and its file. */
object Halves {
  /** Every fp16 bit pattern as a float: the JVM and ART have no half-float intrinsics, and a 256 KB table beat bit-twiddling. */
  val TO_FLOAT = FloatArray(65536) { com.craneai.findr.index.Fp16.toFloat(it.toShort()) }

  /**
   * The stage-1 vector of [full]: its first [stageDim] dims (EmbeddingGemma is trained Matryoshka-style, so the leading dims are
   * a smaller embedding), scaled back to unit length, as fp16 into [out]. A zero vector stays zero.
   */
  fun stageOf(full: ShortArray, stageDim: Int, out: ShortArray) {
    var sum = 0.0
    for (i in 0 until stageDim) { val x = TO_FLOAT[full[i].toInt() and 0xFFFF]; sum += x.toDouble() * x }
    val inv = if (sum > 0.0) (1.0 / Math.sqrt(sum)).toFloat() else 0f
    for (i in 0 until stageDim) out[i] = com.craneai.findr.index.Fp16.toHalf(TO_FLOAT[full[i].toInt() and 0xFFFF] * inv)
  }
}
