package trawl.desktop

import com.craneai.findr.core.FileKind
import com.craneai.findr.core.KindFilter
import com.craneai.findr.core.LearningSpeed
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The scope switches against the real index, with no model: what the walk records (counts per kind), what the window lists, and
 * what the crawler and the watcher refuse to enter. Reading needs a model and is covered by the Crane timing run.
 */
class IndexScopeTest {
  private fun tmp() = Files.createTempDirectory("trawl-scope").toFile().canonicalFile

  private fun file(root: File, rel: String, text: String = "x") = File(root, rel).apply { parentFile.mkdirs(); writeText(text) }

  /** A made-up folder: two notes, a PDF-named file, a photo and a screenshot, with a private subfolder holding one of each kind. */
  private fun corpus(): File = tmp().also { r ->
    file(r, "notes/alpha plan.txt", "alpha plan for the garden")
    file(r, "notes/beta.md", "beta notes")
    file(r, "photos/holiday.jpg")
    file(r, "photos/Screenshot_2026-01-01-10-00-00.png")
    file(r, "private/secret plan.txt", "secret plan")
    file(r, "private/selfie.jpg")
  }

  private fun waitFor(what: String, ms: Long = 20_000, ok: () -> Boolean) {
    val end = System.currentTimeMillis() + ms
    while (System.currentTimeMillis() < end) { if (ok()) return; Thread.sleep(50) }
    throw AssertionError("timed out waiting for $what")
  }

  private fun IndexService.known(kind: FileKind) = ui.value.byKind[kind] ?: 0

  private val all = setOf(FileKind.IMAGE, FileKind.SCREENSHOT, FileKind.DOCUMENT, FileKind.PDF)

  @Test fun theCrawlerDoesNotEnterASkippedFolder() {
    val r = corpus()
    val seen = CopyOnWriteArrayList<String>()
    val res = Crawler.walk(r.toPath(), r.toPath(), true, listOf(File(r, "private").toPath())) { seen += it.rel }
    assertTrue(res.complete)
    assertEquals(4, seen.size, "$seen")
    assertTrue(seen.none { it.contains("private") }, "$seen")
    r.deleteRecursively()
  }

  @Test fun theWalkRecordsEveryKindAndSkippingAFolderRemovesItsFilesFromTheIndex() {
    val r = corpus(); val data = tmp()
    IndexService(data).use { ix ->
      ix.configure(all, emptyList(), LearningSpeed.BALANCED, paused = false)
      ix.setFolders(listOf(r))
      waitFor("the first walk") { ix.known(FileKind.DOCUMENT) == 3 && ix.known(FileKind.IMAGE) == 2 }
      assertEquals(1, ix.known(FileKind.SCREENSHOT), "one screenshot, counted apart from the photos")

      val priv = File(r, "private").toPath()
      ix.configure(all, listOf(priv), LearningSpeed.BALANCED, paused = false)
      waitFor("the private files to leave") { ix.known(FileKind.DOCUMENT) == 2 && ix.known(FileKind.IMAGE) == 1 }
      assertEquals(0, ix.countUnder(File(r, "private")))

      // Un-skipping walks the folder again: the files come back.
      ix.configure(all, emptyList(), LearningSpeed.BALANCED, paused = false)
      waitFor("the private files to return") { ix.known(FileKind.DOCUMENT) == 3 && ix.known(FileKind.IMAGE) == 2 }
      assertEquals(2, ix.countUnder(File(r, "private")))
    }
    r.deleteRecursively(); data.deleteRecursively()
  }

  @Test fun aKindThatIsOffLeavesTheListsButStaysInTheIndex() {
    val r = corpus(); val data = tmp()
    IndexService(data).use { ix ->
      ix.configure(all, emptyList(), LearningSpeed.BALANCED, paused = false)
      ix.setFolders(listOf(r))
      waitFor("the first walk") { ix.known(FileKind.DOCUMENT) == 3 }
      val before = ix.recent(KindFilter.ALL).map { it.kind }.toSet()
      assertTrue(FileKind.IMAGE in before && FileKind.DOCUMENT in before, "$before")

      ix.configure(all - FileKind.IMAGE - FileKind.SCREENSHOT, emptyList(), LearningSpeed.BALANCED, paused = false)
      val docsOnly = ix.recent(KindFilter.ALL).map { it.kind }.toSet()
      assertTrue(docsOnly.all { it == FileKind.DOCUMENT || it == FileKind.PDF }, "photos are off, but $docsOnly is listed")
      assertTrue(ix.recent(KindFilter.PHOTOS).isEmpty())
      assertEquals(2, ix.known(FileKind.IMAGE), "the photos are still known, so switching back on is instant")
      waitFor("the meter to leave the photos out") { ix.ui.value.photos == 0 }

      ix.configure(all, emptyList(), LearningSpeed.BALANCED, paused = false)
      assertTrue(FileKind.IMAGE in ix.recent(KindFilter.ALL).map { it.kind })
    }
    r.deleteRecursively(); data.deleteRecursively()
  }

  @Test fun searchHidesAKindThatIsOff() {
    val r = corpus(); val data = tmp()
    IndexService(data).use { ix ->
      ix.configure(all, emptyList(), LearningSpeed.BALANCED, paused = false)
      ix.setFolders(listOf(r))
      waitFor("the first walk") { ix.known(FileKind.DOCUMENT) == 3 }
      val on = ix.search("holiday", KindFilter.ALL).hits.map { it.file.name }
      ix.configure(all - FileKind.IMAGE, emptyList(), LearningSpeed.BALANCED, paused = false)
      val off = ix.search("holiday", KindFilter.ALL).hits.map { it.file.name }
      assertFalse("holiday.jpg" in off, "the photo is off but was found: $off")
      assertTrue(off.size <= on.size)
    }
    r.deleteRecursively(); data.deleteRecursively()
  }

  @Test fun theWatcherIgnoresASkippedFolder() {
    val r = corpus()
    val skipped = listOf(File(r, "private").toPath())
    val got = CopyOnWriteArrayList<Dirty>()
    FolderWatcher(debounceMs = 300, maxWaitMs = 3000, ignore = { Scope.isSkipped(it, skipped) }, onDirty = { got.addAll(it) }).use { w ->
      w.watch(listOf(r))
      file(r, "private/new.txt", "should not be seen")
      file(r, "notes/new.txt", "should be seen")
      waitFor("the watcher to name the notes folder") { got.any { it.dir.endsWith("notes") } }
      Thread.sleep(1500)
      assertTrue(got.none { it.dir.startsWith(skipped[0]) }, "$got")
    }
    r.deleteRecursively()
  }
}
