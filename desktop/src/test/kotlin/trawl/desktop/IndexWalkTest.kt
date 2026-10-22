package trawl.desktop

import com.craneai.findr.core.FileKind
import com.craneai.findr.core.LearningSpeed
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The walk against the real index, with no model: what stays and what goes when a folder cannot be read, that a walk which gave way
 * carries on instead of starting again, and that the busy hidden folders of a developer's disk are never read.
 */
class IndexWalkTest {
  private fun tmp() = Files.createTempDirectory("trawl-walk-ix").toFile().canonicalFile
  private fun file(root: File, rel: String, text: String = "x") = File(root, rel).apply { parentFile.mkdirs(); writeText(text) }
  private fun lock(d: File) = Files.setPosixFilePermissions(d.toPath(), PosixFilePermissions.fromString("---------"))
  private fun unlock(d: File) = Files.setPosixFilePermissions(d.toPath(), PosixFilePermissions.fromString("rwxr-xr-x"))
  private fun canStillList(d: File) = runCatching { Files.newDirectoryStream(d.toPath()).use { }; true }.getOrDefault(false)
  private val all = setOf(FileKind.IMAGE, FileKind.SCREENSHOT, FileKind.DOCUMENT, FileKind.PDF)

  private fun waitFor(what: String, ms: Long = 30_000, ok: () -> Boolean) {
    val end = System.currentTimeMillis() + ms
    while (System.currentTimeMillis() < end) { if (ok()) return; Thread.sleep(50) }
    throw AssertionError("timed out waiting for $what")
  }

  private fun IndexService.known() = ui.value.byKind[FileKind.DOCUMENT] ?: 0
  private fun IndexService.idle() = ui.value.phase != Phase.SCANNING && ui.value.phase != Phase.NOT_STARTED

  private fun IndexService.start(r: File, setup: (IndexService) -> Unit = {}): IndexService {
    configure(all, emptyList(), LearningSpeed.BALANCED, paused = false)
    setup(this)
    setFolders(listOf(r))
    return this
  }

  @Test fun anUnreadableSubfolderIsReportedByKindAndItsRowsSurviveEveryScanWhileTheRestStaysHonest() {
    val r = tmp(); val data = tmp()
    file(r, "a/keep.txt", "keep"); file(r, "a/gone.txt", "gone"); file(r, "locked/inside.txt", "inside"); file(r, "b/other.txt", "other")
    val locked = File(r, "locked")
    IndexService(data).use { ix ->
      ix.start(r)
      waitFor("the first walk") { ix.known() == 4 && ix.idle() }
      assertEquals(BlockedSummary.NONE, ix.ui.value.blocked)

      lock(locked)
      try {
        if (canStillList(locked)) return   // root: the lock means nothing here
        File(r, "a/gone.txt").delete()
        ix.rescan()
        waitFor("the walk that names the locked folder") { ix.ui.value.blocked.system == 1 }
        // The deleted file in a readable folder is dropped at once: the old behaviour kept it for ever (the walk never finished).
        waitFor("the deleted file to leave") { ix.known() == 3 }
        assertEquals(1, ix.countUnder(locked), "the row under the locked folder is untouched: its absence says nothing")
        assertEquals(1, ix.ui.value.blocked.total)
        assertEquals(mapOf(Place.OTHER to 1), ix.ui.value.blocked.where, "a kind of place, never a name")

        // A second scan changes nothing and says nothing new.
        ix.rescan()
        Thread.sleep(500)
        waitFor("the second walk") { ix.idle() }
        assertEquals(3, ix.known())
        assertEquals(1, ix.countUnder(locked))
      } finally { unlock(locked) }

      // Readable again: the notice goes, and now the folder is judged like any other.
      File(locked, "inside.txt").delete()
      ix.rescan()
      waitFor("the notice to go") { ix.ui.value.blocked.total == 0 }
      waitFor("the row under the folder to go") { ix.countUnder(locked) == 0 }
    }
    r.deleteRecursively(); data.deleteRecursively()
  }

  @Test fun aFolderThatCannotBeReadAtTheFirstScanIsNeverCountedAsEmpty() {
    val r = tmp(); val data = tmp()
    file(r, "a/one.txt"); file(r, "locked/two.txt")
    IndexService(data).use { ix ->
      ix.start(r)
      waitFor("the first walk") { ix.known() == 2 && ix.idle() }
      lock(File(r, "locked"))
      try {
        if (canStillList(File(r, "locked"))) return
        ix.rescan()
        waitFor("the notice") { ix.ui.value.blocked.system == 1 }
        waitFor("the walk to end") { ix.idle() }
        assertEquals(2, ix.known(), "nothing under an unreadable folder is forgotten")
      } finally { unlock(File(r, "locked")) }
    }
    r.deleteRecursively(); data.deleteRecursively()
  }

  @Test fun aWalkThatKeepsGivingWayStillFinishesCountsEachFileOnceAndThenJudgesWhatIsGone() {
    val r = tmp(); val data = tmp()
    for (d in 1..6) for (i in 1..3) file(r, "d$d/sub/f$i.txt", "note $d $i")
    file(r, "top.txt", "top")
    IndexService(data).use { ix ->
      val asked = AtomicInteger()
      // Every folder after the first of each turn is put off: the walk gives way as often as it possibly can.
      ix.yieldHook = { asked.incrementAndGet(); true }
      ix.start(r)
      waitFor("the whole tree, a folder at a time") { ix.known() == 19 && ix.idle() }
      assertTrue(asked.get() >= 12, "it gave way many times (${asked.get()}), and still got to the end")
      assertEquals(19, ix.ui.value.scanned, "each file counted once: carrying on is not starting again")

      // The finished walk may now say what is gone.
      File(r, "d3").deleteRecursively()
      ix.rescan()
      waitFor("the deleted folder's files to leave") { ix.known() == 16 }
      assertEquals(0, ix.countUnder(File(r, "d3")))
      assertEquals(BlockedSummary.NONE, ix.ui.value.blocked)
    }
    r.deleteRecursively(); data.deleteRecursively()
  }

  @Test fun aWalkThatGivesWayAFewTimesStillCountsEachFileOnce() {
    val r = tmp(); val data = tmp()
    for (d in 1..4) for (i in 1..2) file(r, "d$d/f$i.txt", "note $d $i")
    IndexService(data).use { ix ->
      val gaveWay = AtomicInteger()
      ix.yieldHook = { gaveWay.incrementAndGet() <= 3 }   // gives way three times, then lets the walk run
      ix.start(r)
      waitFor("the tree") { ix.known() == 8 && ix.idle() }
      assertEquals(8, ix.ui.value.scanned)
    }
    r.deleteRecursively(); data.deleteRecursively()
  }

  @Test fun changesInsideAHiddenFolderAreNeverReadEvenWhenTheWatcherNamesThem() {
    val r = tmp(); val data = tmp()
    file(r, "real/note.txt", "real note")
    file(r, ".git/objects/secret.txt", "hidden note")
    IndexService(data).use { ix ->
      ix.start(r)
      waitFor("the first walk") { ix.known() == 1 && ix.idle() }
      // The watcher names the hidden folder itself, then a normal folder: the second is read, the first is not.
      ix.reconcile(listOf(Dirty(File(r, ".git").toPath(), true), Dirty(File(r, ".git/objects").toPath(), false)))
      file(r, "real/new.txt", "new note")
      ix.reconcile(listOf(Dirty(File(r, "real").toPath(), false)))
      waitFor("the new note") { ix.known() == 2 }
      assertEquals(0, ix.countUnder(File(r, ".git")), "a hidden folder is never read")
    }
    r.deleteRecursively(); data.deleteRecursively()
  }

  @Test fun manyReportsForTheSameFolderMakeOneWalkOfIt() {
    val r = tmp(); val data = tmp()
    file(r, "real/note.txt", "real note")
    IndexService(data).use { ix ->
      ix.start(r)
      waitFor("the first walk") { ix.known() == 1 && ix.idle() }
      file(r, "real/second.txt", "second note")
      repeat(500) { ix.reconcile(listOf(Dirty(File(r, "real").toPath(), false))) }
      waitFor("the second note") { ix.known() == 2 }
    }
    r.deleteRecursively(); data.deleteRecursively()
  }

  // ---- the words the person reads ---------------------------------------------------------------------------------------

  @Test fun placesAreKindsNeverNames() {
    val home = Paths.get("/Users/someone")
    assertEquals(Place.DOCUMENTS, Place.of(Paths.get("/Users/someone/Documents/clients/acme"), home))
    assertEquals(Place.DESKTOP, Place.of(Paths.get("/Users/someone/Desktop"), home))
    assertEquals(Place.DOWNLOADS, Place.of(Paths.get("/Users/someone/Downloads/x/y"), home))
    assertEquals(Place.PICTURES, Place.of(Paths.get("/Users/someone/Pictures"), home))
    assertEquals(Place.ICLOUD, Place.of(Paths.get("/Users/someone/Library/Mobile Documents/com~apple~CloudDocs/Work"), home))
    assertEquals(Place.REMOVABLE, Place.of(Paths.get("/Volumes/Backup/photos"), home))
    assertEquals(Place.OTHER, Place.of(Paths.get("/Users/someone/projects/app"), home))
  }

  @Test fun theSummaryCountsByKindAndItsLogLineAndNoticeNameNoFolder() {
    val home = Paths.get("/Users/someone")
    val b = BlockedSummary.of(
      mapOf(
        Paths.get("/Users/someone/Documents/clients/acme") to Block.DENIED,
        Paths.get("/Users/someone/Documents/taxes") to Block.DENIED,
        Paths.get("/Users/someone/Desktop/ideas") to Block.DENIED,
        Paths.get("/Volumes/Backup/photos") to Block.OTHER,
      ), home,
    )
    assertEquals(3, b.system); assertEquals(1, b.other); assertEquals(4, b.total)
    assertEquals(mapOf(Place.DOCUMENTS to 2, Place.DESKTOP to 1), b.where)
    val line = b.logLine()
    for (secret in listOf("acme", "taxes", "ideas", "Backup", "someone")) assertFalse(secret in line, line)
    assertFalse(Diagnostics.isError(Diagnostics.safeLine(line, home.toString())), "a safe event, not a failure: $line")
    val mac = BlockedNotice.lines(b, Os.MAC)
    assertEquals(3, mac.size, "$mac")
    assertTrue("Privacy & Security" in mac[1] && "Files & Folders" in mac[1] && "Full Disk Access" in mac[1], mac[1])
    assertTrue("Documents" in mac[0] && "Desktop" in mac[0], mac[0])
    for (secret in listOf("acme", "taxes", "ideas", "Backup", "someone")) assertTrue(mac.none { secret in it }, "$mac")
    assertFalse("Privacy" in BlockedNotice.lines(b, Os.LINUX)[1])
    assertEquals(emptyList(), BlockedNotice.lines(BlockedSummary.NONE, Os.MAC))
  }
}
