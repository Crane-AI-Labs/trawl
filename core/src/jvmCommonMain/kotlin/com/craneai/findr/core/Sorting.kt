package com.craneai.findr.core

/**
 * The order of [SortOrder] as a comparator over files, ties broken by id so a list never shuffles between runs.
 * [SortOrder.BEST] has no file order (it is the score order), so it falls back to newest: that is what browsing shows.
 * The SQL in IndexStore.recent spells the same orders (`date_ms DESC, id DESC`, `size DESC, id DESC`, `name COLLATE NOCASE, id`).
 */
fun fileOrder(order: SortOrder): Comparator<IndexedFile> = when (order) {
  SortOrder.BEST, SortOrder.NEWEST -> compareByDescending<IndexedFile> { it.dateMillis }.thenByDescending { it.id }
  SortOrder.OLDEST -> compareBy<IndexedFile> { it.dateMillis }.thenBy { it.id }
  SortOrder.LARGEST -> compareByDescending<IndexedFile> { it.sizeBytes }.thenByDescending { it.id }
  SortOrder.NAME -> Comparator<IndexedFile> { a, b -> String.CASE_INSENSITIVE_ORDER.compare(a.name, b.name) }.thenBy { it.id }
}

/**
 * Orders ranked [hits] (best first, the first [strong] of them strong) for [order]. A sort never lifts a weak match above a
 * strong one: the strong hits are ordered among themselves, then the weak hits among themselves, so "Newest" shows the newest
 * of the good matches first and the "Weaker matches" fold stays below them. [SortOrder.BEST] returns the hits unchanged.
 */
fun <T> sortRanked(hits: List<T>, strong: Int, order: SortOrder, file: (T) -> IndexedFile): List<T> {
  if (order == SortOrder.BEST) return hits
  val cmp = fileOrder(order)
  val s = strong.coerceIn(0, hits.size)
  val by = Comparator<T> { a, b -> cmp.compare(file(a), file(b)) }
  return hits.subList(0, s).sortedWith(by) + hits.subList(s, hits.size).sortedWith(by)
}
