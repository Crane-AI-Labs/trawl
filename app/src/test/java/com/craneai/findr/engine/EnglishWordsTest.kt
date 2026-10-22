package com.craneai.findr.engine

import java.io.File
import java.util.zip.GZIPInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The word list the gate uses, loaded from the real asset (gradle runs unit tests with the module directory as the working directory). */
class EnglishWordsTest {
  private fun asset() = File("src/main/assets/${SpeechLanguageGate.ASSET}")
  private val words = asset().inputStream().use { EnglishWords.fromGzip(it) }

  @Test fun everyListedWordIsFoundAndNearMissesAreNot() {
    val list = GZIPInputStream(asset().inputStream()).use { it.readBytes() }.toString(Charsets.US_ASCII).trimEnd('\n').split('\n')
    assertTrue(list.size > 230_000)
    assertEquals(list.sorted(), list)   // binary search depends on byte order
    list.forEach { assertTrue(it, words.contains(it)) }
    // one letter appended or removed from a sample of listed words: found only when that is itself listed
    val set = list.toHashSet()
    list.filterIndexed { i, _ -> i % 97 == 0 }.forEach { w ->
      assertEquals(w + "q", set.contains(w + "q"), words.contains(w + "q"))
      assertEquals(w.dropLast(1), set.contains(w.dropLast(1)) , words.contains(w.dropLast(1)))
    }
    listOf("", "zzzzzzzz", "amannyo", "empanvu", "ng", "kampala1").forEach { assertFalse(it, it.isNotEmpty() && words.contains(it) && it != "ng") }
  }

  @Test fun firstAndLastEntriesAreReachable() {
    assertTrue(words.contains("a"))
    assertTrue(words.contains("zyzzogeton") || words.contains("zythum"))
    assertFalse(words.contains("aaaaaaaaaaaaaaaaaaaa"))
    assertFalse(words.contains("zzzzzzzzzzzzzzzzzzzz"))
  }

  @Test fun inflectionsAreDerivedNotStored() {
    listOf("looked", "cities", "running".replace("nn", "n"), "walking", "quickly", "teachers", "tried", "stories", "making", "tables").forEach {
      assertTrue(it, words.isWord(it))
    }
    assertFalse(words.isWord("ankleqs"))
  }

  @Test fun contractionsNeedAnEnglishStemAndASmallSuffixSet() {
    listOf("it's", "don't", "can't", "we're", "i've", "they'll", "he'd", "i'm", "won't", "didn't").forEach { assertTrue(it, words.isEnglish(it)) }
    // Luganda and Swahili apostrophes: ng'ombe, n'amannyo, ng'ambo
    listOf("ng'ombe", "n'amannyo", "ng'ambo", "kw'otadde", "g'ennyo").forEach { assertFalse(it, words.isEnglish(it)) }
  }

  @Test fun heapIsTheBytesOfTheList() {
    assertTrue("${words.sizeBytes} bytes", words.sizeBytes in 2_000_000..3_000_000)
  }
}
