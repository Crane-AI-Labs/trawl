package trawl.desktop

/**
 * "Do not flatten the person's battery for an index." Reading waits while the computer is on its battery and the charge is under
 * [floorPct]; it carries on the moment the charger is back. The person can say "read on battery anyway" ([override]): that holds for the
 * rest of this stay on the battery and ends by itself when the charger is plugged in, so it never has to be undone and nothing is saved.
 *
 * Every speed obeys it. Gentle also waits for the charger whatever the charge ([WorkSpeed.waitsForCharger]); that rule is separate.
 * A computer with no battery, or one that does not say how full it is, is never held: Trawl does not wait for a charger it cannot see.
 */
class LowBatteryRule(
  private val floorPct: Int = BackgroundTuning.lowBatteryPct,
  private val onBattery: () -> Boolean?,
  private val percent: () -> Int? = { HostState.batteryPercent() },
  private val now: () -> Long = System::currentTimeMillis,
  private val ttlMs: Long = 20_000,
) {
  @Volatile var overridden = false; private set
  private var levelAt = Long.MIN_VALUE
  private var level: Int? = null

  /** The person chose to read on battery anyway. */
  fun override() { overridden = true }

  /** True when reading should wait for the charger because the battery is low. */
  @Synchronized fun holds(): Boolean {
    if (floorPct <= 0) return false
    if (onBattery() != true) { overridden = false; return false }   // on the charger (or no battery): nothing to wait for, and the override has done its job
    if (overridden) return false
    val t = now()
    if (levelAt == Long.MIN_VALUE || t - levelAt >= ttlMs) { level = percent(); levelAt = t }
    val p = level ?: return false
    return p < floorPct
  }
}
