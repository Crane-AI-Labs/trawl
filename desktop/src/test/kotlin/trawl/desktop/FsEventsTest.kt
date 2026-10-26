package trawl.desktop

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FsEventsTest {
  private val root = File("/Users/x/Pictures").toPath()

  @Test fun aFileEventNamesItsFolderOnly() {
    val got = ArrayList<Dirty>()
    MacFsEvents.report(root, root, "/Users/x/Pictures/trip/a.jpg", 0x100, got::add)
    assertEquals(listOf(Dirty(File("/Users/x/Pictures/trip").toPath(), false)), got)
  }

  @Test fun aFolderEventIsLookedAtAllTheWayDown() {
    val got = ArrayList<Dirty>()
    MacFsEvents.report(root, root, "/Users/x/Pictures/new", 0x100 or 0x20000, got::add)
    assertEquals(listOf(Dirty(File("/Users/x/Pictures/new").toPath(), true)), got)
  }

  @Test fun aFolderThatOnlyHadItsAttributesTouchedIsLookedAtOnlyForItsOwnFiles() {
    val dir = File("/Users/x/Pictures/busy").toPath()
    for (flags in listOf(0x400, 0x1000, 0x2000, 0x8000, 0x4000)) {   // inode meta, modified, Finder info, xattr, owner: nothing below changed
      val got = ArrayList<Dirty>()
      MacFsEvents.report(root, root, dir.toString(), flags or 0x20000, got::add)
      assertEquals(listOf(Dirty(dir, false)), got, "flags 0x${flags.toString(16)}")
    }
    for (flags in listOf(0x100, 0x200, 0x800, 0x0)) {   // created, removed, renamed (moved in), or no item flag at all: look all the way down
      val got = ArrayList<Dirty>()
      MacFsEvents.report(root, root, dir.toString(), flags or 0x20000, got::add)
      assertEquals(listOf(Dirty(dir, true)), got, "flags 0x${flags.toString(16)}")
    }
  }

  @Test fun hiddenFilesAreIgnoredAndTheKernelsRealPathIsMappedBack() {
    val got = ArrayList<Dirty>()
    MacFsEvents.report(root, root, "/Users/x/Pictures/.DS_Store", 0x100, got::add)
    assertTrue(got.isEmpty())
    val chosen = File("/var/folders/t").toPath()
    val real = File("/private/var/folders/t").toPath()
    MacFsEvents.report(real, chosen, "/private/var/folders/t/doc.txt", 0x100, got::add)
    assertEquals(listOf(Dirty(chosen, false)), got)
  }

  @Test fun scanSubdirsMeansWalkEverythingBelow() {
    val got = ArrayList<Dirty>()
    MacFsEvents.report(root, root, "/Users/x/Pictures/a", 0x1, got::add)
    assertEquals(listOf(Dirty(File("/Users/x/Pictures/a").toPath(), true)), got)
  }

  /** The real kernel stream, on a Mac only (a no-op elsewhere). */
  @Test fun theRealStreamReportsAnAddedAndADeletedFile() {
    if (Platform.os != Os.MAC) return
    assertTrue(MacFsEvents.available(), "CoreServices did not load through JNA")
    val dir = Files.createTempDirectory("trawl-fsevents").toFile()   // /var/folders/..., a symlink to /private/var: exercises the mapping
    val got = CopyOnWriteArrayList<Dirty>()
    MacFsEvents.watch(dir.toPath(), got::add).use {
      Thread.sleep(300)
      val f = File(dir, "new.txt").apply { writeText("hi") }
      assertTrue(waitFor { got.any { d -> d.dir == dir.toPath() && !d.recursive } }, "no report for a new file: $got")
      got.clear(); Thread.sleep(800)
      f.delete()
      assertTrue(waitFor { got.any { d -> d.dir == dir.toPath() } }, "no report for a deleted file: $got")
    }
    dir.deleteRecursively()
  }

  private fun waitFor(ms: Long = 10_000, ok: () -> Boolean): Boolean {
    val end = System.currentTimeMillis() + ms
    while (System.currentTimeMillis() < end) { if (ok()) return true; Thread.sleep(50) }
    return ok()
  }
}
