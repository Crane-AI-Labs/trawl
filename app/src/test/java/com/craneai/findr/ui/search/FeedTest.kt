package com.craneai.findr.ui.search

import com.craneai.findr.R
import com.craneai.findr.core.FileKind
import com.craneai.findr.core.IndexedFile
import com.craneai.findr.core.MatchReason
import com.craneai.findr.core.SearchHit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedTest {
  private fun file(id: Long, kind: FileKind) = IndexedFile(
    id = id, uri = "fake://$id", relativePath = "x/$id", name = "f$id", kind = kind, mimeType = null,
    sizeBytes = 1, modifiedMillis = id,
  )

  private fun hit(id: Long, kind: FileKind, snippet: String? = null) =
    SearchHit(file(id, kind), score = 1.0 / id, reason = MatchReason.BOTH, snippet = snippet)

  @Test fun recentNeverFeaturesOtherFiles() {
    val feed = buildRecentFeed(listOf(file(1, FileKind.OTHER), file(2, FileKind.PDF), file(3, FileKind.DOCUMENT), file(4, FileKind.OTHER), file(5, FileKind.IMAGE)))
    val shown = feed.shownFiles().map { it.id }
    assertEquals(setOf(2L, 3L, 5L), shown.toSet())
    assertTrue(feed.shownFiles().none { it.kind == FileKind.OTHER })
    // only "other" files: nothing to feature, no empty headings
    assertTrue(buildRecentFeed(listOf(file(1, FileKind.OTHER))).isEmpty())
  }

  @Test fun photosAreCellsAndDocumentsAreRows() {
    val feed = buildResultFeed(listOf(hit(1, FileKind.IMAGE), hit(2, FileKind.PDF, "«a»")), strongCount = 2, weakExpanded = false)
    val kinds = feed.map { it::class.simpleName }
    assertTrue(kinds.contains("Cell"))
    assertTrue(kinds.contains("Row"))
    // both groups present, so each gets a header
    assertEquals(2, feed.count { it is FeedItem.Header })
  }

  @Test fun imageWithMatchedTextIsARow() {
    val feed = buildResultFeed(listOf(hit(1, FileKind.SCREENSHOT, "«momo» receipt")), 1, false)
    assertTrue(feed.single() is FeedItem.Row)
  }

  @Test fun weakMatchesHideBehindTheFold() {
    val hits = listOf(hit(1, FileKind.IMAGE), hit(2, FileKind.IMAGE), hit(3, FileKind.IMAGE))
    val folded = buildResultFeed(hits, strongCount = 1, weakExpanded = false)
    val toggle = folded.filterIsInstance<FeedItem.WeakToggle>().single()
    assertEquals(2, toggle.count)
    assertEquals(false, toggle.expanded)
    assertEquals(0, folded.filterIsInstance<FeedItem.Cell>().count { it.hit.file.id > 1 })

    val open = buildResultFeed(hits, strongCount = 1, weakExpanded = true)
    assertEquals(3, open.filterIsInstance<FeedItem.Cell>().size)
    assertTrue(open.filterIsInstance<FeedItem.WeakToggle>().single().expanded)
  }

  @Test fun noStrongMatchesIsSaidPlainly() {
    val feed = buildResultFeed(listOf(hit(1, FileKind.IMAGE)), strongCount = 0, weakExpanded = false)
    assertTrue(feed.filterIsInstance<FeedItem.WeakToggle>().single().noStrongMatches)
  }

  @Test fun keysAreUnique() {
    val hits = (1L..20L).map { hit(it, if (it % 2 == 0L) FileKind.IMAGE else FileKind.DOCUMENT, if (it % 2 == 0L) null else "«x»") }
    val keys = buildResultFeed(hits + hits, strongCount = 10, weakExpanded = true).map { it.key }
    assertEquals(keys.size, keys.toSet().size)
  }

  @Test fun aFileIsNeverStrongAndWeak() {
    val h = hit(1, FileKind.IMAGE)
    val feed = buildResultFeed(listOf(h, h), strongCount = 1, weakExpanded = true)
    assertEquals(1, feed.filterIsInstance<FeedItem.Cell>().size)
    assertTrue(feed.none { it is FeedItem.WeakToggle })
  }

  @Test fun recentsLeadWithDocuments() {
    val feed = buildRecentFeed(listOf(file(1, FileKind.IMAGE), file(2, FileKind.PDF)))
    assertTrue(feed.first() is FeedItem.Header)
    assertTrue(feed[1] is FeedItem.Row)
    assertTrue(feed.last() is FeedItem.Cell)
  }

  @Test fun emptyInputGivesEmptyFeed() {
    assertTrue(buildRecentFeed(emptyList()).isEmpty())
    assertTrue(buildResultFeed(emptyList(), 0, false).isEmpty())
  }

  @Test fun aSortedFeedIsOneFlatListInTheGivenOrder() {
    val files = listOf(file(3, FileKind.IMAGE), file(1, FileKind.PDF), file(2, FileKind.IMAGE), file(3, FileKind.IMAGE))
    val feed = buildSortedFeed(files)
    assertEquals(0, feed.count { it is FeedItem.Header })
    assertEquals(listOf(3L, 1L, 2L), feed.shownFiles().map { it.id })
  }

  @Test fun sortedResultsKeepTheFoldAndHaveNoHeadings() {
    val hits = listOf(hit(1, FileKind.IMAGE), hit(2, FileKind.PDF, "«a»"), hit(3, FileKind.IMAGE))
    val feed = buildResultFeed(hits, strongCount = 2, weakExpanded = false, sorted = true)
    assertEquals(0, feed.count { it is FeedItem.Header })
    assertEquals(listOf(1L, 2L), feed.shownFiles().map { it.id })
    assertEquals(1, feed.filterIsInstance<FeedItem.WeakToggle>().single().count)
  }

  // ---- the photo peek (D40) ---------------------------------------------------------------------------------------

  private fun docs(range: LongRange) = range.map { hit(it, FileKind.PDF, "«x»") }
  private fun photos(range: LongRange) = range.map { hit(it, FileKind.IMAGE) }
  private fun FeedItem.tag() = when (this) {
    is FeedItem.Header -> "H"
    is FeedItem.PhotosPeek -> "P"
    is FeedItem.Cell -> "C"
    is FeedItem.Row -> "R"
    is FeedItem.WeakToggle -> "W"
  }
  private fun List<FeedItem>.shape() = joinToString("") { it.tag() }

  @Test fun peekLiftsTheTopThreePhotosAfterTheFirstFourDocuments() {
    val feed = buildResultFeed(docs(1L..6L) + photos(11L..15L), strongCount = 11, weakExpanded = false)
    // documents heading, 4 rows, the peek and 3 cells, the 2 other rows, the photos heading and the 2 other cells
    assertEquals("HRRRRPCCCRRHCC", feed.shape())
    assertEquals(listOf(1L, 2L, 3L, 4L, 11L, 12L, 13L, 5L, 6L, 14L, 15L), feed.shownFiles().map { it.id })
    val peek = feed.filterIsInstance<FeedItem.PhotosPeek>().single()
    assertEquals(5, peek.count)
    assertEquals(true, peek.more)
    assertEquals(R.string.section_documents_text, (feed.first() as FeedItem.Header).title)
    assertEquals(R.string.section_photos_videos, (feed.filterIsInstance<FeedItem.Header>().last()).title)
  }

  @Test fun peekChangesNoOrderWithinAGroupAndShowsEveryFileOnce() {
    val hits = docs(1L..9L) + photos(21L..30L)
    val plain = buildResultFeed(hits, strongCount = hits.size, weakExpanded = false, sorted = false)
    assertEquals(hits.map { it.file.id }.toSet(), plain.shownFiles().map { it.id }.toSet())
    assertEquals(hits.size, plain.shownFiles().size)
    assertEquals(plain.map { it.key }.size, plain.map { it.key }.toSet().size)
    assertEquals((1L..9L).toList(), plain.filterIsInstance<FeedItem.Row>().map { it.hit.file.id })
    assertEquals((21L..30L).toList(), plain.filterIsInstance<FeedItem.Cell>().map { it.hit.file.id })
  }

  @Test fun aSmallPhotoGroupIsShownWholeInThePeekWithNothingBelow() {
    val feed = buildResultFeed(docs(1L..6L) + photos(11L..12L), strongCount = 8, weakExpanded = false)
    assertEquals("HRRRRPCCRR", feed.shape())
    val peek = feed.filterIsInstance<FeedItem.PhotosPeek>().single()
    assertEquals(2, peek.count)
    assertEquals(false, peek.more)
    assertEquals(1, feed.count { it is FeedItem.Header })
  }

  @Test fun noPeekWhenTheFirstFourRowsAreAllTheDocuments() {
    // the photos already follow the 4 rows: the usual layout
    val feed = buildResultFeed(docs(1L..4L) + photos(11L..15L), strongCount = 9, weakExpanded = false)
    assertEquals("HRRRRHCCCCC", feed.shape())
    assertTrue(feed.none { it is FeedItem.PhotosPeek })
  }

  @Test fun noPeekWhenPhotosLead() {
    val feed = buildResultFeed(photos(1L..5L) + docs(11L..20L), strongCount = 15, weakExpanded = false)
    assertEquals("HCCCCCHRRRRRRRRRR", feed.shape())
    assertTrue(feed.none { it is FeedItem.PhotosPeek })
  }

  @Test fun noPeekWhenOnlyWeakHitsArePhotos() {
    // the photos are weak matches: nothing strong to lift, and the folded group stays folded
    val hits = docs(1L..6L) + photos(11L..13L)
    val folded = buildResultFeed(hits, strongCount = 6, weakExpanded = false)
    assertEquals("RRRRRRW", folded.shape())
    val open = buildResultFeed(hits, strongCount = 6, weakExpanded = true)
    assertEquals("RRRRRRWCCC", open.shape())
    assertTrue(open.none { it is FeedItem.PhotosPeek })
  }

  @Test fun noPeekWhenThereAreNoPhotos() {
    val feed = buildResultFeed(docs(1L..8L), strongCount = 8, weakExpanded = false)
    assertEquals("RRRRRRRR", feed.shape())
  }

  @Test fun noPeekInASortedList() {
    val feed = buildResultFeed(docs(1L..6L) + photos(11L..15L), strongCount = 11, weakExpanded = false, sorted = true)
    assertTrue(feed.none { it is FeedItem.PhotosPeek || it is FeedItem.Header })
    assertEquals((1L..6L).toList() + (11L..15L).toList(), feed.shownFiles().map { it.id })
  }

  @Test fun theWeakSectionIsNeverPeeked() {
    val hits = docs(1L..2L) + photos(11L..12L) + docs(3L..9L) + photos(13L..15L)
    val feed = buildResultFeed(hits, strongCount = 4, weakExpanded = true)
    assertTrue(feed.none { it is FeedItem.PhotosPeek })
  }

  @Test fun anImageWhoseTextMatchedCountsAsADocumentRow() {
    // screenshots with matched text are rows: five of them lead, and the real photos get the peek
    val shots = (1L..5L).map { hit(it, FileKind.SCREENSHOT, "«momo»") }
    val feed = buildResultFeed(shots + photos(11L..13L), strongCount = 8, weakExpanded = false)
    assertEquals("HRRRRPCCCR", feed.shape())
  }
}
