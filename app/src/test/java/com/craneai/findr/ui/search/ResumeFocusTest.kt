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
}
