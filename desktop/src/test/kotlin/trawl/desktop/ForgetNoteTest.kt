package trawl.desktop

import java.io.File
import java.nio.file.Files
import java.util.Locale
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** E21: after "Forget everything" the page says how much was let go, measured before the drop. */
class ForgetNoteTest {
  @BeforeTest fun english() = Strings.use(Locale.ENGLISH)

  @Test fun theLineSaysHowMuchAndHowManyFilesAndThatNothingOfTheirsWasTouched() {
    val line = ForgetNote.line(Forgot(files = 3210, bytes = 13_000_000))
    assertTrue(line.startsWith("Forgot "), line)
    assertTrue("3210 files" in line || "3,210 files" in line, line)
    assertTrue("MB" in line, line)
    assertTrue("Your files were not touched" in line, line)
  }

  @Test fun oneFileIsSingular() { val l = ForgetNote.line(Forgot(1, 4_000)); assertTrue("about 1 file." in l && "1 files" !in l, l) }

  @Test fun anEmptyIndexSaysThereWasNothingToForget() {
    val line = ForgetNote.line(Forgot(0, 0))
    assertTrue("nothing" in line.lowercase(), line)
  }

  @Test fun theControllerKeepsTheMeasurementForTheNote() {
    val dir = Files.createTempDirectory("trawl-e21").toFile()
    val c = AppController(dir, Platform.UserFolders(File(dir, "h"), File(dir, "d"), File(dir, "dl")), logSink = {}, scope = CoroutineScope(SupervisorJob() + Dispatchers.Default))
    try {
      assertNull(c.forgot.value, "nothing is claimed before anything was forgotten")
      c.forgetEverything()
      val f = assertNotNull(c.forgot.value)
      assertEquals(0, f.files, "a fresh data folder had learned nothing")
    } finally { c.close() }
  }
}
