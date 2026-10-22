package com.craneai.findr

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The keyboard must be handled once: Compose's imePadding() on the search dock. Without an explicit soft-input mode some
 * phones (Transsion HiOS, seen on an Infinix) also shrink the window by the keyboard's height, and the dock then floats a
 * keyboard-height above the keys. Both screens that take typing declare adjustResize; this keeps it from being dropped.
 */
class ManifestSoftInputTest {
  private val manifest = listOf("src/main/AndroidManifest.xml", "app/src/main/AndroidManifest.xml").map(::File).first { it.exists() }.readText()

  private fun activityBlock(name: String): String {
    val start = manifest.indexOf("android:name=\"$name\"")
    assertTrue("$name is declared", start >= 0)
    return manifest.substring(start, manifest.indexOf("/>", start).let { a -> manifest.indexOf("</activity>", start).let { b -> if (b in 0 until a || a < 0) b else a } })
  }

  @Test fun theMainScreenResizesForTheKeyboardExplicitly() {
    assertTrue(activityBlock(".MainActivity").contains("adjustResize"))
  }

  @Test fun theQuickPanelResizesForTheKeyboardExplicitly() {
    assertTrue(activityBlock(".quick.QuickSearchActivity").contains("adjustResize"))
  }
}
