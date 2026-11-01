package com.craneai.findr.ui.search

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import com.craneai.findr.core.FindrLog as Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
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

  /** Leaving with the keyboard put away: the box must also refuse focus while the window comes back (see [ReleaseFocusWhenLeaving]). */
  fun holdOnLeave(keyboardUp: Boolean): Boolean = !keyboardUp

  /** How long after the app is resumed the box still refuses focus: the window-focus change that used to hand it focus lands within a few frames. */
  const val HOLD_AFTER_RESUME_MS = 700L
}

/** Applies [ResumeFocus] when the app pauses. The keyboard state is the last one composed while the app was in front: the system hides it after the pause. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ReleaseFocusWhenLeaving(fieldFocused: State<Boolean>): MutableState<Boolean> {
  val focus = LocalFocusManager.current
  val keyboardUp = rememberUpdatedState(WindowInsets.isImeVisible)
  val hold = remember { mutableStateOf(false) }
  val scope = rememberCoroutineScope()
  LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) {
    val drop = ResumeFocus.dropOnLeave(fieldFocused.value, keyboardUp.value)
    if (drop) focus.clearFocus()
    if (ResumeFocus.holdOnLeave(keyboardUp.value)) hold.value = true
    Log.i("FindrResume", "pause: focused=${fieldFocused.value} keyboardUp=${keyboardUp.value} drop=$drop hold=${hold.value}")
  }
  LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
    Log.i("FindrResume", "resume: focused=${fieldFocused.value} hold=${hold.value}")
    if (hold.value) scope.launch {
      delay(ResumeFocus.HOLD_AFTER_RESUME_MS)
      hold.value = false
      Log.i("FindrResume", "hold released: focused=${fieldFocused.value}")
    }
  }
  return hold
}

/**
 * The box refuses focus while [hold] is true (the window handing focus back after a return), but a finger on the box ends the hold at once
 * and the tap takes focus as usual: only focus the system or the window asks for is blocked, never the person's own touch. The press is seen on
 * the initial pass, before the field's own tap handling, and [hold] drops in the same snapshot, so the field's focus target is already
 * focusable when the tap lifts.
 */
internal fun Modifier.refuseFocusWhile(hold: MutableState<Boolean>): Modifier =
  pointerInput(hold) {
    awaitPointerEventScope {
      while (true) {
        val event = awaitPointerEvent(PointerEventPass.Initial)
        if (hold.value && event.changes.any { it.pressed }) hold.value = false
      }
    }
  }.focusProperties { canFocus = !hold.value }
