package com.craneai.findr.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SnippetWindowTest {
  @Test fun bothCutsExpandToWholeWords() {
    val text = "before beginning target ending after"
    assertEquals("…beginning target ending…", SnippetEdges.window(text, text.indexOf("inning"), text.indexOf("ding") + 2))
  }

  @Test fun everyCutInsideAnApostropheWordKeepsItWhole() {
    for (word in listOf("ng'ombe", "ng’ombe", "y'omu", "eby’obulimi", "ngʼombe", "ng‘ombe")) {
      val text = "before $word after"
      val start = text.indexOf(word)
      for (cut in 1 until word.length) {
        assertEquals(word, "…$word…", SnippetEdges.window(text, start + cut, start + word.length))
        assertEquals(word, "…$word…", SnippetEdges.window(text, start, start + cut))
      }
    }
  }

  @Test fun punctuationAndQuotedWordsAreBoundaries() {
    val text = "before 'hello' well-known/path after"
    assertEquals("…hello…", SnippetEdges.window(text, text.indexOf("hello"), text.indexOf("hello") + "hello".length))
    assertEquals("…known…", SnippetEdges.window(text, text.indexOf("known"), text.indexOf("known") + "known".length))
  }

  @Test fun spacedNonLatinWordsAndCombiningMarksStayWhole() {
    for (word in listOf("привет", "مرحبا", "नमस्ते", "cafe\u0301", "𐐀𐐨𐐻")) {
      val text = "before $word after"
      val start = text.indexOf(word)
      for (cut in 1 until word.length) {
        assertEquals(word, "…$word…", SnippetEdges.window(text, start + cut, start + word.length))
        assertEquals(word, "…$word…", SnippetEdges.window(text, start, start + cut))
      }
    }
  }

  @Test fun unspacedTextCutsAtCodePointsRatherThanDroppingThePassage() {
    val text = "我们𠀀学习文字"
    assertEquals("…𠀀…", SnippetEdges.window(text, 3, 4))
    assertEquals("我们…", SnippetEdges.window(text, 0, 2))
  }

  @Test fun aMarkedWordOrPhraseCannotBeCutAway() {
    for (match in listOf("ng'ombe", "two matched words", "long".repeat(80))) {
      val text = "before «$match» after"
      val start = text.indexOf('«')
      val end = text.indexOf('»')
      for (cut in start + 1 until end) {
        assertEquals("…«$match»…", SnippetEdges.window(text, cut, end))
      }
      for (cut in start + 2..end) {
        assertEquals("…«$match»…", SnippetEdges.window(text, start, cut))
      }
    }
  }

  @Test fun adjacentHighlightsAcrossAnApostropheStayAligned() {
    val text = "before «ng»'«ombe» after"
    assertEquals("…«ng»'«ombe»…", SnippetEdges.window(text, text.indexOf("ombe") + 1, text.indexOf("ombe") + 2))
  }

  @Test fun aLongFirstWordIsNotTruncatedToMeetTheBudget() {
    val word = "word".repeat(80)
    assertEquals("$word…", SnippetEdges.window("$word more", 0, 200))
  }

  @Test fun whitespaceEmptyTextAndExistingEllipsesStayClean() {
    assertEquals("word", SnippetEdges.window("  word  ", 0, 8))
    assertEquals("", SnippetEdges.window("", 0, 0))
    assertEquals("", SnippetEdges.window("  ", 0, 2))
    assertEquals("…word…", SnippetEdges.window("…word…", 0, 6))
  }

  @Test fun emojiSequencesAndJoinedWordsStayWhole() {
    val england = String(intArrayOf(0x1F3F4, 0xE0067, 0xE0062, 0xE0065, 0xE006E, 0xE0067, 0xE007F), 0, 7)
    for (cluster in listOf("\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67", "\uD83D\uDC4D\uD83C\uDFFD", "\uD83C\uDDFA\uD83C\uDDEC", england, "1\uFE0F\u20E3", "\u0645\u06CC\u200C\u062E\u0648\u0627\u0647\u0645")) {
      val text = "before $cluster after"
      val start = text.indexOf(cluster)
      for (cut in 1 until cluster.length) {
        assertEquals(cluster, "…$cluster…", SnippetEdges.window(text, start + cut, start + cluster.length))
        assertEquals(cluster, "…$cluster…", SnippetEdges.window(text, start, start + cut))
      }
    }
  }

  @Test fun twoFlagsSeparateAtTheirOwnBoundaryOnly() {
    val uganda = "\uD83C\uDDFA\uD83C\uDDEC"
    val kenya = "\uD83C\uDDF0\uD83C\uDDEA"
    val text = "before $uganda$kenya after"
    val second = text.indexOf(kenya)
    assertEquals("…$kenya…", SnippetEdges.window(text, second, second + kenya.length))
    assertEquals("…$kenya…", SnippetEdges.window(text, second + 1, second + 3))
    assertEquals("…$uganda…", SnippetEdges.window(text, second - 4, second))
  }

  @Test fun guillemetsAreQuotationMarksNotHighlightsWhenHighlightsAreOff() {
    val text = "Il a dit «" + "mot ".repeat(100) + "fin»"
    assertEquals(text.substring(0, text.indexOf(' ', 200)) + "…", SnippetEdges.window(text, 0, 200, highlights = false))
    assertEquals("«fin»", SnippetEdges.window("«fin»", 0, 5, highlights = false))
  }

  @Test fun aWordWithNoBreakIsCutNearTheBudgetNotKeptToItsEnd() {
    // a base64 line or minified blob is "one word" for thousands of characters; the preview must stay one row's worth
    for (blob in listOf("A".repeat(5000), "\uD801\uDC00".repeat(2500), "e\u0301".repeat(2500), "ng'ombe".repeat(700))) {
      val text = "intro $blob end"
      for (out in listOf(SnippetEdges.window(text, 0, 200, highlights = false), SnippetEdges.window(text, text.length - 200, text.length - 3, highlights = false))) {
        assertTrue("${out.length}", out.length <= 200 + 200 + 2)
        assertTrue(out.endsWith("…") || out.startsWith("…"))
        for ((i, c) in out.withIndex()) {
          assertFalse(c.isHighSurrogate() && (i + 1 >= out.length || !out[i + 1].isLowSurrogate()))
          assertFalse(c.isLowSurrogate() && (i == 0 || !out[i - 1].isHighSurrogate()))
        }
        assertFalse("starts on a combining mark", out.removePrefix("…").firstOrNull()?.let { Character.getType(it) == Character.NON_SPACING_MARK.toInt() } ?: false)
      }
    }
  }
}
