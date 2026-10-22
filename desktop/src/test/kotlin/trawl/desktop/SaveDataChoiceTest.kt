package trawl.desktop

import com.craneai.findr.core.ModelChoice
import com.craneai.findr.core.Refusal
import com.craneai.findr.engine.BundleClass
import com.craneai.findr.engine.BundleManifest
import com.craneai.findr.engine.BundleResult
import com.craneai.findr.engine.Embedder
import com.craneai.findr.engine.SignatureVerifier
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.json.JSONArray
import org.json.JSONObject

/**
 * The save-data choice on a computer (internal/research/save-data-choice.md): the small pack (304 MB, the text-only 270m model) is
 * accepted on any computer, Standard stays the default, a small computer keeps its automatic small pack, and the signature checks
 * are the installer's own. Bundles are signed here with a key made per test and passed in as the only trusted key.
 */
class SaveDataChoiceTest {
  private fun tmp() = Files.createTempDirectory("trawl-choice").toFile()

  private val textOnly = Embedder.TEXT_ONLY_NAME
  private val vision = Embedder.MODEL_NAMES.first()
  private val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
  private val verifier = SignatureVerifier(listOf(Base64.getEncoder().encodeToString(pair.public.encoded)))

  private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

  private fun rawSig(manifest: ByteArray): String {
    val der = Signature.getInstance("SHA256withECDSA").run { initSign(pair.private); update(manifest); sign() }
    var i = 3
    val rl = der[i++].toInt(); val r = der.copyOfRange(i, i + rl); i += rl + 1
    val sl = der[i++].toInt(); val s = der.copyOfRange(i, i + sl)
    fun fix(x: ByteArray) = ByteArray(32).also { out -> val t = x.dropWhile { it == 0.toByte() }.toByteArray(); t.copyInto(out, 32 - t.size) }
    return Base64.getUrlEncoder().withoutPadding().encodeToString(fix(r) + fix(s))
  }

  private fun bundle(dir: File, fileName: String, name: String, cls: String, files: Map<String, ByteArray>, signed: Boolean = true): File {
    val arr = JSONArray()
    files.forEach { (p, b) -> arr.put(JSONObject().put("path", p).put("size", b.size).put("sha256", sha(b))) }
    val manifest = JSONObject().put("format", 1).put("bundle", name).put("class", cls).put("version", 1).put("files", arr).toString().toByteArray()
    val bos = ByteArrayOutputStream()
    ZipOutputStream(bos).use { z ->
      fun put(n: String, b: ByteArray) {
        z.putNextEntry(ZipEntry(n).apply { method = ZipEntry.STORED; size = b.size.toLong(); compressedSize = b.size.toLong(); crc = CRC32().also { it.update(b) }.value })
        z.write(b); z.closeEntry()
      }
      put(BundleManifest.ENTRY, manifest)
      put(BundleManifest.SIGNATURE_ENTRY, (if (signed) rawSig(manifest) else "AAAA").toByteArray())
      files.forEach { (p, b) -> put(p, b) }
    }
    return File(dir, fileName).also { it.writeBytes(bos.toByteArray()) }
  }

  private fun lite(dir: File, signed: Boolean = true) =
    bundle(dir, "trawl-models-lite.zip", "trawl-models-lite", "lite", mapOf(textOnly to ByteArray(50_000) { (it % 211).toByte() }, "findr-privacy-models/blur.onnx" to ByteArray(5_000)), signed)

  private fun standard(dir: File) =
    bundle(dir, "trawl-models.zip", "trawl-models", "standard", mapOf(vision to ByteArray(120_000) { (it % 241).toByte() }, "findr-privacy-models/blur.onnx" to ByteArray(5_000)))

  private fun setup(d: File, ramMb: Long) = ModelSetup(File(d, "data"), ramMb, verifier)

  @Test fun aComputerAcceptsTheSmallPackAndItCountsAsSaveData() {
    val d = tmp()
    val s = setup(d, 16_000)
    assertTrue(s.choiceOffered)
    assertEquals(ModelChoice.STANDARD, s.effectiveChoice(null), "nothing said, nothing installed: Standard is the default")
    val r = s.install(lite(d))
    assertTrue(r is BundleResult.Installed, "got $r")
    assertEquals(BundleClass.LITE, s.installedClass())
    assertEquals(ModelChoice.SAVE_DATA, s.installedChoice())
    assertEquals(ModelChoice.SAVE_DATA, s.effectiveChoice(null), "the file saved from the website is the answer")
    assertEquals(textOnly, s.installedModel()?.name)
    assertTrue(ModelSetup.isTextOnly(s.installedModel()!!))
    d.deleteRecursively()
  }

  @Test fun standardInstallsOverSaveDataAndTheSmallModelFileGoes() {
    val d = tmp()
    val s = setup(d, 16_000)
    assertTrue(s.install(lite(d)) is BundleResult.Installed)
    val smallFile = File(s.modelsDir, textOnly)
    assertTrue(smallFile.isFile)
    val r = s.install(standard(d))
    assertTrue(r is BundleResult.Installed, "got $r")
    assertEquals(ModelChoice.STANDARD, s.installedChoice())
    assertEquals(vision, s.installedModel()?.name)
    assertFalse(smallFile.exists(), "the small model is not used any more, so its disk comes back")
    // And it does not go back: the small pack over the standard one changes nothing.
    val back = s.install(lite(d))
    assertTrue(back is BundleResult.Refused && back.reason == Refusal.OLDER_VERSION, "got $back")
    assertEquals(ModelChoice.STANDARD, s.installedChoice())
    d.deleteRecursively()
  }

  @Test fun aSmallComputerKeepsItsAutomaticSmallPackAndIsNeverAsked() {
    val d = tmp()
    val s = setup(d, 1_900)
    assertFalse(s.choiceOffered)
    assertEquals(BundleClass.LITE, s.hardware)
    assertEquals(ModelChoice.SAVE_DATA, s.effectiveChoice(ModelChoice.STANDARD), "even a stored Standard answer is ignored here")
    val r = s.install(standard(d))
    assertTrue(r is BundleResult.Refused && r.reason == Refusal.WRONG_CLASS, "got $r")
    assertEquals("refusal_wrong_class_lite", ModelSetup.refusalKey(Refusal.WRONG_CLASS, s.profile))
    assertNull(s.installedModel())
    assertTrue(s.install(lite(d)) is BundleResult.Installed)
    assertEquals(textOnly, s.installedModel()?.name)
    d.deleteRecursively()
  }

  @Test fun theSignatureChecksAreNotRelaxedForTheSmallPack() {
    val d = tmp()
    val s = setup(d, 16_000)
    val r = s.install(lite(d, signed = false))
    assertTrue(r is BundleResult.Refused && r.reason == Refusal.BAD_SIGNATURE, "got $r")
    assertNull(s.installedModel())
    // The real app, with only the real keys, refuses a bundle signed by the test key.
    val real = ModelSetup(File(d, "data2"), 16_000).install(lite(d))
    assertTrue(real is BundleResult.Refused && real.reason == Refusal.BAD_SIGNATURE, "got $real")
    d.deleteRecursively()
  }

  @Test fun theAnswerSurvivesARestart() {
    val d = tmp()
    SettingsStore(d).save(Settings(modelChoice = ModelChoice.SAVE_DATA))
    assertEquals(ModelChoice.SAVE_DATA, SettingsStore(d).load().modelChoice)
    SettingsStore(d).save(Settings())
    assertNull(SettingsStore(d).load().modelChoice, "no answer is no answer")
    d.deleteRecursively()
  }

  @Test fun findBundlePrefersTheChosenPackAndSkipsTheSmallOneForAnUpgrade() {
    val d = tmp()
    val std = File(d, "trawl-models.zip").apply { writeText("a"); setLastModified(1000) }
    val small = File(d, "trawl-models-lite.zip").apply { writeText("b"); setLastModified(5000) }
    val s = setup(d, 16_000)
    assertEquals(small.name, s.findBundle(listOf(d))?.name, "no preference: the newest")
    assertEquals(std.name, s.findBundle(listOf(d), prefer = ModelChoice.STANDARD)?.name)
    assertEquals(small.name, s.findBundle(listOf(d), prefer = ModelChoice.SAVE_DATA)?.name)
    assertEquals(std.name, s.findBundle(listOf(d), standardOnly = true)?.name, "upgrading from the small pack never offers the small file")
    small.delete()
    assertNotNull(s.findBundle(listOf(d), prefer = ModelChoice.SAVE_DATA), "the other pack is still found when the chosen one is missing")
    d.deleteRecursively()
  }

  @Test fun theSizesShownAreTheSizesTheAppKnowsAbout() {
    val known = listOf(File("../app/src/main/assets/models-known.json"), File("app/src/main/assets/models-known.json")).first { it.isFile }
    val classes = JSONObject(known.readText()).getJSONObject("classes")
    assertEquals(classes.getJSONObject("standard").getInt("size_mb"), ModelSetup.STANDARD_MB)
    assertEquals(classes.getJSONObject("lite").getInt("size_mb"), ModelSetup.SAVE_DATA_MB)
    assertEquals("trawl-models.zip", ModelSetup.archiveName(BundleClass.STANDARD, ModelChoice.STANDARD))
    assertEquals("trawl-models-lite.zip", ModelSetup.archiveName(BundleClass.STANDARD, ModelChoice.SAVE_DATA))
  }

  @Test fun onlyTheTwoSeventyMModelIsTextOnly() {
    assertTrue(ModelSetup.isTextOnly(File("/m/$textOnly")))
    assertFalse(ModelSetup.isTextOnly(File("/m/$vision")))
    assertTrue(ModelSetup.isLiteName("trawl-models-lite (1).zip"))
    assertFalse(ModelSetup.isLiteName("trawl-models.zip"))
  }
}
