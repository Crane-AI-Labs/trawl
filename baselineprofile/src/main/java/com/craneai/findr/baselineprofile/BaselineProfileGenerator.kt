package com.craneai.findr.baselineprofile

import android.util.Log
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test

/**
 * Records which classes and methods Findr runs on a real phone, so the release APK ships them ahead-of-time compiled.
 *
 * The journey is what a person does in their first minute: cold start, the onboarding pages, "Start Findr", a search that fills the
 * grid, a scroll, Settings and back. Run it with scripts/gen-baseline-profile.sh (one named phone, under the phone lock); the clone is
 * uninstalled first, so the first iteration walks onboarding for real, and later ones start on the home screen.
 *
 * Phones: run it on the Samsung A06. On the Transsion phones (Infinix X6885, TECNO KN4) the instrumentation stalls for good right
 * after the app launches (the test process's own timers stop firing), so no profile comes out of them.
 */
/** The last onboarding button says the app's name: "Start Trawl" since the rename, "Start Findr" before it. */
private val START = By.text(java.util.regex.Pattern.compile("Start (Trawl|Findr)"))

class BaselineProfileGenerator {
  @get:Rule
  val rule = BaselineProfileRule()

  private val args = InstrumentationRegistry.getArguments()
  private val pkg = args.getString("targetAppId") ?: error("targetAppId is not set; run through the Gradle baseline-profile tasks")

  @Test
  fun startupOnboardingAndSearch() {
    val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    rule.collect(packageName = pkg, maxIterations = 4, stableIterations = 2, includeInStartupProfile = true) {
      if (first) {
        first = false
        // The two grants the in-app toggles would have made. (No `pm clear` here: the script starts from an uninstalled clone.)
        shell(device, "appops set $pkg MANAGE_EXTERNAL_STORAGE allow")
        shell(device, "pm grant $pkg android.permission.POST_NOTIFICATIONS")
      }
      step("start")
      pressHome()
      startActivityAndWait()
      step("started")

      // Onboarding, when it is showing: Next (past the language page too) until "Start <app name>", then start.
      if (device.wait(Until.hasObject(By.text("Next")), 4_000) || device.hasObject(START)) {
        var guard = 0
        while (!device.hasObject(START) && guard++ < 10) {
          // "Later" moves on from the Transsion background page (TECNO/Infinix/itel phones only).
          val forward = device.findObject(By.text("Next")) ?: device.findObject(By.text("Later"))
          step("tap ${forward?.text}")
          tap(device, forward)
          device.waitForIdle(700)
        }
        device.wait(Until.hasObject(START), 3_000)
        step("tap Start")
        tap(device, device.findObject(START))
      }

      // Home: the search box. Let the first names/text finish indexing on a cleared install so the grid has something to show.
      device.wait(Until.hasObject(By.clazz("android.widget.EditText")), 20_000)
      device.waitForIdle(1_000)
      step("home")
      tap(device, device.findObject(By.clazz("android.widget.EditText")))
      val w = device.displayWidth
      val h = device.displayHeight
      for (query in listOf("invoice", "photo", "receipt")) {
        step("query $query")
        retry { device.findObject(By.clazz("android.widget.EditText"))?.text = query }
        device.waitForIdle(1_500)
        Thread.sleep(1_500)
        // Plain swipes, not UiObject2.fling: the grid's nodes are rebuilt as results arrive and a held node goes stale.
        device.swipe(w / 2, h * 6 / 10, w / 2, h * 3 / 10, 20)
        device.waitForIdle()
        device.swipe(w / 2, h * 3 / 10, w / 2, h * 6 / 10, 20)
        device.waitForIdle()
      }
      retry { device.findObject(By.clazz("android.widget.EditText"))?.text = "" }
      device.pressBack()   // close the keyboard

      // Settings and back.
      step("settings")
      tap(device, device.findObject(By.desc("Settings")))
      device.wait(Until.hasObject(By.scrollable(true)), 3_000)
      device.swipe(w / 2, h * 6 / 10, w / 2, h * 3 / 10, 20)
      device.waitForIdle()
      device.pressBack()
      device.waitForIdle()
    }
  }

  private fun shell(device: UiDevice, cmd: String) {
    device.executeShellCommand(cmd)
  }

  private fun step(what: String) {
    Log.i("BPGen", what)
  }

  /** Runs [block] again if the node it held went stale while the screen redrew. */
  private fun retry(block: () -> Unit) {
    repeat(3) {
      try {
        block()
        return
      } catch (_: StaleObjectException) {
        Thread.sleep(300)
      }
    }
  }

  /** Taps the middle of a node with a plain screen tap: UiObject2.click() can wait for events that a busy Compose screen never sends. */
  private fun tap(device: UiDevice, o: UiObject2?): Boolean {
    val b = try { o?.visibleBounds } catch (_: StaleObjectException) { null } ?: return false
    device.click(b.centerX(), b.centerY())
    return true
  }

  private companion object {
    @JvmStatic var first = true
  }
}
