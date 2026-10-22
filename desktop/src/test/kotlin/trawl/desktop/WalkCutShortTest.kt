package trawl.desktop

import java.io.File
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * "walk was cut short; nothing deleted" 148 times in one log: the causes, one test each, on a made-up folder. A walk is complete when it
 * saw everything; what it could not see (or chose to leave for later) is named, so the rest of what it saw can be trusted.
 */
class WalkCutShortTest {
  private fun tmp() = Files.createTempDirectory("trawl-walk").toFile().canonicalFile
  private fun file(root: File, rel: String, text: String = "x") = File(root, rel).apply { parentFile.mkdirs(); writeText(text) }
  private fun lock(d: File) = Files.setPosixFilePermissions(d.toPath(), PosixFilePermissions.fromString("---------"))
  private fun unlock(d: File) = Files.setPosixFilePermissions(d.toPath(), PosixFilePermissions.fromString("rwxr-xr-x"))
  /** Running as root, nothing is locked: such a run proves nothing about locked folders, so it says so and stops. */
  private fun lockedFolderStillReadable(d: File): Boolean = runCatching { Files.newDirectoryStream(d.toPath()).use { it.iterator().hasNext() || true } }.getOrDefault(false)

  // ---- cause 1: a file that is gone by the time it is looked at ------------------------------------------------------

  @Test fun aFileThatVanishesMidWalkDoesNotMakeTheWalkIncomplete() {
    val r = tmp()
    for (i in 1..20) file(r, "d/f$i.txt")
    // The first file found deletes every other file: the walk has listed them already but not yet looked at them.
    val res = Crawler.walk(r.toPath(), r.toPath(), true) { f ->
      File(r, "d").listFiles()!!.filter { it.name != f.name }.forEach { it.delete() }
    }
    assertTrue(res.complete, "a file that is gone is just gone; it says nothing about the folder")
    assertTrue(res.unreadable.isEmpty())
    assertTrue(res.vanished > 0, "it is counted, for the log")
    r.deleteRecursively()
  }

  @Test fun aFolderThatVanishesBetweenBeingListedAndEnteredIsJustGone() {
    val r = tmp()
    file(r, "a/one.txt"); file(r, "b/two.txt"); file(r, "c/three.txt")
    val seen = CopyOnWriteArrayList<String>()
    val res = Crawler.walk(r.toPath(), r.toPath(), true) { f ->
      seen += f.name
      // Whichever folder the walk is in, the other two disappear before it gets there.
      listOf("a", "b", "c").map { File(r, it) }.filter { it.exists() && !f.path.startsWith(it.toPath()) }.forEach { it.deleteRecursively() }
    }
    assertTrue(res.complete, "$seen")
    r.deleteRecursively()
  }

  // ---- cause 2: a folder the system will not open --------------------------------------------------------------------

  @Test fun aSubfolderThatCannotBeReadIsNamedAndTheRestIsStillRead() {
    val r = tmp()
    file(r, "a/one.txt"); file(r, "locked/two.txt"); file(r, "b/three.txt")
    val locked = File(r, "locked")
    lock(locked)
    try {
      if (lockedFolderStillReadable(locked)) return   // root: the lock means nothing here
      val seen = CopyOnWriteArrayList<String>()
      val res = Crawler.walk(r.toPath(), r.toPath(), true) { seen += it.name }
      assertEquals(listOf("one.txt", "three.txt"), seen.sorted(), "the rest is still read")
      assertFalse(res.complete)
      assertEquals(1, res.unreadable.size)
      assertEquals(locked.toPath(), res.unreadable[0].path)
      assertEquals(Block.DENIED, res.unreadable[0].block, "the system said no")
      assertTrue(res.pending.isEmpty(), "nothing was left for later")
    } finally { unlock(locked); r.deleteRecursively() }
  }

  @Test fun whyTellsAVanishedFileFromAFolderTheSystemRefused() {
    val f = java.nio.file.Paths.get("/x")
    assertNull(Crawler.why(NoSuchFileException("/x")))
    assertNull(Crawler.why(java.io.FileNotFoundException("/x")))
    assertNull(Crawler.why(java.nio.file.NotDirectoryException("/x")))
    assertEquals(Block.DENIED, Crawler.why(AccessDeniedException("/x")))
    // macOS privacy (TCC) says EPERM, which Java surfaces as a plain FileSystemException.
    assertEquals(Block.DENIED, Crawler.why(FileSystemException(f.toString(), null, "Operation not permitted")))
    assertEquals(Block.DENIED, Crawler.why(FileSystemException(f.toString(), null, "Permission denied")))
    assertEquals(Block.OTHER, Crawler.why(FileSystemException(f.toString(), null, "Input/output error")))
    assertEquals(Block.OTHER, Crawler.why(IOException("the share dropped")))
  }

  // ---- cause 3: hidden folders ---------------------------------------------------------------------------------------

  @Test fun insideHiddenIsTrueAnywhereBelowAHiddenFolderButNeverForTheRoot() {
    val r = tmp()
    file(r, ".git/objects/ab/cd.txt"); file(r, "src/.cache/x.txt"); file(r, "src/ok/y.txt")
    val root = r.toPath()
    assertTrue(Crawler.insideHidden(root, File(r, ".git").toPath()))
    assertTrue(Crawler.insideHidden(root, File(r, ".git/objects/ab").toPath()))
    assertTrue(Crawler.insideHidden(root, File(r, "src/.cache").toPath()))
    assertFalse(Crawler.insideHidden(root, File(r, "src/ok").toPath()))
    assertFalse(Crawler.insideHidden(root, root))
    r.deleteRecursively()
  }

  // ---- cause 4: a walk asked to stop must carry on, not start again --------------------------------------------------

  @Test fun aWalkThatStopsNamesTheFoldersLeftAndWalkingThemFinishesTheJob() {
    val r = tmp()
    for (d in listOf("a", "b", "c")) for (i in 1..3) file(r, "$d/f$i.txt")
    file(r, "top.txt")
    val seen = CopyOnWriteArrayList<String>()
    val todo = ArrayDeque<java.nio.file.Path>().apply { add(r.toPath()) }
    var calls = 0
    var stops = 0
    while (todo.isNotEmpty()) {
      val d = todo.removeFirst()
      calls++
      val res = Crawler.walk(r.toPath(), d, true, stop = { stops++; true }) { seen += it.rel + it.name }
      assertTrue(res.unreadable.isEmpty())
      res.pending.reversed().forEach { todo.addFirst(it) }
      if (calls > 20) throw AssertionError("it does not end: a stopped walk starts again from the top")
    }
    assertEquals(10, seen.size, "$seen")
    assertEquals(10, seen.toSet().size, "no file twice: the walk went on, it did not repeat")
    assertEquals(4, calls, "the top folder, then a, b and c once each")
    assertTrue(stops >= 3)
    r.deleteRecursively()
  }

  @Test fun theFirstFolderOfAWalkIsEnteredWhateverStopSays() {
    val r = tmp()
    file(r, "one.txt")
    val seen = CopyOnWriteArrayList<String>()
    val res = Crawler.walk(r.toPath(), r.toPath(), true, stop = { true }) { seen += it.name }
    assertEquals(listOf("one.txt"), seen, "a call that always stops would otherwise never get anywhere")
    assertTrue(res.complete)
    r.deleteRecursively()
  }

  @Test fun theWatcherDropsEventsInsideHiddenFolders() {
    val root = File("/Users/x/Pictures").toPath()
    val got = ArrayList<Dirty>()
    MacFsEvents.report(root, root, "/Users/x/Pictures/proj/.git/objects/ab/cdef", 0x100, got::add)
    MacFsEvents.report(root, root, "/Users/x/Pictures/proj/.git", 0x20000, got::add)
    MacFsEvents.report(root, root, "/Users/x/Pictures/proj/.cache/worktrees", 0x1, got::add)
    assertTrue(got.isEmpty(), "$got")
    MacFsEvents.report(root, root, "/Users/x/Pictures/proj/new.txt", 0x100, got::add)
    assertEquals(listOf(Dirty(File("/Users/x/Pictures/proj").toPath(), false)), got)
  }
}
