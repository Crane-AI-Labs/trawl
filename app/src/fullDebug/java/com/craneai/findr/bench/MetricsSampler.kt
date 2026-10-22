package com.craneai.findr.bench

import android.app.ActivityManager
import android.content.Context
import android.os.BatteryManager
import android.os.Debug
import android.os.PowerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Samples memory, battery current and thermal state while a benchmark runs. The approach comes from Crane's
 * OnDevice Evaluation Suite (MetricsSampler), so Findr's numbers line up with the Hummingbird runs.
 *
 * - PSS is the app's own proportional memory; the model weights dominate it.
 * - Battery current sign conventions differ across OEMs; we use |current|.
 * - getThermalHeadroom is rate-limited by the OS (~1 call/s), so it gets its own slower cadence.
 */
class MetricsSampler(context: Context) {
  private val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
  private val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
  private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager

  data class Snapshot(val pssMb: Double, val availMemMb: Double, val currentMa: Double)

  data class Summary(
    val peakPssMb: Double,
    val minAvailMemMb: Double,
    val avgCurrentMa: Double,
    val maxThermalHeadroom: Float,
    val maxThermalStatus: Int,
  )

  private var job: Job? = null
  private val pss = mutableListOf<Double>()
  private val avail = mutableListOf<Double>()
  private val current = mutableListOf<Double>()
  @Volatile private var maxHeadroom = Float.NaN
  @Volatile private var maxStatus = 0

  /** The latest headroom the OS actually returned (a direct call can come back NaN when rate-limited). */
  @Volatile var lastHeadroom = Float.NaN
    private set

  fun snapshot(): Snapshot {
    val mem = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
    val am = ActivityManager.MemoryInfo().also { activityManager.getMemoryInfo(it) }
    val currentUa = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
    return Snapshot(mem.totalPss / 1024.0, am.availMem / (1024.0 * 1024.0), kotlin.math.abs(currentUa) / 1000.0)
  }

  /** 0..1 cool, 1.0 = severe-throttling threshold, >1 throttled. NaN when unsupported or rate-limited. */
  fun thermalHeadroom(): Float = powerManager.getThermalHeadroom(0)

  fun thermalStatus(): Int = powerManager.currentThermalStatus

  fun batteryPercent(): Int = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

  fun isCharging(): Boolean = batteryManager.isCharging

  fun start(scope: CoroutineScope, intervalMs: Long = 250) {
    job?.cancel()
    job = scope.launch {
      var lastThermal = 0L
      while (isActive) {
        val s = snapshot()
        synchronized(this@MetricsSampler) {
          pss += s.pssMb
          avail += s.availMemMb
          current += s.currentMa
        }
        val now = System.currentTimeMillis()
        if (now - lastThermal >= 1500) {
          lastThermal = now
          val h = thermalHeadroom()
          if (!h.isNaN()) lastHeadroom = h
          if (!h.isNaN() && (maxHeadroom.isNaN() || h > maxHeadroom)) maxHeadroom = h
          maxStatus = maxOf(maxStatus, thermalStatus())
        }
        delay(intervalMs)
      }
    }
  }

  fun stop(): Summary {
    job?.cancel()
    job = null
    val now = snapshot()
    synchronized(this) {
      return Summary(
        peakPssMb = (pss + now.pssMb).max(),
        minAvailMemMb = (avail + now.availMemMb).min(),
        avgCurrentMa = if (current.isEmpty()) now.currentMa else current.average(),
        maxThermalHeadroom = maxHeadroom,
        maxThermalStatus = maxStatus,
      )
    }
  }
}
