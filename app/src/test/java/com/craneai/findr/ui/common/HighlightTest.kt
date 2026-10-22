package com.craneai.findr.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test

class HighlightTest {
  @Test fun splitsMatchedRuns() {
    val runs = parseSnippet("pay «school» fees")
    assertEquals(listOf(SnippetRun("pay ", false), SnippetRun("school", true), SnippetRun(" fees", false)), runs)
  }

  @Test fun plainTextHasNoMarkers() {
    assertEquals("pay school fees", plainSnippet("pay «school» fees"))
  }

  @Test fun toleratesUnbalancedMarkers() {
    assertEquals("a b c", plainSnippet("a «b c"))
    assertEquals("a b c", plainSnippet("a b» c"))
  }

  @Test fun noMarkersIsOneRun() {
    assertEquals(listOf(SnippetRun("hello", false)), parseSnippet("hello"))
  }

  @Test fun emptyIsEmpty() {
    assertEquals(emptyList<SnippetRun>(), parseSnippet(""))
  }
}
