package trawl.desktop.shell

import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import trawl.desktop.Os

/**
 * The Linux raise against a real X server (Xvfb is enough, with or without a window manager): another window has the keyboard, the panel's
 * raise runs, and the panel must end up the focused window. Skipped where there is no X display, which is how it runs on a Mac or Windows
 * build machine. (macOS and Windows have no equivalent test here: their foreground rules need a real desktop session.)
 */
class PanelRaiseX11Test {
  private fun needX() {
    assumeTrue(!GraphicsEnvironment.isHeadless(), "needs a display")
    assumeTrue(!System.getenv("DISPLAY").isNullOrBlank() && System.getenv("WAYLAND_DISPLAY").isNullOrBlank(), "needs an X11 display")
    assumeTrue(PanelRaiseNative.xlib != null, "needs libX11")
  }

  private fun onEdt(block: () -> Unit) = SwingUtilities.invokeAndWait(block)

  private fun waitFor(what: String, ms: Long = 4000, cond: () -> Boolean): Boolean {
    val end = System.currentTimeMillis() + ms
    while (System.currentTimeMillis() < end) { if (cond()) return true; Thread.sleep(25) }
    return false
  }

  @Test fun aPanelBehindAnotherWindowComesBackWithTheKeyboard() {
    needX()
    lateinit var panel: Frame
    lateinit var other: Frame
    try {
      onEdt {
        panel = Frame("panel").apply { isUndecorated = true; setSize(400, 200); setLocation(100, 100); isAlwaysOnTop = true; isVisible = true }
      }
      // "Another app" appears afterwards and takes the keyboard.
      onEdt { other = Frame("other app").apply { setSize(500, 300); setLocation(80, 80); isVisible = true } }
      assumeTrue(waitFor("the other window to take focus") { other.isFocused }, "this X server never gave the other window focus, so there is nothing to win back")
      assertTrue(!panel.isFocused)

      val settled = CountDownLatch(1)
      val result = booleanArrayOf(false)
      val raiser = PanelRaiser.forWindow(Os.LINUX, onSettled = { _, focused -> result[0] = focused; settled.countDown() })
      raiser.raise(panel)
      assertTrue(settled.await(5, TimeUnit.SECONDS), "the raise reached a verdict")
      assertTrue(waitFor("the panel to be focused") { panel.isFocused }, "the panel has the keyboard after the raise (verdict focused=${result[0]})")
    } finally {
      onEdt { runCatching { panel.dispose() }; runCatching { other.dispose() } }
    }
  }

  @Test fun eachX11StepRunsWithoutThrowingAndAnswers() {
    needX()
    lateinit var f: Frame
    try {
      onEdt { f = Frame("step").apply { setSize(200, 100); isVisible = true } }
      waitFor("the window to be mapped") { f.isShowing }
      for (step in listOf(RaiseStep.X11_ACTIVATE_MESSAGE, RaiseStep.X11_RAISE_AND_FOCUS, RaiseStep.AWT_FRONT)) {
        val ok = PanelRaiseNative.run(step, f, Os.LINUX)
        assertTrue(ok, "$step reported success on a mapped window")
      }
    } finally { onEdt { runCatching { f.dispose() } } }
  }
}
