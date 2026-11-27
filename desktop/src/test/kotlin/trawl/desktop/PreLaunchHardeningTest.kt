package trawl.desktop

import com.craneai.findr.core.FormatNotReadable
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.common.PDRectangle
import trawl.desktop.privacy.PrivacySettings
import trawl.desktop.privacy.PrivacyStore
import trawl.desktop.shell.ShellPrefs
import trawl.desktop.shell.ShellPrefsStore

class PreLaunchHardeningTest {
  private val dirs = ArrayList<File>()
  private fun tmpDir(prefix: String): File = Files.createTempDirectory(prefix).toFile().also { dirs += it }

  @AfterTest fun clean() {
    dirs.forEach { it.deleteRecursively() }
    dirs.clear()
  }

  @Test fun ownerOnlyPermissionsHardensDataDirAndConfigStoresTo0700And0600() {
    if (Platform.os == Os.WINDOWS) return
    val data = tmpDir("trawl-perms")
    val indexDir = File(data, "index").apply { mkdirs() }
    val logsDir = File(data, "logs").apply { mkdirs() }
    val db = File(indexDir, "findr-index.db").apply { writeText("sqlite") }
    val wal = File(indexDir, "findr-index.db-wal").apply { writeText("wal") }
    val vec = File(indexDir, "vectors.bin").apply { writeText("vec") }
    val log = File(logsDir, "trawl.log").apply { writeText("log") }

    // Simulate world-readable legacy directories/files (0755 / 0644)
    Files.setPosixFilePermissions(data.toPath(), PosixFilePermissions.fromString("rwxr-xr-x"))
    Files.setPosixFilePermissions(indexDir.toPath(), PosixFilePermissions.fromString("rwxr-xr-x"))
    Files.setPosixFilePermissions(db.toPath(), PosixFilePermissions.fromString("rw-r--r--"))
    Files.setPosixFilePermissions(vec.toPath(), PosixFilePermissions.fromString("rw-r--r--"))

    OwnerOnlyPermissions.hardenDataDir(data)
    SettingsStore(data).save(Settings())
    PrivacyStore(data).save(PrivacySettings())
    ShellPrefsStore(data).save(ShellPrefs())

    assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(data.toPath())))
    assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(indexDir.toPath())))
    assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(logsDir.toPath())))
    for (f in listOf(db, wal, vec, log, File(data, "settings.json"), File(data, "privacy.json"), File(data, "shell.json"))) {
      assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(f.toPath())), "Expected 0600 on ${f.name}")
    }
  }

  @Test fun pdfThumbsClampsBlueprintHugeMediaBoxWithoutOom() {
    val dir = tmpDir("trawl-blueprint-pdf")
    val huge = File(dir, "blueprint.pdf")
    PDDocument().use { doc ->
      // 20,000 x 20,000 pt page (~7m x 7m blueprint) would allocate >2 GB at raw 96 DPI without cropBox clamping!
      doc.addPage(PDPage(PDRectangle(20_000f, 20_000f)))
      doc.save(huge)
    }
    PdfThumbs.clearCache()
    val bmp = PdfThumbs.load(huge, page = 1, side = 512, modifiedMillis = huge.lastModified())
    assertNotNull(bmp)
    assertTrue(bmp.width <= 512 && bmp.height <= 512, "Clamped blueprint render must fit <=512px, got ${bmp.width}x${bmp.height}")
  }

  @Test fun docFormatsPagesHandlesDamagedPdfCleanlyWithoutThrowing() {
    val dir = tmpDir("trawl-damaged-pdf")
    val bad = File(dir, "broken.pdf").apply { writeText("%PDF-1.7\ngarbage-not-a-valid-xref") }
    val pages = DocFormats.pages(bad, namedPdf = true)
    assertTrue(pages.pdf)
    assertTrue(pages.chunks.isEmpty())
    assertEquals(DocFormats.Why.DAMAGED.detail, pages.failure)
  }

  @Test fun linuxShowCommandEscapesCommasInFileUris() {
    val cmd = Platform.showCommand("/tmp/report,2026.pdf", Os.LINUX)
    val arg = cmd.first { it.startsWith("array:string:") }
    assertTrue("%2C" in arg, "Comma in Linux dbus-send URI must be percent-encoded: $arg")
    assertTrue(!arg.substringAfter("array:string:").contains(','), "Unescaped comma breaks dbus-send array:string: $arg")
  }

  @Test fun helperDecoderRejectsDelimiterInjectionPathsAndCapsReadLine() {
    val dec = HelperDecoder(listOf("/bin/true"), "test")
    assertFailsWith<FormatNotReadable> { dec.probe(File("/tmp/bad\nquit.mp4")) }
    assertFailsWith<FormatNotReadable> { dec.probe(File("/tmp/bad\tpath.mp4")) }
    dec.close()

    val giant = ByteArray(70_000) { 'a'.code.toByte() }
    assertFailsWith<IOException> { HelperDecoder.readLine(ByteArrayInputStream(giant)) }
  }
}
