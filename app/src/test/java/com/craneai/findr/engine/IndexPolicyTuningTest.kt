package com.craneai.findr.engine

import com.craneai.findr.core.PauseReason
import org.junit.Assert.assertEquals
import org.junit.Test

class IndexPolicyTuningTest {
  @Test fun chargerThermalDecisionsDriveTheSpeechThreadCapOnly() {
    val base = Tuning.DEFAULT.copy(textThreadsOn = 6, textThreadsOff = 8, audioThreads = 8, asrThreads = 4)
    for (thermal in 0..6) {
      val decision = IndexPolicy.decide(true, 10, thermal, onlyWhileCharging = true, fullOnBattery = false, batterySaver = true)
      val tuning = base.withThermalLimit(decision.reducedThreads)
      assertEquals("thermal=$thermal", if (thermal < 3) IndexPolicy.Tier.BULK else IndexPolicy.Tier.WORDS_ONLY, decision.tier)
      assertEquals("thermal=$thermal", if (thermal < 3) null else PauseReason.TOO_HOT, decision.heldBy)
      assertEquals("thermal=$thermal", 6, tuning.textThreadsOn)   // EmbeddingGemma's threads are never cut: each change reloads its engine
      assertEquals("thermal=$thermal", 8, tuning.textThreadsOff)
      assertEquals("thermal=$thermal", 8, tuning.audioThreads)
      assertEquals("thermal=$thermal", if (thermal == 2) 2 else 4, tuning.asrThreads)
    }
  }

  @Test fun aStaleSevereStatusOnTheChargerKeepsFullThreadsUnlessThePhoneMeasuresWarm() {
    val base = Tuning.DEFAULT.copy(textThreadsOn = 6, textThreadsOff = 8, audioThreads = 8, asrThreads = 4)
    for (thermal in 3..6) {
      val cool = IndexPolicy.decide(true, 60, thermal, onlyWhileCharging = false, fullOnBattery = false, batteryC = 35.0, headroom = Float.NaN)
      assertEquals("thermal=$thermal", IndexPolicy.Tier.BULK, cool.tier)
      assertEquals(true, cool.thermalStale)
      assertEquals("the KN4 at 34-36 C keeps every thread (D51)", base, base.withThermalLimit(cool.reducedThreads))
      for ((b, h) in listOf(38.5 to Float.NaN, 35.0 to 0.9f)) {
        val warm = IndexPolicy.decide(true, 60, thermal, onlyWhileCharging = false, fullOnBattery = false, batteryC = b, headroom = h)
        val tuning = base.withThermalLimit(warm.reducedThreads)
        assertEquals(6, tuning.textThreadsOn); assertEquals(8, tuning.textThreadsOff); assertEquals(8, tuning.audioThreads); assertEquals(2, tuning.asrThreads)
      }
    }
  }
}
