package trawl.desktop

import com.craneai.findr.engine.AsrModelFile
import com.craneai.findr.engine.InstalledAddons
import com.craneai.findr.engine.SignatureVerifier
import java.io.File
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.json.JSONArray
import org.json.JSONObject
import trawl.desktop.privacy.PackChoices

/** The add-on installer's checks, with a throwaway signing key and a tiny made-up pack. */
class AddOnsTest {
  private class Choices : PackChoices {
    val declined = HashSet<String>()
    override fun declined(id: String) = id in declined
    override fun setDeclined(id: String, declined: Boolean) { if (declined) this.declined += id else this.declined -= id }
  }

  private val keyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
  private val otherKey = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
  private fun spki(k: java.security.KeyPair) = Base64.getEncoder().encodeToString(k.public.encoded)
  private val verifier = SignatureVerifier(listOf(spki(keyPair)))

  // The pack: two small files under a folder "testpack/", installed into models/tp/.
  private val packFiles = mapOf("testpack/a.bin" to ByteArray(3000) { (it % 251).toByte() }, "testpack/sub/b.txt" to "hello pack".toByteArray())
  private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
  private val manifest = packFiles.map { (p, b) -> AsrModelFile(p.removePrefix("testpack/"), b.size.toLong(), sha(b)) }
  private val catalogFiles = AddOnFiles("tp", manifest, "testpack", Regex("""testpack.*\.zip""", RegexOption.IGNORE_CASE))
  private val def = AddOnDef("tp", "n", "b", "c", packFiles.values.sumOf { it.size }.toLong(), catalogFiles)
  private val catalog = listOf(def)

  private class Rig(val models: File, val downloads: File, val choices: Choices, val released: MutableList<String>, val changed: IntArray, val manager: AddOnManager)

  private fun rig(): Rig {
    val models = Files.createTempDirectory("models").toFile()
    val dl = Files.createTempDirectory("downloads").toFile()
    val choices = Choices()
    val released = ArrayList<String>()
    val changed = IntArray(1)
    val mgr = AddOnManager(models, { listOf(dl) }, choices, AddOnHost({ released += it }, { changed[0]++ }), executor = { it.run() }, catalog = catalog, verifier = verifier)
    return Rig(models, dl, choices, released, changed, mgr)
  }

  private fun manifestJson(version: Int, files: Map<String, ByteArray> = packFiles, role: String = "addon", cls: String = "any", bundle: String = "trawl-addon-tp"): ByteArray =
    JSONObject().put("format", 1).put("bundle", bundle).put("class", cls).put("role", role).put("version", version)
      .put("files", JSONArray(files.map { (p, b) -> JSONObject().put("path", p).put("size", b.size).put("sha256", sha(b)) })).toString().toByteArray()

  private fun sign(msg: ByteArray, key: java.security.KeyPair = keyPair): String =
    Base64.getEncoder().encodeToString(Signature.getInstance("SHA256withECDSA").run { initSign(key.private); update(msg); sign() })

  private fun bundle(
    into: File, version: Int = 1, key: java.security.KeyPair = keyPair, contents: Map<String, ByteArray> = packFiles,
    manifestOf: Map<String, ByteArray> = packFiles, name: String = "testpack-v$version.zip",
  ): File {
    val m = manifestJson(version, manifestOf)
    return File(into, name).also { f ->
      ZipOutputStream(f.outputStream()).use { z ->
        fun put(n: String, b: ByteArray) { z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() }
        put("trawl-bundle.json", m)
        put("trawl-bundle.sig", sign(m, key).toByteArray())
        contents.forEach { (p, b) -> put(p, b) }
      }
    }
  }

  private fun state(r: Rig) = r.manager.states.value.getValue("tp")

  // ---- signed add-on bundles ----------------------------------------------------------------------------------------

  @Test fun `a correctly signed add-on installs, is recorded with its version and the files are in place`() {
    val r = rig()
    r.manager.install("tp", bundle(r.downloads))
    assertIs<AddOnState.Installed>(state(r))
    assertEquals(1, InstalledAddons.read(r.models)["trawl-addon-tp"])
    assertTrue(File(r.models, "tp/a.bin").isFile && File(r.models, "tp/sub/b.txt").readText() == "hello pack")
    assertEquals(1, r.changed[0])
  }

  @Test fun `a tampered bundle is refused and leaves the models folder untouched`() {
    val r = rig()
    val bad = packFiles + ("testpack/a.bin" to ByteArray(3000) { 7 })        // content differs from what the SIGNED manifest lists
    r.manager.install("tp", bundle(r.downloads, contents = bad))
    assertEquals(AddOnState.Failed(AddOnProblem.DAMAGED, "SHA-256 mismatch"), state(r))
    assertFalse(File(r.models, "tp").exists())
    assertTrue(InstalledAddons.read(r.models).isEmpty())
  }

  @Test fun `a manifest edited after signing is refused`() {
    val r = rig()
    val f = bundle(r.downloads)
    // Rebuild the zip with a different manifest but the old signature.
    val tamperedManifest = manifestJson(1, packFiles + ("testpack/evil.sh" to "rm".toByteArray()))
    val oldSig = ZipOutputStreamHelper.read(f, "trawl-bundle.sig")
    val forged = File(r.downloads, "testpack-forged.zip")
    ZipOutputStream(forged.outputStream()).use { z ->
      z.putNextEntry(ZipEntry("trawl-bundle.json")); z.write(tamperedManifest); z.closeEntry()
      z.putNextEntry(ZipEntry("trawl-bundle.sig")); z.write(oldSig); z.closeEntry()
      packFiles.forEach { (p, b) -> z.putNextEntry(ZipEntry(p)); z.write(b); z.closeEntry() }
    }
    r.manager.install("tp", forged)
    assertEquals(AddOnProblem.BAD_SIGNATURE, (state(r) as AddOnState.Failed).problem)
    assertFalse(File(r.models, "tp").exists())
  }

  @Test fun `a bundle signed by a key the app does not trust is refused`() {
    val r = rig()
    r.manager.install("tp", bundle(r.downloads, key = otherKey))
    assertEquals(AddOnProblem.BAD_SIGNATURE, (state(r) as AddOnState.Failed).problem)
    assertFalse(File(r.models, "tp").exists())
  }

  @Test fun `the apps own trusted keys do not accept a throwaway key`() {
    // The real manager uses the real verifier: a bundle signed with our test key must not install with it.
    val r = rig()
    val mgr = AddOnManager(r.models, { listOf(r.downloads) }, Choices(), executor = { it.run() }, catalog = catalog)
    mgr.install("tp", bundle(r.downloads))
    assertEquals(AddOnProblem.BAD_SIGNATURE, (mgr.states.value.getValue("tp") as AddOnState.Failed).problem)
  }

  @Test fun `a zip with no signature entry and the wrong contents is not this add-on`() {
    val r = rig()
    val plain = File(r.downloads, "testpack-plain.zip")
    ZipOutputStream(plain.outputStream()).use { z -> z.putNextEntry(ZipEntry("readme.txt")); z.write("hi".toByteArray()); z.closeEntry() }
    r.manager.install("tp", plain)
    assertEquals(AddOnProblem.NOT_THIS_ADDON, (state(r) as AddOnState.Failed).problem)
  }

  @Test fun `an older signed version than the one installed is refused, a newer one replaces it`() {
    val r = rig()
    r.manager.install("tp", bundle(r.downloads, version = 2))
    assertIs<AddOnState.Installed>(state(r))
    r.manager.install("tp", bundle(r.downloads, version = 1))
    assertEquals(AddOnProblem.OLDER_VERSION, (state(r) as AddOnState.Failed).problem)
    assertEquals(2, InstalledAddons.read(r.models)["trawl-addon-tp"])
    r.manager.install("tp", bundle(r.downloads, version = 3))
    assertEquals(3, InstalledAddons.read(r.models)["trawl-addon-tp"])
  }

  @Test fun `a bundle for another add-on is not installed here`() {
    val r = rig()
    val other = mapOf("someotherpack/x.bin" to ByteArray(10))
    r.manager.install("tp", bundle(r.downloads, contents = other, manifestOf = other, name = "testpack-other.zip"))
    assertIs<AddOnState.Failed>(state(r))
    assertFalse(File(r.models, "tp").exists())
  }

  @Test fun `a signed bundle with no room is refused before anything is written`() {
    val r = rig()
    val mgr = AddOnManager(r.models, { listOf(r.downloads) }, Choices(), executor = { it.run() }, catalog = catalog, verifier = verifier, freeSpace = { 1_000 })
    mgr.install("tp", bundle(r.downloads))
    assertEquals(AddOnProblem.NO_SPACE, (mgr.states.value.getValue("tp") as AddOnState.Failed).problem)
  }

  // ---- hash pinned folders and zips ---------------------------------------------------------------------------------

  private fun folderPack(parent: File, tamper: Boolean = false): File = File(parent, "testpack").also { d ->
    packFiles.forEach { (p, b) -> File(d, p.removePrefix("testpack/")).also { it.parentFile.mkdirs() }.writeBytes(if (tamper && p.endsWith("b.txt")) "HELLO pack".toByteArray() else b) }
  }

  @Test fun `an unzipped folder whose files match the compiled in hashes installs`() {
    val r = rig()
    r.manager.install("tp", folderPack(r.downloads))
    assertIs<AddOnState.Installed>(state(r))
    assertEquals("hello pack", File(r.models, "tp/sub/b.txt").readText())
  }

  @Test fun `a folder with one changed byte is refused and nothing is installed`() {
    val r = rig()
    r.manager.install("tp", folderPack(r.downloads, tamper = true))
    assertIs<AddOnState.Failed>(state(r))
    assertEquals(AddOnProblem.BAD_FILES, (state(r) as AddOnState.Failed).problem)
    assertFalse(File(r.models, "tp/sub/b.txt").exists())
  }

  @Test fun `Add with no file picked looks in Downloads and finds the pack folder`() {
    val r = rig()
    folderPack(r.downloads)
    assertEquals(1, r.manager.findCandidates("tp").size)
    r.manager.install("tp")
    assertIs<AddOnState.Installed>(state(r))
  }

  @Test fun `Add with nothing in Downloads says not found`() {
    val r = rig()
    r.manager.install("tp")
    assertEquals(AddOnProblem.NOT_FOUND, (state(r) as AddOnState.Failed).problem)
  }

  @Test fun `an add-on that does not exist on desktop yet cannot be added`() {
    val r = rig()
    val soon = AddOnDef("soon", "n", "b", "c", 1, null)
    val mgr = AddOnManager(r.models, { listOf(r.downloads) }, r.choices, executor = { it.run() }, catalog = listOf(soon))
    mgr.install("soon")
    assertEquals(AddOnProblem.UNAVAILABLE, (mgr.states.value.getValue("soon") as AddOnState.Failed).problem)
  }

  // ---- removing -----------------------------------------------------------------------------------------------------

  @Test fun `remove releases the pack, deletes its files and is remembered so it is not added behind the person's back`() {
    val r = rig()
    r.manager.install("tp", folderPack(r.downloads))
    r.manager.remove("tp")
    assertEquals(listOf("tp"), r.released)
    assertEquals(AddOnState.NotInstalled, state(r))
    assertFalse(File(r.models, "tp").exists())
    assertTrue(r.choices.declined("tp"))
    r.manager.install("tp", folderPack(r.downloads))   // the person asks again: the decline is lifted
    assertFalse(r.choices.declined("tp"))
    assertIs<AddOnState.Installed>(state(r))
  }

  @Test fun `a pack copied in by hand shows as installed after a refresh`() {
    val r = rig()
    folderPack(r.models.also { }, false).renameTo(File(r.models, "tp"))
    r.manager.refresh()
    assertIs<AddOnState.Installed>(state(r))
  }

  @Test fun `known limit, same as the phone, the check at start is size only`() {
    // An installed file changed IN PLACE to another content of the same size still reads as installed: hashes are checked at install time.
    val r = rig()
    r.manager.install("tp", folderPack(r.downloads))
    File(r.models, "tp/sub/b.txt").writeText("HELLO pack")
    r.manager.refresh()
    assertIs<AddOnState.Installed>(state(r))
  }

  // ---- the update notice --------------------------------------------------------------------------------------------

  @Test fun `a newer signed bundle in Downloads is offered for an installed add-on, never installed by itself`() {
    val r = rig()
    r.manager.install("tp", bundle(r.downloads, version = 1))
    val newer = bundle(r.downloads, version = 2)
    val found = UpdateNotice.find(r.models, listOf(r.downloads), catalog, verifier)
    assertEquals(listOf(AddOnUpdate("tp", "trawl-addon-tp", 1, 2, newer)), found)
    assertEquals(1, InstalledAddons.read(r.models)["trawl-addon-tp"])
  }

  @Test fun `no notice for the same or an older version, an untrusted signature, or an add-on that is not installed`() {
    val r = rig()
    assertTrue(UpdateNotice.find(r.models, listOf(r.downloads), catalog, verifier).isEmpty(), "not installed")
    r.manager.install("tp", bundle(r.downloads, version = 2))
    assertTrue(UpdateNotice.find(r.models, listOf(r.downloads), catalog, verifier).isEmpty(), "same version")
    bundle(r.downloads, version = 1, name = "older.zip")
    bundle(r.downloads, version = 9, key = otherKey, name = "untrusted.zip")
    assertTrue(UpdateNotice.find(r.models, listOf(r.downloads), catalog, verifier).isEmpty())
  }

  @Test fun `Not now is remembered per version and the newest of several is the one offered`() {
    val r = rig()
    r.manager.install("tp", bundle(r.downloads, version = 1))
    bundle(r.downloads, version = 2)
    val v3 = bundle(r.downloads, version = 3)
    assertEquals(3, UpdateNotice.find(r.models, listOf(r.downloads), catalog, verifier).single().newVersion)
    assertTrue(UpdateNotice.find(r.models, listOf(r.downloads), catalog, verifier) { _, v -> v <= 3 }.isEmpty())
    bundle(r.downloads, version = 4, name = "testpack-v4.zip")
    assertEquals(4, UpdateNotice.find(r.models, listOf(r.downloads), catalog, verifier) { _, v -> v <= 3 }.single().newVersion)
    assertTrue(v3.isFile)
  }

  @Test fun `an add-on installed by hash has no version, so no notice`() {
    val r = rig()
    r.manager.install("tp", folderPack(r.downloads))
    bundle(r.downloads, version = 5)
    assertTrue(UpdateNotice.find(r.models, listOf(r.downloads), catalog, verifier).isEmpty())
  }

  // ---- the real Privacy pack (needs the fixture; skipped where it is not) ---------------------------------------------

  private val fixture: File? = (System.getenv("TRAWL_PRIVACY_PACK_DIR") ?: "/mnt/data/trawl-fixtures/findr-private-models").let(::File).takeIf { it.isDirectory }

  @Test fun `the real Privacy pack installs through the compiled in hashes, and one changed byte is refused`() {
    val src = fixture
    if (src == null) { println("SKIPPED: no Privacy pack on this machine"); return }
    val models = Files.createTempDirectory("real-models").toFile()
    val dl = Files.createTempDirectory("real-dl").toFile()
    src.copyRecursively(File(dl, "findr-private-models"))
    val mgr = AddOnManager(models, { listOf(dl) }, Choices(), executor = { it.run() })
    mgr.install("privacy")
    assertIs<AddOnState.Installed>(mgr.states.value.getValue("privacy"))
    assertTrue(File(models, "private/nsfw-384/nsfw-384.onnx").length() == 22_489_942L)
    println("PRIVACY-PACK installed, bytes=${(mgr.states.value.getValue("privacy") as AddOnState.Installed).bytes}")

    // The same pack with one byte of the model changed (same size): refused.
    val models2 = Files.createTempDirectory("real-models2").toFile()
    val bad = File(dl, "tampered").also { src.copyRecursively(File(it, "findr-private-models")) }
    val onnx = File(bad, "findr-private-models/nsfw-384/nsfw-384.onnx")
    val bytes = onnx.readBytes().also { it[1_000_000] = (it[1_000_000] + 1).toByte() }
    onnx.writeBytes(bytes)
    val mgr2 = AddOnManager(models2, { emptyList() }, Choices(), executor = { it.run() })
    mgr2.install("privacy", File(bad, "findr-private-models"))
    assertEquals(AddOnProblem.BAD_FILES, (mgr2.states.value.getValue("privacy") as AddOnState.Failed).problem)
    assertFalse(File(models2, "private/nsfw-384/nsfw-384.onnx").exists())
  }

  @Test fun `the catalog lists the Privacy pack as available and Speech as coming soon`() {
    assertTrue(AddOnCatalog.privacy.available)
    assertFalse(AddOnCatalog.speech.available)
    assertEquals(22_501_653L, AddOnCatalog.privacy.sizeBytes)
    assertEquals(listOf("privacy", "speech"), AddOnCatalog.all.map { it.id })
  }
}

private object ZipOutputStreamHelper {
  fun read(zip: File, entry: String): ByteArray = java.util.zip.ZipFile(zip).use { z -> z.getInputStream(z.getEntry(entry)).readBytes() }
}
