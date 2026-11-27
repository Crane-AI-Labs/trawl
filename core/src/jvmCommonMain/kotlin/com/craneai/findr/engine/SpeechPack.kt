package com.craneai.findr.engine

import com.craneai.findr.core.WordTimes
import java.io.File
import org.json.JSONObject

/**
 * One speech pack of the opt-in speech add-on, as its own `pack.json` describes it: a second speech recogniser for one language,
 * which reads again the speech the English model cannot ([LocalSpeech]). The packs are FastConformer CTC students trained on
 * Leonardo (Luganda, Runyankole, Acholi, Swahili, Lusoga; D48), each a separate download that falls only on the people who want it.
 *
 * Everything that differs from pack to pack is read here, never written into the code: the model's frame (80 ms for the students,
 * 40 ms for the October yonas model), how late its tokens run, and the [stamp] its transcripts carry, so that installing or updating
 * a pack brings the voice notes already in the index up to it (index.SoundtrackCatchUp). The files themselves are pinned by size and
 * SHA-256 in the app (PackFilesRegistry), pack.json included, as for every other pack.
 *
 * @property id the pack's own name (`lg-student`): lower-case letters, digits and dashes
 * @property version the pack's version (`2026112201`); a new version of the same pack re-reads the old voice notes
 * @property frameMs the model's token grid: its subsampling of 10 ms features
 * @property lateBiasMs how late its tokens run against the sound; pack.json's `late_bias_ms`, else Parakeet's (same encoder graph)
 */
class SpeechPack(
  val id: String,
  val version: Long,
  val language: String,
  val languageName: String,
  val frameMs: Long,
  val lateBiasMs: Long,
  val sampleRate: Int,
  val licence: String,
  val model: String,
  val tokens: String,
) {
  /** What a transcript read with this pack is stamped with: `<pack_id>@<pack_version>`. */
  val stamp: String get() = "$id@$version"

  companion object {
    const val FILE = "pack.json"
    private val ID = Regex("[a-z0-9][a-z0-9-]{0,63}")

    /**
     * Reads a pack.json. Throws [IllegalArgumentException] on a pack that would be read wrongly: a missing or odd id, no version, a
     * frame that is not a positive number of milliseconds, a rate other than 16 kHz (what the recognisers are fed).
     */
    fun parse(json: String): SpeechPack {
      val o = JSONObject(json)
      val id = o.optString("pack_id")
      require(ID.matches(id)) { "pack_id '$id' is not a pack name" }
      val version = o.optLong("pack_version", -1)
      require(version > 0) { "pack_version missing" }
      val frameMs = o.optLong("frame_ms", -1)
      require(frameMs in 1..1000) { "frame_ms missing or out of range" }
      val rate = o.optInt("sample_rate", 16_000)
      require(rate == 16_000) { "sample_rate $rate: the recognisers are fed 16 kHz" }
      val files = o.optJSONObject("files")
      fun file(name: String): String {
        require(files == null || files.has(name)) { "pack.json does not list $name" }
        return name
      }
      return SpeechPack(
        id = id,
        version = version,
        language = o.optString("language"),
        languageName = o.optString("language_name"),
        frameMs = frameMs,
        lateBiasMs = o.optLong("late_bias_ms", WordTimes.LATE_BIAS_MS),
        sampleRate = rate,
        licence = o.optString("licence"),
        model = file("model.int8.onnx"),
        tokens = file("tokens.txt"),
      )
    }

    /** The pack installed in [dir], or null when its pack.json or model files are missing or unreadable. */
    fun read(dir: File): SpeechPack? = try {
      val p = parse(File(dir, FILE).readText())
      p.takeIf { File(dir, it.model).isFile && File(dir, it.tokens).isFile }
    } catch (_: Exception) {
      null
    }
  }
}
