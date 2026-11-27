package trawl.desktop

/**
 * The two reasons to wait that the computer gives and the person did not choose: it is hot, or it is asking programs to save power
 * (Low Power Mode, Battery Saver, the power-saver profile). Reading waits while either is true and the person has not said
 * "run anyway"; the banner shows it with that button. The override lasts for this run of the app: the person has decided, so a computer that
 * cools down and warms up again an hour later does not ask again.
 *
 * Heat is a Hold and not a slower pace: a slower pace would hide the reason and give the person no way to say "I know, go on". Each answer
 * is asked of the system at most every [ttlMs] (heat and power mode do not change faster).
 */
class SoftHolds(
  private val thermalAt: Int = BackgroundTuning.thermalHoldLevel,
  private val lowPowerOn: Boolean = BackgroundTuning.lowPowerHold != 0,
  private val thermal: () -> Int = { HostState.thermal() },
  private val lowPower: () -> Boolean = { HostState.lowPower() },
  private val now: () -> Long = System::currentTimeMillis,
  private val ttlMs: Long = 10_000,
) {
  @Volatile private var thermalOverridden = false
  @Volatile private var lowPowerOverridden = false
  private var thermalAtMs = Long.MIN_VALUE
  private var thermalLevel = 0
  private var powerAtMs = Long.MIN_VALUE
  private var powerSave = false

  /** The person chose to run anyway: [Hold.THERMAL] or [Hold.LOW_POWER]. */
  fun runAnyway(hold: Hold) {
    when (hold) {
      Hold.THERMAL -> thermalOverridden = true
      Hold.LOW_POWER -> lowPowerOverridden = true
      else -> {}
    }
  }

  fun overridden(hold: Hold): Boolean = when (hold) { Hold.THERMAL -> thermalOverridden; Hold.LOW_POWER -> lowPowerOverridden; else -> false }

  /** The last heat level read (0 nominal .. 3 critical), for the log and the benchmark. */
  val level: Int get() = thermalLevel

  /** Live heat level (0 nominal, 1 fair/warm, 2 serious, 3 critical), cached per [ttlMs] even when thermal hold is overridden. */
  @Synchronized fun currentLevel(): Int {
    if (thermalAt <= 0) return 0
    val t = now()
    if (thermalAtMs == Long.MIN_VALUE || t - thermalAtMs >= ttlMs) { thermalLevel = runCatching { thermal() }.getOrDefault(0); thermalAtMs = t }
    return thermalLevel
  }

  /** The reading must wait: the computer reports [thermalAt] ("serious") or worse. */
  @Synchronized fun thermalHolds(): Boolean {
    if (thermalAt <= 0 || thermalOverridden) return false
    return currentLevel() >= thermalAt
  }

  /** The reading must wait: the system is asking programs to save power. */
  @Synchronized fun lowPowerHolds(): Boolean {
    if (!lowPowerOn || lowPowerOverridden) return false
    val t = now()
    if (powerAtMs == Long.MIN_VALUE || t - powerAtMs >= ttlMs) { powerSave = runCatching { lowPower() }.getOrDefault(false); powerAtMs = t }
    return powerSave
  }
}
