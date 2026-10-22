package com.craneai.findr.quick

import org.junit.Assert.assertEquals
import org.junit.Test

/** The panel shows results only when both the phone and Findr's own lock are open. */
class QuickModeTest {
  @Test fun openPhoneAndOpenAppSearches() = assertEquals(QuickMode.SEARCH, quickMode(deviceLocked = false, appLocked = false))

  @Test fun aLockedPhoneShowsNothingWhateverTheAppLockSays() {
    assertEquals(QuickMode.DEVICE_LOCKED, quickMode(deviceLocked = true, appLocked = false))
    assertEquals(QuickMode.DEVICE_LOCKED, quickMode(deviceLocked = true, appLocked = true))
  }

  @Test fun aClosedAppLockAsksForTheUnlock() = assertEquals(QuickMode.APP_LOCKED, quickMode(deviceLocked = false, appLocked = true))
}
