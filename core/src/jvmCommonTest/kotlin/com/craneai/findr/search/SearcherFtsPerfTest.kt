package com.craneai.findr.search

import com.craneai.findr.core.FileKind
import com.craneai.findr.core.KindFilter
import com.craneai.findr.index.CrawlRecord
import com.craneai.findr.index.FileState
import com.craneai.findr.index.IndexStore
import com.craneai.findr.index.Modality
import com.craneai.findr.index.Segment
import com.craneai.findr.index.SegmentKind
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearcherFtsPerfTest {
  private fun withStore(block: (IndexStore, VectorIndex, Searcher) -> Unit) {
    val dir = Files.createTempDirectory("findr-fts-perf").toFile()
    try {
      val store = IndexStore.open(dir)
      val vectors = VectorIndex(parallelism = 1)
      val searcher = Searcher(store, vectors)
      try {
        block(store, vectors, searcher)
      } finally {
        vectors.close()
        store.close()
      }
    } finally {
      dir.deleteRecursively()
    }
  }

  private fun addDoc(store: IndexStore, id: Long, name: String, body: String): Long {
    store.upsertFromCrawl(
      listOf(
        CrawlRecord("disk", id, "file:///$name", "Docs/", name, FileKind.DOCUMENT, "text/plain", body.length.toLong(), 1_000L + id, dateMillis = 1_000L + id)
      )
    )
    val fid = store.knownMediaStoreIds("disk").getValue(id)
    store.saveSegments(fid, listOf(Segment(SegmentKind.CHUNK, Modality.NONE, text = body)), FileState.SEARCHABLE)
    return fid
  }

  @Test fun twoLetterPrefixDoesNotWildcardWhileThreeLetterPrefixStillMatches() = withStore { store, _, searcher ->
    addDoc(store, 1, "manual.txt", "operating manual for machinery and markdown")
    addDoc(store, 2, "yo_note.txt", "short greeting yo from a friend")
    addDoc(store, 3, "young_man.txt", "portrait of a young man standing by the river")

    // 2-letter typing ("yo", "young ma") must match exact 2-letter tokens only, not wildcard-expand "yo"* / "ma"*.
    val twoLetter = searcher.search("yo", null, KindFilter.ALL, 20, prefixLast = true)
    assertEquals(listOf("yo_note.txt"), twoLetter.hits.map { it.file.name })

    val youngMa = searcher.search("young ma", null, KindFilter.ALL, 20, prefixLast = true)
    assertTrue("young_man.txt" in youngMa.hits.map { it.file.name })
    assertFalse("2-letter 'ma' must not wildcard-expand to 'manual'", "manual.txt" in youngMa.hits.map { it.file.name })

    // 3-letter prefix ("you", "young man") continues to match prefixes as expected.
    val threeLetter = searcher.search("you", null, KindFilter.ALL, 20, prefixLast = true)
    assertTrue("young_man.txt" in threeLetter.hits.map { it.file.name })

    val youngMan = searcher.search("young man", null, KindFilter.ALL, 20, prefixLast = true)
    assertEquals("young_man.txt", youngMan.hits.first().file.name)
    assertTrue("3-letter 'man'* still reaches 'manual'", "manual.txt" in youngMan.hits.map { it.file.name })
  }

  @Test fun wordsCacheReusesStageOneAndInvalidatesOnWrite() = withStore { store, _, searcher ->
    addDoc(store, 1, "river.txt", "young man by the river")
    val first = searcher.search("young man", null, KindFilter.ALL, 20, prefixLast = true)
    assertEquals(listOf("river.txt"), first.hits.map { it.file.name })

    // Repeating with same changeCount hits the 1-slot Words cache with identical results.
    val second = searcher.search("young man", null, KindFilter.ALL, 20, prefixLast = true)
    assertEquals(listOf("river.txt"), second.hits.map { it.file.name })

    // Writing a new matching file bumps store.changeCount and invalidates the cache immediately.
    addDoc(store, 2, "young_man_2.txt", "another young man walking")
    val third = searcher.search("young man", null, KindFilter.ALL, 20, prefixLast = true)
    assertEquals(setOf("river.txt", "young_man_2.txt"), third.hits.map { it.file.name }.toSet())
  }
}
