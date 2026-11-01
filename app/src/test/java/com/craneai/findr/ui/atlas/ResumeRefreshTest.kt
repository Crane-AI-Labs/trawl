package com.craneai.findr.ui.atlas

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performScrollToIndex
import com.craneai.findr.ui.search.SearchMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * UI audit item 8: the app comes back in front, indexing flips UP_TO_DATE -> INDEXING -> UP_TO_DATE and a photo arrives. A list the person
 * is looking at never moves by itself.
 */
class ResumeRefreshTest : AtlasTestBase() {
  @get:Rule val rule = createEmptyComposeRule()

  private fun tops() = rule.onAllNodes(androidx.compose.ui.test.hasTestTag("feed-item"), useUnmergedTree = true).fetchSemanticsNodes()
    .map { it.boundsInRoot }.filter { it.width > 0f && it.height > 0f }.sortedBy { it.top }

  @Test fun sameFilesKeepTheSameListAndNothingScrolls() {
    val a = Atlas(rule, "t-resume")
    a.forEach(listOf(Cfg(1.0f)), "resume") { c ->
      prefs()
      val v = Vms(AtlasEngine(corpus = Corpus.FULL))
      a.launchSearch(c, v)
      a.settle(300)
      rule.onNode(androidx.compose.ui.test.hasScrollToIndexAction()).performScrollToIndex(12)
      a.settle(300)
      val before = tops().first()
      val feedBefore = v.search.state.value.feed
      assertEquals(SearchMode.RECENT, v.search.state.value.mode)
      // Resume: the crawl runs and finishes, nothing new on the phone.
      v.engine.statusFlow.value = Status.indexing; a.settle(300)
      v.engine.statusFlow.value = Status.upToDate; a.settle(300)
      assertSame("same files, same list object", feedBefore, v.search.state.value.feed)
      assertTrue(v.search.state.value.quietRefresh)
      
      assertEquals("first visible row moved", before.top, tops().first().top, 1f)
    }
    a.assertNoFailures()
  }

  @Test fun anArrivingPhotoDoesNotScrollTheListBack() {
    val a = Atlas(rule, "t-resume2")
    a.forEach(listOf(Cfg(1.0f)), "resume2") { c ->
      prefs()
      val v = Vms(AtlasEngine(corpus = Corpus.FULL))
      a.launchSearch(c, v)
      a.settle(300)
      rule.onNode(androidx.compose.ui.test.hasScrollToIndexAction()).performScrollToIndex(12)
      a.settle(300)
      val before = tops().first()
      v.engine.statusFlow.value = Status.indexing; a.settle(300)
      v.engine.arrive(); v.engine.statusFlow.value = Status.upToDate; a.settle(300)
      assertTrue("the new photo is in the list", v.search.state.value.feed.size > 0)
      assertTrue(v.search.state.value.quietRefresh)
      
      assertEquals("the list scrolled by itself", before.top, tops().first().top, 1f)
    }
    a.assertNoFailures()
  }
}
