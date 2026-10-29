package trawl.desktop

import com.craneai.findr.core.FileKind
import com.craneai.findr.core.LearningSpeed
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The service against a real index: it publishes sparingly, stays quiet when paused, and forgets a skipped folder in committed batches. */
class IndexPublishTest {
  private fun tmp() = Files.createTempDirectory("trawl-pub").toFile().canonicalFile
  private fun file(root: File, rel: String, text: String) = File(root, rel).apply { parentFile.mkdirs(); writeText(text) }
  private val kinds = setOf(FileKind.DOCUMENT)

  private fun waitFor(what: String, ms: Long = 60_000, ok: () -> Boolean) {
    val end = System.currentTimeMillis() + ms
    while (System.currentTimeMillis() < end) { if (ok()) return; Thread.sleep(25) }
    throw AssertionError("timed out waiting for $what")
  }

  private fun IndexService.known() = ui.value.byKind[FileKind.DOCUMENT] ?: 0
  private fun IndexService.idle() = ui.value.phase != Phase.SCANNING && ui.value.phase != Phase.NOT_STARTED

  @Test fun aWalkOfManyFilesPublishesAboutOncePerSecondAndEndsWithTheTrueCounts() {
    val r = tmp(); val data = tmp()
    for (i in 1..1500) file(r, "d${i % 30}/f$i.txt", "note number $i")
    IndexService(data).use { ix ->
      ix.configure(kinds, emptyList(), LearningSpeed.BALANCED, paused = true)
      val t0 = System.nanoTime()
      ix.setFolders(listOf(r))
      waitFor("the walk") { ix.known() == 1500 && ix.idle() }
      val secs = (System.nanoTime() - t0) / 1e9
      assertEquals(1500, ix.known(), "the last publish carries the final count")
      assertTrue(ix.publishCount.get() <= secs * 2 + 6, "${ix.publishCount.get()} publishes in %.1f s for 1500 files".format(secs))
    }
    r.deleteRecursively(); data.deleteRecursively()
  }

  @Test fun pausedAndIdleMeansAtMostAnOccasionalStatusRefresh() {
    val r = tmp(); val data = tmp()
    for (i in 1..20) file(r, "f$i.txt", "note $i")
    IndexService(data).use { ix ->
      ix.configure(kinds, emptyList(), LearningSpeed.BALANCED, paused = true)
      ix.setFolders(listOf(r))
      waitFor("the walk") { ix.known() == 20 && ix.idle() }
      Thread.sleep(1500)   // let the last held-back status go out
      val before = ix.publishCount.get()
      Thread.sleep(4000)
      assertTrue(ix.publishCount.get() - before <= 1, "paused for 4 s: ${ix.publishCount.get() - before} publishes (it was one a second)")
    }
    r.deleteRecursively(); data.deleteRecursively()
  }

  @Test fun resumingShowsAtOnceEvenWhileTheWorkerIsBusy() {
    val r = tmp(); val data = tmp()
    for (i in 1..30) file(r, "f$i.txt", "note $i")
    IndexService(data).use { ix ->
      ix.configure(kinds, emptyList(), LearningSpeed.BALANCED, paused = true)
      ix.setFolders(listOf(r))
      waitFor("the walk, paused with the words still to read") { ix.known() == 30 && ix.ui.value.phase == Phase.PAUSED }
      Thread.sleep(1200)   // the last held-back status is out: the next one is a state change
      ix.configure(kinds, emptyList(), LearningSpeed.BALANCED, paused = false)
      waitFor("the resume to show", 700) { ix.ui.value.phase != Phase.PAUSED }
    }
    r.deleteRecursively(); data.deleteRecursively()
  }

  @Test fun aFolderSkippedFromNowOnIsForgottenInBatchesRowsWordsAndAll() {
    val r = tmp(); val data = tmp()
    for (i in 1..650) file(r, "vend/lib/f$i.txt", "vendor words $i")
    for (i in 1..5) file(r, "keep/k$i.txt", "kept words $i")
    IndexService(data).use { ix ->
      ix.configure(kinds, emptyList(), LearningSpeed.BALANCED, paused = false)
      ix.setFolders(listOf(r))
      waitFor("the walk") { ix.known() == 655 && ix.idle() }
      val vend = File(r, "vend")
      assertEquals(650, ix.countUnder(vend))
      ix.configure(kinds, listOf(vend.toPath()), LearningSpeed.BALANCED, paused = false)
      waitFor("the forget pass") { ix.countUnder(vend) == 0 && ix.known() == 5 }
      assertEquals(650L, ix.forgottenSoFar)
      assertEquals(5, ix.countUnder(File(r, "keep")))
    }
    r.deleteRecursively(); data.deleteRecursively()
  }

  @Test fun theForgetPassStillRunsAndFinishesWhilePaused() {
    val r = tmp(); val data = tmp()
    for (i in 1..300) file(r, "vend/f$i.txt", "vendor words $i")
    file(r, "keep/k.txt", "kept")
    IndexService(data).use { ix ->
      ix.configure(kinds, emptyList(), LearningSpeed.BALANCED, paused = true)
      ix.setFolders(listOf(r))
      waitFor("the walk") { ix.known() == 301 && ix.idle() }
      val vend = File(r, "vend")
      ix.configure(kinds, listOf(vend.toPath()), LearningSpeed.BALANCED, paused = true)
      waitFor("the paced forget pass", 90_000) { ix.countUnder(vend) == 0 }
      waitFor("the count to follow", 3_000) { ix.known() == 1 }   // shown within about a second of the last batch
    }
    r.deleteRecursively(); data.deleteRecursively()
  }
}
