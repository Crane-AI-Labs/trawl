package trawl.desktop.privacy

import java.awt.KeyboardFocusManager
import java.beans.PropertyChangeListener

/**
 * "Trawl's own dialogs are not leaving." The main window and the quick panel report their own focus to [PrivacyController.onWindowFocus],
 * but a folder chooser or file dialog is another window of the same process: while it is up the main window has lost the focus for as long as
 * the person browses, which is far more than the settle time, so "I leave Trawl" locked the window behind it (Windows and Ubuntu, matrix
 * 2026-10-22; on Ubuntu Settings also fell back to its first page). AWT knows which window of this process has the keyboard
 * (`focusedWindow`); it is null when another application has it. That is reported as one more owner, [OWNER]: any Trawl window with the focus
 * counts as being in Trawl, and only the focus leaving the process altogether can start the "left" timer.
 */
object OwnWindowFocus {
  const val OWNER = "own"

  /** Starts reporting. Returns a function that stops it. */
  fun install(controller: PrivacyController, kfm: KeyboardFocusManager = KeyboardFocusManager.getCurrentKeyboardFocusManager()): () -> Unit {
    val listener = PropertyChangeListener { e -> report(controller, e.newValue) }
    kfm.addPropertyChangeListener("focusedWindow", listener)
    return { kfm.removePropertyChangeListener("focusedWindow", listener) }
  }

  /** [focusedWindow] is what AWT says now: a window of this process, or null. */
  fun report(controller: PrivacyController, focusedWindow: Any?) = controller.onWindowFocus(OWNER, focusedWindow != null)
}
