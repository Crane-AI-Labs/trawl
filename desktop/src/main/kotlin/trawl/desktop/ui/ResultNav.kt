package trawl.desktop.ui

import trawl.desktop.ResultRow

/** One block of results that arrow keys walk as a unit: a list (one column) or a photo grid (several). */
data class NavSection(val grid: Boolean, val ids: List<Long>)

/** What the results list draws, top to bottom. Each entry is one lazy-list item. */
sealed interface Entry {
  val key: String

  /** A group label ("Documents", "Photos") with its count, shown only when both groups are present. */
  data class Header(val group: Group, val count: Int, val mix: Mix = Mix.DOCUMENTS) : Entry { override val key get() = "h-${group.name}" }

  /** One document, PDF or other non-picture result: one compact row. */
  data class Doc(val row: ResultRow) : Entry { override val key get() = "d-${row.file.id}" }

  /** One row of the photo grid; the last row of a grid may be short. */
  data class Tiles(val rows: List<ResultRow>) : Entry { override val key get() = "t-${rows.first().file.id}" }

  /** "Show weaker matches (12)" and its collapse. */
  data class Weak(val count: Int, val expanded: Boolean) : Entry { override val key get() = "weak" }

  /** The photo peek's heading: the top few photos sit under it, among the first document rows. [more] says the rest follow under "Photos". */
  data class Peek(val count: Int, val more: Boolean) : Entry { override val key get() = "peek" }

  /** "Show 214 more documents": the Recent list shows a few document rows, then photos; the rest are one click away. */
  data class More(val group: Group, val hidden: Int, val mix: Mix = Mix.DOCUMENTS) : Entry { override val key get() = "more-${group.name}" }
}

enum class Group { DOCUMENTS, PHOTOS }

/**
 * What the non-picture group holds, so its label does not call a video a document: only documents and PDFs, only videos and recordings,
 * or both. (Pictures are always the other group. The layout and the ranking inside it are unchanged: only the words differ.)
 */
enum class Mix {
  DOCUMENTS, MEDIA, BOTH;

  companion object {
    fun of(rows: List<ResultRow>): Mix {
      val media = rows.count { it.file.kind == com.craneai.findr.core.FileKind.VIDEO || it.file.kind == com.craneai.findr.core.FileKind.AUDIO }
      return when { media == 0 -> DOCUMENTS; media == rows.size -> MEDIA; else -> BOTH }
    }
  }
}

/**
 * How the list is put together. [RESULTS] groups strong matches into documents and photos (the group with the best hit leads, with the photo
 * peek when documents lead). [SORTED] is one flat list in the order given: a chosen sort or date must not be broken up by grouping.
 * [RECENT] is the feed before anything is typed: a few documents, then the photos.
 */
enum class ListStyle { RESULTS, SORTED, RECENT }

/** The results turned into list entries and arrow-key sections. Built once per (results, weak toggle, column count), so drawing and navigation agree. */
data class BuiltResults(val entries: List<Entry>, val sections: List<NavSection>, val cols: Int) {
  /** Every selectable id in reading order, which is also Quick Look's order. */
  val ids: List<Long> by lazy { sections.flatMap { it.ids } }

  /** The lazy-list item holding [id], or -1; used to scroll the selection into view. */
  fun entryIndexOf(id: Long): Int = entries.indexOfFirst {
    when (it) {
      is Entry.Doc -> it.row.file.id == id
      is Entry.Tiles -> it.rows.any { r -> r.file.id == id }
      else -> false
    }
  }
}

object ResultsLayout {
  /** Document rows the photo peek waits for, and the Recent feed's document rows (the phone's own numbers). */
  const val PEEK_AFTER_ROWS = 4
  const val RECENT_DOC_ROWS = 6
  const val PEEK_MAX_CELLS = 6

  /**
   * Strong matches first, grouped into documents and photos with whichever group holds the best hit leading; then, when there are
   * weaker matches, a toggle and (if expanded) those, grouped the same way. Nothing is reordered inside a group: ranking is the searcher's.
   * A file appears once.
   */
  fun build(rows: List<ResultRow>, showWeak: Boolean, cols: Int, style: ListStyle = ListStyle.RESULTS, recentDocRows: Int = RECENT_DOC_ROWS): BuiltResults {
    val c = cols.coerceAtLeast(1)
    val entries = mutableListOf<Entry>()
    val sections = mutableListOf<NavSection>()
    val unique = rows.distinctBy { it.file.id }
    if (style == ListStyle.RECENT) { recent(unique, c, recentDocRows, entries, sections); return BuiltResults(entries, sections, c) }
    val strong = unique.filter { it.strong }
    val weak = unique.filterNot { it.strong }
    if (style == ListStyle.SORTED) flat(strong, c, entries, sections) else addGroups(strong, c, entries, sections, headers = true)
    if (weak.isNotEmpty()) {
      entries += Entry.Weak(weak.size, showWeak)
      if (showWeak) { if (style == ListStyle.SORTED) flat(weak, c, entries, sections) else addGroups(weak, c, entries, sections, headers = false) }
    }
    return BuiltResults(entries, sections, c)
  }

  /** Items in exactly the order given: documents as rows, a run of pictures as grid rows, no groups and no headings. */
  private fun flat(rows: List<ResultRow>, cols: Int, entries: MutableList<Entry>, sections: MutableList<NavSection>) {
    var i = 0
    while (i < rows.size) {
      val tiles = isTile(rows[i])
      var j = i
      while (j < rows.size && isTile(rows[j]) == tiles) j++
      val run = rows.subList(i, j)
      if (tiles) run.chunked(cols).forEach { entries += Entry.Tiles(it) } else run.forEach { entries += Entry.Doc(it) }
      sections += NavSection(grid = tiles, ids = run.map { it.file.id })
      i = j
    }
  }

  /** Before typing: the newest documents (a few), then every picture. */
  private fun recent(rows: List<ResultRow>, cols: Int, docRows: Int, entries: MutableList<Entry>, sections: MutableList<NavSection>) {
    val docs = rows.filterNot(::isTile)
    val photos = rows.filter(::isTile)
    val shown = docs.take(docRows)
    if (shown.isNotEmpty()) {
      if (photos.isNotEmpty()) entries += Entry.Header(Group.DOCUMENTS, docs.size, Mix.of(docs))
      shown.forEach { entries += Entry.Doc(it) }
      sections += NavSection(grid = false, ids = shown.map { it.file.id })
      if (docs.size > shown.size) entries += Entry.More(Group.DOCUMENTS, docs.size - shown.size, Mix.of(docs.drop(shown.size)))
    }
    if (photos.isNotEmpty()) {
      if (shown.isNotEmpty()) entries += Entry.Header(Group.PHOTOS, photos.size)
      photos.chunked(cols).forEach { entries += Entry.Tiles(it) }
      sections += NavSection(grid = true, ids = photos.map { it.file.id })
    }
  }

  private fun addGroups(rows: List<ResultRow>, cols: Int, entries: MutableList<Entry>, sections: MutableList<NavSection>, headers: Boolean) {
    if (rows.isEmpty()) return
    val docs = rows.filterNot(::isTile)
    val photos = rows.filter(::isTile)
    val photosFirst = isTile(rows.first())
    val mixed = docs.isNotEmpty() && photos.isNotEmpty()
    // The photo peek: documents lead, both kinds are strong, and there are more than a screenful of document rows. The top photos are lifted
    // to just after the first rows so a photo query whose answer is a photo shows it on the first screen. No hit moves within its own group
    // and a file appears once (the rest of the photos follow under the usual heading).
    val peek = mixed && headers && !photosFirst && docs.size > PEEK_AFTER_ROWS
    fun docsOut() {
      if (docs.isEmpty()) return
      if (mixed && headers) entries += Entry.Header(Group.DOCUMENTS, docs.size, Mix.of(docs))
      if (peek) {
        val take = cols.coerceIn(1, PEEK_MAX_CELLS)
        val top = photos.take(take)
        val rest = photos.drop(take)
        val head = docs.take(PEEK_AFTER_ROWS)
        val tail = docs.drop(PEEK_AFTER_ROWS)
        head.forEach { entries += Entry.Doc(it) }
        sections += NavSection(grid = false, ids = head.map { it.file.id })
        entries += Entry.Peek(photos.size, more = rest.isNotEmpty())
        entries += Entry.Tiles(top)
        sections += NavSection(grid = true, ids = top.map { it.file.id })
        tail.forEach { entries += Entry.Doc(it) }
        sections += NavSection(grid = false, ids = tail.map { it.file.id })
        if (rest.isNotEmpty()) {
          entries += Entry.Header(Group.PHOTOS, rest.size)
          rest.chunked(cols).forEach { entries += Entry.Tiles(it) }
          sections += NavSection(grid = true, ids = rest.map { it.file.id })
        }
        return
      }
      docs.forEach { entries += Entry.Doc(it) }
      sections += NavSection(grid = false, ids = docs.map { it.file.id })
    }
    fun photosOut() {
      if (photos.isEmpty()) return
      if (mixed && headers) entries += Entry.Header(Group.PHOTOS, photos.size)
      photos.chunked(cols).forEach { entries += Entry.Tiles(it) }
      sections += NavSection(grid = true, ids = photos.map { it.file.id })
    }
    if (photosFirst) { photosOut(); docsOut() } else docsOut().also { if (!peek) photosOut() }
  }
}

enum class Dir { UP, DOWN, LEFT, RIGHT }

/** Arrow-key movement over [NavSection]s, and which result stays selected when the list changes. Pure, so it is tested without a window. */
object ResultNav {
  private fun locate(sections: List<NavSection>, id: Long?): Pair<Int, Int>? {
    if (id == null) return null
    sections.forEachIndexed { si, s -> val i = s.ids.indexOf(id); if (i >= 0) return si to i }
    return null
  }

  fun first(sections: List<NavSection>): Long? = sections.firstOrNull { it.ids.isNotEmpty() }?.ids?.first()
  fun last(sections: List<NavSection>): Long? = sections.lastOrNull { it.ids.isNotEmpty() }?.ids?.last()

  private fun nextFirst(sections: List<NavSection>, si: Int): Long? =
    (si + 1 until sections.size).firstNotNullOfOrNull { sections[it].ids.firstOrNull() }

  /** Going up into the section above lands on its last item, or the first tile of a grid's last row. */
  private fun prevLanding(sections: List<NavSection>, si: Int, cols: Int): Long? {
    for (p in si - 1 downTo 0) {
      val s = sections[p]
      if (s.ids.isEmpty()) continue
      return if (!s.grid) s.ids.last() else s.ids[(s.ids.size - 1) / cols * cols]
    }
    return null
  }

  /**
   * One arrow press. Down and Up walk a list item by item and a grid by [cols]; Left and Right move only inside a grid. At an edge nothing
   * moves (the selection stays), except that a partly filled last grid row is reached by Down from the row above it.
   */
  fun move(sections: List<NavSection>, cols: Int, current: Long?, dir: Dir): Long? {
    val c = cols.coerceAtLeast(1)
    val (si, i) = locate(sections, current) ?: return first(sections)
    val s = sections[si]
    val n = s.ids.size
    return when (dir) {
      Dir.DOWN ->
        if (!s.grid) { if (i + 1 < n) s.ids[i + 1] else nextFirst(sections, si) }
        else if (i + c < n) s.ids[i + c]
        else if (i / c < (n - 1) / c) s.ids[n - 1]
        else nextFirst(sections, si)
      Dir.UP ->
        if (!s.grid) { if (i > 0) s.ids[i - 1] else prevLanding(sections, si, c) }
        else if (i - c >= 0) s.ids[i - c]
        else prevLanding(sections, si, c)
      Dir.LEFT -> if (s.grid && i > 0) s.ids[i - 1] else null
      Dir.RIGHT -> if (s.grid && i + 1 < n) s.ids[i + 1] else null
    } ?: current
  }

  /** Page Down and Page Up: [rows] presses of Down or Up, stopping at the end. */
  fun page(sections: List<NavSection>, cols: Int, current: Long?, down: Boolean, rows: Int): Long? {
    var at = current ?: return first(sections)
    repeat(rows.coerceAtLeast(1)) {
      val next = move(sections, cols, at, if (down) Dir.DOWN else Dir.UP) ?: return at
      if (next == at) return at
      at = next
    }
    return at
  }

  /** Quick Look's previous and next: straight through reading order, grids included. */
  fun step(ids: List<Long>, current: Long?, delta: Int): Long? {
    if (ids.isEmpty()) return null
    val i = ids.indexOf(current)
    if (i < 0) return ids.first()
    return ids[(i + delta).coerceIn(0, ids.lastIndex)]
  }

  /**
   * Which result is selected after the list changed. A new query selects its best hit, so type then Enter opens it. The same query (the
   * list was refreshed while the index grew) keeps the selection by id, or falls back to the first result if it is gone.
   */
  fun reconcile(prev: Long?, prevQuery: String, newQuery: String, ids: List<Long>): Long? {
    if (ids.isEmpty()) return null
    if (prevQuery.trim() == newQuery.trim() && prev != null && prev in ids) return prev
    return ids.first()
  }
}
