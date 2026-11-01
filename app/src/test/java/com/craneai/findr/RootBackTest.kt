package com.craneai.findr

import android.app.Application
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.addCallback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** B2 (UX bugs 1.0.5): Back on the first screen sends Trawl to the background; the app is not ended and its state is not lost. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class RootBackTest {
  private val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
  private val explicit = Intent()   // what the quick panel's "Open Findr" used to send: a component and nothing else
  private val view = Intent(Intent.ACTION_VIEW)

  @Test fun theSystemAlreadyMovesALauncherRootToTheBackground() {
    assertTrue(RootBack.systemMovesToBack(true, Intent.ACTION_MAIN, setOf(Intent.CATEGORY_LAUNCHER)))
    assertFalse("not the root of its task: Back goes to the screen below", RootBack.systemMovesToBack(false, Intent.ACTION_MAIN, setOf(Intent.CATEGORY_LAUNCHER)))
    assertFalse(RootBack.systemMovesToBack(true, null, null))
    assertFalse(RootBack.systemMovesToBack(true, Intent.ACTION_MAIN, setOf(Intent.CATEGORY_DEFAULT)))
  }

  @Test fun theFallbackIsNeededOnlyWhereTheSystemWouldFinish() {
    assertFalse("the icon: the system's own animation and rule stay", RootBack.needed(true, launcher))
    assertTrue("an explicit start of the activity", RootBack.needed(true, explicit))
    assertTrue(RootBack.needed(true, view))
    assertTrue(RootBack.needed(true, null))
    assertFalse("not the task root: finishing is right", RootBack.needed(false, explicit))
  }

  private fun activity(intent: Intent, root: Boolean, moved: () -> Unit): Pair<ComponentActivity, OnBackPressedCallback> {
    val a = Robolectric.buildActivity(ComponentActivity::class.java, intent).create().start().resume().get()
    return a to RootBack.install(a, intent = { a.intent }, isTaskRoot = { root }, moveToBack = moved)
  }

  @Test fun backOnAnExplicitlyStartedRootMovesToTheBackgroundAndDoesNotFinish() {
    var moved = 0
    val (a, _) = activity(explicit, root = true) { moved++ }
    a.onBackPressedDispatcher.onBackPressed()
    assertEquals(1, moved)
    assertFalse("the activity, and with it the search and the open sheet, stays", a.isFinishing)
  }

  @Test fun backOnALauncherStartLeavesTheSystemToDoIt() {
    var moved = 0
    val (a, cb) = activity(launcher, root = true) { moved++ }
    assertFalse(cb.isEnabled)
    assertFalse(a.onBackPressedDispatcher.hasEnabledCallbacks())
    assertEquals(0, moved)
  }

  @Test fun aScreensOwnBackHandlerIsAskedFirst() {
    var moved = 0
    var ownHandled = 0
    val (a, _) = activity(explicit, root = true) { moved++ }
    a.onBackPressedDispatcher.addCallback(a) { ownHandled++ }   // a ticked selection, a sheet: registered later, so asked first
    a.onBackPressedDispatcher.onBackPressed()
    assertEquals(1, ownHandled)
    assertEquals("the background move waits until nothing else wants Back", 0, moved)
  }

  @Test fun aNewIntentChangesWhatBackDoes() {
    val (_, cb) = activity(launcher, root = true) {}
    assertFalse(cb.isEnabled)
    RootBack.refresh(cb, true, explicit)
    assertTrue(cb.isEnabled)
    RootBack.refresh(cb, true, launcher)
    assertFalse(cb.isEnabled)
  }

  @Test fun theQuickPanelOpensTrawlWithALauncherIntent() {
    val src = listOf("src/main/java/com/craneai/findr/quick/QuickSearchActivity.kt", "app/src/main/java/com/craneai/findr/quick/QuickSearchActivity.kt")
      .map { java.io.File(it) }.first { it.exists() }.readText()
    val open = src.substringAfter("onOpenFindr = {").substringBefore("finish()")
    assertTrue(open.contains("ACTION_MAIN") && open.contains("CATEGORY_LAUNCHER") && open.contains("MainActivity::class.java"))
  }
}
