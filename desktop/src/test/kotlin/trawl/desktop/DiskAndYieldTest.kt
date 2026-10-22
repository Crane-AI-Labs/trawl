package trawl.desktop

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The disk rule, the yield while searching, and the folder permission check: each with made-up numbers and clocks. */
class DiskAndYieldTest {
  private val mb = DiskSpace.MB
  private val gb = 1024 * mb

  // ---- the disk rule ------------------------------------------------------------------------------------------------------------

  @Test fun theLevelsFollowTheWrittenRule() {
    assertEquals(DiskSpace.Level.OK, DiskSpace.level(50 * gb))
    assertEquals(DiskSpace.Level.OK, DiskSpace.level(2 * gb))
    assertEquals(DiskSpace.Level.WARN, DiskSpace.level(2 * gb - 1))
    assertEquals(DiskSpace.Level.WARN, DiskSpace.level(500 * mb))
    assertEquals(DiskSpace.Level.LOW, DiskSpace.level(500 * mb - 1))
    assertEquals(DiskSpace.Level.LOW, DiskSpace.level(0))
  }

  @Test fun readingResumesOnlyAboveOneGigabyte() {
    assertEquals(DiskSpace.Level.LOW, DiskSpace.level(700 * mb, previous = DiskSpace.Level.LOW))
    assertEquals(DiskSpace.Level.LOW, DiskSpace.level(gb - 1, previous = DiskSpace.Level.LOW))
    assertEquals(DiskSpace.Level.WARN, DiskSpace.level(gb, previous = DiskSpace.Level.LOW), "above 1 GB it is no longer held, but 2 GB is still a warning")
    assertEquals(DiskSpace.Level.WARN, DiskSpace.level(700 * mb, previous = DiskSpace.Level.WARN), "only LOW has the margin")
  }

  @Test fun anIndexThatWillNotFitIsAWarningEvenWithRoomToday() {
    assertEquals(DiskSpace.Level.OK, DiskSpace.level(10 * gb, growthBytes = 5 * gb))
    assertEquals(DiskSpace.Level.WARN, DiskSpace.level(10 * gb, growthBytes = 9800 * mb), "10 GB free, 9.8 GB to come, 500 MB must stay free")
  }

  @Test fun freeSpaceIsReadFromTheNearestFolderThatExists() {
    val d = Files.createTempDirectory("trawl-disk").toFile()
    val free = assertNotNull(DiskSpace.freeBytes(File(d, "not/made/yet")))
    assertTrue(free > 0)
    d.deleteRecursively()
  }

  // ---- yielding to a search -----------------------------------------------------------------------------------------------------

  private class Clock { var now = 1_000_000L }

  @Test fun readingWaitsOutASearchAndNotLonger() {
    val c = Clock(); var slept = 0L
    val y = SearchYield(quietMs = 1_000, capMs = 5_000, nowMs = { c.now }, sleep = { c.now += it; slept += it })
    assertEquals(0, y.awaitQuiet(), "nobody searched: no wait")
    y.touch()
    assertTrue(y.searching())
    val waited = y.awaitQuiet()
    assertTrue(waited in 1_000..1_100, "waited $waited")
    assertFalse(y.searching())
    assertEquals(waited, slept)
  }

  @Test fun aPersonWhoKeepsTypingCannotStopReadingForGood() {
    val c = Clock()
    lateinit var y: SearchYield
    y = SearchYield(quietMs = 1_000, capMs = 3_000, nowMs = { c.now }, sleep = { c.now += it; y.touch() })   // a query every step
    y.touch()
    assertEquals(3_000, y.awaitQuiet(), "gives up at the cap")
  }

  @Test fun aNewJobOrPauseEndsTheWaitAtOnce() {
    val c = Clock()
    val y = SearchYield(quietMs = 10_000, capMs = 30_000, nowMs = { c.now }, sleep = { c.now += it })
    y.touch()
    var steps = 0
    val waited = y.awaitQuiet { ++steps > 2 }
    assertEquals(200, waited)
  }

  // ---- the folders' permission --------------------------------------------------------------------------------------------------

  @Test fun noPermissionNeedsFoldersThatAreThereAndNoneOfThemReadable() {
    val here = Files.createTempDirectory("trawl-acc").toFile().toPath()
    val gone = File(here.toFile(), "unplugged").toPath()
    assertFalse(FolderAccess.denied(emptyList()))
    assertFalse(FolderAccess.denied(listOf(gone)) { false }, "a folder that is not there is not a permission problem")
    assertTrue(FolderAccess.denied(listOf(here, gone)) { false })
    assertFalse(FolderAccess.denied(listOf(here)) { true })
    assertFalse(FolderAccess.denied(listOf(here, here.resolve("x"))) { it == here }, "one readable folder is enough")
    here.toFile().deleteRecursively()
  }

  @Test fun theRealCheckSeesAFolderWithNoReadRights() {
    val d = Files.createTempDirectory("trawl-acc2").toFile()
    File(d, "a.txt").writeText("x")
    assertTrue(FolderAccess.canList(d.toPath()))
    val locked = File(d, "locked").also { it.mkdir() }
    if (locked.setReadable(false) && !locked.canRead()) {   // a user who is root can read anything: nothing to prove there
      assertFalse(FolderAccess.canList(locked.toPath()))
      locked.setReadable(true)
    }
    assertNull(null)
    d.deleteRecursively()
  }
}
