package com.craneai.findr.ui.search

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.focusable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The hold on the search box (see [refuseFocusWhile]) blocks the window's refocus on a return but never the person's own tap. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class)
class RefuseFocusTest {
  @get:Rule val rule = createComposeRule()

  private fun setBox(hold: androidx.compose.runtime.MutableState<Boolean>, focused: androidx.compose.runtime.MutableState<Boolean>, requester: FocusRequester) {
    rule.setContent {
      Box(
        Modifier.size(80.dp).testTag("box")
          .refuseFocusWhile(hold)
          .onFocusChanged { focused.value = it.isFocused }
          .focusRequester(requester)
          .focusable(),
      )
    }
  }

  @Test fun `a focus request from the system is refused while the hold is on`() {
    val hold = mutableStateOf(true); val focused = mutableStateOf(false); val requester = FocusRequester()
    setBox(hold, focused, requester)
    rule.runOnIdle { runCatching { requester.requestFocus() } }
    rule.waitForIdle()
    assertFalse(focused.value)
    assertTrue(hold.value)
  }

  @Test fun `the person's own tap ends the hold at once and takes focus`() {
    val hold = mutableStateOf(true); val focused = mutableStateOf(false); val requester = FocusRequester()
    setBox(hold, focused, requester)
    rule.onNodeWithTag("box").performClick()
    rule.waitForIdle()
    assertFalse(hold.value)
    // After the tap the box is allowed focus: a request now succeeds.
    rule.runOnIdle { requester.requestFocus() }
    rule.waitForIdle()
    assertTrue(focused.value)
  }

  @Test fun `with no hold the box takes focus as usual`() {
    val hold = mutableStateOf(false); val focused = mutableStateOf(false); val requester = FocusRequester()
    setBox(hold, focused, requester)
    rule.runOnIdle { requester.requestFocus() }
    rule.waitForIdle()
    assertEquals(true, focused.value)
  }
}
