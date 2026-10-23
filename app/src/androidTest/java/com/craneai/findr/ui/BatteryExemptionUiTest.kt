package com.craneai.findr.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.craneai.findr.R
import com.craneai.findr.ui.background.BatteryExemption
import com.craneai.findr.ui.background.BatteryExemption.State
import com.craneai.findr.ui.background.BatteryExemptionBody
import com.craneai.findr.ui.background.BatteryExemptionControl
import com.craneai.findr.ui.theme.FindrTheme
import com.craneai.findr.ui.theme.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The background step's content (the Settings row and the setup page share it): the Allow button while it is not on, the tick and
 * "Allowed" once it is, the plain reason after a no. The state is handed in, so no dialog is opened and no setting is touched; the
 * real system box needs a phone and a person (see internal/research/setup-background-step.md for what was and was not observed).
 * Runs as ./gradlew :app:connectedDebugAndroidTest -PappIdSuffix=.sunlightui (a side build, never the tester's app).
 */
@RunWith(AndroidJUnit4::class)
class BatteryExemptionUiTest {
  @get:Rule val rule = createComposeRule()
  private val context = InstrumentationRegistry.getInstrumentation().targetContext

  private fun show(content: @Composable () -> Unit) {
    rule.setContent { FindrTheme(ThemeMode.LIGHT) { Box(Modifier.width(360.dp)) { content() } } }
  }

  private fun text(id: Int) = context.getString(id)

  @Test fun askShowsTheAllowButtonAndPressingItRequests() {
    var presses = 0
    show { BatteryExemptionBody(BatteryExemptionControl(State.ASK) { presses++ }) }
    rule.onNodeWithText(text(R.string.bgx_allow)).assertIsDisplayed().assertHasClickAction().assertHeightIsAtLeast(56.dp).performClick()
    rule.runOnIdle { assertEquals(1, presses) }
  }

  @Test fun allowedShowsTheTickWordAndNoButton() {
    show { BatteryExemptionBody(BatteryExemptionControl(State.ALLOWED) {}) }
    rule.onNodeWithText(text(R.string.bgx_granted)).assertIsDisplayed()
    assertEquals(0, rule.onAllNodesWithText(text(R.string.bgx_allow)).fetchSemanticsNodes().size)
  }

  @Test fun deniedSaysSoAndKeepsTheButtonForAnotherTry() {
    show { BatteryExemptionBody(BatteryExemptionControl(State.DENIED) {}) }
    rule.onNodeWithText(text(R.string.bgx_denied)).assertIsDisplayed()
    rule.onNodeWithText(text(R.string.bgx_allow)).assertIsDisplayed()
  }

  @Test fun thePageOpenedHintShowsAfterTheBatteryPageFallback() {
    show { BatteryExemptionBody(BatteryExemptionControl(State.PAGE_OPENED) {}) }
    rule.onNodeWithText(text(R.string.bgx_page_hint)).assertIsDisplayed()
  }

  @Test fun theStateFollowsTheLiveAnswerWhenTheBodyIsRecomposed() {
    var state by mutableStateOf(State.ASK)
    show { BatteryExemptionBody(BatteryExemptionControl(state) {}) }
    rule.onNodeWithText(text(R.string.bgx_allow)).assertIsDisplayed()
    rule.runOnIdle { state = BatteryExemption.state(exempt = true, tried = BatteryExemption.Tried.DIALOG) }
    rule.onNodeWithText(text(R.string.bgx_granted)).assertIsDisplayed()
  }
}
