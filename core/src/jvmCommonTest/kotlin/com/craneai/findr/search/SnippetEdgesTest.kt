package com.craneai.findr.search

import org.junit.Assert.assertEquals
import org.junit.Test

/** The two ends of a snippet never show half a word (see [SnippetEdges]). */
class SnippetEdgesTest {
  private fun tidy(s: String, before: String = "", after: String = "") = SnippetEdges.tidy(s, before, after)

  @Test fun aStartInsideAWordLosesTheHalfWord() {
    assertEquals("…notice in writing. No sub-letting without consent.", tidy("h's notice in writing. No sub-letting without consent.", before = "nt"))
  }

  @Test fun aCleanStartIsUntouched() {
    val s = "One month's notice in writing. No sub-letting."
    assertEquals(s, tidy(s))                       // the excerpt begins the text
    assertEquals(s, tidy(s, before = ". "))        // after a sentence
    assertEquals("month's notice", tidy("month's notice", before = "e "))
    assertEquals("\"quoted\" start", tidy("\"quoted\" start", before = "x "))
    assertEquals("'tis the season", tidy("'tis the season", before = " "))   // a quote mark opens it
  }

  @Test fun theHighlightStaysOnTheSameWords() {
    assertEquals("…«notice» in writing", tidy("ing «notice» in writing", before = "ow"))
    assertEquals("…a «month's» «notice»…", tidy("th a «month's» «notice»…", before = "ul"))
  }

  @Test fun aHalfWordThatHoldsAMatchIsKept() {
    assertEquals("«notice» in writing", tidy("«notice» in writing", before = "a"))
    assertEquals("pre«fix» goes on", tidy("pre«fix» goes on", before = "a"))
  }

  @Test fun anEndInsideAWordLosesTheHalfWord() {
    assertEquals("one month's notice in…", tidy("one month's notice in wri", after = "ting"))
    assertEquals("one «month's» notice in…", tidy("one «month's» notice in wri", after = "ting"))
    assertEquals("a clean end", tidy("a clean end", after = " and more"))
    assertEquals("the end of the text", tidy("the end of the text"))
  }

  @Test fun bothEndsAtOnce() {
    assertEquals("…notice in…", tidy("h's notice in wri", before = "nt", after = "ting"))
  }

  @Test fun anEllipsisTheEngineAddedStaysAndNeverDoubles() {
    assertEquals("…notice in writing…", tidy("…s notice in writing…", before = "h'"))
    assertEquals("…notice in writing…", tidy("…notice in writing…", before = " "))
    assertEquals("…notice in…", tidy("…notice in wri…", after = "ting"))
  }

  @Test fun anApostropheIsPartOfTheWord() {
    // "month's": a cut between the h and the 's, or after the apostrophe, is inside the word
    assertEquals("…notice", tidy("s notice", before = "h'"))
    assertEquals("…notice", tidy("'s notice", before = "th"))
    // Luganda: "ng'ombe" (cow)
    assertEquals("…zange", tidy("ombe zange", before = "g'"))
    assertEquals("ng'ombe zange", tidy("ng'ombe zange", before = " "))
    assertEquals("ng'ombe y'omu…", tidy("ng'ombe y'omu mu", after = "nju"))
    // an apostrophe that opens or closes a quote is not
    assertEquals("hello' she said", tidy("hello' she said", before = " '"))
    assertEquals("the dogs", tidy("the dogs", after = "' "))
  }

  @Test fun accentedLettersAreLetters() {
    assertEquals("…nombreux", tidy("aient nombreux", before = "t"))
    assertEquals("…nombreux", tidy("é nombreux", before = "ét"))
    assertEquals("étaient nombreux", tidy("étaient nombreux", before = " "))
    // a combining accent (e + U+0301) is still the same word
    assertEquals("…suite", tidy("ée suite", before = "cr"))
    assertEquals("Swahili: nyumba…", tidy("Swahili: nyumba kub", after = "wa"))
  }

  @Test fun aHyphenOrAStopIsAWordEdge() {
    assertEquals("known fact", tidy("known fact", before = "well-"))
    assertEquals("5 apples", tidy("5 apples", before = "1."))
    assertEquals("the well-", tidy("the well-", after = "known"))
  }

  @Test fun textWithoutSpacesIsNotCut() {
    val cjk = "我们在这里学习语言和文字"
    assertEquals(cjk, tidy(cjk, before = "他", after = "们"))
    val thai = "สวัสดีครับยินดีที่ได้รู้จัก"
    assertEquals(thai, tidy(thai, before = "ค", after = "ร"))
    // mixed: the Chinese edge is no word edge, the Latin one still is
    assertEquals("世界 hello world", tidy("世界 hello world", before = "你"))
    assertEquals("hello 世界", tidy("hello 世界", after = "们"))
    assertEquals("…world", tidy("lo world", before = "hel"))
  }

  @Test fun aVeryShortChunkKeepsSomethingToShow() {
    assertEquals("ly", tidy("ly", before = "b"))               // one fragment and nothing else: shown as it is
    assertEquals("ed ", tidy("ed ", before = "b"))              // nothing follows the fragment
    assertEquals("…c", tidy("ab c", before = "x"))
    assertEquals("a…", tidy("a bc", after = "d"))
    assertEquals("", tidy(""))
    assertEquals("…", tidy("…", before = "a", after = "b"))
  }

  @Test fun aLongUnbrokenTokenIsLeftAlone() {
    val url = "https://example.org/" + "a".repeat(300)
    assertEquals(url, tidy(url, before = "x", after = "y"))
  }

  @Test fun newlinesAndTabsAreWordGapsToo() {
    assertEquals("…second line", tidy("irst\n\tsecond line", before = "f"))
    assertEquals("first line…", tidy("first line\nsec", after = "ond"))
  }

  @Test fun aTidySnippetStaysAsItIs() {
    val once = tidy("h's «notice» in wri", before = "nt", after = "ting")
    assertEquals("…«notice» in…", once)
    assertEquals(once, tidy(once))
  }
}
