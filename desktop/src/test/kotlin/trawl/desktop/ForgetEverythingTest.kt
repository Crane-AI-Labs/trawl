package trawl.desktop

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runComposeUiTest
import com.craneai.findr.core.KindFilter
import com.craneai.findr.search.VectorFile
import java.awt.EventQueue
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import trawl.desktop.ui.SettingsScreen
import trawl.desktop.ui.SunlightTheme

@OptIn(ExperimentalTestApi::class)
class ForgetEverythingTest {
  private val uiDispatcher = object : CoroutineDispatcher() {
    override fun dispatch(context: CoroutineContext, block: Runnable) = EventQueue.invokeLater(block)
  }

  @BeforeTest fun english() = Strings.use(Locale.ENGLISH)

  private fun controller(data: File, shred: (File) -> Long) = AppController(
    data, Platform.UserFolders(File(data, "home"), File(data, "docs"), File(data, "downloads")),
    logSink = {}, scope = CoroutineScope(SupervisorJob() + uiDispatcher), shredFile = shred,
  )

  private fun awaitReady(controller: AppController) = runBlocking {
    withTimeout(30_000) { controller.service.ui.first { !it.opening && it.openProblem == null } }
  }

  private fun awaitForgotten(controller: AppController) = runBlocking {
    withTimeout(30_000) { controller.forgetting.first { !it } }
  }

  private fun onUi(block: () -> Unit) {
    val returned = CountDownLatch(1)
    val failure = AtomicReference<Throwable>()
    EventQueue.invokeLater { try { block() } catch (caught: Throwable) { failure.set(caught) } finally { returned.countDown() } }
    assertTrue(returned.await(10, TimeUnit.SECONDS), "the UI event must return while shredding is held")
    failure.get()?.let { throw it }
  }

  @Test fun slowShredDoesNotBlockUiAndAnotherClickIsIgnored() = runComposeUiTest {
    val data = Files.createTempDirectory("trawl-forget-ui").toFile()
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val calls = AtomicInteger()
    val controller = controller(data) { file ->
      assertFalse(EventQueue.isDispatchThread(), "shredding must never run on the UI thread")
      calls.incrementAndGet()
      entered.countDown()
      check(release.await(30, TimeUnit.SECONDS))
      VectorFile.shred(file)
    }
    try {
      awaitReady(controller)
      setContent { SunlightTheme(ThemeChoice.LIGHT) { SettingsScreen(controller) } }
      onUi { controller.forgetEverything() }
      assertTrue(entered.await(10, TimeUnit.SECONDS), "the background shred started")
      assertTrue(controller.forgetting.value)
      assertNull(controller.forgot.value, "no success is claimed while the wipe is running")
      onNodeWithText("Forgetting…").performScrollTo().assertIsNotEnabled()
      onUi {
        controller.forgetEverything()
        controller.setQuery("the window still responds")
      }
      assertEquals("the window still responds", controller.query.value)
      assertTrue(controller.service.recent(KindFilter.ALL).isEmpty())
      assertTrue(controller.searchApi.recent(KindFilter.ALL).files.isEmpty())
      assertNull(controller.service.detail())
      assertEquals(1, calls.get(), "the repeated click did not start a second wipe")
      release.countDown()
      awaitForgotten(controller)
      assertNotNull(controller.forgot.value)
      waitForIdle()
      onNodeWithText(t("settings_forget")).performScrollTo().assertExists()
    } finally {
      release.countDown()
      controller.close()
      data.deleteRecursively()
    }
  }

  @Test fun failedShredKeepsTheFileAndIndexClosedUntilRetry() {
    val data = Files.createTempDirectory("trawl-forget-failed").toFile()
    val calls = AtomicInteger()
    val controller = controller(data) { file ->
      if (calls.incrementAndGet() == 1) throw IOException("injected shred failure")
      VectorFile.shred(file)
    }
    try {
      awaitReady(controller)
      val existing = File(data, "index").listFiles()!!.filter { it.isFile }.map { it.name }.toSet()
      onUi { controller.forgetEverything() }
      awaitForgotten(controller)
      assertNull(controller.forgot.value)
      assertNull(controller.service.detail(), "a failed shred must not reopen the index")
      assertTrue(File(data, "index").listFiles()!!.any { it.name in existing }, "an unshredded file remains for retry")
      assertTrue(controller.notice.value?.contains("could not finish forgetting") == true)
      onUi { controller.service.retryOpen() }
      assertNull(controller.service.detail(), "Retry open must not bypass the unfinished wipe")
      onUi { controller.forgetEverything() }
      awaitForgotten(controller)
      assertNotNull(controller.forgot.value)
      assertNotNull(controller.service.detail())
    } finally { controller.close(); data.deleteRecursively() }
  }

  @Test fun freshIndexKeepsPrivacyMarksModelsAndChosenFolders() {
    val data = Files.createTempDirectory("trawl-forget-privacy").toFile()
    val root = File(data, "chosen").apply { mkdirs() }
    val document = File(root, "private-note.txt").apply { writeText("orchids in the sunshine") }
    val model = File(data, "models/kept-model.bin").apply { parentFile.mkdirs(); writeText("model data") }
    val controller = controller(data, VectorFile::shred)
    try {
      onUi { controller.addFolder(root) }
      runBlocking { withTimeout(30_000) { controller.service.ui.first { it.phase == Phase.UP_TO_DATE && it.total == 1 } } }
      val file = controller.service.recent(KindFilter.ALL).single()
      onUi { assertEquals(1, controller.privacy.mark(listOf(file.id), true)) }
      val rules = controller.privacy.settings.value.rules
      val folders = controller.settings.value.folders
      onUi { controller.forgetEverything() }
      awaitForgotten(controller)
      assertNotNull(controller.forgot.value)
      runBlocking { withTimeout(30_000) { controller.service.ui.first { it.phase == Phase.UP_TO_DATE && it.total == 1 } } }
      assertEquals(folders, controller.settings.value.folders)
      assertEquals(rules, controller.privacy.settings.value.rules)
      assertEquals("model data", model.readText())
      assertEquals("orchids in the sunshine", document.readText())
      assertTrue(controller.service.recent(KindFilter.ALL, withSensitive = true).single().sensitive)
      assertTrue(controller.service.recent(KindFilter.ALL).isEmpty(), "rebuilding must not make a marked file findable")
    } finally { controller.close(); data.deleteRecursively() }
  }
}
