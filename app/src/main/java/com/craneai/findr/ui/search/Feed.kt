package com.craneai.findr.ui.search

import androidx.annotation.StringRes
import com.craneai.findr.R
import com.craneai.findr.core.FileKind
import com.craneai.findr.core.IndexedFile
import com.craneai.findr.core.MatchReason
import com.craneai.findr.core.SearchHit
import com.craneai.findr.ui.common.isVisual

/** One entry in the home list. The list is built in the view model; composables only draw it. */
sealed interface FeedItem {
  val key: String

  data class Header(@param:StringRes val title: Int) : FeedItem {
    override val key get() = "header-$title"
  }

  /** A thumbnail in the grid (photos, screenshots, videos with no text to show). */
  data class Cell(val hit: SearchHit) : FeedItem {
    override val key get() = "f${hit.file.id}"
  }

  /** A full-width row (documents, audio, and images whose matched text is the evidence). */
  data class Row(val hit: SearchHit) : FeedItem {
    override val key get() = "f${hit.file.id}"
  }

  /**
   * The photo peek: the heading over the top few photo cells that sit among the first document rows. [count] is every photo and
   * video in the strong group; [more] says the rest of them follow under the full "Photos and videos" heading (tap scrolls there).
   */
  data class PhotosPeek(val count: Int, val more: Boolean) : FeedItem {
    override val key get() = "photos-peek"
  }

  /** The fold: "Weaker matches (n)". */
  data class WeakToggle(val count: Int, val expanded: Boolean, val noStrongMatches: Boolean) : FeedItem {
    override val key get() = "weak-toggle"
  }
}

fun SearchHit.isRow(): Boolean = snippet != null || !file.kind.isVisual()

/** Recents are plain files; wrap them as hits so one list renders both. */
fun IndexedFile.asRecentHit() = SearchHit(file = this, score = 0.0, reason = MatchReason.WORDS)

/** Items in exactly the order given: photos as cells, documents as rows, no groups and no headings (a sorted list keeps its order). */
private fun flat(hits: List<SearchHit>): List<FeedItem> = hits.map { if (it.isRow()) FeedItem.Row(it) else FeedItem.Cell(it) }

/** Document rows shown before the photo peek, and how many photo cells it holds (one row of the grid on a phone). */
const val PEEK_AFTER_ROWS = 4
const val PEEK_CELLS = 3

/**
 * Hits grouped into photos (cells) and documents (rows); the group holding the best hit leads.
 *
 * The photo peek (D40): when documents lead, both kinds are strong and there are more than [PEEK_AFTER_ROWS] document rows, the top
 * [PEEK_CELLS] photos are lifted to just after the first [PEEK_AFTER_ROWS] rows, under a "Photos and videos (n)" heading, so a
 * photo query whose answer is a photo shows it on the first screen. Only the layout changes: no hit moves within its own group, a
 * file appears once (the rest of the photos follow the rows under the usual heading), and when photos lead nothing changes.
 */
private fun section(hits: List<SearchHit>, headers: Boolean, cellsTitle: Int, rowsTitle: Int): List<FeedItem> {
  val cells = hits.filter { !it.isRow() }
  val rows = hits.filter { it.isRow() }
  // whichever group holds the best hit leads
  val cellsFirst = hits.firstOrNull()?.let { !it.isRow() } ?: true
  val cellItems = cells.map { FeedItem.Cell(it) }
  val rowItems = rows.map { FeedItem.Row(it) }
  fun group(title: Int, items: List<FeedItem>) = if (items.isEmpty()) emptyList() else if (headers) listOf(FeedItem.Header(title)) + items else items
  val c = group(cellsTitle, cellItems)
  val r = group(rowsTitle, rowItems)
  if (cellsFirst) return c + r
  if (headers && rows.size > PEEK_AFTER_ROWS && cells.isNotEmpty()) {
    val rest = cellItems.drop(PEEK_CELLS)
    return listOf(FeedItem.Header(rowsTitle)) + rowItems.take(PEEK_AFTER_ROWS) +
      FeedItem.PhotosPeek(cells.size, more = rest.isNotEmpty()) + cellItems.take(PEEK_CELLS) +
      rowItems.drop(PEEK_AFTER_ROWS) + group(cellsTitle, rest)
  }
  return r + c
}

/**
 * Search results: strong hits grouped into photos/videos and documents/text, then the "Weaker matches" fold.
 * A file appears once; if it is strong it is never repeated under weak.
 */
fun buildResultFeed(hits: List<SearchHit>, strongCount: Int, weakExpanded: Boolean, sorted: Boolean = false): List<FeedItem> {
  val unique = hits.distinctBy { it.file.id }
  val strong = unique.take(strongCount.coerceIn(0, unique.size))
  val weak = unique.drop(strong.size)
  val showHeaders = !sorted && strong.any { it.isRow() } && strong.any { !it.isRow() }
  val out = ArrayList<FeedItem>()
  // A chosen sort (Newest, Oldest, Largest, Name) is one list in that order. Grouping into photos and documents would break it.
  out += if (sorted) flat(strong) else section(strong, showHeaders, R.string.section_photos_videos, R.string.section_documents_text)
  if (weak.isNotEmpty()) {
    out += FeedItem.WeakToggle(weak.size, weakExpanded, noStrongMatches = strong.isEmpty())
    if (weakExpanded) out += if (sorted) flat(weak) else section(weak, headers = false, R.string.section_photos_videos, R.string.section_documents_text)
  }
  return out
}

/** Browsing with a sort or a date chosen: the files in the order the index returned them, as one list with no headings. */
fun buildSortedFeed(files: List<IndexedFile>): List<FeedItem> = flat(files.distinctBy { it.id }.map { it.asRecentHit() })

/** Every file the list shows (unique, in list order, folded weak matches not included): what "Select all" selects. */
fun List<FeedItem>.shownFiles(): List<IndexedFile> = mapNotNull {
  when (it) {
    is FeedItem.Cell -> it.hit.file
    is FeedItem.Row -> it.hit.file
    else -> null
  }
}

/** Before typing: the newest files. Documents lead (receipts and letters are what people reach for), then photos. */
fun buildRecentFeed(files: List<IndexedFile>): List<FeedItem> {
  // "File" (OTHER: an APK, a zip, a database) is never featured here; it stays findable by name.
  val hits = files.filter { it.kind != FileKind.OTHER }.distinctBy { it.id }.map { it.asRecentHit() }
  val rows = hits.filter { it.isRow() }.take(6)
  val cells = hits.filter { !it.isRow() }
  val out = ArrayList<FeedItem>()
  if (rows.isNotEmpty()) {
    out += FeedItem.Header(R.string.section_recent_documents)
    out += rows.map { FeedItem.Row(it) }
  }
  if (cells.isNotEmpty()) {
    out += FeedItem.Header(R.string.section_recent_photos_videos)
    out += cells.map { FeedItem.Cell(it) }
  }
  return out
}
