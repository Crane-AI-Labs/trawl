package com.craneai.findr.quick

import android.content.Context
import android.os.Build
import androidx.core.content.edit

/**
 * Where the one-time "add the tile" offer stands. The offer is made once, ever: after the first search that found
 * something, and only if the person did not already come in through the tile. Whatever they answer, it does not return.
 */
enum class TileOfferState { NEW, OFFERED, ADDED, DECLINED }

internal object TileOffer {
  /** requestAddTileService() exists from Android 13 (API 33). */
  const val MIN_SDK = Build.VERSION_CODES.TIRAMISU

  /** True only for a person who has not been asked, whose search just found something, and who has no tile yet. */
  fun shouldOffer(state: TileOfferState, sdk: Int, hits: Int, cameFromTile: Boolean): Boolean =
    state == TileOfferState.NEW && sdk >= MIN_SDK && hits > 0 && !cameFromTile
}

/** The panel's own small memory. Separate from `findr_ui` so the first-run work can change that file freely. */
internal class QuickPrefs(context: Context) {
  private val prefs = context.applicationContext.getSharedPreferences("findr_quick", Context.MODE_PRIVATE)

  var tileOffer: TileOfferState
    get() = runCatching { TileOfferState.valueOf(prefs.getString(KEY_TILE, null) ?: "") }.getOrDefault(TileOfferState.NEW)
    set(value) = prefs.edit { putString(KEY_TILE, value.name) }

  /** Blur previews that look private (see PrivateLook). On by default here: the panel opens over other apps, in front of others. */
  var blurPrivate: Boolean
    get() = prefs.getBoolean(KEY_BLUR, true)
    set(value) = prefs.edit { putBoolean(KEY_BLUR, value) }

  private companion object {
    const val KEY_BLUR = "blur_private"
    const val KEY_TILE = "tile_offer"
  }
}
