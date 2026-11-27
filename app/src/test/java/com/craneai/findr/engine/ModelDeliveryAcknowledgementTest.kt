package com.craneai.findr.engine

import android.content.Context
import android.os.Environment
import androidx.test.core.app.ApplicationProvider
import com.craneai.findr.core.DeliveryPhase
import com.craneai.findr.core.Refusal
import java.io.File
import java.io.InputStream
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Resetter
import org.robolectric.shadows.ShadowEnvironment
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(
  sdk = [35],
  application = android.app.Application::class,
  shadows = [ModelDeliveryDownloadsEnvironment::class, AlreadyInstalledBundleInstaller::class],
)
class ModelDeliveryAcknowledgementTest {
  @get:Rule val tmp = TemporaryFolder()
  private val context = ApplicationProvider.getApplicationContext<Context>()
  private val scope = CoroutineScope(SupervisorJob())
  private lateinit var downloads: File

  @Before fun setUp() {
    AlreadyInstalledBundleInstaller.reset()
    ShadowEnvironment.setExternalStoragePublicDirectory(tmp.root.toPath())
    downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).apply { mkdirs() }
    val models = File(context.filesDir, "models").apply { mkdirs() }
    File(models, Embedder.STOCK_NAMES.first()).writeText("installed")
    InstalledBundle("trawl-models", BundleClass.STANDARD, 1).write(models)
  }

  @After fun stop() = scope.cancel()

  private fun bundle(name: String = "trawl-models.zip", contents: String = "already-installed"): File = File(downloads, name).apply {
    val bytes = contents.toByteArray()
    ZipOutputStream(outputStream()).use { zip ->
      zip.putNextEntry(ZipEntry("bundle.txt").apply {
        method = ZipEntry.STORED
        size = bytes.size.toLong()
        compressedSize = size
        crc = CRC32().apply { update(bytes) }.value
        time = 0L
      })
      zip.write(bytes)
      zip.closeEntry()
    }
    setLastModified(System.currentTimeMillis() - DownloadsWatch.STABLE_MS * 2)
  }

  private fun poll(delivery: ModelDelivery) = runBlocking {
    delivery.pollOnce()
    scope.coroutineContext[Job]!!.children.forEach { it.join() }
  }

  private fun settle(delivery: ModelDelivery) {
    val since = System.currentTimeMillis() - DownloadsWatch.STABLE_MS * 2
    ReflectionHelpers.setField(delivery, "previous", downloads.listFiles().orEmpty().associate { file ->
      file.name to Seen(file.length(), file.lastModified(), since)
    })
  }

  private fun acknowledgeBundle(): ModelDelivery {
    val delivery = ModelDelivery(context, scope, MemoryProfile.STANDARD) {}
    poll(delivery)
    assertEquals(DeliveryPhase.DONE, delivery.state.value.phase)
    assertEquals(Refusal.ALREADY_INSTALLED, delivery.state.value.refusal)
    settle(delivery)
    delivery.acknowledge()
    return delivery
  }

  @Test fun anAlreadyInstalledBundleStaysAcknowledgedWhileItRemainsInDownloads() {
    bundle()
    val delivery = ModelDelivery(context, scope, MemoryProfile.STANDARD) {}
    poll(delivery)
    assertEquals(DeliveryPhase.DONE, delivery.state.value.phase)
    assertEquals(Refusal.ALREADY_INSTALLED, delivery.state.value.refusal)
    assertEquals(1, AlreadyInstalledBundleInstaller.calls.get())
    assertTrue(delivery.state.value.searchModelInstalled)

    settle(delivery)
    delivery.acknowledge()
    repeat(3) { poll(delivery) }

    assertEquals(DeliveryPhase.IDLE, delivery.state.value.phase)
    assertNull(delivery.state.value.refusal)
    assertNull(delivery.state.value.removable)
    assertEquals(1, AlreadyInstalledBundleInstaller.calls.get())
  }

  @Test fun acknowledgementSurvivesDeliveryRecreation() {
    bundle()
    acknowledgeBundle()

    val recreated = ModelDelivery(context, scope, MemoryProfile.STANDARD) {}
    repeat(3) { poll(recreated) }

    assertEquals(DeliveryPhase.IDLE, recreated.state.value.phase)
    assertNull(recreated.state.value.refusal)
    assertNull(recreated.state.value.removable)
    assertEquals(2, AlreadyInstalledBundleInstaller.calls.get())
  }

  @Test fun changingOnlyTheTimestampDoesNotUndoAcknowledgement() {
    val file = bundle()
    acknowledgeBundle()
    file.setLastModified(file.lastModified() - 1_000)

    val recreated = ModelDelivery(context, scope, MemoryProfile.STANDARD) {}
    poll(recreated)

    assertEquals(DeliveryPhase.IDLE, recreated.state.value.phase)
    assertNull(recreated.state.value.removable)
  }

  @Test fun aDifferentHashIsOfferedEvenWithTheSameNameSizeAndTimestamp() {
    val file = bundle()
    val bytes = file.length()
    val modified = file.lastModified()
    acknowledgeBundle()
    bundle(contents = "already-installEd").setLastModified(modified)
    assertEquals(bytes, file.length())
    assertEquals(modified, file.lastModified())

    val recreated = ModelDelivery(context, scope, MemoryProfile.STANDARD) {}
    poll(recreated)

    assertEquals(DeliveryPhase.DONE, recreated.state.value.phase)
    assertEquals(Refusal.ALREADY_INSTALLED, recreated.state.value.refusal)
    assertEquals(file.name, recreated.state.value.removable?.name)
  }

  @Test fun aDifferentSizeIsOfferedAgain() {
    bundle()
    acknowledgeBundle()
    val replacement = bundle(contents = "another already-installed bundle")

    val recreated = ModelDelivery(context, scope, MemoryProfile.STANDARD) {}
    poll(recreated)

    assertEquals(DeliveryPhase.DONE, recreated.state.value.phase)
    assertEquals(replacement.length(), recreated.state.value.removable?.bytes)
  }

  @Test fun aDifferentNameIsOfferedWithoutForgettingTheFirstFile() {
    val first = bundle()
    val delivery = acknowledgeBundle()
    val second = bundle(name = "trawl-models (1).zip")
    second.setLastModified(first.lastModified() + 1_000)

    poll(delivery)
    assertEquals(DeliveryPhase.DONE, delivery.state.value.phase)
    assertEquals(second.name, delivery.state.value.removable?.name)
    assertEquals(2, AlreadyInstalledBundleInstaller.calls.get())
    delivery.acknowledge()
    settle(delivery)
    repeat(3) {
      poll(delivery)
      assertEquals(DeliveryPhase.IDLE, delivery.state.value.phase)
      assertNull(delivery.state.value.refusal)
      assertNull(delivery.state.value.removable)
    }

    assertTrue(first.isFile)
    assertTrue(second.isFile)
    assertEquals(2, AlreadyInstalledBundleInstaller.calls.get())

    val recreated = ModelDelivery(context, scope, MemoryProfile.STANDARD) {}
    settle(recreated)
    repeat(3) {
      poll(recreated)
      assertEquals(DeliveryPhase.IDLE, recreated.state.value.phase)
      assertNull(recreated.state.value.refusal)
      assertNull(recreated.state.value.removable)
    }
    assertEquals(4, AlreadyInstalledBundleInstaller.calls.get())
  }

  @Test fun keepingTheDownloadBeforeOkStillAcknowledgesIt() {
    bundle()
    val delivery = ModelDelivery(context, scope, MemoryProfile.STANDARD) {}
    poll(delivery)
    delivery.keepDownloaded()
    delivery.acknowledge()

    val recreated = ModelDelivery(context, scope, MemoryProfile.STANDARD) {}
    poll(recreated)

    assertEquals(DeliveryPhase.IDLE, recreated.state.value.phase)
    assertNull(recreated.state.value.removable)
  }

  @Test fun acknowledgingARefusalStillAllowsTheSameFileToBeRetried() {
    bundle()
    AlreadyInstalledBundleInstaller.refusal = Refusal.NO_SPACE
    val delivery = ModelDelivery(context, scope, MemoryProfile.STANDARD) {}
    poll(delivery)
    assertEquals(DeliveryPhase.REFUSED, delivery.state.value.phase)
    assertEquals(Refusal.NO_SPACE, delivery.state.value.refusal)

    settle(delivery)
    delivery.acknowledge()
    AlreadyInstalledBundleInstaller.refusal = Refusal.ALREADY_INSTALLED
    poll(delivery)

    assertEquals(DeliveryPhase.DONE, delivery.state.value.phase)
    assertEquals(Refusal.ALREADY_INSTALLED, delivery.state.value.refusal)
    assertEquals(2, AlreadyInstalledBundleInstaller.calls.get())
  }
}

@Implements(Environment::class)
class ModelDeliveryDownloadsEnvironment : ShadowEnvironment() {
  companion object {
    @JvmStatic @Implementation fun isExternalStorageManager(): Boolean = true
  }
}

@Implements(SignedBundleInstaller::class, isInAndroidSdk = false)
class AlreadyInstalledBundleInstaller {
  @Implementation fun install(input: InputStream, progress: (Long, Long) -> Unit, cancelled: () -> Boolean): BundleResult {
    input.close()
    calls.incrementAndGet()
    return BundleResult.Refused(refusal)
  }

  companion object {
    val calls = AtomicInteger()
    var refusal = Refusal.ALREADY_INSTALLED

    @JvmStatic @Resetter fun reset() { calls.set(0); refusal = Refusal.ALREADY_INSTALLED }
  }
}
