package com.craneai.findr.ui.search

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A relative age ICU could not put into words ("-8 h", Luganda) is replaced by the date; real words are kept. */
class AgeLabelTest {
  @Test fun bareSignedOffsetsAreCaught() {
    for (s in listOf("-8 h", "+3 d", "−17 h", " -2 min")) assertTrue(s, looksUnlocalised(s))
  }

  @Test fun wordsAreKept() {
    for (s in listOf("8 hr. ago", "Yesterday", "il y a 8 h", "منذ 8 ساعات", "saa 8 zilizopita", "in 2 days")) assertFalse(s, looksUnlocalised(s))
  }
}

/** An age label never reads as the future (wrong-clock phones: a file newer than the clock's "now"). */
class AgeBasisTest {
  private val now = 1_700_000_000_000L

  @Test fun futureIsClampedToNow() {
    for (ahead in listOf(1_000L, 60_000L, 86_400_000L, 400L * 86_400_000L)) org.junit.Assert.assertEquals("+$ahead", now, ageBasis(now + ahead, now))
  }

  @Test fun exactlyNowIsNow() = org.junit.Assert.assertEquals(now, ageBasis(now, now))

  @Test fun pastIsUnchanged() {
    for (ago in listOf(1L, 1_000L, 60_000L, 3_600_000L, 86_400_000L, 400L * 86_400_000L)) org.junit.Assert.assertEquals("-$ago", now - ago, ageBasis(now - ago, now))
  }
}
