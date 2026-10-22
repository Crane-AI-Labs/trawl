package com.craneai.findr.engine

import com.craneai.findr.core.Refusal
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The lean first download (internal/research/lean-download.md): a small core bundle first (the lite class: text-only model plus
 * quick look), packs as separate signed add-ons, and a later vision bundle that upgrades the core. Every rule here is the
 * installer's, tested off the phone with keys made per test.
 */
class LeanBundleTest {
  @get:Rule val tmp = TemporaryFolder()

  private val textOnly = Embedder.TEXT_ONLY_NAME
  private val vision = "embeddinggemma-2-text-vision-440m.litertlm"
  private val textOnlyBytes = ByteArray(160_000) { (it % 211).toByte() }
  private val visionBytes = ByteArray(380_000) { (it % 241).toByte() }
  private val quick = ByteArray(110_000) { (it % 13).toByte() }
  private val speech = ByteArray(90_000) { (it % 7).toByte() }

  private val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
  private val spki = Base64.getEncoder().encodeToString(pair.public.encoded)

  private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

  private fun rawSig(manifest: ByteArray): String {
    val der = Signature.getInstance("SHA256withECDSA").run { initSign(pair.private); update(manifest); sign() }
    var i = 3
    val rl = der[i++].toInt(); val r = der.copyOfRange(i, i + rl); i += rl + 1
    val sl = der[i++].toInt(); val s = der.copyOfRange(i, i + sl)
    fun fix(x: ByteArray) = ByteArray(32).also { out -> val t = x.dropWhile { it == 0.toByte() }.toByteArray(); t.copyInto(out, 32 - t.size) }
    return Base64.getUrlEncoder().withoutPadding().encodeToString(fix(r) + fix(s))
  }

  /** A signed stored zip of [files]; [role] and [cls] go into the manifest as given (role null leaves the field out, as today's bundles do). */
  private fun bundle(name: String, cls: String, version: Int, files: Map<String, ByteArray>, role: String? = null): ByteArray {
    val arr = JSONArray()
    files.forEach { (p, b) -> arr.put(JSONObject().put("path", p).put("size", b.size).put("sha256", sha(b))) }
    val manifest = JSONObject().put("format", 1).put("bundle", name).put("class", cls).put("version", version).put("files", arr)
      .also { if (role != null) it.put("role", role) }.toString().toByteArray()
    val bos = ByteArrayOutputStream()
    ZipOutputStream(bos).use { z ->
      fun put(n: String, b: ByteArray) {
        z.putNextEntry(ZipEntry(n).apply { method = ZipEntry.STORED; size = b.size.toLong(); compressedSize = b.size.toLong(); crc = java.util.zip.CRC32().also { it.update(b) }.value })
        z.write(b); z.closeEntry()
      }
      put(BundleManifest.ENTRY, manifest)
      put(BundleManifest.SIGNATURE_ENTRY, rawSig(manifest).toByteArray())
      files.forEach { (p, b) -> put(p, b) }
    }
    return bos.toByteArray()
  }

  private val coreFiles = mapOf(textOnly to textOnlyBytes, "findr-quicklook-models/tinyclip.onnx" to quick)
  private fun core(version: Int = 1) = bundle("trawl-models-core", "lite", version, coreFiles)
  private fun visionUpgrade(version: Int = 1) = bundle("trawl-models-vision", "standard", version, mapOf(vision to visionBytes))
  private fun speechAddon(version: Int = 1, bytes: ByteArray = speech) =
    bundle("trawl-models-speech", "any", version, mapOf("findr-speech-models/vad.onnx" to bytes), role = "addon")

  private fun installer(
    dir: File,
    cls: BundleClass = BundleClass.STANDARD,
    accept: Set<BundleClass> = setOf(cls),
    skip: Set<String> = emptySet(),
  ) = SignedBundleInstaller(
    dir, cls, Embedder.modelNames(MemoryProfile.LITE), mapOf("findr-quicklook-models" to "quicklook", "findr-speech-models" to "asr"),
    SignatureVerifier(listOf(spki)), skip, { Long.MAX_VALUE }, accept,
  )

  private fun refused(r: BundleResult) = (r as BundleResult.Refused).reason
  private fun snapshot(dir: File) = dir.walkTopDown().filter { it.isFile }.map { it.relativeTo(dir).path + ":" + it.length() }.sorted().toList()

  // ---- the core bundle on a phone that would get the standard stack ---------------------------------------------------

  @Test fun aStandardPhoneRefusesTheCoreBundleUnlessItAcceptsTheLiteClass() {
    val dir = tmp.newFolder()
    assertEquals(Refusal.WRONG_CLASS, refused(installer(dir).install(ByteArrayInputStream(core()))))
    assertEquals(emptyList<String>(), snapshot(dir))
    val r = installer(dir, accept = setOf(BundleClass.STANDARD, BundleClass.LITE)).install(ByteArrayInputStream(core()))
    assertTrue(r.toString(), r is BundleResult.Installed)
    assertArrayEquals(textOnlyBytes, File(dir, textOnly).readBytes())
    assertArrayEquals(quick, File(dir, "quicklook/tinyclip.onnx").readBytes())
    assertEquals(BundleClass.LITE, InstalledBundle.read(dir)?.bundleClass)
  }

  @Test fun theVisionBundleUpgradesTheCoreWhateverItsVersionNumberIs() {
    val dir = tmp.newFolder()
    val both = setOf(BundleClass.STANDARD, BundleClass.LITE)
    installer(dir, accept = both).install(ByteArrayInputStream(core(version = 5)))
    val r = installer(dir, accept = both).install(ByteArrayInputStream(visionUpgrade(version = 1)))
    assertTrue(r.toString(), r is BundleResult.Installed)
    assertArrayEquals(visionBytes, File(dir, vision).readBytes())
    assertEquals(BundleClass.STANDARD, InstalledBundle.read(dir)?.bundleClass)
    assertEquals(1, InstalledBundle.read(dir)?.version)
    // The core's quick look stays: the vision bundle carries no pack, and an install only ever touches what its manifest lists.
    assertTrue(File(dir, "quicklook/tinyclip.onnx").isFile)
  }

  @Test fun aLighterBundleNeverReplacesAFullerModel() {
    val dir = tmp.newFolder()
    val both = setOf(BundleClass.STANDARD, BundleClass.LITE)
    installer(dir, accept = both).install(ByteArrayInputStream(visionUpgrade()))
    val before = snapshot(dir)
    assertEquals(Refusal.OLDER_VERSION, refused(installer(dir, accept = both).install(ByteArrayInputStream(core(version = 9)))))
    assertEquals(before, snapshot(dir))
  }

  @Test fun theSameClassStillComparesVersions() {
    val dir = tmp.newFolder()
    val both = setOf(BundleClass.STANDARD, BundleClass.LITE)
    installer(dir, accept = both).install(ByteArrayInputStream(core(version = 2)))
    assertEquals(Refusal.ALREADY_INSTALLED, refused(installer(dir, accept = both).install(ByteArrayInputStream(core(version = 2)))))
    assertEquals(Refusal.OLDER_VERSION, refused(installer(dir, accept = both).install(ByteArrayInputStream(core(version = 1)))))
    assertTrue(installer(dir, accept = both).install(ByteArrayInputStream(core(version = 3))) is BundleResult.Installed)
  }

  // ---- add-on bundles -------------------------------------------------------------------------------------------------

  @Test fun anAddonInstallsWithNoModelOnThePhoneAndLeavesTheModelRecordAlone() {
    val dir = tmp.newFolder()
    val r = installer(dir).install(ByteArrayInputStream(speechAddon()))
    assertTrue(r.toString(), r is BundleResult.Installed)
    assertArrayEquals(speech, File(dir, "asr/vad.onnx").readBytes())
    assertNull(InstalledBundle.read(dir))
    assertEquals(mapOf("trawl-models-speech" to 1), InstalledAddons.read(dir))
    assertFalse(File(dir, ".incoming").exists())
  }

  @Test fun anAddonFitsEveryPhoneClass() {
    for (cls in listOf(BundleClass.LITE, BundleClass.STANDARD)) {
      val dir = tmp.newFolder()
      assertTrue(installer(dir, cls).install(ByteArrayInputStream(speechAddon())) is BundleResult.Installed)
    }
  }

  @Test fun anAddonDoesNotDisturbAnInstalledModel() {
    val dir = tmp.newFolder()
    val both = setOf(BundleClass.STANDARD, BundleClass.LITE)
    installer(dir, accept = both).install(ByteArrayInputStream(core(version = 4)))
    assertTrue(installer(dir, accept = both).install(ByteArrayInputStream(speechAddon())) is BundleResult.Installed)
    assertEquals(4, InstalledBundle.read(dir)?.version)
    assertArrayEquals(textOnlyBytes, File(dir, textOnly).readBytes())
  }

  @Test fun anAddonOnPurposeInstallsEvenWhereThePackWasDeclined() {
    val dir = tmp.newFolder()
    val r = installer(dir, skip = setOf("findr-speech-models")).install(ByteArrayInputStream(speechAddon()))
    assertTrue(r is BundleResult.Installed)
    assertTrue(File(dir, "asr/vad.onnx").isFile)
  }

  @Test fun anAddonTheSameVersionIsAlreadyInstalledUntilItsFilesAreGone() {
    val dir = tmp.newFolder()
    installer(dir).install(ByteArrayInputStream(speechAddon()))
    assertEquals(Refusal.ALREADY_INSTALLED, refused(installer(dir).install(ByteArrayInputStream(speechAddon()))))
    File(dir, "asr/vad.onnx").delete()   // the person removed the pack
    assertTrue(installer(dir).install(ByteArrayInputStream(speechAddon())) is BundleResult.Installed)
    assertTrue(File(dir, "asr/vad.onnx").isFile)
  }

  @Test fun anOlderAddonIsRefusedAndANewerOneReplaces() {
    val dir = tmp.newFolder()
    installer(dir).install(ByteArrayInputStream(speechAddon(version = 2)))
    assertEquals(Refusal.OLDER_VERSION, refused(installer(dir).install(ByteArrayInputStream(speechAddon(version = 1)))))
    val newer = ByteArray(95_000) { 3 }
    assertTrue(installer(dir).install(ByteArrayInputStream(speechAddon(version = 3, bytes = newer))) is BundleResult.Installed)
    assertEquals(95_000L, File(dir, "asr/vad.onnx").length())
    assertEquals(3, InstalledAddons.read(dir)["trawl-models-speech"])
  }

  @Test fun anAddonWithAFolderTheAppDoesNotKnowIsRefused() {
    val dir = tmp.newFolder()
    val unknown = bundle("trawl-models-klingon", "any", 1, mapOf("findr-klingon-models/a.onnx" to speech), role = "addon")
    assertEquals(Refusal.BAD_MANIFEST, refused(installer(dir).install(ByteArrayInputStream(unknown))))
    assertEquals(emptyList<String>(), snapshot(dir))
  }

  @Test fun aFlippedByteInAnAddonIsRefusedAndNothingIsInstalled() {
    val dir = tmp.newFolder()
    val bytes = speechAddon()
    val at = bytes.size / 2   // inside the stored file data, not the directory at the end
    bytes[at] = (bytes[at].toInt() xor 1).toByte()
    assertEquals(Refusal.DAMAGED, refused(installer(dir).install(ByteArrayInputStream(bytes))))
    assertEquals(emptyList<String>(), snapshot(dir))
    assertTrue(InstalledAddons.read(dir).isEmpty())
  }

  // ---- the manifest's role and class rules -----------------------------------------------------------------------------

  @Test fun anAddonMustBeClassAnyAndAFullBundleMustNot() {
    val file = mapOf("findr-speech-models/vad.onnx" to speech)
    assertNull(parse(bundle("x", "lite", 1, file, role = "addon")))   // an add-on that names a phone class
    assertNull(parse(bundle("x", "any", 1, file)))   // a model bundle that claims every phone
    assertNull(parse(bundle("x", "any", 1, mapOf(textOnly to textOnlyBytes), role = "addon")))   // an add-on carrying a root file (a model)
    assertNull(parse(bundle("x", "any", 1, file, role = "surprise")))
    assertNotNull(parse(bundle("x", "any", 1, file, role = "addon")))
    assertEquals(BundleRole.FULL, parse(bundle("x", "lite", 1, coreFiles))!!.role)   // today's manifests have no role field
  }

  @Test fun anOldStyleLiteOrStandardManifestStillParsesAsAFullBundle() {
    val m = parse(bundle("trawl-models-lite", "lite", 1, coreFiles))!!
    assertEquals(BundleClass.LITE, m.bundleClass)
    assertEquals(BundleRole.FULL, m.role)
  }

  @Test fun classRanksOrderTheStacks() {
    assertTrue(BundleClass.STANDARD.rank > BundleClass.LITE.rank)
    assertTrue(BundleClass.LITE.rank > BundleClass.ANY.rank)
    assertEquals(BundleClass.ANY, BundleClass.parse("any"))
  }

  private fun parse(zip: ByteArray): BundleManifest? {
    // the manifest is the first entry
    val zin = java.util.zip.ZipInputStream(ByteArrayInputStream(zip))
    zin.nextEntry
    return BundleManifest.parse(zin.readBytes())
  }
}
