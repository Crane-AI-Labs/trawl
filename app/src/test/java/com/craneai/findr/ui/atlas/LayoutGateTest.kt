package com.craneai.findr.ui.atlas

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onRoot
import org.junit.Rule
import org.junit.Test

/** Boxes of every node with [tag] that has a size. */
internal fun androidx.compose.ui.test.junit4.ComposeTestRule.boxes(tag: String): List<Rect> =
  onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().map { it.boundsInRoot }.filter { it.width > 0f && it.height > 0f }

/** Fails when two of [boxes] cover each other by more than a pixel either way: a clipped or stacked key element. */
internal fun assertNoOverlap(where: String, boxes: List<Rect>) {
  for (i in boxes.indices) for (j in i + 1 until boxes.size) {
    val o = boxes[i].intersect(boxes[j])
    check(o.width <= 1f || o.height <= 1f) { "$where: ${boxes[i]} overlaps ${boxes[j]}" }
  }
}

/** Fails when any of [boxes] reaches outside the window sideways (a row wider than the screen is cut off). */
internal fun androidx.compose.ui.test.junit4.ComposeTestRule.assertInsideWindow(where: String, boxes: List<Rect>) {
  val win = onRoot().fetchSemanticsNode().boundsInRoot
  for (b in boxes) check(b.left >= win.left - 1f && b.right <= win.right + 1f) { "$where: $b is outside the window $win" }
}

/**
 * The gate for layout bugs the single-cell tests miss: the home list and the answers, at font 1.0 / 1.3 / 2.0, phone, tablet and landscape,
 * English and Luganda. Every listed file and header must stay inside the window and nothing may cover anything else.
 */
class LayoutGateTest : AtlasTestBase() {
  @get:Rule val rule = createEmptyComposeRule()

  private fun matrix() = listOf(Dev.PHONE, Dev.TABLET, Dev.LAND, Dev.SMALL).flatMap { d ->
    listOf(1.0f, 1.3f, 2.0f).flatMap { s -> listOf("en", "lg").map { Cfg(s, false, d, it) } }
  }

  private fun gate(name: String, type: String?) {
    val a = Atlas(rule, "t-gate-$name")
    a.forEach(matrix(), "gate-$name") { c ->
      prefs()
      val v = Vms(AtlasEngine(corpus = Corpus.FULL))
      a.launchSearch(c, v)
      a.settle(300)
      if (type != null) { a.focusField(if (c.dev == Dev.LAND) 160 else 300); a.type(type); a.settle(600) }
      val where = "$name ${c.tag}"
      val items = rule.boxes("feed-item") + rule.boxes("filters-header")
      check(items.isNotEmpty()) { "$where: nothing listed (mode=${v.search.state.value.mode} query='${v.search.state.value.query}' feed=${v.search.state.value.feed.size} raw=${rule.onAllNodes(hasTestTag("feed-item"), useUnmergedTree = true).fetchSemanticsNodes().map { it.boundsInRoot }} root=${rule.onRoot().fetchSemanticsNode().boundsInRoot})" }
      rule.assertInsideWindow(where, items)
      assertNoOverlap(where, items)
    }
    a.assertNoFailures()
  }

  @Test fun homeList() = gate("home", null)
  @Test fun answers() = gate("answers", "wedding")
}
