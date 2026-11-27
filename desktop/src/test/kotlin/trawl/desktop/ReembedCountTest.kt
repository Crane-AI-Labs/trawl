package trawl.desktop

import com.craneai.findr.core.FileKind
import com.craneai.findr.index.CrawlRecord
import com.craneai.findr.index.IndexStore
import com.craneai.findr.index.Schema
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * 1.0.3 worked the "updating for the new model" count out on every status, and a paused computer works the status out every 15 s:
 * on a 180k-file index that was 4.9 CPU-s a minute for nothing, because the desktop never queues a re-embed. The count must cost
 * nothing when nothing was queued, and stop once it has found the queue empty.
 */
class ReembedCountTest {
  private val dir: File = Files.createTempDirectory("trawl-reembed").toFile()
  private val store: IndexStore = IndexStore.open(dir)
  private var clock = 0L

  @AfterTest fun close() { store.close(); dir.deleteRecursively() }

  private fun addSearchable(n: Int) {
    store.upsertFromCrawl(List(n) { i -> CrawlRecord("desktop", i + 1L, "file:///t/f$i.txt", "t/", "f$i.txt", FileKind.DOCUMENT, null, 1000, 1L) })
  }

  private fun counter() = ReembedCount(
    4_000, { store.meta(Schema.Meta.REEMBED_TOTAL)?.toIntOrNull() ?: 0 }, { store.reembedProgress() }, now = { clock },
  )

  private fun markStale(ids: Int) = store.write { db ->
    db.run("INSERT INTO segments(file, kind, modality, late) SELECT id, 'chunk', 'image', 2 FROM files WHERE id <= ?", ids.toLong())
  }

  @Test fun anIndexThatQueuedNothingIsNeverCounted() {
    addSearchable(2_000)
    val c = counter()
    repeat(200) { clock += 15_000; assertNull(c.of(setOf(FileKind.DOCUMENT))) }
    assertEquals(0, c.scans, "200 paused publishes cost no scan of the segments")
  }

  @Test fun aQueueThatWasSetIsCountedUntilItIsEmptyAndThenNeverAgain() {
    addSearchable(10)
    markStale(4)
    store.setMeta(Schema.Meta.REEMBED_TOTAL, "4")
    val c = counter()
    val first = c.of(setOf(FileKind.DOCUMENT))
    assertNotNull(first)
    assertEquals(0 to 4, first)
    clock += 1_000
    c.of(setOf(FileKind.DOCUMENT))
    assertEquals(1, c.scans, "within the gap the last answer stands")
    clock += 4_000
    c.of(setOf(FileKind.DOCUMENT))
    assertEquals(2, c.scans)
    store.write { db -> db.run("UPDATE segments SET late = 0") }
    clock += 4_000
    assertNull(c.of(setOf(FileKind.DOCUMENT)))
    val scans = c.scans
    repeat(50) { clock += 15_000; assertNull(c.of(setOf(FileKind.DOCUMENT))) }
    assertEquals(scans, c.scans, "once the queue is found empty it is not counted again")
    c.reset()
    clock += 4_000
    c.of(setOf(FileKind.DOCUMENT))
    assertEquals(scans + 1, c.scans, "a new model starts the count afresh")
  }
}
