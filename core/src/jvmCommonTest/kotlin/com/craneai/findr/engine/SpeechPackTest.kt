package com.craneai.findr.engine

import com.craneai.findr.core.WordTimes
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A speech pack's pack.json (SpeechPack): what is read from it, never hard-coded, and what makes a pack refused. */
class SpeechPackTest {
  /** The Luganda student pack's pack.json as built on Leonardo (/mnt/data/findr-lugasr/packs/lg-student/pack.json). */
  private val lg = """
    {
     "pack_id": "lg-student",
     "pack_version": 2026112201,
     "language": "lg",
     "language_name": "Luganda",
     "source_run": "S1",
     "subsampling_factor": 8,
     "frame_ms": 80,
     "sample_rate": 16000,
     "licence": "CC-BY-SA-4.0",
     "files": {
      "model.int8.onnx": {"bytes": 131387952, "sha256": "cb7299ae30fa9c0ca36b3f607ed85cc3e543512fa8e132e72f635bf4ff5ca464"},
      "tokens.txt": {"bytes": 4537, "sha256": "a5cd0c3625be3cfc0ac53e8ae21c28e2b0492a44766b4db0787ff017ce4a693a"}
     },
     "transcript_stamp": "lg-student@2026112201"
    }
  """.trimIndent()

  @Test fun theStudentPackIsReadFromItsOwnFile() {
    val p = SpeechPack.parse(lg)
    assertEquals("lg-student", p.id)
    assertEquals(2026112201L, p.version)
    assertEquals("lg-student@2026112201", p.stamp)
    assertEquals(80L, p.frameMs)
    assertEquals("no late_bias_ms: Parakeet's, the same encoder graph", WordTimes.LATE_BIAS_MS, p.lateBiasMs)
    assertEquals("Luganda", p.languageName)
    assertEquals("model.int8.onnx", p.model)
    assertEquals("tokens.txt", p.tokens)
  }

  @Test fun theStampIsWhatThePackBuilderWroteToo() {
    assertEquals(org.json.JSONObject(lg).getString("transcript_stamp"), SpeechPack.parse(lg).stamp)
  }

  @Test fun anotherFrameAndLatenessAreTakenFromTheFile() {
    // The October yonas model: a 40 ms grid, 170 ms late. Nothing in the code assumes 80 ms.
    val yonas = SpeechPack.parse(lg.replace("\"frame_ms\": 80,", "\"frame_ms\": 40, \"late_bias_ms\": 170,").replace("lg-student", "yonas"))
    assertEquals(40L, yonas.frameMs)
    assertEquals(170L, yonas.lateBiasMs)
    assertEquals("yonas@2026112201", yonas.stamp)
  }

  @Test fun aPackThatWouldBeReadWronglyIsRefused() {
    for (bad in listOf(
      lg.replace("\"frame_ms\": 80,", ""),
      lg.replace("\"frame_ms\": 80,", "\"frame_ms\": 0,"),
      lg.replace("\"pack_version\": 2026112201,", ""),
      lg.replace("\"lg-student\"", "\"lg student\""),
      lg.replace("\"lg-student\"", "\"lg'--\""),
      lg.replace("\"sample_rate\": 16000,", "\"sample_rate\": 8000,"),
      lg.replace("\"tokens.txt\": {", "\"tokens.bin\": {"),
    )) {
      val r = runCatching { SpeechPack.parse(bad) }
      assertTrue("accepted: $bad", r.isFailure)
    }
  }

  @Test fun readNeedsThePackJsonAndBothModelFiles() {
    val dir = Files.createTempDirectory("speechpack").toFile()
    try {
      assertNull(SpeechPack.read(dir))
      java.io.File(dir, SpeechPack.FILE).writeText(lg)
      assertNull("no model yet (still copying)", SpeechPack.read(dir))
      java.io.File(dir, "model.int8.onnx").writeText("x")
      java.io.File(dir, "tokens.txt").writeText("x")
      assertNotNull(SpeechPack.read(dir))
      java.io.File(dir, SpeechPack.FILE).writeText("{")
      assertNull("unreadable pack.json", SpeechPack.read(dir))
    } finally {
      dir.deleteRecursively()
    }
  }

  @Test fun everyPinnedPackListsItsPackJsonModelTokensAndNotice() {
    for (e in SpeechPacks.all) {
      val names = e.files.map { it.path }
      for (f in listOf(SpeechPack.FILE, "model.int8.onnx", "tokens.txt", "NOTICE.md", "MODEL_CARD.md")) assertTrue("${e.id} lacks $f", f in names)
      assertTrue("${e.id}: about 131 MB", e.sizeBytes in 125_000_000L..140_000_000L)
      assertTrue(e.files.all { it.sha256.length == 64 })
    }
    assertEquals(com.craneai.findr.core.PackIds.SPEECH_PACKS, SpeechPacks.all.map { it.id })
  }
}
