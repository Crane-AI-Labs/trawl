package com.craneai.findr.ui.atlas

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onRoot
import org.junit.Rule
import org.junit.Test

/**
 * UI audit item 3: a small window (landscape phone, 360x640 at 2.0, split screen) must show content, not only chrome.
 * On home and on answers the first file starts in the upper part of the window and a real piece of it is visible.
 */
class SmallWindowTest : AtlasTestBase() {
  @get:Rule val rule = createEmptyComposeRule()

  private fun feedBounds() = rule.onAllNodes(hasTestTag("feed-item"), useUnmergedTree = true).fetchSemanticsNodes()
    .map { it.boundsInRoot }.filter { it.width > 0f && it.height > 0f }

  private fun check(a: Atlas, c: Cfg, what: String) {
    val win = rule.onRoot().fetchSemanticsNode().boundsInRoot
    val items = feedBounds()
    val density = a.activity.resources.displayMetrics.density
    check(items.isNotEmpty()) { "$what ${c.tag}: no file is drawn at all" }
    val first = items.minBy { it.top }
    check(first.top < win.height * 0.6f) { "$what ${c.tag}: the first file starts at ${first.top / density} dp of ${win.height / density} dp" }
    // A heading may lead the list; what counts is how much of the list (heading plus files) a person can see.
    val shown = items.sumOf { (minOf(it.bottom, win.bottom) - maxOf(it.top, win.top)).coerceAtLeast(0f).toDouble() }.toFloat() / density
    check(shown >= 120f) { "$what ${c.tag}: only ${shown} dp of the list is visible (${items.size} items)" }
  }

  @Test fun contentIsVisibleInSmallWindows() {
    val a = Atlas(rule, "t-small")
    val cells = listOf(Cfg(1.0f, false, Dev.LAND), Cfg(2.0f, false, Dev.LAND), Cfg(2.0f, false, Dev.SMALL), Cfg(2.0f, false, Dev.SPLIT), Cfg(1.3f, false, Dev.SMALL))
    a.forEach(cells, "small") { c ->
      prefs(firstSearchDone = true)
      val v = Vms(AtlasEngine(corpus = Corpus.MEDIA_ONLY))
      a.launchSearch(c, v)
      a.settle(300)
      check(a, c, "home")
      a.focusField(); a.type("wedding"); a.keyboardDown(); a.settle(300)
      check(a, c, "answers")
    }
    a.assertNoFailures()
  }
}
