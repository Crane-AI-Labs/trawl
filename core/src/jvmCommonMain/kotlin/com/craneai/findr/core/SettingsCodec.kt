package com.craneai.findr.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * [FindrSettings] as stored in SharedPreferences. Kept apart from the engine so the migration from older builds is a
 * plain function the JVM tests can run.
 *
 * Versions: 1 (first builds), 2 (speech to text on by default for everyone), 3 (folder choices: [FindrSettings.groupOverrides]
 * and [FindrSettings.includedFolders]), 4 (battery policy: "only while charging" became the strict, off-by-default setting and
 * [FindrSettings.fullOnBattery] arrived; [FindrSettings.indexRecordings] too: voice notes and recordings are read by default, and a
 * save without it reads as on, so no further version was needed). A field that is missing reads as its default, and the defaults are the old
 * behaviour: an install from before version 3 has no folder choices, so every folder group follows its default and
 * Findr reads exactly what it read before. The one deliberate exception is "only while charging": any save from before
 * version 4 holds the old default (on), which is not a choice, so it reads as off.
 * 5 (learning speed: the two battery switches became one three-way choice, [LearningSpeed]. A save without it reads from the old
 * pair: "only while charging" on is GENTLE (the strict one wins when both were on), "full detail on battery" on is FAST, else BALANCED.
 * Both old keys are still written, derived, so a downgrade keeps the choice.)
 */
object SettingsCodec {
  const val VERSION = 5

  /**
   * [lite]: the phone runs the lite memory profile (2 GB, engine/MemoryProfile). There speech to text starts off, since the
   * speech engine costs another ~240 MB while it runs (the research notes): a fresh install gets it off, and a saved
   * choice is kept as it is (there is no old default to correct).
   */
  fun decode(raw: String?, lite: Boolean = false): FindrSettings {
    val defaults = if (lite) FindrSettings(speechToText = false) else FindrSettings()
    if (raw == null) return defaults
    return runCatching {
      val o = JSONObject(raw)
      FindrSettings(
        learningSpeed = LearningSpeed.of(o.optString("learningSpeed", ""))
          // Before version 4 "only while charging" meant "everything waits for the charger" and defaulted on; now it is the strict
          // setting (off by default) and the light work runs on battery. An old saved "on" is the old default, not a choice.
          ?: if (o.optInt("version", 1) >= 4 && o.optBoolean("onlyWhileCharging", false)) LearningSpeed.GENTLE
          else if (o.optBoolean("fullOnBattery", false)) LearningSpeed.FAST
          else LearningSpeed.BALANCED,
        indexPhotos = o.optBoolean("indexPhotos", true),
        indexScreenshots = o.optBoolean("indexScreenshots", true),
        indexVideos = o.optBoolean("indexVideos", true),
        indexDocuments = o.optBoolean("indexDocuments", true),
        // Before version 4 the one "Audio" switch was the slow audio vector (default off) and sound files were read only when the
        // speech models were in. "indexAudio" keeps that meaning (now "Deeper sound search"); reading recordings is a new switch, on.
        indexRecordings = o.optBoolean("indexRecordings", true),
        indexAudio = o.optBoolean("indexAudio", false),
        // Speech to text is on by default in every build (D3); it takes effect once the speech models are installed. Settings saved
        // before version 2 stored the old default (off in release builds) without anyone having been able to choose it.
        speechToText = if (lite) o.optBoolean("speechToText", false) else o.optInt("version", 1) < 2 || o.optBoolean("speechToText", true),
        quickLook = o.optBoolean("quickLook", true),
        blurPrivatePreviews = o.optBoolean("blurPrivatePreviews", true),
        privateScoring = o.optBoolean("privateScoring", true),
        excludedFolders = o.strings("excludedFolders"),
        groupOverrides = o.optJSONObject("groups")?.let { g -> g.keys().asSequence().associateWith { g.optBoolean(it, true) } } ?: emptyMap(),
        includedFolders = o.strings("includedFolders"),
        // Privacy marks (app lock work). Additive: a save from before they existed reads as "nothing marked, results hidden".
        showSensitive = o.optBoolean("showSensitive", false),
        privateFolders = o.strings("privateFolders"),
        markedFiles = o.strings("markedFiles"),
      )
    }.getOrDefault(defaults)
  }

  fun encode(s: FindrSettings): String = JSONObject()
    .put("learningSpeed", s.learningSpeed.key).put("onlyWhileCharging", s.onlyWhileCharging).put("fullOnBattery", s.fullOnBattery).put("indexPhotos", s.indexPhotos)
    .put("indexScreenshots", s.indexScreenshots).put("indexVideos", s.indexVideos)
    .put("indexDocuments", s.indexDocuments).put("indexRecordings", s.indexRecordings).put("indexAudio", s.indexAudio)
    .put("speechToText", s.speechToText)
    .put("quickLook", s.quickLook).put("blurPrivatePreviews", s.blurPrivatePreviews)
    .put("privateScoring", s.privateScoring).put("version", VERSION)
    .put("excludedFolders", JSONArray(s.excludedFolders.toList()))
    .put("includedFolders", JSONArray(s.includedFolders.toList()))
    .put("showSensitive", s.showSensitive)
    .put("privateFolders", JSONArray(s.privateFolders.toList()))
    .put("markedFiles", JSONArray(s.markedFiles.toList()))
    .put("groups", JSONObject().also { g -> s.groupOverrides.forEach { (id, on) -> g.put(id, on) } })
    .toString()

  private fun JSONObject.strings(key: String): Set<String> =
    optJSONArray(key)?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } ?: emptySet()
}
