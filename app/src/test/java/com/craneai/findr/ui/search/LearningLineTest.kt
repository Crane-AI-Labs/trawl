package com.craneai.findr.ui.search

import com.craneai.findr.core.IndexPhase
import com.craneai.findr.core.IndexStatus
import com.craneai.findr.core.PauseReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What the folded meter says while typing: how far learning has got, or nothing once every file is understood. */
class LearningLineTest {
  private fun s(phase: IndexPhase, understood: Int, known: Int = 3000, needsCharger: Boolean = false, light: Boolean = false, reason: PauseReason? = null) =
    IndexStatus(phase = phase, filesKnown = known, filesSearchable = known, filesUnderstood = understood, needsCharger = needsCharger,
      lightOnBattery = light, pauseReason = reason)

  @Test fun learningShowsUnderstoodOfKnown() {
    assertEquals(Learning(1240, 3000, charger = false, paused = false), s(IndexPhase.INDEXING, 1240).learning())
  }

  @Test fun nothingWhenDoneOrNothingKnown() {
    assertNull(s(IndexPhase.UP_TO_DATE, 3000).learning())
    assertNull(s(IndexPhase.INDEXING, 3000).learning())
    assertNull(s(IndexPhase.NOT_STARTED, 0, known = 0).learning())
  }

  @Test fun theChargerAndPausesAreSaid() {
    assertEquals(true, s(IndexPhase.INDEXING, 10, needsCharger = true).learning()?.charger)
    assertEquals(false, s(IndexPhase.INDEXING, 10, needsCharger = true, light = true).learning()?.charger)   // light work on battery
    assertEquals(true, s(IndexPhase.PAUSED, 10, reason = PauseReason.NOT_CHARGING).learning()?.charger)
    assertEquals(true, s(IndexPhase.PAUSED, 10, reason = PauseReason.USER).learning()?.paused)
  }
}
