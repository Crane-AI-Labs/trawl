package com.craneai.findr.search

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.MappedByteBuffer
import java.nio.ShortBuffer
import java.nio.channels.FileChannel
import java.util.BitSet
import java.util.zip.CRC32

/** On-disk (and heap) storage format for [VectorFile] and [VectorIndex]. */
enum class VectorFormat {
  /** Version 2 (and legacy version 1): IEEE fp16 vectors (2 B/dim). Exact fp16 scoring. */
  FP16,

  /** Version 3: symmetric per-vector SQ8 (4 B float32 scale + 1 B int8/dim). ~50–62% smaller and NEON-accelerated. */
  SQ8,
}

/**
 * The vector index's own store: a file in the app's private storage, memory-mapped, so a 100k-vector index costs the Java
 * heap nothing (150 MB of fp16 would not fit a 192 MB heap, and even a few hundred MB heaps are a ceiling). The kernel
 * pages it in and out; the pages are clean file-backed memory it can drop under pressure, where a heap array is memory only
 * the low-memory killer can reclaim. A cold start maps the file instead of reading every vector out of SQLite.
 *
 * Layout (all little endian, the byte order the vectors have in the database, so a vector's bytes are the same everywhere):
 *  - one 4 KB header: magic, version, vector size, rows per chunk, whether the file is [settled][settle] or open for change,
 *    the row count, the database generation it settled at, chunk count, stage width, and a CRC of those fields;
 *  - Version 1 / Version 2 ([VectorFormat.FP16]): chunks of [rowsPerChunk] rows, each with 24 B/row metadata, then fp16 full
 *    vectors (`dim * 2` B/row), then optional fp16 stage-1 blocks (`stageDim * 2` B/row);
 *  - Version 3 ([VectorFormat.SQ8]): chunks of [rowsPerChunk] rows, each with 24 B/row metadata, then contiguous float32
 *    full scales (`4` B/row), then signed int8 full vectors (`dim` B/row), then (when `stageDim > 0`) 4-byte aligned float32
 *    stage-1 scales (`4` B/row) and signed int8 stage-1 vectors (`stageDim` B/row).
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
  /** Width of the stage-1 block of the two-stage scan (0: none). See [Halves.stageOf] / [Sq8]. */
  val stageDim: Int,
  val format: VectorFormat,
  private val raf: RandomAccessFile,
) {
  private val channel: FileChannel = raf.channel
  private val chunks = ArrayList<MappedByteBuffer>()
  private val writeViews = ArrayList<ShortBuffer?>()
  private val writeStageViews = ArrayList<ShortBuffer?>()
  private val writeByteViews = ArrayList<ByteBuffer?>()
  private val writeStageByteViews = ArrayList<ByteBuffer?>()
  private val dirty = BitSet()
  private var header: MappedByteBuffer? = null

  private val version: Int = if (format == VectorFormat.SQ8) VERSION_SQ8 else VERSION_FP16
  internal val fullScaleBase: Int = if (format == VectorFormat.SQ8) rowsPerChunk * META_ROW else 0
  internal val vectorBase: Int =
    if (format == VectorFormat.SQ8) fullScaleBase + rowsPerChunk * 4 else rowsPerChunk * META_ROW
  internal val stageScaleBase: Int =
    if (format == VectorFormat.SQ8 && stageDim > 0) align4(vectorBase + rowsPerChunk * dim) else 0
  internal val stageBase: Int =
    if (format == VectorFormat.SQ8) {
      if (stageDim > 0) stageScaleBase + rowsPerChunk * 4 else 0
    } else {
      vectorBase + rowsPerChunk * dim * 2
    }
  private val chunkBytes: Long = chunkBytesFor(dim, rowsPerChunk, stageDim, format)

  private val scratch = if (format == VectorFormat.FP16) ShortArray(dim) else ShortArray(0)
  private val zeros = if (format == VectorFormat.FP16) ShortArray(dim) else ShortArray(0)
  private val stageScratch = if (format == VectorFormat.FP16) ShortArray(stageDim) else ShortArray(0)
  private val q8Scratch = if (format == VectorFormat.SQ8) ByteArray(dim) else ByteArray(0)
  private val q8Zeros = if (format == VectorFormat.SQ8) ByteArray(dim) else ByteArray(0)
  private val q8StageScratch = if (format == VectorFormat.SQ8) ByteArray(stageDim) else ByteArray(0)
  private val q8StageZeros = if (format == VectorFormat.SQ8) ByteArray(stageDim) else ByteArray(0)

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

  /** Direct mapped chunk buffer for zero-copy native scanning (caller holds the index read lock). */
  internal fun chunkBuffer(chunk: Int): MappedByteBuffer = chunks[chunk]

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
      writeByteViews += null
      writeStageByteViews += null
    } catch (e: IOException) {
      runCatching { raf.setLength(start) }
      throw e
    }
  }

  /**
   * Cuts the file back to the chunks [rows] rows need (at least none), and returns the bytes given back. The rows are dense (a removed row is
   * filled from the last one and the last is zeroed), so every chunk past the rows is zeros and nothing moves. The file is truncated first: where the system
   * will not truncate a mapped file (Windows) that throws before anything here has changed, and 0 is returned. The caller holds its write lock and
   * has marked the file open, so a crash in between leaves a file the next start rebuilds.
   */
  fun shrinkTo(rows: Int): Long {
    val keep = (rows + rowsPerChunk - 1) / rowsPerChunk
    if (keep >= chunks.size) return 0L
    val before = fileBytes
    try { raf.setLength(HEADER + keep * chunkBytes) } catch (e: IOException) { return 0L }
    while (chunks.size > keep) {
      val last = chunks.size - 1
      chunks.removeAt(last)
      writeViews.removeAt(last)
      writeStageViews.removeAt(last)
      writeByteViews.removeAt(last)
      writeStageByteViews.removeAt(last)
    }
    dirty.clear(keep, dirty.length().coerceAtLeast(keep))
    writeHeader()
    return before - fileBytes
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

  /** Writes [dim] fp16 halves of [src] as row [row]'s vector (quantizing to SQ8 in v3) and its stage-1 block. */
  fun putVector(row: Int, src: ShortArray) {
    val chunk = row / rowsPerChunk
    val at = row % rowsPerChunk
    if (format == VectorFormat.FP16) {
      val view = writeView(chunk)
      view.position(at * dim)
      view.put(src, 0, dim)
      if (stageDim > 0) {
        Halves.stageOf(src, stageDim, stageScratch)
        putStage(row, stageScratch)
      }
    } else {
      val scale = Sq8.quantizeHalves(src, dim, q8Scratch)
      putSq8Full(chunk, at, scale, q8Scratch)
      if (stageDim > 0) {
        val stageScale = Sq8.quantizeStageFromHalves(src, stageDim, q8StageScratch)
        putSq8Stage(chunk, at, stageScale, q8StageScratch)
      }
    }
    dirty.set(chunk)
  }

  /** Writes [dim] floats of [src] as row [row]'s vector (avoiding intermediate fp16 rounding in SQ8 v3) and its stage-1 block. */
  fun putFloatVector(row: Int, src: FloatArray) {
    if (format == VectorFormat.FP16) {
      for (i in 0 until dim) scratch[i] = com.craneai.findr.index.Fp16.toHalf(src[i])
      putVector(row, scratch)
      return
    }
    val chunk = row / rowsPerChunk
    val at = row % rowsPerChunk
    val scale = Sq8.quantizeFloats(src, dim, q8Scratch)
    putSq8Full(chunk, at, scale, q8Scratch)
    if (stageDim > 0) {
      val stageScale = Sq8.quantizeStageFromFloats(src, stageDim, q8StageScratch)
      putSq8Stage(chunk, at, stageScale, q8StageScratch)
    }
    dirty.set(chunk)
  }

  private fun putSq8Full(chunk: Int, at: Int, scale: Float, vec: ByteArray) {
    chunks[chunk].putFloat(fullScaleBase + at * 4, scale)
    val view = writeByteView(chunk)
    view.position(at * dim)
    view.put(vec, 0, dim)
  }

  private fun putSq8Stage(chunk: Int, at: Int, scale: Float, vec: ByteArray) {
    chunks[chunk].putFloat(stageScaleBase + at * 4, scale)
    val view = writeStageByteView(chunk)
    view.position(at * stageDim)
    view.put(vec, 0, stageDim)
  }

  private fun putStage(row: Int, stage: ShortArray) {
    val chunk = row / rowsPerChunk
    val view = writeStageViews[chunk] ?: stageViewOf(chunks[chunk]).also { writeStageViews[chunk] = it }
    view.position((row % rowsPerChunk) * stageDim)
    view.put(stage, 0, stageDim)
  }

  /** Moves a row's vector and stage block (copied raw, not recomputed, so it stays what was written). */
  fun copyVector(from: Int, to: Int) {
    val srcChunk = from / rowsPerChunk
    val srcAt = from % rowsPerChunk
    val dstChunk = to / rowsPerChunk
    val dstAt = to % rowsPerChunk
    if (format == VectorFormat.FP16) {
      val view = writeView(srcChunk)
      view.position(srcAt * dim)
      view.get(scratch, 0, dim)
      val target = writeView(dstChunk)
      target.position(dstAt * dim)
      target.put(scratch, 0, dim)
      if (stageDim > 0) {
        val sv = writeStageViews[srcChunk] ?: stageViewOf(chunks[srcChunk]).also { writeStageViews[srcChunk] = it }
        sv.position(srcAt * stageDim)
        sv.get(stageScratch, 0, stageDim)
        putStage(to, stageScratch)
      }
    } else {
      val scale = chunks[srcChunk].getFloat(fullScaleBase + srcAt * 4)
      val view = writeByteView(srcChunk)
      view.position(srcAt * dim)
      view.get(q8Scratch, 0, dim)
      putSq8Full(dstChunk, dstAt, scale, q8Scratch)
      if (stageDim > 0) {
        val stScale = chunks[srcChunk].getFloat(stageScaleBase + srcAt * 4)
        val sv = writeStageByteView(srcChunk)
        sv.position(srcAt * stageDim)
        sv.get(q8StageScratch, 0, stageDim)
        putSq8Stage(dstChunk, dstAt, stScale, q8StageScratch)
      }
    }
    dirty.set(dstChunk)
  }

  fun zeroVector(row: Int) {
    if (format == VectorFormat.FP16) {
      putVector(row, zeros)
    } else {
      val chunk = row / rowsPerChunk
      val at = row % rowsPerChunk
      putSq8Full(chunk, at, 0f, q8Zeros)
      if (stageDim > 0) putSq8Stage(chunk, at, 0f, q8StageZeros)
      dirty.set(chunk)
    }
  }

  private fun writeView(chunk: Int): ShortBuffer =
    writeViews[chunk] ?: viewOf(chunks[chunk]).also { writeViews[chunk] = it }

  private fun writeByteView(chunk: Int): ByteBuffer =
    writeByteViews[chunk] ?: byteViewOf(chunks[chunk], vectorBase).also { writeByteViews[chunk] = it }

  private fun writeStageByteView(chunk: Int): ByteBuffer =
    writeStageByteViews[chunk] ?: byteViewOf(chunks[chunk], stageBase).also { writeStageByteViews[chunk] = it }

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

  private fun byteViewOf(chunk: MappedByteBuffer, base: Int): ByteBuffer {
    val dup = chunk.duplicate()
    dup.position(base)
    return dup.slice()
  }

  private fun floatViewOf(chunk: MappedByteBuffer, base: Int): FloatBuffer {
    val dup = chunk.duplicate()
    dup.order(ByteOrder.LITTLE_ENDIAN)
    dup.position(base)
    return dup.asFloatBuffer()
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
    private val byteViews = arrayOfNulls<ByteBuffer>(chunks.size)
    private val scaleViews = arrayOfNulls<FloatBuffer>(chunks.size)
    private val stageByteViews = arrayOfNulls<ByteBuffer>(chunks.size)
    private val stageScaleViews = arrayOfNulls<FloatBuffer>(chunks.size)

    /** Copies up to [maxRows] fp16 rows starting at [row] (never across a chunk) into [dst] from 0. Returns the rows copied. */
    fun fetch(row: Int, maxRows: Int, dst: ShortArray): Int {
      val chunk = row / rowsPerChunk
      val at = row % rowsPerChunk
      val n = minOf(maxRows, rowsPerChunk - at, dst.size / dim)
      val view = views[chunk] ?: viewOf(chunks[chunk]).also { views[chunk] = it }
      view.position(at * dim)
      view.get(dst, 0, n * dim)
      return n
    }

    /** Like [fetch], for fp16 stage-1 blocks ([stageDim] halves a row). */
    fun fetchStage(row: Int, maxRows: Int, dst: ShortArray): Int {
      val chunk = row / rowsPerChunk
      val at = row % rowsPerChunk
      val n = minOf(maxRows, rowsPerChunk - at, dst.size / stageDim)
      val view = stageViews[chunk] ?: stageViewOf(chunks[chunk]).also { stageViews[chunk] = it }
      view.position(at * stageDim)
      view.get(dst, 0, n * stageDim)
      return n
    }

    /** Copies up to [maxRows] SQ8 full rows starting at [row] (never across a chunk) into [dstVecs] and [dstScales]. */
    fun fetchSq8(row: Int, maxRows: Int, dstVecs: ByteArray, dstScales: FloatArray): Int {
      val chunk = row / rowsPerChunk
      val at = row % rowsPerChunk
      val n = minOf(maxRows, rowsPerChunk - at, dstScales.size, dstVecs.size / dim)
      val sv = scaleViews[chunk] ?: floatViewOf(chunks[chunk], fullScaleBase).also { scaleViews[chunk] = it }
      sv.position(at)
      sv.get(dstScales, 0, n)
      val bv = byteViews[chunk] ?: byteViewOf(chunks[chunk], vectorBase).also { byteViews[chunk] = it }
      bv.position(at * dim)
      bv.get(dstVecs, 0, n * dim)
      return n
    }

    /** Like [fetchSq8], for SQ8 stage-1 blocks ([stageDim] bytes and 1 float scale per row). */
    fun fetchSq8Stage(row: Int, maxRows: Int, dstVecs: ByteArray, dstScales: FloatArray): Int {
      val chunk = row / rowsPerChunk
      val at = row % rowsPerChunk
      val n = minOf(maxRows, rowsPerChunk - at, dstScales.size, dstVecs.size / stageDim)
      val sv = stageScaleViews[chunk] ?: floatViewOf(chunks[chunk], stageScaleBase).also { stageScaleViews[chunk] = it }
      sv.position(at)
      sv.get(dstScales, 0, n)
      val bv = stageByteViews[chunk] ?: byteViewOf(chunks[chunk], stageBase).also { stageByteViews[chunk] = it }
      bv.position(at * stageDim)
      bv.get(dstVecs, 0, n * stageDim)
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
    h.putInt(8, version); h.putInt(12, dim); h.putInt(16, rowsPerChunk)
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
    chunks.clear()
    writeViews.clear(); writeStageViews.clear()
    writeByteViews.clear(); writeStageByteViews.clear()
    header = null
  }

  companion object {
    const val HEADER = 4096
    const val META_ROW = 24
    const val ROWS_PER_CHUNK = 4096
    /** Version 1 had no stage block and left byte 44 zero: its layout is exactly a version 2 fp16 file with `stageDim = 0`. */
    const val VERSION_NO_STAGE = 1
    /** Version 2: fp16 full vectors + optional fp16 stage-1 block (width at byte 44). */
    const val VERSION_FP16 = 2
    /** Version 3: SQ8 (float32 scale + int8) full vectors + optional SQ8 stage-1 block (width at byte 44). */
    const val VERSION_SQ8 = 3
    private const val ZERO_BLOCK = 1 shl 20
    private val MAGIC = "FNDRVEC1".toByteArray(Charsets.US_ASCII)

    private fun align4(offset: Int): Int = (offset + 3) and 3.inv()

    /** Exact size in bytes of one chunk for the given dimensions and [format]. */
    fun chunkBytesFor(dim: Int, rowsPerChunk: Int, stageDim: Int, format: VectorFormat): Long =
      if (format == VectorFormat.FP16) {
        rowsPerChunk.toLong() * (META_ROW + (dim + stageDim) * 2L)
      } else {
        val fullEnd = rowsPerChunk.toLong() * (META_ROW + 4L + dim)
        if (stageDim > 0) {
          val stageScaleStart = (fullEnd + 3L) and 3L.inv()
          stageScaleStart + rowsPerChunk.toLong() * (4L + stageDim)
        } else {
          fullEnd
        }
      }

    private fun crcOf(h: ByteBuffer): Int {
      val bytes = ByteArray(48)
      val dup = h.duplicate()
      dup.position(0)
      dup.get(bytes)
      return CRC32().also { it.update(bytes) }.value.toInt()
    }

    /** Makes a new, empty, settled file at generation 0, replacing whatever was at [path]. Throws [IOException] if it cannot. */
    fun create(
      path: File,
      dim: Int,
      rowsPerChunk: Int = ROWS_PER_CHUNK,
      stageDim: Int = 0,
      format: VectorFormat = VectorFormat.FP16,
    ): VectorFile {
      path.parentFile?.mkdirs()
      path.delete()
      val raf = RandomAccessFile(path, "rw")
      try {
        val f = VectorFile(path, dim, rowsPerChunk, stageDim, format, raf)
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
     * otherwise (the caller rebuilds). When [format] is null, accepts v1, v2 ([VectorFormat.FP16]), or v3 ([VectorFormat.SQ8])
     * seamlessly; when non-null, requires the on-disk format to match so version upgrades rebuild cleanly from SQLite.
     */
    fun openExisting(
      path: File,
      dim: Int,
      rowsPerChunk: Int = ROWS_PER_CHUNK,
      stageDim: Int = 0,
      format: VectorFormat? = null,
    ): VectorFile? {
      if (!path.isFile || path.length() < HEADER) return null
      val raf = try { RandomAccessFile(path, "rw") } catch (e: IOException) { return null }
      try {
        // Read & validate the 4 KB header via regular I/O BEFORE creating any MappedByteBuffer: on Windows, mapping an
        // incompatible/unsettled file locks it against immediate delete/truncate during v2->v3 rebuilds.
        val rawHeader = ByteArray(HEADER)
        raf.seek(0)
        raf.readFully(rawHeader)
        val h = ByteBuffer.wrap(rawHeader).order(ByteOrder.LITTLE_ENDIAN)
        val magic = ByteArray(8)
        h.position(0); h.get(magic)
        if (!magic.contentEquals(MAGIC)) return null.also { runCatching { raf.close() } }
        val ver = h.getInt(8)
        val fileFormat = when (ver) {
          VERSION_NO_STAGE, VERSION_FP16 -> VectorFormat.FP16
          VERSION_SQ8 -> VectorFormat.SQ8
          else -> return null.also { runCatching { raf.close() } }
        }
        if (format != null && fileFormat != format) return null.also { runCatching { raf.close() } }
        if (h.getInt(12) != dim || h.getInt(16) != rowsPerChunk) return null.also { runCatching { raf.close() } }
        if (h.getInt(44) != stageDim) return null.also { runCatching { raf.close() } }
        if (h.getInt(48) != crcOf(h)) return null.also { runCatching { raf.close() } }
        if (h.getInt(20) != 0) return null.also { runCatching { raf.close() } }   // open for change: the last session did not settle
        val count = h.getLong(24)
        val chunkCount = h.getInt(40)
        val f = VectorFile(path, dim, rowsPerChunk, stageDim, fileFormat, raf)
        if (chunkCount < 0 || count < 0 || count > chunkCount.toLong() * rowsPerChunk) return null.also { f.close() }
        if (raf.length() != HEADER + chunkCount * f.chunkBytes) return null.also { f.close() }
        f.header = f.channel.map(FileChannel.MapMode.READ_WRITE, 0, HEADER.toLong()).also { it.order(ByteOrder.LITTLE_ENDIAN) }
        for (c in 0 until chunkCount) {
          f.chunks += f.channel.map(FileChannel.MapMode.READ_WRITE, HEADER + c * f.chunkBytes, f.chunkBytes)
            .also { it.order(ByteOrder.LITTLE_ENDIAN) }
          f.writeViews += null
          f.writeStageViews += null
          f.writeByteViews += null
          f.writeStageByteViews += null
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
  /** Every fp16 bit pattern as a float: lazy so Trawl Go (0 vectors) never allocates the 256 KB table. */
  val TO_FLOAT: FloatArray by lazy { FloatArray(65536) { com.craneai.findr.index.Fp16.toFloat(it.toShort()) } }

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
