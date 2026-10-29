package trawl.desktop

import com.craneai.findr.core.FileKind
import com.craneai.findr.index.CrawlRecord
import com.craneai.findr.index.FileState
import com.craneai.findr.index.Fp16
import com.craneai.findr.index.IndexStore
import com.craneai.findr.index.Modality
import com.craneai.findr.index.Segment
import com.craneai.findr.index.SegmentKind
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The counts the status needs are kept until a write finishes, never longer, and the batched delete takes rows, words and vectors together. */
class StatsCacheTest {
  private val dir: File = Files.createTempDirectory("trawl-statscache").toFile()
  private val store: IndexStore = IndexStore.open(dir)
  private var next = 1L

  @AfterTest fun close() { store.close(); dir.deleteRecursively() }

  private fun add(name: String, kind: FileKind = FileKind.DOCUMENT): Long {
    val n = next++
    store.upsertFromCrawl(listOf(CrawlRecord("desktop", n, "file:///t/$name", "t/", name, kind, null, 1000, 1L)))
    return store.read { db -> db.long("SELECT id FROM files WHERE name = ?", name) }!!
  }

  private fun read(id: Long, chunks: Int, vectors: Boolean) = store.saveSegments(
    id,
    List(chunks) { i ->
      Segment(
        SegmentKind.CHUNK, if (vectors) Modality.TEXT else Modality.NONE, text = "alpha beta $id $i",
        vector = if (vectors) FloatArray(Fp16.DIM) { if (it == i % Fp16.DIM) 1f else 0f } else null,
      )
    },
    FileState.UNDERSTOOD,
  )

  private fun ftsRows(): Long = store.read { it.long("SELECT COUNT(*) FROM fts_docsize") ?: 0L }

  @Test fun theCountsAreWorkedOutOnceWhileTheIndexIsQuietAndAgainAfterAWrite() {
    add("a.txt"); add("b.txt")
    val runs = AtomicInteger()
    val probe = { store.cached("probe") { db -> runs.incrementAndGet(); db.long("SELECT COUNT(*) FROM files") } }
    val first = probe()
    repeat(50) { assertEquals(first, probe()) }
    assertEquals(1, runs.get(), "50 asks while nothing was written cost no query")
    add("c.txt")
    val after = probe()
    assertEquals(2, runs.get())
    assertEquals(3L, after); assertEquals(2L, first)
  }

  @Test fun statsAreNeverStaleAfterAWriteWhateverItWas() {
    val a = add("a.txt"); val b = add("b.txt")
    assertEquals(0, store.stats().segments)
    assertEquals(2, store.waiting(setOf(FileKind.DOCUMENT)))
    read(a, 3, vectors = true)
    var s = store.stats()
    assertEquals(1, s.count(FileKind.DOCUMENT, FileState.UNDERSTOOD)); assertEquals(3, s.segments); assertEquals(3, s.vectors)
    assertEquals(1, store.fileStats().count(FileKind.DOCUMENT, FileState.UNDERSTOOD))
    store.setState(b, FileState.FAILED, "x")
    s = store.stats()
    assertEquals(1, s.count(FileKind.DOCUMENT, FileState.FAILED))
    assertEquals(0, store.waiting(setOf(FileKind.DOCUMENT)), "one understood, one failed: nothing waits")
    store.markDeleted(listOf(a))
    s = store.stats()
    assertEquals(0, s.segments); assertEquals(0, s.vectors); assertEquals(0, s.count(FileKind.DOCUMENT, FileState.UNDERSTOOD))
  }

  @Test fun fileStatsIsTheCheapHalfOfStatsAndAgreesWithItOnTheFileCounts() {
    val a = add("a.txt"); add("b.pdf", FileKind.PDF)
    read(a, 2, vectors = false)
    val f = store.fileStats(); val s = store.stats()
    assertEquals(s.byKindAndState, f.byKindAndState)
    assertEquals(0, f.segments, "the heavy counts are not part of the cheap half")
    assertEquals(2, s.segments)
    assertEquals(s.waiting(FileKind.entries.toSet()), f.waiting(FileKind.entries.toSet()))
  }

  @Test fun aBatchDeleteTakesRowsWordsAndVectorsAndCountsFilesNotCascadeRows() {
    val ids = List(30) { add("f$it.txt") }
    ids.forEachIndexed { i, id -> read(id, 4, vectors = i % 2 == 0) }
    val words = ftsRows()
    assertTrue(words >= 30 * 4, "chunk words are in the keyword index: $words")
    val gone = ids.take(20)
    assertEquals(20, store.markDeleted(gone, checkpoint = false), "files, not the segments that went with them")
    assertEquals(0, store.markDeleted(gone, checkpoint = false), "dropping what is already gone counts nothing")
    val s = store.stats()
    assertEquals(10, s.count(FileKind.DOCUMENT, FileState.UNDERSTOOD))
    assertEquals(10 * 4, s.segments)
    assertEquals(5 * 4, s.vectors, "the ten kept files: every second one has vectors, 4 each")
    assertTrue(ftsRows() < words, "the words of the dropped files left the keyword index")
    val list = gone.joinToString()
    assertEquals(0L, store.read { it.long("SELECT COUNT(*) FROM files WHERE id IN ($list)") })
    assertEquals(0L, store.read { it.long("SELECT COUNT(*) FROM segments WHERE file IN ($list)") })
    assertEquals(10, store.markDeleted(ids.drop(20)))   // the default still checkpoints
  }
}
