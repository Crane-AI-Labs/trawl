package trawl.desktop

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import trawl.desktop.privacy.PrivacyController

/** Text a person typed into a chooser must never throw: it is "not a folder". (Matrix bug 1: InvalidPathException on Windows, 2026-10-22.) */
class SafePathTest {
  /** A NUL is refused by `File.toPath()` on every OS, as a second colon is on Windows. */
  private val bad = "/home/u/Pictures/ba\u0000d"

  @Test fun `an unreadable path is null and a good one is absolute and normal`() {
    assertNull(SafePath.of(File(bad)))
    assertNull(SafePath.of(bad))
    assertNotNull(SafePath.of(File("/home/u/Pictures/../Pictures/x")))
    assertEquals("/home/u/Pictures/x", SafePath.of("/home/u/Pictures/../Pictures/x")?.toString()?.replace('\\', '/')?.removePrefix("C:"))
  }

  @Test fun `a private folder that cannot be read is refused, and a bad root is ignored`() {
    val root = File("/home/u/Pictures")
    assertNull(PrivacyController.relativeFolder(File(bad), listOf(root)))
    assertEquals("Trips", PrivacyController.relativeFolder(File("/home/u/Pictures/Trips"), listOf(File(bad), root)))
  }

  @Test fun `the reading scope never throws on a bad entry`() {
    val chosen = listOf("/r/one", bad)
    assertEquals(1, Scope.paths(chosen).size)
    assertEquals(Scope.SkipProblem.NOT_A_FOLDER, Scope.skipProblem(File(bad), chosen))
    assertEquals(listOf("/r/one/x"), Scope.withSkipped(listOf("/r/one/x"), bad).map { it.replace('\\', '/') })
    assertEquals(listOf("/r/one/x"), Scope.pruneSkipped(listOf("/r/one/x", bad), listOf("/r/one")).map { it.replace('\\', '/') })
  }
}
