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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The signed-bundle gate: a good bundle installs; a flipped byte, a wrong key, an unsigned or reordered zip, the wrong phone
 * class and an older version are each refused, and a refused bundle leaves the models folder exactly as it was. Keys are made
 * per test; the signature is converted to the raw r||s form Key Vault returns, so the app's conversion is exercised too.
 */
class SignedBundleTest {
  @get:Rule val tmp = TemporaryFolder()

  private val modelName = "embeddinggemma-2-text-vision-440m.litertlm"
  private val model = ByteArray(300_000) { (it % 241).toByte() }
  private val quick = ByteArray(120_000) { (it % 13).toByte() }
  private val speech = ByteArray(90_000) { (it % 7).toByte() }

  private class Key {
    val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    val spki: String = Base64.getEncoder().encodeToString(pair.public.encoded)
  }
  private val key1 = Key()
  private val key2 = Key()
  private val stranger = Key()

  private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

  /** DER (what Java signs) to raw r||s, base64url: what `az keyvault key sign` returns and pack-bundle.sh writes. */
  private fun akvStyle(der: ByteArray): String {
    var i = 3
    val rl = der[i++].toInt(); val r = der.copyOfRange(i, i + rl); i += rl + 1
    val sl = der[i++].toInt(); val s = der.copyOfRange(i, i + sl)
    fun fix(x: ByteArray) = ByteArray(32).also { out -> val t = x.dropWhile { it == 0.toByte() }.toByteArray(); t.copyInto(out, 32 - t.size) }
    return Base64.getUrlEncoder().withoutPadding().encodeToString(fix(r) + fix(s))
  }

  private fun sign(k: Key, manifest: ByteArray): String =
    akvStyle(Signature.getInstance("SHA256withECDSA").run { initSign(k.pair.private); update(manifest); sign() })

  private val standardFiles = mapOf(modelName to model, "findr-quicklook-models/tinyclip.onnx" to quick, "findr-speech-models/vad.onnx" to speech)

  private fun manifest(cls: String = "standard", version: Int = 1, files: Map<String, ByteArray> = standardFiles, tweak: (JSONObject) -> Unit = {}): ByteArray {
    val arr = JSONArray()
    files.forEach { (p, b) -> arr.put(JSONObject().put("path", p).put("size", b.size).put("sha256", sha(b))) }
    return JSONObject().put("format", 1).put("bundle", "trawl-models").put("class", cls).put("version", version).put("files", arr)
      .also(tweak).toString().toByteArray()
  }

  /** Builds a stored zip: manifest, signature, then the files, unless told otherwise. */
  private fun bundle(
    signer: Key? = key1,
    manifest: ByteArray = manifest(),
    files: Map<String, ByteArray> = standardFiles,
    signed: Boolean = true,
    sigFirst: Boolean = false,
    signedOver: ByteArray = manifest,
    mutate: (ByteArray) -> Unit = {},
  ): ByteArray {
    val bos = ByteArrayOutputStream()
    ZipOutputStream(bos).use { z ->
      fun put(name: String, b: ByteArray) {
        val e = ZipEntry(name).apply { method = ZipEntry.STORED; size = b.size.toLong(); compressedSize = b.size.toLong(); crc = java.util.zip.CRC32().also { it.update(b) }.value }
        z.putNextEntry(e); z.write(b); z.closeEntry()
      }
      val sig = signer?.let { sign(it, signedOver).toByteArray() }
      if (signed && sigFirst && sig != null) put(BundleManifest.SIGNATURE_ENTRY, sig)
      if (signed) put(BundleManifest.ENTRY, manifest)
      if (signed && !sigFirst && sig != null) put(BundleManifest.SIGNATURE_ENTRY, sig)
      files.forEach { (p, b) -> put(p, b) }
    }
    return bos.toByteArray().also(mutate)
  }

  private fun installer(
    dir: File = tmp.newFolder(),
    cls: BundleClass = BundleClass.STANDARD,
    trusted: List<String> = listOf(key1.spki, key2.spki),
    skip: Set<String> = emptySet(),
    free: Long = Long.MAX_VALUE,
  ) = SignedBundleInstaller(
    dir, cls, listOf(modelName), mapOf("findr-quicklook-models" to "quicklook", "findr-speech-models" to "asr"),
    SignatureVerifier(trusted), skip, { free },
  )

  private fun refused(r: BundleResult): Refusal = (r as BundleResult.Refused).reason

  private fun snapshot(dir: File): List<String> = dir.walkTopDown().filter { it.isFile }.map { it.relativeTo(dir).path + ":" + it.length() }.sorted().toList()

  @Test fun goodBundleInstallsEveryFileAndRecordsTheVersion() {
    val dir = tmp.newFolder()
    val r = installer(dir).install(ByteArrayInputStream(bundle()))
    assertTrue(r.toString(), r is BundleResult.Installed)
    assertArrayEquals(model, File(dir, modelName).readBytes())
    assertArrayEquals(quick, File(dir, "quicklook/tinyclip.onnx").readBytes())
    assertArrayEquals(speech, File(dir, "asr/vad.onnx").readBytes())
    assertEquals(1, InstalledBundle.read(dir)?.version)
    assertFalse(File(dir, ".incoming").exists())
    assertTrue(dir.walkTopDown().none { it.name.endsWith(".part") })
  }

  @Test fun progressReachesTheTotalAndNeverPassesIt() {
    var last = 0L; var total = 0L
    installer().install(ByteArrayInputStream(bundle()), { d, t -> last = d; total = t })
    assertEquals(standardFiles.values.sumOf { it.size }.toLong(), total)
    assertEquals(total, last)
  }

  @Test fun oneFlippedByteInAFileIsRefusedAndNothingIsInstalled() {
    val dir = tmp.newFolder()
    val bytes = bundle()
    // Flip a byte deep inside the model's data (it is the first file after the two small entries).
    bytes[bytes.size / 2] = (bytes[bytes.size / 2].toInt() xor 1).toByte()
    assertEquals(Refusal.DAMAGED, refused(installer(dir).install(ByteArrayInputStream(bytes))))
    assertEquals(emptyList<String>(), snapshot(dir))
  }

  @Test fun aManifestChangedAfterSigningBreaksTheSignature() {
    val dir = tmp.newFolder()
    // An attacker rebuilds the zip (so every CRC is right) with a different manifest but keeps the original signature.
    val original = manifest(version = 1)
    val altered = manifest(version = 9)
    assertEquals(Refusal.BAD_SIGNATURE, refused(installer(dir).install(ByteArrayInputStream(bundle(manifest = altered, signedOver = original)))))
    assertEquals(emptyList<String>(), snapshot(dir))
  }

  @Test fun aByteFlippedInTheManifestInsideTheZipIsRefused() {
    val bytes = bundle()
    val at = String(bytes, Charsets.ISO_8859_1).indexOf("\"version\":1") + 10
    bytes[at] = '2'.code.toByte()
    // The zip's own CRC catches this one first; either way it is refused and never installed.
    assertTrue(refused(installer().install(ByteArrayInputStream(bytes))) in setOf(Refusal.DAMAGED, Refusal.BAD_SIGNATURE, Refusal.NOT_SIGNED))
  }

  @Test fun aBundleSignedByAnUntrustedKeyIsRefused() {
    assertEquals(Refusal.BAD_SIGNATURE, refused(installer().install(ByteArrayInputStream(bundle(signer = stranger)))))
  }

  @Test fun theRotatedSpareKeyIsAccepted() {
    val r = installer(trusted = listOf(key1.spki, key2.spki)).install(ByteArrayInputStream(bundle(signer = key2)))
    assertTrue(r is BundleResult.Installed)
  }

  @Test fun aRetiredKeyIsRefusedOnceDroppedFromTheApp() {
    // The app of a later release trusts only the spare: a bundle still signed by key 1 no longer installs.
    assertEquals(Refusal.BAD_SIGNATURE, refused(installer(trusted = listOf(key2.spki)).install(ByteArrayInputStream(bundle(signer = key1)))))
  }

  @Test fun anUnsignedBundleIsRefused() {
    assertEquals(Refusal.NOT_SIGNED, refused(installer().install(ByteArrayInputStream(bundle(signed = false)))))
  }

  @Test fun aManifestWithoutASignatureIsRefused() {
    assertEquals(Refusal.NOT_SIGNED, refused(installer().install(ByteArrayInputStream(bundle(signer = null)))))
  }

  @Test fun aSignatureBeforeTheManifestIsNotAcceptedAsSigned() {
    assertEquals(Refusal.NOT_SIGNED, refused(installer().install(ByteArrayInputStream(bundle(sigFirst = true)))))
  }

  @Test fun notAZipAtAllIsRefusedAsNotSigned() {
    assertEquals(Refusal.NOT_SIGNED, refused(installer().install(ByteArrayInputStream("hello, this is not a zip".toByteArray()))))
  }

  @Test fun aStandardBundleOnALitePhoneAndTheReverseAreWrongClass() {
    assertEquals(Refusal.WRONG_CLASS, refused(installer(cls = BundleClass.LITE).install(ByteArrayInputStream(bundle()))))
    val lite = bundle(manifest = manifest(cls = "lite"))
    assertEquals(Refusal.WRONG_CLASS, refused(installer(cls = BundleClass.STANDARD).install(ByteArrayInputStream(lite))))
  }

  @Test fun theWrongClassIsRefusedBeforeTheBigFilesAreRead() {
    // Hands out 1 KB at a time and counts: the refusal must come long before the model's 300 KB is consumed.
    val bytes = bundle()
    var served = 0
    val counting = object : java.io.InputStream() {
      override fun read(): Int = if (served < bytes.size) bytes[served++].toInt() and 0xff else -1
      override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (served >= bytes.size) return -1
        val n = minOf(len, 1024, bytes.size - served)
        System.arraycopy(bytes, served, b, off, n); served += n
        return n
      }
    }
    assertEquals(Refusal.WRONG_CLASS, refused(installer(cls = BundleClass.LITE).install(counting)))
    assertTrue("read $served of ${bytes.size}", served < 20_000)
  }

  @Test fun anOlderVersionIsRefusedAndTheSameVersionIsAlreadyInstalled() {
    val dir = tmp.newFolder()
    assertTrue(installer(dir).install(ByteArrayInputStream(bundle(manifest = manifest(version = 3)))) is BundleResult.Installed)
    val before = snapshot(dir)
    assertEquals(Refusal.OLDER_VERSION, refused(installer(dir).install(ByteArrayInputStream(bundle(manifest = manifest(version = 2))))))
    assertEquals(Refusal.ALREADY_INSTALLED, refused(installer(dir).install(ByteArrayInputStream(bundle(manifest = manifest(version = 3))))))
    assertEquals(before, snapshot(dir))
  }

  @Test fun aNewerVersionReplacesTheOldOne() {
    val dir = tmp.newFolder()
    installer(dir).install(ByteArrayInputStream(bundle(manifest = manifest(version = 1))))
    val newer = standardFiles + (modelName to ByteArray(310_000) { 9 })
    val r = installer(dir).install(ByteArrayInputStream(bundle(manifest = manifest(version = 2, files = newer), files = newer)))
    assertTrue(r.toString(), r is BundleResult.Installed)
    assertEquals(310_000L, File(dir, modelName).length())
    assertEquals(2, InstalledBundle.read(dir)?.version)
  }

  @Test fun theSameVersionRepairsAPhoneWhoseModelFileIsGone() {
    val dir = tmp.newFolder()
    installer(dir).install(ByteArrayInputStream(bundle()))
    File(dir, modelName).delete()
    assertTrue(installer(dir).install(ByteArrayInputStream(bundle())) is BundleResult.Installed)
    assertTrue(File(dir, modelName).isFile)
  }

  @Test fun aTruncatedDownloadIsIncompleteNotDamagedAndLeavesNothing() {
    val dir = tmp.newFolder()
    val bytes = bundle()
    // Cut inside the last file, inside a middle file and inside the manifest zone: each is a download that stopped, none is damage.
    for (cut in listOf(50_000, 200_000, bytes.size - 100)) {
      assertEquals("cut $cut", Refusal.INCOMPLETE, refused(installer(dir).install(ByteArrayInputStream(bytes.copyOf(bytes.size - cut)))))
    }
    assertEquals(emptyList<String>(), snapshot(dir))
  }

  @Test fun aBundleCutShortHasNoZipEndAndAWholeOneHas() {
    val bytes = bundle()
    assertTrue(ZipTail.hasEnd(bytes))
    assertFalse(ZipTail.hasEnd(bytes.copyOf(bytes.size - 1)))
    assertFalse(ZipTail.hasEnd(bytes.copyOf(bytes.size / 2)))
  }

  @Test fun aWholeBundleWithAChangedByteIsStillDamagedNotIncomplete() {
    val bytes = bundle()
    bytes[bytes.size / 2] = (bytes[bytes.size / 2].toInt() xor 1).toByte()
    assertTrue(ZipTail.hasEnd(bytes))   // it ends like a zip, so it is not "stopped": the SHA says what it is
    assertEquals(Refusal.DAMAGED, refused(installer().install(ByteArrayInputStream(bytes))))
  }

  @Test fun aFileMissingFromTheZipIsDamaged() {
    val partial = standardFiles - "findr-speech-models/vad.onnx"
    assertEquals(Refusal.DAMAGED, refused(installer().install(ByteArrayInputStream(bundle(files = partial)))))
  }

  @Test fun aFileTheManifestDoesNotListIsDamaged() {
    val extra = standardFiles + ("evil.bin" to ByteArray(10))
    assertEquals(Refusal.DAMAGED, refused(installer().install(ByteArrayInputStream(bundle(files = extra)))))
  }

  @Test fun aManifestPathThatEscapesTheFolderIsNotUnderstood() {
    val evil = mapOf(modelName to model, "../../evil" to ByteArray(10))
    assertEquals(Refusal.BAD_MANIFEST, refused(installer().install(ByteArrayInputStream(bundle(manifest = manifest(files = evil), files = evil)))))
  }

  @Test fun aNewerManifestFormatIsNotUnderstood() {
    val m = manifest { it.put("format", 2) }
    assertEquals(Refusal.BAD_MANIFEST, refused(installer().install(ByteArrayInputStream(bundle(manifest = m)))))
  }

  @Test fun notEnoughSpaceIsRefusedBeforeCopying() {
    val dir = tmp.newFolder()
    assertEquals(Refusal.NO_SPACE, refused(installer(dir, free = 100_000).install(ByteArrayInputStream(bundle()))))
    assertEquals(emptyList<String>(), snapshot(dir))
  }

  @Test fun aDeclinedPackIsVerifiedButNotInstalled() {
    val dir = tmp.newFolder()
    val r = installer(dir, skip = setOf("findr-speech-models")).install(ByteArrayInputStream(bundle()))
    assertTrue(r is BundleResult.Installed)
    assertTrue(File(dir, modelName).isFile)
    assertFalse(File(dir, "asr").exists())
  }

  @Test fun cancellingLeavesNothing() {
    val dir = tmp.newFolder()
    assertEquals(Refusal.CANCELLED, refused(installer(dir).install(ByteArrayInputStream(bundle()), cancelled = { true })))
    assertEquals(emptyList<String>(), snapshot(dir))
  }

  @Test fun theEmbeddedKeysParseAsP256PublicKeys() {
    // Constructing the verifier decodes every embedded key; a typo in TrustedKeys fails here, not on a phone.
    SignatureVerifier(TrustedKeys.ALL)
    assertEquals(2, TrustedKeys.ALL.toSet().size)
  }

  @Test fun rawSignaturesWithLeadingZerosAndHighBitsConvertToValidDer() {
    // Many signatures: r and s with a leading zero byte or a set high bit are the cases a naive converter gets wrong.
    repeat(200) { i ->
      val m = "message $i".toByteArray()
      assertTrue(SignatureVerifier(listOf(key1.spki)).verifies(m, SignatureVerifier.decodeSig(sign(key1, m).toByteArray())!!))
    }
  }

  @Test fun phoneClassRoutingFollowsTheMemoryProfile() {
    assertEquals(BundleClass.LITE, BundleClass.of(MemoryProfile.LITE))
    assertEquals(BundleClass.STANDARD, BundleClass.of(MemoryProfile.STANDARD))
    // The A06 (3,633 MiB) and a 2 GB phone (1,900 MiB) land in different classes; Android Go is lite whatever it reports.
    assertEquals(BundleClass.STANDARD, BundleClass.of(MemoryProfile.choose(3_633, false, 256)))
    assertEquals(BundleClass.LITE, BundleClass.of(MemoryProfile.choose(1_900, false, 192)))
    assertEquals(BundleClass.LITE, BundleClass.of(MemoryProfile.choose(4_000, true, 256)))
    assertNull(BundleClass.parse("huge"))
  }

  /** Optional, off by default: TRAWL_BUNDLE=<signed zip> TRAWL_CLASS=lite|standard checks a real, Key Vault-signed bundle with the embedded keys. */
  @Test fun aRealSignedBundleVerifiesAndInstallsWhenOneIsGiven() {
    val path = System.getenv("TRAWL_BUNDLE") ?: return
    val cls = BundleClass.parse(System.getenv("TRAWL_CLASS")) ?: BundleClass.STANDARD
    val names = Embedder.modelNames(if (cls == BundleClass.LITE) MemoryProfile.LITE else MemoryProfile.STANDARD)
    val dir = tmp.newFolder()
    val r = SignedBundleInstaller(dir, cls, names, mapOf("findr-quicklook-models" to "quicklook", "findr-speech-models" to "asr", "findr-private-models" to "private"))
      .install(File(path).inputStream())
    assertTrue(r.toString(), r is BundleResult.Installed)
    println("real bundle installed: " + snapshot(dir))
  }
}
