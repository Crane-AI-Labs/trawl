package com.craneai.findr

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback

/**
 * Back on the first screen sends Trawl to the background, as Home does; it does not end it (B2, UX bugs 1.0.5).
 *
 * Android 12 and later do this by themselves for "launcher activities that are the root of their tasks", that is an activity whose
 * intent is ACTION_MAIN + CATEGORY_LAUNCHER ("Root launcher activities are no longer finished on Back press", Android 12 behavior
 * changes). For any other way in, the system keeps the old rule and finishes the activity, so the next open is a fresh screen with
 * the search, the scroll position and the open sheet gone. [systemMovesToBack] is that rule; when it would not apply, this puts
 * the same behaviour in place. It is registered first, so every other Back handler (a ticked selection, a sheet, the keyboard)
 * is asked before it, and it is disabled whenever the system already does the right thing, so the predictive Back-to-home
 * animation stays with the system there.
 */
internal object RootBack {
  /** Would the system send this task to the background on Back, unaided? */
  fun systemMovesToBack(isTaskRoot: Boolean, action: String?, categories: Set<String>?): Boolean =
    isTaskRoot && action == Intent.ACTION_MAIN && categories?.contains(Intent.CATEGORY_LAUNCHER) == true

  /** Is the fallback needed for this activity, as it was started? */
  fun needed(isTaskRoot: Boolean, intent: Intent?): Boolean =
    isTaskRoot && !systemMovesToBack(isTaskRoot, intent?.action, intent?.categories)

  /**
   * Registers the fallback on [activity]. [intent] is read each time (the activity's current intent, which a new one replaces),
   * [moveToBack] is `moveTaskToBack(true)`. Returns the callback so the caller can re-evaluate it ([refresh]) on a new intent.
   */
  fun install(activity: ComponentActivity, intent: () -> Intent?, isTaskRoot: () -> Boolean, moveToBack: () -> Unit): OnBackPressedCallback {
    val callback = object : OnBackPressedCallback(needed(isTaskRoot(), intent())) {
      override fun handleOnBackPressed() = moveToBack()
    }
    activity.onBackPressedDispatcher.addCallback(activity, callback)
    return callback
  }

  fun refresh(callback: OnBackPressedCallback, isTaskRoot: Boolean, intent: Intent?) {
    callback.isEnabled = needed(isTaskRoot, intent)
  }
}
