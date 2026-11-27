package com.craneai.findr.search

import com.craneai.findr.core.FileKind
import com.craneai.findr.index.CrawlRecord
import com.craneai.findr.index.FileState
import com.craneai.findr.index.IndexStore
import com.craneai.findr.index.Modality
import com.craneai.findr.index.Segment
import com.craneai.findr.index.SegmentKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import kotlin.random.Random

class WordSearchHelpersTest {
  @Test fun segmentFileRunsCompressesContiguousRangesAndMatchesOutOfOrderAndInterleavedIds() {
    val runs = SegmentFileRuns(4)
    // File 0: contiguous 100..5000 (1 run)
    for (id in 100L..5_000L) runs.add(id, 0)
    // File 1: interleaved singletons & small bursts out of order
    runs.add(10L, 1); runs.add(12L, 1); runs.add(13L, 1); runs.add(14L, 1)
    // File 2: sits inside the gap at 11 and after 5000
    runs.add(11L, 2); runs.add(6_000L, 2); runs.add(6_001L, 2)
    runs.finish()

    assertTrue("expected tight run compression, got ${runs.runCount}", runs.runCount <= 6)
    assertEquals(-1, runs.fileIdxOf(9L))
    assertEquals(1, runs.fileIdxOf(10L))
    assertEquals(2, runs.fileIdxOf(11L))
    assertEquals(1, runs.fileIdxOf(12L))
    assertEquals(1, runs.fileIdxOf(14L))
    assertEquals(-1, runs.fileIdxOf(15L))
    assertEquals(0, runs.fileIdxOf(100L))
    assertEquals(0, runs.fileIdxOf(2_500L))
    assertEquals(0, runs.fileIdxOf(5_000L))
    assertEquals(-1, runs.fileIdxOf(5_001L))
    assertEquals(2, runs.fileIdxOf(6_000L))
    assertEquals(2, runs.fileIdxOf(6_001L))
    assertEquals(-1, runs.fileIdxOf(6_002L))

    // Randomized parity against a plain HashMap
    val rnd = Random(42)
    val truth = HashMap<Long, Int>()
    val randRuns = SegmentFileRuns(8)
    val ids = (1L..1_500L).shuffled(rnd)
    for (id in ids) {
      val f = rnd.nextInt(0, 25)
      truth[id] = f
      randRuns.add(id, f)
    }
    randRuns.finish()
    for (id in 0L..1_600L) {
      assertEquals("id=$id", truth[id] ?: -1, randRuns.fileIdxOf(id))
    }
  }

  @Test fun snippetBuilderHighlightsStemsPrefixesDiacriticsAndReturnsNullOnNoMatch() {
    val body = "The quarterly café invoices for management were approved on Tuesday morning."
    val s1 = SnippetBuilder.build(
      body,
      listOf(
        SnippetBuilder.MatchGroup(listOf("invoice"), prefix = false),
        SnippetBuilder.MatchGroup(listOf("man"), prefix = true),
        SnippetBuilder.MatchGroup(listOf("cafe"), prefix = false),
      ),
    )
    assertEquals("The quarterly «café» «invoices» for «management» were approved on Tuesday morning.", s1)

    // Non-matching body (e.g. filename-only match) returns null
    val none = SnippetBuilder.build(body, listOf(SnippetBuilder.MatchGroup(listOf("nonexistent"), prefix = false)))
    assertNull(none)
  }

  @Test fun indexStoreMergeFtsAndCloseOptimizeSucceedCleanly() {
    val dir = Files.createTempDirectory("trawl-fts-merge").toFile()
    try {
      val store = IndexStore.open(dir, log = {})
      for (i in 1..15) {
        store.upsertFromCrawl(
          listOf(CrawlRecord("primary", i.toLong(), "file:///d$i.txt", "docs/", "d$i.txt", FileKind.DOCUMENT, null, 100, 100L + i)),
        )
        val fid = store.knownMediaStoreIds("primary").getValue(i.toLong())
        store.saveSegments(fid, listOf(Segment(SegmentKind.CHUNK, Modality.TEXT, text = "quarterly budget report number $i")), FileState.UNDERSTOOD)
      }
      assertTrue(store.mergeFts(64))
      store.close()

      // Reopen cleanly with fastOpen=true and verify sqlite_stat1 / data integrity survived close() optimize
      val reopened = IndexStore.open(dir, log = {}, fastOpen = true)
      try {
        val count = reopened.read { db -> db.long("SELECT COUNT(*) FROM files") ?: 0L }
        assertEquals(15L, count)
      } finally {
        reopened.close()
      }
    } finally {
      dir.deleteRecursively()
    }
  }
}
