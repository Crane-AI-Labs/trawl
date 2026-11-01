package com.craneai.findr.ui.atlas

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.craneai.findr.core.KindFilter
import org.junit.Rule
import org.junit.Test

/** UI audit item 14: on a video tile the length pill and the share badge never touch, at any font size. */
class VideoTileTest : AtlasTestBase() {
  @get:Rule val rule = createEmptyComposeRule()

  private fun b(tag: String) = rule.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().map { it.boundsInRoot }.filter { it.width > 0f }

  @Test fun pillAndBadgeDoNotMeet() {
    val a = Atlas(rule, "t-video")
    a.forEach(listOf(Cfg(1.0f, false, Dev.SMALL), Cfg(2.0f, false, Dev.SMALL), Cfg(2.0f), Cfg(1.3f, false, Dev.PHONE, "lg")), "video") { c ->
      prefs(firstSearchDone = true)
      val v = Vms(AtlasEngine(corpus = Corpus.MEDIA_ONLY))
      a.launchSearch(c, v)
      v.search.setFilter(KindFilter.VIDEOS)
      a.settle(500)
      val pills = b("video-pill"); val badges = b("share-badge")
      check(pills.isNotEmpty() && badges.isNotEmpty()) { "${c.tag}: no video tile drawn (pills ${pills.size}, badges ${badges.size})" }
      for (p in pills) for (s in badges) check(!p.overlaps(s)) { "${c.tag}: pill $p meets share badge $s" }
    }
    a.assertNoFailures()
  }
}
