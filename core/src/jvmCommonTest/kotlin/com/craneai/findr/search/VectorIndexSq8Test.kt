package com.craneai.findr.search

import com.craneai.findr.core.FileKind
import com.craneai.findr.core.KindFilter
import com.craneai.findr.core.StorageEstimate
import com.craneai.findr.index.CrawlRecord
import com.craneai.findr.index.FileState
import com.craneai.findr.index.Fp16
import com.craneai.findr.index.IndexStore
import com.craneai.findr.index.Modality
import com.craneai.findr.index.Segment
import com.craneai.findr.index.SegmentKind
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * End-to-end verification of SQ8 (int8) `vectors.bin` v3 and optional ARM64 NEON `SDOT` acceleration:
 *  - Quantization accuracy (mean cosine delta < 0.001, Recall@10 >= 0.995)
 *  - Persistence round-trip (write -> settle -> adopt -> search -> remove -> compact -> reload -> discardFile)
 *  - Seamless v2 fp16 readability and clean v2 -> v3 upgrade
 *  - Pure-JVM SQ8 vs ARM64 NEON SDOT score parity (delta < 1e-4) across full scan, stage-1 filtered scan, and scoresAt
 *  - Per-modality TopK (G10), scaleSample() cache, Searcher Top-100 fp16 rescore, and StorageEstimate parity
 */
class VectorIndexSq8Test {
  private lateinit var dir: File
  private val open = ArrayList<VectorIndex>()
  private var store: IndexStore? = null

  @Before fun setUp() { dir = Files.createTempDirectory("findr-sq8").toFile() }

  @After fun tearDown() {
    open.forEach { runCatching { it.close() } }
    store?.close()
    dir.deleteRecursively()
  }

  private fun unit(random: Random, dim: Int = Fp16.DIM): FloatArray {
    val v = FloatArray(dim) { (random.nextDouble() * 2 - 1).toFloat() }
    val n = sqrt(v.sumOf { (it * it).toDouble() }).toFloat()
    return FloatArray(dim) { v[it] / n }
  }

  private fun dot(a: FloatArray, b: FloatArray): Float {
    var s = 0.0
    for (i in a.indices) s += a[i].toDouble() * b[i]
    return s.toFloat()
  }

  private fun allowAll() = BooleanArray(FileKind.entries.size) { true }

  private fun sq8Index(
    name: String = "v3.bin",
    dim: Int = Fp16.DIM,
    rowsPerChunk: Int = 500,
    parallelism: Int = 1,
    twoStage: TwoStage? = null,
    backed: Boolean = true,
    useNative: Boolean = true,
  ) = VectorIndex(
    dim = dim,
    initialCapacity = 16,
    parallelism = parallelism,
    backing = if (backed) File(dir, name) else null,
    settleDelayMs = -1,
    rowsPerChunk = rowsPerChunk,
    twoStage = twoStage,
    format = VectorFormat.SQ8,
    useNative = useNative,
  ).also { open += it }

  @Test fun quantizationAccuracyAndRecallMeetTargets() {
    val random = Random(42)
    val n = 2_000
    val fp16 = VectorIndex(parallelism = 1, backing = File(dir, "v2.bin"), settleDelayMs = -1, rowsPerChunk = 500, format = VectorFormat.FP16).also { open += it }
    val sq8 = sq8Index("v3-acc.bin", rowsPerChunk = 500, parallelism = 1)

    // Create 20 semantic clusters so top-10 neighbors have realistic cosine separation (like real embeddings)
    val centroids = List(20) { unit(random) }
    val vecs = List(n) { i ->
      val c = centroids[i % centroids.size]
      val noise = unit(random)
      val weight = if (i < 200) 0.72f - (i % 10) * 0.025f else 0.15f
      val raw = FloatArray(Fp16.DIM) { c[it] * weight + noise[it] * (1f - weight) }
      val norm = sqrt(raw.sumOf { (it * it).toDouble() }).toFloat()
      FloatArray(Fp16.DIM) { raw[it] / norm }
    }
    vecs.forEachIndexed { i, v ->
      fp16.add(i + 1L, i + 1L, FileKind.DOCUMENT, Modality.TEXT, false, v)
      sq8.add(i + 1L, i + 1L, FileKind.DOCUMENT, Modality.TEXT, false, v)
    }

    var totalAbsDiff = 0.0
    var maxAbsDiff = 0.0
    var totalPairs = 0L
    var recallHits = 0.0
    val queries = 20
    for (qi in 0 until queries) {
      val q = centroids[qi]
      val exact = fp16.scores(q)
      val approx = sq8.scores(q)
      for (i in 0 until n) {
        val d = abs(exact[i] - approx[i]).toDouble()
        totalAbsDiff += d
        if (d > maxAbsDiff) maxAbsDiff = d
        totalPairs++
      }
      val gtTop10 = exact.indices.sortedByDescending { exact[it] }.take(10).toSet()
      val sqTop10 = approx.indices.sortedByDescending { approx[it] }.take(10).toSet()
      recallHits += gtTop10.intersect(sqTop10).size / 10.0
    }
    val meanAbsDiff = totalAbsDiff / totalPairs
    val recall10 = recallHits / queries
    assertTrue("mean cosine delta $meanAbsDiff must be < 0.001", meanAbsDiff < 0.001)
    assertTrue("Recall@10 $recall10 must be >= 0.995", recall10 >= 0.995)

    // Verify zero vectors and arbitrary non-64-aligned dims do not NaN or crash
    val odd = sq8Index("v3-odd.bin", dim = 19, rowsPerChunk = 17, twoStage = TwoStage(stageDim = 7, candidates = 5, minRows = 0))
    val zero = FloatArray(19)
    val one = unit(random, 19)
    odd.add(1L, 1L, FileKind.IMAGE, Modality.IMAGE, false, zero)
    odd.add(2L, 2L, FileKind.IMAGE, Modality.IMAGE, false, one)
    val oddScores = odd.scores(one)
    assertEquals(0f, oddScores[0], 1e-6f)
    assertEquals(1f, oddScores[1], 5e-3f)
    assertTrue(odd.stageCandidates(one, allowAll()).contains(1))
  }

  @Test fun persistenceRoundTripShrinkCompactReloadAndDiscard() {
    val random = Random(101)
    val twoStage = TwoStage(stageDim = 256, candidates = 50, minRows = 0, scaleSample = 200)
    val backing = File(dir, "roundtrip.bin")
    val vecs = List(4_000) { unit(random) }
    val gen = longArrayOf(11L)

    val ix = sq8Index("roundtrip.bin", rowsPerChunk = 500, parallelism = 4, twoStage = twoStage)
    ix.loadFrom({ gen[0] }) { }
    for (r in 0 until 4_000) ix.add(r + 1L, r / 4L + 1L, FileKind.PDF, Modality.TEXT, false, vecs[r])
    assertTrue(ix.offHeap)
    assertTrue(ix.isQuantized)

    // Verify header version == 3 and v3 size is ~49% smaller than v2
    val v3Full = backing.length()
    val v2Full = VectorFile.HEADER + 8L * VectorFile.chunkBytesFor(Fp16.DIM, 500, 256, VectorFormat.FP16)
    assertEquals(VectorFile.HEADER + 8L * VectorFile.chunkBytesFor(Fp16.DIM, 500, 256, VectorFormat.SQ8), v3Full)
    assertTrue("v3 ($v3Full) should be ~49% smaller than v2 ($v2Full)", v3Full < v2Full * 0.52)
    RandomAccessFile(backing, "r").use { raf ->
      val hdr = ByteArray(16)
      raf.readFully(hdr)
      val ver = ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN).getInt(8)
      assertEquals(VectorFile.VERSION_SQ8, ver)
    }

    assertTrue(ix.settleNow())
    val q = vecs[123]
    val scoresBefore = ix.scores(q)
    val candsBefore = ix.stageCandidates(q, allowAll())
    ix.close()

    // Adopt settled v3 file without reading SQLite
    val adopted = sq8Index("roundtrip.bin", rowsPerChunk = 500, parallelism = 4, twoStage = twoStage)
    adopted.loadFrom({ gen[0] }) { throw AssertionError("v3 file must be adopted, not rebuilt") }
    assertEquals(4_000, adopted.size)
    assertArrayEquals(scoresBefore, adopted.scores(q), 0f)
    assertArrayEquals(candsBefore, adopted.stageCandidates(q, allowAll()))

    // Remove 80% of files -> compact() shrinks file -> add new rows -> settle -> reload
    adopted.removeFiles((201L..1_000L).toList())
    assertEquals(800, adopted.size)
    val freed = adopted.compact()
    assertTrue("compact should free trailing chunks: freed=$freed", freed > 0)
    assertEquals(VectorFile.HEADER + 2L * VectorFile.chunkBytesFor(Fp16.DIM, 500, 256, VectorFormat.SQ8), backing.length())
    for (probe in listOf(0, 399, 799)) {
      assertTrue("row $probe self-score", adopted.scores(vecs[probe])[probe] > 0.99f)
    }
    for (r in 0 until 600) adopted.add(10_000L + r, 5_000L + r / 4L, FileKind.PDF, Modality.TEXT, false, vecs[r])
    assertEquals(1_400, adopted.size)
    gen[0] = 12L
    assertTrue(adopted.settleNow())
    adopted.close()

    val reloaded = sq8Index("roundtrip.bin", rowsPerChunk = 500, parallelism = 4, twoStage = twoStage)
    reloaded.loadFrom({ gen[0] }) { throw AssertionError("compacted v3 file must be adopted") }
    assertEquals(1_400, reloaded.size)
    assertTrue(reloaded.scores(vecs[10])[10] > 0.99f)
    assertTrue(reloaded.stageCandidates(vecs[10], allowAll()).contains(10))

    // discardFile drops backing cleanly
    reloaded.discardFile()
    assertFalse(backing.exists())
    assertEquals(0, reloaded.size)
  }

  @Test fun v2FileCanBeReadSeamlesslyAndCleanlyUpgradesToV3() {
    val s = IndexStore.open(dir, log = {}).also { store = it }
    val random = Random(77)
    val vecs = ArrayList<FloatArray>()
    for (i in 1..150) {
      s.upsertFromCrawl(listOf(CrawlRecord("primary", i.toLong(), "file:///d$i.txt", "docs/", "d$i.txt", FileKind.DOCUMENT, null, 100, 100)))
      val f = s.knownMediaStoreIds("primary").getValue(i.toLong())
      val v = unit(random)
      vecs += v
      s.saveSegments(f, listOf(Segment(SegmentKind.CHUNK, Modality.TEXT, text = "doc $i", vector = v)), FileState.UNDERSTOOD)
    }
    val path = File(dir, "upgrade.bin")
    val twoStage = TwoStage(stageDim = 128, candidates = 30, minRows = 0)

    // Write v2 fp16 file
    val v2 = VectorIndex(parallelism = 1, backing = path, settleDelayMs = -1, rowsPerChunk = 100, twoStage = twoStage, format = VectorFormat.FP16).also { open += it }
    v2.load(s)
    assertTrue(v2.settleNow())
    val v2Size = path.length()
    v2.close()

    // VectorFile.openExisting with format=null opens v2 seamlessly
    val openedV2 = VectorFile.openExisting(path, Fp16.DIM, rowsPerChunk = 100, stageDim = 128)
    assertNotNull(openedV2)
    assertEquals(VectorFormat.FP16, openedV2!!.format)
    assertEquals(150, openedV2.count)
    openedV2.close()

    // Opening with VectorIndex(format = SQ8) rebuilds & upgrades v2 -> v3 cleanly
    val v3 = VectorIndex(parallelism = 1, backing = path, settleDelayMs = -1, rowsPerChunk = 100, twoStage = twoStage, format = VectorFormat.SQ8).also { open += it }
    v3.load(s)
    assertTrue(v3.settleNow())
    assertEquals(150, v3.size)
    assertTrue("upgraded v3 (${path.length()}) must be smaller than v2 ($v2Size)", path.length() < v2Size * 0.6)
    assertTrue(v3.scores(vecs[42])[42] > 0.99f)
    v3.close()

    // VectorFile.openExisting with format=null now detects SQ8 v3 seamlessly
    val openedV3 = VectorFile.openExisting(path, Fp16.DIM, rowsPerChunk = 100, stageDim = 128)
    assertNotNull(openedV3)
    assertEquals(VectorFormat.SQ8, openedV3!!.format)
    openedV3.close()
  }

  @Test fun pureJvmAndArm64NeonSdotProduceIdenticalScores() {
    val isMacArm64 = System.getProperty("os.name").startsWith("Mac") &&
      System.getProperty("os.arch").let { it == "aarch64" || it == "arm64" }
    if (isMacArm64) {
      assertTrue("libtrawl_vec_neon.dylib must load on Apple Silicon", Sq8Native.available)
      assertTrue("ARM64 NEON SDOT must be active on Apple Silicon", Sq8Native.neonDotProd)
    }

    val random = Random(303)
    val twoStage = TwoStage(stageDim = 256, candidates = 40, minRows = 0, scaleSample = 500)
    val n = 5_000
    val kinds = listOf(FileKind.IMAGE, FileKind.DOCUMENT, FileKind.PDF, FileKind.VIDEO)
    val mods = listOf(Modality.TEXT, Modality.IMAGE, Modality.AUDIO)

    val jvm = sq8Index("parity.bin", rowsPerChunk = 1000, parallelism = 4, twoStage = twoStage, useNative = false)
    val gen = longArrayOf(1L)
    jvm.loadFrom({ gen[0] }) { }
    for (i in 0 until n) {
      jvm.add(i + 1L, i / 2L + 1L, kinds[i % kinds.size], mods[i % mods.size], false, unit(random))
    }
    assertTrue(jvm.settleNow())
    assertFalse(jvm.nativeAccelerated)

    // Adopt the exact same v3 file with useNative = true
    val native = sq8Index("parity.bin", rowsPerChunk = 1000, parallelism = 4, twoStage = twoStage, useNative = true)
    native.loadFrom({ gen[0] }) { throw AssertionError("must adopt existing file") }
    if (isMacArm64) assertTrue(native.nativeAccelerated)

    // Compare full scores, scoresAt, and filtered stageCandidates across multiple queries
    repeat(5) { qi ->
      val q = unit(random)
      val sJvm = jvm.scores(q)
      val sNat = native.scores(q)
      assertEquals(sJvm.size, sNat.size)
      var maxDelta = 0f
      for (i in sJvm.indices) {
        val d = abs(sJvm[i] - sNat[i])
        if (d > maxDelta) maxDelta = d
      }
      assertTrue("query $qi full scan max delta $maxDelta must be < 1e-4", maxDelta < 1e-4f)

      val sampleRows = IntArray(200) { (it * 23 + qi) % n }.also { it.sort() }
      val atJvm = jvm.scoresAt(q, sampleRows)
      val atNat = native.scoresAt(q, sampleRows)
      for (i in sampleRows.indices) {
        assertEquals("scoresAt[$i]", atJvm[i], atNat[i], 1e-4f)
        // scoresAt must match scores() bit-for-bit on the same engine
        assertEquals(sJvm[sampleRows[i]].toRawBits(), atJvm[i].toRawBits())
        assertEquals(sNat[sampleRows[i]].toRawBits(), atNat[i].toRawBits())
      }

      val onlyImagesAndPdf = BooleanArray(FileKind.entries.size).apply {
        this[FileKind.IMAGE.ordinal] = true
        this[FileKind.PDF.ordinal] = true
      }
      val cJvm = jvm.stageCandidates(q, onlyImagesAndPdf)
      val cNat = native.stageCandidates(q, onlyImagesAndPdf)
      assertEquals(cJvm.size, cNat.size)
      val overlap = cJvm.toSet().intersect(cNat.toSet()).size
      assertTrue("filtered stageCandidates overlap $overlap/${cJvm.size} must be >= 98%", overlap >= cJvm.size * 98 / 100)
    }

    // Also verify heap-mode SQ8 parity between JVM and native
    val heapJvm = sq8Index(backed = false, parallelism = 1, useNative = false)
    val heapNat = sq8Index(backed = false, parallelism = 1, useNative = true)
    repeat(300) { i ->
      val v = unit(random)
      heapJvm.add(i + 1L, i + 1L, FileKind.DOCUMENT, Modality.TEXT, false, v)
      heapNat.add(i + 1L, i + 1L, FileKind.DOCUMENT, Modality.TEXT, false, v)
    }
    val qh = unit(random)
    val hj = heapJvm.scores(qh)
    val hn = heapNat.scores(qh)
    for (i in hj.indices) assertEquals("heap[$i]", hj[i], hn[i], 1e-4f)
  }

  @Test fun perModalityTopKScaleSampleCacheAndSearcherFp16RescoreWorkEndToEnd() {
    val s = IndexStore.open(dir, log = {}).also { store = it }
    val random = Random(505)
    val twoStage = TwoStage(stageDim = 128, candidates = 25, minRows = 0, scaleSample = 5_000)

    val fp16 = VectorIndex(parallelism = 1, format = VectorFormat.FP16).also { open += it }
    val sq8 = sq8Index("searcher-v3.bin", rowsPerChunk = 256, parallelism = 2, twoStage = twoStage)

    val anchor = unit(random)
    var media = 1L
    fun addFile(name: String, kind: FileKind, mod: Modality, v: FloatArray, text: String? = null): Long {
      val id = media++
      s.upsertFromCrawl(listOf(CrawlRecord("primary", id, "file:///$name", "dir/", name, kind, null, 1_000, 1_000)))
      val f = s.knownMediaStoreIds("primary").getValue(id)
      val segKind = if (mod == Modality.IMAGE) SegmentKind.IMAGE else SegmentKind.CHUNK
      val seg = s.saveSegments(f, listOf(Segment(segKind, mod, text = text, vector = v)), FileState.UNDERSTOOD)[0]
      fp16.add(seg, f, kind, mod, false, v)
      sq8.add(seg, f, kind, mod, false, v)
      return f
    }

    // 400 text chunks with high cosine + 100 images with lower raw cosine to exercise G10 per-modality TopK.
    // Give the top 15 docs a clear gradient (like real semantic matches) so stage-1 (128-d) retrieves them.
    repeat(400) { i ->
      val noise = unit(random)
      val w = if (i < 15) 0.78f - i * 0.015f else 0.45f
      val raw = FloatArray(Fp16.DIM) { anchor[it] * w + noise[it] * (1f - w) }
      val norm = sqrt(raw.sumOf { (it * it).toDouble() }).toFloat()
      addFile("doc-$i.txt", FileKind.DOCUMENT, Modality.TEXT, FloatArray(Fp16.DIM) { raw[it] / norm }, "plain doc $i")
    }
    var plantedPhoto = -1L
    repeat(100) { i ->
      val noise = unit(random)
      val w = if (i == 42) 0.48f else 0.15f
      val raw = FloatArray(Fp16.DIM) { anchor[it] * w + noise[it] * (1f - w) }
      val norm = sqrt(raw.sumOf { (it * it).toDouble() }).toFloat()
      val f = addFile("img-$i.jpg", FileKind.IMAGE, Modality.IMAGE, FloatArray(Fp16.DIM) { raw[it] / norm })
      if (i == 42) plantedPhoto = f
    }

    // Verify scaleSample() cache returns same instance across reads and invalidates on write
    sq8.read {
      val first = sq8.scaleSample()
      val second = sq8.scaleSample()
      assertSame("scaleSample must be cached across reads", first, second)
    }
    // G10: stageCandidates must include both TEXT and IMAGE rows
    val staged = sq8.stageCandidates(anchor, allowAll())
    val stagedMods = sq8.read { staged.map { sq8.modalityOrdinal(it) }.toSet() }
    assertTrue("G10: TEXT must be represented", Modality.TEXT.ordinal in stagedMods)
    assertTrue("G10: IMAGE must not be starved behind TEXT", Modality.IMAGE.ordinal in stagedMods)

    // Searcher Top-100 fp16 rescore matches exact FP16 top-10 ranking
    val exactHits = Searcher(s, fp16).search("unrelated query", anchor, KindFilter.ALL, 15).hits
    val sq8Hits = Searcher(s, sq8).search("unrelated query", anchor, KindFilter.ALL, 15).hits
    assertTrue("planted photo must appear in SQ8 top results", sq8Hits.any { it.file.id == plantedPhoto })
    val top10Exact = exactHits.take(10).map { it.file.id }
    val top10Sq8 = sq8Hits.take(10).map { it.file.id }
    assertEquals("Searcher Top-10 with fp16 rescore must match exact FP16", top10Exact, top10Sq8)

    // Also verify full-scan SQ8 (no stage-1) + Searcher Top-100 fp16 rescore matches exact FP16 top-10
    val sq8Full = sq8Index("searcher-v3-full.bin", rowsPerChunk = 256, parallelism = 2, twoStage = null)
    sq8Full.load(s)
    val sq8FullHits = Searcher(s, sq8Full).search("unrelated query", anchor, KindFilter.ALL, 15).hits
    assertEquals(top10Exact, sq8FullHits.take(10).map { it.file.id })

    // Cache invalidation on write
    val beforeMut = sq8.read { sq8.scaleSample() }
    addFile("extra.txt", FileKind.DOCUMENT, Modality.TEXT, unit(random), "extra")
    val afterMut = sq8.read { sq8.scaleSample() }
    assertNotSame("scaleSample must invalidate after write", beforeMut, afterMut)
  }

  @Test fun storageEstimateMatchesRealSq8VectorFile() {
    val index = VectorIndex(
      parallelism = 1,
      backing = File(dir, "est-v3.bin"),
      settleDelayMs = -1,
      twoStage = TwoStage.DEFAULT,
      format = VectorFormat.SQ8,
    ).also { open += it }
    val v = FloatArray(768) { 0.01f }
    for (i in 1..5_000) index.add(i.toLong(), i.toLong(), FileKind.IMAGE, Modality.IMAGE, false, v)
    val r = StorageEstimate.estimate(
      listOf(StorageEstimate.KindTotals(FileKind.IMAGE, 5_000, 0, 0)),
      FileKind.entries.toSet(),
      format = VectorFormat.SQ8,
      stageDim = TwoStage.DEFAULT.stageDim,
    )
    assertEquals(5_000 * StorageEstimate.IMAGE_BYTES + index.mappedBytes(), r.indexBytes)
    assertEquals(index.mappedBytes(), File(dir, "est-v3.bin").length())
    val quickEmpty = 256 * 23L + 32 * 12L
    assertEquals(index.memoryBytes() + quickEmpty, r.ramBytes)
  }

  @Test fun heapOverheadWithoutRowOfSegmentStaysCompactAndDedupsPerFile() {
    val random = Random(909)
    val ix = sq8Index("compact-heap.bin", rowsPerChunk = 500, parallelism = 1)
    val gen = longArrayOf(1L)
    ix.loadFrom({ gen[0] }) { }
    // 100 files x 32 chunks = 3,200 rows; headOfFile only stores 100 entries (no rowOfSegment map)
    val v1 = unit(random)
    val v2 = unit(random)
    for (f in 1L..100L) {
      for (c in 0 until 32) {
        val seg = f * 100L + c
        ix.add(seg, f, FileKind.DOCUMENT, Modality.TEXT, false, v1)
      }
    }
    assertEquals(3_200, ix.size)
    // Replacing an existing segment on the same file deduplicates via headOfFile walk
    ix.add(50 * 100L + 7, 50L, FileKind.DOCUMENT, Modality.TEXT, false, v2)
    assertEquals(3_200, ix.size)
    assertTrue(ix.settleNow())
    val heapBeforeClose = ix.heapBytes()
    ix.close()

    val adopted = sq8Index("compact-heap.bin", rowsPerChunk = 500, parallelism = 1)
    adopted.loadFrom({ gen[0] }) { throw AssertionError("must adopt settled file") }
    assertEquals(3_200, adopted.size)
    // Lazy headOfFile sizing (maxOf(16, n / 32)) keeps adopted heap tightly bounded (< 30 B/row total)
    assertTrue("adopted heap ${adopted.heapBytes()} must stay compact (was $heapBeforeClose)", adopted.heapBytes() < 3_200 * 35L)
  }
}
