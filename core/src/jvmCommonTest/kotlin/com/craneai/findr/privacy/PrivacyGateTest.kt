package com.craneai.findr.privacy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The lock and reveal rules, with no Android in them: time is a number the test moves. */
class PrivacyGateTest {
  private val minute = 60_000L
  private fun gate(lock: Boolean = true, after: Long = 5 * minute, grace: Long = minute) =
    PrivacyGate(PrivacyGate.Config(lockEnabled = lock, lockAfterMs = after, revealGraceMs = grace)).also { it.onProcessStart() }

  @Test fun theLockIsOffByDefault() {
    val g = PrivacyGate()
    g.onProcessStart()
    assertFalse(g.isLocked)
    assertFalse(g.isRevealed)
  }

  @Test fun aColdStartWithTheLockOnBeginsLockedAndHidden() {
    val g = gate()
    assertTrue(g.isLocked)
    assertFalse(g.isRevealed)
  }

  @Test fun unlockingOpensTheApp() {
    val g = gate()
    g.onUnlocked()
    assertFalse(g.isLocked)
  }

  @Test fun leavingBrieflyKeepsItOpenAndLeavingLongLocksIt() {
    val g = gate()
    g.onUnlocked()
    g.onBackground(0)
    g.onForeground(4 * minute)
    assertFalse("4 min away with a 5 min timeout", g.isLocked)
    g.onBackground(10 * minute)
    g.onForeground(10 * minute + 5 * minute)
    assertTrue("exactly the timeout locks", g.isLocked)
  }

  @Test fun everyTimeMeansZeroMinutes() {
    val g = gate(after = 0)
    g.onUnlocked()
    g.onBackground(1_000)
    g.onForeground(1_001)
    assertTrue(g.isLocked)
  }

  @Test fun theScreenTurningOffLocksAtOnceWhateverTheTimeout() {
    val g = gate(after = 30 * minute)
    g.onUnlocked()
    g.onScreenOff()
    assertTrue(g.isLocked)
  }

  @Test fun foregroundWithoutAnEarlierBackgroundChangesNothing() {
    val g = gate()
    g.onUnlocked()
    g.onForeground(99 * minute)
    assertFalse(g.isLocked)
  }

  @Test fun turningTheLockOnDoesNotLockYouOutOfWhatYouAreDoing() {
    val g = gate(lock = false)
    g.configure(PrivacyGate.Config(lockEnabled = true))
    assertFalse(g.isLocked)
    g.onScreenOff()
    assertTrue(g.isLocked)
  }

  @Test fun turningTheLockOffOpensIt() {
    val g = gate()
    g.configure(PrivacyGate.Config(lockEnabled = false))
    assertFalse(g.isLocked)
    g.onScreenOff()
    assertFalse("a lock that is off never closes", g.isLocked)
  }

  @Test fun onlyOnePromptAtATime() {
    val g = gate()
    assertTrue(g.beginAuth())
    assertFalse(g.beginAuth())
    g.endAuth()
    assertTrue(g.beginAuth())
  }

  @Test fun revealWorksWithoutTheAppLock() {
    val g = gate(lock = false)
    g.onRevealedByUser()
    assertTrue(g.isRevealed)
    g.hide()
    assertFalse(g.isRevealed)
  }

  @Test fun revealEndsWhenTheScreenTurnsOff() {
    val g = gate(lock = false)
    g.onRevealedByUser()
    g.onScreenOff()
    assertFalse(g.isRevealed)
  }

  @Test fun revealSurvivesAQuickGlanceElsewhereButNotALongOne() {
    val g = gate(lock = false, grace = minute)
    g.onRevealedByUser()
    g.onBackground(0)
    g.onForeground(59_000)
    assertTrue(g.isRevealed)
    g.onBackground(100_000)
    g.onForeground(100_000 + minute)
    assertFalse(g.isRevealed)
  }

  @Test fun revealEndsOnItsOwnWhileTheAppIsStillAway() {
    val g = gate(lock = false, grace = minute)
    g.onRevealedByUser()
    g.onBackground(0)
    g.onRevealGraceElapsed(30_000)
    assertTrue(g.isRevealed)
    g.onRevealGraceElapsed(minute)
    assertFalse(g.isRevealed)
  }

  @Test fun revealIsSeparateFromTheLockTimeout() {
    // Away 2 min: still unlocked (5 min), but the 1 min reveal is over.
    val g = gate()
    g.onUnlocked()
    g.onRevealedByUser()
    g.onBackground(0)
    g.onForeground(2 * minute)
    assertFalse(g.isLocked)
    assertFalse(g.isRevealed)
  }

  @Test fun aNewProcessAlwaysStartsHidden() {
    val g = gate(lock = false)
    g.onRevealedByUser()
    g.onProcessStart()
    assertFalse(g.isRevealed)
  }

  @Test fun defaultsAreFiveMinutesAndOneMinute() {
    assertEquals(5 * minute, PrivacyGate.DEFAULT_LOCK_AFTER_MS)
    assertEquals(minute, PrivacyGate.DEFAULT_REVEAL_GRACE_MS)
  }
}
