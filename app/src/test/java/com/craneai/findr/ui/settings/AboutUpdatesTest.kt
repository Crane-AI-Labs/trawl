package com.craneai.findr.ui.settings

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.craneai.findr.R
import com.craneai.findr.ui.theme.FindrTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/** Verifies Settings -> About renders "Check for updates" in both Full and Go and opens the downloads URL via external browser. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AboutUpdatesTest {
  @get:Rule val rule = createComposeRule()

  @Test fun checkForUpdatesLaunchesSafeExternalBrowserIntent() {
    val app = RuntimeEnvironment.getApplication()
    val title = app.getString(R.string.settings_check_updates)

    rule.setContent {
      FindrTheme {
        Column { About(onLicences = {}) }
      }
    }

    rule.onNodeWithText(title).performClick()
    val started = Shadows.shadowOf(app).nextStartedActivity
    assertNotNull("Tapping Check for updates must launch ACTION_VIEW", started)
    assertEquals(Intent.ACTION_VIEW, started.action)
    assertEquals(UPDATES_URL, started.dataString)
    assertEquals("https://trawl.craneailabs.com/#download", started.dataString)
    assertTrue(started.hasCategory(Intent.CATEGORY_BROWSABLE))
    assertTrue((started.flags and Intent.FLAG_ACTIVITY_NEW_TASK) != 0)
  }
}
