package com.craneai.findr.bench

import android.app.Activity
import android.app.KeyguardManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.util.Log
import android.view.WindowManager
import android.widget.ScrollView
import android.widget.TextView
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.craneai.findr.core.FileKind
import com.craneai.findr.core.KindFilter
import com.craneai.findr.index.CrawlRecord
import com.craneai.findr.index.Db
import com.craneai.findr.index.FileState
import com.craneai.findr.index.IndexStore
import com.craneai.findr.index.Modality
import com.craneai.findr.index.Segment
import com.craneai.findr.index.SegmentKind
import com.craneai.findr.search.Searcher
import com.craneai.findr.search.VectorIndex
import java.io.File
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.spec.MGF1ParameterSpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject

private const val TAG = "FindrSecBench"
private const val SQLCIPHER_DRIVER = "net.zetetic.database.sqlcipher.driver.SQLCipherDriver"

/**
 * What would encrypting the index cost, and what does secure delete cost? (the research notes)
 *
 *   adb shell am start -n <pkg>/com.craneai.findr.bench.SecurityBenchActivity [--ei files 5000]
 *
 * Builds one synthetic index (a phone's mix of photos, screenshots, documents, videos and recordings, with OCR text,
 * transcripts and 768-d vectors) under each storage option, through Findr's own IndexStore and Searcher, and times
 * what a person waits for: saving a file, opening the index, loading vectors (the first search), a words search and a
 * meaning search, and deleting files. Then the Keystore and AES-GCM steps a private store (option c) would need.
 * SQLCipher runs only in a build made with `-PbenchSqlcipher=true`; it is not a Findr dependency.
 * Result: FINDR_SEC lines in logcat and files/results/security-<model>.json; FINDR_SEC_DONE at the end.
 * Everything it writes stays in its own files dir and is deleted at the end, except the result.
 */
class SecurityBenchActivity : Activity() {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private lateinit var view: TextView
  private val out = JSONObject()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    view = TextView(this).apply { textSize = 11f; setPadding(24, 96, 24, 24) }
    setContentView(ScrollView(this).apply { addView(view) })
    val files = intent.getIntExtra("files", 5000)
    scope.launch {
      try {
        run(files)
      } catch (t: Throwable) {
        Log.e(TAG, "bench failed", t)
        say("error", t.toString())
      } finally {
        File(filesDir, "results").mkdirs()
        File(filesDir, "results/security-${Build.MODEL.replace(' ', '_')}.json").writeText(out.toString(2))
        say("done", "files/results/security-${Build.MODEL.replace(' ', '_')}.json")
        Log.i(TAG, "FINDR_SEC_DONE")
      }
    }
  }

  override fun onDestroy() {
    scope.cancel()
    super.onDestroy()
  }

  private fun say(key: String, value: Any) {
    out.put(key, value)
    Log.i(TAG, "FINDR_SEC $key=$value")
    runOnUiThread { view.append("$key = $value\n") }
  }

  private suspend fun run(files: Int) {
    say("model", Build.MODEL)
    say("sdk", Build.VERSION.SDK_INT)
    say("files", files)
    val work = File(filesDir, "secbench").apply { deleteRecursively(); mkdirs() }
    val corpus = Corpus(files)
    say("corpus", corpus.describe())

    val drivers = linkedMapOf<String, () -> SQLiteDriver>("bundled" to { BundledSQLiteDriver() })
    if (hasSqlcipher) {
      val rawKey = ByteArray(32).also { SecureRandom().nextBytes(it) }
      // "x'…'" is SQLCipher's raw-key form: a random key (kept wrapped by the Keystore) needs no PBKDF2 at every open.
      val rawKeySpec = "x'${rawKey.joinToString("") { "%02x".format(it) }}'".toByteArray()
      drivers["sqlcipher-nokey"] = { sqlcipher(ByteArray(0)) }
      drivers["sqlcipher-rawkey"] = { sqlcipher(rawKeySpec) }
    }
    say("sqlcipher", if (hasSqlcipher) "present" else "absent (build with -PbenchSqlcipher=true)")

    for ((name, driver) in drivers) {
      runCatching { storeBench(name, File(work, name), driver, corpus) }
        .onFailure { Log.e(TAG, "$name failed", it); say("$name.error", it.toString()) }
    }
    if (hasSqlcipher) runCatching { passphraseOpen(File(work, "pass")) }.onFailure { say("sqlcipher-passphrase.error", it.toString()) }
    secureDeleteBench(File(work, "bundled"), corpus)
    keystoreBench()
    work.deleteRecursively()
  }

  // ---- (a)/(b): the index under each driver ------------------------------------------------------------------------

  private fun storeBench(name: String, dir: File, driver: () -> SQLiteDriver, corpus: Corpus) {
    var store = IndexStore.open(dir, driver(), log = { Log.i(TAG, "$name: $it") })
    val compile = store.read { db -> buildList { db.query("PRAGMA compile_options") { add(it.getText(0)) } } }
    say("$name.fts5", compile.any { it.contains("FTS5") })
    say("$name.sqlite", store.read { it.text("SELECT sqlite_version()") } ?: "?")
    // Names first (the crawl), then one saveSegments per file (what the worker does), timed per file.
    val crawlMs = timeMs { corpus.records.chunked(200).forEach { store.upsertFromCrawl(it) } }
    val ids = store.knownMediaStoreIds("external_primary")
    val perFile = DoubleArray(corpus.records.size)
    corpus.records.forEachIndexed { i, r ->
      val segments = corpus.segmentsFor(i)
      perFile[i] = timeMs { store.saveSegments(ids[r.mediaStoreId]!!, segments, FileState.UNDERSTOOD) }
    }
    say("$name.crawl_ms_per_1000", "%.1f".format(crawlMs / corpus.records.size * 1000))
    say("$name.save_ms_per_file", stats(perFile))
    store.close()   // checkpoints
    say("$name.bytes", File(dir, IndexStore.FILE_NAME).length())

    val opens = DoubleArray(5) { timeMs { IndexStore.open(dir, driver()).close() } }
    say("$name.open_ms", stats(opens))
    store = IndexStore.open(dir, driver())
    val vectors = VectorIndex()
    val loadMs = timeMs { vectors.load(store) }
    say("$name.vector_load_ms", "%.0f (%d vectors)".format(loadMs, vectors.size))
    val searcher = Searcher(store, vectors)
    val words = ArrayList<Double>()
    val meaning = ArrayList<Double>()
    repeat(3) {
      corpus.queries.forEach { q ->
        words += timeMs { searcher.search(q, null, KindFilter.ALL, 60, prefixLast = true) }
        meaning += timeMs { searcher.search(q, corpus.queryVector(q), KindFilter.ALL, 60, prefixLast = true) }
      }
    }
    say("$name.words_search_ms", stats(words.toDoubleArray()))
    say("$name.meaning_search_ms", stats(meaning.toDoubleArray()))
    val gone = ids.values.shuffled(Random(7)).take(300)
    val delMs = timeMs { store.markDeleted(gone) }
    say("$name.delete_ms_per_file", "%.2f".format(delMs / gone.size))
    store.close()
  }

  /** SQLCipher with a typed passphrase: the PBKDF2 cost an app pays at every open if it doesn't use a raw key. */
  private fun passphraseOpen(dir: File) {
    dir.mkdirs()
    val pass = "correct horse battery staple".toByteArray()
    IndexStore.open(dir, sqlcipher(pass)).close()
    val ms = DoubleArray(3) { timeMs { IndexStore.open(dir, sqlcipher(pass)).close() } }
    say("sqlcipher-passphrase.open_ms", stats(ms))
  }

  /**
   * SQLCipher's driver runs every statement through an Android Cursor, and its `PRAGMA journal_mode` statement reads the
   * result row before checking one came back; through the Cursor none does, so Findr's `PRAGMA journal_mode = WAL` throws
   * "step() must be called before reading column values" (seen on the Infinix, 4.19.1). This turns that one pragma into
   * SQLCipher's own `enableWriteAheadLogging()`, so the bench measures it in the WAL mode Findr uses. A real switch to
   * SQLCipher would need the same.
   */
  private class WalShim(private val inner: SQLiteDriver) : SQLiteDriver {
    override fun open(fileName: String): SQLiteConnection {
      val c = inner.open(fileName)
      return object : SQLiteConnection by c {
        override fun prepare(sql: String): SQLiteStatement {
          if (!sql.trim().startsWith("PRAGMA journal_mode", ignoreCase = true)) return c.prepare(sql)
          val db = c.javaClass.getDeclaredField("database").apply { isAccessible = true }.get(c)!!
          db.javaClass.getMethod("enableWriteAheadLogging").invoke(db)
          return c.prepare("SELECT 1 WHERE 0")   // no rows: the caller ignores the pragma's answer
        }
      }
    }
  }

  /** SQLCipher is in this build (`-PbenchSqlcipher=true`) and its native library loads. */
  private val hasSqlcipher: Boolean by lazy {
    runCatching { Class.forName(SQLCIPHER_DRIVER); System.loadLibrary("sqlcipher") }.isSuccess
  }

  /** The SQLCipher driver, by reflection so this compiles without it. An empty key opens a plain (unencrypted) file. */
  private fun sqlcipher(key: ByteArray): SQLiteDriver = Class.forName(SQLCIPHER_DRIVER).getConstructor(
    ByteArray::class.java,
    Class.forName("net.zetetic.database.sqlcipher.SQLiteDatabaseHook"),
    Class.forName("net.zetetic.database.DatabaseErrorHandler"),
  ).newInstance(key, null, null).let { WalShim(it as SQLiteDriver) }

  // ---- secure delete: the same deletes on two copies, one with it off ------------------------------------------------

  private fun secureDeleteBench(built: File, corpus: Corpus) {
    // Rebuild a fresh copy (the store bench deleted 300 files from its own).
    val dir = File(built.parentFile, "sd-src").apply { mkdirs() }
    val store = IndexStore.open(dir)
    corpus.records.chunked(200).forEach { store.upsertFromCrawl(it) }
    val ids = store.knownMediaStoreIds("external_primary")
    corpus.records.forEachIndexed { i, r -> store.saveSegments(ids[r.mediaStoreId]!!, corpus.segmentsFor(i), FileState.UNDERSTOOD) }
    store.close()
    val gone = ids.values.shuffled(Random(11)).take(500)
    for (on in listOf(false, true)) {
      val copy = File(built.parentFile, "sd-$on").apply { deleteRecursively(); mkdirs() }
      File(dir, IndexStore.FILE_NAME).copyTo(File(copy, IndexStore.FILE_NAME))
      val db = Db(BundledSQLiteDriver().open(File(copy, IndexStore.FILE_NAME).path))
      db.query("PRAGMA journal_mode = WAL") { }
      db.exec("PRAGMA foreign_keys = ON")
      db.exec("PRAGMA secure_delete = ${if (on) "ON" else "OFF"}")
      db.run("INSERT INTO fts(fts, rank) VALUES('secure-delete', ${if (on) 1 else 0})")
      val ms = timeMs {
        db.transaction { gone.forEach { db.run("DELETE FROM files WHERE id = ?", it) } }
        db.query("PRAGMA wal_checkpoint(TRUNCATE)") { }
      }
      say("secure_delete_${if (on) "on" else "off"}.ms_per_file", "%.2f".format(ms / gone.size))
      // A file that changed: its old words replaced (what saveSegments does on a re-read).
      val changed = ids.values.shuffled(Random(13)).filter { it !in gone }.take(200)
      val reMs = timeMs { db.transaction { changed.forEach { db.run("DELETE FROM segments WHERE file = ? AND kind != 'name'", it) } } }
      say("secure_delete_${if (on) "on" else "off"}.replace_ms_per_file", "%.2f".format(reMs / changed.size))
      db.close()
    }
  }

  // ---- (c): the private store's crypto steps ------------------------------------------------------------------------

  private fun keystoreBench() {
    val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    val km = getSystemService(KeyguardManager::class.java)
    say("device_secure", km.isDeviceSecure)
    say("strongbox", packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE))

    // An AES key in the TEE wrapping a 32-byte data key: each wrap or unwrap is one Keystore operation.
    val alias = "secbench-aes"
    var aes: SecretKey? = null
    val genMs = timeMs {
      aes = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
        init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
          .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
          .setKeySize(256).build())
      }.generateKey()
    }
    say("keystore_aes.generate_ms", "%.1f".format(genMs))
    say("keystore_aes.level", securityLevel(aes!!))
    val dek = ByteArray(32).also { SecureRandom().nextBytes(it) }
    var wrapped = ByteArray(0)
    var iv = ByteArray(0)
    val wrap = DoubleArray(20) {
      timeMs {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, aes) }
        wrapped = c.doFinal(dek); iv = c.iv
      }
    }
    val unwrap = DoubleArray(20) {
      timeMs { Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, aes, GCMParameterSpec(128, iv)) }.doFinal(wrapped) }
    }
    say("keystore_aes.wrap_ms", stats(wrap))
    say("keystore_aes.unwrap_ms", stats(unwrap))

    // Per item, in software with the unwrapped data key: a private file's text (3 KB) and its vectors (2 × 1.5 KB).
    val key = SecretKeySpec(dek, "AES")
    val item = ByteArray(6 * 1024).also { Random(3).nextBytes(it) }
    val enc = ArrayList<Pair<ByteArray, ByteArray>>()
    val encMs = timeMs {
      repeat(1000) {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
        enc += c.doFinal(item) to c.iv
      }
    }
    val decMs = timeMs {
      enc.forEach { (ct, nonce) -> Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, nonce)) }.doFinal(ct) }
    }
    say("aes_gcm_6kb.encrypt_us_per_item", "%.0f".format(encMs * 1000 / 1000))
    say("aes_gcm_6kb.decrypt_ms_per_1000_items", "%.0f".format(decMs))

    // RSA-OAEP: the worker can seal a new private item with the public key while the phone is locked; opening needs the private key.
    val rsaAlias = "secbench-rsa"
    var pair: java.security.KeyPair? = null
    val rsaGen = timeMs {
      pair = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore").apply {
        initialize(KeyGenParameterSpec.Builder(rsaAlias, KeyProperties.PURPOSE_DECRYPT)
          .setKeySize(2048).setDigests(KeyProperties.DIGEST_SHA256).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP).build())
      }.generateKeyPair()
    }
    say("keystore_rsa.generate_ms", "%.0f".format(rsaGen))
    val oaep = OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT)
    // The public key used outside the Keystore, as a worker would (a public-key op needs no authentication).
    val pub = java.security.KeyFactory.getInstance("RSA").generatePublic(java.security.spec.X509EncodedKeySpec(pair!!.public.encoded))
    var sealed = ByteArray(0)
    val seal = DoubleArray(20) { timeMs { sealed = Cipher.getInstance("RSA/ECB/OAEPPadding").apply { init(Cipher.ENCRYPT_MODE, pub, oaep) }.doFinal(dek) } }
    val open = DoubleArray(20) { timeMs { Cipher.getInstance("RSA/ECB/OAEPPadding").apply { init(Cipher.DECRYPT_MODE, pair!!.private, oaep) }.doFinal(sealed) } }
    say("keystore_rsa.seal_ms", stats(seal))
    say("keystore_rsa.open_ms", stats(open))

    // Can this phone make a key that only works after the person authenticates? (Needs a screen lock.)
    val authAlias = "secbench-auth"
    val auth = runCatching {
      KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
        init(KeyGenParameterSpec.Builder(authAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
          .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256)
          .setUserAuthenticationRequired(true)
          .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
          .build())
      }.generateKey()
    }
    say("auth_bound_key", auth.fold({ "created (${securityLevel(it)})" }, { "refused: ${it.javaClass.simpleName}: ${it.message}" }))
    // Using it without a prompt must fail: the point of option (c).
    auth.getOrNull()?.let { k ->
      say("auth_bound_key.use_without_prompt", runCatching { Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, k) }.doFinal(dek) }
        .fold({ "WORKED (unexpected)" }, { "refused: ${it.javaClass.simpleName}" }))
    }
    listOf(alias, rsaAlias, authAlias).forEach { runCatching { ks.deleteEntry(it) } }
  }

  private fun securityLevel(key: java.security.Key): String = runCatching {
    val info = if (key is SecretKey) {
      SecretKeyFactory.getInstance(key.algorithm, "AndroidKeyStore").getKeySpec(key, KeyInfo::class.java) as KeyInfo
    } else {
      java.security.KeyFactory.getInstance(key.algorithm, "AndroidKeyStore").getKeySpec(key, KeyInfo::class.java)
    }
    when (info.securityLevel) {
      KeyProperties.SECURITY_LEVEL_STRONGBOX -> "StrongBox"
      KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> "TEE"
      KeyProperties.SECURITY_LEVEL_SOFTWARE -> "software"
      else -> "level ${info.securityLevel}"
    }
  }.getOrElse { "unknown" }

  // ---- helpers -------------------------------------------------------------------------------------------------------

  private inline fun timeMs(block: () -> Unit): Double {
    val t0 = System.nanoTime()
    block()
    return (System.nanoTime() - t0) / 1e6
  }

  private fun stats(ms: DoubleArray): String {
    val s = ms.sorted()
    fun q(p: Double) = s[((s.size - 1) * p).toInt()]
    return "median %.2f p90 %.2f max %.2f (n=%d)".format(q(0.5), q(0.9), s.last(), s.size)
  }

  /**
   * A phone's worth of index, made up but shaped like the real thing (D13: ~2.5 KB a photo, ~2.2 KB a document chunk):
   * 70% photos (a third with OCR text), 10% screenshots (all with OCR), 10% documents (1–10 chunks of ~400 characters),
   * 7% videos (3 frames and 2 transcript lines), 3% recordings (4 transcript lines). Words follow a Zipf-like spread.
   */
  private class Corpus(n: Int) {
    private val rnd = Random(42)
    private val syll = listOf("ka", "mo", "li", "na", "te", "ru", "be", "so", "di", "ga", "ne", "po", "mi", "lu", "ze", "wa", "ki", "to", "ba", "fe")
    private val vocab = List(6000) { i -> buildString { var x = i + 7; repeat(2 + i % 3) { append(syll[x % syll.size]); x /= syll.size + 1 } } + if (i % 5 == 0) "s" else "" }
    private fun word(): String = vocab[(vocab.size * Math.pow(rnd.nextDouble(), 2.2)).toInt().coerceIn(0, vocab.size - 1)]
    private fun text(chars: Int) = buildString { while (length < chars) append(word()).append(' ') }.trim()
    private fun vec() = FloatArray(768) { rnd.nextFloat() - 0.5f }

    val kinds: List<FileKind> = List(n) { i ->
      when (i % 100) { in 0..69 -> FileKind.IMAGE; in 70..79 -> FileKind.SCREENSHOT; in 80..89 -> FileKind.DOCUMENT; in 90..96 -> FileKind.VIDEO; else -> FileKind.AUDIO }
    }
    val records: List<CrawlRecord> = kinds.mapIndexed { i, k ->
      val ext = when (k) { FileKind.IMAGE -> "jpg"; FileKind.SCREENSHOT -> "png"; FileKind.DOCUMENT -> "pdf"; FileKind.VIDEO -> "mp4"; else -> "opus" }
      CrawlRecord("external_primary", i + 1L, "content://media/external/file/${i + 1}", "DCIM/${word()}/", "${word()}_${i}.$ext", k, null, 100_000L + i, 1_700_000_000_000L + i)
    }
    // Segments are made on demand (they hold the vectors) but the same each time: seeded per file.
    fun segmentsFor(i: Int): List<Segment> {
      val r = Random(1000 + i)
      fun t(chars: Int) = buildString { while (length < chars) append(vocab[(vocab.size * Math.pow(r.nextDouble(), 2.2)).toInt().coerceIn(0, vocab.size - 1)]).append(' ') }.trim()
      fun v() = FloatArray(768) { r.nextFloat() - 0.5f }
      return when (kinds[i]) {
        FileKind.IMAGE -> listOf(Segment(SegmentKind.IMAGE, Modality.IMAGE, vector = v())) +
          if (i % 3 == 0) listOf(Segment(SegmentKind.OCR, Modality.NONE, text = t(120))) else emptyList()
        FileKind.SCREENSHOT -> listOf(Segment(SegmentKind.IMAGE, Modality.IMAGE, vector = v()), Segment(SegmentKind.OCR, Modality.TEXT, text = t(300), vector = v()))
        FileKind.DOCUMENT -> List(1 + i % 10) { p -> Segment(SegmentKind.CHUNK, Modality.TEXT, p + 1L, p + 1L, text = t(400), vector = v()) }
        FileKind.VIDEO -> List(3) { f -> Segment(SegmentKind.FRAME, Modality.IMAGE, f * 5_000L, f * 5_000L + 4_000, vector = v()) } +
          List(2) { s -> Segment(SegmentKind.TRANSCRIPT, Modality.TEXT, s * 9_000L, s * 9_000L + 9_000, text = t(200), vector = v()) }
        else -> List(4) { s -> Segment(SegmentKind.TRANSCRIPT, Modality.TEXT, s * 9_000L, s * 9_000L + 9_000, text = t(200), vector = v()) }
      }
    }
    val queries: List<String> = List(30) { q -> List(1 + q % 3) { vocab[10 + rnd.nextInt(1500)] }.joinToString(" ") }
    private val qv = HashMap<String, FloatArray>()
    fun queryVector(q: String) = qv.getOrPut(q) { vec() }
    fun describe() = kinds.groupingBy { it }.eachCount().entries.joinToString(", ") { "${it.value} ${it.key.name.lowercase()}" }
  }
}
