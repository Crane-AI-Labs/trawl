package com.craneai.findr.search

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.craneai.findr.core.FileKind
import com.craneai.findr.core.KindFilter
import com.craneai.findr.core.MatchReason
import com.craneai.findr.index.CrawlRecord
import com.craneai.findr.index.FileState
import com.craneai.findr.index.IndexStore
import com.craneai.findr.index.Modality
import com.craneai.findr.index.Segment
import com.craneai.findr.index.SegmentKind
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Verifies parity and SQLite FTS round-trip savings for the keystroke search optimizations in [Searcher]:
 *  1. Single-term coverage fast path (skips redundant `SELECT DISTINCT s.file FROM fts ...` coverage query)
 *  2. Guarded FTS phrase query (skipped when `words` is empty or no file has full coverage)
 *  3. Revision-stamped `isIndexed` LRU cache keyed by [IndexStore.changeCount]
 */
class SearcherTimingsTest {
  private lateinit var dir: File
  private lateinit var driver: CountingDriver
  private lateinit var store: IndexStore
  private lateinit var vectors: VectorIndex
  private lateinit var searcher: Searcher
  private var nextMediaId = 1L

  private class CountingDriver(private val delegate: SQLiteDriver = BundledSQLiteDriver()) : SQLiteDriver {
    val prepares = AtomicInteger()
    val executions = CopyOnWriteArrayList<String>()

    fun reset() {
      prepares.set(0)
      executions.clear()
    }

    override fun open(fileName: String): SQLiteConnection {
      val inner = delegate.open(fileName)
      return object : SQLiteConnection by inner {
        override fun prepare(sql: String): SQLiteStatement {
          prepares.incrementAndGet()
          val stmt = inner.prepare(sql)
          return object : SQLiteStatement by stmt {
            private var stepped = false
            override fun step(): Boolean {
              if (!stepped) {
                stepped = true
                executions.add(sql)
              }
              return stmt.step()
            }
            override fun reset() {
              stepped = false
              stmt.reset()
            }
          }
        }
      }
    }
  }

  @Before fun setUp() {
    dir = Files.createTempDirectory("findr-searcher-timings").toFile()
    driver = CountingDriver()
    store = IndexStore.open(dir, driver = driver, readers = 1, log = {})
    vectors = VectorIndex(parallelism = 1)
    searcher = Searcher(store, vectors)
  }

  @After fun tearDown() {
    vectors.close()
    store.close()
    dir.deleteRecursively()
  }

  private fun add(name: String, text: String, kind: FileKind = FileKind.DOCUMENT): Long {
    val id = nextMediaId++
    store.upsertFromCrawl(listOf(CrawlRecord("external_primary", id, "content://media/external/file/$id", "Documents/", name, kind, null, 2048, 1_000)))
    val fileId = store.knownMediaStoreIds("external_primary").getValue(id)
    store.saveSegments(fileId, listOf(Segment(SegmentKind.CHUNK, Modality.TEXT, text = text)), FileState.UNDERSTOOD)
    return fileId
  }

  private fun ftsExecutions(): List<String> = driver.executions.filter { it.contains("FROM fts") }

  @Test fun singleTermExactMatchSkipsRedundantCoverageQueryAndPreservesParity() {
    add("receipt_jan.pdf", "supermarket receipt total forty thousand", FileKind.PDF)
    add("receipt_feb.pdf", "pharmacy receipt medicine purchase", FileKind.PDF)
    add("notes.txt", "meeting agenda for monday morning")

    // Warm the isIndexed cache once, then measure the exact single-term query.
    val first = searcher.search("receipt", null, KindFilter.ALL, 20, prefixLast = false)
    assertEquals(setOf("receipt_jan.pdf", "receipt_feb.pdf"), first.hits.map { it.file.name }.toSet())
    assertEquals(2, first.strongCount)
    assertTrue(first.hits.all { it.reason == MatchReason.WORDS })

    // Repeating the same query at the same changeCount (ProgressiveSearch Stage 1 -> Stage 2) hits the 1-slot Words cache: 0 FTS queries.
    driver.reset()
    val cached = searcher.search("receipt", null, KindFilter.ALL, 20, prefixLast = false)
    assertEquals(first.hits.map { it.file.id to it.score }, cached.hits.map { it.file.id to it.score })
    assertEquals("expected 0 FTS queries on identical cached words pass", 0, ftsExecutions().size)

    // Evict the 1-slot Words cache with another query while keeping isIndexed warm for "receipt".
    searcher.search("meeting", null, KindFilter.ALL, 20, prefixLast = false)
    driver.reset()
    val second = searcher.search("receipt", null, KindFilter.ALL, 20, prefixLast = false)
    assertEquals(first.hits.map { it.file.id to it.score }, second.hits.map { it.file.id to it.score })

    // Only the single main FTS native-rank query should execute: no isIndexed probe (cached), no coverage query, no phrase query.
    val fts = ftsExecutions()
    assertEquals("expected only the 1 main FTS query, got: $fts", 1, fts.size)
    assertTrue("expected native fts.rank MATCH bm25, got: ${fts[0]}", fts[0].contains("fts.rank MATCH 'bm25(3.0, 1.0)'") && fts[0].contains("ORDER BY fts.rank"))
  }

  @Test fun singleTermPrefixMatchSeparatesWholeWordFromPrefixOnlyWithoutRedundantCoverageQuery() {
    add("toy_box.txt", "children toy box in the bedroom")
    add("toilet_plan.txt", "bathroom toilet renovation plan")

    driver.reset()
    val res = searcher.search("toy", null, KindFilter.ALL, 20, prefixLast = true)
    assertTrue("whole-word hit must be present", res.hits.any { it.file.name == "toy_box.txt" })
    // "toy*" matches "toy" (whole word) and possibly "toilet" (prefix-only via Porter stem "toi").
    // Whole-word match must remain strong.
    assertEquals("toy_box.txt", res.hits.first().file.name)
    assertTrue(res.strongCount >= 1)

    // With single-term fast path: main BM25 query + whole-word disambiguation query = 2 FTS queries (not 3).
    val fts = ftsExecutions()
    assertEquals("expected 2 FTS queries (main + whole-word check), got: $fts", 2, fts.size)
  }

  @Test fun phraseQuerySkippedWhenNoFileHasFullCoverageOrEmptyWords() {
    add("alpha.txt", "alpha document with unique text")
    add("beta.txt", "beta spreadsheet with separate text")

    // 1. Completely missing terms -> empty words -> phrase query MUST be skipped.
    driver.reset()
    val empty = searcher.search("nonexistent phantom", null, KindFilter.ALL, 20, prefixLast = false)
    assertTrue(empty.hits.isEmpty())
    assertFalse("phrase query must be skipped when words is empty", ftsExecutions().any { it.contains("s.kind IN") })

    // 2. Two words that never appear in the same file -> max coverage is 0.5 < 1.0 -> phrase query MUST be skipped.
    driver.reset()
    val split = searcher.search("alpha beta", null, KindFilter.ALL, 20, prefixLast = false)
    assertEquals(setOf("alpha.txt", "beta.txt"), split.hits.map { it.file.name }.toSet())
    assertFalse("phrase query must be skipped when no file has full coverage", ftsExecutions().any { it.contains("s.kind IN") })

    // 3. Add a file with the actual phrase -> coverage reaches 1.0 -> phrase query runs and lifts it to #1!
    add("combined.txt", "the alpha beta release notes")
    driver.reset()
    val withPhrase = searcher.search("alpha beta", null, KindFilter.ALL, 20, prefixLast = false)
    assertEquals("combined.txt", withPhrase.hits.first().file.name)
    assertTrue("phrase query must execute when a file has full coverage", ftsExecutions().any { it.contains("s.kind IN") })
  }

  @Test fun isIndexedCacheSavesProbesWhileTypingAndInvalidatesOnStoreWrite() {
    add("invoice_1.pdf", "monthly invoice from electricity provider", FileKind.PDF)
    add("invoice_2.pdf", "water invoice receipt paid", FileKind.PDF)

    // Simulate typing "invoice r" -> "invoice re" -> "invoice rec" -> "invoice rece" -> "invoice recei"
    driver.reset()
    val keystrokes = listOf("invoice r", "invoice re", "invoice rec", "invoice rece", "invoice recei")
    for (q in keystrokes) {
      val r = searcher.search(q, null, KindFilter.ALL, 20, prefixLast = true)
      assertTrue("expected hits for '$q'", r.hits.isNotEmpty())
    }
    val probes = ftsExecutions().count { it.trim() == "SELECT 1 FROM fts WHERE fts MATCH ? LIMIT 1" }
    assertEquals("already-typed word 'invoice' must only be probed once across all 5 keystrokes", 1, probes)

    // Now test cache invalidation when a write adds a previously unindexed word ("grok" vs "grok4").
    add("bench_doc.txt", "notes about grok4 benchmarks")
    val beforeWrite = searcher.search("grok", null, KindFilter.ALL, 20, prefixLast = false)
    assertEquals("unindexed 'grok' falls back to prefix and finds 'grok4'", listOf("bench_doc.txt"), beforeWrite.hits.map { it.file.name })

    // Add a file containing whole word "grok" -> store.changeCount advances -> isIndexed cache clears!
    add("exact_grok.txt", "exact grok word appears here")
    val afterWrite = searcher.search("grok", null, KindFilter.ALL, 20, prefixLast = false)
    assertEquals("after write, 'grok' is indexed as whole word and no longer widens to 'grok4'", listOf("exact_grok.txt"), afterWrite.hits.map { it.file.name })
  }

  @Test fun keystrokeSearchBenchmark() {
    repeat(200) { i ->
      add("doc_$i.txt", "quarterly budget report section $i with invoice summary and receipt details")
    }
    val queries = listOf("invoice", "invoice r", "invoice re", "invoice rec", "invoice rece", "invoice recei", "invoice receipt")
    // Warm up
    repeat(3) { queries.forEach { searcher.search(it, null, KindFilter.ALL, 20, prefixLast = true) } }

    driver.reset()
    val t0 = System.nanoTime()
    val rounds = 20
    repeat(rounds) {
      for (q in queries) {
        val r = searcher.search(q, null, KindFilter.ALL, 20, prefixLast = true)
        assertTrue(r.hits.isNotEmpty())
      }
    }
    val elapsedMs = (System.nanoTime() - t0) / 1_000_000.0
    val totalSearches = rounds * queries.size
    val avgMs = elapsedMs / totalSearches
    val avgPrepares = driver.prepares.get().toDouble() / totalSearches
    val avgFtsQueries = ftsExecutions().size.toDouble() / totalSearches
    println("BENCH: Searcher keystroke sequence ($totalSearches searches, 200 docs): avg=${"%.3f".format(avgMs)}ms, avgPrepares=${"%.2f".format(avgPrepares)}, avgFtsQueries=${"%.2f".format(avgFtsQueries)}")
    // Statement cache + isIndexed cache + single-term fast path should keep prepares tiny (< 2/search across repeated queries).
    assertTrue("expected < 2.0 prepares/search with statement cache, got $avgPrepares", avgPrepares < 2.0)
  }
}
