package com.craneai.findr.ui.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Home shows one card at a time, and a strip instead of a card on wide, short and large-text windows (RC QA, tablet landscape). */
class HomeCardsTest {
  private val all = HomeCard.entries.toSet()

  @Test fun `one card wins by priority`() {
    assertEquals(HomeCard.CRASH, HomeCards.pick(all, typing = false, searching = false))
    assertEquals(HomeCard.ACCESS, HomeCards.pick(all - HomeCard.CRASH, false, false))
    assertEquals(HomeCard.MODELS, HomeCards.pick(all - HomeCard.CRASH - HomeCard.ACCESS, false, false))
    assertEquals(HomeCard.PAUSE, HomeCards.pick(setOf(HomeCard.PAUSE, HomeCard.NOTIFICATIONS), false, false))
    assertEquals(HomeCard.SAMSUNG, HomeCards.pick(setOf(HomeCard.SAMSUNG, HomeCard.NOTIFICATIONS), false, false))
    assertEquals(HomeCard.NOTIFICATIONS, HomeCards.pick(setOf(HomeCard.NOTIFICATIONS), false, false))
  }

  @Test fun `nothing wanted shows nothing`() {
    assertNull(HomeCards.pick(emptySet(), typing = false, searching = false))
  }

  @Test fun `typing puts every card away`() {
    HomeCard.entries.forEach { assertNull(HomeCards.pick(setOf(it), typing = true, searching = false)) }
  }

  @Test fun `searching keeps the urgent cards and puts the favours away`() {
    assertEquals(HomeCard.CRASH, HomeCards.pick(setOf(HomeCard.CRASH), typing = false, searching = true))
    assertEquals(HomeCard.ACCESS, HomeCards.pick(setOf(HomeCard.ACCESS), false, true))
    assertEquals(HomeCard.MODELS, HomeCards.pick(setOf(HomeCard.MODELS), false, true))
    assertNull(HomeCards.pick(setOf(HomeCard.PAUSE), false, true))
    assertNull(HomeCards.pick(setOf(HomeCard.SAMSUNG), false, true))
    assertNull(HomeCards.pick(setOf(HomeCard.NOTIFICATIONS), false, true))
  }

  @Test fun `a card put away does not hand its slot to a lower one`() {
    // The pause note is first in line but not wanted while searching; the notification question must not slip in under it.
    assertNull(HomeCards.pick(setOf(HomeCard.PAUSE, HomeCard.NOTIFICATIONS), typing = false, searching = true))
    assertNull(HomeCards.pick(setOf(HomeCard.SAMSUNG, HomeCard.NOTIFICATIONS), typing = false, searching = true))
  }

  @Test fun `landscape is compact`() {
    assertTrue(HomeCards.compact(widthDp = 1280, heightDp = 800, fontScale = 1f)) // tablet landscape
    assertTrue(HomeCards.compact(widthDp = 800, heightDp = 360, fontScale = 1f)) // phone landscape
  }

  @Test fun `portrait at normal text is a full card`() {
    assertFalse(HomeCards.compact(widthDp = 800, heightDp = 1280, fontScale = 1f)) // tablet portrait
    assertFalse(HomeCards.compact(widthDp = 360, heightDp = 780, fontScale = 1f)) // phone portrait
    assertFalse(HomeCards.compact(widthDp = 360, heightDp = 780, fontScale = 1.15f))
  }

  @Test fun `large text is compact even in portrait`() {
    assertTrue(HomeCards.compact(widthDp = 360, heightDp = 780, fontScale = 1.3f))
    assertTrue(HomeCards.compact(widthDp = 360, heightDp = 780, fontScale = 1.5f))
  }

  @Test fun `a short window is compact even when taller than wide`() {
    assertTrue(HomeCards.compact(widthDp = 360, heightDp = 420, fontScale = 1f)) // split screen
  }

  @Test fun `searching always uses the strip`() {
    assertTrue(HomeCards.asStrip(compact = false, searching = true))
    assertTrue(HomeCards.asStrip(compact = true, searching = false))
    assertFalse(HomeCards.asStrip(compact = false, searching = false))
  }
}
