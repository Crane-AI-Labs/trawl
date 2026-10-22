package trawl.desktop

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import trawl.desktop.ui.Rect
import trawl.desktop.ui.SavedWindow
import trawl.desktop.ui.WindowMemory
import trawl.desktop.ui.WindowSizing
import trawl.desktop.ui.WindowStore

class WindowMemoryTest {
  private val laptop = Rect(0, 38, 1512, 944)
  private val monitor = Rect(1512, 0, 1920, 1080)
  private val first = WindowSizing.plan(laptop.w, laptop.h)

  @Test fun nothingRememberedOpensTheFirstRunWay() {
    val p = WindowMemory.plan(null, listOf(laptop), first)
    assertEquals(first.width to first.height, p.width to p.height)
    assertNull(p.x); assertNull(p.y)
    assertFalse(p.restored)
  }

  @Test fun aSensibleSavedWindowIsRestoredAsItWas() {
    val p = WindowMemory.plan(SavedWindow(100, 120, 1000, 700), listOf(laptop), first)
    assertEquals(listOf(100, 120, 1000, 700), listOf(p.x, p.y, p.width, p.height))
    assertTrue(p.restored)
    assertFalse(p.maximized)
  }

  @Test fun maximisedIsRemembered() {
    assertTrue(WindowMemory.plan(SavedWindow(0, 38, 1512, 944, maximized = true), listOf(laptop), first).maximized)
  }

  @Test fun aWindowOnAMonitorThatIsGoneComesBackCentred() {
    val saved = SavedWindow(1700, 100, 1000, 700)   // on the external monitor
    assertTrue(WindowMemory.plan(saved, listOf(laptop, monitor), first).restored)
    val p = WindowMemory.plan(saved, listOf(laptop), first)
    assertFalse(p.restored)
    assertNull(p.x)
    assertEquals(first.width, p.width)
  }

  @Test fun aWindowWhoseTitleBarIsOffEveryScreenIsNotRestored() {
    assertFalse(WindowMemory.plan(SavedWindow(100, -400, 1000, 700), listOf(laptop), first).restored)
    assertFalse(WindowMemory.plan(SavedWindow(100, 2000, 1000, 700), listOf(laptop), first).restored)
  }

  @Test fun aBigWindowShrinksToASmallerScreenAndSlidesBackInside() {
    val small = Rect(0, 25, 1280, 775)
    val p = WindowMemory.plan(SavedWindow(600, 300, 1500, 1000), listOf(small), first)
    assertTrue(p.restored)
    assertEquals(1280, p.width)
    assertEquals(775, p.height)
    assertEquals(0, p.x)
    assertEquals(25, p.y)
  }

  @Test fun aTinyWindowIsRaisedToTheMinimum() {
    val p = WindowMemory.plan(SavedWindow(100, 100, 300, 200), listOf(laptop), first)
    assertEquals(first.minWidth to first.minHeight, p.width to p.height)
  }

  @Test fun garbageSizesAreIgnored() {
    assertFalse(WindowMemory.plan(SavedWindow(0, 38, 0, 700), listOf(laptop), first).restored)
    assertFalse(WindowMemory.plan(SavedWindow(0, 38, 800, -1), listOf(laptop), first).restored)
    assertFalse(WindowMemory.plan(SavedWindow(100, 100, 800, 600), emptyList(), first).restored)
  }

  @Test fun theSharedAreaOfTwoRectanglesIsCountedInSquareUnits() {
    assertEquals(0L, Rect(0, 0, 10, 10).overlap(Rect(20, 20, 5, 5)))
    assertEquals(25L, Rect(0, 0, 10, 10).overlap(Rect(5, 5, 20, 20)))
    assertEquals(100L, Rect(0, 0, 10, 10).overlap(Rect(-5, -5, 50, 50)))
  }

  @Test fun theStoreRoundTripsAndSurvivesGarbageAndAMissingFile() {
    val dir = Files.createTempDirectory("trawl-window").toFile()
    try {
      val store = WindowStore(File(dir, "nested"))   // the folder does not exist yet
      assertNull(store.load())
      val w = SavedWindow(12, 34, 1120, 742, maximized = true)
      store.save(w)
      assertEquals(w, store.load())
      assertFalse(File(dir, "nested/window-state.json.tmp").exists(), "no temp file is left behind")
      File(dir, "nested/window-state.json").writeText("{ not json")
      assertNull(store.load())
      File(dir, "nested/window-state.json").writeText("""{"x":1,"y":2,"width":3,"height":4}""")
      assertNotNull(store.load())
      assertFalse(store.load()!!.maximized, "maximized defaults to false")
    } finally {
      dir.deleteRecursively()
    }
  }
}
