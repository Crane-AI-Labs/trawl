package trawl.desktop

import com.craneai.findr.core.Refusal
import com.craneai.findr.engine.BundleResult
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The signature check itself is the phone's (SignedBundleTest in :app); here we only check the desktop wiring refuses what it must. */
class ModelSetupTest {
  private fun tmp() = Files.createTempDirectory("trawl-models").toFile()

  private fun zip(file: File, vararg entries: Pair<String, String>) {
    ZipOutputStream(file.outputStream()).use { z -> for ((n, c) in entries) { z.putNextEntry(ZipEntry(n)); z.write(c.toByteArray()); z.closeEntry() } }
  }

  @Test fun refusesAZipThatIsNotSigned() {
    val d = tmp()
    val z = File(d, "trawl-models.zip"); zip(z, "model.litertlm" to "not a model")
    val r = ModelSetup(File(d, "data"), 16_000).install(z)
    assertTrue(r is BundleResult.Refused && r.reason == Refusal.NOT_SIGNED, "got $r")
    assertNull(ModelSetup(File(d, "data"), 16_000).installedModel())
    d.deleteRecursively()
  }

  @Test fun refusesAForgedSignature() {
    val d = tmp()
    val z = File(d, "trawl-models.zip")
    zip(z, "manifest.json" to "{\"bundle\":\"x\"}", "manifest.sig" to "AAAA")
    val r = ModelSetup(File(d, "data"), 16_000).install(z)
    assertTrue(r is BundleResult.Refused && r.reason in setOf(Refusal.BAD_SIGNATURE, Refusal.NOT_SIGNED), "got $r")
    d.deleteRecursively()
  }

  @Test fun refusesATruncatedFile() {
    val d = tmp()
    val z = File(d, "trawl-models.zip"); z.writeBytes(byteArrayOf(1, 2, 3))
    val r = ModelSetup(File(d, "data"), 16_000).install(z)
    assertTrue(r is BundleResult.Refused)
    d.deleteRecursively()
  }

  @Test fun findsTheNewestBundleInDownloads() {
    val d = tmp()
    File(d, "trawl-models.zip").apply { writeText("a"); setLastModified(1000) }
    val b = File(d, "trawl-models (1).zip").apply { writeText("b"); setLastModified(5000) }
    File(d, "trawl-models.zip.part").writeText("half")
    File(d, "other.zip").writeText("x")
    val found = ModelSetup(File(d, "data"), 16_000).findBundle(listOf(d, File(d, "nope")))
    assertNotNull(found); assertEquals(b.name, found.name)
    d.deleteRecursively()
  }
}
