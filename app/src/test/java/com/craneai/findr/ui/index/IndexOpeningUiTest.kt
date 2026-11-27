package com.craneai.findr.ui.index

import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.craneai.findr.R
import com.craneai.findr.core.FindrSettings
import com.craneai.findr.core.IndexOpenProblem
import com.craneai.findr.core.IndexPhase
import com.craneai.findr.core.IndexStatus
import com.craneai.findr.ui.atlas.AtlasTestBase
import com.craneai.findr.ui.theme.FindrTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class IndexOpeningUiTest : AtlasTestBase() {
  @get:Rule val rule = createComposeRule()
  private val context = ApplicationProvider.getApplicationContext<Context>()

  @Test fun theFirstOpenShowsAnHonestWaitingState() {
    rule.setContent {
      FindrTheme { IndexMeterCard(IndexStatus(indexOpening = true), compact = false, onClick = {}) }
    }
    rule.onNodeWithText(context.getString(R.string.index_opening_title)).assertExists()
    rule.onNodeWithText(context.getString(R.string.index_opening_detail)).assertExists()
  }

  @Test fun anOpenFailureExplainsTheRetryAndOffersAWorkingButton() {
    var retries = 0
    val status = IndexStatus(phase = IndexPhase.ERROR, openProblem = IndexOpenProblem.BUSY)
    val actions = MeterActions({}, {}, {}, {}, {}, { retries++ })
    rule.setContent {
      FindrTheme { IndexDetailSheet(status, FindrSettings(), actions, {}) }
    }
    val title = context.getString(R.string.open_problem_title, context.getString(R.string.app_name))
    rule.onNodeWithText(title).assertExists()
    rule.onNodeWithText(context.getString(R.string.open_problem_detail)).assertExists()
    rule.onNodeWithText(context.getString(R.string.action_retry)).performScrollTo().performClick()
    rule.runOnIdle { assertEquals(1, retries) }
  }
}
