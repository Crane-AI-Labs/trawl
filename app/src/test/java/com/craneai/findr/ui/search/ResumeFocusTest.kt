package com.craneai.findr.ui.search

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResumeFocusTest {
  @Test fun `a focused box with the keyboard put away lets go as the app leaves`() {
    // Otherwise the field asks for the keyboard when the window returns and the screen jumps into the typing layout.
    assertTrue(ResumeFocus.dropOnLeave(fieldFocused = true, keyboardUp = false))
  }

  @Test fun `a focused box with the keyboard up keeps its focus`() {
    assertFalse(ResumeFocus.dropOnLeave(fieldFocused = true, keyboardUp = true))
  }

  @Test fun `a box without focus has nothing to let go of`() {
    assertFalse(ResumeFocus.dropOnLeave(fieldFocused = false, keyboardUp = false))
    assertFalse(ResumeFocus.dropOnLeave(fieldFocused = false, keyboardUp = true))
  }

  @Test fun `leaving with the keyboard put away holds the box off focus until the window is back`() {
    assertTrue(ResumeFocus.holdOnLeave(keyboardUp = false))
  }

  @Test fun `leaving with the keyboard up never holds the box off focus`() {
    assertFalse(ResumeFocus.holdOnLeave(keyboardUp = true))
  }
}
