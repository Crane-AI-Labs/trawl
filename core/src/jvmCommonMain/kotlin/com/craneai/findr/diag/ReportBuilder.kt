package com.craneai.findr.diag

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** The phone and the app, as the report states them. All of it is about the phone and the build, none about the person's files. */
data class DeviceFacts(
  val appVersion: String,
  val appBuild: Int,
  val buildType: String,
  val packageName: String,
  val manufacturer: String,
  val model: String,
  val androidRelease: String,
  val sdk: Int,
  val abi: String,
  val ramMb: Long,
  val lowRamDevice: Boolean,
  val freeStorageMb: Long,
  val totalStorageMb: Long,
)

/** The AI model and add-on packs on the phone, one preformatted line each ("speech: installed, 3fa2c1d0"). */
data class ModelFacts(val embedding: String, val packs: List<String>)

data class ReportInput(
  val createdAt: Long,
  val device: DeviceFacts,
  val models: ModelFacts,
  /** Settings as on/off flags and counts, never folder names. */
  val settingsFlags: List<String>,
  val events: List<StoredEvent>,
  val handled: Map<HandledKind, HandledStat>,
  val logLines: List<String>,
  /** What the person typed under "Report a problem", if anything. */
  val note: String?,
)

/** A finished report: the [text] shown by View report and attached to the email, and the short [summary] for a body or a chat. */
class BuiltReport(val text: String, val summary: String, val subject: String, val events: Int)

object ReportBuilder {
  /** The whole report stays under this, so an attachment is small enough for any mail app or chat. */
  const val MAX_CHARS = 60_000
  const val NOTE_MAX = 1_000
  private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC)

  fun build(input: ReportInput): BuiltReport {
    val d = input.device
    val note = input.note?.trim()?.takeIf { it.isNotEmpty() }?.take(NOTE_MAX)
    val head = buildString {
      appendLine("Trawl problem report")
      appendLine("Made: ${STAMP.format(Instant.ofEpochMilli(input.createdAt))}")
      appendLine("This report holds the phone and app facts below, error traces with their messages removed, and the app's own")
      appendLine("log with names and searches removed. It holds no file names, folders, searches, text from files or transcripts.")
      appendLine()
      appendLine("== App and phone ==")
      appendLine("App: ${d.appVersion} (build ${d.appBuild}, ${d.buildType}) ${d.packageName}")
      appendLine("Phone: ${d.manufacturer} ${d.model}, Android ${d.androidRelease} (SDK ${d.sdk}), ${d.abi}")
      appendLine("Memory: ${d.ramMb} MB RAM${if (d.lowRamDevice) ", low-RAM device" else ""}")
      appendLine("Storage: ${d.freeStorageMb} MB free of ${d.totalStorageMb} MB")
      appendLine("Model: ${input.models.embedding}")
      input.models.packs.forEach { appendLine("Add-on $it") }
      appendLine("Settings: ${input.settingsFlags.joinToString(", ")}")
      if (d.buildType != "debug") appendLine("Stack frames are shrunk by R8; they read with the mapping file of build ${d.appBuild}.")
      if (note != null) { appendLine(); appendLine("== What the person wrote =="); appendLine(note) }
    }
    val handledBlock = buildString {
      appendLine()
      appendLine("== Errors Trawl handled and carried on from (since the app was installed or reports were last deleted) ==")
      if (input.handled.isEmpty()) appendLine("none") else input.handled.entries.sortedBy { it.key.ordinal }.forEach { (k, s) ->
        appendLine("${k.label}: ${s.count}, last ${s.lastClass}")
      }
    }
    val logBlock = buildString {
      appendLine()
      appendLine("== Recent log from this launch (redacted) ==")
      if (input.logLines.isEmpty()) appendLine("none") else input.logLines.forEach { appendLine(brand(it)) }
    }

    val events = StringBuilder()
    var shown = 0
    val budget = MAX_CHARS - head.length - handledBlock.length - logBlock.length
    for (e in input.events) {
      val block = "\n== Event ${shown + 1} ==\n${e.body.trimEnd()}\n"
      if (shown > 0 && events.length + block.length > budget) break
      events.append(block)
      shown++
    }
    val eventsText = if (shown == 0) "\n== Events ==\nnone recorded\n" else events.toString()
    val omitted = input.events.size - shown
    val text = head + eventsText + (if (omitted > 0) "\n($omitted older events not shown)\n" else "") + handledBlock + logBlock

    return BuiltReport(
      text = text.take(MAX_CHARS),
      summary = summary(input, note),
      subject = SendPlans.subject(d.appVersion, d.model),
      events = input.events.size,
    )
  }

  /** A few lines for an email body or a chat message: the build, the phone, and the newest event in one line. */
  fun summary(input: ReportInput, note: String?): String {
    val d = input.device
    return buildString {
      append("Trawl ${d.appVersion} (build ${d.appBuild}) on ${d.manufacturer} ${d.model}, Android ${d.androidRelease}.")
      input.events.firstOrNull()?.let { e ->
        append("\nLatest event: ${e.kind.label}, ${STAMP.format(Instant.ofEpochMilli(e.time))}")
        headline(e.body)?.let { append("\n$it") }
      } ?: append("\nNo crash or freeze recorded.")
      if (input.handled.isNotEmpty()) append("\nHandled errors: " + input.handled.entries.sortedBy { it.key.ordinal }.joinToString(", ") { "${it.key.label} ${it.value.count}" })
      if (note != null) append("\nNote: ${note.take(300)}")
    }
  }

  /** The exception (or the ANR's top frame) of an event, in one or two lines. */
  fun headline(body: String): String? {
    val lines = body.lines()
    val ex = lines.indexOfFirst { EXCEPTION.containsMatchIn(it) }
    if (ex >= 0) {
      val frame = lines.drop(ex + 1).firstOrNull { it.trimStart().startsWith("at ") }?.trim()
      return lines[ex].trim() + (frame?.let { " $it" } ?: "")
    }
    return lines.firstOrNull { it.trimStart().startsWith("at ") }?.trim()?.let { "Main thread: $it" }
  }

  /**
   * The product name as the person knows it. Log lines carry the internal tags and prose of the app's former name ("FindrWorker",
   * "Findr started"); in a report they read "Trawl". Applied to the log block only: the logcat tags themselves are untouched, and
   * stack frames keep their real class names so they still match the source and the R8 mapping.
   */
  fun brand(line: String): String = line.replace("Findr", "Trawl")

  private val EXCEPTION = Regex("""^[\w.$]+(?:Exception|Error|Throwable)\b""")
}
