package com.craneai.findr.core

import com.craneai.findr.core.PrivateLook.Look
import org.junit.Assert.assertEquals
import org.junit.Test

/** Blur until scored (internal/research/blur-until-scored.md): the three looks of a thumbnail. */
class PrivateLookLooksTest {
  private fun look(score: Float?, unscored: Boolean, fresh: Float? = null, canCheck: Boolean = true) =
    PrivateLook.look(score, unscored, fresh, canCheck, PrivateLook.MAIN_APP_THRESHOLD)

  @Test fun anUnscoredPictureIsCheckedBlurredWhileAScorerCanScoreIt() {
    assertEquals(Look.CHECKING, look(null, unscored = true))
    assertEquals("no scorer: nothing could ever score it, so nothing waits blurred", Look.CLEAR, look(null, unscored = true, canCheck = false))
  }

  @Test fun aScoredPictureIsClearOrPrivateByItsScore() {
    assertEquals(Look.CLEAR, look(0.1f, unscored = false))
    assertEquals(Look.PRIVATE, look(0.95f, unscored = false))
    assertEquals("the threshold is unchanged", Look.CLEAR, look(0.93f, unscored = false))
    assertEquals("unreadable", Look.CLEAR, look(null, unscored = false))
  }

  @Test fun aFreshScoreDecidesAnUnscoredPicture() {
    assertEquals(Look.CLEAR, look(null, unscored = true, fresh = 0.1f))
    assertEquals(Look.PRIVATE, look(null, unscored = true, fresh = 0.96f))
    assertEquals("unreadable once tried", Look.CLEAR, look(null, unscored = true, fresh = -1f))
  }
}
