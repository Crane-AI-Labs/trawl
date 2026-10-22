package com.craneai.findr.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StoredVectorsProbeTest {
  private var clock = 1_000L
  private var probes = 0
  private var stored = false
  private var loaded = 0

  private fun probe() = StoredVectorsProbe(
    probe = { probes++; stored },
    loaded = { loaded },
    now = { clock },
  )

  @Test fun `a loaded matrix answers yes without asking the store`() {
    loaded = 12
    val p = probe()
    repeat(5) { assertTrue(p.any()) }
    assertEquals(0, probes)
  }

  @Test fun `a yes is cached until reset`() {
    stored = true
    val p = probe()
    repeat(5) { assertTrue(p.any()) }
    assertEquals(1, probes)
    stored = false
    clock += 10 * 60_000
    assertTrue(p.any())            // still cached, the store is not asked again
    assertEquals(1, probes)
    p.reset()
    assertFalse(p.any())           // after a delete or swap the store is asked afresh
    assertEquals(2, probes)
  }

  @Test fun `a no is re-probed at most every 30 seconds`() {
    val p = probe()
    repeat(20) { assertFalse(p.any()) }
    assertEquals(1, probes)
    clock += 29_999
    assertFalse(p.any())
    assertEquals(1, probes)
    stored = true                  // vectors appear while the no is cached
    assertFalse(p.any())           // not seen yet, by design
    clock += 1
    assertTrue(p.any())            // 30 s passed: re-probed, now yes
    assertEquals(2, probes)
    repeat(5) { assertTrue(p.any()) }
    assertEquals(2, probes)
  }

  @Test fun `reset clears a cached no at once`() {
    val p = probe()
    assertFalse(p.any())
    stored = true
    p.reset()
    assertTrue(p.any())
    assertEquals(2, probes)
  }

  @Test fun `a matrix that loads later turns a cached no into yes`() {
    val p = probe()
    assertFalse(p.any())
    loaded = 3
    assertTrue(p.any())
    assertEquals(1, probes)
  }
}
