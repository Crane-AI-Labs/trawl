package trawl.desktop

import com.craneai.findr.core.FileKind
import com.craneai.findr.index.CrawlRecord
import com.craneai.findr.index.FileState
import com.craneai.findr.index.IndexStore
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The detail panel's numbers and the one-time clean-up, against a real temporary index. */
class IndexAdminTest {
  private val dir: File = Files.createTempDirectory("trawl-admin").toFile()
  private val store: IndexStore = IndexStore.open(dir)
  private var next = 1L

  @AfterTest fun close() { store.close(); dir.deleteRecursively() }

  private fun add(name: String, kind: FileKind, size: Long = 1000, state: FileState? = null, detail: String? = null): Long {
    val n = next++
    store.upsertFromCrawl(listOf(CrawlRecord("desktop", n, "file:///t/$name", "t/", name, kind, null, size, 1L)))
    val id = store.read { db -> db.long("SELECT id FROM files WHERE name = ?", name) }!!
    if (state != null) store.setState(id, state, detail)
    return id
  }

  private fun stateOf(name: String): Pair<String?, String?> = store.read { db ->
    var r: Pair<String?, String?> = null to null
    db.query("SELECT state, detail FROM files WHERE name = ?", name) { r = it.getText(0) to (if (it.isNull(1)) null else it.getText(1)) }
    r
  }

  @Test fun theStoredDetailsBecomeReasonsAPersonCanRead() {
    assertEquals(IndexAdmin.FailReason.TOO_BIG, IndexAdmin.failReason(DocFormats.Why.TOO_BIG.detail))
    assertEquals(IndexAdmin.FailReason.TOO_BIG, IndexAdmin.failReason(IndexStore.TOO_LARGE_DETAIL))
    assertEquals(IndexAdmin.FailReason.NOT_TEXT, IndexAdmin.failReason(DocFormats.Why.BINARY.detail))
    assertEquals(IndexAdmin.FailReason.DAMAGED, IndexAdmin.failReason(DocFormats.Why.DAMAGED.detail))
    assertEquals(IndexAdmin.FailReason.CANT_OPEN, IndexAdmin.failReason(IndexStore.CANT_OPEN))
    assertEquals(IndexAdmin.FailReason.PICTURE, IndexAdmin.failReason("unreadable picture"))
    assertEquals(IndexAdmin.FailReason.TOO_LONG, IndexAdmin.failReason("took too long"))
    // An exception's name or message is never shown: it can carry a file name.
    assertEquals(IndexAdmin.FailReason.OTHER, IndexAdmin.failReason("IOException: /home/me/secret.docx"))
    assertEquals(IndexAdmin.FailReason.OTHER, IndexAdmin.failReason(null))
    assertEquals(IndexAdmin.FailReason.entries.size, IndexAdmin.FailReason.entries.map { it.key }.toSet().size)
  }

  @Test fun theDetailCountsKindsWaitingFailuresAndSizes() {
    add("a.txt", FileKind.DOCUMENT, 2_000, FileState.UNDERSTOOD)
    add("b.txt", FileKind.DOCUMENT, 3_000)                                      // new: waiting
    add("c.docx", FileKind.DOCUMENT, 4_000, FileState.FAILED, DocFormats.Why.DAMAGED.detail)
    add("d.pdf", FileKind.PDF, 5_000, FileState.FAILED, DocFormats.Why.TOO_BIG.detail)
    add("e.pdf", FileKind.PDF, 6_000, FileState.FAILED, IndexStore.TOO_LARGE_DETAIL)
    add("f.jpg", FileKind.IMAGE, 7_000, FileState.SEARCHABLE)
    add("g.jpg", FileKind.IMAGE, 8_000, FileState.SKIPPED)                      // left out of everything
    val d = IndexAdmin.detail(store, setOf(FileKind.DOCUMENT, FileKind.PDF, FileKind.IMAGE), modelBytes = 123, withMeaning = true)

    val docs = d.rows.first { it.kind == FileKind.DOCUMENT }
    assertEquals(3, docs.known); assertEquals(1, docs.understood); assertEquals(1, docs.waiting); assertEquals(1, docs.failed); assertEquals(9_000, docs.bytes)
    assertEquals(1, d.rows.first { it.kind == FileKind.IMAGE }.known, "the skipped photo is not counted")
    assertTrue(d.rows.none { it.kind == FileKind.VIDEO }, "kinds with nothing in the index are not listed")
    assertEquals(6, d.known)
    assertEquals(1, d.understood)
    assertEquals(2, d.waiting)
    assertEquals(3, d.failedTotal)
    val toobig = d.failed.first { it.reason == IndexAdmin.FailReason.TOO_BIG }
    assertEquals(2, toobig.count); assertEquals(setOf(FileKind.PDF), toobig.kinds)
    assertEquals(IndexAdmin.FailReason.TOO_BIG, d.failed.first().reason, "biggest group first")
    assertNotNull(toobig.example)
    assertEquals(123L, d.modelBytes)
    val est = assertNotNull(d.estimate)
    assertTrue(est.indexBytes > 0)
    assertTrue(d.growthBytes >= 0)
  }

  @Test fun anOffKindIsOutOfTheTotalsButStillListed() {
    add("a.txt", FileKind.DOCUMENT)
    add("f.jpg", FileKind.IMAGE)
    val d = IndexAdmin.detail(store, setOf(FileKind.DOCUMENT), null, withMeaning = false)
    assertEquals(1, d.known)
    assertEquals(2, d.rows.size)
  }

  @Test fun wordsInIsEveryFileLessTheOnesTheWordsPassHasNotReached() {
    add("a.txt", FileKind.DOCUMENT, state = FileState.SEARCHABLE, detail = IndexStore.WORDS_DONE)
    add("b.txt", FileKind.DOCUMENT)
    add("c.mp4", FileKind.VIDEO)   // not a words-pass kind: counted as in
    val on = setOf(FileKind.DOCUMENT, FileKind.VIDEO)
    assertEquals(1, IndexAdmin.wordsLeft(store, on))
    assertEquals(2, IndexAdmin.detail(store, on, null, false).wordsIn)
  }

  // ---- the one-time clean-up ----------------------------------------------------------------------------------------------------

  @Test fun filesTheFirstBuildKnewByNameOnlyAreQueuedOnceAndPlainTextIsLeftAlone() {
    add("report.docx", FileKind.DOCUMENT, state = FileState.UNDERSTOOD, detail = IndexStore.NAME_ONLY)
    add("sheet.XLSX", FileKind.DOCUMENT, state = FileState.SEARCHABLE, detail = IndexStore.WORDS_DONE)
    add("notes.txt", FileKind.DOCUMENT, state = FileState.UNDERSTOOD)
    add("photo.webp", FileKind.IMAGE, state = FileState.FAILED, detail = IndexStore.CANT_OPEN)
    add("ok.jpg", FileKind.IMAGE, state = FileState.UNDERSTOOD)
    add("broken.png", FileKind.IMAGE, state = FileState.FAILED, detail = "unreadable picture")   // a png failing is not a missing format
    add("skipped.docx", FileKind.DOCUMENT, state = FileState.SKIPPED)

    val log = ArrayList<String>()
    assertEquals(3, IndexAdmin.rereadNewFormats(store) { log += it })
    assertEquals(FileState.SEARCHABLE.db to null, stateOf("report.docx"))
    assertEquals(FileState.SEARCHABLE.db to null, stateOf("sheet.XLSX"))
    assertEquals(FileState.SEARCHABLE.db to null, stateOf("photo.webp"))
    assertEquals(FileState.UNDERSTOOD.db, stateOf("notes.txt").first)
    assertEquals(FileState.UNDERSTOOD.db, stateOf("ok.jpg").first)
    assertEquals(FileState.FAILED.db, stateOf("broken.png").first)
    assertEquals(FileState.SKIPPED.db, stateOf("skipped.docx").first, "a file the person skipped stays skipped")
    assertEquals(listOf("3 files go back in the queue: this build reads more formats"), log)

    // Once per index: a docx read (or failing) later is not queued again at the next start.
    store.setState(store.read { db -> db.long("SELECT id FROM files WHERE name = 'report.docx'") }!!, FileState.UNDERSTOOD)
    assertEquals(0, IndexAdmin.rereadNewFormats(store))
    assertEquals(FileState.UNDERSTOOD.db, stateOf("report.docx").first)
    assertNotNull(store.meta(IndexAdmin.FORMATS_KEY))
  }

  @Test fun aFreshIndexIsMarkedWithoutQueuingAnything() {
    assertEquals(0, IndexAdmin.rereadNewFormats(store))
    assertNotNull(store.meta(IndexAdmin.FORMATS_KEY))
    assertNull(stateOf("nothing").first)
  }
}
