package com.craneai.findr.diag

import com.craneai.findr.core.FindrSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The hand-off to email and WhatsApp, as plain data: the intents are built from exactly these. */
class SendPlansTest {
  private fun device() = DeviceFacts("0.1.0", 1, "release", "com.craneai.findr", "Samsung", "SM-A065F", "16", 36, "arm64-v8a", 3700, false, 25_000, 64_000)
  private fun input(events: List<StoredEvent> = emptyList(), note: String? = null, handled: Map<HandledKind, HandledStat> = emptyMap()) = ReportInput(
    createdAt = 1_760_000_000_000L,
    device = device(),
    models = ModelFacts("embeddinggemma-text-vision-440m.litertlm, 484 MB", listOf("speech: installed, 142 MB", "quicklook: not installed")),
    settingsFlags = Diag.settingsFlags(FindrSettings(excludedFolders = setOf("DCIM/Private", "Download/Tax 2026"))),
    events = events,
    handled = handled,
    logLines = listOf("10:00:00.000 I/Index: 12 files understood"),
    note = note,
  )
  private val crashBody = "Event: crash (uncaught exception)\nProcess: pid 1\njava.lang.IllegalStateException: <private>\n\tat com.craneai.findr.engine.FileProcessor.process(FileProcessor.kt:325)\n"

  // ---- the two constants -----------------------------------------------------------------------------------------------

  @Test fun theSupportContactIsAnAddressAndWhatsAppIsOffUntilSet() {
    assertTrue(SupportContact.EMAIL.matches(Regex("""[^@\s]+@[^@\s]+\.[a-z]+""")))
    assertTrue("shipped empty: WhatsApp is hidden until a number is set", SupportContact.WHATSAPP.isEmpty() || SendPlans.isE164(SupportContact.WHATSAPP))
  }

  @Test fun noWhatsAppPlanWhileTheNumberIsEmpty() {
    val report = ReportBuilder.build(input())
    assertNull(SendPlan.whatsApp(report, number = ""))
    assertNull(SendPlan.whatsApp(report, number = "0772123456"))
    assertNull(SendPlan.whatsApp(report, number = "+256 772 123 456"))
    assertNotNull(SendPlan.whatsApp(report, number = "+256772123456"))
  }

  /** The exact flag [com.craneai.findr.ui.report.ReportDialog] reads to decide whether to show the WhatsApp buttons at
   * all: with [SupportContact.WHATSAPP] shipped empty, it must be false, so no WhatsApp option appears anywhere. */
  @Test fun theReportScreenGateIsOffWhileTheNumberIsEmpty() {
    assertFalse("SupportContact.WHATSAPP is empty, so the UI must not show WhatsApp", ReportSender.whatsAppConfigured())
  }

  @Test fun e164() {
    assertTrue(SendPlans.isE164("+256772123456"))
    assertFalse(SendPlans.isE164("256772123456"))
    assertFalse(SendPlans.isE164("+0772123456"))
    assertFalse(SendPlans.isE164("+25677"))
    assertFalse(SendPlans.isE164(""))
  }

  // ---- email ----------------------------------------------------------------------------------------------------------------

  @Test fun theEmailCarriesASummaryInTheBodyAndTheReportAsAnAttachment() {
    val report = ReportBuilder.build(input(listOf(StoredEvent(1_760_000_000_000L, EventKind.CRASH, 1, crashBody))))
    val plan = SendPlan.email(report, to = "info@example.org")
    assertEquals("info@example.org", plan.to)
    assertEquals("findr-report.txt", plan.attachmentName)
    assertEquals("text/plain", plan.mime)
    assertTrue(plan.subject, plan.subject.contains("0.1.0") && plan.subject.contains("SM-A065F"))
    assertTrue(plan.body, plan.body.contains("IllegalStateException"))
    assertTrue(plan.body, plan.body.contains("The full report is attached"))
    assertTrue(plan.body.length <= SendPlans.MAIL_BODY_MAX)
  }

  @Test fun aHugeSummaryIsClippedInTheBodyAndTheFileKeepsTheRest() {
    val body = SendPlans.mailBody("line of text\n".repeat(2_000), hasAttachment = true)
    assertTrue(body.length <= SendPlans.MAIL_BODY_MAX)
    assertTrue(body, body.contains("[cut: the attached report has the rest]"))
    assertTrue(body.endsWith("It holds no file names, searches or text from files."))
    val short = SendPlans.mailBody("short", hasAttachment = false)
    assertEquals("short", short)
  }

  @Test fun clipCutsAtALineEndAndSaysSo() {
    val s = (1..100).joinToString("\n") { "row $it" }
    val c = SendPlans.clip(s, 200)
    assertTrue(c.length <= 200)
    assertTrue(c, c.endsWith("[cut: the attached report has the rest]"))
    assertTrue("cut at a line end: $c", c.contains("\n[cut"))
    assertTrue(c, c.substringBefore("\n[cut").lines().last().matches(Regex("row \\d+")))
    assertEquals("x", SendPlans.clip("x", 200))
  }

  @Test fun mailtoEncodesSpacesNewlinesAmpersandsAndNonAscii() {
    val uri = SendPlans.mailtoUri("a@b.org", "Findr problem report (0.1.0, SM-A065F)", "Line one & two\nOluganda: nkwagala = I love")
    assertTrue(uri, uri.startsWith("mailto:a@b.org?subject=Findr%20problem%20report%20%280.1.0%2C%20SM-A065F%29&body="))
    assertFalse(uri, uri.contains(" "))
    assertFalse(uri, uri.contains("+"))
    assertTrue(uri, uri.contains("%26") && uri.contains("%0A"))
    assertFalse(uri.substringAfter("?").contains("?"))
  }

  @Test fun theMailtoFallbackNeverExceedsTheBodyLimitEvenForAFullReport() {
    val report = ReportBuilder.build(input(List(10) { StoredEvent(1_000L * it, EventKind.CRASH, it, crashBody.repeat(20)) }))
    val uri = SendPlan.mailtoFallback(report, to = "a@b.org")
    val body = java.net.URLDecoder.decode(uri.substringAfter("&body="), "UTF-8")
    assertTrue(body.length <= SendPlans.MAIL_BODY_MAX)
  }

  // ---- WhatsApp -----------------------------------------------------------------------------------------------------------

  @Test fun theWhatsAppLinkHasDigitsOnlyAndAShortEncodedText() {
    val report = ReportBuilder.build(input(listOf(StoredEvent(1_760_000_000_000L, EventKind.ANR, 1, "Event: ANR\nmain thread: Blocked\n  at a.B.c(B.kt:1)\n")), note = "x ".repeat(400)))
    val plan = SendPlan.whatsApp(report, number = "+256772123456")!!
    assertTrue(plan.url, plan.url.startsWith("https://wa.me/256772123456?text="))
    assertFalse(plan.url.contains("+256"))
    assertFalse(plan.url.contains(" "))
    assertTrue(plan.text.length <= SendPlans.WHATSAPP_TEXT_MAX)
    assertEquals("findr-report.txt", plan.attachmentName)
  }

  // ---- the report itself --------------------------------------------------------------------------------------------------

  @Test fun theReportStatesTheAppPhoneModelAndSettingsWithoutFolderNames() {
    val text = ReportBuilder.build(input(note = "It closed while I searched")).text
    for (expected in listOf("0.1.0 (build 1, release)", "Samsung SM-A065F", "Android 16 (SDK 36)", "3700 MB RAM", "25000 MB free of 64000 MB", "embeddinggemma-text-vision-440m.litertlm", "Add-on speech: installed", "photos on", "2 folders left out", "It closed while I searched", "12 files understood")) {
      assertTrue("missing '$expected' in:\n$text", text.contains(expected))
    }
    assertFalse(text, text.contains("DCIM/Private") || text.contains("Tax 2026"))
  }

  @Test fun theReportListsHandledErrorsByKindAndClassOnly() {
    val handled = mapOf(HandledKind.OCR to HandledStat(3, "java.io.IOException", 1L), HandledKind.MODEL_INSTALL to HandledStat(1, "java.lang.IllegalStateException", 2L))
    val text = ReportBuilder.build(input(handled = handled)).text
    assertTrue(text, text.contains("model install: 1, last java.lang.IllegalStateException"))
    assertTrue(text, text.contains("text in pictures (OCR): 3, last java.io.IOException"))
  }

  @Test fun aVeryLongHistoryStaysUnderTheSizeCapAndKeepsTheNewestEvent() {
    val events = List(10) { StoredEvent(10_000L - it, EventKind.CRASH, it, "Event $it\n" + "frame line\n".repeat(900)) }
    val built = ReportBuilder.build(input(events))
    assertTrue("${built.text.length}", built.text.length <= ReportBuilder.MAX_CHARS)
    assertTrue(built.text.contains("Event 0\n"))
    assertTrue(built.text, built.text.contains("older events not shown"))
  }

  @Test fun anEmptyHistoryStillBuildsAReport() {
    val built = ReportBuilder.build(input())
    assertTrue(built.text.contains("none recorded"))
    assertTrue(built.summary.contains("No crash or freeze recorded"))
  }

  @Test fun theHeadlineNamesTheExceptionAndItsFirstFrame() {
    assertEquals(
      "java.lang.IllegalStateException: <private> at com.craneai.findr.engine.FileProcessor.process(FileProcessor.kt:325)",
      ReportBuilder.headline(crashBody),
    )
    assertEquals("Main thread: at a.B.c(B.kt:1)", ReportBuilder.headline("Event: ANR\nmain thread: Blocked\n  at a.B.c(B.kt:1)\n"))
  }
}
