package trawl.desktop

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

@OptIn(ExperimentalTestApi::class)
class ComposeTestHarnessTest {
  @Test fun offscreenRenderingAndBackgroundEffectsStayOnTheUiThread() {
    val uiThread = onComposeUiThread { Thread.currentThread() }
    val threads = ConcurrentLinkedQueue<Thread>()
    val value = MutableStateFlow(0)
    val rendered = AtomicInteger(-1)
    val resumed = CountDownLatch(1)
    val disposed = AtomicBoolean(false)
    val scene = TestImageComposeScene(width = 160, height = 80) {
      val current by value.collectAsState()
      SideEffect { threads.add(Thread.currentThread()); rendered.set(current) }
      LaunchedEffect(Unit) {
        withContext(Dispatchers.Default) { assertTrue(Thread.currentThread() !== uiThread) }
        threads.add(Thread.currentThread())
        resumed.countDown()
      }
      DisposableEffect(Unit) {
        threads.add(Thread.currentThread())
        onDispose { threads.add(Thread.currentThread()); disposed.set(true) }
      }
      BasicText("value $current")
    }
    try {
      scene.render(0).close()
      runBlocking(Dispatchers.Default) { value.value = 1 }
      val deadline = System.nanoTime() + 5_000_000_000L
      var frame = 0L
      while ((rendered.get() != 1 || resumed.count != 0L) && System.nanoTime() < deadline) {
        frame += 16_000_000L
        scene.render(frame).close()
        Thread.sleep(5)
      }
      assertEquals(1, rendered.get())
      assertEquals(0L, resumed.count)
    } finally { scene.close() }
    assertTrue(disposed.get())
    assertTrue(threads.isNotEmpty() && threads.all { it === uiThread }, "scene lifecycle, recomposition and effect resumption share the EDT: $threads")
  }

  @Test fun semanticsAndBackgroundEffectsStayOnTheUiThread() {
    val uiThread = onComposeUiThread { Thread.currentThread() }
    val threads = ConcurrentLinkedQueue<Thread>()
    val value = MutableStateFlow("before")
    val resumed = CountDownLatch(1)
    val disposed = AtomicBoolean(false)
    runComposeUiTest {
      threads.add(Thread.currentThread())
      setContent {
        val current by value.collectAsState()
        SideEffect { threads.add(Thread.currentThread()) }
        LaunchedEffect(Unit) {
          withContext(Dispatchers.Default) { assertTrue(Thread.currentThread() !== uiThread) }
          threads.add(Thread.currentThread())
          resumed.countDown()
        }
        DisposableEffect(Unit) {
          onDispose { threads.add(Thread.currentThread()); disposed.set(true) }
        }
        BasicText(current)
      }
      runBlocking(Dispatchers.Default) { value.value = "after" }
      waitUntil(timeoutMillis = 5000) {
        onAllNodesWithText("after").fetchSemanticsNodes().isNotEmpty() && resumed.count == 0L
      }
      onNodeWithText("after").assertExists()
    }
    assertTrue(disposed.get())
    assertTrue(threads.isNotEmpty() && threads.all { it === uiThread }, "test body, recomposition, effect resumption and disposal share the EDT: $threads")
  }

  @Test fun sizedDesktopTestsStayOnTheUiThread() {
    val uiThread = onComposeUiThread { Thread.currentThread() }
    runDesktopComposeUiTest(width = 160, height = 80) {
      assertSame(uiThread, Thread.currentThread())
      setContent { BasicText("desktop") }
      onNodeWithText("desktop").assertExists()
    }
  }

  @Test fun uiCallsAreReentrantAndPreserveTheOriginalFailure() {
    onComposeUiThread {
      assertSame(Thread.currentThread(), onComposeUiThread { Thread.currentThread() })
    }
    val original = AssertionError("original assertion")
    assertSame(original, assertFailsWith<AssertionError> { onComposeUiThread { throw original } })
    assertEquals(original.message, assertFailsWith<AssertionError> { runComposeUiTest { throw original } }.message)
  }
}
