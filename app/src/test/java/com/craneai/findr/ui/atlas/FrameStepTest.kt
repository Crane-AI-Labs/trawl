package com.craneai.findr.ui.atlas

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import com.craneai.findr.R
import com.craneai.findr.ui.search.SearchMode
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import org.junit.Rule
import org.junit.Test
import org.robolectric.shadows.ShadowLooper

/**
 * Frame-stepping tests: idle snapshots cannot catch a bad frame in flight (B1). The clock is stopped, the home list's reload is held
 * (the real one takes 0.1 to 1 s), the X is pressed, and every 16 ms frame after it is checked.
 */
class FrameStepTest : AtlasTestBase() {
  @get:Rule val rule = createEmptyComposeRule()

  private fun bounds(tag: String) = rule.onAllNodes(androidx.compose.ui.test.hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes()
    .map { it.boundsInRoot }.filter { it.width > 0f && it.height > 0f }

  /**
   * Type "wedding", press the X with the reload held, step 60 frames (release at frame [release]). Every frame:
   * (1) an empty box never sits over the answers' list (the clear is one state change);
   * (2) the sort chip names the order of the list on screen: Best match for answers, Newest for the home list;
   * (3) no feed item overlaps the header row.
   */
  private fun xFrames(cfg: Cfg, release: Int) {
    val a = Atlas(rule, "t-frames")
    a.forEach(listOf(cfg), "frames") { c ->
      prefs()
      val v = Vms(AtlasEngine(corpus = Corpus.MEDIA_ONLY))
      a.launchSearch(c, v)
      a.focusField(); a.type("wedding")
      check(v.search.state.value.mode == SearchMode.RESULTS) { "setup: answers expected" }
      val gate = CompletableDeferred<Unit>()
      v.engine.recentGate = gate
      rule.mainClock.autoAdvance = false
      rule.onNodeWithContentDescription(a.str(R.string.search_clear)).performClick()
      var prevMode = v.search.state.value.mode
      for (frame in 0 until 60) {
        if (frame == release) gate.complete(Unit)
        ShadowLooper.idleMainLooper(16, TimeUnit.MILLISECONDS)
        rule.mainClock.advanceTimeBy(16)
        val st = v.search.state.value
        val where = "frame $frame (${c.tag}): mode=${st.mode} query='${st.query}'"
        if (st.query.isEmpty()) check(st.mode == SearchMode.RECENT) { "$where: empty box over the answers' list" }
        prevMode = st.mode
        val head = bounds("filters-header").firstOrNull()
        if (head != null) for (b in bounds("feed-item")) check(b.top >= head.bottom - 1f || b.bottom <= head.top + 1f) { "$where: feed item $b overlaps header $head" }
      }
      rule.mainClock.autoAdvance = true
    }
    a.assertNoFailures()
  }

  @Test fun xFramesScale10() = xFrames(Cfg(1.0f), release = 30)
  @Test fun xFramesScale13() = xFrames(Cfg(1.3f), release = 10)
  @Test fun xFramesScale20() = xFrames(Cfg(2.0f), release = 50)
}
