package com.craneai.findr.ui.atlas

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onRoot
import com.craneai.findr.R
import com.craneai.findr.ui.search.SelectionTopBar
import org.junit.Rule
import org.junit.Test

/** UI audit item 2: the selection top bar is one row at every font size, in English and Luganda; nothing clips or overlaps. */
class SelectionBarTest : AtlasTestBase() {
  @get:Rule val rule = createEmptyComposeRule()

  @Test fun topBarIsOneRowAtEveryFontScale() {
    val a = Atlas(rule, "t-selbar")
    val cells = listOf("en", "lg").flatMap { l -> listOf(1.0f, 1.3f, 2.0f).map { Cfg(it, false, Dev.PHONE, l) } }
    a.forEach(cells, "selbar") { c ->
      prefs()
      for (count in listOf(1, 12, 123)) {
        a.launch(c) { SelectionTopBar(count = count, allSelected = false, onSelectAll = {}, onExit = {}) }
        val root = rule.onRoot().fetchSemanticsNode().boundsInRoot
        val head = rule.onAllNodes(androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading), useUnmergedTree = true)
          .fetchSemanticsNodes().single().boundsInRoot
        val close = rule.onNode(androidx.compose.ui.test.hasContentDescription(a.str(R.string.select_exit)), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val toggleLabel = a.str(R.string.select_all)
        val toggle = rule.onAllNodes(androidx.compose.ui.test.hasContentDescription(toggleLabel).or(hasText(toggleLabel)), useUnmergedTree = true)
          .fetchSemanticsNodes().first().boundsInRoot
        val where = "${c.tag} count=$count"
        for ((n, r) in listOf("heading" to head, "close" to close, "toggle" to toggle)) {
          check(r.left >= root.left - 1f && r.right <= root.right + 1f && r.top >= root.top - 1f && r.bottom <= root.bottom + 1f) { "$where: $n $r outside window $root" }
        }
        fun apart(x: String, p: Rect, y: String, q: Rect) = check(!p.deflate(1f).overlaps(q.deflate(1f))) { "$where: $x $p overlaps $y $q" }
        apart("close", close, "heading", head); apart("heading", head, "toggle", toggle); apart("close", close, "toggle", toggle)
        check(head.width > head.height * 2.5f) { "$where: the count is squeezed into a column ($head)" }
      }
    }
    a.assertNoFailures()
  }

  /** UI audit item 10: all five actions (Share, Copy, Mark, Not sensitive, More) stay inside the window and apart at every font size. */
  @Test fun actionBarKeepsMoreInView() {
    val a = Atlas(rule, "t-selbar-bottom")
    val cells = listOf("en", "lg").flatMap { l -> listOf(1.0f, 1.3f, 2.0f).flatMap { s -> listOf(Dev.SMALL, Dev.PHONE).map { Cfg(s, false, it, l) } } }
    a.forEach(cells, "actionbar") { c ->
      prefs()
      a.launch(c) { com.craneai.findr.ui.search.SelectionActionBar(onShare = {}, onCopy = {}, onMark = {}, onUnmark = {}, onMore = {}) }
      val root = rule.onRoot().fetchSemanticsNode().boundsInRoot
      val names = listOf(R.string.action_share, R.string.action_copy, R.string.action_mark_sensitive, R.string.action_unmark_sensitive, R.string.action_more).map { a.str(it) }
      val boxes = names.map { n -> rule.onAllNodes(androidx.compose.ui.test.hasContentDescription(n), useUnmergedTree = true).fetchSemanticsNodes().first().boundsInRoot }
      boxes.forEachIndexed { i, r ->
        check(r.left >= root.left - 1f && r.right <= root.right + 1f) { "${c.tag}: '${names[i]}' $r outside window $root" }
        check(r.width >= 40f * a.activity.resources.displayMetrics.density) { "${c.tag}: '${names[i]}' is only ${r.width}px wide" }
      }
      for (i in 0 until boxes.size - 1) check(!boxes[i].deflate(1f).overlaps(boxes[i + 1].deflate(1f))) { "${c.tag}: '${names[i]}' overlaps '${names[i + 1]}'" }
    }
    a.assertNoFailures()
  }
}
