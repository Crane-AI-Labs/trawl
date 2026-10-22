package com.craneai.findr.index

import com.craneai.findr.core.FileKind
import com.craneai.findr.core.QuickLookProgress
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The quick-look meter must end: a file quick look can't decode leaves the queue, so it has to leave the meter's denominator too
 * (the 1,580-file corpus stuck at 1,141 of 1,166 on the TECNO KN3 and the Samsung tablet), and a file EmbeddingGemma gives up on
 * is a final state that stays findable by name, so "searchable" must not fall when it moves to failed.
 */
class QuickLookMeterTest {
  private lateinit var dir: File
  private lateinit var store: IndexStore

  @Before fun setUp() {
    dir = Files.createTempDirectory("findr-meter").toFile()
    store = IndexStore.open(dir, log = {})
  }

  @After fun tearDown() {
    store.close()
    dir.deleteRecursively()
  }

  private var nextId = 1L
  private fun add(name: String, kind: FileKind = FileKind.IMAGE): Long {
    val id = nextId++
    store.upsertFromCrawl(listOf(CrawlRecord("external_primary", id, "content://media/external/file/$id", "DCIM/", name, kind, null, 50_000, 1_000)))
    return store.knownMediaStoreIds("external_primary").getValue(id)
  }

  private fun quick() = FloatArray(IndexStore.QUICK_DIM) { if (it == 0) 1f else 0f }
  private fun eg() = FloatArray(Fp16.DIM) { if (it == 0) 1f else 0f }
  private val visual = setOf(FileKind.IMAGE, FileKind.SCREENSHOT, FileKind.VIDEO)
  private fun meter() = store.stats().quickLookProgress(visual)

  @Test fun filesQuickLookCantDecodeLeaveTheQueueAndTheMeterReachesItsEnd() {
    add("a.jpg"); add("b.jpg"); add("bad.tga"); add("bad.wmv", FileKind.VIDEO)
    assertEquals(4, store.nextForQuickLook(100, visual).size)
    for (p in store.nextForQuickLook(100, visual)) {
      val unreadable = p.file.name.startsWith("bad")
      store.saveQuick(p.file.id, if (p.file.kind == FileKind.VIDEO) SegmentKind.FRAME else SegmentKind.IMAGE, if (unreadable) null else quick())
    }
    assertEquals(emptyList<String>(), store.nextForQuickLook(100, visual).map { it.file.name })
    val m = meter()!!
    assertEquals(QuickLookProgress(known = 2, ready = 2, full = 0, unreadable = 2), m)
    assertEquals("quick look is done: every file it can read has its vector", m.known, m.ready)
  }

  @Test fun anUnreadableFileThatEmbeddingGemmaThenGivesUpOnIsCountedOnceAndStaysSearchable() {
    val a = add("a.jpg"); val bad = add("bad.jpg")
    store.saveQuick(a, SegmentKind.IMAGE, quick()); store.saveQuick(bad, SegmentKind.IMAGE, null)
    assertEquals(2, store.stats().filesSearchable)
    store.setState(bad, FileState.FAILED, "this phone can't open this format")
    val after = store.stats()
    assertEquals("a failed file is still found by name", 2, after.filesSearchable)
    assertEquals(1, after.filesUnreadable)
    assertEquals(QuickLookProgress(known = 1, ready = 1, full = 0, unreadable = 1), after.quickLookProgress(visual))
  }

  @Test fun aFileWithAVectorThatFailsLaterLeavesBothSidesOfTheMeter() {
    val a = add("a.jpg"); val b = add("b.jpg")
    store.saveQuick(a, SegmentKind.IMAGE, quick()); store.saveQuick(b, SegmentKind.IMAGE, quick())
    store.setState(b, FileState.FAILED, "took too long")
    assertEquals(QuickLookProgress(known = 1, ready = 1, full = 0, unreadable = 1), meter())
  }

  @Test fun understoodAndSkippedFilesAreNotUnreadable() {
    val a = add("a.jpg"); val bad = add("bad.jpg"); val s = add("skip.jpg")
    store.saveQuick(bad, SegmentKind.IMAGE, null)
    store.setState(s, FileState.SKIPPED, "too small")
    store.saveSegments(a, listOf(Segment(SegmentKind.IMAGE, Modality.IMAGE, vector = eg())), FileState.UNDERSTOOD)
    assertEquals(QuickLookProgress(known = 1, ready = 1, full = 1, unreadable = 1), meter())
  }

  @Test fun nothingVisualMeansNoMeter() {
    add("doc.txt", FileKind.DOCUMENT)
    assertEquals(null, meter())
  }

  @Test fun filesTheCrawlSawButNothingHasReadYetAreNotUnreadable() {
    add("new.jpg")
    assertEquals(QuickLookProgress(known = 1, ready = 0, full = 0, unreadable = 0), meter())
  }
}
