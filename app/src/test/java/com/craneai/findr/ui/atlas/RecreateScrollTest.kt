package com.craneai.findr.ui.atlas

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performScrollToIndex
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** UI audit item 16: turning the phone or switching dark mode builds the screen again; the list stays where the person had scrolled it. */
class RecreateScrollTest : AtlasTestBase() {
  @get:Rule val rule = createEmptyComposeRule()

  private fun headerShown() = rule.onAllNodes(hasTestTag("filters-header"), useUnmergedTree = true).fetchSemanticsNodes().size

  @Test fun recreateKeepsTheScrollPlace() {
    val a = Atlas(rule, "t-recreate-scroll")
    a.forEach(listOf(Cfg(1.0f)), "recreate-scroll") { c ->
      prefs()
      val v = Vms(AtlasEngine(corpus = Corpus.FULL))
      a.launchSearch(c, v)
      a.settle(300)
      rule.onNode(androidx.compose.ui.test.hasScrollToIndexAction()).performScrollToIndex(30); a.settle(200)
      assertEquals("setup: the filters row should have scrolled away", 0, headerShown())
      a.reconfigure(Cfg(1.3f))
      a.settle(500)
      assertEquals("the list went back to the top after recreate", 0, headerShown())
    }
    a.assertNoFailures()
  }
}
