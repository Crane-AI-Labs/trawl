package com.craneai.findr.core

import com.craneai.findr.core.StorageEstimate.KindTotals
import com.craneai.findr.search.VectorIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageEstimateTest {
  private val all = FileKind.entries.toSet()

  private fun est(vararg t: KindTotals, indexed: Set<FileKind> = all) = StorageEstimate.estimate(t.toList(), indexed)

  @Test fun nothingKnownMeansOnlyTheRowFactsAtTheirStartingSize() {
    val r = est()
    assertEquals(0L, r.indexBytes)
    assertEquals(0L, r.vectors)
    // Empty indexes have no vector file: 1,024 and 256 rows of facts (19 bytes) and a 32-slot lookup (12 bytes a slot) each.
    assertEquals((1_024 + 256) * 19L + 2 * 32 * 12L, r.ramBytes)
  }

  @Test fun aPhotoCostsItsMeasuredShareAndOneVector() {
    val r = est(KindTotals(FileKind.IMAGE, 1_000, 3_000_000_000, 0))
    // The database share, and the vector file (a header and one 4,096-row chunk of 24 + 1,536 bytes a row).
    assertEquals(3_800_000L + 4_096 + 4_096 * 1_560L, r.indexBytes)
    assertEquals(1_000L, r.vectors)
  }

  @Test fun screenshotsCostTheSameAsPhotos() {
    assertEquals(est(KindTotals(FileKind.IMAGE, 10, 1, 0)).indexBytes, est(KindTotals(FileKind.SCREENSHOT, 10, 1, 0)).indexBytes)
  }

  @Test fun theMeasuredInfinixIndexIsReproducedWithinAFewPercent() {
    // 1,306,624 bytes on disk, 2026-10-15; the crawl totals are the rows of that index (skipped files left out).
    val r = est(
      KindTotals(FileKind.IMAGE, 116, 36_059_968, 0),
      KindTotals(FileKind.SCREENSHOT, 48, 7_497_348, 0),
      KindTotals(FileKind.DOCUMENT, 58, 59_515, 0),
      KindTotals(FileKind.PDF, 4, 86_144, 0),
      KindTotals(FileKind.VIDEO, 9, 357_109_501, 655_362),
      KindTotals(FileKind.OTHER, 15, 1_727_088, 0),
    )
    val measured = 1_306_624.0
    val vectorFile = 4_096.0 + 4_096 * 1_560   // 362 vectors still take a whole chunk
    assertEquals(measured, r.indexBytes - vectorFile, measured * 0.06)
  }

  @Test fun theEstimateMatchesWhatARealVectorIndexHolds() {
    // 5,000 vectors in a real mapped index: the estimate is the search memory it reports (heap facts + the mapped file), and
    // the same file is part of the disk figure. (Before the vectors left the heap, the Infinix measured 1,832 KB for 362.)
    val dir = java.nio.file.Files.createTempDirectory("estimate").toFile()
    try {
      val index = VectorIndex(parallelism = 1, backing = java.io.File(dir, "v.bin"), settleDelayMs = -1)
      val v = FloatArray(768) { 0.01f }
      for (i in 1..5_000) index.add(i.toLong(), i.toLong(), FileKind.IMAGE, com.craneai.findr.index.Modality.IMAGE, false, v)
      val r = est(KindTotals(FileKind.IMAGE, 5_000, 0, 0))
      val quickEmpty = 256 * 19L + 32 * 12L
      assertEquals(index.memoryBytes() + quickEmpty, r.ramBytes)
      assertEquals(index.mappedBytes(), java.io.File(dir, "v.bin").length())
      assertEquals(5_000 * StorageEstimate.IMAGE_BYTES + index.mappedBytes(), r.indexBytes)
      index.close()
    } finally { dir.deleteRecursively() }
  }

  @Test fun aTextDocumentIsChunksOfAboutFourHundredBytesRoundedUp() {
    // 10 documents of 1,000 bytes: 3 chunks each (2.5 rounded up) at 3.5 KB.
    val r = est(KindTotals(FileKind.DOCUMENT, 10, 10_000, 0))
    assertEquals(30L, r.vectors)
    assertEquals(30 * StorageEstimate.DOC_CHUNK_BYTES + 4_096 + 4_096 * 1_560L, r.indexBytes)
  }

  @Test fun aPdfMakesFewerChunksThanItsSizeSuggests() {
    // 4 PDFs of 21.5 KB: 3 chunks each, not 54.
    assertEquals(12L, est(KindTotals(FileKind.PDF, 4, 86_144, 0)).vectors)
  }

  @Test fun everyDocumentMakesAtLeastOneChunkAndNoHugeFileRunsAway() {
    assertEquals(3L, est(KindTotals(FileKind.PDF, 3, 300, 0)).vectors)
    assertEquals(2L * StorageEstimate.DOC_MAX_CHUNKS, est(KindTotals(FileKind.PDF, 2, 2_000_000_000L, 0)).vectors)
  }

  @Test fun videoCostsItsRowPlusSevenKilobytesAMinuteAndThreeVectors() {
    val r = est(KindTotals(FileKind.VIDEO, 2, 500_000_000, 10 * 60_000L))
    assertEquals(2 * StorageEstimate.NAME_ONLY_BYTES + 71_000L + 4_096 + 4_096 * 1_560L, r.indexBytes)
    assertEquals(30L, r.vectors)
  }

  @Test fun kindsSwitchedOffCostOnlyTheirNames() {
    val r = est(KindTotals(FileKind.AUDIO, 5, 1_000_000, 3_600_000L), indexed = setOf(FileKind.IMAGE))
    assertEquals(5 * StorageEstimate.NAME_ONLY_BYTES, r.indexBytes)
    assertEquals(0L, r.vectors)
  }

  @Test fun otherFilesAreOnlyKnownByName() {
    assertEquals(7 * StorageEstimate.NAME_ONLY_BYTES, est(KindTotals(FileKind.OTHER, 7, 9_999, 0)).indexBytes)
  }

  @Test fun theVectorFileGrowsAWholeChunkAtATime() {
    val full = est(KindTotals(FileKind.IMAGE, 4_096, 0, 0)).ramBytes
    val next = est(KindTotals(FileKind.IMAGE, 4_097, 0, 0)).ramBytes
    // One more vector than a chunk holds adds a whole chunk to the file.
    assertTrue(next >= full + 4_096 * 1_560L)
  }

  @Test fun aMixedPhoneAddsUp() {
    val r = est(
      KindTotals(FileKind.IMAGE, 20_000, 0, 0),
      KindTotals(FileKind.SCREENSHOT, 3_000, 0, 0),
      KindTotals(FileKind.DOCUMENT, 400, 400 * 24_000L, 0),
      KindTotals(FileKind.VIDEO, 150, 0, 150 * 60_000L),
    )
    val database = 23_000 * 3_800L + 400 * 60 * 3_500L + 150 * 600L + 150 * 7_100L
    val vectors = 23_000L + 400 * 60 + 450
    assertEquals(vectors, r.vectors)
    val file = 4_096 + ((vectors + 4_095) / 4_096) * 4_096 * 1_560L
    assertEquals(database + file, r.indexBytes)
    assertTrue(r.ramBytes > file)
  }
}
