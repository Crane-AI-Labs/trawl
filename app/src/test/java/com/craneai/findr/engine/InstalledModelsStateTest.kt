package com.craneai.findr.engine

import com.craneai.findr.core.DeliveryPhase
import com.craneai.findr.core.DeliveryState
import com.craneai.findr.core.PackCatalog
import com.craneai.findr.core.Refusal
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class InstalledModelsStateTest {
  @get:Rule val tmp = TemporaryFolder(File("build/tmp/models-card-honest").apply { mkdirs() })
  private val allPacks = listOf(PackCatalog.QUICK_LOOK, PackCatalog.PRIVACY, PackCatalog.SPEECH)

  private fun searchModel(name: String = Embedder.STOCK_NAMES.first()) {
    File(tmp.root, name).writeText("installed")
  }

  private fun pack(id: String) {
    val files = PackFilesRegistry.of(id)!!
    files.manifest.filter { it.required }.forEach { entry ->
      val file = File(tmp.root, "${files.dir}/${entry.path}")
      file.parentFile!!.mkdirs()
      RandomAccessFile(file, "rw").use { it.setLength(entry.size) }
    }
  }

  private fun state(bundleClass: BundleClass = BundleClass.STANDARD, state: DeliveryState = DeliveryState()) =
    installedModelsState(state, tmp.root, bundleClass)

  @Test fun noSearchModelIsNotAnInstalledSearchModelWithMissingPacks() {
    assertFalse(state().searchModelInstalled)
    assertTrue(state().missingPacks.isEmpty())
    File(tmp.root, Embedder.STOCK_NAMES.first() + ".part").writeText("unfinished")
    assertFalse(state().searchModelInstalled)
  }

  @Test fun installedSearchModelWithoutPacksReportsEveryMissingPack() {
    searchModel()
    assertTrue(state().searchModelInstalled)
    assertEquals(allPacks, state().missingPacks)
  }

  @Test fun eachMissingPackIsNamedAndInstalledPacksAreNot() {
    searchModel()
    for (missing in allPacks) {
      val dir = tmp.newFolder(missing)
      File(dir, Embedder.STOCK_NAMES.first()).writeText("installed")
      for (present in allPacks - missing) {
        val files = PackFilesRegistry.of(present)!!
        files.manifest.filter { it.required }.forEach { entry ->
          val file = File(dir, "${files.dir}/${entry.path}")
          file.parentFile!!.mkdirs()
          RandomAccessFile(file, "rw").use { it.setLength(entry.size) }
        }
      }
      assertEquals(listOf(missing), installedModelsState(DeliveryState(), dir, BundleClass.STANDARD).missingPacks)
    }
  }

  @Test fun aPartlyInstalledPackRemainsMissingAndInventoryRefreshesWhenItFinishes() {
    searchModel()
    pack(PackCatalog.QUICK_LOOK)
    pack(PackCatalog.PRIVACY)
    pack(PackCatalog.SPEECH)
    assertTrue(state().missingPacks.isEmpty())
    val files = PackFilesRegistry.speech
    val entry = files.manifest.first { it.required }
    RandomAccessFile(File(tmp.root, "${files.dir}/${entry.path}"), "rw").use { it.setLength(entry.size - 1) }
    assertEquals(listOf(PackCatalog.SPEECH), state().missingPacks)
    pack(PackCatalog.SPEECH)
    assertTrue(state().missingPacks.isEmpty())
  }

  @Test fun saveDataDoesNotClaimItsIntentionallyAbsentSpeechPackIsMissing() {
    searchModel(Embedder.TEXT_ONLY_NAME)
    pack(PackCatalog.QUICK_LOOK)
    pack(PackCatalog.PRIVACY)
    assertTrue(state(BundleClass.LITE).searchModelInstalled)
    assertTrue(state(BundleClass.LITE).missingPacks.isEmpty())
    assertEquals(listOf(PackCatalog.SPEECH), state(BundleClass.STANDARD).missingPacks)
  }

  @Test fun refusalDoesNotEraseTheInstalledSearchModelOrItsMissingPackInventory() {
    searchModel()
    pack(PackCatalog.QUICK_LOOK)
    val refused = DeliveryState(phase = DeliveryPhase.REFUSED, refusal = Refusal.DAMAGED, sizeMb = 670)
    val result = state(state = refused)
    assertTrue(result.searchModelInstalled)
    assertEquals(listOf(PackCatalog.PRIVACY, PackCatalog.SPEECH), result.missingPacks)
    assertEquals(refused.phase, result.phase)
    assertEquals(refused.refusal, result.refusal)
    assertEquals(refused.sizeMb, result.sizeMb)
  }

  @Test fun aDownloadsZipWithoutItsEndRecordStopsRatherThanInstallingAndCanMoveAgain() {
    val file = File(tmp.root, ModelBundle.ARCHIVE_NAME)
    ZipOutputStream(file.outputStream()).use { zip ->
      zip.putNextEntry(ZipEntry("search-model"))
      zip.write("model bytes".toByteArray())
      zip.closeEntry()
    }
    assertTrue(ZipTail.hasEnd(file))
    RandomAccessFile(file, "rw").use { it.setLength(it.length() - 22) }
    assertFalse(ZipTail.hasEnd(file))
    val now = DownloadsWatch.STALLED_MS + 1_000
    val entry = DownloadEntry(file.name, file.length(), 1)
    val previous = mapOf(file.name to Seen(entry.length, entry.modified, 1))
    val stopped = DownloadsWatch.step(listOf(entry), previous, now, emptySet()) { ZipTail.hasEnd(file) }
    assertEquals(DownloadStep.Stopped(entry), stopped)
    val grown = entry.copy(length = entry.length + 1, modified = now)
    assertEquals(DownloadStep.Downloading(grown.length), DownloadsWatch.step(
      listOf(grown), previous, now + 1, setOf(DownloadsWatch.key(entry)),
    ) { ZipTail.hasEnd(file) })
  }
}
