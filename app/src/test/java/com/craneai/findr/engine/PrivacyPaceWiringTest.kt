package com.craneai.findr.engine

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Privacy pacing until searchable ([PrivacyPace], the switch-over itself is tested in core's PrivacyPaceTest): only the background
 * pass rests. A thumbnail on screen is scored on demand and must never wait on the pace.
 */
class PrivacyPaceWiringTest {
  private val root = File("src/main/java/com/craneai/findr")
  private val coreRoots = File("../core/src").listFiles { f -> f.name.endsWith("Main") }.orEmpty().map { File(it, "kotlin/com/craneai/findr") }

  @Test fun onlyTheBackgroundPassIsPaced() {
    val users = (listOf(root) + coreRoots).flatMap { r ->
      r.walkTopDown().filter { it.isFile && it.extension == "kt" }.filter { "PrivacyPace" in it.readText() }.map { it.relativeTo(r).path }.toList()
    }.toSet()
    // Tuning.kt holds the baseline knob (privacyPace) and names the class in its doc.
    assertEquals(setOf("engine/PrivacyPace.kt", "engine/PrivacyPass.kt", "engine/FindrEngine.kt", "engine/IndexWorker.kt", "engine/Tuning.kt"), users)
  }

  @Test fun onDemandScoringNeverRestsOrReadsThePace() {
    val onDemand = File(root, "engine/PrivacyOnDemand.kt").readText()
    for (needle in listOf("PrivacyPace", "restMs", "privacyDuty", "heatPaced", "privacyPaceWarm")) {
      assertFalse("PrivacyOnDemand reads $needle", needle in onDemand)
    }
    // The scoring step the on-demand queue shares with the pass (PrivacyPass.scoreAndSave) has no rest of its own: the pass rests
    // in its loop, after scoreAndSave returns.
    val pass = File(root, "engine/PrivacyPass.kt").readText()
    val body = pass.substringAfter("internal fun scoreAndSave(").substringBefore("private fun pictures(")
    assertFalse("scoreAndSave must not delay", "delay(" in body)
    assertFalse("scoreAndSave must not read the pace", "pace" in body.lowercase())
    val queue = coreRoots.map { File(it, "engine/OnDemandQueue.kt") }.firstOrNull { it.exists() }?.readText()
      ?: (listOf(root) + coreRoots).flatMap { r -> r.walkTopDown().filter { it.name == "OnDemandQueue.kt" }.toList() }.single().readText()
    assertFalse("the on-demand queue reads the pace", "PrivacyPace" in queue || "restMs" in queue)
  }

  @Test fun oneEpisodeClockPerWorkerRun() {
    val worker = File(root, "engine/IndexWorker.kt").readText()
    assertEquals("one PrivacyPace per run, made outside startPrivacy", 1, Regex("""newPrivacyPace\(\)""").findAll(worker).count())
    assertTrue(worker.contains(".run(privacyPace)"))
    assertTrue("the pace is made before startPrivacy is defined", worker.indexOf("newPrivacyPace()") < worker.indexOf("fun startPrivacy()"))
  }

  @Test fun theBaselineKnobIsExactly107() {
    assertEquals(true, Tuning.DEFAULT.privacyPace)
    assertEquals(false, Tuning.apply(Tuning.DEFAULT, mapOf("privacyPace" to "false")).privacyPace)
    assertEquals(true, Tuning.apply(Tuning.DEFAULT, emptyMap()).privacyPace)
    assertEquals("shipped: catch up only, never pace (the A06 study)", 0, Tuning.DEFAULT.privacyPaceMaxS)
    assertEquals("a study can still pace", 900, Tuning.apply(Tuning.DEFAULT, mapOf("privacyPaceMaxS" to "900")).privacyPaceMaxS)
    assertEquals("out of range is ignored", 0, Tuning.apply(Tuning.DEFAULT, mapOf("privacyPaceMaxS" to "99999")).privacyPaceMaxS)
  }
}
