package com.craneai.findr.ui.common

import com.craneai.findr.core.FindrLog as Log
import java.util.concurrent.atomic.AtomicInteger

/**
 * Deterministic counters for scroll work, next to the wall-clock numbers (which move with heat and with whatever else the
 * phone is doing). A change that lowers these did less work; one that only lowers milliseconds may just have run cooler.
 * Logged with the FindrPerf tag when scrolling stops: `adb logcat -s FindrPerf | grep counters`. Totals since launch.
 */
object UiCounters {
  /** Times a grid cell (MediaCell) was recomposed. */
  val cellComposes = AtomicInteger()

  /** Thumbnails requested from MediaProvider (a Coil memory-cache miss). */
  val thumbLoads = AtomicInteger()

  fun log(reason: String) {
    Log.i("FindrPerf", "counters $reason cellComposes=${cellComposes.get()} thumbLoads=${thumbLoads.get()}")
  }
}
