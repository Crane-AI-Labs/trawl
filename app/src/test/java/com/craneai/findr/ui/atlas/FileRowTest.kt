package com.craneai.findr.ui.atlas

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onRoot
import com.craneai.findr.R
import com.craneai.findr.core.FileKind
import com.craneai.findr.ui.search.FileRow
import org.junit.Rule
import org.junit.Test

/** UI audit item 12: from 130% text the row's Share and Copy sit under the name, so the name keeps the row's width. */
class FileRowTest : AtlasTestBase() {
  @get:Rule val rule = createEmptyComposeRule()

  @Test fun actionsMoveUnderTheNameAtLargeText() {
    val a = Atlas(rule, "t-filerow")
    a.forEach(listOf(Cfg(1.3f, false, Dev.PHONE, "lg"), Cfg(1.3f, false, Dev.SMALL), Cfg(2.0f, false, Dev.SMALL)), "filerow") { c ->
      prefs()
      val v = Vms(AtlasEngine(corpus = Corpus.FULL))
      val hit = v.hitsFor("school").first { it.file.kind == FileKind.DOCUMENT || it.file.kind == FileKind.PDF }
      a.launch(c) { FileRow(hit, {}, {}, {}, {}) }
      a.settle(300)
      val name = rule.onAllNodes(hasText(hit.file.name), useUnmergedTree = true).fetchSemanticsNodes().first().boundsInRoot
      val share = rule.onAllNodes(hasContentDescription(a.str(R.string.action_share_file, hit.file.name)), useUnmergedTree = true).fetchSemanticsNodes().first().boundsInRoot
      check(share.top >= name.bottom - 1f) { "${c.tag}: Share $share is beside the name $name" }
      val win = rule.onRoot().fetchSemanticsNode().boundsInRoot
      check(name.width >= win.width * 0.45f) { "${c.tag}: the name has only ${name.width} of ${win.width} px" }
    }
    a.assertNoFailures()
  }
}
