package trawl.desktop

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GpuProbeTest {
  @Test fun theAnswerIsKeptAndReusedUntilTheModelChanges() {
    val d = Files.createTempDirectory("trawl-probe").toFile()
    val model = File(d, "m.litertlm").apply { writeText("model") }
    val answer = File(d, "vision-backend.txt")
    var runs = 0
    val crash = { _: File -> runs++; false }
    val works = { _: File -> runs++; true }
    assertFalse(GpuProbe.usable(model, answer, crash)); assertEquals(1, runs)
    assertFalse(GpuProbe.usable(model, answer, works)); assertEquals(1, runs)   // remembered: not tried again
    model.writeText("a different, longer model file")
    assertTrue(GpuProbe.usable(model, answer, works)); assertEquals(2, runs)   // a new model file is probed again
    d.deleteRecursively()
  }
}
