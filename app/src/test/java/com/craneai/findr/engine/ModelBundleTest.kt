package com.craneai.findr.engine

import java.io.File
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The one-file models bundle (`trawl-models.zip`) and the install rules around it: the packs and the model are found in the
 * bundle or as separate files, verified the same way, a choice the user made is kept, and a bundle that lacks something is
 * not a failure. The model is a few KB here (the floor is a parameter); the packs use a small synthetic manifest.
 */
class ModelBundleTest {
  @get:Rule val tmp = TemporaryFolder()

  private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

  private val speechData = mapOf(
    "parakeet/model.onnx" to ByteArray(300_000) { (it % 251).toByte() },
    "parakeet/tokens.txt" to "hello".toByteArray(),
    "silero_vad.onnx" to "vad".toByteArray(),
  )
  private val quickData = mapOf("tinyclip/image.onnx" to ByteArray(200_000) { (it % 13).toByte() }, "tinyclip/text.onnx" to "text".toByteArray())
  private val privateData = mapOf("nsfw-384/nsfw-384.onnx" to ByteArray(250_000) { (it % 7).toByte() }, "nsfw-384/LICENSE.txt" to "Apache".toByteArray())
  private val modelBytes = ByteArray(400_000) { (it % 241).toByte() }
  private val modelName = "embeddinggemma-2-text-vision-440m.litertlm"
  private val floor = 100_000L

  private fun files(dir: String, folder: String, archive: String, data: Map<String, ByteArray>) = PackFiles(
    dir = dir,
    manifest = data.map { (p, b) -> AsrModelFile(p, b.size.toLong(), sha(b)) },
    folderName = folder,
    archive = Regex("""$archive.*\.zip""", RegexOption.IGNORE_CASE),
  )
  private val speech = files("asr", "findr-speech-models", "findr-speech-models", speechData)
  private val quick = files("quicklook", "findr-quicklook-models", "findr-quicklook-models", quickData)
  private val priv = files("private", "findr-private-models", "findr-private-models", privateData)

  /** A stored (uncompressed) zip, like the real bundle: entry name to bytes. */
  private fun storedZip(name: String, entries: Map<String, ByteArray>): File = File(tmp.root, name).also { f ->
    ZipOutputStream(f.outputStream()).use { z ->
      entries.forEach { (n, b) ->
        val e = ZipEntry(n).apply { method = ZipEntry.STORED; size = b.size.toLong(); compressedSize = b.size.toLong(); crc = CRC32().also { it.update(b) }.value }
        z.putNextEntry(e); z.write(b); z.closeEntry()
      }
    }
  }

  private fun bundleEntries(wrapper: String = "", withSpeech: Boolean = true, withQuick: Boolean = true, withModel: Boolean = true, withPrivate: Boolean = true) = buildMap<String, ByteArray> {
    if (withModel) put(wrapper + modelName, modelBytes)
    if (withQuick) quickData.forEach { (p, b) -> put("${wrapper}findr-quicklook-models/$p", b) }
    if (withSpeech) speechData.forEach { (p, b) -> put("${wrapper}findr-speech-models/$p", b) }
    if (withPrivate) privateData.forEach { (p, b) -> put("${wrapper}findr-private-models/$p", b) }
  }

  private fun listing() = tmp.root.listFiles()!!.toList()

  /** What [ModelInstaller] does for one default pack once the choice and access checks have passed. */
  private fun installPack(pack: PackFiles, dest: File, problems: MutableList<String> = mutableListOf()): Boolean {
    val installer = AsrModelInstaller(File(dest, pack.dir), pack.manifest)
    if (installer.complete()) return true
    val sources = findPackSources(listing(), pack) { f, t -> problems += "${f.name}: ${t.message}" }
    for (s in sources) try {
      if (installer.install(s) is AsrModelInstaller.Outcome.Ready) return true
    } finally { s.close() }
    return false
  }

  // ---- the packs, from the bundle ---------------------------------------------------------------------------------------

  @Test fun bothDefaultPacksInstallFromOneBundleAtTheRoot() {
    storedZip("trawl-models.zip", bundleEntries())
    val dest = tmp.newFolder("app")
    assertTrue(installPack(quick, dest))
    assertTrue(installPack(speech, dest))
    quickData.forEach { (p, b) -> assertEquals(b.toList(), File(dest, "quicklook/$p").readBytes().toList()) }
    speechData.forEach { (p, b) -> assertEquals(b.toList(), File(dest, "asr/$p").readBytes().toList()) }
    assertTrue(dest.walkTopDown().none { it.name.endsWith(".part") })
  }

  @Test fun theBlurPackInstallsFromTheSameBundleAndAnOlderBundleWithoutItIsNotAFailure() {
    storedZip("trawl-models.zip", bundleEntries())
    val dest = tmp.newFolder("app")
    assertTrue(installPack(priv, dest))
    privateData.forEach { (p, b) -> assertEquals(b.toList(), File(dest, "private/$p").readBytes().toList()) }
    // A bundle made before the blur pack was added still installs the other two and says nothing about the third.
    File(tmp.root, "trawl-models.zip").delete()
    storedZip("trawl-models.zip", bundleEntries(withPrivate = false))
    val problems = mutableListOf<String>()
    assertFalse(installPack(priv, tmp.newFolder("app2"), problems))
    assertTrue(problems.isEmpty())
    assertTrue(installPack(quick, tmp.newFolder("app3")) && installPack(speech, tmp.newFolder("app4")))
  }

  @Test fun theRealBlurPackLivesWhereTheBundleScriptPutsIt() {
    assertEquals("findr-private-models", PackFilesRegistry.privacy.folderName)
    assertEquals("private", PackFilesRegistry.privacy.dir)
    assertTrue(PackFilesRegistry.privacy.archive.matches("findr-private-models.zip"))
  }

  @Test fun theBundleMayBeWrappedInOneFolderAndHaveABrowserSuffix() {
    storedZip("trawl-models (1).zip", bundleEntries(wrapper = "trawl-models/"))
    val dest = tmp.newFolder("app")
    assertTrue(installPack(quick, dest))
    assertTrue(installPack(speech, dest))
  }

  @Test fun anUnzippedBundleFolderWorksToo() {
    val dir = tmp.newFolder("trawl-models")
    speechData.forEach { (p, b) -> File(dir, "findr-speech-models/$p").apply { parentFile!!.mkdirs() }.writeBytes(b) }
    assertTrue(installPack(speech, tmp.newFolder("app")))
  }

  @Test fun aBundleThatLacksAPackIsSkippedNotReportedAsAFailure() {
    storedZip("trawl-models.zip", bundleEntries(withSpeech = false))
    val problems = mutableListOf<String>()
    assertTrue(findPackSources(listing(), speech) { f, t -> problems += "${f.name}: $t" }.isEmpty())
    assertTrue(problems.isEmpty())
    assertFalse(installPack(speech, tmp.newFolder("app"), problems))   // nothing there, and nothing said
    assertTrue(problems.isEmpty())
    assertTrue(installPack(quick, tmp.newFolder("app2")))   // the pack it does carry still installs
  }

  @Test fun nothingInDownloadsFindsNothingAndSaysNothing() {
    val problems = mutableListOf<String>()
    assertTrue(findPackSources(emptyList(), speech) { f, t -> problems += "${f.name}: $t" }.isEmpty())
    assertTrue(problems.isEmpty())
  }

  // ---- the packs, as separate files (what testers have today) -------------------------------------------------------------

  @Test fun theSeparatePackFilesStillWork() {
    File(tmp.root, "findr-speech-models.zip").also { f ->
      ZipOutputStream(f.outputStream()).use { z -> speechData.forEach { (p, b) -> z.putNextEntry(ZipEntry("findr-speech-models/$p")); z.write(b); z.closeEntry() } }
    }
    assertTrue(installPack(speech, tmp.newFolder("app")))
  }

  @Test fun aSeparateFileIsTriedBeforeTheBundle() {
    storedZip("trawl-models.zip", bundleEntries())
    File(tmp.root, "findr-speech-models.zip").also { f ->
      ZipOutputStream(f.outputStream()).use { z -> speechData.forEach { (p, b) -> z.putNextEntry(ZipEntry(p)); z.write(b); z.closeEntry() } }
    }
    val labels = findPackSources(listing(), speech).onEach { it.close() }.map { File(it.label).name }
    assertEquals(listOf("findr-speech-models.zip", "trawl-models.zip"), labels)
  }

  @Test fun aDamagedSeparateFileFallsBackToTheBundle() {
    File(tmp.root, "findr-speech-models.zip").writeBytes(ByteArray(1000))   // not a zip
    storedZip("trawl-models.zip", bundleEntries())
    val problems = mutableListOf<String>()
    assertTrue(installPack(speech, tmp.newFolder("app"), problems))
    assertEquals(1, problems.size)   // the damaged file is still reported, never silent
  }

  @Test fun aTruncatedBundleIsReportedForThePacksToo() {
    val whole = storedZip("whole.zip", bundleEntries())
    File(tmp.root, "trawl-models.zip").writeBytes(whole.readBytes().copyOf(whole.length().toInt() / 2))
    whole.delete()
    val problems = mutableListOf<String>()
    assertFalse(installPack(speech, tmp.newFolder("app"), problems))
    assertEquals(1, problems.size)
    assertTrue(problems.single().startsWith("trawl-models.zip"))
  }

  // ---- the model, from the bundle ------------------------------------------------------------------------------------

  @Test fun theModelInstallsFromTheBundleThroughATempFile() {
    storedZip("trawl-models.zip", bundleEntries())
    val models = File(tmp.root, "files/models")
    val name = ModelBundle.installModel(listing(), listOf("other.litertlm", modelName), models, floor)
    assertEquals(modelName, name)
    assertEquals(modelBytes.toList(), File(models, modelName).readBytes().toList())
    assertTrue(models.listFiles()!!.none { it.name.endsWith(".part") })
  }

  @Test fun theModelIsFoundUnderOneWrappingFolderToo() {
    storedZip("trawl-models.zip", bundleEntries(wrapper = "trawl-models/"))
    assertEquals(modelName, ModelBundle.installModel(listing(), listOf(modelName), tmp.newFolder("m"), floor))
  }

  @Test fun theBestWantedModelWinsWhenTheBundleCarriesSeveral() {
    storedZip("trawl-models.zip", bundleEntries() + ("best.litertlm" to ByteArray(150_000) { 7 }))
    val models = tmp.newFolder("m")
    assertEquals("best.litertlm", ModelBundle.installModel(listing(), listOf("best.litertlm", modelName), models, floor))
    assertFalse(File(models, modelName).exists())
  }

  @Test fun aBundleWithoutAModelOrWithAModelTheAppDoesNotKnowGivesNull() {
    storedZip("trawl-models.zip", bundleEntries(withModel = false))
    val problems = mutableListOf<Pair<File, Throwable>>()
    assertNull(ModelBundle.installModel(listing(), listOf(modelName), tmp.newFolder("m"), floor) { f, t -> problems += f to t })
    assertTrue(problems.isEmpty())
    File(tmp.root, "trawl-models.zip").delete()
    storedZip("trawl-models.zip", bundleEntries(withModel = false) + ("someone-elses.litertlm" to modelBytes))
    assertNull(ModelBundle.installModel(listing(), listOf(modelName), tmp.newFolder("m2"), floor))
  }

  @Test fun aTooSmallFileIsNotTakenForTheModel() {
    storedZip("trawl-models.zip", mapOf(modelName to ByteArray(1000)))
    assertNull(ModelBundle.installModel(listing(), listOf(modelName), tmp.newFolder("m"), floor))
  }

  @Test fun aModelDamagedInTransitFailsItsCheckAndLeavesNothingBehind() {
    val zip = storedZip("trawl-models.zip", bundleEntries())
    val bytes = zip.readBytes()
    // Flip one byte inside the stored model (its data sits in the file as-is): the entry's CRC-32 no longer matches.
    val at = bytes.indexOfSlice(modelBytes.copyOfRange(1000, 1064)) + 10
    bytes[at] = (bytes[at] + 1).toByte()
    zip.writeBytes(bytes)
    val models = tmp.newFolder("m")
    val problems = mutableListOf<Throwable>()
    assertNull(ModelBundle.installModel(listing(), listOf(modelName), models, floor) { _, t -> problems += t })
    assertEquals(0, models.listFiles()!!.size)   // no .part, no model
    assertEquals("checksum mismatch", problems.single().message)
  }

  @Test fun aTruncatedBundleIsReportedAndTheNextOneIsTried() {
    val whole = storedZip("good.zip", bundleEntries())
    File(tmp.root, "trawl-models (1).zip").also { it.writeBytes(whole.readBytes().copyOf(whole.length().toInt() / 2)); it.setLastModified(System.currentTimeMillis() + 5_000) }
    File(tmp.root, "trawl-models.zip").also { whole.copyTo(it); it.setLastModified(System.currentTimeMillis() - 5_000) }
    whole.delete()
    val problems = mutableListOf<File>()
    val models = tmp.newFolder("m")
    assertEquals(modelName, ModelBundle.installModel(listing(), listOf(modelName), models, floor) { f, _ -> problems += f })
    assertEquals(listOf("trawl-models (1).zip"), problems.map { it.name })   // the newer one was tried first, failed, and was reported
  }

  @Test fun anUnzippedBundleFolderSuppliesTheModel() {
    File(tmp.newFolder("trawl-models"), modelName).writeBytes(modelBytes)
    val models = tmp.newFolder("m")
    assertEquals(modelName, ModelBundle.installModel(listing(), listOf(modelName), models, floor))
    assertEquals(modelBytes.size.toLong(), File(models, modelName).length())
  }

  @Test fun theBundleAndPackNamesDoNotCollideWithTheSeparateFiles() {
    assertTrue(ModelBundle.ARCHIVE.matches("trawl-models.zip"))
    assertTrue(ModelBundle.ARCHIVE.matches("Trawl-Models (2).zip"))
    assertFalse(ModelBundle.ARCHIVE.matches("findr-speech-models.zip"))
    assertFalse(PackFilesRegistry.speech.archive.matches("trawl-models.zip"))
    assertFalse(PackFilesRegistry.quickLook.archive.matches("trawl-models.zip"))
  }

  // ---- choices the user made -------------------------------------------------------------------------------------------

  private class Choices(vararg declined: String) : PackChoices {
    val set = declined.toMutableSet()
    override fun declined(id: String) = id in set
    override fun setDeclined(id: String, declined: Boolean) { if (declined) set += id else set -= id }
  }

  @Test fun aPackTheUserSaidNotNowToIsNotInstalledFromTheBundleOrAnywhere() {
    val choices = Choices("quicklook")
    assertNotNull(autoInstallBlock(choices, "quicklook", hasFileAccess = true))
    assertNull(autoInstallBlock(choices, "speech", hasFileAccess = true))   // the other default pack still installs
  }

  @Test fun removeIsKeptButAddClearsIt() {
    val choices = Choices()
    choices.setDeclined("speech", true)   // Remove / Not now
    assertNotNull(autoInstallBlock(choices, "speech", true))
    choices.setDeclined("speech", false)   // Add
    assertNull(autoInstallBlock(choices, "speech", true))
  }

  @Test fun withoutAllFilesAccessNothingIsReadFromDownloads() {
    assertNotNull(autoInstallBlock(Choices(), "speech", hasFileAccess = false))
  }

  private fun ByteArray.indexOfSlice(part: ByteArray): Int {
    outer@ for (i in 0..size - part.size) {
      for (j in part.indices) if (this[i + j] != part[j]) continue@outer
      return i
    }
    return -1
  }
}
