package com.craneai.findr.ui

import android.app.Application
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Item 1 of the UI audit: two quick taps on a Back arrow must not pop the app to a blank page. The real routes, a plain NavHost. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class NavGuardTest {
  @get:Rule val rule = createComposeRule()
  private lateinit var nav: NavHostController

  private fun host() {
    rule.setContent {
      nav = rememberNavController()
      NavHost(nav, "home") {
        composable("home") { Text("home") }
        composable("settings") { Text("settings") }
        composable("licences") { Text("licences") }
      }
    }
    rule.waitForIdle()
  }

  private fun route() = nav.currentDestination?.route

  @Test fun unguardedDoubleBackEmptiesTheStack() {
    host()
    rule.runOnUiThread { nav.navigate("settings") }; rule.waitForIdle()
    rule.runOnUiThread { nav.popBackStack(); nav.popBackStack() }; rule.waitForIdle()
    assertNull("this is the bug the guard prevents: a blank page", route())
  }

  @Test fun doubleBackFromSettingsLandsOnHome() {
    host()
    rule.runOnUiThread { nav.navigate("settings") }; rule.waitForIdle()
    rule.runOnUiThread { nav.backOnce(); nav.backOnce() }; rule.waitForIdle()
    assertEquals("home", route())
  }

  @Test fun doubleBackFromLicencesStopsAtSettings() {
    host()
    rule.runOnUiThread { nav.navigate("settings") }; rule.waitForIdle()
    rule.runOnUiThread { nav.navigate("licences") }; rule.waitForIdle()
    rule.runOnUiThread { nav.backOnce(); nav.backOnce() }; rule.waitForIdle()
    assertEquals("settings", route())
  }

  @Test fun backOnHomeDoesNothing() {
    host()
    rule.runOnUiThread { nav.backOnce() }; rule.waitForIdle()
    assertEquals("home", route())
  }

  @Test fun doubleOpenStacksOnePage() {
    host()
    rule.runOnUiThread { nav.goOnce("settings"); nav.goOnce("settings") }; rule.waitForIdle()
    rule.runOnUiThread { nav.backOnce() }; rule.waitForIdle()
    assertEquals("one Back returns to home: only one Settings was stacked", "home", route())
  }
}
