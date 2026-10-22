package com.craneai.findr.engine

import com.craneai.findr.extract.SherpaAsrEngine
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AsrModelInstallerTest {
  @get:Rule val tmp = TemporaryFolder()

  private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

  private val data = mapOf(
    "a/model.onnx" to ByteArray(3_000_000) { (it % 251).toByte() },   // bigger than the copy buffer's first read
    "a/tokens.txt" to "hello".toByteArray(),
    "vad.onnx" to "vad".toByteArray(),
    "extra/alt.onnx" to "alt".toByteArray(),
  )
  private val manifest = data.map { (p, b) -> AsrModelFile(p, b.size.toLong(), sha(b), required = !p.startsWith("extra/")) }

  private fun folderWith(vararg skip: String): File = tmp.newFolder().also { d ->
    data.filterKeys { it !in skip }.forEach { (p, b) -> File(d, p).apply { parentFile!!.mkdirs() }.writeBytes(b) }
  }

  private fun zipWith(wrapper: String = "", skip: Set<String> = emptySet()): File = tmp.newFile("pack.zip").also { f ->
    ZipOutputStream(f.outputStream()).use { z ->
      data.filterKeys { it !in skip }.forEach { (p, b) -> z.putNextEntry(ZipEntry(wrapper + p)); z.write(b); z.closeEntry() }
    }
  }

  private fun installer(dest: File) = AsrModelInstaller(dest, manifest)

  @Test fun installsEveryFileFromAFolderAndVerifiesThem() {
    val dest = tmp.newFolder()
    val r = installer(dest).install(FolderModelSource(folderWith()))
    assertTrue(r is AsrModelInstaller.Outcome.Ready)
    manifest.forEach { assertEquals(it.size, File(dest, it.path).length()) }
    assertTrue(installer(dest).complete())
    assertTrue(dest.walkTopDown().none { it.name.endsWith(".part") })
  }

  @Test fun installsFromAZipAtTheRootAndFromOneWrappedInAFolder() {
    for (wrapper in listOf("", "findr-speech-models/")) {
      val dest = tmp.newFolder()
      val zip = ZipModelSource(zipWith(wrapper))
      try {
        assertTrue("wrapper '$wrapper'", installer(dest).install(zip) is AsrModelInstaller.Outcome.Ready)
      } finally {
        zip.close()
      }
      assertEquals(data["a/tokens.txt"]!!.toList(), File(dest, "a/tokens.txt").readBytes().toList())
      tmp.root.listFiles()!!.filter { it.name == "pack.zip" }.forEach { it.delete() }
    }
  }

  @Test fun aCorruptFileIsRejectedAndNeverLeftInPlace() {
    val src = folderWith()
    File(src, "a/model.onnx").also { it.writeBytes(it.readBytes().also { b -> b[1234] = (b[1234] + 1).toByte() }) }   // same size, one byte off
    val dest = tmp.newFolder()
    val r = installer(dest).install(FolderModelSource(src))
    assertTrue(r is AsrModelInstaller.Outcome.Incomplete)
    r as AsrModelInstaller.Outcome.Incomplete
    assertTrue(r.problems.single().contains("a/model.onnx") && r.problems.single().contains("checksum"))
    assertFalse(File(dest, "a/model.onnx").exists())
    assertFalse(File(dest, "a/model.onnx.part").exists())
    assertTrue("the good files are still installed", File(dest, "a/tokens.txt").isFile)
    assertFalse(installer(dest).complete())
  }

  @Test fun aTruncatedFileIsRejected() {
    val src = folderWith()
    File(src, "a/model.onnx").also { it.writeBytes(it.readBytes().copyOf(1_000_000)) }
    val dest = tmp.newFolder()
    val r = installer(dest).install(FolderModelSource(src)) as AsrModelInstaller.Outcome.Incomplete
    assertTrue(r.problems.single().contains("size"))
    assertFalse(File(dest, "a/model.onnx").exists())
  }

  @Test fun aMissingRequiredFileIsReportedAndAnOptionalOneIsNot() {
    val dest = tmp.newFolder()
    val r = installer(dest).install(FolderModelSource(folderWith("vad.onnx", "extra/alt.onnx"))) as AsrModelInstaller.Outcome.Incomplete
    assertEquals(1, r.problems.size)
    assertTrue(r.problems.single().startsWith("vad.onnx"))
    assertEquals(listOf("vad.onnx"), installer(dest).missing().map { it.path })
  }

  @Test fun anOptionalFileAloneDoesNotMakeItIncomplete() {
    val dest = tmp.newFolder()
    val r = installer(dest).install(FolderModelSource(folderWith("extra/alt.onnx")))
    assertTrue(r is AsrModelInstaller.Outcome.Ready)
    assertFalse(File(dest, "extra/alt.onnx").exists())
  }

  @Test fun filesAlreadyInPlaceAreNotCopiedAgain() {
    val dest = tmp.newFolder()
    installer(dest).install(FolderModelSource(folderWith()))
    var opened = 0
    val counting = object : AsrModelSource {
      override val label = "counting"
      override fun open(path: String): InputStream? { opened++; return null }
    }
    assertTrue(installer(dest).install(counting) is AsrModelInstaller.Outcome.Ready)
    assertEquals("nothing is opened when everything is in place", 0, opened)
  }

  @Test fun aStalePartFileFromACrashIsOverwritten() {
    val dest = tmp.newFolder()
    File(dest, "a").mkdirs()
    File(dest, "a/tokens.txt.part").writeText("garbage from a killed copy")
    assertTrue(installer(dest).install(FolderModelSource(folderWith())) is AsrModelInstaller.Outcome.Ready)
    assertEquals("hello", File(dest, "a/tokens.txt").readText())
  }

  @Test fun anInstalledFileOfTheWrongSizeIsReplaced() {
    val dest = tmp.newFolder()
    File(dest, "vad.onnx").apply { parentFile!!.mkdirs() }.writeText("older")
    assertEquals(listOf("a/model.onnx", "a/tokens.txt", "vad.onnx"), installer(dest).missing().map { it.path }.sorted())
    assertTrue(installer(dest).install(FolderModelSource(folderWith())) is AsrModelInstaller.Outcome.Ready)
    assertEquals("vad", File(dest, "vad.onnx").readText())
  }

  @Test fun downloadsLookupFindsTheFolderAndArchivesNewestFirst() {
    val dl = tmp.newFolder()
    val folder = File(dl, AsrModelPack.FOLDER_NAME).apply { mkdirs() }
    val old = File(dl, "findr-speech-models.zip").apply { writeBytes(zipBytes()); setLastModified(1_000) }
    val newer = File(dl, "findr-speech-models (1).zip").apply { writeBytes(zipBytes()); setLastModified(9_000) }
    File(dl, "unrelated.zip").writeBytes(zipBytes())
    File(dl, "findr-speech-models.zip.crdownload").writeBytes(ByteArray(1))
    val found = ModelInstaller.findAsrSources(dl.listFiles()!!.toList())
    try {
      assertEquals(listOf(folder.path, newer.path, old.path), found.map { it.label })
    } finally {
      found.forEach { it.close() }
    }
  }

  @Test fun progressCountsBytesToTheTotalOfWhatWasMissing() {
    val dest = tmp.newFolder()
    val seen = mutableListOf<Pair<Long, Long>>()
    installer(dest).install(FolderModelSource(folderWith())) { done, total -> seen += done to total }
    val total = manifest.filter { it.required }.sumOf { it.size }
    assertTrue(seen.isNotEmpty() && seen.all { it.second == total })
    assertEquals(total, seen.last().first)
    assertTrue("a 3 MB file reports more than once", seen.size > 3)
    assertTrue(seen.map { it.first }.zipWithNext().all { (a, b) -> b >= a })
    // Already installed: nothing missing, so a total of zero and no steps.
    val again = mutableListOf<Pair<Long, Long>>()
    installer(dest).install(FolderModelSource(folderWith())) { d, t -> again += d to t }
    assertTrue(again.none { it.first > 0 })
    assertEquals("everything in place counts, optional files too", manifest.sumOf { it.size }, installer(dest).installedBytes())
  }

  @Test fun anyPackCanUseTheDownloadsLookupWithItsOwnNames() {
    val dl = tmp.newFolder()
    val pack = PackFiles("x", manifest, "findr-quicklook-models", Regex("""findr-quicklook-models.*\.zip""", RegexOption.IGNORE_CASE))
    File(dl, "findr-quicklook-models.zip").writeBytes(zipBytes())
    File(dl, "findr-speech-models.zip").writeBytes(zipBytes())
    val found = findPackSources(dl.listFiles()!!.toList(), pack)
    try {
      assertEquals(listOf(File(dl, "findr-quicklook-models.zip").path), found.map { it.label })
    } finally {
      found.forEach { it.close() }
    }
  }

  @Test fun anArchiveThatCannotBeOpenedIsReportedNotDroppedInSilence() {
    val dl = tmp.newFolder()
    val pack = PackFiles("x", manifest, "findr-quicklook-models", Regex("""findr-quicklook-models.*\.zip""", RegexOption.IGNORE_CASE))
    File(dl, "findr-quicklook-models.zip").writeText("truncated")
    val bad = mutableListOf<String>()
    val found = findPackSources(dl.listFiles()!!.toList(), pack) { f, _ -> bad += f.name }
    assertTrue(found.isEmpty())
    assertEquals(listOf("findr-quicklook-models.zip"), bad)
  }

  @Test fun theRealManifestMatchesWhatTheSpeechEngineReads() {
    val paths = AsrModelPack.files.filter { it.required }.map { it.path }.toSet()
    SherpaAsrEngine.PARAKEET_FILES.forEach { assertTrue(it, "${SherpaAsrEngine.PARAKEET_DIR}/$it" in paths) }
    assertTrue(SherpaAsrEngine.VAD_FILE in paths)
    assertTrue("ced-mini-int8/model.int8.onnx" in paths && "${SherpaAsrEngine.CED_DIR}/class_labels_indices.csv" in paths)
    val moonshine = AsrModelPack.files.filter { !it.required }.map { it.path }.toSet()
    SherpaAsrEngine.MOONSHINE_FILES.forEach { assertTrue(it, "${SherpaAsrEngine.MOONSHINE_DIR}/$it" in moonshine) }
    assertTrue(AsrModelPack.files.all { it.sha256.length == 64 && it.size > 0 })
    assertNotNull(AsrModelPack.files.firstOrNull { it.path.endsWith("model.int8.onnx") })
  }

  private fun zipBytes(): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    ZipOutputStream(out).use { z -> z.putNextEntry(ZipEntry("x/y.txt")); z.write(1); z.closeEntry() }
    return out.toByteArray()
  }
}
