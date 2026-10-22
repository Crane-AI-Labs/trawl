package com.craneai.findr.engine

import com.craneai.findr.core.PackCatalog
import com.craneai.findr.core.PackCopy
import com.craneai.findr.core.PackDef
import com.craneai.findr.core.PackProblem
import com.craneai.findr.core.PackState
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The add/remove flow against a synthetic two-file pack standing in for the real 140 MB one. Everything runs on
 * `Dispatchers.Unconfined`, so a launched install has finished by the time `install()` returns.
 */
class EnginePackManagerTest {
  @get:Rule val tmp = TemporaryFolder()

  private val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
  @After fun stop() = scope.coroutineContext[Job]!!.cancel()

  private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

  private val data = mapOf(
    "m/model.onnx" to ByteArray(2_500_000) { (it % 241).toByte() },   // more than two copy buffers, so progress has steps
    "tokens.txt" to "hello".toByteArray(),
  )
  private val manifest = data.map { (p, b) -> AsrModelFile(p, b.size.toLong(), sha(b), required = true) }
  private val files = PackFiles("testpack", manifest, "findr-speech-models", Regex("""findr-speech-models.*\.zip""", RegexOption.IGNORE_CASE))
  private val registry: (String) -> PackFiles? = { if (it == PackCatalog.SPEECH) files else null }

  private class FakeSource(var result: PackSourceResult) : ModelPackSource {
    var opened = 0
    override suspend fun open(pack: PackFiles): PackSourceResult { opened++; return result }
    override fun howToAdd(def: PackDef) = PackCopy(0, listOf(def.archiveName))
  }

  private class FakeChoices : PackChoices {
    val declined = mutableMapOf<String, Boolean>()
    override fun declined(id: String) = declined[id] ?: false
    override fun setDeclined(id: String, declined: Boolean) { this.declined[id] = declined }
  }

  private val modelsDir by lazy { tmp.newFolder("models") }
  private val choices = FakeChoices()
  private val enabledCalls = mutableListOf<Pair<String, Boolean>>()
  private var changed = 0
  private var filesPresentWhenReleased: Boolean? = null

  private val host = PackHost(
    setEnabled = { def, on -> enabledCalls += def.id to on },
    releaseForRemoval = { filesPresentWhenReleased = File(modelsDir, "testpack").exists() },
    changed = { changed++ },
  )

  private fun goodFolder(corrupt: Boolean = false): File = tmp.newFolder().also { d ->
    data.forEach { (p, b) ->
      val bytes = if (corrupt && p == "tokens.txt") "hellx".toByteArray() else b   // same size, one byte off
      File(d, p).apply { parentFile!!.mkdirs() }.writeBytes(bytes)
    }
  }

  private fun found(dir: File) = PackSourceResult.Found(listOf(FolderModelSource(dir)))
  private fun manager(source: ModelPackSource) =
    EnginePackManager(modelsDir, source, choices, host, scope, Dispatchers.Unconfined, log = {}, registry = registry)

  @Test fun startsNotInstalledWhenNothingIsOnDisk() {
    val m = manager(FakeSource(PackSourceResult.NotFound))
    assertEquals(PackState.NotInstalled, m.states.value[PackCatalog.SPEECH])
    assertEquals(PackState.NotInstalled, m.states.value[PackCatalog.QUICK_LOOK])
  }

  @Test fun installCopiesChecksAndSwitchesThePackOn() {
    val m = manager(FakeSource(found(goodFolder())))
    m.install(PackCatalog.SPEECH)
    val s = m.states.value[PackCatalog.SPEECH]
    assertTrue(s.toString(), s is PackState.Installed)
    assertEquals(data.values.sumOf { it.size.toLong() }, (s as PackState.Installed).bytes)
    data.forEach { (p, b) -> assertEquals(b.toList(), File(modelsDir, "testpack/$p").readBytes().toList()) }
    assertEquals(listOf(PackCatalog.SPEECH to true), enabledCalls)
    assertEquals(1, changed)
    assertFalse(choices.declined(PackCatalog.SPEECH))
  }

  @Test fun installReportsProgressFromZeroToTheTotal() {
    val m = manager(FakeSource(found(goodFolder())))
    val seen = mutableListOf<PackState>()
    val watcher = scope.launch { m.states.collect { seen += it[PackCatalog.SPEECH]!! } }
    m.install(PackCatalog.SPEECH)
    watcher.cancel()
    val total = data.values.sumOf { it.size.toLong() }
    val installing = seen.filterIsInstance<PackState.Installing>()
    assertEquals("starts at zero", 0L, installing.first().doneBytes)
    // Steps in between are conflated by the unconfined test dispatcher; the installer's own test checks every step.
    assertTrue("never goes backwards", installing.map { it.doneBytes }.zipWithNext().all { (a, b) -> b >= a })
    assertTrue(seen.last() is PackState.Installed)
  }

  @Test fun installingAClearedChoiceUndoesAnEarlierNotNow() {
    choices.setDeclined(PackCatalog.SPEECH, true)
    val m = manager(FakeSource(found(goodFolder())))
    m.install(PackCatalog.SPEECH)
    assertFalse(choices.declined(PackCatalog.SPEECH))
  }

  @Test fun noFileInDownloadsIsReportedAsNotFound() {
    val m = manager(FakeSource(PackSourceResult.NotFound))
    m.install(PackCatalog.SPEECH)
    assertEquals(PackState.Failed(PackProblem.NOT_FOUND), m.states.value[PackCatalog.SPEECH])
    assertTrue(enabledCalls.isEmpty())
  }

  @Test fun missingFileAccessIsReportedAsThat() {
    val m = manager(FakeSource(PackSourceResult.NeedsFileAccess))
    m.install(PackCatalog.SPEECH)
    assertEquals(PackState.Failed(PackProblem.NEEDS_FILE_ACCESS), m.states.value[PackCatalog.SPEECH])
  }

  @Test fun aSourceThatCannotDeliverIsReportedAsUnavailable() {
    val m = manager(FakeSource(PackSourceResult.Unavailable("no Play yet")))
    m.install(PackCatalog.SPEECH)
    assertEquals(PackState.Failed(PackProblem.UNAVAILABLE, "no Play yet"), m.states.value[PackCatalog.SPEECH])
  }

  @Test fun aPackFileThatCannotBeOpenedIsReportedAsDamagedNotMissing() {
    val dl = tmp.newFolder()
    File(dl, "findr-speech-models.zip").writeText("not a zip: a copy cut short")
    val m = manager(DownloadsFolderSource(downloads = { dl }, hasAccess = { true }))
    m.install(PackCatalog.SPEECH)
    val s = m.states.value[PackCatalog.SPEECH]
    assertTrue(s.toString(), s is PackState.Failed && s.problem == PackProblem.BAD_FILES && s.detail!!.startsWith("findr-speech-models.zip"))
  }

  @Test fun aFailedBackgroundInstallShowsOnTheCardButNeverOverAnInstalledPack() {
    val m = manager(FakeSource(found(goodFolder())))
    m.reportAutoInstall(PackCatalog.SPEECH, PackState.Failed(PackProblem.BAD_FILES, "x.zip: truncated"))
    assertEquals(PackState.Failed(PackProblem.BAD_FILES, "x.zip: truncated"), m.states.value[PackCatalog.SPEECH])
    m.install(PackCatalog.SPEECH)
    m.reportAutoInstall(PackCatalog.SPEECH, PackState.Failed(PackProblem.BAD_FILES, "late"))
    assertTrue(m.states.value[PackCatalog.SPEECH] is PackState.Installed)
  }

  @Test fun aDamagedFileIsReportedAndNothingBadIsLeftInPlace() {
    val m = manager(FakeSource(found(goodFolder(corrupt = true))))
    m.install(PackCatalog.SPEECH)
    val s = m.states.value[PackCatalog.SPEECH] as PackState.Failed
    assertEquals(PackProblem.BAD_FILES, s.problem)
    assertFalse(File(modelsDir, "testpack/tokens.txt").exists())
    assertTrue(enabledCalls.isEmpty())
  }

  @Test fun aPackWithoutFilesYetCannotBeInstalled() {
    val source = FakeSource(found(goodFolder()))
    val m = manager(source)
    m.install(PackCatalog.QUICK_LOOK)
    assertEquals(PackState.Failed(PackProblem.UNAVAILABLE), m.states.value[PackCatalog.QUICK_LOOK])
    assertEquals("the source is never asked", 0, source.opened)
  }

  @Test fun anAlreadyCompletePackIsNotReadFromTheSourceAgain() {
    val source = FakeSource(found(goodFolder()))
    val m = manager(source)
    m.install(PackCatalog.SPEECH)
    m.install(PackCatalog.SPEECH)
    assertEquals(1, source.opened)
    assertTrue(m.states.value[PackCatalog.SPEECH] is PackState.Installed)
  }

  @Test fun removeDeletesTheFilesTurnsThePackOffAndRemembersTheChoice() {
    val m = manager(FakeSource(found(goodFolder())))
    m.install(PackCatalog.SPEECH)
    enabledCalls.clear()
    m.remove(PackCatalog.SPEECH)
    assertEquals(PackState.NotInstalled, m.states.value[PackCatalog.SPEECH])
    assertFalse(File(modelsDir, "testpack").exists())
    assertEquals(listOf(PackCatalog.SPEECH to false), enabledCalls)
    assertTrue(choices.declined(PackCatalog.SPEECH))
    assertEquals("whatever has the files open lets go first", true, filesPresentWhenReleased)
    assertEquals(2, changed)
  }

  @Test fun removeThenAddWorksAgain() {
    val dir = goodFolder()
    val m = manager(FakeSource(PackSourceResult.Found(listOf(FolderModelSource(dir)))))
    m.install(PackCatalog.SPEECH)
    m.remove(PackCatalog.SPEECH)
    m.install(PackCatalog.SPEECH)
    assertTrue(m.states.value[PackCatalog.SPEECH] is PackState.Installed)
    assertFalse(choices.declined(PackCatalog.SPEECH))
  }

  @Test fun theBlurPackInstallsAndRemovesLikeTheOthersAndDrivesOnlyItsOwnSwitch() {
    val m = EnginePackManager(
      modelsDir, FakeSource(found(goodFolder())), choices, host, scope, Dispatchers.Unconfined, log = {},
      registry = { if (it == PackCatalog.PRIVACY) files else null },
    )
    m.install(PackCatalog.PRIVACY)
    assertTrue(m.states.value[PackCatalog.PRIVACY] is PackState.Installed)
    assertEquals(listOf(PackCatalog.PRIVACY to true), enabledCalls)
    m.remove(PackCatalog.PRIVACY)
    assertEquals(PackState.NotInstalled, m.states.value[PackCatalog.PRIVACY])
    assertFalse(File(modelsDir, "testpack").exists())
    assertEquals(listOf(PackCatalog.PRIVACY to true, PackCatalog.PRIVACY to false), enabledCalls)
    assertTrue("a removed pack is not put back unasked", choices.declined(PackCatalog.PRIVACY))
  }

  @Test fun theBlurPackManifestIsWellFormed() {
    val files = PrivatePack.files
    assertEquals(files.size, files.map { it.path }.toSet().size)
    files.forEach { assertTrue(it.path, it.size > 0 && it.sha256.matches(Regex("[0-9a-f]{64}"))) }
    assertTrue(files.any { it.path == PrivatePack.MODEL } && files.any { it.path == PrivatePack.LICENCE })
    assertEquals("the scorer reads the file the installer writes", PrivatePack.MODEL, "nsfw-384/nsfw-384.onnx")
  }

  @Test fun notNowIsRemembered() {
    val m = manager(FakeSource(PackSourceResult.NotFound))
    m.decline(PackCatalog.SPEECH)
    assertTrue(choices.declined(PackCatalog.SPEECH))
    assertEquals(PackState.NotInstalled, m.states.value[PackCatalog.SPEECH])
  }

  @Test fun refreshPicksUpFilesPutInPlaceByAnotherPath() {
    val m = manager(FakeSource(PackSourceResult.NotFound))
    data.forEach { (p, b) -> File(modelsDir, "testpack/$p").apply { parentFile!!.mkdirs() }.writeBytes(b) }
    m.refresh()
    assertTrue(m.states.value[PackCatalog.SPEECH] is PackState.Installed)
  }

  @Test fun refreshKeepsAFailureVisibleUntilTheNextTryButNotOverARealInstall() {
    val m = manager(FakeSource(PackSourceResult.NotFound))
    m.install(PackCatalog.SPEECH)
    m.refresh()
    assertEquals(PackState.Failed(PackProblem.NOT_FOUND), m.states.value[PackCatalog.SPEECH])
    data.forEach { (p, b) -> File(modelsDir, "testpack/$p").apply { parentFile!!.mkdirs() }.writeBytes(b) }
    m.refresh()
    assertTrue(m.states.value[PackCatalog.SPEECH] is PackState.Installed)
  }

  @Test fun refreshNoticesAPackThatWasDeletedBehindOurBack() {
    val m = manager(FakeSource(found(goodFolder())))
    m.install(PackCatalog.SPEECH)
    File(modelsDir, "testpack").deleteRecursively()
    m.refresh()
    assertEquals(PackState.NotInstalled, m.states.value[PackCatalog.SPEECH])
  }

  @Test fun anUnknownPackIdIsIgnored() {
    val m = manager(FakeSource(found(goodFolder())))
    m.install("nope"); m.remove("nope"); m.decline("nope")
    assertNull(m.states.value["nope"])
    assertEquals(0, changed)
  }
}
