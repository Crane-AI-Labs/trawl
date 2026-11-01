package com.craneai.findr.ui.atlas

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import com.craneai.findr.core.PackCatalog
import com.craneai.findr.core.PackState
import com.craneai.findr.engine.Edition
import com.craneai.findr.core.FindrSettings
import com.craneai.findr.ui.addons.PackHeader
import com.craneai.findr.core.packRows
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** UI audit item 13: at large text an add-on's status tag sits under its title, and the title keeps most of the card's width. */
class AddOnHeaderTest : AtlasTestBase() {
  @get:Rule val rule = createEmptyComposeRule()

  @Test fun titleKeepsItsWidthAtLargeText() {
    assumeTrue(Edition.MODELS)
    val a = Atlas(rule, "t-addon-header")
    a.forEach(listOf(Cfg(1.3f, false, Dev.SMALL), Cfg(2.0f, false, Dev.SMALL), Cfg(2.0f, false, Dev.PHONE, "lg"), Cfg(1.0f, false, Dev.SMALL)), "addon") { c ->
      prefs()
      val rows = packRows(PackCatalog.all, PackCatalog.all.associate { it.id to PackState.NotInstalled }, FindrSettings())
      a.launch(c) { Column(Modifier.padding(16.dp)) { rows.forEach { PackHeader(it) } } }
      a.settle(300)
      val titles = rule.onAllNodes(hasTestTag("pack-title"), useUnmergedTree = true).fetchSemanticsNodes().map { it.boundsInRoot }
      val tags = rule.onAllNodes(hasTestTag("pack-tag"), useUnmergedTree = true).fetchSemanticsNodes().map { it.boundsInRoot }
      check(titles.size == rows.size && tags.size == rows.size) { "${c.tag}: ${titles.size} titles, ${tags.size} tags for ${rows.size} add-ons" }
      val d = a.activity.resources.displayMetrics.density
      titles.zip(tags).forEach { (t, g) ->
        if (c.scale >= 1.3f) check(g.top >= t.bottom - 1f) { "${c.tag}: tag $g beside title $t" }
        check(t.width >= 150f * d) { "${c.tag}: title is only ${t.width / d} dp wide" }
      }
    }
    a.assertNoFailures()
  }
}
