package trawl.desktop.shell

import java.awt.Window
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities
import trawl.desktop.Os

/**
 * One thing the panel tries to come to the front and take the keyboard. The names say what each does; [PanelRaiseNative] holds the calls.
 * Kept as plain data so the plan for each system and each retry is tested without a window or a native library.
 */
enum class RaiseStep {
  /** Java's own: the app asks to be foreground, then the window is moved to the front and asked for focus. Works when nothing else is in the way. */
  AWT_FRONT,

  /** macOS: let the panel appear on whatever Space is showing (also over another app's full-screen Space) and sit above ordinary windows. */
  MAC_JOIN_SPACES,

  /** macOS: NSApp activateIgnoringOtherApps:YES, on the main thread. The step that makes another app give up the keyboard. */
  MAC_ACTIVATE,

  /** macOS: the same through NSRunningApplication, which the system sometimes honours when the first is ignored. */
  MAC_ACTIVATE_WORKSPACE,

  /** macOS: make the panel the key window and put it in front, whoever is active. */
  MAC_ORDER_FRONT,

  /** Windows: tell the system this process may take the foreground (AllowSetForegroundWindow), then SetForegroundWindow. */
  WIN_ALLOW_AND_FOREGROUND,

  /** Windows: share the foreground window's input queue for a moment (AttachThreadInput) so SetForegroundWindow is not refused. */
  WIN_ATTACH_INPUT,

  /** Windows: a harmless key event (an unassigned virtual key) so the system counts Trawl as having just had input, then SetForegroundWindow. */
  WIN_INPUT_NUDGE,

  /** Windows: SetWindowPos to the top of the topmost band, shown without activating anything else. */
  WIN_TOPMOST,

  /** Linux X11: the window manager's own request, a _NET_ACTIVE_WINDOW message from a "pager" (source 2, which managers honour over focus-stealing prevention). */
  X11_ACTIVATE_MESSAGE,

  /** Linux X11: XRaiseWindow, then XSetInputFocus. Works with no window manager at all, and as the fallback with one. */
  X11_RAISE_AND_FOCUS,
}

/** What to try, how long to wait for the system to answer, and when to give up. Pure. */
object RaiseLadder {
  /** The first try plus three retries, each stronger or simply later (the system may still be switching away from the other app). */
  const val ATTEMPTS = 4

  private val delays = longArrayOf(70, 140, 280, 500)

  /** How long after attempt [attempt] to check whether the panel got the keyboard. Null: no more attempts. */
  fun checkAfterMs(attempt: Int): Long? = delays.getOrNull(attempt)

  /**
   * [overlay] is the quick panel: it floats over full-screen apps and every Space. The main window is an ordinary window, so it skips the
   * macOS step that changes the window's level and the Spaces it lives in.
   */
  fun steps(os: Os, attempt: Int, overlay: Boolean = true): List<RaiseStep> = when (os) {
    Os.MAC -> when {
      attempt == 0 -> listOfNotNull(RaiseStep.MAC_JOIN_SPACES.takeIf { overlay }, RaiseStep.MAC_ACTIVATE, RaiseStep.MAC_ORDER_FRONT, RaiseStep.AWT_FRONT)
      else -> listOf(RaiseStep.MAC_ACTIVATE_WORKSPACE, RaiseStep.MAC_ACTIVATE, RaiseStep.MAC_ORDER_FRONT, RaiseStep.AWT_FRONT)
    }
    Os.WINDOWS -> when (attempt) {
      0 -> listOf(RaiseStep.AWT_FRONT, RaiseStep.WIN_ALLOW_AND_FOREGROUND)
      1 -> listOf(RaiseStep.WIN_ATTACH_INPUT, RaiseStep.WIN_TOPMOST)
      else -> listOf(RaiseStep.WIN_INPUT_NUDGE, RaiseStep.WIN_ATTACH_INPUT, RaiseStep.WIN_TOPMOST, RaiseStep.AWT_FRONT)
    }
    Os.LINUX -> when (attempt) {
      0 -> listOf(RaiseStep.X11_ACTIVATE_MESSAGE, RaiseStep.X11_RAISE_AND_FOCUS, RaiseStep.AWT_FRONT)
      else -> listOf(RaiseStep.X11_RAISE_AND_FOCUS, RaiseStep.X11_ACTIVATE_MESSAGE, RaiseStep.AWT_FRONT)
    }
  }
}

/** Runs one [RaiseStep] on a window. Implemented by [PanelRaiseNative]; a fake in tests. Must not throw (a failure is just "false"). */
fun interface RaiseNative {
  fun run(step: RaiseStep, window: Window): Boolean
}

/**
 * Brings the quick panel to the front and keeps trying, within reason, until it really has the keyboard.
 *
 * The first attempt runs at once (the panel is shown the instant the shortcut fires). Because a JVM window often does not get focus while
 * another app is active, and the system answers a moment later, each attempt is followed by a check ([hasFocus]) after a short wait; if
 * the panel still does not have the keyboard, the next, stronger attempt runs. A newer [raise] (the shortcut pressed again) or [cancel]
 * (the panel closed) ends the old sequence. [onSettled] is told how it ended, for the diagnostics log: the number of attempts used and
 * whether the panel had focus at the end.
 */
class PanelRaiser(
  private val os: Os,
  private val native: RaiseNative,
  private val hasFocus: () -> Boolean,
  private val later: (delayMs: Long, work: () -> Unit) -> Unit,
  private val onSettled: (attempts: Int, focused: Boolean) -> Unit = { _, _ -> },
  private val overlay: Boolean = true,
) {
  @Volatile private var generation = 0

  fun raise(window: Window) {
    val gen = ++generation
    attempt(window, 0, gen)
  }

  fun cancel() { generation++ }

  private fun attempt(window: Window, n: Int, gen: Int) {
    if (gen != generation) return
    for (s in RaiseLadder.steps(os, n, overlay)) runCatching { native.run(s, window) }
    val wait = RaiseLadder.checkAfterMs(n) ?: return
    later(wait) {
      if (gen != generation) return@later
      val ok = runCatching { hasFocus() }.getOrDefault(false)
      when {
        ok -> onSettled(n + 1, true)
        n + 1 >= RaiseLadder.ATTEMPTS -> onSettled(n + 1, false)
        else -> attempt(window, n + 1, gen)
      }
    }
  }

  companion object {
    private val timer = Executors.newSingleThreadScheduledExecutor(ThreadFactory { r -> Thread(r, "panel-raise").apply { isDaemon = true } })

    /** The real thing: the native steps, the AWT focus owner as the check, and the retries run on the UI thread after a delay. */
    fun forWindow(os: Os, onSettled: (Int, Boolean) -> Unit = { _, _ -> }, overlay: Boolean = true): PanelRaiser {
      var current: Window? = null
      return PanelRaiser(
        os, RaiseNative { step, w -> current = w; PanelRaiseNative.run(step, w, os) },
        hasFocus = { current?.let { it.isFocused || it.isActive } ?: false },
        later = { ms, work -> timer.schedule({ SwingUtilities.invokeLater(work) }, ms, TimeUnit.MILLISECONDS) },
        onSettled = onSettled, overlay = overlay,
      )
    }
  }
}
