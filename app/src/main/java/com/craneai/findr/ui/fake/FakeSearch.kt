package com.craneai.findr.ui.fake

import com.craneai.findr.core.FileKind
import com.craneai.findr.core.KindFilter
import com.craneai.findr.core.Locator
import com.craneai.findr.core.MatchReason
import com.craneai.findr.core.SearchHit

/**
 * A small stand-in for the real fusion search: words match the file name and its text, "meaning" matches the concept
 * words (so "sunset over the lake" finds a photo that never mentions either). Only enough to exercise every UI state.
 */
internal object FakeSearch {
  private val stopWords = setOf("the", "a", "an", "of", "my", "for", "to", "from", "in", "on", "at", "and", "or", "is", "me", "i", "with", "over")

  /** A few synonym families, so meaning matches words the file does not literally contain. */
  private val families = listOf(
    setOf("money", "momo", "ugx", "payment", "paid", "cash", "balance", "mobile", "transaction"),
    setOf("school", "fees", "tuition", "bursar", "term", "receipt", "uniform"),
    setOf("wedding", "bride", "kwanjula", "introduction", "marriage", "gomesi", "groom"),
    setOf("sick", "illness", "malaria", "clinic", "hospital", "doctor", "fever", "diagnosis"),
    setOf("food", "matooke", "recipe", "cooking", "lunch", "rolex"),
    setOf("sunset", "evening", "lake", "water", "sky"),
    setOf("rent", "tenancy", "landlord", "tenant", "agreement"),
    setOf("electricity", "umeme", "yaka", "token", "power"),
    setOf("funny", "meme", "joke"),
  )

  fun tokens(query: String): List<String> =
    Regex("[\\p{L}\\p{N}]+").findAll(query.lowercase()).map { it.value }.filter { it !in stopWords }.toList()

  private fun stem(w: String) = if (w.length > 3 && w.endsWith("s")) w.dropLast(1) else w

  /** Typing "wedd" must already find "wedding", so tokens of 3+ letters match as prefixes. */
  private fun wordMatches(token: String, word: String): Boolean {
    val t = stem(token)
    val w = stem(word)
    return if (t.length >= 3) w.startsWith(t) else w == t
  }

  private fun words(text: String): List<String> = Regex("[\\p{L}\\p{N}]+").findAll(text.lowercase()).map { it.value }.toList()

  private fun containsToken(text: String, token: String) = words(text).any { wordMatches(token, it) }

  private fun related(token: String, text: String): Boolean {
    val t = stem(token)
    val family = families.firstOrNull { f -> f.any { wordMatches(t, it) } } ?: return false
    val textWords = words(text)
    return textWords.any { w -> family.any { f -> wordMatches(f, w) } }
  }

  /** Wraps every token match in « ». */
  private fun mark(text: String, tokens: List<String>): String {
    val sb = StringBuilder()
    var i = 0
    val m = Regex("[\\p{L}\\p{N}]+").findAll(text).toList()
    for (match in m) {
      sb.append(text, i, match.range.first)
      val hit = tokens.any { wordMatches(it, match.value.lowercase()) }
      if (hit) sb.append('«').append(match.value).append('»') else sb.append(match.value)
      i = match.range.last + 1
    }
    sb.append(text, i, text.length)
    return sb.toString()
  }

  /** ~110 characters around the first marked word. */
  private fun window(marked: String): String {
    val at = marked.indexOf('«').coerceAtLeast(0)
    val from = (at - 40).coerceAtLeast(0)
    val to = (from + 120).coerceAtMost(marked.length)
    var s = marked.substring(from, to)
    // never leave a half-open « » pair at the window edges
    if (s.count { it == '«' } > s.count { it == '»' }) s += "»"
    if (s.count { it == '»' } > s.count { it == '«' }) s = "«$s"
    return (if (from > 0) "…" else "") + s.trim() + (if (to < marked.length) "…" else "")
  }

  fun run(entries: List<FakeEntry>, query: String, filter: KindFilter, limit: Int, useMeaning: Boolean): Pair<List<SearchHit>, Int> {
    val toks = tokens(query)
    if (toks.isEmpty()) return emptyList<SearchHit>() to 0
    val kept = entries.filter { it.file.kind in filter.kinds }

    class Scored(val hit: SearchHit, val strong: Boolean)

    val scored = ArrayList<Scored>()
    for (e in kept) {
      val nameHits = toks.count { containsToken(e.file.name, it) }
      val pageIdx = e.pages.indexOfFirst { p -> toks.any { containsToken(p, it) } }
      val bodyText = e.pages.joinToString(" ") + " " + (e.ocr ?: "")
      val bodyHits = toks.count { containsToken(bodyText, it) }
      val momentIdx = e.moments.indexOfFirst { m -> toks.any { containsToken(m.text, it) } }
      val wordHits = toks.count { t ->
        containsToken(e.file.name, t) || containsToken(bodyText, t) || e.moments.any { containsToken(it.text, t) }
      }
      val meaningText = e.concepts + " " + e.file.name + " " + bodyText + " " + e.moments.joinToString(" ") { it.text }
      // a concept word is a firm meaning match; a related word from a synonym family is a slightly softer one
      val meaningHits = if (!useMeaning) 0.0 else toks.sumOf { t ->
        when {
          containsToken(e.concepts, t) -> 1.0
          related(t, meaningText) -> 0.8
          else -> 0.0
        }
      }
      if (wordHits == 0 && meaningHits == 0.0) continue

      val wordCoverage = wordHits.toDouble() / toks.size
      val meaningCoverage = meaningHits / toks.size
      val reason = when {
        wordHits > 0 && meaningHits > 0.0 -> MatchReason.BOTH
        wordHits > 0 -> MatchReason.WORDS
        else -> MatchReason.MEANING
      }
      val score = wordCoverage * 1.0 + meaningCoverage * 0.6 + nameHits * 0.15 + (bodyHits.coerceAtMost(3) * 0.02)
      val strong = wordCoverage >= 0.6 || meaningCoverage >= 0.75

      var snippet: String? = null
      var locator: Locator? = null
      if (e.pages.isNotEmpty() && pageIdx >= 0) {
        snippet = window(mark(e.pages[pageIdx], toks))
        if (e.file.kind == FileKind.PDF) locator = Locator.Page(pageIdx + 1)
      } else if (e.ocr != null && toks.any { containsToken(e.ocr, it) }) {
        snippet = window(mark(e.ocr, toks))
      }
      if (e.moments.isNotEmpty()) {
        val m = if (momentIdx >= 0) e.moments[momentIdx] else e.moments.firstOrNull { mo -> toks.any { related(it, mo.text) } }
        if (m != null) {
          locator = Locator.Time(m.ms, m.ms + 8_000)
          if (e.file.kind == FileKind.AUDIO) snippet = window(mark(m.text, toks))
        }
      }
      scored += Scored(SearchHit(e.file, score, reason, snippet, locator), strong)
    }
    val ordered = scored.sortedWith(compareByDescending<Scored> { it.strong }.thenByDescending { it.hit.score }).take(limit)
    return ordered.map { it.hit } to ordered.count { it.strong }
  }
}
