package com.craneai.findr.search

import com.craneai.findr.core.FileKind
import com.craneai.findr.core.KindFilter
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * The two-stage (Matryoshka) scan: stage 1 on the first dims in a separate block, stage 2 exact on the best rows. The exact path
 * (below the size threshold, or with no two-stage configuration) must stay what it was; the accuracy gate is the app-path harness
 * (the build notes), these tests hold the mechanics.
 */
class TwoStageScanTest {
  private lateinit var dir: File
  private val open = ArrayList<VectorIndex>()
  private var store: IndexStore? = null

  @Before fun setUp() { dir = Files.createTempDirectory("findr-two-stage").toFile() }

  @After fun tearDown() {
    open.forEach { runCatching { it.close() } }
    store?.close()
    dir.deleteRecursively()
  }

  private val stage = 64
  private fun config(candidates: Int = 50, minRows: Int = 0) = TwoStage(stageDim = stage, candidates = candidates, minRows = minRows, scaleSample = 200)

  private fun index(twoStage: TwoStage?, backed: Boolean, name: String = "v.bin", rowsPerChunk: Int = 100) = VectorIndex(
    initialCapacity = 16, parallelism = 1, backing = if (backed) File(dir, name) else null, settleDelayMs = -1,
    rowsPerChunk = rowsPerChunk, twoStage = twoStage,
  ).also { open += it }

  private fun unit(random: Random): FloatArray {
    val v = FloatArray(Fp16.DIM) { (random.nextDouble() * 2 - 1).toFloat() }
    val n = sqrt(v.sumOf { (it * it).toDouble() }).toFloat()
    return FloatArray(Fp16.DIM) { v[it] / n }
  }

  private val kinds = listOf(FileKind.IMAGE, FileKind.DOCUMENT)

  private fun fill(index: VectorIndex, n: Int, seed: Int = 1): List<FloatArray> {
    val random = Random(seed)
    return List(n) { i ->
      unit(random).also { index.add(i + 1L, i / 2L + 1, kinds[i % 2], Modality.TEXT, false, it) }
    }
  }

  private fun allowAll() = BooleanArray(FileKind.entries.size) { true }

  /** What stage 1 should rank by: the fp16-rounded first [stage] dims, scaled to unit length, against the same of the query. */
  private fun stageScore(q: FloatArray, v: FloatArray): Double {
    fun head(x: FloatArray) = DoubleArray(stage) { Fp16.toFloat(Fp16.toHalf(x[it])).toDouble() }.let { h -> val n = sqrt(h.sumOf { it * it }); DoubleArray(stage) { h[it] / n } }
    val a = head(q); val b = head(v)
    return a.indices.sumOf { a[it] * b[it] }
  }

  @Test fun stageOneReturnsTheBestRowsOnTheLeadingDimsInRowOrder() {
    val index = index(config(candidates = 30), backed = true)
    val data = fill(index, 400)
    val q = unit(Random(99))
    val got = index.stageCandidates(q, allowAll())
    assertEquals(30, got.size)
    assertTrue("ascending", got.toList() == got.sorted())
    val want = data.indices.sortedByDescending { stageScore(q, data[it]) }.take(30).toSet()
    // fp16 rounding can swap rows whose scores differ in the last bits: demand all but the boundary to agree
    assertTrue("overlap ${got.toSet().intersect(want).size}", got.toSet().intersect(want).size >= 28)
  }

  @Test fun theHeapAndTheMappedIndexPickTheSameCandidates() {
    val heap = index(config(), backed = false)
    val mapped = index(config(), backed = true)
    fill(heap, 500); fill(mapped, 500)
    val q = unit(Random(5))
    assertArrayEquals(heap.stageCandidates(q, allowAll()), mapped.stageCandidates(q, allowAll()))
  }

  @Test fun stageOneOnlyConsidersTheKindsTheFilterAllows() {
    val index = index(config(candidates = 40), backed = true)
    fill(index, 300)
    val allowed = BooleanArray(FileKind.entries.size).also { it[FileKind.IMAGE.ordinal] = true }
    val got = index.stageCandidates(unit(Random(3)), allowed)
    assertEquals(40, got.size)
    assertTrue(got.all { index.kindOrdinal(it) == FileKind.IMAGE.ordinal })
  }

  @Test fun theRowMostLikeTheQueryIsAlwaysACandidate() {
    val index = index(config(candidates = 10), backed = true)
    val data = fill(index, 600)
    assertTrue(index.stageCandidates(data[417], allowAll()).contains(417))
  }

  @Test fun stageTwoScoresAreTheExactScoresBitForBit() {
    val index = index(config(), backed = true)
    fill(index, 300)
    val q = unit(Random(11))
    val exact = index.scores(q)
    val rows = intArrayOf(0, 7, 120, 299)
    val got = index.scoresAt(q, rows)
    for (i in rows.indices) assertEquals(exact[rows[i]].toRawBits(), got[i].toRawBits())
  }

  @Test fun theStageBlockSurvivesRemovingAndAddingRows() {
    val index = index(config(candidates = 5), backed = true)
    val data = fill(index, 250).toMutableList()
    index.removeFile(1L)       // rows 0 and 1: the last rows move into their places
    val q = data[200]
    assertTrue(index.stageCandidates(q, allowAll()).any { index.segmentId(it) == 201L })
    val extra = unit(Random(77))
    index.add(1_000L, 500L, FileKind.IMAGE, Modality.TEXT, false, extra)
    assertTrue(index.stageCandidates(extra, allowAll()).any { index.segmentId(it) == 1_000L })
  }

  @Test fun aSettledFileIsAdoptedWithItsStageBlockAndAnotherWidthIsRebuilt() {
    val s = IndexStore.open(dir, log = {}).also { store = it }
    val ids = saveRows(s, 300)
    val first = index(config(), backed = true)
    first.load(s)
    assertEquals(300, first.size)
    assertTrue(first.settleNow())
    val q = unit(Random(2))
    val before = first.stageCandidates(q, allowAll())
    first.close()

    val same = index(config(), backed = true)
    same.load(s)
    assertArrayEquals("adopted file gives the same candidates", before, same.stageCandidates(q, allowAll()))
    same.close()

    val otherWidth = index(TwoStage(stageDim = 32, candidates = 50, minRows = 0), backed = true)
    otherWidth.load(s)                                   // a file with another stage width is not trusted: rebuilt from SQLite
    assertEquals(ids.size, otherWidth.size)
    assertEquals(50, otherWidth.stageCandidates(q, allowAll()).size)

    val noStage = index(null, backed = true)             // and a file written without any stage block is not trusted either
    noStage.load(s)
    assertEquals(ids.size, noStage.size)
  }

  @Test fun aVersionOneFileIsAdoptedWhenThereIsNoStageBlockAndRebuiltWhenThereShouldBe() {
    val s = IndexStore.open(dir, log = {}).also { store = it }
    saveRows(s, 120)
    val file = File(dir, "v1.bin")
    val first = index(null, backed = true, name = "v1.bin")
    first.load(s)
    assertTrue(first.settleNow())
    first.close()
    // Rewrite the header as version 1 (what the previous release wrote): the version, and the CRC over the first 48 bytes.
    java.io.RandomAccessFile(file, "rw").use { raf ->
      val head = ByteArray(48)
      raf.seek(0); raf.readFully(head)
      java.nio.ByteBuffer.wrap(head).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(8, 1)
      val crc = java.util.zip.CRC32().also { it.update(head) }.value.toInt()
      raf.seek(0); raf.write(head)
      raf.seek(48); raf.write(java.nio.ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(crc).array())
    }
    assertNotNull("a version 1 file is a version 2 file with no stage block", VectorFile.openExisting(file, Fp16.DIM, rowsPerChunk = 100)?.also { it.close() })
    assertEquals("but it cannot serve a scan that wants a stage block", null, VectorFile.openExisting(file, Fp16.DIM, rowsPerChunk = 100, stageDim = stage))
  }

  @Test fun belowTheThresholdTheScanStaysExact() {
    val small = index(config(minRows = 1_000), backed = true)
    fill(small, 500)
    assertFalse(small.twoStageApplies())
    val big = index(config(minRows = 400, candidates = 50), backed = true)
    fill(big, 500)
    assertTrue(big.twoStageApplies())
    val noCandidatesToDrop = index(config(minRows = 0, candidates = 600), backed = true)
    fill(noCandidatesToDrop, 500)
    assertFalse("K >= rows is just an exact scan", noCandidatesToDrop.twoStageApplies())
    assertFalse(index(null, backed = true).also { fill(it, 500) }.twoStageApplies())
  }

  @Test fun theSearcherRanksAPlantedTargetFirstAndAgreesWithTheExactSearch() {
    val s = IndexStore.open(dir, log = {}).also { store = it }
    val random = Random(21)
    val exact = VectorIndex(parallelism = 1).also { open += it }
    val staged = index(TwoStage(stageDim = stage, candidates = 80, minRows = 0, scaleSample = 5_000), backed = true)   // every row is in the scale sample
    var media = 1L
    fun add(name: String, v: FloatArray, text: String?): Long {
      val id = media++
      s.upsertFromCrawl(listOf(CrawlRecord("external_primary", id, "content://media/external/file/$id", "Documents/", name, FileKind.DOCUMENT, null, 1_000, 1_000)))
      val f = s.knownMediaStoreIds("external_primary").getValue(id)
      val seg = s.saveSegments(f, listOf(Segment(SegmentKind.CHUNK, Modality.TEXT, text = text, vector = v)), FileState.UNDERSTOOD)[0]
      exact.add(seg, f, FileKind.DOCUMENT, Modality.TEXT, false, v)
      staged.add(seg, f, FileKind.DOCUMENT, Modality.TEXT, false, v)
      return f
    }
    repeat(600) { add("note$it.txt", unit(random), "filler text number $it") }
    val target = unit(random)
    val targetFile = add("lease.txt", target.copyOf().also { v -> for (i in 0 until 20) v[i] += 0.0f }, "tenancy agreement")
    val query = FloatArray(Fp16.DIM) { target[it] * 0.9f + unit(Random(8))[it] * 0.1f }
    val a = Searcher(s, exact).search("rental contract", query, KindFilter.ALL, 20)
    val b = Searcher(s, staged).search("rental contract", query, KindFilter.ALL, 20)
    assertEquals(targetFile, a.hits.first().file.id)
    assertEquals(targetFile, b.hits.first().file.id)
    assertNotNull(b.hits.firstOrNull())
    // the top of both lists is the same files; the z of the target is the same to within the sampled scale (here every row is sampled)
    assertEquals(a.hits.take(5).map { it.file.id }, b.hits.take(5).map { it.file.id })
    assertEquals(a.hits.first().score, b.hits.first().score, 1e-6)
  }

  private fun saveRows(s: IndexStore, n: Int): List<Long> {
    val random = Random(13)
    val out = ArrayList<Long>()
    for (media in 1L..n) {
      s.upsertFromCrawl(listOf(CrawlRecord("external_primary", media, "content://media/external/file/$media", "Documents/", "d$media.txt", FileKind.DOCUMENT, null, 1_000, 1_000)))
      val f = s.knownMediaStoreIds("external_primary").getValue(media)
      out += s.saveSegments(f, listOf(Segment(SegmentKind.CHUNK, Modality.TEXT, text = "w$media", vector = unit(random))), FileState.UNDERSTOOD)[0]
    }
    return out
  }
}
