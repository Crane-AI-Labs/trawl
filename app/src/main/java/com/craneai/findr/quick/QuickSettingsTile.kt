package com.craneai.findr.quick

import android.app.PendingIntent
import android.os.Build
import android.os.SystemClock
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.craneai.findr.core.FindrLog as Log

/**
 * The Quick Settings tile. It is a button, not a switch: a tap closes the shade and opens the panel over the app that was
 * in front. The service is bound by the system for a moment only, and holds nothing.
 */
class QuickSettingsTile : TileService() {
  override fun onStartListening() {
    qsTile?.apply {
      state = Tile.STATE_INACTIVE // never "on": it launches, it does not toggle
      updateTile()
    }
  }

  override fun onClick() {
    val tapped = SystemClock.uptimeMillis()
    Log.i(PERF, "tile_click uptime_ms=$tapped")
    // On a locked phone the shade is reachable; the panel is not, until the person unlocks (the system asks for it).
    if (isLocked) unlockAndRun { open(tapped) } else open(tapped)
  }

  private fun open(tapped: Long) {
    val intent = QuickSearchActivity.intent(this, QuickSearchActivity.SOURCE_TILE)
      .putExtra(QuickSearchActivity.EXTRA_TAP_UPTIME, tapped)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      // Android 14 dropped the Intent overload: a PendingIntent is the only way to start an activity from the shade.
      val pending = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
      startActivityAndCollapse(pending)
    } else {
      @Suppress("DEPRECATION")
      startActivityAndCollapse(intent)
    }
  }

  private companion object {
    const val PERF = "QuickPerf"
  }
}
