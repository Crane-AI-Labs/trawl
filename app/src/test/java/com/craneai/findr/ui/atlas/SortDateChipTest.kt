package com.craneai.findr.ui.atlas

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import com.craneai.findr.R
import com.craneai.findr.core.SortOrder
import com.craneai.findr.ui.search.ResultViewMode
import com.craneai.findr.ui.search.SortChip
import com.craneai.findr.ui.search.SortDateRow
import com.craneai.findr.ui.search.chipLabelRes
import com.craneai.findr.ui.theme.Sun
import org.junit.Rule
import org.junit.Test

/** UI audit item 11: the sort chip never cuts its word (Luganda at 1.0 and any language at 2.0). */
class SortDateChipTest : AtlasTestBase() {
  @get:Rule val rule = createEmptyComposeRule()

  @Test fun sortWordIsWhole() {
    val a = Atlas(rule, "t-sortchip")
    val cells = listOf("en", "lg").flatMap { l -> listOf(1.0f, 2.0f).map { Cfg(it, false, Dev.SMALL, l) } }
    a.forEach(cells, "sort") { c ->
      prefs()
      for (order in SortOrder.entries) {
        a.launch(c) { Row(androidx.compose.ui.Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState())) { SortChip(order, searching = true, onSort = {}) } }
        a.settle(200)
        val word = a.str(order.chipLabelRes())
        val node = rule.onAllNodes(hasText(word), useUnmergedTree = true).fetchSemanticsNodes().firstOrNull() ?: continue
        val layout = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layout)
        check(layout.none { (0 until it.lineCount).any { l -> it.isLineEllipsized(l) } || it.didOverflowHeight }) { "${c.tag}: sort word '$word' is cut" }
      }
    }
    a.assertNoFailures()
  }

  @Test fun dateChipIsCompactAndGridListToggleStaysVisibleOnPhone() {
    val a = Atlas(rule, "t-sortdaterow")
    val cells = listOf(Cfg(1.0f, false, Dev.PHONE, "en"), Cfg(1.0f, false, Dev.SMALL, "en"))
    a.forEach(cells, "row") { c ->
      prefs()
      a.launch(c) {
        SortDateRow(
          sort = SortOrder.NEWEST,
          searching = false,
          date = null,
          onSort = {},
          onPreset = {},
          onPickRange = {},
          onClearDate = {},
          modifier = Modifier.padding(horizontal = Sun.S16),
          viewMode = ResultViewMode.GRID,
          onViewMode = {},
        )
      }
      a.settle(200)
      check(a.str(R.string.date_any_time) == "Date") { "Expected compact 'Date' label, got '${a.str(R.string.date_any_time)}'" }
      check(a.str(SortOrder.NEWEST.chipLabelRes()) == "Newest") { "Expected compact 'Newest' chip label" }
      val root = rule.onRoot().fetchSemanticsNode().boundsInRoot
      val sortNode = rule.onNodeWithTag("sort-chip", useUnmergedTree = true).fetchSemanticsNode()
      val dateNode = rule.onNodeWithTag("date-chip", useUnmergedTree = true).fetchSemanticsNode()
      val sortBounds = sortNode.boundsInRoot
      val dateBounds = dateNode.boundsInRoot
      val grid = rule.onNodeWithTag("view-mode-grid", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
      val list = rule.onNodeWithTag("view-mode-list", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
      check(sortBounds.width >= sortNode.size.width - 1f) {
        "${c.tag}: Sort chip is horizontally clipped: visible=${sortBounds.width} full=${sortNode.size.width}"
      }
      check(dateBounds.width >= dateNode.size.width - 1f) {
        "${c.tag}: Date chip is horizontally clipped: visible=${dateBounds.width} full=${dateNode.size.width}"
      }
      check(sortBounds.right < dateBounds.left) {
        "${c.tag}: Sort chip collides with Date chip: sort=$sortBounds date=$dateBounds"
      }
      check(dateBounds.right < grid.left) {
        "${c.tag}: Date chip collides/clips against Grid/List toggle: date=$dateBounds grid=$grid"
      }
      check(grid.left >= root.left - 1f && list.right <= root.right + 1f) {
        "${c.tag}: Grid/List toggle must stay visible on phone width: grid=$grid list=$list root=$root"
      }
    }
    a.assertNoFailures()
  }
}
