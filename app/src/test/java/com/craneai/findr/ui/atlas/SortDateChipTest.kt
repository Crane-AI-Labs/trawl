package com.craneai.findr.ui.atlas

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.craneai.findr.R
import com.craneai.findr.core.SortOrder
import com.craneai.findr.ui.search.SortChip
import com.craneai.findr.ui.search.labelRes
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
        val word = a.str(order.labelRes())
        val node = rule.onAllNodes(hasText(word), useUnmergedTree = true).fetchSemanticsNodes().firstOrNull() ?: continue
        val layout = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layout)
        check(layout.none { (0 until it.lineCount).any { l -> it.isLineEllipsized(l) } || it.didOverflowHeight }) { "${c.tag}: sort word '$word' is cut" }
      }
    }
    a.assertNoFailures()
  }
}
