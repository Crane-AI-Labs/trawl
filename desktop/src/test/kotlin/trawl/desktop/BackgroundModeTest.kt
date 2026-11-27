package trawl.desktop

import com.craneai.findr.core.LearningSpeed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** When the whole app is put behind the person's programs, and that it always comes back before a window is seen. */
class BackgroundModeTest {
  private class Fake(var refuse: Boolean = false, var explode: Boolean = false, override val supportsUtility: Boolean = false) : Throttle {
    val calls = ArrayList<String>()
    override fun enter(speed: LearningSpeed): String? { calls += "enter ${speed.key}"; if (explode) error("boom"); return if (refuse) null else "in" }
    override fun enterUtility(speed: LearningSpeed): String? { calls += "utility ${speed.key}"; if (explode) error("boom"); return if (refuse) null else "util" }
    override fun leave(): String? { calls += "leave"; return if (refuse) null else "out" }
  }

  private var speed = LearningSpeed.BALANCED
  private val logs = ArrayList<String>()
  private fun mode(f: Fake?) = BackgroundMode(f, { speed }, { logs += it })

  @Test fun gentleGoesBehindWhenNoWindowShowsAndOnlyThen() {
    speed = LearningSpeed.GENTLE
    val f = Fake(); val m = mode(f)
    m.refresh(); assertTrue(f.calls.isEmpty(), "a window shows: nothing to do")
    m.mainVisible = false; m.refresh()
    assertEquals(listOf("enter gentle"), f.calls); assertTrue(m.inBackground)
    m.mainVisible = true; m.refresh()
    assertEquals(listOf("enter gentle", "leave"), f.calls); assertFalse(m.inBackground)
  }

  @Test fun gentleStaysForegroundWhileAWindowShowsEvenWithoutTheFocus() {
    speed = LearningSpeed.GENTLE
    val f = Fake(); val m = mode(f)
    m.mainFocused = false; m.refresh()
    assertTrue(f.calls.isEmpty())
  }

  @Test fun balancedAlsoGoesBehindWhenTheWindowShowsButIsNotTheOneBeingUsed() {
    val f = Fake(); val m = mode(f)
    m.mainFocused = false; m.refresh()
    assertEquals(listOf("enter balanced"), f.calls)
    m.mainFocused = true; m.refresh()
    assertEquals(listOf("enter balanced", "leave"), f.calls)
  }

  @Test fun fastIsNeverBehind() {
    speed = LearningSpeed.FAST
    val f = Fake(); val m = mode(f)
    m.mainVisible = false; m.mainFocused = false; m.refresh()
    assertTrue(f.calls.isEmpty())
  }

  @Test fun theQuickPanelIsForegroundAndClearsAtOnce() {
    speed = LearningSpeed.GENTLE
    val f = Fake(); val m = mode(f)
    m.mainVisible = false; m.refresh()
    assertTrue(m.inBackground)
    m.panelOpen = true; m.refresh()
    assertFalse(m.inBackground, "the panel opens on a hotkey and must not open inside a throttled process")
    assertEquals("leave", f.calls.last())
    m.panelOpen = false; m.refresh()
    assertTrue(m.inBackground)
  }

  @Test fun aNewSpeedWhileBehindIsAppliedAndAFastOneLeaves() {
    val f = Fake(); val m = mode(f)
    m.mainVisible = false; m.refresh()
    speed = LearningSpeed.GENTLE; m.refresh()
    speed = LearningSpeed.FAST; m.refresh()
    assertEquals(listOf("enter balanced", "enter gentle", "leave"), f.calls)
  }

  @Test fun repeatedRefreshesDoNothingMore() {
    val f = Fake(); val m = mode(f)
    m.mainVisible = false
    repeat(20) { m.refresh() }
    assertEquals(1, f.calls.size)
  }

  @Test fun aRefusalIsRememberedPerSpeedAndNotAskedAgain() {
    val f = Fake(refuse = true); val m = mode(f)
    m.mainVisible = false
    repeat(5) { m.refresh() }
    assertEquals(1, f.calls.size)
    assertFalse(m.inBackground)
    m.mainVisible = true; m.refresh()
    assertEquals(1, f.calls.size, "nothing was entered, so nothing is left")
  }

  @Test fun aThrottleThatFailsIsClearedAndTheAppRunsNormally() {
    val f = Fake(explode = true); val m = mode(f)
    m.mainVisible = false; m.refresh()
    assertFalse(m.inBackground)
    assertTrue(logs.any { "running normally" in it })
  }

  @Test fun closeAlwaysClears() {
    speed = LearningSpeed.GENTLE
    val f = Fake(); val m = mode(f)
    m.mainVisible = false; m.refresh()
    m.close()
    assertEquals("leave", f.calls.last()); assertFalse(m.inBackground)
  }

  @Test fun withoutAThrottleItIsAllANoOp() {
    val m = mode(null)
    m.mainVisible = false; m.refresh(); m.close()
    assertFalse(m.available); assertFalse(m.inBackground)
  }

  @Test fun theThrottleIsOffUnlessAskedForAndOnlyExistsWhereTheSystemHasOne() {
    assertNull(Throttle.forThisSystem(Os.MAC, on = false))
    assertNull(Throttle.forThisSystem(Os.WINDOWS, on = true, windowsLevel = 0))
    assertTrue(Throttle.forThisSystem(Os.WINDOWS, on = true, windowsLevel = 1) is WindowsProcess)
    assertTrue(Throttle.forThisSystem(Os.WINDOWS, on = true, windowsLevel = 2) is WindowsProcess)
    assertNull(Throttle.forThisSystem(Os.WINDOWS, on = false, windowsLevel = 2))
    val mac = Throttle.forThisSystem(Os.MAC, on = true)
    assertTrue(mac != null && mac.supportsUtility)
  }

  @Test fun macUtilityTierSeparatesActiveOrBatteryFromAwayOnAc() {
    var awayAc = false
    val tiers = ArrayList<BackgroundTier>()
    val f = Fake(supportsUtility = true)
    val m = BackgroundMode(f, { speed }, { logs += it }, awayOnAc = { awayAc }, allowUtility = true, onTierChanged = { tiers += it })

    // Hidden while user is active (or on battery): full background class.
    m.mainVisible = false; m.mainFocused = false; m.refresh()
    assertEquals(BackgroundTier.BACKGROUND, m.tier)
    assertTrue(m.inBackground)

    // User goes away on AC power: transitions to Utility middle tier.
    awayAc = true; m.refresh()
    assertEquals(BackgroundTier.UTILITY, m.tier)
    assertTrue(m.inBackground)

    // Repeated refresh in same state is a no-op.
    repeat(10) { m.refresh() }

    // User returns to keyboard (or unplugs to battery): drops back to full background immediately.
    awayAc = false; m.refresh()
    assertEquals(BackgroundTier.BACKGROUND, m.tier)

    // Away on AC again, then Quick Panel hotkey opens: returns to foreground at once.
    awayAc = true; m.refresh()
    assertEquals(BackgroundTier.UTILITY, m.tier)
    m.panelOpen = true; m.refresh()
    assertEquals(BackgroundTier.FOREGROUND, m.tier)
    assertFalse(m.inBackground)

    assertEquals(listOf("enter balanced", "utility balanced", "enter balanced", "utility balanced", "leave"), f.calls)
    assertEquals(
      listOf(BackgroundTier.BACKGROUND, BackgroundTier.UTILITY, BackgroundTier.BACKGROUND, BackgroundTier.UTILITY, BackgroundTier.FOREGROUND),
      tiers,
    )
  }

  @Test fun utilityTierDisabledOrFastSpeedNeverUsesUtility() {
    var awayAc = true
    val f = Fake(supportsUtility = true)
    val off = BackgroundMode(f, { speed }, { logs += it }, awayOnAc = { awayAc }, allowUtility = false)
    off.mainVisible = false; off.refresh()
    assertEquals(BackgroundTier.BACKGROUND, off.tier)
    assertEquals(listOf("enter balanced"), f.calls)

    speed = LearningSpeed.FAST
    val fast = BackgroundMode(f, { speed }, { logs += it }, awayOnAc = { awayAc }, allowUtility = true)
    fast.mainVisible = false; fast.mainFocused = false; fast.refresh()
    assertEquals(BackgroundTier.FOREGROUND, fast.tier)
    assertFalse(fast.inBackground)
  }

  @Test fun closeClearsUtilityTierBackToForeground() {
    val f = Fake(supportsUtility = true)
    val m = BackgroundMode(f, { speed }, { logs += it }, awayOnAc = { true }, allowUtility = true)
    m.mainVisible = false; m.refresh()
    assertEquals(BackgroundTier.UTILITY, m.tier)
    m.close()
    assertEquals(BackgroundTier.FOREGROUND, m.tier)
    assertFalse(m.inBackground)
    assertEquals(listOf("utility balanced", "leave"), f.calls)
  }
}
