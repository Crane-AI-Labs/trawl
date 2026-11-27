package com.craneai.findr.search

import com.craneai.findr.core.FileKind
import com.craneai.findr.core.KindFilter
import com.craneai.findr.index.CrawlRecord
import com.craneai.findr.index.FileState
import com.craneai.findr.index.IndexStore
import com.craneai.findr.index.Segment
import com.craneai.findr.index.SegmentKind
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SeesSearchTest {
  private lateinit var directory: File
  private lateinit var store: IndexStore
  private lateinit var vectors: VectorIndex
  private lateinit var searcher: Searcher
  private var nextId = 0L

  @Before fun setup() {
    directory = Files.createTempDirectory("sees-search").toFile()
    store = IndexStore.open(directory, log = {})
    vectors = VectorIndex(parallelism = 1)
    searcher = Searcher(store, vectors)
  }

  @After fun cleanup() {
    vectors.close()
    store.close()
    directory.deleteRecursively()
  }

  private fun add(kind: FileKind, vararg segments: Segment): Long {
    val mediaId = ++nextId
    store.upsertFromCrawl(listOf(CrawlRecord("test", mediaId, "content://test/$mediaId", "", "unnamed$mediaId", kind, null, 100, 1000)))
    val id = store.knownMediaStoreIds("test").getValue(mediaId)
    store.saveSegments(id, segments.toList(), FileState.UNDERSTOOD)
    return id
  }

  private fun search(query: String, filter: KindFilter = KindFilter.ALL, prefixLast: Boolean = false) = searcher.search(query, null, filter, 200, prefixLast)

  @Test fun onlyVideoHintsAllowSeesWordsNotAVideoFilterAlone() {
    val video = add(FileKind.VIDEO, Segment(SegmentKind.SEES, text = "Sees: trees, church"))
    assertTrue(search("trees").hits.isEmpty())
    assertTrue(search("picture of trees").hits.isEmpty())
    assertTrue(search("trees", KindFilter.VIDEOS).hits.isEmpty())
    for (kind in listOf("video", "videos", "clip", "clips", "movie", "movies")) {
      val hit = search("$kind trees").hits.single()
      assertEquals(video, hit.file.id)
      assertNull(hit.snippet)
      assertNull(hit.locator)
    }
    assertTrue(search("video trees", KindFilter.PHOTOS).hits.isEmpty())
  }

  @Test fun sharedWordCapAndScoresAreUnaffectedByManySeesRows() {
    repeat(120) { add(FileKind.DOCUMENT, Segment(SegmentKind.CHUNK, text = "trees church ordinary passage $it")) }
    val videos = List(150) { add(FileKind.VIDEO) }
    val before = search("trees church").hits.map { it.file.id to it.score }
    for (video in videos) store.saveSegments(video, listOf(Segment(SegmentKind.SEES, text = "Sees: trees, church")), FileState.UNDERSTOOD)
    assertEquals(before, search("trees church").hits.map { it.file.id to it.score })
    assertEquals(100, before.size)
    assertFalse(search("video trees church").hits.isEmpty())
  }

  @Test fun ordinaryPrefixFallbackNeverSeesTheHeadsVocabulary() {
    val document = add(FileKind.DOCUMENT, Segment(SegmentKind.CHUNK, text = "treeside"))
    val before = search("trees").hits.map { it.file.id to it.score }
    add(FileKind.VIDEO, Segment(SegmentKind.SEES, text = "Sees: trees"))
    assertEquals(listOf(document), before.map { it.first })
    assertEquals(before, search("trees").hits.map { it.file.id to it.score })
  }

  @Test fun ordinaryAndSeesWordsCountOnceAndNeverGetPhraseBonus() {
    val ordinary = add(FileKind.VIDEO, Segment(SegmentKind.OCR, text = "trees"))
    val duplicated = add(FileKind.VIDEO, Segment(SegmentKind.OCR, text = "trees"), Segment(SegmentKind.SEES, text = "Sees: trees"))
    val hits = search("video trees").hits.associate { it.file.id to it.score }
    assertEquals(hits.getValue(ordinary), hits.getValue(duplicated), 0.0)
    val seesOnly = add(FileKind.VIDEO, Segment(SegmentKind.SEES, text = "Sees: church choir"))
    val phrase = add(FileKind.VIDEO, Segment(SegmentKind.OCR, text = "video church choir"))
    val phrases = search("video church choir").hits.associate { it.file.id to it.score }
    assertTrue(phrases.getValue(phrase) > phrases.getValue(seesOnly))
  }

  @Test fun nonVideoRowsAndDisabledVideosNeverContributeSeesWords() {
    add(FileKind.IMAGE, Segment(SegmentKind.SEES, text = "Sees: trees"))
    val video = add(FileKind.VIDEO, Segment(SegmentKind.SEES, text = "Sees: trees"))
    store.setState(video, FileState.SKIPPED, "off")
    assertTrue(search("video trees").hits.isEmpty())
  }
}
