package com.craneai.findr.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PolitePolicyTest {
  @Test fun politeOnlyWhenScreenOnAndTrawlNotInFront() {
    assertTrue(PolitePolicy.polite(screenOn = true, trawlVisible = false))
    assertFalse("Trawl in front (search, quick panel, Keep learning) is full speed", PolitePolicy.polite(screenOn = true, trawlVisible = true))
    assertFalse("screen off: the phone is down, full speed", PolitePolicy.polite(screenOn = false, trawlVisible = false))
    assertFalse(PolitePolicy.polite(screenOn = false, trawlVisible = true))
  }

  @Test fun aTightPhoneReleasesItsModelsOnlyAfterTheSettleTimeAndOnlyOnce() {
    assertFalse(PolitePolicy.releaseDue(0, released = false, memoryTight = true))
    assertFalse("a notification waking the screen is not enough", PolitePolicy.releaseDue(PolitePolicy.RELEASE_AFTER_MS - 1, released = false, memoryTight = true))
    assertTrue(PolitePolicy.releaseDue(PolitePolicy.RELEASE_AFTER_MS, released = false, memoryTight = true))
    assertFalse(PolitePolicy.releaseDue(10 * PolitePolicy.RELEASE_AFTER_MS, released = true, memoryTight = true))
  }

  @Test fun aPhoneWithRoomKeepsItsModelsWhileItWaits() {
    // The S24 case: 12 GB, the owner hopping between apps; every release cost a 1.6-14 s reload.
    for (ms in listOf(0L, PolitePolicy.RELEASE_AFTER_MS, 5 * PolitePolicy.RELEASE_AFTER_MS, RunHandoff.HAND_OFF_AFTER_MS)) {
      assertFalse("roomy phone at $ms ms", PolitePolicy.releaseDue(ms, released = false, memoryTight = false))
    }
  }

  @Test fun memoryPressureReleasesAtOnceOnAnyPhoneButOnlyOnce() {
    assertTrue(PolitePolicy.releaseDue(2_000, released = false, memoryTight = false, pressure = true))
    assertTrue(PolitePolicy.releaseDue(2_000, released = false, memoryTight = true, pressure = true))
    assertFalse(PolitePolicy.releaseDue(2_000, released = true, memoryTight = false, pressure = true))
  }

  @Test fun theTightnessRuleIsTheMemoryBudgetOne() {
    // 4 GB-class phones (the A06 reports 3.6 GB, the TECNO Spark 50 3.7 GB) are tight; 8 and 12 GB phones with room are not.
    assertTrue(MemoryBudget.tight(totalRamMb = 3_633, availableMb = 1_800))
    assertFalse(MemoryBudget.tight(totalRamMb = 7_600, availableMb = 3_500))
    assertFalse(MemoryBudget.tight(totalRamMb = 11_300, availableMb = 5_000))
    assertTrue("a big phone with little free memory is tight too", MemoryBudget.tight(totalRamMb = 11_300, availableMb = 900))
  }

  @Test fun onlyRealTrimLevelsCountAsPressure() {
    assertFalse("RUNNING_MODERATE is an early hint", PolitePolicy.pressure(5))
    assertTrue("RUNNING_LOW", PolitePolicy.pressure(10))
    assertTrue("RUNNING_CRITICAL", PolitePolicy.pressure(15))
    assertFalse("UI_HIDDEN fires whenever our last screen goes away", PolitePolicy.pressure(20))
    assertTrue("BACKGROUND", PolitePolicy.pressure(40))
    assertTrue("COMPLETE", PolitePolicy.pressure(80))
  }

  @Test fun waitIsLongEnoughToSettleButShortEnoughToStayResponsive() {
    assertEquals(30_000L, PolitePolicy.RELEASE_AFTER_MS)
    assertTrue(PolitePolicy.POLL_MS in 250L..2_000L)
  }
}
