package com.craneai.findr.ui.common

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Looper
import com.craneai.findr.core.FileKind
import com.craneai.findr.core.IndexedFile
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** B6 (UX bugs 1.0.5): a double tap on Open must start one "Open with" chooser, not two. */
class LaunchGateRuleTest {
  @Test fun `a second launch inside the window is refused, one after it goes through`() {
    val gate = LaunchGate(windowMs = 1_000)
    assertTrue(gate.allow(10_000))
    assertFalse(gate.allow(10_001))
    assertFalse(gate.allow(10_999))
    assertTrue(gate.allow(11_000))
  }

  @Test fun `a refused tap does not extend the window`() {
    val gate = LaunchGate(windowMs = 1_000)
    assertTrue(gate.allow(0))
    assertFalse(gate.allow(900))
    assertTrue(gate.allow(1_000))   // counted from the launch that went through, not from the tap that was refused
  }

  @Test fun `the first launch is always allowed, whatever the clock reads`() {
    assertTrue(LaunchGate().allow(0))
    assertTrue(LaunchGate().allow(5))
  }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DoubleTapOpenTest {
  @Before fun freshGate() = LaunchGate.shared.reset()

  private fun file(id: Long) = IndexedFile(id, "content://media/external/file/$id", "Download/", "report$id.pdf", FileKind.PDF, "application/pdf", 1000, 1000)

  // The app starts these from an Activity (no NEW_TASK flag), so the test does too.
  private val activity: Activity = Robolectric.buildActivity(Activity::class.java).create().get()

  private fun launches(): Int {
    val shadow = shadowOf(activity)
    var n = 0
    while (shadow.nextStartedActivity != null) n++
    return n
  }

  private fun later(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

  @Test fun `two taps on Open a moment apart start one chooser`() {
    val app: Context = activity
    later(5_000)
    assertEquals(OpenResult.OPENED, FileActions.open(app, file(1)))
    later(93)
    assertEquals(OpenResult.OPENED, FileActions.open(app, file(1)))
    assertEquals(1, launches())
  }

  @Test fun `Open works again once the window has passed`() {
    val app: Context = activity
    later(5_000)
    FileActions.open(app, file(1))
    later(LaunchGate.WINDOW_MS + 100)
    FileActions.open(app, file(1))
    assertEquals(2, launches())
  }

  @Test fun `Open then Share uses the same launch window`() {
    FileActions.open(activity, file(1))
    later(93)
    FileActions.share(activity, file(1))
    assertEquals(1, launches())
  }

  @Test fun `Share then Open uses the same launch window`() {
    FileActions.share(activity, file(1))
    later(93)
    FileActions.open(activity, file(1))
    assertEquals(1, launches())
  }

  @Test fun `Share and Show folder are held by the same gate`() {
    val app: Context = activity
    later(5_000)
    FileActions.share(app, file(2))
    later(100)
    FileActions.share(app, file(2))
    FileActions.shareAll(app, listOf(file(2), file(3)))
    FileActions.showInFolder(app, file(2))
    assertEquals(1, launches())
  }

  @Test fun `Copy is not held, it starts nothing`() {
    val app: Context = activity
    later(5_000)
    FileActions.open(app, file(1))
    // copy never goes through the gate: it must still answer with COPIED or NO_APP, never be swallowed as OPENED
    assertTrue(FileActions.copy(app, file(1)) != OpenResult.OPENED)
  }
}
