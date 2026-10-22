package trawl.desktop

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The Linux text reader is found by looking along PATH, never by running `tesseract --version` (that call makes a DNS lookup; see D43). */
class OcrsTest {
  private val here = setOf(File("/usr/bin/tesseract"))

  @Test fun foundWhenAPathFolderHoldsIt() = assertTrue(Ocrs.tesseractPresent("/opt/x:/usr/bin", isFile = { it in here }))

  @Test fun notFoundWhenNoPathFolderHoldsIt() = assertFalse(Ocrs.tesseractPresent("/opt/x:/usr/local/bin", isFile = { it in here }))

  @Test fun notFoundWithoutAPath() = assertFalse(Ocrs.tesseractPresent(null, isFile = { true }))

  @Test fun aDirectoryOrNonExecutableFileDoesNotCount() {
    val dir = kotlin.io.path.createTempDirectory("ocrs").toFile()
    try {
      val f = File(dir, "tesseract").apply { writeText("x"); setExecutable(false) }
      assertFalse(Ocrs.tesseractPresent(dir.path))
      f.setExecutable(true)
      assertTrue(Ocrs.tesseractPresent(dir.path))
      f.delete(); File(dir, "tesseract").mkdir()
      assertFalse(Ocrs.tesseractPresent(dir.path))
    } finally { dir.deleteRecursively() }
  }
}
