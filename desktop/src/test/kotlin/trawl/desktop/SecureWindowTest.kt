package trawl.desktop

import java.awt.Frame
import java.awt.GraphicsEnvironment
import kotlin.test.Test
import kotlin.test.assertEquals
import trawl.desktop.privacy.CaptureResult
import trawl.desktop.privacy.SecureWindow

/** Only what runs without a real Windows or macOS window: the native calls themselves are checked by hand on those systems. */
class SecureWindowTest {
  @Test fun `no window means nothing to do`() {
    assertEquals(CaptureResult.UNSUPPORTED, SecureWindow.apply(null, true, Os.WINDOWS))
    assertEquals(CaptureResult.UNSUPPORTED, SecureWindow.apply(null, false, Os.MAC))
  }

  @Test fun `a window that is not on screen yet is left alone`() {
    if (GraphicsEnvironment.isHeadless()) return
    val f = Frame()
    assertEquals(CaptureResult.UNSUPPORTED, SecureWindow.apply(f, true, Os.MAC))
  }

  @Test fun `linux says it cannot and changes nothing`() {
    assertEquals(CaptureResult.UNSUPPORTED, SecureWindow.apply(null, true, Os.LINUX))
  }
}
