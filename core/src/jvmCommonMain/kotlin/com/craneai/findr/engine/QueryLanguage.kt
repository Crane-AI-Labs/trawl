package com.craneai.findr.engine

import com.craneai.findr.search.Fusion
import java.util.concurrent.atomic.AtomicLong

/**
 * Which word weight a query gets: [NON_ENGLISH_WORD_WEIGHT] when the query is not English, today's [Fusion.WORD_WEIGHT] when it is.
 *
 * The rule was pre-registered and passed on the known-item (D17) harness (the research notes 10e, scripts/bench/ww_gate.py, which is its
 * Python twin; the unit tests check this class against a golden file made by that script). The phone has no language label for a
 * query, but it already carries the English word list, so:
 *  - tokens are `[A-Za-z]+('[A-Za-z]+)?` (curly apostrophes folded); digits and codes are not tokens, so they are neutral;
 *  - names are neutral: a capitalised token that is not the first token is dropped;
 *  - the query is non-English when fewer than [THRESHOLD] of the kept tokens are English ([EnglishWords.isEnglish]);
 *  - no kept token: non-English only if there are no tokens at all and the text has a letter of another script (Arabic, ...);
 *    numbers, codes and a query of nothing but names stay English;
 *  - no word list (asset missing, or not loaded yet): English, the safe default, which is today's behaviour.
 *
 * This is NOT [SpeechLanguageGate.isEnglishQuery], which gates the English-only quick-look text tower (threshold 0.5, names count);
 * that rule is left as it was.
 *
 * Only counts are kept and logged (never query text).
 */
class QueryLanguage(private val words: Lazy<EnglishWords?>) {
  private val classified = AtomicLong()
  private val nonEnglish = AtomicLong()

  /** Queries the final answer has classified so far, and how many of them were non-English: the log's only trace of the rule. */
  val counts: String get() = "${nonEnglish.get()} of ${classified.get()} queries non-English"

  /**
   * The word weight for [text]. [mayLoad] false is for the words-first answer, which must not wait for the word list: if the list
   * is not loaded yet, the query counts as English. The final answer passes true (it loads the list, ~0.1 s once per process) and
   * is the one that is counted.
   */
  fun wordWeight(text: String, mayLoad: Boolean = true): Double {
    val list = if (mayLoad || words.isInitialized()) words.value else null
    val non = isNonEnglish(list, text)
    if (mayLoad) { classified.incrementAndGet(); if (non) nonEnglish.incrementAndGet() }
    return if (non) NON_ENGLISH_WORD_WEIGHT else Fusion.WORD_WEIGHT
  }

  companion object {
    /** Non-English when the share of English tokens is below this (strict). */
    const val THRESHOLD = 0.6
    /** Chosen on the D17 DEV half with the threshold (ww_gate.py), confirmed on TEST. */
    const val NON_ENGLISH_WORD_WEIGHT = 4.0

    private val TOKEN = Regex("[A-Za-z]+(?:['’‘][A-Za-z]+)?")

    /** The rule, with the list passed in (null: no list, so English). */
    fun isNonEnglish(list: EnglishWords?, text: String): Boolean {
      if (list == null) return false
      val tokens = TOKEN.findAll(text).toList()
      val kept = ArrayList<String>(tokens.size)
      for ((i, m) in tokens.withIndex()) {
        val t = m.value
        if (i > 0 && t[0].isUpperCase()) continue   // a name: neutral
        kept += t.lowercase(java.util.Locale.ROOT).replace('’', '\'').replace('‘', '\'')
      }
      if (kept.isEmpty()) return tokens.isEmpty() && hasOtherScriptLetter(text)
      return kept.count { list.isEnglish(it) }.toDouble() / kept.size < THRESHOLD
    }

    private fun hasOtherScriptLetter(text: String) = text.codePoints().anyMatch { Character.isLetter(it) && it > 127 }
  }
}
