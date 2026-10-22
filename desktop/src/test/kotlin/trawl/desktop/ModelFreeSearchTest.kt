package trawl.desktop

import com.craneai.findr.core.FileKind
import com.craneai.findr.core.KindFilter
import com.craneai.findr.core.LearningSpeed
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * With no model installed Trawl still reads the words: a document's text and the text in a picture go into the keyword index
 * through the core's words pass, so a word inside a file finds the file. The first-run line ("Search then works on names and
 * words") is only true because of this.
 */
class ModelFreeSearchTest {
  private fun tmp() = Files.createTempDirectory("trawl-words").toFile().canonicalFile
  private fun file(root: File, rel: String, text: String) = File(root, rel).apply { parentFile.mkdirs(); writeText(text) }

  private val all = setOf(FileKind.IMAGE, FileKind.SCREENSHOT, FileKind.DOCUMENT, FileKind.PDF)

  private fun waitFor(what: String, ms: Long = 20_000, ok: () -> Boolean) {
    val end = System.currentTimeMillis() + ms
    while (System.currentTimeMillis() < end) { if (ok()) return; Thread.sleep(50) }
    throw AssertionError("timed out waiting for $what")
  }

  /** A text reader that never touches the picture: it says what a scan of this made-up invoice would say. */
  private class FakeOcr : Ocr {
    override val name = "fake"
    override fun read(image: File): String = if (image.name.startsWith("scan")) "Invoice number 4417 for web hosting services rendered in March" else ""
    override fun close() {}
  }

  private fun names(ix: IndexService, q: String, filter: KindFilter = KindFilter.ALL) = ix.search(q, filter).hits.map { it.file.name }

  @Test fun aWordInsideATextFileFindsItWithNoModel() {
    val r = tmp(); val data = tmp()
    file(r, "notes/recipe.txt", "Slow roasted tomatoes with a spoonful of orange marmalade and thyme.")
    file(r, "notes/shopping.txt", "bread, milk, eggs and a bag of flour")
    IndexService(data).use { ix ->
      ix.configure(all, emptyList(), LearningSpeed.BALANCED, paused = false)
      ix.setFolders(listOf(r))
      waitFor("the words to be read") { names(ix, "marmalade").isNotEmpty() }
      assertEquals(listOf("recipe.txt"), names(ix, "marmalade"), "exactly the one file that says it")
      assertEquals(emptyList(), names(ix, "unicorn"), "a word no file holds finds nothing")
      assertFalse(ix.ui.value.modelReady, "no model was involved")
    }
    r.deleteRecursively(); data.deleteRecursively()
  }

  @Test fun theMeterSaysWordsAreBeingReadThenSettlesOnNamesAndWords() {
    val r = tmp(); val data = tmp()
    repeat(12) { file(r, "docs/note$it.txt", "meeting minutes number $it about the harvest festival budget") }
    IndexService(data).use { ix ->
      ix.configure(all, emptyList(), LearningSpeed.BALANCED, paused = false)
      ix.setFolders(listOf(r))
      waitFor("the words of all 12 notes") { names(ix, "harvest").size == 12 }
      waitFor("the meter to settle") { ix.ui.value.phase == Phase.UP_TO_DATE }
      assertFalse(ix.ui.value.readingWords, "nothing is left to read")
      assertEquals(12, ix.ui.value.understood, "every file's words are in")
      assertEquals(12, ix.ui.value.total)
    }
    r.deleteRecursively(); data.deleteRecursively()
  }

  @Test fun theTextInAPictureIsSearchableThroughTheComputersOwnReader() {
    val r = tmp(); val data = tmp()
    file(r, "pics/scan001.jpg", "not really a picture")
    file(r, "pics/holiday.jpg", "not really a picture either")
    IndexService(data, ocrChoice = { FakeOcr() }).use { ix ->
      ix.configure(all, emptyList(), LearningSpeed.BALANCED, paused = false)
      ix.setFolders(listOf(r))
      waitFor("the picture's words") { names(ix, "hosting").isNotEmpty() }
      assertEquals(listOf("scan001.jpg"), names(ix, "hosting"))
      assertEquals(emptyList(), names(ix, "hosting", KindFilter.DOCUMENTS), "it is a photo, not a document")
    }
    r.deleteRecursively(); data.deleteRecursively()
  }

  @Test fun noReaderMeansAPictureIsFoundByNameOnlyAndTheQueueStillEmpties() {
    val r = tmp(); val data = tmp()
    file(r, "pics/scan001.jpg", "not really a picture")
    IndexService(data).use { ix ->   // the default text reader: none on this computer
      ix.configure(all, emptyList(), LearningSpeed.BALANCED, paused = false)
      ix.setFolders(listOf(r))
      waitFor("the queue to empty") { ix.ui.value.phase == Phase.UP_TO_DATE && ix.ui.value.total == 1 }
      assertEquals(listOf("scan001.jpg"), names(ix, "scan001"))
      assertEquals(emptyList(), names(ix, "hosting"))
    }
    r.deleteRecursively(); data.deleteRecursively()
  }

  @Test fun theWordsAreKeptAcrossARestartWithoutReadingAgain() {
    val r = tmp(); val data = tmp()
    val f = file(r, "notes/recipe.txt", "Slow roasted tomatoes with orange marmalade.")
    IndexService(data).use { ix ->
      ix.configure(all, emptyList(), LearningSpeed.BALANCED, paused = false)
      ix.setFolders(listOf(r))
      waitFor("the words") { names(ix, "marmalade").isNotEmpty() }
    }
    // The words are in the index file, not in memory: a new service finds them with the file gone from view of the test (it is deleted).
    assertTrue(f.delete())
    IndexService(data).use { ix ->
      waitFor("the index to open") { names(ix, "marmalade").isNotEmpty() }   // it opens in the background now
      assertEquals(listOf("recipe.txt"), names(ix, "marmalade"))
    }
    r.deleteRecursively(); data.deleteRecursively()
  }

  /** Needs the real model file (TRAWL_MODEL, as on a build machine); with none it has nothing to prove and passes. */
  @Test fun whenTheModelsArriveLaterMeaningGoesOnTopOfTheWordsWithoutReadingTheFilesAgain() {
    val model = System.getenv("TRAWL_MODEL")?.let(::File)?.takeIf { it.isFile } ?: return
    val r = tmp(); val data = tmp()
    val a = file(r, "notes/recipe.txt", "Slow roasted tomatoes with a spoonful of orange marmalade and thyme.")
    val b = file(r, "notes/shopping.txt", "bread, milk, eggs and a bag of flour")
    IndexService(data).use { ix ->
      ix.configure(all, emptyList(), LearningSpeed.BALANCED, paused = false)
      ix.setFolders(listOf(r))
      waitFor("the words") { names(ix, "marmalade").isNotEmpty() && ix.ui.value.phase == Phase.UP_TO_DATE }
      // From here the files cannot be opened: any second read of them would fail them.
      assertTrue(a.setReadable(false) && b.setReadable(false))
      try {
        ix.attachModel(model)
        waitFor("the model", ms = 120_000) { ix.ui.value.modelReady }
        waitFor("meaning to be added", ms = 120_000) { ix.ui.value.phase == Phase.UP_TO_DATE && ix.ui.value.understood == 2 && !ix.ui.value.readingWords }
        assertEquals(0, ix.ui.value.unreadable, "the stored words were embedded, the files were not opened again")
        assertEquals(listOf("recipe.txt"), names(ix, "marmalade"), "the words are still there")
      } finally { a.setReadable(true); b.setReadable(true) }
    }
    r.deleteRecursively(); data.deleteRecursively()
  }

  @Test fun pausingStopsTheWordsPass() {
    val r = tmp(); val data = tmp()
    repeat(30) { file(r, "docs/note$it.txt", "budget meeting minutes number $it") }
    IndexService(data).use { ix ->
      ix.configure(all, emptyList(), LearningSpeed.BALANCED, paused = true)
      ix.setFolders(listOf(r))
      waitFor("the walk") { ix.ui.value.byKind[FileKind.DOCUMENT] == 30 }
      Thread.sleep(1500)
      assertEquals(emptyList(), names(ix, "budget"), "paused: nothing was read")
      assertEquals(Phase.PAUSED, ix.ui.value.phase)
      ix.configure(all, emptyList(), LearningSpeed.BALANCED, paused = false)
      waitFor("reading to resume") { names(ix, "budget").size == 30 }
    }
    r.deleteRecursively(); data.deleteRecursively()
  }
}
