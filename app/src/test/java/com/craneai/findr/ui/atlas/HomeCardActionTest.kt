package com.craneai.findr.ui.atlas

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onRoot
import com.craneai.findr.R
import org.junit.Rule
import org.junit.Test

/** UI audit item 4: the home card's action is fully inside the window and its words are not cut, on small phones at large text. */
class HomeCardActionTest : AtlasTestBase() {
  @get:Rule val rule = createEmptyComposeRule()

  @Test fun accessCardActionIsInViewAndWordsAreWhole() {
    val a = Atlas(rule, "t-homecard")
    val cells = listOf(Dev.SMALL, Dev.PHONE, Dev.LAND).flatMap { d -> listOf(1.0f, 2.0f).flatMap { s -> listOf("en", "lg").map { Cfg(s, false, d, it) } } }
    a.forEach(cells, "homecard") { c ->
      prefs()
      val v = Vms(AtlasEngine(status = Status.notStarted, corpus = Corpus.EMPTY))
      a.launchSearch(c, v, NO_FILES_ACCESS)
      a.settle(300)
      val win = rule.onRoot().fetchSemanticsNode().boundsInRoot
      val action = rule.onAllNodes(hasText(a.str(R.string.action_allow_access)), useUnmergedTree = true).fetchSemanticsNodes()
      check(action.isNotEmpty()) { "${c.tag}: no Allow access action drawn" }
      val b = action.first().boundsInRoot
      check(b.left >= win.left - 1 && b.right <= win.right + 1 && b.top >= win.top - 1 && b.bottom <= win.bottom + 1) { "${c.tag}: action $b outside window $win" }
      val wanted = a.str(R.string.access_banner_body)
      for (n in rule.onAllNodes(hasText(wanted.take(12), substring = true), useUnmergedTree = true).fetchSemanticsNodes()) {
        val layout = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        n.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layout)
        check(layout.none { it.hasVisualOverflow }) { "${c.tag}: card body is cut" }
      }
    }
    a.assertNoFailures()
  }
}
