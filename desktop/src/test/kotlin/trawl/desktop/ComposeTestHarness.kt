package trawl.desktop

import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest as runQueuedComposeUiTest
import androidx.compose.ui.test.v2.runDesktopComposeUiTest as runQueuedDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import javax.swing.SwingUtilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.swing.Swing
import org.jetbrains.skia.Image

internal fun <Result> onComposeUiThread(block: () -> Result): Result {
  if (SwingUtilities.isEventDispatchThread()) return block()
  val task = FutureTask(block)
  SwingUtilities.invokeAndWait(task)
  return try { task.get() } catch (failure: ExecutionException) { throw failure.cause!! }
}

@OptIn(ExperimentalTestApi::class)
internal fun runComposeUiTest(block: suspend ComposeUiTest.() -> Unit) = onComposeUiThread {
  runQueuedComposeUiTest(block = block)
}

@OptIn(ExperimentalTestApi::class)
internal fun runDesktopComposeUiTest(width: Int = 1024, height: Int = 768, block: suspend DesktopComposeUiTest.() -> Unit) = onComposeUiThread {
  runQueuedDesktopComposeUiTest(width = width, height = height, block = block)
}

@OptIn(ExperimentalComposeUiApi::class)
internal class TestImageComposeScene(width: Int, height: Int, density: Density = Density(1f), content: @Composable () -> Unit) : AutoCloseable {
  private val scene = onComposeUiThread {
    ImageComposeScene(width = width, height = height, density = density, coroutineContext = Dispatchers.Swing, content = content)
  }

  fun render(nanoTime: Long): Image = onComposeUiThread { scene.render(nanoTime) }

  override fun close() = onComposeUiThread { scene.close() }
}
