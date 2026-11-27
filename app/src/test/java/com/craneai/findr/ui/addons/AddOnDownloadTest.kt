package com.craneai.findr.ui.addons

import android.content.Intent
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.craneai.findr.R
import com.craneai.findr.core.PackCatalog
import com.craneai.findr.core.PackCopy
import com.craneai.findr.core.PackProblem
import com.craneai.findr.core.PackRow
import com.craneai.findr.core.PackStatus
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

/** Verifies sideloaded Add-ons offer a working external-browser download action (ACTION_VIEW, 0 INTERNET permission). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AddOnDownloadTest {
  @get:Rule val rule = createComposeRule()

  private val lugandaMissing = PackRow(
    def = PackCatalog.speechLg,
    status = PackStatus.NOT_INSTALLED,
    on = false,
    progress = null,
    problem = PackProblem.NOT_FOUND,
    problemDetail = null,
    installedBytes = 0,
  )

  private val url = "https://github.com/Crane-AI-Labs/trawl/releases/download/models-v1/trawl-speech-lg.zip"

  @Test fun notFoundCardOffersBrowserDownloadButtonThatFiresActionView() {
    val app = RuntimeEnvironment.getApplication()
    val btnLabel = app.getString(R.string.addons_download_browser)

    rule.setContent {
      FindrTheme {
        AddOnCard(
          row = lugandaMissing,
          onAdd = {},
          onRemove = {},
          onSwitch = {},
          downloadUrl = url,
        )
      }
    }

    rule.onNodeWithText(btnLabel).performClick()
    val started = Shadows.shadowOf(app).nextStartedActivity
    assertNotNull("Tapping Download in browser must launch an external ACTION_VIEW intent", started)
    assertEquals(Intent.ACTION_VIEW, started.action)
    assertEquals(url, started.dataString)
    assertTrue(started.hasCategory(Intent.CATEGORY_BROWSABLE))
  }

  @Test fun addPackDialogOffersBrowserDownloadButtonThatFiresActionView() {
    val app = RuntimeEnvironment.getApplication()
    val btnLabel = app.getString(R.string.addons_download_browser)

    rule.setContent {
      FindrTheme {
        AddPackDialog(
          row = lugandaMissing.copy(problem = null),
          howTo = PackCopy(R.string.pack_how_to_add_downloads, listOf(PackCatalog.speechLg.archiveName, "131 MB")),
          onInstall = {},
          onDismiss = {},
          downloadUrl = url,
        )
      }
    }

    rule.onNodeWithText(btnLabel).performClick()
    val started = Shadows.shadowOf(app).nextStartedActivity
    assertNotNull("Tapping Download in browser inside dialog must launch ACTION_VIEW", started)
    assertEquals(Intent.ACTION_VIEW, started.action)
    assertEquals(url, started.dataString)
    assertTrue(started.hasCategory(Intent.CATEGORY_BROWSABLE))
  }
}
