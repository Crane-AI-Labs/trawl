package com.craneai.findr.diag

import com.craneai.findr.core.FindrLog
import com.craneai.findr.core.FindrLog as Log
import com.craneai.findr.core.FindrSettings
import com.craneai.findr.core.SearchEngine
import java.io.File
import kotlin.concurrent.thread
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What [Diag] needs from the platform it runs on: where to keep reports, how this process and device look, how the
 * system recorded earlier exits, and how to clear what was shared. Android supplies one in :app; the desktop its own.
 */
interface DiagPlatform {
  /** The folder reports are kept in. */
  val reportDir: File
  val pid: Int
  fun deviceFacts(): DeviceFacts
  fun modelFacts(engine: SearchEngine?): ModelFacts

  /** The system's record of how earlier processes ended. Empty where there is none. Does disk work. */
  fun readExits(): List<ExitRecord>

  /** Remove anything the report sender left behind. */
  fun cleanupShared()
}

/**
 * Crash and error reporting, as the rest of the app sees it. Findr never sends anything itself (it has no internet
 * permission): this keeps redacted reports on the phone and builds the text the person can choose to send through
 * their own email or WhatsApp ([ReportSender]).
 *
 * Every entry point is safe to call before [init] and never throws: reporting must not become a second way to fail.
 */
object Diag {
  @Volatile private var platform: DiagPlatform? = null
  @Volatile private var store: ReportStore? = null
  @Volatile private var engine: SearchEngine? = null
  private val ring = LogRing()

  private val _pending = MutableStateFlow(0)

  /** How many crashes, freezes or failed starts the person has not been asked about yet. Above 0: show the card. */
  val pending: StateFlow<Int> = _pending.asStateFlow()

  /** Call once from Application.onCreate, as early as possible, so a crash during start-up is caught too. */
  fun init(platform: DiagPlatform, engine: SearchEngine?) {
    if (this.platform != null) return
    this.platform = platform
    this.engine = engine
    val s = ReportStore(platform.reportDir)
    store = s
    FindrLog.tap = { level, tag, line -> ring.add(level, tag, line) }
    CrashHandler.install(s, ring, platform.pid)
    // Reading the system's exit history touches the disk and a system service: not on the main thread.
    thread(name = "findr-diag", isDaemon = true) {
      try {
        s.ingest(platform.readExits())
        _pending.value = s.pending().size
      } catch (t: Throwable) {
        Log.w("FindrDiag", "could not read earlier exits (${Log.brief(t)})")
      }
    }
  }

  /** The engine exists once the app has started; the report reads settings and add-on states from it. */
  fun attach(engine: SearchEngine) { this.engine = engine }

  /** A failure Findr handled and carried on from. Only the kind and the exception's class are kept, never its message. */
  fun handled(kind: HandledKind, t: Throwable) {
    try { store?.recordHandled(kind, t) } catch (_: Throwable) { }
  }

  /** The person answered the card (Send or Not now). */
  fun acknowledge() {
    try { store?.acknowledge() } catch (_: Throwable) { }
    _pending.value = 0
  }

  /** Whether there is anything to attach: a report can always be built, but "View report" is more useful with events. */
  fun hasEvents(): Boolean = try { store?.events()?.isNotEmpty() == true } catch (_: Throwable) { false }

  /** "Delete everything Findr knows" reaches the reports too. */
  fun forgetReports() {
    try { store?.wipe() } catch (_: Throwable) { }
    ring.clear()
    try { platform?.cleanupShared() } catch (_: Throwable) { }
    _pending.value = 0
  }

  /** The finished report. Does disk work: call off the main thread. */
  fun buildReport(note: String?): BuiltReport {
    val p = platform
    val s = store
    if (p == null || s == null) {
      return BuiltReport("Findr problem report\nReporting was not ready.\n", "Findr problem report", SendPlans.subject("?", "?"), 0)
    }
    s.flushHandled()
    return ReportBuilder.build(
      ReportInput(
        createdAt = System.currentTimeMillis(),
        device = p.deviceFacts(),
        models = p.modelFacts(engine),
        settingsFlags = settingsFlags(engine?.settings?.value ?: FindrSettings()),
        events = s.events(),
        handled = s.handledCounts(),
        logLines = ring.tail(40),
        note = note,
      ),
    )
  }

  /** On/off switches and counts only: never a folder name. */
  fun settingsFlags(s: FindrSettings): List<String> = listOf(
    "photos ${onOff(s.indexPhotos)}",
    "screenshots ${onOff(s.indexScreenshots)}",
    "videos ${onOff(s.indexVideos)}",
    "documents ${onOff(s.indexDocuments)}",
    "audio ${onOff(s.indexAudio)}",
    "speech ${onOff(s.speechToText)}",
    "quick look ${onOff(s.quickLook)}",
    "learning speed ${s.learningSpeed.key}",
    "${s.excludedFolders.size} folders left out",
    "${s.includedFolders.size} folders added",
    "${s.groupOverrides.size} folder groups changed",
  )

  private fun onOff(b: Boolean) = if (b) "on" else "off"

  const val DIR = "reports"
}
