package com.craneai.findr.engine

import com.craneai.findr.engine.IndexPause.Event
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IndexPauseTest {
  @Test fun aLaunchOrRetryNeverUndoesTheUsersPause() {
    assertTrue(IndexPause.after(Event.START, paused = true))
    assertFalse(IndexPause.after(Event.START, paused = false))
  }

  @Test fun onlyResumeClearsIt() {
    var paused = IndexPause.after(Event.PAUSE, paused = false)
    repeat(3) { paused = IndexPause.after(Event.START, paused) }   // the app opened three times
    assertTrue(paused)
    paused = IndexPause.after(Event.RESUME, paused)
    assertFalse(paused)
  }

  @Test fun pauseAndResumeAreIdempotent() {
    for (p in listOf(true, false)) {
      assertEquals(true, IndexPause.after(Event.PAUSE, p))
      assertEquals(false, IndexPause.after(Event.RESUME, p))
    }
  }
}
