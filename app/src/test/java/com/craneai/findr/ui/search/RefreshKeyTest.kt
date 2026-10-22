package com.craneai.findr.ui.search

import com.craneai.findr.core.IndexPhase
import com.craneai.findr.core.IndexStatus
import com.craneai.findr.core.PauseReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** The home list reloads when this key changes. Delete everything must change it, or the old file names stay on screen. */
class RefreshKeyTest {
  private val waitingForCharger = IndexStatus(phase = IndexPhase.PAUSED, pauseReason = PauseReason.NOT_CHARGING, filesKnown = 248)

  @Test fun deleteWhileWaitingForChargerChangesTheKey() {
    // Same phase (PAUSED) before and after: only the reason and the empty index tell the list it is stale.
    val afterDelete = IndexStatus(phase = IndexPhase.PAUSED, pauseReason = PauseReason.USER, filesKnown = 0)
    assertNotEquals(refreshKey(waitingForCharger), refreshKey(afterDelete))
  }

  @Test fun deleteWhileAlreadyPausedByUserChangesTheKey() {
    val pausedByUser = IndexStatus(phase = IndexPhase.PAUSED, pauseReason = PauseReason.USER, filesKnown = 248)
    assertNotEquals(refreshKey(pausedByUser), refreshKey(pausedByUser.copy(filesKnown = 0)))
  }

  @Test fun progressDoesNotChangeTheKey() {
    val a = IndexStatus(phase = IndexPhase.INDEXING, filesKnown = 248, filesUnderstood = 3)
    assertEquals(refreshKey(a), refreshKey(a.copy(filesUnderstood = 4, filesSearchable = 200)))
  }
}
