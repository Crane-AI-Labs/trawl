package com.craneai.findr.ui.search

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalFocusManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect

/**
 * A search left on screen with the keyboard put away, then the app left and reopened, used to jolt: the box still had focus, so when
 * the window came back the text field asked for the keyboard again, and the screen jumped from the full layout (header, meter, chips,
 * section headings) into the typing layout (compact rows, no headings). The box now lets go of focus as the app leaves when the
 * keyboard is already down, so the screen comes back as it was left. With the keyboard up the box keeps focus and it returns too
 * (nothing moves, the keyboard was part of the picture).
 */
internal object ResumeFocus {
  fun dropOnLeave(fieldFocused: Boolean, keyboardUp: Boolean): Boolean = fieldFocused && !keyboardUp
}

/** Applies [ResumeFocus] when the app pauses. The keyboard state is the last one composed while the app was in front: the system hides it after the pause. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ReleaseFocusWhenLeaving(fieldFocused: State<Boolean>) {
  val focus = LocalFocusManager.current
  val keyboardUp = rememberUpdatedState(WindowInsets.isImeVisible)
  LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) {
    if (ResumeFocus.dropOnLeave(fieldFocused.value, keyboardUp.value)) focus.clearFocus()
  }
}
