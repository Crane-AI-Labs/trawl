package com.craneai.findr.search

import java.text.Normalizer

/**
 * Compact sorted-interval map from `segments.id` -> candidate file index (`0 until words.size`).
 *
 * Because SQLite's `segments_file` covering index streams rows ordered by `(file, id)` and each file's chunks are
 * inserted in contiguous rowid ranges, 460,000+ segments across 100 candidate files typically compress to ~200 disjoint
 * `[start, end]` intervals (~4 KB, L1-cache resident, zero resizes) instead of a 12.6 MB `LongIntMap` per keystroke.
 * Handles arbitrary out-of-order or interleaved segment IDs with 100% exactness.
 */
internal class SegmentFileRuns(initialRuns: Int = 64) {
  private var starts = LongArray(initialRuns.coerceAtLeast(16))
  private var ends = LongArray(starts.size)
  private var fileIdxs = IntArray(starts.size)
  var runCount = 0
    private set
  private var minSeg = Long.MAX_VALUE
  private var maxSeg = Long.MIN_VALUE

  fun add(segmentId: Long, fileIdx: Int) {
    val n = runCount
    if (n > 0 && fileIdxs[n - 1] == fileIdx && segmentId == ends[n - 1] + 1L) {
      ends[n - 1] = segmentId
      return
    }
    if (n == starts.size) {
      val next = starts.size shl 1
      starts = starts.copyOf(next)
      ends = ends.copyOf(next)
      fileIdxs = fileIdxs.copyOf(next)
    }
    starts[n] = segmentId
    ends[n] = segmentId
    fileIdxs[n] = fileIdx
    runCount = n + 1
  }

  /** Sorts runs by ascending `start` (heapsort, O(R log R) worst-case, zero allocations) and caches global bounds. */
  fun finish() {
    val n = runCount
    if (n == 0) return
    var sorted = true
    for (i in 1 until n) {
      if (starts[i - 1] > starts[i]) { sorted = false; break }
    }
    if (!sorted) sortRuns(n)
    minSeg = starts[0]
    maxSeg = ends[n - 1]
  }

  /** Returns the candidate file index for [segmentId], or `-1` when absent. Call after [finish]. */
  fun fileIdxOf(segmentId: Long): Int {
    val n = runCount
    if (n == 0 || segmentId < minSeg || segmentId > maxSeg) return -1
    var lo = 0
    var hi = n - 1
    while (lo <= hi) {
      val mid = (lo + hi) ushr 1
      val s = starts[mid]
      if (segmentId < s) hi = mid - 1
      else if (segmentId > ends[mid]) lo = mid + 1
      else return fileIdxs[mid]
    }
    return -1
  }

  private fun sortRuns(n: Int) {
    for (i in (n ushr 1) - 1 downTo 0) siftDown(i, n)
    for (end in n - 1 downTo 1) {
      swap(0, end)
      siftDown(0, end)
    }
  }

  private fun siftDown(root: Int, size: Int) {
    var i = root
    while (true) {
      var child = (i shl 1) + 1
      if (child >= size) return
      if (child + 1 < size && starts[child + 1] > starts[child]) child++
      if (starts[i] >= starts[child]) return
      swap(i, child)
      i = child
    }
  }

  private fun swap(a: Int, b: Int) {
    val ts = starts[a]; starts[a] = starts[b]; starts[b] = ts
    val te = ends[a]; ends[a] = ends[b]; ends[b] = te
    val tf = fileIdxs[a]; fileIdxs[a] = fileIdxs[b]; fileIdxs[b] = tf
  }
}

/**
 * Generates FTS5-compatible `«...»` excerpts directly in Kotlin from `fts.body` (already fetched by `Searcher.segmentFacts`)
 * for only the final winning hits, avoiding 1,000 wasted `snippet(fts, 1, ...)` calls inside the FTS5 BM25 scan.
 */
internal object SnippetBuilder {
  private const val DEFAULT_MAX_TOKENS = 18

  /** One query term group (all equivalent surface/class/translated forms) and whether it matches prefixes. */
  class MatchGroup(val forms: List<String>, val prefix: Boolean) {
    val normalized: Array<String> = Array(forms.size) { normalizeToken(forms[it]) }
    val stems: Array<String> = Array(forms.size) { PorterStemmer.stem(normalized[it]) }
  }

  /**
   * Extracts a window of up to [maxTokens] tokens from [body] with matched tokens wrapped in `«` and `»` and `…`
   * marking any trimmed ends. Returns `null` when no token in [body] matches [groups] (e.g. a filename-only match).
   */
  fun build(body: String, groups: List<MatchGroup>, maxTokens: Int = DEFAULT_MAX_TOKENS): String? {
    if (body.isEmpty() || groups.isEmpty()) return null
    var cap = 64
    var tokStart = IntArray(cap)
    var tokEnd = IntArray(cap)
    var tokCount = 0
    val len = body.length
    var i = 0
    while (i < len) {
      if (!isTokenChar(body[i])) { i++; continue }
      val s = i
      i++
      while (i < len && isTokenChar(body[i])) i++
      if (tokCount == cap) {
        cap = cap shl 1
        tokStart = tokStart.copyOf(cap)
        tokEnd = tokEnd.copyOf(cap)
      }
      tokStart[tokCount] = s
      tokEnd[tokCount] = i
      tokCount++
    }
    if (tokCount == 0) return null

    val matched = BooleanArray(tokCount)
    val groupMask = LongArray(tokCount)
    var anyMatch = false
    for (t in 0 until tokCount) {
      val norm = normalizeToken(body.substring(tokStart[t], tokEnd[t]))
      var tokStem: String? = null
      var mask = 0L
      for (gIdx in groups.indices) {
        val g = groups[gIdx]
        var hit = false
        for (fIdx in g.normalized.indices) {
          val f = g.normalized[fIdx]
          if (f.isEmpty()) continue
          if (norm == f || (g.prefix && norm.startsWith(f))) { hit = true; break }
          val st = tokStem ?: PorterStemmer.stem(norm).also { tokStem = it }
          val fs = g.stems[fIdx]
          if (st == fs || (g.prefix && fs.isNotEmpty() && st.startsWith(fs))) { hit = true; break }
        }
        if (hit) {
          matched[t] = true
          mask = mask or (1L shl (gIdx and 63))
          anyMatch = true
        }
      }
      groupMask[t] = mask
    }
    if (!anyMatch) return null

    val winStart: Int
    val winEnd: Int
    if (tokCount <= maxTokens) {
      winStart = 0
      winEnd = tokCount
    } else {
      val groupCount = minOf(groups.size, 64)
      val grpCounts = IntArray(groupCount)
      var distinct = 0
      var hits = 0
      for (t in 0 until maxTokens) {
        if (matched[t]) {
          hits++
          var m = groupMask[t]
          while (m != 0L) {
            val bit = java.lang.Long.numberOfTrailingZeros(m)
            if (grpCounts[bit]++ == 0) distinct++
            m = m and (m - 1L)
          }
        }
      }
      var bestStart = 0
      var bestScore = distinct * 1024 + hits
      var bestCenterDist = centerDistance(matched, 0, maxTokens)
      for (w in 1..(tokCount - maxTokens)) {
        val out = w - 1
        if (matched[out]) {
          hits--
          var m = groupMask[out]
          while (m != 0L) {
            val bit = java.lang.Long.numberOfTrailingZeros(m)
            if (--grpCounts[bit] == 0) distinct--
            m = m and (m - 1L)
          }
        }
        val enter = w + maxTokens - 1
        if (matched[enter]) {
          hits++
          var m = groupMask[enter]
          while (m != 0L) {
            val bit = java.lang.Long.numberOfTrailingZeros(m)
            if (grpCounts[bit]++ == 0) distinct++
            m = m and (m - 1L)
          }
        }
        val score = distinct * 1024 + hits
        if (score > bestScore) {
          bestScore = score
          bestStart = w
          bestCenterDist = centerDistance(matched, w, maxTokens)
        } else if (score == bestScore && score > 0) {
          val dist = centerDistance(matched, w, maxTokens)
          if (dist < bestCenterDist) {
            bestStart = w
            bestCenterDist = dist
          }
        }
      }
      winStart = bestStart
      winEnd = bestStart + maxTokens
    }

    val cStart = if (winStart == 0) 0 else tokStart[winStart]
    val cEnd = if (winEnd == tokCount) len else tokEnd[winEnd - 1]
    val sb = StringBuilder((cEnd - cStart) + 24)
    if (winStart > 0) sb.append('…')
    var cursor = cStart
    for (t in winStart until winEnd) {
      if (!matched[t]) continue
      val s = tokStart[t]
      val e = tokEnd[t]
      if (s > cursor) sb.append(body, cursor, s)
      sb.append('«')
      sb.append(body, s, e)
      sb.append('»')
      cursor = e
    }
    if (cursor < cEnd) sb.append(body, cursor, cEnd)
    if (winEnd < tokCount) sb.append('…')
    return sb.toString()
  }

  private fun centerDistance(matched: BooleanArray, start: Int, len: Int): Int {
    var first = -1
    var last = -1
    val end = start + len
    for (t in start until end) {
      if (matched[t]) {
        if (first < 0) first = t
        last = t
      }
    }
    if (first < 0) return Int.MAX_VALUE
    val midTwice = first + last
    val targetTwice = start + end - 1
    return kotlin.math.abs(midTwice - targetTwice)
  }

  private fun isTokenChar(c: Char): Boolean {
    if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9') return true
    if (c.code < 128) return false
    if (Character.isLetterOrDigit(c)) return true
    val type = Character.getType(c)
    return type == Character.NON_SPACING_MARK.toInt() || type == Character.COMBINING_SPACING_MARK.toInt()
  }

  internal fun normalizeToken(raw: String): String {
    var ascii = true
    for (i in raw.indices) if (raw[i].code >= 128) { ascii = false; break }
    if (ascii) return raw.lowercase(java.util.Locale.ROOT)
    val nfd = Normalizer.normalize(raw, Normalizer.Form.NFD)
    val sb = StringBuilder(nfd.length)
    for (i in nfd.indices) {
      val c = nfd[i]
      if (Character.getType(c) != Character.NON_SPACING_MARK.toInt()) sb.append(Character.toLowerCase(c))
    }
    return sb.toString()
  }
}

/**
 * Standard English Porter stemmer (Porter 1980), matching SQLite FTS5's built-in `porter` tokenizer for ASCII English words
 * so deferred Kotlin snippet highlighting marks the exact stemmed words FTS5 matched.
 */
internal object PorterStemmer {
  fun stem(word: String): String {
    val len = word.length
    if (len < 3) return word
    for (i in 0 until len) if (word[i] !in 'a'..'z') return word
    val b = word.toCharArray()
    var k = len - 1

    fun cons(i: Int): Boolean = when (b[i]) {
      'a', 'e', 'i', 'o', 'u' -> false
      'y' -> if (i == 0) true else !cons(i - 1)
      else -> true
    }

    fun measure(j: Int): Int {
      var n = 0
      var i = 0
      while (true) {
        if (i > j) return n
        if (!cons(i)) break
        i++
      }
      i++
      while (true) {
        while (true) {
          if (i > j) return n
          if (cons(i)) break
          i++
        }
        i++; n++
        while (true) {
          if (i > j) return n
          if (!cons(i)) break
          i++
        }
        i++
      }
    }

    fun vowelInStem(j: Int): Boolean {
      for (i in 0..j) if (!cons(i)) return true
      return false
    }

    fun doublec(j: Int): Boolean = j >= 1 && b[j] == b[j - 1] && cons(j)

    fun cvc(i: Int): Boolean {
      if (i < 2 || !cons(i) || cons(i - 1) || !cons(i - 2)) return false
      val ch = b[i]
      return ch != 'w' && ch != 'x' && ch != 'y'
    }

    fun ends(s: String): Int {
      val l = s.length
      val o = k - l + 1
      if (o < 0) return -1
      for (i in 0 until l) if (b[o + i] != s[i]) return -1
      return k - l
    }

    fun setTo(j: Int, s: String) {
      val l = s.length
      val o = j + 1
      for (i in 0 until l) b[o + i] = s[i]
      k = j + l
    }

    fun replaceIfM0(j: Int, s: String): Boolean {
      if (j < 0) return false
      if (measure(j) > 0) setTo(j, s)
      return true
    }

    // Step 1a
    if (b[k] == 's') {
      when {
        ends("sses") >= 0 -> k -= 2
        ends("ies") >= 0 -> k -= 2
        b[k - 1] != 's' -> k--
      }
    }
    // Step 1b
    val eed = ends("eed")
    if (eed >= 0) {
      if (measure(eed) > 0) k--
    } else {
      val ed = ends("ed")
      val ing = if (ed < 0) ends("ing") else -1
      val j = if (ed >= 0) ed else ing
      if (j >= 0 && vowelInStem(j)) {
        k = j
        when {
          ends("at") >= 0 -> setTo(k - 2, "ate")
          ends("bl") >= 0 -> setTo(k - 2, "ble")
          ends("iz") >= 0 -> setTo(k - 2, "ize")
          doublec(k) -> {
            val ch = b[k]
            if (ch != 'l' && ch != 's' && ch != 'z') k--
          }
          measure(k) == 1 && cvc(k) -> setTo(k, "e")
        }
      }
    }
    // Step 1c
    if (k >= 1 && b[k] == 'y' && vowelInStem(k - 1)) b[k] = 'i'

    // Step 2
    if (k > 0) {
      when (b[k - 1]) {
        'a' -> { replaceIfM0(ends("ational"), "ate") || replaceIfM0(ends("tional"), "tion") }
        'c' -> { replaceIfM0(ends("enci"), "ence") || replaceIfM0(ends("anci"), "ance") }
        'e' -> { replaceIfM0(ends("izer"), "ize") }
        'l' -> {
          replaceIfM0(ends("bli"), "ble") || replaceIfM0(ends("alli"), "al") ||
            replaceIfM0(ends("entli"), "ent") || replaceIfM0(ends("eli"), "e") || replaceIfM0(ends("ousli"), "ous")
        }
        'o' -> {
          replaceIfM0(ends("ization"), "ize") || replaceIfM0(ends("ation"), "ate") || replaceIfM0(ends("ator"), "ate")
        }
        's' -> {
          replaceIfM0(ends("alism"), "al") || replaceIfM0(ends("iveness"), "ive") ||
            replaceIfM0(ends("fulness"), "ful") || replaceIfM0(ends("ousness"), "ous")
        }
        't' -> {
          replaceIfM0(ends("aliti"), "al") || replaceIfM0(ends("iviti"), "ive") || replaceIfM0(ends("biliti"), "ble")
        }
        'g' -> { replaceIfM0(ends("logi"), "log") }
      }
    }

    // Step 3
    if (k > 0) {
      when (b[k]) {
        'e' -> {
          replaceIfM0(ends("icate"), "ic") || replaceIfM0(ends("ative"), "") || replaceIfM0(ends("alize"), "al")
        }
        'i' -> { replaceIfM0(ends("iciti"), "ic") }
        'l' -> { replaceIfM0(ends("ical"), "ic") || replaceIfM0(ends("ful"), "") }
        's' -> { replaceIfM0(ends("ness"), "") }
      }
    }

    // Step 4
    if (k > 1) {
      val j = when (b[k - 1]) {
        'a' -> ends("al")
        'c' -> ends("ance").let { if (it >= 0) it else ends("ence") }
        'e' -> ends("er")
        'i' -> ends("ic")
        'l' -> ends("able").let { if (it >= 0) it else ends("ible") }
        'n' -> ends("ant").let { if (it >= 0) it else ends("ement") }.let { if (it >= 0) it else ends("ment") }.let { if (it >= 0) it else ends("ent") }
        'o' -> {
          val ion = ends("ion")
          if (ion >= 0 && (b[ion] == 's' || b[ion] == 't')) ion else ends("ou")
        }
        's' -> ends("ism")
        't' -> ends("ate").let { if (it >= 0) it else ends("iti") }
        'u' -> ends("ous")
        'v' -> ends("ive")
        'z' -> ends("ize")
        else -> -1
      }
      if (j >= 0 && measure(j) > 1) k = j
    }

    // Step 5a & 5b
    if (b[k] == 'e') {
      val m = measure(k - 1)
      if (m > 1 || (m == 1 && !cvc(k - 1))) k--
    }
    if (b[k] == 'l' && doublec(k) && measure(k - 1) > 1) k--
    return String(b, 0, k + 1)
  }
}
