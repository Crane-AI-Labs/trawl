package com.craneai.findr.engine

import com.craneai.findr.core.Refusal
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import org.json.JSONObject

/**
 * Signed model bundles (the research notes). Findr has no internet permission, so a person fetches the models with a
 * browser (or gets the file over WhatsApp) and Trawl installs them from Downloads. That makes the file untrusted input: this
 * file is the gate. A bundle carries `trawl-bundle.json` (its manifest: id, class, version, and the name, size and SHA-256 of
 * every file) and `trawl-bundle.sig` (an ECDSA P-256 signature over the manifest's exact bytes) as the FIRST two zip entries,
 * so one streaming pass can check the signature, refuse early, and then verify each file as it is copied.
 *
 * Pure JVM (java.security, org.json), so every refusal is unit-tested off the phone.
 */

/**
 * The two phone classes a bundle is built for. A bundle fits only its own class ([MemoryProfile] decides the phone's), except an
 * add-on bundle ([BundleRole.ADDON]), which carries no model and is [ANY]: a pack is the same file on every phone.
 */
enum class BundleClass(val id: String) {
  STANDARD("standard"), LITE("lite"), ANY("any");

  /** How full a model stack a class holds: an install never swaps a fuller model for a lighter one. */
  val rank: Int get() = when (this) { LITE -> 0; STANDARD -> 1; ANY -> -1 }

  companion object {
    fun of(profile: MemoryProfile): BundleClass = if (profile == MemoryProfile.LITE) LITE else STANDARD
    fun parse(id: String?): BundleClass? = entries.firstOrNull { it.id == id }
  }
}

/**
 * What a bundle carries. [FULL] is a model (with, optionally, its packs): today's `trawl-models` and `trawl-models-lite`, and the
 * lean download's core and vision-upgrade bundles. [ADDON] is packs only (speech, ...): no model, class `any`, installable before,
 * after or without a model, with its own version record. An older app reads `class: any` as an unknown class and refuses it.
 */
enum class BundleRole(val id: String) {
  FULL("full"), ADDON("addon");

  companion object {
    fun parse(id: String?): BundleRole? = entries.firstOrNull { it.id == id }
  }
}

data class BundleFile(val path: String, val size: Long, val sha256: String)

data class BundleManifest(val bundle: String, val bundleClass: BundleClass, val version: Int, val files: List<BundleFile>, val role: BundleRole = BundleRole.FULL) {
  val totalBytes: Long get() = files.sumOf { it.size }

  companion object {
    const val ENTRY = "trawl-bundle.json"
    const val SIGNATURE_ENTRY = "trawl-bundle.sig"
    const val FORMAT = 1
    private val HEX = Regex("[0-9a-f]{64}")

    /** Null when the bytes are not a manifest this app understands (not JSON, a newer format, a missing or malformed field). */
    fun parse(bytes: ByteArray): BundleManifest? = try {
      val o = JSONObject(String(bytes, Charsets.UTF_8))
      if (o.getInt("format") != FORMAT) null else {
        val arr = o.getJSONArray("files")
        val files = (0 until arr.length()).map {
          val f = arr.getJSONObject(it)
          BundleFile(f.getString("path"), f.getLong("size"), f.getString("sha256").lowercase())
        }
        val cls = BundleClass.parse(o.getString("class"))
        val role = BundleRole.parse(o.optString("role", BundleRole.FULL.id))
        // An add-on is class `any` and holds only pack folders; a full bundle is one class and never `any`.
        val roleOk = role != null && cls != null && (role == BundleRole.ADDON) == (cls == BundleClass.ANY) &&
          (role == BundleRole.FULL || files.all { '/' in it.path })
        val ok = roleOk && files.isNotEmpty() && files.map { it.path }.toSet().size == files.size &&
          files.all { it.size >= 0 && HEX.matches(it.sha256) && it.path != ENTRY && it.path != SIGNATURE_ENTRY && safePath(it.path) }
        if (ok) BundleManifest(o.getString("bundle"), cls!!, o.getInt("version"), files, role!!) else null
      }
    } catch (_: Exception) {
      null
    }

    /** A relative path with no way out of the models folder. Only manifest paths are ever written, but they are checked anyway. */
    fun safePath(p: String): Boolean =
      p.isNotEmpty() && !p.startsWith("/") && !p.contains('\\') && !p.contains(':') && p.split('/').none { it.isEmpty() || it == "." || it == ".." }
  }
}

/**
 * The public keys whose signatures this app accepts, as base64 X.509 SubjectPublicKeyInfo (EC P-256). The private halves live in
 * an Azure Key Vault and never leave it (`az keyvault key sign`). Two are embedded so a key can be retired
 * without stranding phones: sign with the spare, ship an app that trusts both, drop the old one in a later release.
 */
object TrustedKeys {
  /** trawl-models-signing-1 (in use) */
  const val KEY_1 = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEAS6i+HSBBV8p+MdgVt8CWvnd+7fz7ZZ2oEN3tkIRsTsdR2HRoB7Sx08UTH5TLB8k52gkTbos4Y6iUXWQylb7CA=="
  /** trawl-models-signing-2 (the spare, for rotation) */
  const val KEY_2 = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEDwspZYv/lHnGGDzOV6EAXFpIPVuO5vwW0LtHwb2J87tJdS4MUjB4fc3MVPtJ+Upxe4+eVr4RtKrpNLw7H92ZeA=="
  val ALL: List<String> = listOf(KEY_1, KEY_2)
}

/** ECDSA P-256 / SHA-256 verification against a set of trusted public keys. */
class SignatureVerifier(trustedSpki: List<String> = TrustedKeys.ALL) {
  private val keys = trustedSpki.map { KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(it))) }

  /**
   * True when [signature] is a valid signature of [message] by any trusted key. Key Vault returns the raw 64-byte r||s form
   * (base64url in the .sig file); Java wants DER, so a 64-byte value is converted. A DER signature is accepted as is.
   */
  fun verifies(message: ByteArray, signature: ByteArray): Boolean {
    val der = if (signature.size == 64) rawToDer(signature) else signature
    return keys.any { key ->
      try {
        Signature.getInstance("SHA256withECDSA").run { initVerify(key); update(message); verify(der) }
      } catch (_: Exception) {
        false
      }
    }
  }

  companion object {
    /** The .sig file's text (base64url or base64, whitespace allowed) as bytes; null when it is not base64. */
    fun decodeSig(text: ByteArray): ByteArray? = try {
      val s = String(text, Charsets.US_ASCII).trim().replace('-', '+').replace('_', '/')
      Base64.getMimeDecoder().decode(s)
    } catch (_: Exception) {
      null
    }

    /** r||s (two 32-byte big-endian integers) to the ASN.1 DER SEQUENCE Java expects. */
    fun rawToDer(raw: ByteArray): ByteArray {
      fun int(b: ByteArray): ByteArray {
        var i = 0
        while (i < b.size - 1 && b[i] == 0.toByte()) i++
        val t = b.copyOfRange(i, b.size)
        return if (t[0] < 0) byteArrayOf(0) + t else t
      }
      val r = int(raw.copyOfRange(0, 32))
      val s = int(raw.copyOfRange(32, 64))
      val body = byteArrayOf(0x02, r.size.toByte()) + r + byteArrayOf(0x02, s.size.toByte()) + s
      return byteArrayOf(0x30, body.size.toByte()) + body
    }
  }
}

sealed interface BundleResult {
  data class Installed(val manifest: BundleManifest, val installedBytes: Long) : BundleResult
  data class Refused(val reason: Refusal, val detail: String = "", val manifest: BundleManifest? = null) : BundleResult
}

/** What is installed: written last, after every file is in place. */
data class InstalledBundle(val bundle: String, val bundleClass: BundleClass, val version: Int) {
  companion object {
    const val FILE = "bundle-installed.json"
    fun read(modelsDir: File): InstalledBundle? = try {
      val o = JSONObject(File(modelsDir, FILE).readText())
      InstalledBundle(o.getString("bundle"), BundleClass.parse(o.getString("class"))!!, o.getInt("version"))
    } catch (_: Exception) {
      null
    }
  }
  fun write(modelsDir: File) {
    val part = File(modelsDir, "$FILE.part")
    part.writeText(JSONObject().put("bundle", bundle).put("class", bundleClass.id).put("version", version).toString())
    Files.move(part.toPath(), File(modelsDir, FILE).toPath(), StandardCopyOption.REPLACE_EXISTING)
  }
}

/** The add-on bundles installed, bundle name to version (`addons-installed.json`): separate from the model's record, which an add-on never touches. */
object InstalledAddons {
  const val FILE = "addons-installed.json"

  fun read(modelsDir: File): Map<String, Int> = try {
    val o = JSONObject(File(modelsDir, FILE).readText())
    // keys() is Iterator<String> on Android but a raw Iterator in the desktop's JSON library: read the names as strings on both.
    o.keys().asSequence().map { it.toString() }.associateWith { o.getInt(it) }
  } catch (_: Exception) {
    emptyMap()
  }

  fun write(modelsDir: File, bundle: String, version: Int) {
    val o = JSONObject()
    (read(modelsDir) + (bundle to version)).forEach { (k, v) -> o.put(k, v) }
    val part = File(modelsDir, "$FILE.part")
    part.writeText(o.toString())
    Files.move(part.toPath(), File(modelsDir, FILE).toPath(), StandardCopyOption.REPLACE_EXISTING)
  }
}

/**
 * Installs a signed bundle from a stream (a Downloads file or a content Uri: the same code) into `files/models/`.
 *
 * Order of checks, cheapest first, so a bad file is refused before 600 MB is read: (1) the manifest and signature are the first
 * two entries and the signature is valid; (2) the manifest is understood and built for this phone's class; (3) its version is
 * newer than the installed one (or nothing is installed); (4) there is room; (5) every file is read once, copied to a staging
 * `.part` while its size and SHA-256 are checked; (6) only when ALL files pass are they renamed into place, the model last, and
 * the installed-version record written. Anything that fails leaves the models folder exactly as it was.
 *
 * @param modelNames model file names this phone may use (a bundle must carry one at its root)
 * @param packDirs bundle folder name to the folder under `files/models/` it installs into; a folder not listed is checked but not installed
 * @param skipFolders pack folders the person declined ("Not now" or Remove): verified but left uninstalled (an add-on bundle, chosen
 *   on purpose, installs its folders anyway)
 * @param acceptClasses the bundle classes this phone takes. Its own by default; a phone that can run the lighter stack also takes
 *   LITE (the lean first download), and a later STANDARD bundle then upgrades it whatever the version numbers say.
 *
 * Add-on bundles ([BundleRole.ADDON]) skip the class and model checks and keep their own per-bundle version record.
 */
class SignedBundleInstaller(
  private val modelsDir: File,
  private val phoneClass: BundleClass,
  private val modelNames: List<String>,
  private val packDirs: Map<String, String>,
  private val verifier: SignatureVerifier = SignatureVerifier(),
  private val skipFolders: Set<String> = emptySet(),
  private val freeSpace: (File) -> Long = { it.usableSpace },
  private val acceptClasses: Set<BundleClass> = setOf(phoneClass),
) {
  private val incoming = File(modelsDir, ".incoming")

  /** The installed version, or null when none is installed or its model file is gone (then even the same version repairs it). */
  fun installedVersion(): Int? {
    val rec = InstalledBundle.read(modelsDir) ?: return null
    return if (modelNames.any { File(modelsDir, it).isFile }) rec.version else null
  }

  /** Where a manifest path installs; null when it is only checked. [honourSkips] is false for an add-on the person asked for. */
  fun targetOf(path: String, honourSkips: Boolean = true): File? {
    val slash = path.indexOf('/')
    if (slash < 0) return if (path in modelNames) File(modelsDir, path) else null
    val folder = path.substring(0, slash)
    if (honourSkips && folder in skipFolders) return null
    val dir = packDirs[folder] ?: return null
    return File(modelsDir, "$dir/${path.substring(slash + 1)}")
  }

  fun install(
    input: InputStream,
    progress: (done: Long, total: Long) -> Unit = { _, _ -> },
    cancelled: () -> Boolean = { false },
  ): BundleResult {
    incoming.deleteRecursively()
    try {
      return run(input, progress, cancelled)
    } catch (e: ZipException) {
      return BundleResult.Refused(Refusal.DAMAGED, "zip: ${e.message}")
    } catch (e: IOException) {
      return BundleResult.Refused(Refusal.DAMAGED, "read: ${e.message}")
    } finally {
      incoming.deleteRecursively()   // success has renamed everything out already; this clears a failure's leftovers
    }
  }

  private fun run(input: InputStream, progress: (Long, Long) -> Unit, cancelled: () -> Boolean): BundleResult {
    val zin = ZipInputStream(input.buffered(1 shl 16))
    // 1. manifest, then signature: first and second entries, small.
    val manifestBytes = smallEntry(zin, BundleManifest.ENTRY) ?: return BundleResult.Refused(Refusal.NOT_SIGNED, "no ${BundleManifest.ENTRY} first")
    val sig = smallEntry(zin, BundleManifest.SIGNATURE_ENTRY)?.let { SignatureVerifier.decodeSig(it) }
      ?: return BundleResult.Refused(Refusal.NOT_SIGNED, "no ${BundleManifest.SIGNATURE_ENTRY} second")
    if (!verifier.verifies(manifestBytes, sig)) return BundleResult.Refused(Refusal.BAD_SIGNATURE, "signature does not verify against a trusted key")
    // 2. understood, and for this phone.
    val m = BundleManifest.parse(manifestBytes) ?: return BundleResult.Refused(Refusal.BAD_MANIFEST, "unreadable manifest")
    val addon = m.role == BundleRole.ADDON
    if (!addon && m.bundleClass !in acceptClasses) return BundleResult.Refused(Refusal.WRONG_CLASS, "bundle is ${m.bundleClass.id}, phone is ${phoneClass.id}", m)
    if (addon) {
      if (m.files.none { targetOf(it.path, honourSkips = false) != null }) return BundleResult.Refused(Refusal.BAD_MANIFEST, "no pack this app knows", m)
    } else if (m.files.none { f -> '/' !in f.path && f.path in modelNames }) {
      return BundleResult.Refused(Refusal.BAD_MANIFEST, "no model this phone can use", m)
    }
    // 3. version. A model bundle of a fuller class than the installed one is an upgrade whatever its number; a lighter one is
    // refused; the same class compares versions. An add-on compares with its own record, and is repaired when its files are gone.
    if (addon) {
      val recorded = InstalledAddons.read(modelsDir)[m.bundle]
      if (recorded != null && m.version < recorded) return BundleResult.Refused(Refusal.OLDER_VERSION, "add-on v${m.version}, installed v$recorded", m)
      val whole = m.files.all { f -> targetOf(f.path, honourSkips = false)?.let { it.isFile && it.length() == f.size } ?: true }
      if (recorded == m.version && whole) return BundleResult.Refused(Refusal.ALREADY_INSTALLED, "v$recorded", m)
    } else {
      val rec = InstalledBundle.read(modelsDir)
      if (rec != null && modelNames.any { File(modelsDir, it).isFile }) {
        if (rec.bundleClass.rank > m.bundleClass.rank) return BundleResult.Refused(Refusal.OLDER_VERSION, "installed ${rec.bundleClass.id}, bundle ${m.bundleClass.id}", m)
        if (rec.bundleClass == m.bundleClass) {
          if (m.version < rec.version) return BundleResult.Refused(Refusal.OLDER_VERSION, "bundle v${m.version}, installed v${rec.version}", m)
          if (m.version == rec.version) return BundleResult.Refused(Refusal.ALREADY_INSTALLED, "v${rec.version}", m)
        }
      }
    }
    // 4. room: the staged copies, plus a little for the record and the filesystem.
    val staged = m.files.filter { targetOf(it.path, honourSkips = !addon) != null }.sumOf { it.size }
    modelsDir.mkdirs()
    if (freeSpace(modelsDir) < staged + SPACE_MARGIN) return BundleResult.Refused(Refusal.NO_SPACE, "need ${staged / 1_000_000} MB", m)
    // 5. every file, once.
    val byPath = m.files.associateBy { it.path }
    val seen = HashSet<String>()
    val moves = ArrayList<Pair<File, File>>()
    val total = m.totalBytes
    var done = 0L
    val buf = ByteArray(1 shl 20)
    incoming.mkdirs()
    while (true) {
      val e = zin.nextEntry ?: break
      if (e.isDirectory) continue
      val f = byPath[e.name] ?: return BundleResult.Refused(Refusal.DAMAGED, "unlisted file in bundle", m)
      if (!seen.add(e.name)) return BundleResult.Refused(Refusal.DAMAGED, "file listed twice in zip", m)
      val target = targetOf(f.path, honourSkips = !addon)
      val part = target?.let { File(incoming, "${moves.size}.part") }
      val md = MessageDigest.getInstance("SHA-256")
      var n = 0L
      val out = part?.outputStream()
      try {
        while (true) {
          val r = zin.read(buf)
          if (r < 0) break
          md.update(buf, 0, r)
          out?.write(buf, 0, r)
          n += r
          if (n > f.size) return BundleResult.Refused(Refusal.DAMAGED, "file longer than its manifest size", m)
          done += r
          progress(done, total)
          if (cancelled()) return BundleResult.Refused(Refusal.CANCELLED, "", m)
        }
      } finally {
        out?.close()
      }
      if (n != f.size) return BundleResult.Refused(Refusal.DAMAGED, "file shorter than its manifest size", m)
      if (hex(md.digest()) != f.sha256) return BundleResult.Refused(Refusal.DAMAGED, "SHA-256 mismatch", m)
      if (part != null && target != null) moves += part to target
    }
    if (seen.size != m.files.size) return BundleResult.Refused(Refusal.DAMAGED, "${m.files.size - seen.size} listed files missing", m)
    // 6. everything is proven: move into place, the model last (the engine starts when it appears), then the record.
    val (modelMoves, packMoves) = moves.partition { it.second.parentFile == modelsDir }
    for ((from, to) in packMoves + modelMoves) {
      to.parentFile?.mkdirs()
      Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
    if (addon) InstalledAddons.write(modelsDir, m.bundle, m.version) else InstalledBundle(m.bundle, m.bundleClass, m.version).write(modelsDir)
    return BundleResult.Installed(m, moves.sumOf { it.second.length() })
  }

  /** The named entry if it is the next one and small; null otherwise. */
  private fun smallEntry(zin: ZipInputStream, name: String): ByteArray? {
    val e = zin.nextEntry ?: return null
    if (e.name != name || e.isDirectory) return null
    val out = java.io.ByteArrayOutputStream()
    val b = ByteArray(8192)
    while (true) {
      val r = zin.read(b)
      if (r < 0) break
      out.write(b, 0, r)
      if (out.size() > MAX_SMALL) return null
    }
    return out.toByteArray()
  }

  companion object {
    private const val MAX_SMALL = 512 * 1024
    private const val SPACE_MARGIN = 20_000_000L
    private fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }
  }
}
