package com.craneai.findr.engine

import android.content.Context
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** What was remembered when the probes last passed: the files they passed on, the app version, and when. */
data class ProbeRecord(val fingerprint: String, val appVersion: Long, val passedAtMs: Long)

/** Where the [ProbeRecord] lives (the app's private preferences; a fake in the tests). */
interface ProbeMemory {
  fun read(): ProbeRecord?
  fun write(record: ProbeRecord)
  fun clear()
}

class PrefsProbeMemory(context: Context) : ProbeMemory {
  private val prefs = context.applicationContext.getSharedPreferences("findr_engine_health", Context.MODE_PRIVATE)

  override fun read(): ProbeRecord? {
    val f = prefs.getString("fingerprint", null) ?: return null
    return ProbeRecord(f, prefs.getLong("appVersion", -1), prefs.getLong("passedAtMs", 0))
  }

  // commit(), not apply(): a crash right after must not leave a half-trusted record
  override fun write(record: ProbeRecord) {
    prefs.edit().putString("fingerprint", record.fingerprint).putLong("appVersion", record.appVersion)
      .putLong("passedAtMs", record.passedAtMs).commit()
  }

  override fun clear() { prefs.edit().clear().commit() }
}

/**
 * When the engine probes need to run. A normal warm load, with the cache files exactly as the last passing run left them,
 * skips them (they cost seconds on a 4 GB phone). They run when something that could have damaged the cache may have happened:
 *  - nothing passed before, or the record is unreadable;
 *  - the model's cache files, or the model file, differ from what passed (a torn or concurrent write, a model swap, a cleared cache);
 *  - the model has no cache file yet (the probe's own embed is what builds it, and it is checked at once);
 *  - the app version changed (a new LiteRT-LM or new probes);
 *  - Findr crashed or hit an ANR after the last pass (the process may have died mid-write).
 * Not covered by the fingerprint, by design: a bit flipped in the middle of a cache file that keeps its size and modification
 * time (the head and tail are hashed, the middle is not). The once-per-app-version probe is the backstop for that.
 */
object ProbeGate {
  /** Null: run the probes. A string: skip them, and this is why. [lastBadExitAtMs] is only asked when everything else matches. */
  fun skipReason(record: ProbeRecord?, fingerprintNow: String, appVersion: Long, lastBadExitAtMs: () -> Long): String? {
    if (record == null) return null
    if (fingerprintNow.isEmpty() || fingerprintNow != record.fingerprint) return null
    if (record.appVersion != appVersion) return null
    if (lastBadExitAtMs() > record.passedAtMs) return null
    return "the cache files and the app version are the ones that passed, and nothing crashed since"
  }
}

/** The three hooks [EngineSelfTest] takes, wired to a [ProbeMemory] for one model file. The Embedder and the tests use the same one. */
class ProbeSchedule(
  private val memory: ProbeMemory,
  private val cacheDir: File,
  private val modelFile: File,
  private val appVersion: Long,
  private val lastBadExitAtMs: () -> Long,
  private val now: () -> Long = System::currentTimeMillis,
) {
  fun skipReason(): String? = ProbeGate.skipReason(memory.read(), EngineFingerprint.of(cacheDir, modelFile), appVersion, lastBadExitAtMs)
  fun passed() = memory.write(ProbeRecord(EngineFingerprint.of(cacheDir, modelFile), appVersion, now()))
  fun failed() = memory.clear()
}

object EngineFingerprint {
  /** How much of a file's start and end is hashed: the bytes a torn or replaced file changes first, cheap even for a 70 MB cache. */
  const val HASH_HEAD_BYTES = 64 * 1024
  const val HASH_TAIL_BYTES = 4 * 1024

  /**
   * Name, size, modification time in nanoseconds (a whole-second or millisecond clock let a file rebuilt in the same tick look
   * unchanged) and a short hash of the first and last bytes, for the model's xnnpack cache files and the model file itself, in
   * name order; empty when the model has no cache file yet. Only our own file names, numbers and a hash: nothing about the
   * person's files.
   */
  fun of(cacheDir: File, modelFile: File): String {
    val caches = cacheDir.listFiles { f -> f.isFile && f.name.startsWith("${modelFile.name}_") && f.name.endsWith(".xnnpack_cache") }
      ?.sortedBy { it.name }.orEmpty()
    if (caches.isEmpty()) return ""
    return (listOf(modelFile) + caches).joinToString("|") { "${it.name}:${it.length()}:${modifiedNanos(it)}:${edgeHash(it)}" }
  }

  private fun modifiedNanos(f: File): Long = try {
    Files.readAttributes(f.toPath(), BasicFileAttributes::class.java).lastModifiedTime().to(TimeUnit.NANOSECONDS)
  } catch (_: Exception) { f.lastModified() * 1_000_000L }

  /** SHA-256 (first 12 hex digits) over the head and the tail of the file; "-" if it cannot be read, which never equals a pass. */
  internal fun edgeHash(f: File): String = try {
    val md = MessageDigest.getInstance("SHA-256")
    RandomAccessFile(f, "r").use { raf ->
      val len = raf.length()
      val head = ByteArray(minOf(len, HASH_HEAD_BYTES.toLong()).toInt())
      raf.readFully(head)
      md.update(head)
      val tailStart = maxOf(len - HASH_TAIL_BYTES, head.size.toLong())
      if (tailStart < len) {
        val tail = ByteArray((len - tailStart).toInt())
        raf.seek(tailStart)
        raf.readFully(tail)
        md.update(tail)
      }
    }
    md.digest().take(6).joinToString("") { "%02x".format(it) }
  } catch (_: Exception) { "-" }
}
