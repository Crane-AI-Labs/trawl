package com.craneai.findr.engine

import java.io.InputStream

/**
 * An English word list the phone can hold cheaply: the whole of web2 (public domain, ~234,000 lower-case words) as ONE sorted,
 * newline-separated byte array (~2.4 MB of heap, no per-word objects; a String array of the same list would cost ~10 times that),
 * searched by binary search over byte offsets. Inflections are not stored: [isWord] derives them the way the experiment code
 * does, so the Mac eval and this class are one rule. Plain JVM, no Android types, so unit tests run it as is.
 *
 * Built by scripts/build_english_words.py into app/src/main/assets/english_words.txt.gz (735 KB).
 */
class EnglishWords(private val data: ByteArray) {
  /** [word] (lower case, a-z only) is in the list as written. */
  fun contains(word: String): Boolean {
    if (word.isEmpty()) return false
    var lo = 0
    var hi = data.size
    while (lo < hi) {
      val mid = (lo + hi) ushr 1
      var s = mid
      while (s > 0 && data[s - 1] != NL) s--          // start of the line holding byte `mid`
      var e = mid
      while (e < data.size && data[e] != NL) e++       // its end (the newline)
      val c = compare(word, s, e)
      when {
        c == 0 -> return true
        c < 0 -> hi = s
        else -> lo = e + 1
      }
    }
    return false
  }

  /** The word, or the word plus s/es/ed/d/ing/ly/er/ers, or its e-dropped -ing, or its y-to-ies/ied form. */
  fun isWord(w: String): Boolean {
    if (contains(w)) return true
    for (s in SUFFIXES) if (w.length > s.length && w.endsWith(s) && contains(w.substring(0, w.length - s.length))) return true
    if (w.endsWith("ing") && contains(w.substring(0, w.length - 3) + "e")) return true
    if ((w.endsWith("ies") || w.endsWith("ied")) && contains(w.substring(0, w.length - 3) + "y")) return true
    return false
  }

  /** A token from [SpeechLanguageGate.tokens]: a plain word, or stem'suffix for the contractions (it's, don't, we're, I've, they'll, he'd, I'm). */
  fun isEnglish(token: String): Boolean {
    val q = token.indexOf('\'')
    if (q < 0) return isWord(token)
    val stem = token.substring(0, q)
    val suffix = token.substring(q + 1)
    if (suffix !in CONTRACTIONS) return false   // Luganda and Swahili use ' too: ng'ombe, n'amannyo
    return isWord(stem) || (suffix == "t" && stem.endsWith("n") && isWord(stem.dropLast(1)))
  }

  /** Bytes [s, e) of the list against [word], as signed order of the first difference (shorter first on a shared prefix). */
  private fun compare(word: String, s: Int, e: Int): Int {
    val n = minOf(word.length, e - s)
    for (i in 0 until n) {
      val d = word[i].code - (data[s + i].toInt() and 0xFF)
      if (d != 0) return d
    }
    return word.length - (e - s)
  }

  val sizeBytes: Int get() = data.size

  companion object {
    private const val NL = '\n'.code.toByte()
    private val SUFFIXES = listOf("s", "es", "ed", "d", "ing", "ly", "er", "ers")
    private val CONTRACTIONS = setOf("s", "t", "re", "ve", "ll", "d", "m")

    /** Reads the list built by scripts/build_english_words.py: gzipped (the source file) or plain (as the APK packages it). */
    fun fromGzip(stream: InputStream): EnglishWords {
      val raw = BundledAssets.ungzipIfNeeded(stream).use { it.readBytes() }
      return EnglishWords(if (raw.isNotEmpty() && raw.last() != NL) raw + NL else raw)
    }
  }
}
