package com.craneai.findr.quick

/** What the panel may show right now. Anything but [SEARCH] shows no search box and no result. */
enum class QuickMode { SEARCH, DEVICE_LOCKED, APP_LOCKED }

/**
 * The panel sits over any app and can be opened from the shade or a widget, so it checks twice before it shows a file name:
 * the phone itself must be unlocked (a held phone), and Findr's own optional lock must be open. The phone's lock comes
 * first because the app lock's prompt cannot be answered on a locked phone.
 */
fun quickMode(deviceLocked: Boolean, appLocked: Boolean): QuickMode = when {
  deviceLocked -> QuickMode.DEVICE_LOCKED
  appLocked -> QuickMode.APP_LOCKED
  else -> QuickMode.SEARCH
}
