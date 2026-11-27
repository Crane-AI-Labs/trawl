package trawl.desktop

/**
 * Every number that shapes how the desktop app works in the background, in one place: how long a change in a watched folder is
 * waited on before it is looked at, how often an idle app wakes, how a walk or a forget pass paces itself, how the reader steps aside
 * for a search, how it shares the machine with the person's own work.
 *
 * The values are the ones measured in internal/research/desktop-tuning.md. Each can be overridden with `-Dtrawl.tune.<name>=<value>` so that
 * a benchmark can sweep it without a rebuild; nothing in the app sets those, so a person always runs the defaults. Read once, at first use.
 */
object BackgroundTuning {
  private fun long(name: String, default: Long): Long = System.getProperty("trawl.tune.$name")?.trim()?.toLongOrNull() ?: default
  private fun int(name: String, default: Int): Int = long(name, default.toLong()).toInt()
  private fun double(name: String, default: Double): Double = System.getProperty("trawl.tune.$name")?.trim()?.toDoubleOrNull() ?: default

  // ---- the watcher (FolderWatcher) ------------------------------------------------------------------------------------------------

  /**
   * A change is reported when no new one has come for this long (the watcher looks every [watchTickMs], so a change is seen up to that much later
   * still). 1.0.3 waited 1,500 ms: a new document was searchable by name after 2.0 s, and at 250 ms it is after 0.7 s, for 8% more CPU while a folder
   * is being written to without a break (churn, 1,000 files a minute). 100 ms is no faster for documents and photos and 0.4 s faster for pdfs; 250 is kept
   * because a quiet gap of 100 ms is often shorter than the pauses inside a download or a save, so a half-written file would be read twice (not measured).
   */
  val debounceMs: Long = long("debounceMs", 250)
  /** Grace window before flushing a delete so atomic editor saves (`tmp -> rename`) coalesce into a modify instead of dropping embeddings. */
  val deleteGraceMs: Long = long("deleteGraceMs", 300)
  /** ...or when the first of them is this old, so a folder that never stops changing is still looked at. */
  val maxWaitMs: Long = long("maxWaitMs", 10_000)
  /** Legacy tick knob (FolderWatcher now uses demand-driven one-shot timers for 0 Hz idle wakeups). */
  val watchTickMs: Long = long("watchTickMs", 500)
  /** A folder that cannot be watched (too many folders for the kernel, an unplugged drive) is walked in full this often. */
  val pollSeconds: Int = int("pollSeconds", 30)
  /**
   * ...but not again until this many times as long as the last walk of it took (0 turns the rule off): a walk of 2.4 s, which is a 130,000-file
   * folder, then comes every 288 s (just under [pollMaxMs]) and costs 0.8 CPU-seconds a minute instead of 4.75. A small folder (a walk under
   * a quarter of a second) still comes every [pollSeconds]. At most [pollMaxMs] between two walks.
   */
  val pollFactor: Long = long("pollFactor", 120)
  val pollMaxMs: Long = long("pollMaxMs", 300_000)

  // ---- walking (IndexService.Scan, ReconcilePacing) -----------------------------------------------------------------------------

  /** A walk that took longer than this makes the next one wait... */
  val reconcileFreeMs: Long = long("reconcileFreeMs", 250)
  /** ...this many times as long as it took... */
  val reconcileFactor: Long = long("reconcileFactor", 4)
  /** ...but never longer than this. */
  val reconcileMaxWaitMs: Long = long("reconcileMaxWaitMs", 60_000)
  /** Files a walk collects before it writes them to the index in one transaction. */
  val scanFlushBatch: Int = int("scanFlushBatch", 500)

  // ---- forgetting files that are gone (IndexService.drop) ---------------------------------------------------------------------

  val forgetBatch: Int = int("forgetBatch", 200)
  val forgetBatchPaused: Int = int("forgetBatchPaused", 50)
  val forgetCheckpointEvery: Int = int("forgetCheckpointEvery", 5_000)
  /** Paused, a step of background work rests this many times as long as it took... */
  val pausedRestFactor: Long = long("pausedRestFactor", 9)
  /** ...but at most this long. */
  val pausedRestMaxMs: Long = long("pausedRestMaxMs", 5_000)

  // ---- the worker's idle rhythm and what it shows (IndexService, PublishThrottle) ------------------------------------------------

  /** Progress reaches the window at most once in this long. */
  val publishGapMs: Long = long("publishGapMs", 1_000)
  /** An idle worker looks at the queue this often... */
  val idleWaitMs: Long = long("idleWaitMs", 1_000)
  /** ...and refreshes what the window shows this often when nothing happens (and this often when paused). */
  val idleRefreshMs: Long = long("idleRefreshMs", 5_000)
  val pausedRefreshMs: Long = long("pausedRefreshMs", 15_000)

  // ---- reading ------------------------------------------------------------------------------------------------------------------

  /** Files the worker takes from the queue in one go. */
  val readBatch: Int = int("readBatch", 8)
  /** Videos and recordings get this share of the busy time while photos and documents wait too. */
  val mediaShare: Double = double("mediaShare", 0.2)
  /** Reading steps aside for a search: it waits until no query has come for [searchQuietMs], but never more than [searchCapMs] in a row. */
  val searchQuietMs: Long = long("searchQuietMs", 1_200)
  val searchCapMs: Long = long("searchCapMs", 8_000)
  val searchStepMs: Long = long("searchStepMs", 100)

  // ---- sharing the computer with the person (Politeness, DutyTable, the Holds) -----------------------------------------------------

  /** Whether the reader gives way to what else is running on the computer and keeps to its duty cell (Politeness). 0 turns it off (the 1.0.3 behaviour). */
  val governor: Int = int("governor", 1)
  /** Cores the reader leaves free for the person before it takes any. */
  val governorSpareCores: Double = double("governorSpareCores", 1.0)
  /** The least the reader averages when the computer is fully busy, in cores: it always keeps working, only slowly. */
  val governorMinCores: Double = double("governorMinCores", 0.25)
  /** The longest the reader rests after one file. This is also the starvation floor: a file is read at least this often (plus its own time), in every state. */
  val governorMaxRestMs: Long = long("governorMaxRestMs", 5_000)
  /** How often the computer's load is looked at, and how much of each new look counts (the rest is the last answer). */
  val governorSampleMs: Long = long("governorSampleMs", 1_000)
  val governorSmoothing: Double = double("governorSmoothing", 0.4)

  /** Engine threads per speed (0: Gentle a quarter of the cores, Balanced two, Fast half of them and at least four). */
  val threadsGentle: Int = int("threadsGentle", 0)
  val threadsBalanced: Int = int("threadsBalanced", 0)
  val threadsFast: Int = int("threadsFast", 0)
  /**
   * The duty table: the most cores each speed averages while the person is active, away on the battery, away on the charger (0: no limit).
   * Mean cores = threads x duty: Gentle 2 threads at 25% = 0.5, Balanced 2 threads at 35% = 0.7.
   */
  val gentleActive: Double = double("gentleActive", 0.5)
  val gentleAwayBattery: Double = double("gentleAwayBattery", 0.3)
  val gentleAwayAc: Double = double("gentleAwayAc", 1.0)
  val balancedActive: Double = double("balancedActive", 0.7)
  val balancedAwayBattery: Double = double("balancedAwayBattery", 0.7)
  /**
   * Balanced has two threads that each use more than a core while they read, so a cap of 2.0 held it to 86% of the 1.0.3 speed on documents and
   * 69% on photos when the person was away on the charger (21.3 / 58.4 files a minute against 24.7 / 84.9). At 3.0 it is back to 1.0.3's speed
   * (25.0 / 86.4) for 12% to 37% more CPU, and 4.0 adds nothing; so away on the charger Balanced reads as fast as it can, and the caps apply
   * while the person is there or the computer is on its battery. The macOS and Windows levers still lower it while no window shows.
   */
  val balancedAwayAc: Double = double("balancedAwayAc", 3.0)
  val fastActive: Double = double("fastActive", 0.0)
  val fastAwayBattery: Double = double("fastAwayBattery", 0.0)
  val fastAwayAc: Double = double("fastAwayAc", 0.0)

  /** The person counts as at the keyboard for this long after the last key or touch. */
  val activeWithinS: Long = long("activeWithinS", 120)
  /** Benchmarks on a computer with no keyboard: 1 says the person is there, 0 that they are not, -1 asks the system. */
  val assumeActive: Int = int("assumeActive", -1)
  /** Benchmarks on a computer with no battery: 1 says it is on the battery, 0 that it is on the charger, -1 asks the system. */
  val assumePower: Int = int("assumePower", -1)
  /** Reading waits (a Hold, with "run anyway") when the computer reports this heat level or higher: 2 is "serious" (0 turns the rule off). */
  val thermalHoldLevel: Int = int("thermalHoldLevel", 2)
  /** Reading waits (a Hold, with "run anyway") while the system's Low Power Mode or Battery Saver is on (0 turns the rule off). */
  val lowPowerHold: Int = int("lowPowerHold", 1)
  /**
   * Operating-system priority for the reader while no window of Trawl is showing and the quick panel is closed: 0 off, 1 on. On by default on macOS
   * and Windows, off on Linux. macOS: the whole process (every thread and helper program) is set to the background class with throttled disk (Qos).
   * Windows: see [windowsBackground]. Linux: the app's systemd scope gets a CPU weight and quota, lifted at once when a window comes forward
   * (LinuxScope); measured to add nothing once the governor is on (it only made searches slower), so it stays off there unless asked for.
   */
  val lowPriority: Int = int("lowPriority", if (Platform.os == Os.LINUX) 0 else 1)
  /**
   * macOS only: while the person is away from the keyboard and the Mac is on AC power (plugged in), lift `PRIO_DARWIN_BG` and apply
   * `QOS_CLASS_UTILITY` to reading threads with `IOPOL_UTILITY` disk policy instead (keeping [Politeness] CPU caps active), so unattended
   * indexing on mains power is not slowed ~7x by the E-core background clamp. While the person is active or on battery, full
   * `PRIO_DARWIN_BG` + disk throttle still applies. 0 off (always full background class), 1 on.
   */
  val macUtilityAwayAc: Int = int("macUtilityAwayAc", 1)
  /**
   * macOS only: each Trawl reading thread sets its own quality-of-service class at its start (new threads do not inherit it). 0 off, 1 utility,
   * 2 background. Off by default: it holds for the life of the thread, so it would also slow Fast and the reader while a window of Trawl is in
   * front, and the whole-process lever ([lowPriority]) already covers every thread while it matters and is lifted when a window comes forward.
   */
  val threadQos: Int = int("threadQos", 0)
  /**
   * macOS only: helper programs (ffmpeg, the OCR tools) are started through `taskpolicy` with throttled disk. 0 off, 1 utility, 2 background.
   * Off by default for the same reason: a helper started this way stays lowered when the process lever is lifted. The process lever already
   * covers helpers started while it is on.
   */
  val helperQos: Int = int("helperQos", 0)
  /**
   * Windows only: what the whole process is lowered to while no window of Trawl is showing and the quick panel is closed (the same moments as
   * [lowPriority]). 0 nothing, 1 the BELOW_NORMAL priority class, 2 that and EcoQoS (the efficiency mode of Task Manager). Measured on a Windows 11 VM
   * (internal/research/desktop-tuning.md): the person's own work slows by 21% with the reader at normal priority and by 0.5% with it lowered, and
   * the reader's speed while the person is away does not change. Not thread background mode: that cut the speed away by five and a half times.
   * Helper programs are started at the same level, as the class passes to children but EcoQoS does not. A VM cannot show on which kind of core
   * the work lands, or the power it draws; that is what Eco QoS is documented to do on a laptop with efficiency cores.
   */
  val windowsBackground: Int = int("windowsBackground", 2)
  /** Linux scope limits while hidden (percent of one core for the quota, 0 for none; the weight is systemd's CPUWeight, default 100). */
  val linuxQuotaPct: Int = int("linuxQuotaPct", 0)
  val linuxWeight: Int = int("linuxWeight", 20)
  /** Below this battery charge, off the charger, reading waits until the charger is back or the person says "run anyway" (0 turns the rule off). Every speed; new in desktop 1.0.4, which 1.0.3 did not have. */
  val lowBatteryPct: Int = int("lowBatteryPct", 20)
}
