package trawl.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Heat and Low Power Mode are reasons to wait that the person can answer with "run anyway"; they are not a slower pace. */
class SoftHoldsTest {
  private var clock = 0L
  private var heat = 0
  private var save = false
  private var heatAsked = 0
  private var powerAsked = 0

  private fun holds(thermalAt: Int = 2, lowPowerOn: Boolean = true) = SoftHolds(
    thermalAt, lowPowerOn, { heatAsked++; heat }, { powerAsked++; save }, { clock }, ttlMs = 10_000,
  )

  @Test fun aWarmComputerIsNotAHoldButASeriousOneIs() {
    val h = holds()
    heat = 1; assertFalse(h.thermalHolds())
    clock += 10_000; heat = 2; assertTrue(h.thermalHolds())
    clock += 10_000; heat = 3; assertTrue(h.thermalHolds())
    clock += 10_000; heat = 0; assertFalse(h.thermalHolds(), "it cooled")
  }

  @Test fun runAnywayLiftsTheHoldForTheRestOfTheRunAndOnlyThatOne() {
    val h = holds()
    heat = 3; save = true
    assertTrue(h.thermalHolds()); assertTrue(h.lowPowerHolds())
    h.runAnyway(Hold.THERMAL)
    assertFalse(h.thermalHolds())
    assertTrue(h.overridden(Hold.THERMAL)); assertFalse(h.overridden(Hold.LOW_POWER))
    assertTrue(h.lowPowerHolds(), "the other hold is a separate answer")
    clock += 3_600_000; heat = 0; clock += 10_000; heat = 3
    assertFalse(h.thermalHolds(), "an hour later it is hot again, and the person has already said go on")
    h.runAnyway(Hold.LOW_POWER)
    assertFalse(h.lowPowerHolds())
    h.runAnyway(Hold.BATTERY)   // not one of its holds: nothing happens
  }

  @Test fun theSystemIsAskedAtMostOncePerTtl() {
    val h = holds()
    heat = 2; save = true
    repeat(100) { clock += 50; h.thermalHolds(); h.lowPowerHolds() }
    assertEquals(1, heatAsked); assertEquals(1, powerAsked)
    clock += 10_000; h.thermalHolds(); h.lowPowerHolds()
    assertEquals(2, heatAsked); assertEquals(2, powerAsked)
  }

  @Test fun theRulesCanBeSwitchedOff() {
    val h = holds(thermalAt = 0, lowPowerOn = false)
    heat = 3; save = true
    assertFalse(h.thermalHolds()); assertFalse(h.lowPowerHolds())
    assertEquals(0, heatAsked + powerAsked, "off means the system is not asked")
  }

  @Test fun aSensorThatFailsIsNotAHold() {
    val h = SoftHolds(2, true, { error("no") }, { error("no") }, { clock }, 10_000)
    assertFalse(h.thermalHolds()); assertFalse(h.lowPowerHolds())
  }
}
