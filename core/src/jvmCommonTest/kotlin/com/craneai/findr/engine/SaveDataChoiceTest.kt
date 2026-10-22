package com.craneai.findr.engine

import com.craneai.findr.core.ChoiceKeyValue
import com.craneai.findr.core.FileKind
import com.craneai.findr.core.ModelChoice
import com.craneai.findr.core.ModelChoiceStore
import com.craneai.findr.core.ModelPlan
import com.craneai.findr.core.Refusal
import com.craneai.findr.index.CrawlRecord
import com.craneai.findr.index.FileState
import com.craneai.findr.index.Fp16
import com.craneai.findr.index.IndexStore
import com.craneai.findr.index.Modality
import com.craneai.findr.index.Segment
import com.craneai.findr.index.SegmentKind
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The save-data choice (internal/research/save-data-choice.md): Standard (670 MB) or Save data (304 MB, the lite bundle) on any
 * phone that can hold either; a 2 GB phone keeps its automatic lite. Signature checks are the installer's and are unchanged:
 * these tests drive the real [SignedBundleInstaller] with keys made per test, and the real [IndexStore] for the upgrade.
 */
class SaveDataChoiceTest {
  @get:Rule val tmp = TemporaryFolder()

  private class MapKv(val map: MutableMap<String, String> = HashMap()) : ChoiceKeyValue {
    override fun get(key: String) = map[key]
    override fun put(key: String, value: String) { map[key] = value }
  }

  // ---- persistence and the choice in force -------------------------------------------------------------------------------

  @Test fun theAnswerSurvivesARestart() {
    val kv = MapKv()
    val first = ModelChoiceStore(kv, BundleClass.STANDARD)
    assertNull(first.stored())
    assertEquals("no answer, nothing installed: the default is Standard", ModelChoice.STANDARD, first.effective(null))
    first.set(ModelChoice.SAVE_DATA)
    val afterRestart = ModelChoiceStore(kv, BundleClass.STANDARD)   // a new process over the same preferences
    assertEquals(ModelChoice.SAVE_DATA, afterRestart.stored())
    assertEquals(ModelChoice.SAVE_DATA, afterRestart.effective(null))
    afterRestart.set(ModelChoice.STANDARD)
    assertEquals(ModelChoice.STANDARD, ModelChoiceStore(kv, BundleClass.STANDARD).effective(null))
    assertEquals(ModelChoice.SAVE_DATA, ModelChoice.parse(ModelChoice.SAVE_DATA.id))
    assertNull("an unknown value is no answer", ModelChoice.parse("tiny"))
  }

  @Test fun aTwoGbPhoneIsAlwaysOnSaveDataAndStoresNothing() {
    val kv = MapKv()
    val store = ModelChoiceStore(kv, BundleClass.LITE)
    assertFalse(ModelPlan.offered(BundleClass.LITE))
    store.set(ModelChoice.STANDARD)   // ignored
    assertTrue(kv.map.isEmpty())
    assertEquals(ModelChoice.SAVE_DATA, store.effective(null))
    assertEquals(ModelChoice.SAVE_DATA, ModelChoiceStore(MapKv(hashMapOf(ModelChoiceStore.KEY to "standard")), BundleClass.LITE).effective(null))
    assertEquals("trawl-models-lite.zip", ModelPlan.archiveName(BundleClass.LITE, ModelChoice.STANDARD))
    assertEquals(setOf(BundleClass.LITE), ModelPlan.acceptClasses(BundleClass.LITE))
  }

  @Test fun theDownloadFollowsTheChoiceAndTheInstalledPackIsTheAnswer() {
    assertEquals("trawl-models.zip", ModelPlan.archiveName(BundleClass.STANDARD, ModelChoice.STANDARD))
    assertEquals("trawl-models-lite.zip", ModelPlan.archiveName(BundleClass.STANDARD, ModelChoice.SAVE_DATA))
    // The small file saved from the website, with no answer given in the app, still means Save data.
    assertEquals(ModelChoice.SAVE_DATA, ModelChoiceStore(MapKv(), BundleClass.STANDARD).effective(BundleClass.LITE))
    // An install records itself: standard over a stored Save data answer makes the answer Standard.
    val kv = MapKv()
    val store = ModelChoiceStore(kv, BundleClass.STANDARD)
    store.set(ModelChoice.SAVE_DATA)
    store.recordInstalled(BundleClass.STANDARD)
    assertEquals(ModelChoice.STANDARD, ModelChoiceStore(kv, BundleClass.STANDARD).effective(BundleClass.STANDARD))
    // The pack row says what is offered: Save data is not offered to someone who already holds Standard.
    assertTrue(ModelPlan.canChooseSaveData(BundleClass.STANDARD, null))
    assertTrue(ModelPlan.canChooseSaveData(BundleClass.STANDARD, BundleClass.LITE))
    assertFalse(ModelPlan.canChooseSaveData(BundleClass.STANDARD, BundleClass.STANDARD))
    assertTrue(ModelPlan.canUpgradeToStandard(BundleClass.STANDARD, BundleClass.LITE))
    assertFalse(ModelPlan.canUpgradeToStandard(BundleClass.LITE, BundleClass.LITE))
    assertTrue(ModelPlan.upgradesStack(BundleClass.LITE, BundleClass.STANDARD))
    assertFalse(ModelPlan.upgradesStack(null, BundleClass.STANDARD))
  }

  // ---- the installer: signed bundles, as strict as before ---------------------------------------------------------------

  private val textOnly = Embedder.TEXT_ONLY_NAME
  private val vision = "embeddinggemma-2-text-vision-440m.litertlm"
  private val textOnlyBytes = ByteArray(160_000) { (it % 211).toByte() }
  private val visionBytes = ByteArray(380_000) { (it % 241).toByte() }
  private val quick = ByteArray(110_000) { (it % 13).toByte() }
  private val privacy = ByteArray(20_000) { (it % 17).toByte() }
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

  private fun bundle(name: String, cls: String, version: Int, files: Map<String, ByteArray>, signed: Boolean = true): ByteArray {
    val arr = JSONArray()
    files.forEach { (p, b) -> arr.put(JSONObject().put("path", p).put("size", b.size).put("sha256", sha(b))) }
    val manifest = JSONObject().put("format", 1).put("bundle", name).put("class", cls).put("version", version).put("files", arr).toString().toByteArray()
    val bos = ByteArrayOutputStream()
    ZipOutputStream(bos).use { z ->
      fun put(n: String, b: ByteArray) {
        z.putNextEntry(ZipEntry(n).apply { method = ZipEntry.STORED; size = b.size.toLong(); compressedSize = b.size.toLong(); crc = java.util.zip.CRC32().also { it.update(b) }.value })
        z.write(b); z.closeEntry()
      }
      put(BundleManifest.ENTRY, manifest)
      put(BundleManifest.SIGNATURE_ENTRY, (if (signed) rawSig(manifest) else "AAAA").toByteArray())
      files.forEach { (p, b) -> put(p, b) }
    }
    return bos.toByteArray()
  }

  /** trawl-models-lite.zip as scripts/pack-bundle.sh makes it: the text-only model, quick look and the private-preview pack. No speech. */
  private fun liteBundle(version: Int = 1, signed: Boolean = true) =
    bundle("trawl-models-lite", "lite", version, mapOf(textOnly to textOnlyBytes, "findr-quicklook-models/tinyclip.onnx" to quick, "findr-privacy-models/blur.onnx" to privacy), signed)

  /** trawl-models.zip: the vision model, the same packs, and speech. */
  private fun standardBundle(version: Int = 1) =
    bundle("trawl-models", "standard", version, mapOf(vision to visionBytes, "findr-quicklook-models/tinyclip.onnx" to quick, "findr-privacy-models/blur.onnx" to privacy, "findr-speech-models/vad.onnx" to speech))

  /** The installer exactly as ModelDelivery builds it: acceptClasses comes from [ModelPlan], the one place that decides it. */
  private fun installer(dir: File, hardware: BundleClass) = SignedBundleInstaller(
    dir, hardware, Embedder.modelNames(MemoryProfile.LITE),
    mapOf("findr-quicklook-models" to "quicklook", "findr-privacy-models" to "privacy", "findr-speech-models" to "asr"),
    SignatureVerifier(listOf(spki)), emptySet(), { Long.MAX_VALUE }, ModelPlan.acceptClasses(hardware),
  )

  private fun refused(r: BundleResult) = (r as BundleResult.Refused).reason

  @Test fun aFourGbPhoneInstallsTheLiteBundleWhenItIsGivenOne() {
    // 4 GB class: the Galaxy A06 reports 3,633 MiB and gets the STANDARD hardware profile, so it may take either class.
    val hardware = BundleClass.of(MemoryProfile.choose(3_633, isLowRamDevice = false, memoryClassMb = 256))
    assertEquals(BundleClass.STANDARD, hardware)
    val dir = tmp.newFolder()
    val r = installer(dir, hardware).install(ByteArrayInputStream(liteBundle()))
    assertTrue(r.toString(), r is BundleResult.Installed)
    assertArrayEquals(textOnlyBytes, File(dir, textOnly).readBytes())
    assertTrue(File(dir, "quicklook/tinyclip.onnx").isFile)
    assertTrue("the private-preview pack comes with the lite bundle", File(dir, "privacy/blur.onnx").isFile)
    assertFalse("no speech in the small pack", File(dir, "asr").exists())
    assertEquals(BundleClass.LITE, InstalledBundle.read(dir)?.bundleClass)
    // What it holds counts as the person's answer, and the phone then runs the lite stack until a standard model arrives.
    assertEquals(ModelChoice.SAVE_DATA, ModelChoiceStore(object : ChoiceKeyValue { override fun get(key: String): String? = null; override fun put(key: String, value: String) {} }, hardware).effective(InstalledBundle.read(dir)?.bundleClass))
    assertEquals(MemoryProfile.LITE, MemoryProfile.adopt(MemoryProfile.STANDARD, null, hasStandardModel = false, hasTextOnlyModel = File(dir, textOnly).isFile))
  }

  @Test fun theSignatureChecksAreNotRelaxedForTheLiteBundle() {
    val dir = tmp.newFolder()
    assertEquals(Refusal.BAD_SIGNATURE, refused(installer(dir, BundleClass.STANDARD).install(ByteArrayInputStream(liteBundle(signed = false)))))
    // A bundle signed by some other key is refused too.
    val other = SignedBundleInstaller(
      dir, BundleClass.STANDARD, Embedder.modelNames(MemoryProfile.LITE), emptyMap(),
      SignatureVerifier(listOf(Base64.getEncoder().encodeToString(KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair().public.encoded))),
      emptySet(), { Long.MAX_VALUE }, ModelPlan.acceptClasses(BundleClass.STANDARD),
    )
    assertEquals(Refusal.BAD_SIGNATURE, refused(other.install(ByteArrayInputStream(liteBundle()))))
    assertFalse(File(dir, textOnly).exists())
  }

  @Test fun theTwoGbAutoPathIsUnchanged() {
    val hardware = BundleClass.of(MemoryProfile.choose(1_850, isLowRamDevice = false, memoryClassMb = 192))
    assertEquals(BundleClass.LITE, hardware)
    val dir = tmp.newFolder()
    // The big pack is refused on a 2 GB phone, as before the choice existed; the small one installs.
    assertEquals(Refusal.WRONG_CLASS, refused(installer(dir, hardware).install(ByteArrayInputStream(standardBundle()))))
    assertEquals(emptyList<String>(), dir.walkTopDown().filter { it.isFile }.map { it.name }.toList())
    assertTrue(installer(dir, hardware).install(ByteArrayInputStream(liteBundle())) is BundleResult.Installed)
    // No Standard offer, whatever is stored or installed.
    assertFalse(ModelPlan.canUpgradeToStandard(hardware, BundleClass.LITE))
    assertFalse(ModelPlan.canChooseSaveData(hardware, null))
    assertEquals(MemoryProfile.LITE, MemoryProfile.choose(1_850, false, 192))
  }

  @Test fun standardInstallsOverSaveDataAndKeepsWhatWasThere() {
    val dir = tmp.newFolder()
    val inst = installer(dir, BundleClass.STANDARD)
    assertTrue(inst.install(ByteArrayInputStream(liteBundle(version = 1))) is BundleResult.Installed)
    val before = InstalledBundle.read(dir)?.bundleClass
    assertEquals(BundleClass.LITE, before)
    val r = inst.install(ByteArrayInputStream(standardBundle(version = 1)))   // the same version number: a fuller class still upgrades
    assertTrue(r.toString(), r is BundleResult.Installed)
    assertEquals(BundleClass.STANDARD, InstalledBundle.read(dir)?.bundleClass)
    assertTrue(ModelPlan.upgradesStack(before, BundleClass.STANDARD))
    assertArrayEquals(visionBytes, File(dir, vision).readBytes())
    assertTrue(File(dir, "asr/vad.onnx").isFile)   // speech arrives with the standard pack
    assertTrue(File(dir, "quicklook/tinyclip.onnx").isFile)
    // And it does not go back: the small pack over the standard one is refused as older, nothing changes.
    assertEquals(Refusal.OLDER_VERSION, refused(inst.install(ByteArrayInputStream(liteBundle(version = 9)))))
    assertEquals(BundleClass.STANDARD, InstalledBundle.read(dir)?.bundleClass)
  }

  // ---- the index across the upgrade ---------------------------------------------------------------------------------------

  @Test fun theUpgradeKeepsTheIndexAndQueuesTheFilesToBeReadAgain() {
    val store = IndexStore.open(tmp.newFolder(), log = {})
    try {
      fun eg(seed: Int) = FloatArray(Fp16.DIM) { if (it == seed) 1f else 0f }
      fun q(seed: Int) = FloatArray(IndexStore.QUICK_DIM) { if (it == seed) 1f else 0f }
      fun add(id: Long, name: String, kind: FileKind): Long {
        store.upsertFromCrawl(listOf(CrawlRecord("external_primary", id, "content://media/external/file/$id", "DCIM/", name, kind, null, 50_000, 1_000)))
        return store.knownMediaStoreIds("external_primary").getValue(id)
      }
      val liteFp = "lite:tinyclip"
      val standardFp = "$vision:vt70"
      // A person on Save data: the lite worker has read a screenshot (OCR words + a text vector + a quick-look vector) and a photo.
      assertFalse(store.ensureModelFingerprint(liteFp))
      store.ensureTextFingerprint(textOnly)
      val shot = add(1, "receipt.png", FileKind.SCREENSHOT)
      store.saveSegments(shot, listOf(
        Segment(SegmentKind.OCR, Modality.OCR_TEXT, text = "airtime top up 5000", vector = eg(1)),
        Segment(SegmentKind.IMAGE, Modality.CLIP, vector = q(2)),
      ), FileState.UNDERSTOOD)
      val photo = add(2, "market.jpg", FileKind.IMAGE)
      store.saveSegments(photo, listOf(Segment(SegmentKind.IMAGE, Modality.CLIP, vector = q(3))), FileState.UNDERSTOOD)
      fun count(sql: String, vararg a: Any) = store.read { it.long(sql, *a) } ?: 0L
      val filesBefore = count("SELECT COUNT(*) FROM files")
      val nameRowsBefore = count("SELECT COUNT(*) FROM segments WHERE kind = 'name'")

      // The standard pack arrives; on the next start the engine runs the standard stack. Exactly what IndexWorker does.
      val keepStale = Embedder.keepStaleOnChange(store.meta(com.craneai.findr.index.Schema.Meta.MODEL_FINGERPRINT) ?: liteFp, standardFp)
      assertFalse("a change out of lite reads every file again", keepStale)
      assertTrue(store.ensureModelFingerprint(standardFp, keepStale = keepStale))
      store.ensureTextFingerprint(vision)

      assertEquals("no file row is lost", filesBefore, count("SELECT COUNT(*) FROM files"))
      assertEquals("names stay", nameRowsBefore, count("SELECT COUNT(*) FROM segments WHERE kind = 'name'"))
      assertEquals("the words inside screenshots and documents come back only once the file is read again", 0L, store.read { db -> var n = 0L; db.query("SELECT rowid FROM fts WHERE fts MATCH 'airtime'") { n++ }; n })
      assertEquals("quick-look vectors keep photos searchable until they are read again", 2L, count("SELECT COUNT(*) FROM segments WHERE modality = 'clip' AND vec IS NOT NULL"))
      assertEquals("the old text model's vectors are dropped", 0L, count("SELECT COUNT(*) FROM segments WHERE modality = 'ocr_text'"))
      assertEquals("queued to be read with the better model", FileState.SEARCHABLE, store.stateOf(shot)!!.first)
      assertEquals(FileState.SEARCHABLE, store.stateOf(photo)!!.first)
    } finally {
      store.close()
    }
  }
}
