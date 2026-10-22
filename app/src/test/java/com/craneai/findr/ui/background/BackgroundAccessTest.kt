package com.craneai.findr.ui.background

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundAccessTest {
  @Test fun transsionPhonesGetTheScreenAndOthersDoNot() {
    // As the test phones report them (getprop ro.product.manufacturer / ro.product.brand).
    assertTrue(BackgroundAccess.applies("TECNO", "TECNO"))
    assertTrue(BackgroundAccess.applies("INFINIX", "Infinix"))
    assertTrue(BackgroundAccess.applies("ITEL", "itel"))
    assertTrue(BackgroundAccess.applies("INFINIX MOBILITY LIMITED", null))
    assertTrue("an itel build that reports only its brand", BackgroundAccess.applies("unknown", "itel"))
    assertFalse(BackgroundAccess.applies("samsung", "samsung"))
    assertFalse(BackgroundAccess.applies("Google", "google"))
    assertFalse(BackgroundAccess.applies("Xiaomi", "Redmi"))
    assertFalse(BackgroundAccess.applies(null, null))
    assertFalse(BackgroundAccess.applies("", "  "))
  }

  @Test fun phoneMastersAutoStartFirstAndTheAppsOwnPageLast() {
    val t = BackgroundAccess.TARGETS
    val first = t.first() as BackgroundAccess.Target.Action
    assertEquals("com.cyin.himgr.applicationmanager.view.activities.AUTO_START_ACTIVITY", first.action)
    assertEquals("pinned to Phone Master", BackgroundAccess.PHONE_MASTER, first.pkg)
    val byClass = t[1] as BackgroundAccess.Target.Component
    assertEquals("com.cyin.himgr.autostart.AutoStartActivity", byClass.cls)
    assertEquals("the system page for the app always exists", BackgroundAccess.Target.AppDetails, t.last())
  }

  @Test fun everyPlaceholderIsTheAppNameAndEveryStringIsUsed() {
    val xml = File("src/main/res/values/strings_background.xml").readText()
    val names = Regex("""<string name="(bg_[a-z_]+)"""").findAll(xml).map { it.groupValues[1] }.toList()
    val sources = File("src/main/java").walkTopDown().filter { it.extension == "kt" }.joinToString("\n") { it.readText() }
    for (n in names) assertTrue("$n is used", sources.contains("R.string.$n"))
    // Only %1$s, always inside the app_name span, so a translator can't drop the name and the code passes exactly one argument.
    val placeholders = Regex("""%\d\$[sd]""").findAll(xml).map { it.value }.toSet()
    assertEquals(setOf("%1\$s"), placeholders)
    val name = "%1" + "$" + "s"
    assertEquals(xml.split(name).size - 1, xml.split("<xliff:g id=\"app_name\" example=\"Trawl\">$name</xliff:g>").size - 1)
  }
}
