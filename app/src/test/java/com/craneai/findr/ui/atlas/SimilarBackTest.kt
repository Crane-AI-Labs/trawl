package com.craneai.findr.ui.atlas

import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.craneai.findr.core.FileKind
import com.craneai.findr.ui.search.SearchMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Verifies Android system Back from "Find similar" restores the prior search instead of closing the app. */
class SimilarBackTest : AtlasTestBase() {
  @get:Rule val rule = createEmptyComposeRule()

  @Test fun systemBackLeavesFindSimilarAndRestoresPriorSearch() {
    val a = Atlas(rule, "t-similar-back")
    a.forEach(listOf(Cfg(1.0f)), "similar-back") { c ->
      prefs()
      val v = Vms(AtlasEngine(corpus = Corpus.MEDIA_ONLY))
      a.launchSearch(c, v)

      // Search first, then enter Find similar from a result.
      a.focusField(); a.type("wedding"); a.keyboardDown()
      assertEquals("wedding", v.search.state.value.resultsQuery)

      val seed = v.engine.files.first { it.kind == FileKind.IMAGE }
      v.search.findSimilar(seed, firstVisibleItemIndex = 2, firstVisibleItemScrollOffset = 24); a.settle(600)
      assertNotNull("Find similar must be active", v.search.state.value.similar)
      assertEquals("", v.search.state.value.query)

      // Selection inside Find similar: first Back clears selection, second Back leaves Find similar.
      val other = v.engine.files.last { it.id != seed.id }
      v.search.toggleSelected(other); a.settle(300)
      assertTrue("Selection should be active", v.search.state.value.selected.isNotEmpty())

      a.back()
      assertFalse("Back should clear selection, not finish activity", a.activity.isFinishing)
      assertTrue("First Back must clear selection", v.search.state.value.selected.isEmpty())
      assertNotNull("Find similar should still be active after clearing selection", v.search.state.value.similar)

      a.back()
      a.settle(600)
      assertFalse("Back from Find similar must not finish activity", a.activity.isFinishing)
      assertNull("Second Back must exit Find similar", v.search.state.value.similar)
      assertEquals("wedding", v.search.state.value.query)
      assertEquals("wedding", v.search.state.value.resultsQuery)
      assertEquals(SearchMode.RESULTS, v.search.state.value.mode)
      assertNull("Scroll restore must be consumed by Results once applied", v.search.state.value.pendingScrollRestore)
      a.field().assertTextEquals("wedding")
    }
    a.assertNoFailures()
  }
}
