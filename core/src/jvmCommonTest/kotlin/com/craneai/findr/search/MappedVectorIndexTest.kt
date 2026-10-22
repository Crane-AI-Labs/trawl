package com.craneai.findr.search

import com.craneai.findr.core.FileKind
import com.craneai.findr.core.LocalData
import com.craneai.findr.index.CrawlRecord
import com.craneai.findr.index.FileState
import com.craneai.findr.index.Fp16
import com.craneai.findr.index.IndexStore
import com.craneai.findr.index.Modality
import com.craneai.findr.index.Schema
import com.craneai.findr.index.Segment
import com.craneai.findr.index.SegmentKind
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * The vector index with its vectors in a memory-mapped file: it must answer exactly as the heap index it replaced did
 * (bit for bit), and must never trust a file that could be wrong.
 */
class MappedVectorIndexTest {
  private lateinit var dir: File
  private val open = ArrayList<VectorIndex>()

  @Before fun setUp() { dir = Files.createTempDirectory("findr-mapped").toFile() }

  @After fun tearDown() {
    open.forEach { runCatching { it.close() } }
    dir.deleteRecursively()
  }

  private fun file(name: String = "vectors.bin") = File(dir, name)

  private fun mapped(
    name: String = "vectors.bin", rowsPerChunk: Int = 1000, parallelism: Int = 1, quiet: () -> Boolean = { true },
    dim: Int = Fp16.DIM, holds: Set<Modality> = VectorIndex.EMBEDDING_GEMMA,
  ) = VectorIndex(
    dim = dim, initialCapacity = 16, parallelism = parallelism, holds = holds,
    backing = file(name), settleDelayMs = -1, quiet = quiet, rowsPerChunk = rowsPerChunk,
  ).also { open += it }

  private fun unit(random: Random, dim: Int = Fp16.DIM): FloatArray {
    val v = FloatArray(dim) { (random.nextDouble() * 2 - 1).toFloat() }
    val n = sqrt(v.sumOf { (it * it).toDouble() }).toFloat()
    return FloatArray(dim) { v[it] / n }
  }

  private val kinds = listOf(FileKind.IMAGE, FileKind.PDF, FileKind.DOCUMENT, FileKind.VIDEO)
  private val modalities = listOf(Modality.TEXT, Modality.IMAGE, Modality.AUDIO)

  /** [n] random rows as (segment, file, kind, modality, late flag, vector). */
  private class Row(val segment: Long, val file: Long, val kind: FileKind, val modality: Modality, val late: Int, val vector: FloatArray)

  private fun rows(n: Int, seed: Int, dim: Int = Fp16.DIM): List<Row> {
    val random = Random(seed)
    return List(n) {
      Row(it + 1L, it / 4L + 1, kinds[random.nextInt(kinds.size)], modalities[random.nextInt(modalities.size)], random.nextInt(3), unit(random, dim))
    }
  }

  private fun fill(index: VectorIndex, rows: List<Row>) {
    for (r in rows) index.addBlob(r.segment, r.file, r.kind, r.modality, r.late, Fp16.toBlob(r.vector))
  }

  private fun fillLegacy(index: LegacyHeapVectorIndex, rows: List<Row>) {
    for (r in rows) index.addBlob(r.segment, r.file, r.kind, r.modality, r.late, Fp16.toBlob(r.vector))
  }

  private fun assertSameBits(expected: FloatArray, actual: FloatArray) {
    assertEquals(expected.size, actual.size)
    for (i in expected.indices) assertEquals("row $i", expected[i].toRawBits(), actual[i].toRawBits())
  }

  private fun topBySegment(scores: FloatArray, segmentOf: (Int) -> Long, k: Int): List<Long> =
    scores.indices.sortedWith(compareByDescending<Int> { scores[it] }.thenBy { segmentOf(it) }).take(k).map(segmentOf)

  // ---- identical answers ------------------------------------------------------------------------------------------

  @Test fun scoresAndRankingsAreBitForBitTheHeapIndexsOnTwentyThousandRandomVectors() {
    val data = rows(20_000, seed = 11)
    val legacy = LegacyHeapVectorIndex(parallelism = 1); fillLegacy(legacy, data)
    for (parallelism in listOf(1, 4)) for (chunk in listOf(1000, VectorFile.ROWS_PER_CHUNK)) {
      val index = mapped("v-$parallelism-$chunk.bin", rowsPerChunk = chunk, parallelism = parallelism)
      fill(index, data)
      assertTrue(index.offHeap)
      assertEquals(legacy.size, index.size)
      val random = Random(5)
      repeat(5) {
        val q = unit(random)
        val expected = legacy.scores(q)
        val actual = index.scores(q)
        assertSameBits(expected, actual)
        val ranked = legacy.read { topBySegment(expected, legacy::segmentId, 100) }
        assertEquals(ranked, index.read { topBySegment(actual, index::segmentId, 100) })
      }
      // The facts on the rows too.
      index.read { legacy.read { for (row in 0 until legacy.size step 97) {
        assertEquals(legacy.segmentId(row), index.segmentId(row)); assertEquals(legacy.fileId(row), index.fileId(row))
        assertEquals(legacy.kindOrdinal(row), index.kindOrdinal(row)); assertEquals(legacy.modalityOrdinal(row), index.modalityOrdinal(row))
        assertEquals(legacy.isLate(row), index.isLate(row)); assertEquals(legacy.isStale(row), index.isStale(row))
      } } }
      index.close()
    }
    legacy.close()
  }

  @Test fun theSameChangesGiveTheSameRowsAndScores() {
    val data = rows(6_000, seed = 12)
    val legacy = LegacyHeapVectorIndex(parallelism = 1, initialCapacity = 16); fillLegacy(legacy, data)
    val index = mapped(rowsPerChunk = 700); fill(index, data)
    val random = Random(6)
    // Removals (which swap the last row in), replacements, a stale flip, a whole-index clear and a refill.
    for (file in listOf(3L, 77L, 1500L, 1L)) assertEquals(legacy.removeFile(file), index.removeFile(file))
    assertEquals(legacy.removeFiles(listOf(10L, 11L, 12L, 9999L)), index.removeFiles(listOf(10L, 11L, 12L, 9999L)))
    val again = List(300) { Row(random.nextInt(1, 7000).toLong(), 8000L + it / 3, FileKind.IMAGE, Modality.IMAGE, 0, unit(random)) }
    fillLegacy(legacy, again); fill(index, again)
    assertEquals(legacy.markStale(setOf(Modality.TEXT)), index.markStale(setOf(Modality.TEXT)))
    assertEquals(legacy.size, index.size)
    val q = unit(random)
    assertSameBits(legacy.scores(q), index.scores(q))
    legacy.read { index.read { for (row in 0 until legacy.size) {
      assertEquals(legacy.segmentId(row), index.segmentId(row)); assertEquals(legacy.isStale(row), index.isStale(row)); assertEquals(legacy.isLate(row), index.isLate(row))
    } } }
    legacy.clear(); index.clear()
    assertEquals(0, index.size)
    assertEquals(0, index.scores(q).size)
    fillLegacy(legacy, data.take(500)); fill(index, data.take(500))
    assertSameBits(legacy.scores(q), index.scores(q))
  }

  @Test fun addingAVectorFromFloatsAndAnUnnormalisedQueryMatchToo() {
    val random = Random(13)
    val legacy = LegacyHeapVectorIndex(parallelism = 1); val index = mapped(rowsPerChunk = 64)
    for (i in 1L..300L) {
      val v = unit(random)
      legacy.add(i, i / 3, FileKind.IMAGE, Modality.IMAGE, i % 5 == 0L, v, stale = i % 7 == 0L)
      index.add(i, i / 3, FileKind.IMAGE, Modality.IMAGE, i % 5 == 0L, v, stale = i % 7 == 0L)
    }
    val q = FloatArray(Fp16.DIM) { (random.nextDouble() * 6 - 3).toFloat() }
    assertSameBits(legacy.scores(q), index.scores(q))
  }

  @Test fun theVectorsAreOffTheHeap() {
    val index = mapped(rowsPerChunk = VectorFile.ROWS_PER_CHUNK)
    fill(index, rows(5_000, seed = 14))
    assertTrue(index.offHeap)
    // 5,000 rows of facts (19 bytes) and the lookup, not the 7.7 MB of vectors, which are in the file.
    assertTrue("heap ${index.heapBytes()}", index.heapBytes() < 400_000)
    assertTrue(index.mappedBytes() >= 5_000L * (Fp16.DIM * 2 + VectorFile.META_ROW))
    assertEquals(index.mappedBytes(), file().length())
  }

  // ---- adopting the file, and distrusting it -------------------------------------------------------------------------

  private fun noRows(): ((Long, Long, FileKind, Modality, Int, ByteArray) -> Unit) -> Unit =
    { throw AssertionError("the database was read although the file should have been adopted") }

  private fun rowsFrom(data: List<Row>, read: IntArray = IntArray(1)): ((Long, Long, FileKind, Modality, Int, ByteArray) -> Unit) -> Unit = { sink ->
    for (r in data) { sink(r.segment, r.file, r.kind, r.modality, r.late, Fp16.toBlob(r.vector)); read[0]++ }
  }

  /** A settled file at generation 7 holding [data], closed, ready to be reopened. */
  private fun settledFile(data: List<Row>, name: String = "vectors.bin") {
    val first = mapped(name)
    first.loadFrom({ 7L }, rowsFrom(data))
    assertTrue(first.settleNow())
    first.close()
  }

  @Test fun aSettledFileAtTheDatabasesGenerationIsAdoptedWithoutReadingTheDatabase() {
    val data = rows(3_000, seed = 15)
    val legacy = LegacyHeapVectorIndex(parallelism = 1); fillLegacy(legacy, data)
    settledFile(data)
    val second = mapped()
    second.loadFrom({ 7L }, noRows())
    assertEquals(3_000, second.size)
    val q = unit(Random(1))
    assertSameBits(legacy.scores(q), second.scores(q))
    // And it keeps working: a change after adopting is seen, and the segment lookup was rebuilt (a replace finds its row).
    val replaced = Row(5L, 99L, FileKind.IMAGE, Modality.IMAGE, 0, unit(Random(2)))
    second.addBlob(replaced.segment, replaced.file, replaced.kind, replaced.modality, 0, Fp16.toBlob(replaced.vector))
    assertEquals(3_000, second.size)
  }

  private fun assertRebuilds(why: String, generation: Long = 7L) {
    val data = rows(1_500, seed = 16)
    val legacy = LegacyHeapVectorIndex(parallelism = 1); fillLegacy(legacy, data)
    val read = IntArray(1)
    val index = mapped()
    index.loadFrom({ generation }, rowsFrom(data, read))
    assertEquals("$why: rebuilt from the database", 1_500, read[0])
    assertEquals(why, 1_500, index.size)
    val q = unit(Random(3))
    assertSameBits(legacy.scores(q), index.scores(q))
    // The rebuilt file is a good one: it settles and is adopted next time.
    assertTrue(index.settleNow())
    index.close()
    val next = mapped()
    next.loadFrom({ generation }, noRows())
    assertEquals(why, 1_500, next.size)
  }

  private fun patch(offset: Long, vararg bytes: Int) = RandomAccessFile(file(), "rw").use { it.seek(offset); bytes.forEach { b -> it.write(b) } }

  @Test fun aMissingFileRebuilds() { assertRebuilds("missing") }

  @Test fun aWrongMagicRebuilds() { settledFile(rows(1_500, seed = 16)); patch(0, 'X'.code); assertRebuilds("magic") }

  @Test fun aHeaderWithABadChecksumRebuilds() { settledFile(rows(1_500, seed = 16)); patch(26, 0x5A); assertRebuilds("crc") }

  @Test fun aTruncatedFileRebuilds() {
    settledFile(rows(1_500, seed = 16))
    RandomAccessFile(file(), "rw").use { it.setLength(it.length() - 4096) }
    assertRebuilds("truncated")
  }

  @Test fun aFileLongerThanItsHeaderSaysRebuilds() {
    settledFile(rows(1_500, seed = 16))
    RandomAccessFile(file(), "rw").use { it.setLength(it.length() + 4096) }
    assertRebuilds("long")
  }

  @Test fun anEmptyFileAndGarbageRebuild() {
    file().writeBytes(ByteArray(0)); assertRebuilds("empty")
    file().writeBytes(ByteArray(100_000) { (it * 31).toByte() }); assertRebuilds("garbage")
  }

  @Test fun aFileLeftOpenByACrashRebuilds() {
    // A change was made after the file settled and the process died before it settled again: the header still says "open".
    val first = mapped()
    first.loadFrom({ 7L }, rowsFrom(rows(1_500, seed = 16)))
    assertTrue(first.settleNow())
    first.addBlob(99_999L, 1L, FileKind.IMAGE, Modality.IMAGE, 0, Fp16.toBlob(unit(Random(1))))
    file().copyTo(file("crashed.bin"))   // what the disk holds at that moment
    val read = IntArray(1)
    val second = mapped("crashed.bin")
    second.loadFrom({ 7L }, rowsFrom(rows(10, seed = 1), read))
    assertEquals("an open file is not trusted", 10, read[0])
    assertEquals(10, second.size)
  }

  @Test fun aFileBehindTheDatabaseRebuilds() {
    settledFile(rows(1_500, seed = 16))
    assertRebuilds("generation moved", generation = 8L)
  }

  @Test fun aFileAheadOfTheDatabaseRebuildsToo() {
    settledFile(rows(1_500, seed = 16))
    assertRebuilds("generation went back", generation = 3L)
  }

  @Test fun anotherDimensionRebuilds() {
    settledFile(rows(500, seed = 17))
    val quick = mapped(dim = IndexStore.QUICK_DIM, holds = VectorIndex.QUICK_LOOK)
    val read = IntArray(1)
    val data = rows(40, seed = 18, dim = IndexStore.QUICK_DIM).map { Row(it.segment, it.file, it.kind, Modality.CLIP, 0, it.vector) }
    quick.loadFrom({ 7L }, rowsFrom(data, read))
    assertEquals(40, read[0])
    assertEquals(40, quick.size)
  }

  @Test fun anUnquietAppDoesNotSettleSoTheNextStartRebuilds() {
    var quiet = false
    val index = mapped(quiet = { quiet })
    index.loadFrom({ 7L }, rowsFrom(rows(500, seed = 19)))
    assertFalse(index.settleNow())   // the worker is running: a vector it saved may not have arrived yet
    file().copyTo(file("unsettled.bin"))
    val read = IntArray(1)
    mapped("unsettled.bin").loadFrom({ 7L }, rowsFrom(rows(500, seed = 19), read))
    assertEquals(500, read[0])
    quiet = true
    assertTrue(index.settleNow())
    file().copyTo(file("settled.bin"))
    mapped("settled.bin").loadFrom({ 7L }, noRows())
  }

  // ---- stale and late flags ---------------------------------------------------------------------------------------

  @Test fun markStaleSurvivesAClosedAndReopenedFile() {
    val data = rows(2_000, seed = 21)
    val first = mapped()
    first.loadFrom({ 7L }, rowsFrom(data))
    val flipped = first.markStale(setOf(Modality.TEXT))
    assertTrue(flipped > 0)
    assertTrue(first.settleNow())
    val staleBefore = first.read { (0 until first.size).filter { first.isStale(it) }.map { first.segmentId(it) }.toSet() }
    val lateBefore = first.read { (0 until first.size).filter { first.isLate(it) }.map { first.segmentId(it) }.toSet() }
    first.close()
    val second = mapped()
    second.loadFrom({ 7L }, noRows())
    val staleAfter = second.read { (0 until second.size).filter { second.isStale(it) }.map { second.segmentId(it) }.toSet() }
    val lateAfter = second.read { (0 until second.size).filter { second.isLate(it) }.map { second.segmentId(it) }.toSet() }
    assertEquals(staleBefore, staleAfter)
    assertEquals(lateBefore, lateAfter)
    assertTrue(staleAfter.isNotEmpty() && lateAfter.isNotEmpty())
    assertEquals(0, second.markStale(setOf(Modality.TEXT)))   // already stale: nothing left to flip
    // A re-read replaces a stale row with a current one, in the file too.
    val segment = staleAfter.first()
    val row = data.first { it.segment == segment }
    second.addBlob(segment, row.file, row.kind, row.modality, 0, Fp16.toBlob(row.vector))
    assertFalse(second.read { (0 until second.size).first { second.segmentId(it) == segment }.let(second::isStale) })
  }

  @Test fun theAtomicPerFileSwapReplacesAFilesRowsAndKeepsTheOthers() {
    val dirStore = Files.createTempDirectory("findr-swap").toFile()
    val store = IndexStore.open(dirStore, log = {})
    try {
      val random = Random(22)
      val index = mapped(rowsPerChunk = 8)
      fun file(name: String): Long {
        val id = (name.hashCode().toLong() and 0xFFFFF) + 1
        store.upsertFromCrawl(listOf(CrawlRecord("external_primary", id, "content://media/external/file/$id", "DCIM/", name, FileKind.IMAGE, null, 1, 1)))
        return store.knownMediaStoreIds("external_primary").getValue(id)
      }
      val files = (1..20).map { file("f$it.jpg") }
      for (f in files) {
        val ids = store.saveSegments(f, List(3) { Segment(SegmentKind.CHUNK, Modality.TEXT, text = "t", vector = unit(random)) }, FileState.UNDERSTOOD)
        index.loadFile(store, f)
        assertEquals(3, ids.size)
      }
      assertEquals(60, index.size)
      val target = files[5]
      store.saveSegments(target, List(2) { Segment(SegmentKind.CHUNK, Modality.TEXT, text = "t", vector = unit(random)) }, FileState.UNDERSTOOD)
      index.loadFile(store, target)
      assertEquals(59, index.size)
      assertEquals(2, index.read { (0 until index.size).count { index.fileId(it) == target } })
      // The index now equals a fresh load of the same store, vector for vector.
      val q = unit(random)
      val fresh = mapped("fresh.bin", rowsPerChunk = 8)
      fresh.load(store)
      val a = index.read { index.scores(q).withIndex().associate { (i, s) -> index.segmentId(i) to s.toRawBits() } }
      val b = fresh.read { fresh.scores(q).withIndex().associate { (i, s) -> fresh.segmentId(i) to s.toRawBits() } }
      assertEquals(b, a)
    } finally { store.close(); dirStore.deleteRecursively() }
  }

  // ---- the database's generation ----------------------------------------------------------------------------------

  @Test fun theGenerationCountsEveryChangeToTheSearchableVectorsAndSeparatesQuickLook() {
    val dirStore = Files.createTempDirectory("findr-gen").toFile()
    val store = IndexStore.open(dirStore, log = {})
    try {
      val random = Random(23)
      assertEquals(0L, store.vectorGeneration()); assertEquals(0L, store.vectorGeneration(quickLook = true))
      store.upsertFromCrawl(listOf(CrawlRecord("external_primary", 1, "content://media/external/file/1", "DCIM/", "a.jpg", FileKind.IMAGE, null, 1, 1)))
      val f = store.knownMediaStoreIds("external_primary").getValue(1)
      store.saveSegments(f, listOf(Segment(SegmentKind.NAME, text = "a.jpg")), FileState.UNDERSTOOD)
      assertEquals("a name has no vector", 0L, store.vectorGeneration())
      store.saveSegments(f, listOf(Segment(SegmentKind.IMAGE, Modality.IMAGE, vector = unit(random))), FileState.UNDERSTOOD)
      val afterSave = store.vectorGeneration()
      assertTrue(afterSave > 0)
      assertEquals("an EmbeddingGemma vector does not move the quick-look counter", 0L, store.vectorGeneration(quickLook = true))
      store.upsertFromCrawl(listOf(CrawlRecord("external_primary", 2, "content://media/external/file/2", "DCIM/", "b.jpg", FileKind.IMAGE, null, 1, 1)))
      val g = store.knownMediaStoreIds("external_primary").getValue(2)   // not read yet: only quick look has seen it
      assertTrue(store.saveQuick(g, SegmentKind.IMAGE, unit(random, IndexStore.QUICK_DIM)) > 0)
      assertEquals(afterSave, store.vectorGeneration())
      assertTrue(store.vectorGeneration(quickLook = true) > 0)
      store.setState(f, FileState.SKIPPED)
      assertTrue("a file leaving the index changes what a load returns", store.vectorGeneration() > afterSave)
    } finally { store.close(); dirStore.deleteRecursively() }
  }

  @Test fun aChangeTheFileNeverSawIsPickedUpFromTheDatabaseOnTheNextStart() {
    val dirStore = Files.createTempDirectory("findr-gen2").toFile()
    val store = IndexStore.open(dirStore, log = {})
    try {
      val random = Random(24)
      fun fileFor(id: Long): Long {
        store.upsertFromCrawl(listOf(CrawlRecord("external_primary", id, "content://media/external/file/$id", "DCIM/", "p$id.jpg", FileKind.IMAGE, null, 1, 1)))
        return store.knownMediaStoreIds("external_primary").getValue(id)
      }
      val a = fileFor(1)
      store.saveSegments(a, listOf(Segment(SegmentKind.IMAGE, Modality.IMAGE, vector = unit(random))), FileState.UNDERSTOOD)
      val first = mapped()
      first.load(store)
      assertTrue(first.settleNow())
      first.close()
      // The worker saved a second file to the database, and the app died before the index (or the file) heard of it.
      val b = fileFor(2)
      store.saveSegments(b, listOf(Segment(SegmentKind.IMAGE, Modality.IMAGE, vector = unit(random))), FileState.UNDERSTOOD)
      val second = mapped()
      second.load(store)
      assertEquals("the file was stale and the database won", 2, second.size)
      assertTrue(second.settleNow())
      second.close()
      val third = mapped()
      third.load(store)
      assertEquals(2, third.size)
    } finally { store.close(); dirStore.deleteRecursively() }
  }

  // ---- delete everything --------------------------------------------------------------------------------------------

  @Test fun shreddingOverwritesTheFileAndTheWipeDeletesIt() {
    val indexDir = File(dir, "index").also { it.mkdirs() }
    val cacheDir = File(dir, "cache").also { it.mkdirs() }
    val path = File(indexDir, "vectors.bin")
    val index = VectorIndex(backing = path, settleDelayMs = -1, rowsPerChunk = 100)
    open += index
    fill(index, rows(500, seed = 25))
    assertTrue(index.settleNow())
    val before = path.readBytes()
    assertTrue("the file holds the vectors", before.count { it != 0.toByte() } > 100_000)
    val length = path.length()
    index.shred()
    assertEquals(0, index.size)
    assertTrue(path.isFile)
    assertEquals(length, path.length())
    assertTrue("every byte is zero", path.readBytes().all { it == 0.toByte() })
    val gone = LocalData.wipe(indexDir, cacheDir)
    assertFalse(path.exists())
    assertTrue(gone.files >= 1)
    assertEquals(0, indexDir.listFiles()!!.size)
    // The index can be used again afterwards and starts a new file.
    fill(index, rows(10, seed = 26))
    assertEquals(10, index.size)
    assertTrue(path.isFile)
  }

  @Test fun destroyShredsAndDeletes() {
    val index = mapped("gone.bin")
    fill(index, rows(100, seed = 27))
    index.destroy()
    assertFalse(file("gone.bin").exists())
    assertEquals(0, index.size)
  }

  @Test fun clearZeroesTheRowsItDrops() {
    val index = mapped("clear.bin", rowsPerChunk = 100)
    fill(index, rows(300, seed = 28))
    index.clear()
    assertTrue(index.settleNow())
    val bytes = file("clear.bin").readBytes()
    // Past the 4 KB header every dropped row's facts and vector are zero.
    assertTrue(bytes.drop(VectorFile.HEADER).all { it == 0.toByte() })
  }

  // ---- no backing, or a backing that cannot be made ------------------------------------------------------------------

  @Test fun anIndexWithoutAFileStaysOnTheHeapAsBefore() {
    val index = VectorIndex(parallelism = 1)
    fill(index, rows(2_000, seed = 29))
    assertFalse(index.offHeap)
    assertEquals(0L, index.mappedBytes())
    assertTrue(index.heapBytes() > 2_000L * Fp16.DIM * 2)
  }

  @Test fun aFileThatCannotBeMadeFallsBackToTheHeapAndStillAnswersRight() {
    val blocker = file("blocker").also { it.writeText("a file where a directory should be") }
    val data = rows(1_000, seed = 30)
    val legacy = LegacyHeapVectorIndex(parallelism = 1); fillLegacy(legacy, data)
    val index = VectorIndex(parallelism = 1, backing = File(blocker, "vectors.bin"), settleDelayMs = -1).also { open += it }
    fill(index, data)
    assertFalse(index.offHeap)
    assertEquals(1_000, index.size)
    val q = unit(Random(4))
    assertSameBits(legacy.scores(q), index.scores(q))
  }

  // ---- the segment lookup -------------------------------------------------------------------------------------------

  @Test fun theLookupAgreesWithAHashMapUnderRandomPutsAndRemoves() {
    val map = LongIntMap(4)
    val model = HashMap<Long, Int>()
    val random = Random(31)
    repeat(200_000) {
      val key = random.nextLong(1, 3_000)
      when (random.nextInt(3)) {
        0, 1 -> { val v = random.nextInt(0, 1_000_000); map.put(key, v); model[key] = v }
        else -> { map.remove(key); model.remove(key) }
      }
      if (it % 1_000 == 0) {
        assertEquals(model.size, map.size)
        for (k in 1L until 3_000L) assertEquals(model[k] ?: -1, map.get(k))
      }
    }
    map.clear()
    assertEquals(0, map.size)
    assertEquals(-1, map.get(5))
  }

  @Test fun theHeapIsAboutNineteenBytesARowPlusTheLookup() {
    val index = mapped(rowsPerChunk = VectorFile.ROWS_PER_CHUNK)
    fill(index, rows(20_000, seed = 32))
    val perRow = index.heapBytes() / 20_000.0
    assertTrue("heap per row $perRow", perRow in 19.0..80.0)
    assertArrayEquals(intArrayOf(20_000), intArrayOf(index.size))
  }
}
