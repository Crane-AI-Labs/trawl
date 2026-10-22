package trawl.desktop

import com.craneai.findr.diag.CrashHandler
import com.craneai.findr.diag.HandledKind
import com.craneai.findr.diag.HandledStat
import com.craneai.findr.diag.LogRing
import com.craneai.findr.diag.Redaction
import com.craneai.findr.diag.ReportBuilder
import com.craneai.findr.diag.ReportStore
import com.craneai.findr.diag.StoredEvent
import com.craneai.findr.diag.SendPlans
import com.craneai.findr.diag.SupportContact
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** A finished problem report: the whole [text] (what is copied or saved), a few lines of [summary], and how many [events] it holds. */
class ProblemReport(val text: String, val summary: String, val events: Int)

/**
 * "Report a problem" on a computer. It reuses the phone's pieces (core `ReportStore`, `CrashHandler`, `LogRing`, `Redaction`) and the
 * desktop's existing diagnostics text, and joins them into ONE report, so there is a single thing to copy instead of a crash report
 * here and a "Copy diagnostics" there.
 *
 * Nothing is sent: Trawl never goes online. The person reads the report, then copies it or saves it as a file and sends it
 * themselves (to [SupportContact.EMAIL] if they choose). The report holds versions, sizes, counts, settings as on or off, code names
 * from error traces (never their messages), and the app's own log lines, which are already free of names and paths
 * ([AppLog]). The one free-text part is the note the person types; it is kept as they wrote it, with anything shaped like a path
 * removed, and the dialog says so.
 */
object ProblemReports {
  const val FILE_NAME = "trawl-report.txt"
  const val NOTE_MAX = ReportBuilder.NOTE_MAX
  const val MAX_CHARS = ReportBuilder.MAX_CHARS
  const val EMAIL = SupportContact.EMAIL

  private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC)

  /**
   * @param facts the app, the system and the counts (the text behind the old "Copy diagnostics")
   * @param privacyFlags settings as on/off words, no names ("app lock: on", "blur: on", "marked files: 3")
   */
  fun build(
    createdAt: Long,
    facts: Diagnostics.Facts,
    privacyFlags: List<String>,
    events: List<StoredEvent>,
    handled: Map<HandledKind, HandledStat>,
    logLines: List<String>,
    note: String?,
  ): ProblemReport {
    val cleanNote = note?.let { Redaction.scrub(it).trim() }?.takeIf { it.isNotEmpty() }?.take(NOTE_MAX)
    // The diagnostics text starts with its own title and ends with its own "nothing was sent" line; this report says both once, itself.
    val facts1 = Diagnostics.report(facts).lines().drop(1).dropLast(1)
    val head = buildString {
      appendLine("Trawl problem report")
      appendLine("Made: ${STAMP.format(Instant.ofEpochMilli(createdAt))}")
      appendLine("This report holds the app and computer facts below, error traces with their messages removed, and the app's own")
      appendLine("log with names and paths removed. It holds no file names, folders, searches or text from files.")
      appendLine("Nothing was sent anywhere: you read it, and you choose whether to send it.")
      appendLine()
      appendLine("== App and computer ==")
      facts1.forEach { appendLine(it) }
      if (privacyFlags.isNotEmpty()) appendLine("Privacy: ${privacyFlags.joinToString(", ")}")
      if (cleanNote != null) {
        appendLine()
        appendLine("== What the person wrote ==")
        appendLine(cleanNote)
      }
    }
    val handledBlock = buildString {
      appendLine()
      appendLine("== Errors Trawl handled and carried on from ==")
      if (handled.isEmpty()) appendLine("none") else handled.entries.sortedBy { it.key.ordinal }.forEach { (k, s) -> appendLine("${k.label}: ${s.count}, last ${s.lastClass}") }
    }
    val logBlock = buildString {
      appendLine()
      appendLine("== Recent log from this run (redacted) ==")
      if (logLines.isEmpty()) appendLine("none") else logLines.forEach { appendLine(Redaction.logLine(it)) }
    }
    val body = StringBuilder()
    var shown = 0
    val budget = MAX_CHARS - head.length - handledBlock.length - logBlock.length
    for (e in events) {
      val block = "\n== Event ${shown + 1} ==\n${e.body.trimEnd()}\n"
      if (shown > 0 && body.length + block.length > budget) break
      body.append(block)
      shown++
    }
    val eventsText = if (shown == 0) "\n== Events ==\nnone recorded\n" else body.toString()
    val omitted = events.size - shown
    val text = head + eventsText + (if (omitted > 0) "\n($omitted older events not shown)\n" else "") + handledBlock + logBlock
    return ProblemReport(text.take(MAX_CHARS), summary(facts, events, handled, cleanNote), events.size)
  }

  /** A few lines for the top of an email or a chat message the person writes themselves. */
  fun summary(facts: Diagnostics.Facts, events: List<StoredEvent>, handled: Map<HandledKind, HandledStat>, note: String?): String = buildString {
    append("Trawl ${facts.version} on ${facts.os} ${facts.osVersion} (${facts.arch}).")
    events.firstOrNull()?.let { e ->
      append("\nLatest event: ${e.kind.label}, ${STAMP.format(Instant.ofEpochMilli(e.time))}")
      ReportBuilder.headline(e.body)?.let { append("\n$it") }
    } ?: append("\nNo crash or freeze recorded.")
    if (handled.isNotEmpty()) append("\nHandled errors: " + handled.entries.sortedBy { it.key.ordinal }.joinToString(", ") { "${it.key.label} ${it.value.count}" })
    if (note != null) append("\nNote: ${note.take(300)}")
  }

  /** F10: the subject of the email the "Open email" button starts, with the Trawl version read from the summary's first line. */
  fun mailSubject(summary: String): String {
    val version = Regex("""^Trawl (\S+) on """).find(summary.lineSequence().firstOrNull().orEmpty())?.groupValues?.get(1)
    return if (version != null) "Trawl problem report ($version)" else "Trawl problem report"
  }

  /**
   * F10: the `mailto:` link for "Open email". The body is a line saying where the full report is (the clipboard: the button copies
   * it first) and the short [ProblemReport.summary]; the full text is too long for the weakest mail apps, so it is pasted, not carried
   * in the link. Nothing is sent: this only opens the person's own mail app with a draft they read, change and send themselves.
   */
  fun mailUri(summary: String, to: String = EMAIL): String {
    val lead = t("report_mail_lead") + "\n\n"
    val room = SendPlans.MAIL_BODY_MAX - lead.length
    val body = lead + if (summary.length <= room) summary else summary.take(room).substringBeforeLast('\n')
    return SendPlans.mailtoUri(to, mailSubject(summary), body)
  }

  /** `trawl-report-2026-10-21.txt`: a dated name so a saved report never overwrites an earlier one by accident. */
  fun fileName(now: Long = System.currentTimeMillis()): String =
    "trawl-report-" + DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC).format(Instant.ofEpochMilli(now)) + ".txt"
}

/**
 * The wiring: a report store in the data folder, the crash handler in front of the one already installed, and the pieces a report is
 * built from. [facts] and [privacyFlags] are asked for when a report is made, so it always shows the settings of that moment.
 */
class Reporter(
  val store: ReportStore,
  private val tail: LogRing,
  private val facts: () -> Diagnostics.Facts,
  private val privacyFlags: () -> List<String> = { emptyList() },
  private val clock: () -> Long = System::currentTimeMillis,
) {
  /** Puts the crash handler in front of the current one: a crash writes one redacted report here, then goes on as it would have. */
  fun installCrashHandler(): CrashHandler = CrashHandler.install(store, tail, ProcessHandle.current().pid().toInt())

  /** The newest crash the card has not been answered for, or null. */
  fun pendingCrash(): StoredEvent? = store.pending().firstOrNull()

  /** "Not now" or "Report": the card does not come back for events up to now. */
  fun acknowledge() = store.acknowledge()

  fun build(note: String? = null): ProblemReport {
    store.flushHandled()
    return ProblemReports.build(clock(), facts(), privacyFlags(), store.events(), store.handledCounts(), tail.tail(60), note)
  }

  /** Records a problem Trawl handled and carried on from: the kind and the error's class only. */
  fun handled(kind: HandledKind, t: Throwable) = store.recordHandled(kind, t)

  /** Deletes every kept report and count ("forget everything"). */
  fun wipe() = store.wipe()

  /** Writes the report where the person chose. Returns false when the disk refused. */
  fun save(report: ProblemReport, target: File): Boolean = runCatching {
    target.parentFile?.mkdirs()
    target.writeText(report.text)
  }.isSuccess

  companion object {
    fun forDataDir(dataDir: File, appLog: AppLog, facts: () -> Diagnostics.Facts, privacyFlags: () -> List<String> = { emptyList() }): Reporter =
      Reporter(ReportStore(File(dataDir, "reports")), appLog.tail, facts, privacyFlags)
  }
}
