package com.craneai.findr.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt
import kotlin.random.Random

/** When the engine probes run (ProbeGate): not on a normal warm load, yes on every case that could have damaged the cache. */
class ProbeGateTest {
  @get:Rule val tmp = TemporaryFolder()

  private val bundle = "embeddinggemma-2-test.litertlm"
  private val golden = listOf("one", "two", "three").mapIndexed { i, t ->
    val r = Random(i + 7)
    val v = FloatArray(768) { r.nextFloat() - 0.5f }
    val n = sqrt(v.sumOf { (it * it).toDouble() }).toFloat()
    EngineProbe.Golden("p$i", t, FloatArray(768) { v[it] / n })
  }
  private val probe = EngineProbe(mapOf(bundle to golden))

  private class Memory : ProbeMemory {
    var record: ProbeRecord? = null
    override fun read() = record
    override fun write(record: ProbeRecord) { this.record = record }
    override fun clear() { record = null }
  }

  /** One app process's worth of state: a cache folder, a model file, a memory, and a clock. */
  private inner class Rig(var appVersion: Long = 10, var lastCrashAt: Long = 0, var clock: Long = 1_000) {
    val cacheDir: File = tmp.newFolder()
    val model: File = File(tmp.newFolder(), bundle).apply { writeText("model") }
    val memory = Memory()
    val logs = ArrayList<String>()
    var probeRuns = 0
    var loads = 0
    var pinOnBuild: Long? = null   // when set, every cache this rig's load builds gets exactly this modification time (ns)
    val cacheFile get() = File(cacheDir, "${bundle}_1_2.text_encoder.xnnpack_cache")

    /** Pins a file's modification time, so a test never depends on how fast the file system clock ticks. */
    fun pin(f: File, nanos: Long) {
      Files.setLastModifiedTime(f.toPath(), FileTime.from(nanos, TimeUnit.NANOSECONDS))
    }

    /** A load as the Embedder does it; [engineMode] "OK" gives the golden vectors, anything else garbage. */
    fun load(engineMode: String = "OK"): String? {
      val schedule = ProbeSchedule(memory, cacheDir, model, appVersion, { lastCrashAt }, { clock })
      val test = EngineSelfTest<String>(
        probe, clearCache = { EngineCache.clear(cacheDir, bundle) }, log = { logs += it },
        skipReason = schedule::skipReason, onPassed = schedule::passed, onFailed = schedule::failed,
      )
      test.loadVerified(
        bundle,
        // a load makes the cache when absent; each build has its own bytes, as a real rebuild after a torn write may
        load = {
          loads++
          if (!cacheFile.exists()) { cacheFile.writeText("cache built by load #$loads"); pinOnBuild?.let { pin(cacheFile, it) } }
          engineMode
        },
        embed = { mode, text -> probeRuns++; golden.first { it.text == text }.vector.let { v -> if (mode == "OK") v else FloatArray(768) { Float.NaN } } },
        close = {},
      )
      return logs.lastOrNull { it.startsWith("engine self-test") }
    }
  }

  @Test fun `the first load probes and remembers what passed`() {
    val rig = Rig()
    rig.load()
    assertEquals(3, rig.probeRuns)
    val rec = rig.memory.record
    assertNotNull(rec)
    assertEquals(10L, rec!!.appVersion)
    assertEquals(1_000L, rec.passedAtMs)
    assertTrue(rec.fingerprint.contains(rig.cacheFile.name))
  }

  @Test fun `fingerprint unchanged, same version, no crash since, so the probes are skipped`() {
    val rig = Rig()
    rig.load()
    val before = rig.probeRuns
    rig.clock = 50_000
    val line = rig.load()
    assertEquals("no probe ran on the second load", before, rig.probeRuns)
    assertTrue(line!!, line.startsWith("engine self-test skipped"))
    assertEquals("the skip leaves the record alone", 1_000L, rig.memory.record!!.passedAtMs)
  }

  @Test fun `a cache file that changed is probed again`() {
    val rig = Rig()
    rig.load()
    val before = rig.probeRuns
    rig.cacheFile.writeText("a different, longer cache after a torn write")
    rig.load()
    assertEquals(before + 3, rig.probeRuns)
  }

  @Test fun `same size and a new modification time is also a change`() {
    val rig = Rig()
    rig.load()
    val before = rig.probeRuns
    rig.cacheFile.setLastModified(rig.cacheFile.lastModified() + 5_000)
    rig.load()
    assertEquals(before + 3, rig.probeRuns)
  }

  @Test fun `a cache file added is probed again`() {
    val rig = Rig()
    rig.load()
    val before = rig.probeRuns
    File(rig.cacheDir, "${bundle}_1_2.vision_adapter.xnnpack_cache").writeText("new")   // a concurrent or later creation
    rig.load()
    assertEquals(before + 3, rig.probeRuns)
  }

  // This was the flaky one: the cache was deleted and rebuilt with the same name, the same size and (when the file system
  // clock had not ticked) the same modification time, so the fingerprint equalled the last pass.
  @Test fun `a cache file removed and rebuilt in the same clock tick is probed again`() {
    val rig = Rig()
    val t = 1_700_000_000_123_456_789L
    rig.pinOnBuild = t
    rig.load()   // builds the cache at time t and remembers the pass
    val before = rig.probeRuns
    rig.cacheFile.delete()   // the system cleared the cache: the load rebuilds it (same name, same size, same time t, new bytes)
    rig.load()
    assertEquals("a pass on the old cache is not reused for the rebuilt one", before + 3, rig.probeRuns)
    // and with the real clock, whatever it does
    rig.pinOnBuild = null
    val after = rig.probeRuns
    rig.cacheFile.delete()
    rig.load()
    assertEquals(after + 3, rig.probeRuns)
  }

  @Test fun `same size, same millisecond, a different nanosecond is a change`() {
    val rig = Rig()
    rig.load()
    val t = 1_700_000_000_123_000_000L
    rig.pin(rig.cacheFile, t)
    rig.load()   // records the pinned state
    val before = rig.probeRuns
    rig.pin(rig.cacheFile, t + 500_000L)   // 0.5 ms later: the same millisecond, java.io.File.lastModified() cannot tell
    val sameMs = rig.cacheFile.lastModified() == t / 1_000_000L
    org.junit.Assume.assumeTrue("this file system keeps sub-millisecond times", sameMs && Files.getLastModifiedTime(rig.cacheFile.toPath()).to(TimeUnit.NANOSECONDS) != t)
    rig.load()
    assertEquals(before + 3, rig.probeRuns)
  }

  @Test fun `same size and same modification time but other bytes at the head is a change`() {
    val rig = Rig()
    rig.load()
    val t = 1_700_000_000_000_000_000L
    rig.pin(rig.cacheFile, t)
    rig.load()
    val before = rig.probeRuns
    rig.cacheFile.writeText("cache built by load #X")   // the same length as "cache built by load #1" ...
    assertEquals(22, rig.cacheFile.length().toInt())
    rig.pin(rig.cacheFile, t)                       // ... and the same time: only the bytes differ
    rig.load()
    assertEquals(before + 3, rig.probeRuns)
  }

  @Test fun `same size and time but another tail on a large cache is a change`() {
    val rig = Rig()
    val big = ByteArray(300 * 1024) { (it % 251).toByte() }
    rig.cacheFile.writeBytes(big)
    rig.load()
    val t = 1_700_000_000_000_000_000L
    rig.pin(rig.cacheFile, t)
    rig.load()
    val before = rig.probeRuns
    val torn = big.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
    rig.cacheFile.writeBytes(torn)
    rig.pin(rig.cacheFile, t)
    rig.load()
    assertEquals(before + 3, rig.probeRuns)
  }

  @Test fun `the same bytes and time are still skipped, and only a bounded part is read`() {
    val rig = Rig()
    rig.cacheFile.writeBytes(ByteArray(400 * 1024) { it.toByte() })
    rig.load()
    val before = rig.probeRuns
    rig.load()
    assertEquals(before, rig.probeRuns)
    // a byte in the middle, outside the head and tail, is the documented blind spot (the once-per-version probe covers it)
    val fp = EngineFingerprint.of(rig.cacheDir, rig.model)
    val parts = fp.split("|")
    assertEquals(2, parts.size)
    assertEquals("name:size:nanos:hash", 4, parts[1].split(":").size)
    assertTrue(EngineFingerprint.HASH_HEAD_BYTES + EngineFingerprint.HASH_TAIL_BYTES < 400 * 1024)
  }

  @Test fun `a swapped model file is probed again`() {
    val rig = Rig()
    rig.load()
    val before = rig.probeRuns
    rig.model.writeText("a different model, different size")
    rig.load()
    assertEquals(before + 3, rig.probeRuns)
  }

  @Test fun `a crash or ANR after the last pass forces a probe`() {
    val rig = Rig()
    rig.load()
    val before = rig.probeRuns
    rig.lastCrashAt = 2_000   // after the pass at 1_000
    rig.clock = 3_000
    rig.load()
    assertEquals(before + 3, rig.probeRuns)
    assertEquals("the new pass is later than the crash", 3_000L, rig.memory.record!!.passedAtMs)
    val after = rig.probeRuns
    rig.load()
    assertEquals("and the crash no longer forces anything", after, rig.probeRuns)
  }

  @Test fun `a crash before the last pass does not force a probe`() {
    val rig = Rig(lastCrashAt = 500)
    rig.load()
    val before = rig.probeRuns
    rig.load()
    assertEquals(before, rig.probeRuns)
  }

  @Test fun `a new app version probes once`() {
    val rig = Rig()
    rig.load()
    rig.appVersion = 11
    val before = rig.probeRuns
    rig.load()
    assertEquals(before + 3, rig.probeRuns)
    rig.load()
    assertEquals("only once per version", before + 3, rig.probeRuns)
  }

  @Test fun `a failed probe forgets what had passed`() {
    val rig = Rig()
    rig.load()
    rig.cacheFile.writeText("damaged")
    try { rig.load("GARBAGE") } catch (_: EngineUnhealthyException) { }
    assertNull(rig.memory.record)
    assertFalse("the failed attempt left no cache for the next load to trust", rig.cacheFile.exists())
  }

  @Test fun `a repair is remembered as a pass on the rebuilt cache`() {
    val rig = Rig()
    rig.load()
    rig.cacheFile.writeText("damaged")
    // the engine is bad while the damaged file is there and good after the clear: emulate with a mode keyed on the file
    val schedule = ProbeSchedule(rig.memory, rig.cacheDir, rig.model, 10, { 0 }, { 9_000 })
    var repaired = false
    val test = EngineSelfTest<String>(
      probe, clearCache = { EngineCache.clear(rig.cacheDir, bundle) }, log = {}, onRepaired = { repaired = true },
      skipReason = schedule::skipReason, onPassed = schedule::passed, onFailed = schedule::failed,
    )
    test.loadVerified(
      bundle,
      load = { if (rig.cacheFile.exists()) "BAD" else { rig.cacheFile.writeText("fresh"); "OK" } },
      embed = { mode, text -> if (mode == "OK") golden.first { it.text == text }.vector else FloatArray(768) { Float.NaN } },
      close = {},
    )
    assertTrue(repaired)
    assertEquals(9_000L, rig.memory.record!!.passedAtMs)
    assertTrue(rig.memory.record!!.fingerprint.contains(rig.cacheFile.name))
  }

  @Test fun `no cache files yet means probe, whatever was remembered`() {
    val rig = Rig()
    rig.memory.record = ProbeRecord("", 10, 1)
    assertNull(ProbeGate.skipReason(rig.memory.record, "", 10) { 0 })
    assertNull(ProbeGate.skipReason(null, "a:1:2", 10) { 0 })
  }

  @Test fun `the crash check is only asked when everything else matches`() {
    var asked = 0
    val rec = ProbeRecord("a:1:2", 10, 100)
    assertNull(ProbeGate.skipReason(rec, "b:1:2", 10) { asked++; 0 })
    assertNull(ProbeGate.skipReason(rec, "a:1:2", 11) { asked++; 0 })
    assertEquals("the exit history is read only when a skip is possible", 0, asked)
    assertNotNull(ProbeGate.skipReason(rec, "a:1:2", 10) { asked++; 0 })
    assertEquals(1, asked)
  }

  @Test fun `the fingerprint holds only our own file names and numbers`() {
    val rig = Rig()
    rig.cacheFile.writeText("x")
    File(rig.cacheDir, "other-model.litertlm_1_2.text_encoder.xnnpack_cache").writeText("another model's")
    File(rig.cacheDir, "scratch.tmp").writeText("unrelated")
    val fp = EngineFingerprint.of(rig.cacheDir, rig.model)
    assertTrue(fp.contains(bundle))
    assertFalse(fp.contains("other-model"))
    assertFalse(fp.contains("scratch"))
    assertEquals("", EngineFingerprint.of(tmp.newFolder(), rig.model))
  }

  @Test fun `the embedder wires the schedule into the self-test and reads crashes from the exit history`() {
    val src = File("src/full/java/com/craneai/findr/engine/LiteRtEmbedder.kt").readText()
    assertTrue(src.contains("ProbeSchedule("))
    assertTrue(src.contains("skipReason = schedule::skipReason"))
    assertTrue(src.contains("onPassed = schedule::passed"))
    assertTrue(src.contains("onFailed = schedule::failed"))
    assertTrue(src.contains("REASON_CRASH_NATIVE") && src.contains("REASON_ANR"))
  }
}
