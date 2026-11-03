package com.craneai.findr.engine

import com.craneai.findr.search.LocalStopwords
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.GZIPInputStream

/**
 * The Luganda and Swahili grammar words that queries leave out (core LocalStopwords) never include a common English word, so an English
 * query keeps every word it had; the two kept on purpose are "ya" and "ku" (scripts/build_local_stopwords.py says why).
 */
class LocalStopwordsTest {
  private val common = GZIPInputStream(File("src/main/assets/${SpeechLanguageGate.COMMON_ASSET}").inputStream()).bufferedReader().readLines().toSet()

  @Test fun noCommonEnglishWordIsAGrammarWordExceptTheTwoChosen() {
    assertTrue(common.size > 1000)
    assertEquals(setOf("ya", "ku"), LocalStopwords.WORDS.filterTo(HashSet()) { it in common })
  }

  @Test fun everyWordIsLowerCaseLettersAsQueriesAreSplit() {
    for (w in LocalStopwords.WORDS + LocalStopwords.PICTURE_WORDS + LocalStopwords.MESSAGE_WORDS) assertTrue(w, w.length >= 2 && w.all { it in 'a'..'z' })
  }

  @Test fun theOtherLocalLanguagesGrammarWordsHoldNoCommonEnglishWordAtAll() {
    // Runyankole, Kinyarwanda, Acholi (core LocalLanguages, scripts/build_local_stopwords_more.py): no exception, so an English query
    // never holds one
    for (l in com.craneai.findr.search.LocalLanguage.entries) assertEquals(l.name, emptySet<String>(), l.grammar.filterTo(HashSet()) { it in common })
  }

  @Test fun theKindWordsAreNotEnglishEither() {
    assertEquals(emptySet<String>(), (LocalStopwords.PICTURE_WORDS + LocalStopwords.MESSAGE_WORDS).filterTo(HashSet()) { it in common })
  }
}
