package trawl.desktop

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.craneai.findr.diag.LogRing
import com.craneai.findr.diag.ReportStore
import java.io.File
import java.net.URLDecoder
import java.nio.file.Files
import java.util.Locale
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import trawl.desktop.ui.SunlightTheme
import trawl.desktop.ui.report.ReportDialog

/** F10: "Open email" copies the whole report, then opens the person's own mail app with a short draft. Nothing is sent by Trawl. */
@OptIn(ExperimentalTestApi::class)
class ReportMailTest {
  @BeforeTest fun english() = Strings.use(Locale.ENGLISH)

  private val summary = "Trawl 1.0.2 on Linux 6.8 (amd64).\nNo crash or freeze recorded."

  private fun decoded(uri: String, key: String): String =
    URLDecoder.decode(uri.substringAfter("$key=").substringBefore("&"), "UTF-8")

  @Test fun theLinkGoesToSupportWithTheVersionInTheSubjectAndTheSummaryInTheBody() {
    val uri = ProblemReports.mailUri(summary)
    assertTrue(uri.startsWith("mailto:${ProblemReports.EMAIL}?"), uri)
    assertEquals("Trawl problem report (1.0.2)", decoded(uri, "subject"))
    val body = decoded(uri, "body")
    assertTrue(body.startsWith("The full report is on my clipboard."), body)
    assertTrue(summary in body, body)
  }

  @Test fun aSummaryThatDoesNotParseStillGetsASubject() {
    assertEquals("Trawl problem report", ProblemReports.mailSubject("something else"))
  }

  @Test fun aLongSummaryIsCutToWhatTheWeakestMailAppTakes() {
    val long = "Trawl 1.0.2 on Linux 6.8 (amd64).\n" + (1..200).joinToString("\n") { "line number $it of a long summary" }
    val body = decoded(ProblemReports.mailUri(long), "body")
    assertTrue(body.length <= com.craneai.findr.diag.SendPlans.MAIL_BODY_MAX, "${body.length}")
    assertFalse("[cut: the attached report" in body, "this report is pasted, not attached")
  }

  @Test fun theOpenCommandForALinkIsTheSystemsOwnOnPerOs() {
    assertEquals(listOf("open", "mailto:a@b"), Platform.openCommand("mailto:a@b", Os.MAC))
    assertEquals(listOf("xdg-open", "mailto:a@b"), Platform.openCommand("mailto:a@b", Os.LINUX))
    assertEquals(listOf("rundll32.exe", "url.dll,FileProtocolHandler", "mailto:a@b"), Platform.openCommand("mailto:a@b", Os.WINDOWS))
  }

  private fun reporter(): Reporter {
    val dir = Files.createTempDirectory("f10").toFile()
    val facts = Diagnostics.Facts(
      os = "Linux", osVersion = "6.8", arch = "amd64", cores = 8, ramMb = 31_000, ocr = "Tesseract", vision = "cpu", textThreads = null,
      speed = "balanced", language = "en", folders = 2, skippedFolders = 1, photosOn = true, screenshotsOn = false, documentsOn = true,
      photos = 10, screenshots = 3, documents = 7, unreadable = 1, indexBytes = 5_000_000, modelBytes = 700_000_000, ratePerMin = 42.4,
      models = "EmbeddingGemma 2", errors = emptyList(),
    )
    return Reporter(ReportStore(File(dir, "reports")), LogRing(), { facts })
  }

  private fun run(copyOk: Boolean, openOk: Boolean, expect: String) = runComposeUiTest {
    val copied = mutableListOf<String>(); val opened = mutableListOf<String>()
    val rep = reporter()
    setContent {
      SunlightTheme { ReportDialog(rep, onClose = {}, build = { rep.build(it) }, openMail = { opened += it; openOk }, copy = { copied += it; copyOk }) }
    }
    // The report is built on the IO pool and handed back to the test's own clock, so the clock is nudged while we wait.
    val end = System.currentTimeMillis() + 15_000
    while (System.currentTimeMillis() < end && !runCatching { onNodeWithText("Open email").assertIsEnabled(); true }.getOrDefault(false)) {
      Thread.sleep(30); mainClock.advanceTimeByFrame()
    }
    onNodeWithText("Open email").assertIsEnabled()
    onNodeWithText("Open email").performClick()
    val end2 = System.currentTimeMillis() + 10_000
    while (System.currentTimeMillis() < end2 && opened.isEmpty()) { Thread.sleep(30); mainClock.advanceTimeByFrame() }
    waitForIdle()
    assertEquals(1, copied.size, "the whole report is copied first")
    assertTrue(copied[0].startsWith("Trawl problem report"), copied[0].take(60))
    assertTrue(opened[0].startsWith("mailto:${ProblemReports.EMAIL}?subject="), opened[0])
    onNodeWithText(expect, substring = true).assertExists()
  }

  @Test fun theButtonCopiesThenOpensAndSaysToPaste() =
    run(copyOk = true, openOk = true, expect = "Paste the report into the email, then send it yourself")

  @Test fun noMailAppSaysSoAndNamesTheAddress() =
    run(copyOk = true, openOk = false, expect = "could not open an email app")

  @Test fun noClipboardIsSaidOutRightNotHidden() =
    run(copyOk = false, openOk = true, expect = "would not give Trawl its clipboard")
}
