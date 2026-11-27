package com.craneai.findr.engine

import com.craneai.findr.BuildConfig
import com.craneai.findr.R
import com.craneai.findr.core.PackCatalog
import com.craneai.findr.core.PackIds
import com.craneai.findr.core.PackProblem
import com.craneai.findr.core.PackState
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Verifies Google Play Asset Delivery ([PlayAssetPackSource]) pack discovery, status/progress transitions,
 * removal cleanup, and automatic fallback to [DownloadsFolderSource] + external browser download URLs when
 * installed outside Google Play.
 */
class PlayAssetPackSourceTest {
  @get:Rule val tmp = TemporaryFolder()

  private val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
  @After fun tearDown() = scope.coroutineContext[Job]!!.cancel()

  private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

  private val sampleData = mapOf(
    "pack.json" to """{"pack_id":"lg-student","pack_version":2026112201,"language":"lg","language_name":"Luganda","frame_ms":80,"sample_rate":16000,"licence":"CC-BY-SA-4.0","files":{"model.int8.onnx":{},"tokens.txt":{}}}""".toByteArray(),
    "model.int8.onnx" to ByteArray(1_500_000) { ((it * 31) % 251).toByte() },
    "tokens.txt" to "a 0\nb 1\n".toByteArray(),
  )

  private val sampleManifest = sampleData.map { (path, bytes) ->
    AsrModelFile(path, bytes.size.toLong(), sha256(bytes), required = true)
  }

  private val lgPack = PackFiles(
    dir = SpeechPacks.lg.dir,
    manifest = sampleManifest,
    folderName = SpeechPacks.lg.folderName,
    archive = Regex("""(?:findr|trawl)-speech-lg(?:[-_ (].*)?\.zip""", RegexOption.IGNORE_CASE),
  )

  private class FakePadClient(
    var playStore: Boolean = true,
    val installedDirs: MutableMap<String, File> = mutableMapOf(),
    var fetchHandler: suspend (String, (PlayAssetProgress) -> Unit) -> PlayAssetFetchResult = { name, _ ->
      PlayAssetFetchResult.Unavailable("not configured for $name")
    },
  ) : PlayAssetDeliveryClient {
    val fetchCalls = mutableListOf<String>()
    val removedPacks = mutableListOf<String>()

    override fun isPlayStoreInstall(): Boolean = playStore
    override fun installedAssetsDir(packName: String): File? = installedDirs[packName]
    override suspend fun fetch(packName: String, onUpdate: (PlayAssetProgress) -> Unit): PlayAssetFetchResult {
      fetchCalls += packName
      return fetchHandler(packName, onUpdate)
    }

    override fun removePack(packName: String) {
      removedPacks += packName
      installedDirs.remove(packName)
    }
  }

  private class FakeChoices : PackChoices {
    private val map = mutableMapOf<String, Boolean>()
    override fun declined(id: String): Boolean = map[id] ?: false
    override fun setDeclined(id: String, declined: Boolean) { map[id] = declined }
  }

  private fun populatePack(dir: File, nested: Boolean = true): File {
    val target = if (nested) File(dir, lgPack.folderName) else dir
    sampleData.forEach { (path, bytes) ->
      File(target, path).apply { parentFile!!.mkdirs() }.writeBytes(bytes)
    }
    return dir
  }

  private fun writePackZip(destZip: File) {
    ZipOutputStream(destZip.outputStream()).use { z ->
      sampleData.forEach { (path, bytes) ->
        z.putNextEntry(ZipEntry("${lgPack.folderName}/$path"))
        z.write(bytes)
        z.closeEntry()
      }
    }
  }

  private fun manager(modelsDir: File, source: ModelPackSource): EnginePackManager =
    EnginePackManager(
      modelsDir = modelsDir,
      source = source,
      choices = FakeChoices(),
      host = PackHost(setEnabled = { _, _ -> }, releaseForRemoval = {}, changed = {}),
      scope = scope,
      io = Dispatchers.Unconfined,
      log = {},
      registry = { id -> if (id == PackIds.SPEECH_LG) lgPack else null },
    )

  @Test fun everyCatalogPackMapsToValidAndroidAssetPackName() {
    val validPadName = Regex("^[a-zA-Z][a-zA-Z0-9_]*$")
    val seen = mutableSetOf<String>()
    for (def in PackCatalog.all) {
      val byId = PlayAssetPacks.packNameForId(def.id)
      val byFiles = PlayAssetPacks.packNameFor(PackFilesRegistry.of(def.id)!!)
      assertNotNull("missing PAD mapping for ${def.id}", byId)
      assertEquals("id and PackFiles mapping must agree for ${def.id}", byId, byFiles)
      assertTrue("invalid Android PAD pack name '$byId'", validPadName.matches(byId!!))
      assertTrue("duplicate PAD pack name '$byId'", seen.add(byId))
    }
    assertEquals(PackIds.ALL.size, seen.size)
  }

  @Test fun discoversAlreadyDeliveredOnDemandPackWithoutReFetching() {
    for (nested in listOf(true, false)) {
      val modelsDir = tmp.newFolder()
      val padAssets = populatePack(tmp.newFolder(), nested = nested)
      val client = FakePadClient(playStore = true, installedDirs = mutableMapOf(PlayAssetPacks.SPEECH_LG to padAssets))
      val fallback = DownloadsFolderSource(downloads = { tmp.newFolder() }, hasAccess = { true })
      val source = PlayAssetPackSource(client, fallback)

      assertEquals(R.string.pack_how_to_add_play, source.howToAdd(PackCatalog.speechLg).res)
      assertNull("Play-managed installs do not show external browser download link", source.downloadUrl(PackCatalog.speechLg))

      val mgr = manager(modelsDir, source)
      mgr.install(PackIds.SPEECH_LG)

      assertTrue("nested=$nested should install cleanly", mgr.states.value[PackIds.SPEECH_LG] is PackState.Installed)
      assertTrue("already delivered pack must not trigger fetch()", client.fetchCalls.isEmpty())
      assertEquals(sampleData.getValue("tokens.txt").toList(), File(modelsDir, "speech-lg/tokens.txt").readBytes().toList())
    }
  }

  @Test fun onDemandFetchTransitionsStatusesReportsProgressAndInstalls() {
    val modelsDir = tmp.newFolder()
    val fetchedDir = populatePack(tmp.newFolder(), nested = true)
    val totalBytes = sampleData.values.sumOf { it.size.toLong() }
    val observedStatuses = mutableListOf<PlayAssetStatus>()
    val client = FakePadClient(
      playStore = true,
      fetchHandler = { packName, onUpdate ->
        assertEquals(PlayAssetPacks.SPEECH_LG, packName)
        val steps = listOf(
          PlayAssetProgress(PlayAssetStatus.PENDING, 0L, totalBytes),
          PlayAssetProgress(PlayAssetStatus.DOWNLOADING, totalBytes / 4, totalBytes),
          PlayAssetProgress(PlayAssetStatus.DOWNLOADING, (totalBytes * 3) / 4, totalBytes),
          PlayAssetProgress(PlayAssetStatus.TRANSFERRING, totalBytes, totalBytes),
          PlayAssetProgress(PlayAssetStatus.COMPLETED, totalBytes, totalBytes),
        )
        for (step in steps) {
          observedStatuses += step.status
          onUpdate(step)
        }
        PlayAssetFetchResult.Ready(fetchedDir)
      },
    )
    val source = PlayAssetPackSource(client, DownloadsFolderSource(downloads = { tmp.newFolder() }, hasAccess = { true }))
    val mgr = manager(modelsDir, source)
    val states = mutableListOf<PackState>()
    val watcher = scope.launch { mgr.states.collect { states += it.getValue(PackIds.SPEECH_LG) } }

    mgr.install(PackIds.SPEECH_LG)
    watcher.cancel()

    assertEquals(listOf(PlayAssetPacks.SPEECH_LG), client.fetchCalls)
    assertEquals(
      listOf(
        PlayAssetStatus.PENDING,
        PlayAssetStatus.DOWNLOADING,
        PlayAssetStatus.DOWNLOADING,
        PlayAssetStatus.TRANSFERRING,
        PlayAssetStatus.COMPLETED,
      ),
      observedStatuses,
    )
    val installing = states.filterIsInstance<PackState.Installing>()
    assertTrue("progress must be observed while downloading", installing.isNotEmpty())
    assertTrue("final state must be Installed", states.last() is PackState.Installed)
    // Play Asset Delivery duplicate cache must be reclaimed immediately after verified copy (no 2x storage leak).
    assertEquals(listOf(PlayAssetPacks.SPEECH_LG), client.removedPacks)

    mgr.remove(PackIds.SPEECH_LG)
    assertEquals(listOf(PlayAssetPacks.SPEECH_LG, PlayAssetPacks.SPEECH_LG), client.removedPacks)
    assertEquals(PackState.NotInstalled, mgr.states.value[PackIds.SPEECH_LG])
  }

  @Test fun nonCompletedPlayStatusesReportClearUnavailableReason() = runBlocking {
    val cases = listOf(
      PlayAssetFetchResult.WaitingForWifi(PlayAssetPacks.SPEECH_LG) to "Wi-Fi",
      PlayAssetFetchResult.NeedsConfirmation(PlayAssetPacks.SPEECH_LG) to "confirmation",
      PlayAssetFetchResult.Canceled(PlayAssetPacks.SPEECH_LG) to "canceled",
      PlayAssetFetchResult.Failed("network error", -6) to "network error",
    )
    for ((fetchResult, expectedFragment) in cases) {
      val client = FakePadClient(playStore = true, fetchHandler = { _, _ -> fetchResult })
      val source = PlayAssetPackSource(client, DownloadsFolderSource(downloads = { tmp.newFolder() }, hasAccess = { true }))
      val result = source.open(lgPack)
      assertTrue(result.toString(), result is PackSourceResult.Unavailable)
      assertTrue(
        "expected '$expectedFragment' in ${(result as PackSourceResult.Unavailable).reason}",
        result.reason.contains(expectedFragment, ignoreCase = true),
      )
    }
  }

  @Test fun sideloadedOutsidePlayFallsBackToDownloadsAndBrowserRedirect() {
    val downloadsDir = tmp.newFolder()
    val modelsDir = tmp.newFolder()
    val client = FakePadClient(playStore = false)
    val downloads = DownloadsFolderSource(downloads = { downloadsDir }, hasAccess = { true })
    val source = PlayAssetPackSource(client, fallback = downloads)

    assertEquals(R.string.pack_how_to_add_downloads, source.howToAdd(PackCatalog.speechLg).res)
    assertEquals(
      BuildConfig.MODELS_BASE_URL + "trawl-speech-lg.zip",
      source.downloadUrl(PackCatalog.speechLg),
    )

    val mgr = manager(modelsDir, source)
    mgr.install(PackIds.SPEECH_LG)
    assertEquals(PackState.Failed(PackProblem.NOT_FOUND), mgr.states.value[PackIds.SPEECH_LG])
    assertTrue("non-Play install must not invoke Play fetch", client.fetchCalls.isEmpty())

    // Once user downloads trawl-speech-lg.zip via browser into Downloads, the same source installs it.
    writePackZip(File(downloadsDir, "trawl-speech-lg.zip"))
    mgr.install(PackIds.SPEECH_LG)
    assertTrue(mgr.states.value[PackIds.SPEECH_LG] is PackState.Installed)
    assertFalse(File(modelsDir, "speech-lg/model.int8.onnx").length() == 0L)
  }

  @Test fun playUnavailableErrorAtRuntimeFallsBackToDownloads() {
    val downloadsDir = tmp.newFolder()
    val modelsDir = tmp.newFolder()
    writePackZip(File(downloadsDir, "trawl-speech-lg.zip"))
    val client = FakePadClient(
      playStore = true,
      fetchHandler = { _, _ -> PlayAssetFetchResult.Unavailable("APP_NOT_OWNED") },
    )
    val downloads = DownloadsFolderSource(downloads = { downloadsDir }, hasAccess = { true })
    val source = PlayAssetPackSource(client, fallback = downloads)
    val mgr = manager(modelsDir, source)

    mgr.install(PackIds.SPEECH_LG)

    assertEquals(listOf(PlayAssetPacks.SPEECH_LG), client.fetchCalls)
    assertTrue("Unavailable from Play must fall back to Downloads zip", mgr.states.value[PackIds.SPEECH_LG] is PackState.Installed)
  }

  @Test fun cellularConfirmationResumesDownloadToInstalledAndReclaimsCache() {
    val modelsDir = tmp.newFolder()
    val fetchedDir = populatePack(tmp.newFolder(), nested = true)
    val totalBytes = sampleData.values.sumOf { it.size.toLong() }
    val seenStatuses = mutableListOf<PlayAssetStatus>()
    val client = FakePadClient(
      playStore = true,
      fetchHandler = { _, onUpdate ->
        // Mirrors PlayAssetDeliveryBridge prompting showConfirmationDialog on WAITING_FOR_WIFI and resuming on approval.
        val steps = listOf(
          PlayAssetProgress(PlayAssetStatus.PENDING, 0L, totalBytes),
          PlayAssetProgress(PlayAssetStatus.WAITING_FOR_WIFI, 0L, totalBytes),
          PlayAssetProgress(PlayAssetStatus.DOWNLOADING, totalBytes / 2, totalBytes),
          PlayAssetProgress(PlayAssetStatus.TRANSFERRING, totalBytes, totalBytes),
          PlayAssetProgress(PlayAssetStatus.COMPLETED, totalBytes, totalBytes),
        )
        steps.forEach { seenStatuses += it.status; onUpdate(it) }
        PlayAssetFetchResult.Ready(fetchedDir)
      },
    )
    val mgr = manager(modelsDir, PlayAssetPackSource(client, fallback = null))
    mgr.install(PackIds.SPEECH_LG)

    assertTrue(seenStatuses.contains(PlayAssetStatus.WAITING_FOR_WIFI))
    assertTrue(mgr.states.value[PackIds.SPEECH_LG] is PackState.Installed)
    assertEquals(listOf(PlayAssetPacks.SPEECH_LG), client.removedPacks)
  }

  @Test fun zipBombOversizedStreamStopsInsideReadLoopWithoutWritingExtraBytes() {
    val dest = tmp.newFolder()
    val expected = "small-token-file".toByteArray()
    val manifest = listOf(AsrModelFile("tokens.txt", expected.size.toLong(), sha256(expected), required = true))
    var bytesServed = 0L
    val bombSource = object : AsrModelSource {
      override val label = "zip-bomb"
      override fun open(path: String): java.io.InputStream = object : java.io.InputStream() {
        override fun read(): Int { bytesServed++; return 0x41 }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
          val n = minOf(len, 64 * 1024)
          bytesServed += n
          return n
        }
      }
    }

    val outcome = AsrModelInstaller(dest, manifest).install(bombSource)
    assertTrue(outcome is AsrModelInstaller.Outcome.Incomplete)
    val problems = (outcome as AsrModelInstaller.Outcome.Incomplete).problems
    assertTrue(problems.single(), problems.single().contains("exceeds expected"))
    assertTrue("must abort on first chunk instead of streaming gigabytes (served=$bytesServed)", bytesServed <= 64L * 1024L)
    assertFalse(File(dest, "tokens.txt").exists())
    assertFalse(File(dest, "tokens.txt.part").exists())
  }
}
