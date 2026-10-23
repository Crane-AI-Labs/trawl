package com.craneai.findr.ui.background

import com.craneai.findr.ui.background.BatteryExemption.State
import com.craneai.findr.ui.background.BatteryExemption.Tried
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The "let Trawl keep reading in the background" setup step: when it shows, what it says, the Samsung card after a yes, and the Play-compatible wiring. */
class BatteryExemptionTest {
  private fun read(path: String) = File(path).readText()
  private fun sources() = File("src/main/java").walkTopDown().filter { it.extension == "kt" }.joinToString("\n") { it.readText() }

  // --- the step is shown or skipped

  @Test fun theStepShowsOnlyWhileThePhoneStillOptimisesTheApp() {
    assertTrue(BatteryExemption.stepShown(exemptAtStart = false))
    assertFalse("a phone that already ignores optimisation skips the page", BatteryExemption.stepShown(exemptAtStart = true))
  }

  @Test fun theStepSitsAfterAllFilesAccessAndBeforeTheModels() {
    val page = Regex("""private enum class Page \{([^}]*)\}""").find(read("src/main/java/com/craneai/findr/ui/onboarding/OnboardingScreen.kt"))!!
      .groupValues[1].split(',').map { it.trim() }
    assertEquals(listOf("LANGUAGE", "WELCOME", "FILES", "BACKGROUND", "MODELS", "EXTRAS"), page)
  }

  @Test fun theStepIsSkippedWhenAlreadyExemptAndNeverGatesStart() {
    val onboarding = read("src/main/java/com/craneai/findr/ui/onboarding/OnboardingScreen.kt")
    assertTrue(onboarding.contains("it != Page.BACKGROUND || showBackground"))
    assertTrue(onboarding.contains("BatteryExemption.stepShown(exemptAtStart = BatteryExemption.isExempt(context))"))
    // "Not now" is a button on the page itself, wired to move on (or finish when it is the last page), whatever the state.
    assertTrue(onboarding.contains("R.string.bgx_not_now"))
    val notNow = onboarding.substringAfter("if (onBackground && battery.state != BatteryExemption.State.ALLOWED)").substringBefore("} else if (last)")
    assertTrue(notNow.contains("onFinish()") && notNow.contains("animateScrollToPage(pager.currentPage + 1)"))
  }

  // --- the state re-check

  @Test fun theStateIsAllowedWheneverAndroidSaysSoWhateverWasTried() {
    for (tried in listOf(null) + Tried.entries) assertEquals(State.ALLOWED, BatteryExemption.state(exempt = true, tried = tried))
  }

  @Test fun beforeAnyPressTheStateIsAsk() {
    assertEquals(State.ASK, BatteryExemption.state(exempt = false, tried = null))
  }

  @Test fun afterANoTheStateIsDeniedAndAfterTheBatteryPageItIsTheHint() {
    assertEquals("the box was shown and the answer is still no", State.DENIED, BatteryExemption.state(false, Tried.DIALOG))
    assertEquals("nothing could be opened", State.DENIED, BatteryExemption.state(false, Tried.NOTHING))
    assertEquals(State.PAGE_OPENED, BatteryExemption.state(false, Tried.PAGE))
  }

  @Test fun theAnswerIsReadFromAndroidNotFromTheActivityResult() {
    val src = read("src/main/java/com/craneai/findr/ui/background/BatteryExemption.kt")
    assertTrue("re-read when the system box closes", src.contains("rememberLauncherForActivityResult") && src.contains("exempt = BatteryExemption.isExempt(context)"))
    assertTrue("re-read when the app comes back", src.contains("LifecycleEventEffect(Lifecycle.Event.ON_RESUME)"))
  }

  // --- the Samsung card after a yes

  @Test fun theSamsungCardIsHiddenOnceTheAppIsExemptAndStillShownWhileOptimised() {
    // A yes in setup makes isIgnoringBatteryOptimizations true, which is SamsungSleep's "unrestricted".
    assertFalse(SamsungSleep.shouldShow(samsung = true, allFiles = true, waiting = true, unrestricted = true, dismissed = false))
    assertTrue("said Not now: the card is shown as before", SamsungSleep.shouldShow(true, true, true, unrestricted = false, dismissed = false))
    assertTrue("the exemption check is the one the step uses", read("src/main/java/com/craneai/findr/ui/background/BatteryExemption.kt").contains("fun isExempt(context: Context): Boolean = SamsungSleep.isUnrestricted(context)"))
  }

  // --- routes and the Play-compatible build flag

  @Test fun theRequestIsASystemBoxOnlyWhenTheBuildHasIt() {
    assertEquals(BatteryExemption.Route.SYSTEM_DIALOG, BatteryExemption.route(requestBuilt = true))
    assertEquals("a Play build opens the app's battery page", BatteryExemption.Route.BATTERY_PAGE, BatteryExemption.route(requestBuilt = false))
  }

  @Test fun thePermissionLivesOnlyInTheOptInManifestAndTheFlagCannotDisagreeWithIt() {
    assertFalse(read("src/main/AndroidManifest.xml").contains("REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"))
    assertTrue(read("src/batteryRequest/AndroidManifest.xml").contains("""<uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />"""))
    val gradle = read("build.gradle.kts")
    assertTrue(gradle.contains("""batteryExemptionRequest = providers.gradleProperty("batteryExemptionRequest").orNull != "false""""))
    assertTrue(gradle.contains("BATTERY_EXEMPTION_REQUEST"))
    // Both the build field and the manifest hang on the same val, so a build with the flag off has neither.
    assertTrue(gradle.contains("if (batteryExemptionRequest)") && gradle.contains("src/batteryRequest/AndroidManifest.xml"))
  }

  @Test fun noNetworkPermissionIsAddedByTheStep() {
    for (m in listOf("src/main/AndroidManifest.xml", "src/batteryRequest/AndroidManifest.xml")) {
      // The main manifest names INTERNET only to strip it (tools:node="remove"); no manifest may grant it.
      val grants = Regex("""<uses-permission[^>]*android.permission.INTERNET[^>]*>""").findAll(read(m)).filterNot { it.value.contains("""tools:node="remove"""") }
      assertEquals(m, 0, grants.count())
    }
  }

  // --- strings

  @Test fun everyStepStringIsUsedAndTheNameIsAlwaysThePlaceholder() {
    val xml = read("src/main/res/values/strings_background.xml")
    val names = Regex("""<string name="(bgx_[a-z_]+)"""").findAll(xml).map { it.groupValues[1] }.toList()
    assertEquals(listOf("bgx_title", "bgx_body", "bgx_allow", "bgx_not_now", "bgx_granted", "bgx_denied", "bgx_page_hint"), names)
    val src = sources()
    for (n in names) assertTrue("$n is used", src.contains("R.string.$n"))
    val tags = listOf("am", "ar", "b+ach", "b+nyn", "fr", "ha", "lg", "pt", "rw", "so", "sw", "yo", "zu")
    for (tag in tags) {
      val t = read("src/main/res/values-$tag/strings_background.xml")
      for (n in names) assertTrue("$n in $tag", t.contains("""name="$n""""))
      for (n in listOf("bgx_title", "bgx_body")) {
        val line = t.lines().first { it.contains("""name="$n"""") }
        assertTrue("$n in $tag keeps the app-name placeholder", line.contains("""<xliff:g id="app_name" example="Trawl">%1${'$'}s</xliff:g>"""))
        assertFalse("$n in $tag never names the app literally", line.replace("example=\"Trawl\"", "").let { it.contains("Trawl") || it.contains("Findr") })
      }
    }
  }
}
