package trawl.desktop.privacy

import com.craneai.findr.index.PrivacyRules
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.json.JSONArray
import org.json.JSONObject

/**
 * Everything the person decided about privacy, in one small file (`privacy.json` in the data folder) that is NOT the index:
 * "Delete index" and a rebuild throw the index away, and the marks must survive that or a rebuild would quietly make a private
 * file findable again (the same reason the phone keeps them in settings, not in the index).
 *
 * @property lockEnabled the app lock is on (needs [passphrase]: it is the fallback on every computer)
 * @property useOsPrompt also accept Touch ID / Windows Hello where the computer has it
 * @property blurEnabled blur thumbnails that look private (only does anything while the Privacy pack is installed)
 * @property hideFromCapture keep Trawl's window out of screenshots and screen sharing where the system allows it (it always is while a sensitive result is on screen)
 * @property rules the folders and files the person marked private (the only things that hide a result)
 * @property lockOfferShown the one-time "want to lock Trawl?" offer after the first Mark sensitive has been shown
 * @property declinedPacks add-ons the person said "Not now" or Remove to: never installed behind their back
 * @property dismissedUpdates add-on id to the version whose update notice was dismissed
 */
data class PrivacySettings(
  val lockEnabled: Boolean = false,
  val lockAfterMinutes: Int = 5,
  val useOsPrompt: Boolean = true,
  val passphrase: PassphraseRecord? = null,
  val blurEnabled: Boolean = true,
  val hideFromCapture: Boolean = false,
  val rules: PrivacyRules = PrivacyRules.NONE,
  val lockOfferShown: Boolean = false,
  val blurHintShown: Boolean = false,
  val declinedPacks: Set<String> = emptySet(),
  val dismissedUpdates: Map<String, Int> = emptyMap(),
) {
  companion object {
    /** The choices Settings offers, in minutes; 0 asks every time Trawl comes back to the front. */
    val LOCK_AFTER_CHOICES = listOf(0, 1, 5, 15, 30)
  }
}

/** How [PrivacyStore.load] found the file. */
enum class LoadState {
  /** Read normally. */
  OK,
  /** There is no file yet: a first run. */
  FRESH,
  /** The last good copy was used because the file was damaged. */
  RECOVERED,
  /** Neither the file nor its backup could be read: defaults are in force and the person is told (their marks and lock are gone). */
  DAMAGED,
}

/**
 * `privacy.json`, written through a temp file and a rename (a crash never leaves half a file), with the previous good copy kept as
 * `privacy.json.bak`. A damaged file is never silently treated as "no privacy settings": it falls back to the copy, and when there is
 * none the caller is told ([LoadState.DAMAGED]) so the screen can say plainly that the marks and the lock were lost.
 */
class PrivacyStore(private val dir: File) {
  val file = File(dir, "privacy.json")
  private val backup = File(dir, "privacy.json.bak")

  fun load(): Pair<PrivacySettings, LoadState> {
    if (!file.exists() && !backup.exists()) return PrivacySettings() to LoadState.FRESH
    parse(file)?.let { return it to LoadState.OK }
    parse(backup)?.let { return it to LoadState.RECOVERED }
    return PrivacySettings() to LoadState.DAMAGED
  }

  fun save(s: PrivacySettings) {
    dir.mkdirs()
    val part = File(dir, "privacy.json.part")
    part.writeText(toJson(s).toString(2))
    // Keep the last good file: only a copy that parses becomes the backup.
    if (file.isFile && parse(file) != null) Files.copy(file.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING)
    Files.move(part.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
  }

  private fun parse(f: File): PrivacySettings? = try {
    if (!f.isFile) null else fromJson(JSONObject(f.readText()))
  } catch (_: Exception) {
    null
  }

  companion object {
    fun toJson(s: PrivacySettings): JSONObject = JSONObject()
      .put("format", 1)
      .put("lockEnabled", s.lockEnabled)
      .put("lockAfterMinutes", s.lockAfterMinutes)
      .put("useOsPrompt", s.useOsPrompt)
      .put("passphrase", s.passphrase?.let {
        // Exactly these four fields: the algorithm, the rounds, the salt and the derived hash. Never the passphrase.
        JSONObject().put("algo", it.algo).put("iterations", it.iterations).put("salt", it.saltB64).put("hash", it.hashB64)
      } ?: JSONObject.NULL)
      .put("blurEnabled", s.blurEnabled)
      .put("hideFromCapture", s.hideFromCapture)
      .put("privateFolders", JSONArray(s.rules.privateFolders.sorted()))
      .put("markedFiles", JSONArray(s.rules.markedFiles.sorted()))
      .put("lockOfferShown", s.lockOfferShown)
      .put("blurHintShown", s.blurHintShown)
      .put("declinedPacks", JSONArray(s.declinedPacks.sorted()))
      .put("dismissedUpdates", JSONObject().also { o -> s.dismissedUpdates.forEach { (k, v) -> o.put(k, v) } })

    fun fromJson(o: JSONObject): PrivacySettings {
      fun strings(name: String): Set<String> = o.optJSONArray(name)?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } ?: emptySet()
      val pass = o.optJSONObject("passphrase")?.let {
        PassphraseRecord(it.getString("algo"), it.getInt("iterations"), it.getString("salt"), it.getString("hash"))
      }
      val dismissed = o.optJSONObject("dismissedUpdates")?.let { d -> d.keys().asSequence().map { it.toString() }.associateWith { d.getInt(it) } } ?: emptyMap()
      return PrivacySettings(
        lockEnabled = o.optBoolean("lockEnabled", false),
        lockAfterMinutes = o.optInt("lockAfterMinutes", 5).takeIf { it >= 0 } ?: 5,
        useOsPrompt = o.optBoolean("useOsPrompt", true),
        passphrase = pass,
        blurEnabled = o.optBoolean("blurEnabled", true),
        hideFromCapture = o.optBoolean("hideFromCapture", false),
        rules = PrivacyRules(privateFolders = strings("privateFolders"), markedFiles = strings("markedFiles")),
        lockOfferShown = o.optBoolean("lockOfferShown", false),
        blurHintShown = o.optBoolean("blurHintShown", false),
        declinedPacks = strings("declinedPacks"),
        dismissedUpdates = dismissed,
      ).let {
        // A lock with no way to open it can never be satisfied: that file is damaged, not "locked".
        if (it.lockEnabled && it.passphrase == null) throw IllegalStateException("lock on without a passphrase") else it
      }
    }
  }
}
