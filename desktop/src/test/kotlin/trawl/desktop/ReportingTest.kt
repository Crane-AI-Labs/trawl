package trawl.desktop

import com.craneai.findr.diag.EventKind
import com.craneai.findr.diag.HandledKind
import com.craneai.findr.diag.HandledStat
import com.craneai.findr.diag.LogRing
import com.craneai.findr.diag.Redaction
import com.craneai.findr.diag.ReportStore
import com.craneai.findr.diag.StoredEvent
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** "Report a problem": one redacted report, nothing sent, no names and no paths in it. */
class ReportingTest {
  private val facts = Diagnostics.Facts(
    os = "Linux", osVersion = "6.8", arch = "amd64", cores = 8, ramMb = 31_000, ocr = "Tesseract", vision = "cpu", textThreads = null,
    speed = "balanced", language = "en", folders = 2, skippedFolders = 1, photosOn = true, screenshotsOn = false, documentsOn = true,
    photos = 10, screenshots = 3, documents = 7, unreadable = 1, indexBytes = 5_000_000, modelBytes = 700_000_000, ratePerMin = 42.4,
    models = "EmbeddingGemma 2 (text and pictures)", errors = emptyList(),
  )
  private val t0 = 1_760_000_000_000L

  private fun build(
    events: List<StoredEvent> = emptyList(),
    handled: Map<HandledKind, HandledStat> = emptyMap(),
    log: List<String> = emptyList(),
    note: String? = null,
    flags: List<String> = listOf("app lock: on", "blur: on", "marked files: 3"),
  ) = ProblemReports.build(t0, facts, flags, events, handled, log, note)

  @Test fun `a quiet report holds the facts, the privacy settings as words, and says nothing was sent`() {
    val r = build()
    assertTrue("Trawl problem report" in r.text)
    assertTrue("Nothing was sent anywhere" in r.text)
    assertTrue("Version:" in r.text && "System: Linux 6.8 (amd64)" in r.text)
    assertTrue("Privacy: app lock: on, blur: on, marked files: 3" in r.text)
    assertTrue("No crash or freeze recorded." in r.summary)
    assertEquals(0, r.events)
    assertFalse("Trawl diagnostics" in r.text, "the old title is not repeated")
  }

  @Test fun `a crash whose message names a file gives a report without the name or the path`() {
    val crash = RuntimeException("cannot read /home/amina/Documents/Tax 2025/secret-letter.pdf", IllegalStateException("C:\\Users\\Amina\\Desktop\\bank.docx"))
    val body = Redaction.trace(crash)
    val r = build(events = listOf(StoredEvent(t0, EventKind.CRASH, 1, body)))
    for (leak in listOf("amina", "Amina", "secret-letter", "Tax 2025", "bank.docx", "/home/", "C:\\Users")) {
      assertFalse(leak in r.text, "'$leak' leaked into:\n${r.text}")
      assertFalse(leak in r.summary, "'$leak' leaked into the summary")
    }
    assertTrue("RuntimeException" in r.text, "the class name is the useful part and stays")
    assertEquals(1, r.events)
  }

  @Test fun `log lines are scrubbed again on the way in`() {
    val r = build(log = listOf("12:00:00.000 W/Crawl: read /mnt/usb/Family/grandma.jpg and file:///mnt/usb/x/y.png and D:\\Backup\\Clients\\a.pdf"))
    for (leak in listOf("grandma", "Family", "Clients", "y.png")) assertFalse(leak in r.text, "'$leak' leaked")
  }

  @Test fun `a note is kept as written but paths are removed and it is capped`() {
    val r = build(note = "It froze when I opened /home/amina/Pictures/holiday/beach.jpg, then Trawl closed.")
    assertTrue("It froze when I opened" in r.text && "then Trawl closed." in r.text)
    assertFalse("beach.jpg" in r.text || "amina" in r.text)
    val long = build(note = "x".repeat(ProblemReports.NOTE_MAX * 3))
    assertTrue("x".repeat(ProblemReports.NOTE_MAX) in long.text)
    assertFalse("x".repeat(ProblemReports.NOTE_MAX + 1) in long.text)
    assertFalse("What the person wrote" in build(note = "   ").text, "a blank note adds no section")
    assertFalse("What the person wrote" in build(note = null).text)
  }

  @Test fun `handled errors show kind and class only`() {
    val handled = mapOf(HandledKind.OCR to HandledStat(4, "IOException", t0), HandledKind.PACK_INSTALL to HandledStat(1, "SecurityException", t0))
    val r = build(handled = handled)
    assertTrue("text in pictures (OCR): 4, last IOException" in r.text)
    assertTrue("add-on install: 1, last SecurityException" in r.text)
    assertTrue("Handled errors:" in r.summary)
  }

  @Test fun `the report never goes over the size cap and says how many older events were left out`() {
    val big = "at some.code.Frame(File.kt:1)\n".repeat(300)
    val events = (1..40).map { StoredEvent(t0 - it * 1000L, EventKind.CRASH, it, big) }
    val r = build(events = events)
    assertTrue(r.text.length <= ProblemReports.MAX_CHARS)
    assertTrue("older events not shown" in r.text)
    assertEquals(40, r.events)
  }

  @Test fun `the saved file is dated`() {
    assertEquals("trawl-report-2025-10-09.txt", ProblemReports.fileName(t0))
  }

  @Test fun `the reporter writes a crash report, builds one report, and saves it`() {
    val dir = Files.createTempDirectory("reporter").toFile().also { it.deleteOnExit() }
    val tail = LogRing()
    tail.add('W', "Crawl", "opened /home/amina/Documents/x y/z.pdf")
    val reporter = Reporter(ReportStore(File(dir, "reports")), tail, { facts }, { listOf("app lock: off") })
    assertNull(reporter.pendingCrash())
    reporter.store.add(StoredEvent(t0, EventKind.CRASH, 7, Redaction.trace(IllegalStateException("/home/amina/secret.txt"))))
    assertEquals(EventKind.CRASH, reporter.pendingCrash()?.kind)
    reporter.handled(HandledKind.OCR, java.io.IOException("/home/amina/secret.txt"))
    val report = reporter.build("my note")
    assertFalse("amina" in report.text || "secret" in report.text, report.text)
    assertTrue("my note" in report.text && "text in pictures (OCR): 1, last java.io.IOException" in report.text, report.text)
    val out = File(dir, "out/sub/report.txt")
    assertTrue(reporter.save(report, out))
    assertEquals(report.text, out.readText())
    reporter.acknowledge()
    assertNull(reporter.pendingCrash(), "answering the card clears it")
    reporter.wipe()
    assertTrue(reporter.store.events().isEmpty())
  }
}
