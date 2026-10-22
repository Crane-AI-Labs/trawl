package trawl.desktop

import com.craneai.findr.core.FileKind
import com.craneai.findr.core.IndexedFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import trawl.desktop.ui.BuiltResults
import trawl.desktop.ui.Dir
import trawl.desktop.ui.Entry
import trawl.desktop.ui.Group
import trawl.desktop.ui.NavSection
import trawl.desktop.ui.ResultNav
import trawl.desktop.ui.ResultsLayout

class ResultNavTest {
  private fun row(id: Long, kind: FileKind, strong: Boolean = true) =
    ResultRow(IndexedFile(id, "file:///$id", "$id", "f$id", kind, null, 1, 1), null, null, null, strong)

  private fun doc(id: Long, strong: Boolean = true) = row(id, FileKind.DOCUMENT, strong)
  private fun photo(id: Long, strong: Boolean = true) = row(id, FileKind.IMAGE, strong)

  private fun list(vararg ids: Long) = NavSection(grid = false, ids = ids.toList())
  private fun grid(vararg ids: Long) = NavSection(grid = true, ids = ids.toList())

  // --- building the list ---

  @Test fun headersAppearOnlyWhenDocumentsAndPhotosAreBothThere() {
    val onlyDocs = ResultsLayout.build(listOf(doc(1), doc(2)), showWeak = false, cols = 3)
    assertTrue(onlyDocs.entries.none { it is Entry.Header })
    val mixed = ResultsLayout.build(listOf(doc(1), photo(2), photo(3)), showWeak = false, cols = 3)
    assertEquals(listOf(Group.DOCUMENTS to 1, Group.PHOTOS to 2), mixed.entries.filterIsInstance<Entry.Header>().map { it.group to it.count })
  }

  @Test fun theGroupHoldingTheBestHitLeads() {
    val docsFirst = ResultsLayout.build(listOf(doc(1), photo(2)), false, 2)
    assertEquals(listOf(false, true), docsFirst.sections.map { it.grid })
    val photosFirst = ResultsLayout.build(listOf(photo(1), doc(2)), false, 2)
    assertEquals(listOf(true, false), photosFirst.sections.map { it.grid })
  }

  @Test fun photosFillTheGridRowByRowAndTheLastRowMayBeShort() {
    val b = ResultsLayout.build((1L..7L).map { photo(it) }, false, 3)
    val rows = b.entries.filterIsInstance<Entry.Tiles>().map { t -> t.rows.map { it.file.id } }
    assertEquals(listOf(listOf(1L, 2L, 3L), listOf(4L, 5L, 6L), listOf(7L)), rows)
    assertEquals((1L..7L).toList(), b.ids)
  }

  @Test fun weakMatchesSitUnderATogglePerhapsCollapsed() {
    val rows = listOf(doc(1), doc(2, strong = false), doc(3, strong = false))
    val collapsed = ResultsLayout.build(rows, showWeak = false, cols = 2)
    assertEquals(listOf(1L), collapsed.ids)
    assertEquals(Entry.Weak(2, expanded = false), collapsed.entries.last())
    val open = ResultsLayout.build(rows, showWeak = true, cols = 2)
    assertEquals(listOf(1L, 2L, 3L), open.ids)
    assertEquals(Entry.Weak(2, expanded = true), open.entries.filterIsInstance<Entry.Weak>().single())
  }

  @Test fun entryIndexFindsTheItemHoldingAnId() {
    val b: BuiltResults = ResultsLayout.build(listOf(doc(1), photo(2), photo(3), photo(4)), false, 2)
    assertEquals(b.entries.indexOfFirst { it is Entry.Doc }, b.entryIndexOf(1))
    assertEquals(b.entryIndexOf(2), b.entryIndexOf(3))   // same tile row
    assertTrue(b.entryIndexOf(4) > b.entryIndexOf(3))
    assertEquals(-1, b.entryIndexOf(99))
  }

  @Test fun zeroColumnsIsTreatedAsOne() {
    assertEquals(1, ResultsLayout.build(listOf(photo(1), photo(2)), false, 0).cols)
  }

  // --- arrow keys ---

  @Test fun downAndUpWalkAListItemByItem() {
    val s = listOf(list(1, 2, 3))
    assertEquals(2L, ResultNav.move(s, 1, 1, Dir.DOWN))
    assertEquals(1L, ResultNav.move(s, 1, 2, Dir.UP))
    assertEquals(3L, ResultNav.move(s, 1, 3, Dir.DOWN), "the end stays put")
    assertEquals(1L, ResultNav.move(s, 1, 1, Dir.UP), "the start stays put")
  }

  @Test fun leftAndRightDoNothingInAList() {
    val s = listOf(list(1, 2, 3))
    assertEquals(2L, ResultNav.move(s, 1, 2, Dir.LEFT))
    assertEquals(2L, ResultNav.move(s, 1, 2, Dir.RIGHT))
  }

  @Test fun aGridMovesByColumnsAndWithinARow() {
    val s = listOf(grid(1, 2, 3, 4, 5, 6, 7))   // 3 columns: rows 1-3, 4-6, 7
    assertEquals(4L, ResultNav.move(s, 3, 1, Dir.DOWN))
    assertEquals(2L, ResultNav.move(s, 3, 5, Dir.UP))
    assertEquals(2L, ResultNav.move(s, 3, 1, Dir.RIGHT))
    assertEquals(1L, ResultNav.move(s, 3, 2, Dir.LEFT))
    assertEquals(1L, ResultNav.move(s, 3, 1, Dir.LEFT), "no wrap to the row above")
  }

  @Test fun downFromTheRowAboveAShortLastRowLandsOnItsLastTile() {
    val s = listOf(grid(1, 2, 3, 4, 5, 6, 7))
    assertEquals(7L, ResultNav.move(s, 3, 6, Dir.DOWN))
    assertEquals(7L, ResultNav.move(s, 3, 5, Dir.DOWN))
    assertEquals(7L, ResultNav.move(s, 3, 7, Dir.DOWN), "nothing below the last row")
  }

  @Test fun crossingBetweenSectionsLandsSensibly() {
    val s = listOf(list(1, 2), grid(10, 11, 12, 13, 14))
    assertEquals(10L, ResultNav.move(s, 3, 2, Dir.DOWN), "list end into the grid's first tile")
    assertEquals(2L, ResultNav.move(s, 3, 10, Dir.UP), "grid top into the list's last row")
    assertEquals(2L, ResultNav.move(s, 3, 12, Dir.UP))
    assertEquals(13L, ResultNav.move(listOf(grid(10, 11, 12, 13, 14), list(20)), 3, 20, Dir.UP), "list top into the grid's last row, first tile")
    assertEquals(20L, ResultNav.move(listOf(grid(10, 11, 12, 13, 14), list(20)), 3, 14, Dir.DOWN))
  }

  @Test fun emptySectionsAreSkipped() {
    val s = listOf(list(1), list(), list(2))
    assertEquals(2L, ResultNav.move(s, 1, 1, Dir.DOWN))
    assertEquals(1L, ResultNav.move(s, 1, 2, Dir.UP))
  }

  @Test fun noSelectionSelectsTheFirstResult() {
    val s = listOf(list(), list(5, 6))
    assertEquals(5L, ResultNav.move(s, 1, null, Dir.DOWN))
    assertEquals(5L, ResultNav.move(s, 1, 999, Dir.UP), "an id that left the list counts as none")
    assertNull(ResultNav.move(emptyList(), 1, null, Dir.DOWN))
    assertEquals(5L, ResultNav.first(s))
    assertEquals(6L, ResultNav.last(s))
  }

  @Test fun pagingIsRepeatedArrowsAndStopsAtTheEnd() {
    val s = listOf(list(1, 2, 3, 4, 5, 6, 7, 8, 9, 10))
    assertEquals(6L, ResultNav.page(s, 1, 1, down = true, rows = 5))
    assertEquals(10L, ResultNav.page(s, 1, 6, down = true, rows = 50))
    assertEquals(1L, ResultNav.page(s, 1, 4, down = false, rows = 50))
    assertEquals(1L, ResultNav.page(s, 1, null, down = true, rows = 5), "no selection: the first one")
  }

  @Test fun quickLookStepsThroughReadingOrderAndClamps() {
    val ids = listOf(1L, 2L, 3L)
    assertEquals(2L, ResultNav.step(ids, 1, 1))
    assertEquals(1L, ResultNav.step(ids, 1, -1))
    assertEquals(3L, ResultNav.step(ids, 3, 1))
    assertEquals(1L, ResultNav.step(ids, 99, 1), "unknown id starts at the first")
    assertNull(ResultNav.step(emptyList(), 1, 1))
  }

  // --- what stays selected ---

  @Test fun aNewQuerySelectsItsBestHitAndARefreshKeepsTheSelection() {
    val ids = listOf(7L, 8L, 9L)
    assertEquals(7L, ResultNav.reconcile(9, "cake", "cakes", ids))
    assertEquals(9L, ResultNav.reconcile(9, "cake", " cake ", ids), "same query apart from spaces")
    assertEquals(7L, ResultNav.reconcile(5, "cake", "cake", ids), "the selection left the list")
    assertEquals(7L, ResultNav.reconcile(null, "cake", "cake", ids))
    assertNull(ResultNav.reconcile(7, "cake", "cake", emptyList()))
  }
}
