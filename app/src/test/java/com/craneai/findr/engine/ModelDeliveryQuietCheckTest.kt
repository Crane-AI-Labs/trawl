package com.craneai.findr.engine

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.craneai.findr.core.DeliveryPhase
import com.craneai.findr.core.ModelChoice
import com.craneai.findr.core.PackCatalog
import com.craneai.findr.core.Refusal
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The bundle left in Downloads after an install is looked at again on every start of the app. That look must not announce
 * "Installing": the phase it set at once made Home want the "Get the search models" card for as long as the first two entries took
 * to read, on every cold start (1.0.8). A file found in Downloads says Installing only once its files are copied; a chosen file at once.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ModelDeliveryQuietCheckTest {
  private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
  private val scope = CoroutineScope(SupervisorJob() + CoroutineExceptionHandler { _, _ -> })

  @After fun stop() = scope.cancel()

  /** A zip under the bundle's name whose first entry is not the signed manifest: refused (NOT_SIGNED) after reading one entry. */
  private fun notABundle(): File = File(context.cacheDir, "trawl-models.zip").apply {
    ZipOutputStream(outputStream()).use { z -> z.putNextEntry(ZipEntry("readme.txt")); z.write("hello".toByteArray()); z.closeEntry() }
  }

  private fun waitFor(d: ModelDelivery, phase: DeliveryPhase) {
    val end = System.currentTimeMillis() + 10_000
    while (d.state.value.phase != phase && System.currentTimeMillis() < end) Thread.sleep(20)
  }

  @Test fun aFileFoundInDownloadsIsNotAnnouncedWhileItIsOnlyBeingChecked() {
    val d = ModelDelivery(context, scope, MemoryProfile.STANDARD) {}
    assertEquals(DeliveryPhase.IDLE, d.state.value.phase)
    d.installFile(notABundle())
    // Right after the call: the old code had set INSTALLING here, before a single byte was read.
    assertNotEquals(DeliveryPhase.INSTALLING, d.state.value.phase)
    waitFor(d, DeliveryPhase.REFUSED)
    assertEquals(DeliveryPhase.REFUSED, d.state.value.phase)
    assertEquals(Refusal.NOT_SIGNED, d.state.value.refusal)
  }

  @Test fun aChosenFileIsAnnouncedAtOnce() {
    val d = ModelDelivery(context, scope, MemoryProfile.STANDARD) {}
    d.installFrom(Uri.fromFile(notABundle()).toString())
    assertEquals(DeliveryPhase.INSTALLING, d.state.value.phase)   // the person chose it: say so at once
    waitFor(d, DeliveryPhase.REFUSED)
  }

  @Test fun choosingTheSameBundleRefreshesALooseInstalledSearchModel() {
    val delivery = ModelDelivery(context, scope, MemoryProfile.STANDARD) {}
    assertFalse(delivery.state.value.searchModelInstalled)
    val models = File(context.filesDir, "models")
    val createdDirectory = !models.exists()
    models.mkdirs()
    val model = File(models, Embedder.STOCK_NAMES.first())
    assertFalse(model.exists())
    model.writeText("installed")
    try {
      delivery.setChoice(ModelChoice.STANDARD)
      assertTrue(delivery.state.value.searchModelInstalled)
      assertEquals(listOf(
        PackCatalog.QUICK_LOOK,
        PackCatalog.PRIVACY,
        PackCatalog.SPEECH,
      ), delivery.state.value.missingPacks)
      assertEquals(null, delivery.state.value.installedChoice)
      assertEquals(com.craneai.findr.BuildConfig.MODELS_BASE_URL + ModelBundle.ARCHIVE_NAME, delivery.downloadUrl())
    } finally {
      model.delete()
      if (createdDirectory) models.delete()
    }
  }
}
