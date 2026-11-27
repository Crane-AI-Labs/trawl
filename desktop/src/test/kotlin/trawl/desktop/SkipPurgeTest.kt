package trawl.desktop

import com.craneai.findr.core.FileKind
import com.craneai.findr.core.LearningSpeed
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Skipped folders must really leave the index. On a developer Mac a skipped ~/Documents/Projects still held 87% of 729,000 vectors for days: the
 * skip was marked applied before its files were dropped, so one failed drop (an out-of-memory index, a broken state) meant it was never tried
 * again in that run. The drop is injected to fail, and the purge must be retried, finished, counted and told to the window.
 */
class SkipPurgeTest {
  private fun tmp() = Files.createTempDirectory("trawl-purge").toFile().canonicalFile
  private fun file(root: File, rel: String, text: String = "x") = File(root, rel).apply { parentFile.mkdirs(); writeText(text) }
  private val all = setOf(FileKind.IMAGE, FileKind.SCREENSHOT, FileKind.DOCUMENT, FileKind.PDF)

  private fun waitFor(what: String, ms: Long = 40_000, ok: () -> Boolean) {
    val end = System.currentTimeMillis() + ms
    while (System.currentTimeMillis() < end) { if (ok()) return; Thread.sleep(50) }
    throw AssertionError("timed out waiting for $what")
  }

  private fun IndexService.known() = ui.value.byKind[FileKind.DOCUMENT] ?: 0
  private fun IndexService.idle() = ui.value.phase != Phase.SCANNING && ui.value.phase != Phase.NOT_STARTED

  private fun library(): File = tmp().also { r ->
    repeat(5) { file(r, "keep/k$it.txt", "keep $it") }
    repeat(40) { file(r, "Projects/deep/p$it.txt", "project $it") }
    file(r, "Projects/top.txt")
  }

  @Test fun aSkippedFolderWhoseDropFailedIsTakenOutOnALaterTurnAndTheWindowIsTold() {
    val r = library(); val data = tmp()
    val purged = java.util.Collections.synchronizedList(ArrayList<Pair<Int, Boolean>>())
    IndexService(data).use { ix ->
      ix.onPurged = { n, projects -> purged += n to projects }
      ix.configure(all, emptyList(), LearningSpeed.BALANCED, paused = false)
      ix.setFolders(listOf(r))
      waitFor("the first walk") { ix.known() == 46 && ix.idle() }
      val fails = AtomicInteger(2)
      ix.beforeDrop = { if (fails.getAndDecrement() > 0) throw OutOfMemoryError("test: heap") }
      ix.configure(all, listOf(File(r, "Projects").toPath()), LearningSpeed.BALANCED, paused = false)
      waitFor("the first failed drop") { fails.get() < 1 }
      assertTrue(ix.known() == 46, "a failed drop removes nothing")
      assertTrue(purged.isEmpty(), "and says nothing")
      waitFor("the folder to leave the index after the retries") { ix.known() == 5 }
      assertEquals(listOf(41 to false), purged.toList(), "told once, with the number of files taken out")
      assertEquals(0, ix.countUnder(File(r, "Projects")))
      assertEquals(5, ix.countUnder(File(r, "keep")))
    }
    r.deleteRecursively(); data.deleteRecursively()
  }

  @Test fun theFirstStartAfterAnUpdateTakesOutAFolderThatWasSkippedBeforeAndStillHeldFiles() {
    val r = library(); val data = tmp()
    IndexService(data).use { ix ->
      ix.configure(all, emptyList(), LearningSpeed.BALANCED, paused = false)
      ix.setFolders(listOf(r))
      waitFor("the first walk") { ix.known() == 46 && ix.idle() }
    }
    // A new process: the skip list already names the folder, and the index still holds its files (the state the broken 1.0.3 left).
    val purged = AtomicInteger()
    IndexService(data).use { ix ->
      ix.onPurged = { n, _ -> purged.set(n) }
      ix.configure(all, listOf(File(r, "Projects").toPath()), LearningSpeed.BALANCED, paused = false)
      ix.setFolders(listOf(r))
      waitFor("the purge on the first start") { ix.known() == 5 && ix.idle() }
      assertEquals(41, purged.get())
    }
    r.deleteRecursively(); data.deleteRecursively()
  }

  @Test fun aPurgeThatFailsKeepsTryingEvenWithNothingElseToDoAndAFolderSkippedAgainIsNotCountedTwice() {
    val r = library(); val data = tmp()
    val purged = java.util.Collections.synchronizedList(ArrayList<Int>())
    IndexService(data).use { ix ->
      ix.onPurged = { n, _ -> purged += n }
      ix.configure(all, emptyList(), LearningSpeed.BALANCED, paused = false)
      ix.setFolders(listOf(r))
      waitFor("the first walk") { ix.known() == 46 && ix.idle() }
      val skipList = listOf(File(r, "Projects").toPath())
      ix.configure(all, skipList, LearningSpeed.BALANCED, paused = false)
      waitFor("the purge") { ix.known() == 5 }
      ix.configure(all, skipList, LearningSpeed.BALANCED, paused = false)   // the same list again: nothing to do
      Thread.sleep(600)
      assertEquals(listOf(41), purged.toList())
      // Un-skipping walks the folder again and the files come back (the applied list holds the folder, not the parts of its path).
      ix.configure(all, emptyList(), LearningSpeed.BALANCED, paused = false)
      waitFor("the folder to come back") { ix.known() == 46 }
    }
    r.deleteRecursively(); data.deleteRecursively()
  }
}
