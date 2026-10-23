package com.craneai.findr.ui.background

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SamsungSleepTest {
  @Test fun samsungByManufacturerOnly() {
    // As the Samsung test devices report it (ro.product.manufacturer).
    assertTrue(SamsungSleep.applies("samsung"))
    assertTrue(SamsungSleep.applies("Samsung"))
    assertTrue(SamsungSleep.applies(" SAMSUNG "))
    assertFalse(SamsungSleep.applies("TECNO"))
    assertFalse(SamsungSleep.applies("Google"))
    assertFalse(SamsungSleep.applies("Xiaomi"))
    assertFalse(SamsungSleep.applies("samsungx"))
    assertFalse(SamsungSleep.applies(null))
    assertFalse(SamsungSleep.applies(""))
  }

  @Test fun theCardShowsOnlyWhenEveryConditionHolds() {
    assertTrue(SamsungSleep.shouldShow(samsung = true, allFiles = true, waiting = true, unrestricted = false, dismissed = false))
    assertFalse("not a Samsung phone", SamsungSleep.shouldShow(false, true, true, false, false))
    assertFalse("no access yet: nothing is being indexed", SamsungSleep.shouldShow(true, false, true, false, false))
    assertFalse("indexing finished", SamsungSleep.shouldShow(true, true, false, false, false))
    assertFalse("already Unrestricted", SamsungSleep.shouldShow(true, true, true, true, false))
    assertFalse("put away with OK", SamsungSleep.shouldShow(true, true, true, false, true))
  }

  @Test fun everyOtherCombinationHidesTheCard() {
    // Exhaustive: exactly one of the 32 input combinations shows it.
    var shown = 0
    for (bits in 0 until 32) {
      val on = { i: Int -> bits and (1 shl i) != 0 }
      if (SamsungSleep.shouldShow(on(0), on(1), on(2), on(3), on(4))) shown++
    }
    assertEquals(1, shown)
  }

  @Test fun theSettingsRowStaysUntilTheAppIsUnrestricted() {
    assertTrue(SamsungSleep.settingsRowShown(samsung = true, unrestricted = false))
    assertFalse(SamsungSleep.settingsRowShown(samsung = true, unrestricted = true))
    assertFalse(SamsungSleep.settingsRowShown(samsung = false, unrestricted = false))
  }

  @Test fun theBatteryPageComesFirstAndTheAppsOwnPageLast() {
    val t = SamsungSleep.TARGETS
    val first = t.first() as SamsungSleep.Target.Action
    assertEquals("android.settings.VIEW_ADVANCED_POWER_USAGE_DETAIL", first.action)
    assertEquals("the system page for the app always exists", SamsungSleep.Target.AppDetails, t.last())
  }

  @Test fun theMainManifestStillDeclaresNoRestrictedPermission() {
    // Play-compatible: the permission lives only in the opt-in src/batteryRequest manifest (BatteryExemptionTest checks the wiring).
    val manifest = File("src/main/AndroidManifest.xml").readText()
    assertFalse(manifest.contains("REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"))
  }

  @Test fun everySamsungStringIsUsedAndTheNameIsAlwaysThePlaceholder() {
    val xml = File("src/main/res/values/strings_background.xml").readText()
    val names = Regex("""<string name="(bgs_[a-z_]+)"""").findAll(xml).map { it.groupValues[1] }.toList()
    assertEquals(listOf("bgs_title", "bgs_body", "bgs_open"), names)
    val sources = File("src/main/java").walkTopDown().filter { it.extension == "kt" }.joinToString("\n") { it.readText() }
    for (n in names) assertTrue("$n is used", sources.contains("R.string.$n"))
  }
}
