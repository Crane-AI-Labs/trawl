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
import trawl.desktop.ui.ListStyle
import trawl.desktop.ui.Mix
import trawl.desktop.ui.ResultsLayout
import trawl.desktop.ui.groupTitle

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

  @Test fun videosAndRecordingsGetTheirOwnDedicatedSections() {
    val mixed = ResultsLayout.build(
      listOf(doc(1), row(2, FileKind.PDF), row(3, FileKind.VIDEO), row(4, FileKind.AUDIO), photo(99)),
      showWeak = false, cols = 3,
    )
    assertEquals(
      listOf(Group.DOCUMENTS to 2, Group.PHOTOS to 1, Group.VIDEOS to 1, Group.AUDIO to 1),
      mixed.entries.filterIsInstance<Entry.Header>().map { it.group to it.count },
    )
    Strings.use(java.util.Locale.ENGLISH)
    assertEquals("Documents and PDFs", groupTitle(Group.DOCUMENTS, Mix.DOCUMENTS))
    assertEquals("Videos", groupTitle(Group.VIDEOS))
    assertEquals("Audio", groupTitle(Group.AUDIO))
    assertEquals("Photos", groupTitle(Group.PHOTOS))
    assertEquals("Videos and recordings", groupTitle(Group.DOCUMENTS, Mix.MEDIA))
    assertEquals("Documents, videos and recordings", groupTitle(Group.DOCUMENTS, Mix.BOTH))
    // the Recent feed's "show more" row and header say the same
    val feed = ResultsLayout.build(List(8) { row(it + 1L, FileKind.VIDEO) } + photo(50), showWeak = false, cols = 3, style = ListStyle.RECENT)
    assertEquals(Mix.MEDIA, feed.entries.filterIsInstance<Entry.Header>().first().mix)
    assertEquals(Mix.MEDIA, feed.entries.filterIsInstance<Entry.More>().single().mix)
  }

  @Test fun leadingDocumentSectionIsCappedCleanlyWithoutSplittingAroundPhotos() {
    val rows = List(10) { doc(it + 1L) } + listOf(photo(50), row(60, FileKind.AUDIO))
    val capped = ResultsLayout.build(rows, showWeak = false, cols = 3, recentDocRows = 6)
    assertTrue(capped.entries.none { it is Entry.Peek }, "documents must never be split in half around photos")
    assertEquals(listOf(Group.DOCUMENTS, Group.PHOTOS, Group.AUDIO), capped.entries.filterIsInstance<Entry.Header>().map { it.group })
    val more = capped.entries.filterIsInstance<Entry.More>().single()
    assertEquals(Group.DOCUMENTS, more.group)
    assertEquals(4, more.hidden)
    val expanded = ResultsLayout.build(rows, showWeak = false, cols = 3, recentDocRows = Int.MAX_VALUE)
    assertTrue(expanded.entries.none { it is Entry.More })
    assertEquals(10, expanded.entries.filterIsInstance<Entry.Doc>().count { it.row.file.kind == FileKind.DOCUMENT })
  }

  @Test fun imagesAndDocumentsLeadBeforeVideosAndAudioUnlessQueryAsksForMedia() {
    val rows = listOf(
      row(1, FileKind.VIDEO),
      row(2, FileKind.AUDIO),
      photo(3),
      doc(4),
      row(5, FileKind.SCREENSHOT),
      row(6, FileKind.PDF),
    )
    // General query: Images (Photos/Screenshots) and Documents appear FIRST, ordered by best hit between them; Videos & Audio follow.
    val general = ResultsLayout.build(rows, showWeak = false, cols = 3, query = "kampala")
    assertEquals(
      listOf(Group.PHOTOS to 2, Group.DOCUMENTS to 2, Group.VIDEOS to 1, Group.AUDIO to 1),
      general.entries.filterIsInstance<Entry.Header>().map { it.group to it.count },
    )
    assertEquals(listOf(3L, 5L, 4L, 6L, 1L, 2L), general.ids)

    // List view on general search also prioritizes Images & Documents before Videos & Audio
    val generalList = ResultsLayout.build(rows, showWeak = false, cols = 3, forceList = true, query = "kampala")
    assertEquals(listOf(3L, 4L, 5L, 6L, 1L, 2L), generalList.ids)

    // Explicit media query keeps top Video/Audio match first
    val videoQuery = ResultsLayout.build(rows, showWeak = false, cols = 3, query = "wedding video")
    assertEquals(
      listOf(Group.VIDEOS to 1, Group.AUDIO to 1, Group.PHOTOS to 2, Group.DOCUMENTS to 2),
      videoQuery.entries.filterIsInstance<Entry.Header>().map { it.group to it.count },
    )
    assertEquals(listOf(1L, 2L, 3L, 5L, 4L, 6L), videoQuery.ids)

    // Explicit SortOrder (ListStyle.SORTED) preserves exact sorted order without re-bucketing
    val sorted = ResultsLayout.build(rows, showWeak = false, cols = 3, style = ListStyle.SORTED, forceList = true, query = "kampala")
    assertEquals(listOf(1L, 2L, 3L, 4L, 5L, 6L), sorted.ids)
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

  @Test fun forceListRendersEveryFileIncludingPhotosAndScreenshotsAsDocRows() {
    val mixed = listOf(
      photo(1),
      row(2, FileKind.SCREENSHOT),
      doc(3),
      row(4, FileKind.VIDEO),
      row(5, FileKind.AUDIO),
      photo(6, strong = false),
    )
    for (style in ListStyle.entries) {
      val collapsed = ResultsLayout.build(mixed, showWeak = false, cols = 4, style = style, forceList = true)
      assertTrue(collapsed.entries.none { it is Entry.Tiles }, "$style with forceList must emit zero Entry.Tiles")
      assertTrue(collapsed.sections.all { !it.grid }, "$style with forceList must have non-grid sections only")
      val expectedIds = if (style == ListStyle.RECENT) listOf(1L, 2L, 3L, 4L, 5L, 6L) else listOf(1L, 2L, 3L, 4L, 5L)
      assertEquals(expectedIds, collapsed.ids, "$style ids")
      if (style != ListStyle.RECENT) {
        val expanded = ResultsLayout.build(mixed, showWeak = true, cols = 4, style = style, forceList = true)
        assertTrue(expanded.entries.none { it is Entry.Tiles })
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L, 6L), expanded.ids)
      }
    }
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
    assertEquals(7L, ResultNav.reconcile(9, "cake", "cakes", ids, userMoved = true))
    assertEquals(9L, ResultNav.reconcile(9, "cake", " cake ", ids, userMoved = true), "same query apart from spaces")
    assertEquals(7L, ResultNav.reconcile(5, "cake", "cake", ids, userMoved = true), "the selection left the list")
    assertEquals(7L, ResultNav.reconcile(null, "cake", "cake", ids, userMoved = true))
    assertNull(ResultNav.reconcile(7, "cake", "cake", emptyList(), userMoved = true))
  }

  // Closeout check F1: the words arrive first and select their best hit; when meaning lands the same query is re-ranked, and Enter must open
  // the new best hit, not the words-phase one, unless the person has chosen a row themselves since.
  @Test fun meaningLandingOnTheWordsMovesTheSelectionToTheNewBestHit() {
    // Row 5 (the 4th row selected), row 8 (a document selected under a photo that became first), row 16 (the 3rd row selected).
    val cases = listOf(
      Triple(listOf(40L, 41L, 42L, 43L, 44L), 43L, listOf(44L, 40L, 41L, 42L, 43L)),
      Triple(listOf(50L, 51L, 52L), 52L, listOf(53L, 50L, 51L, 52L)),
      Triple(listOf(60L, 61L, 62L, 63L), 62L, listOf(63L, 60L, 61L, 62L)),
    )
    for ((_, wordsHit, reRanked) in cases) {
      assertEquals(reRanked.first(), ResultNav.reconcile(wordsHit, "the month", "the month", reRanked, userMoved = false), "follows the new best hit")
      assertEquals(wordsHit, ResultNav.reconcile(wordsHit, "the month", "the month", reRanked, userMoved = true), "a row the person chose stays chosen")
    }
    assertEquals(7L, ResultNav.reconcile(9, "cake", "cakes", listOf(7L, 8L, 9L), userMoved = true), "a new query still starts at its best hit")
    assertEquals(9L, ResultNav.reconcile(9, "cake", "cake", listOf(9L, 7L), userMoved = false), "already the best hit: unchanged")
  }
}
