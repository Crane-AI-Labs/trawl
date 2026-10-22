package trawl.desktop

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The problem report: whatever the log held, nothing a person named can reach the clipboard. */
class DiagnosticsTest {
  private val home = "/home/amina"
  private val winHome = "C:\\Users\\Amina Nakato"

  private val facts = Diagnostics.Facts(
    os = "Linux", osVersion = "6.8", arch = "amd64", cores = 8, ramMb = 31_000, ocr = "Tesseract", vision = "cpu", textThreads = null,
    speed = "balanced", language = "en", folders = 2, skippedFolders = 1, photosOn = true, screenshotsOn = false, documentsOn = true,
    photos = 10, screenshots = 3, documents = 7, unreadable = 1, indexBytes = 5_000_000, modelBytes = 700_000_000, ratePerMin = 42.4,
    models = "EmbeddingGemma 2 (text and pictures)", errors = emptyList(),
  )

  @Test fun theHomeFolderAndEverythingAfterItBecomesATilde() {
    assertEquals("opened ~", Diagnostics.redact("opened /home/amina/Documents/Tax 2025/my secret report.pdf", home))
    assertEquals("opened ~", Diagnostics.redact("opened C:\\Users\\Amina Nakato\\Desktop\\bank letter.docx", winHome))
    assertEquals("opened ~", Diagnostics.redact("opened C:/Users/Amina Nakato/Desktop/bank letter.docx", winHome), "the other slash")
    assertEquals("opened ~", Diagnostics.redact("opened /HOME/AMINA/x y z", home), "case does not matter")
  }

  @Test fun pathsOutsideTheHomeFolderAreCutToo() {
    val out = Diagnostics.redact("read /mnt/usb/Family/photos/grandma.jpg now", home)
    assertFalse("grandma" in out || "Family" in out, out)
    val win = Diagnostics.redact("read D:\\Backup\\Clients\\Acme\\contract.pdf now", home)
    assertFalse("Acme" in win || "contract" in win, win)
    val uri = Diagnostics.redact("read file:///mnt/usb/Family/grandma.jpg now", home)
    assertFalse("grandma" in uri, uri)
  }

  @Test fun anErrorLineKeepsTheCodesWordsAndTheClassNeverTheMessage() {
    val raw = "failed a document: java.io.FileNotFoundException /home/amina/Documents/my secret report.pdf (Permission denied)"
    val safe = Diagnostics.safeLine(raw, home)
    assertEquals("failed a document: FileNotFoundException (Permission denied)", safe)
    assertTrue(Diagnostics.isError(safe))
  }

  @Test fun aFileNameInsideAnErrorMessageNeverSurvives() {
    val raw = "index worker: IllegalStateException my secret report.pdf is broken at /srv/clients/Acme/contract.pdf"
    val safe = Diagnostics.safeLine(raw, home)
    for (bad in listOf("secret", "report", "Acme", "contract", "broken", "srv")) assertFalse(bad in safe, "'$bad' in: $safe")
    assertTrue("IllegalStateException" in safe, safe)
  }

  @Test fun aClassNameThatIsNotInTheJdkOrKotlinIsNotPrinted() {
    // A person can name a file "ReportException" or "Acme_Error"; only classes that really exist in the platform are shown.
    val safe = Diagnostics.safeLine("failed a photo: AcmeSecretProjectException", home)
    assertFalse("Acme" in safe, safe)
    assertTrue("<error>" in safe, safe)
  }

  @Test fun anOrdinaryLineIsKeptButItsPathsAreNot() {
    val safe = Diagnostics.safeLine("model embeddinggemma.litertlm ready, init 900 ms, vision cpu, ocr none", home)
    assertTrue("ready" in safe && "900 ms" in safe, safe)
    val folder = Diagnostics.safeLine("watching /home/amina/Pictures/Uganda trip 2025/Kampala", home)
    assertEquals("watching ~", folder)
  }

  @Test fun theLogFileHoldsOnlySafeLines() {
    val dir = Files.createTempDirectory("trawl-log").toFile()
    val seen = ArrayList<String>()
    val log = AppLog(dir, home, sink = { seen += it })
    log.log("failed a pdf: java.io.IOException /home/amina/Documents/my secret report.pdf")
    log.log("scan /home/amina/Documents/Tax 2025")
    val text = File(dir, "trawl.log").readText()
    for (bad in listOf("secret", "report", "Tax", "amina", "Documents")) {
      assertFalse(bad in text, "'$bad' reached the log file: $text")
      assertFalse(seen.any { bad in it }, "'$bad' reached the sink: $seen")
    }
    assertEquals(listOf("failed a pdf: IOException"), log.recentErrors())
    dir.deleteRecursively()
  }

  @Test fun theLogIsRotatedSoItStaysSmall() {
    val dir = Files.createTempDirectory("trawl-log").toFile()
    val log = AppLog(dir, home, sink = {}, maxBytes = 2_000)
    repeat(200) { log.log("worker line number $it with some filler text to make it long enough to count") }
    assertNotNull(File(dir, "trawl.log.1").takeIf { it.exists() }, "no rotated file")
    assertTrue(File(dir, "trawl.log").length() < 4_000)
    dir.deleteRecursively()
  }

  @Test fun theReportListsCountsAndVersionsAndNoNames() {
    val errors = listOf("failed a pdf: IOException", "failed a photo: <error>")
    val text = Diagnostics.report(facts.copy(errors = errors))
    for (want in listOf("Version: ${AppInfo.VERSION}", "Linux 6.8", "8 cores", "Files known: 10 photos, 3 screenshots, 7 documents", "screenshots off", "42 files a minute", "failed a pdf: IOException")) {
      assertTrue(want in text, "'$want' missing from:\n$text")
    }
    assertTrue(text.lines().size < 30, "short plain text")
    assertFalse(Regex("""[/\\][A-Za-z]""").containsMatchIn(text.replace("No file names, paths or file contents", "")), "a path shape is in:\n$text")
  }

  @Test fun aReportBuiltFromHostileLogLinesHoldsNoneOfTheirNames() {
    val dir = Files.createTempDirectory("trawl-log").toFile()
    val log = AppLog(dir, home, sink = {})
    log.log("failed a document: java.io.FileNotFoundException /home/amina/Documents/Tax 2025/my secret report.pdf (No such file)")
    log.log("failed a photo: java.nio.file.AccessDeniedException C:\\Users\\Amina Nakato\\Pictures\\wedding.jpg")
    log.log("failed a pdf: SecretClientException")
    val text = Diagnostics.report(facts.copy(errors = log.recentErrors()))
    for (bad in listOf("secret", "Tax", "amina", "Amina", "Nakato", "wedding", "Pictures", "Documents", "SecretClient")) assertFalse(bad in text, "'$bad' in:\n$text")
    assertTrue("AccessDeniedException" in text && "FileNotFoundException" in text, text)
    dir.deleteRecursively()
  }

  @Test fun memoryIsFormattedInGbAndUnknownIsNeverZero() {
    assertEquals("8 cores, 31.0 GB memory", Diagnostics.machine(8, 31_744))
    assertEquals("4 cores, 7.8 GB memory", Diagnostics.machine(4, 8_000))
    assertEquals("1 core, 0.5 GB memory", Diagnostics.machine(1, 512))
    for (unknown in listOf(0L, -1L)) {
      val s = Diagnostics.machine(4, unknown)
      assertEquals("4 cores, memory unknown", s)
      assertFalse("0.0" in s)
    }
    val text = Diagnostics.report(facts.copy(ramMb = 0))
    assertTrue("Processor: 8 cores, memory unknown" in text && "0.0 GB" !in text, text)
  }

  @Test fun theReportSaysWhatReadsVideosAndSoundAndCountsThemWithoutNames() {
    val text = Diagnostics.report(facts.copy(videos = 1, recordings = 4, decoder = "ffmpeg", speechPack = true, videosOn = true, audioOn = false))
    assertTrue("videos on, recordings off" in text, text)
    assertTrue("1 video, 4 recordings; decoder ffmpeg, speech pack installed" in text, text)
    val none = Diagnostics.report(facts.copy(decoder = "none", speechPack = false))
    assertTrue("decoder none, speech pack not installed" in none, none)
    assertTrue("decoder not checked yet" in Diagnostics.report(facts.copy(decoder = null)))
  }

  @Test fun theReportUsesSingularForOne() {
    val text = Diagnostics.report(facts.copy(photos = 1, screenshots = 1, documents = 1))
    assertTrue("Files known: 1 photo, 1 screenshot, 1 document or PDF, 1 unreadable" in text, text)
    val zero = Diagnostics.report(facts.copy(photos = 0, screenshots = 0, documents = 0))
    assertTrue("Files known: 0 photos, 0 screenshots, 0 documents and PDFs" in zero, zero)
  }

  /** The deletion guard ("the walk could not read a folder, so nothing is deleted") is a safe event, not a failure. */
  @Test fun aWalkCutShortIsLoggedButIsNotAnError() {
    val line = BlockedSummary(system = 2, other = 1, where = mapOf(Place.DOCUMENTS to 2)).logLine()
    assertFalse(Diagnostics.isError(Diagnostics.safeLine(line, home)), line)
    assertFalse(Diagnostics.isError(Diagnostics.safeLine("walk was cut short; nothing deleted", home)))
    val dir = Files.createTempDirectory("trawl-log").toFile()
    val log = AppLog(dir, home, sink = {})
    log.log(line)
    log.log("failed a pdf: java.io.IOException")
    assertTrue(line in File(dir, "trawl.log").readText(), "it stays in the log file for support")
    assertEquals(listOf("failed a pdf: IOException"), log.recentErrors())
    assertTrue(line !in Diagnostics.report(facts.copy(errors = log.recentErrors())))
    dir.deleteRecursively()
  }

  @Test fun theVersionInTheAppMatchesTheBuild() {
    val gradle = File("build.gradle.kts").readText()
    val v = Regex("""packageVersion\s*=\s*"([^"]+)"""").find(gradle)?.groupValues?.get(1)
    assertEquals(v, AppInfo.VERSION, "AppInfo.VERSION and packageVersion in build.gradle.kts must be changed together")
  }
}
