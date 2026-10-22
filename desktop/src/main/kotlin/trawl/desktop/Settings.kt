package trawl.desktop

import com.craneai.findr.core.LearningSpeed
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.json.JSONArray
import org.json.JSONObject

enum class ThemeChoice(val id: String) {
  SYSTEM("system"), LIGHT("light"), DARK("dark");

  companion object { fun of(id: String?) = entries.firstOrNull { it.id == id } ?: SYSTEM }
}

/**
 * How big the text is, across the whole app (the main window, Settings, first run, dialogs and the quick panel). It scales the type only:
 * boxes, icons and targets keep their size, so a bigger setting reads larger without the window turning into a magnifier.
 * [scale] is what Compose multiplies its font scale by. Cmd/Ctrl + Plus and Minus step through these four, Cmd/Ctrl + 0 returns to [DEFAULT].
 */
enum class TextSize(val id: String, val scale: Float) {
  SMALL("small", 0.9f), DEFAULT("default", 1.0f), LARGE("large", 1.3f), LARGER("larger", 1.5f);

  fun bigger(): TextSize = entries.getOrElse(ordinal + 1) { this }
  fun smaller(): TextSize = entries.getOrElse(ordinal - 1) { this }

  companion object { fun of(id: String?) = entries.firstOrNull { it.id == id } ?: DEFAULT }
}

/**
 * Contrast: [SYSTEM] follows the computer's own high-contrast setting where Trawl can read it (see OsContrast), [STANDARD] is the Sunlight
 * colours as designed, [HIGH] is pure black and white text with strong edges whatever the computer says.
 */
enum class ContrastChoice(val id: String) {
  SYSTEM("system"), STANDARD("standard"), HIGH("high");

  companion object { fun of(id: String?) = entries.firstOrNull { it.id == id } ?: SYSTEM }
}

/** What the person chose. Small, and the only thing besides the index and the models that Trawl writes to disk. */
data class Settings(
  val onboarded: Boolean = false,
  /** Absolute paths of the folders Trawl reads, in the order added. */
  val folders: List<String> = emptyList(),
  val theme: ThemeChoice = ThemeChoice.SYSTEM,
  /** Models the person declined at first run ("Not now"): the first-run card is not shown again, Settings still offers them. */
  val modelsDeclined: Boolean = false,
  /** "What to read": off stops reading that kind and takes it out of results. Documents also covers PDFs. */
  val indexPhotos: Boolean = true,
  val indexScreenshots: Boolean = true,
  val indexDocuments: Boolean = true,
  /** Videos (their pictures and their sound) and recordings (voice notes, music, calls). Both need the computer's decoder; Settings says when it is missing. */
  val indexVideos: Boolean = true,
  val indexAudio: Boolean = true,
  /** Absolute paths of folders inside the chosen folders that are never read. */
  val excludedFolders: List<String> = emptyList(),
  /** How hard Trawl works while reading: [LearningSpeed.BALANCED] is how it has always worked. */
  val learningSpeed: LearningSpeed = LearningSpeed.BALANCED,
  /** The person's Pause: kept across restarts, cleared only by Resume. */
  val indexPaused: Boolean = false,
  /** A language tag the person picked ("lg", "sw"), or null to follow the computer. */
  val language: String? = null,
  /** Text size for the whole app (see [TextSize]). */
  val textSize: TextSize = TextSize.DEFAULT,
  val contrast: ContrastChoice = ContrastChoice.SYSTEM,
)

/** `settings.json` in the data folder, written through a temp file and a rename so a crash never leaves half a file. */
class SettingsStore(private val dir: File) {
  private val file = File(dir, "settings.json")

  fun load(): Settings = try {
    val o = JSONObject(file.readText())
    Settings(
      onboarded = o.optBoolean("onboarded", false),
      folders = o.optJSONArray("folders")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
      theme = ThemeChoice.of(o.optString("theme")),
      modelsDeclined = o.optBoolean("modelsDeclined", false),
      indexPhotos = o.optBoolean("indexPhotos", true),
      indexScreenshots = o.optBoolean("indexScreenshots", true),
      indexDocuments = o.optBoolean("indexDocuments", true),
      indexVideos = o.optBoolean("indexVideos", true),
      indexAudio = o.optBoolean("indexAudio", true),
      excludedFolders = o.optJSONArray("excludedFolders")?.let { a -> (0 until a.length()).mapNotNull { a.optString(it).takeIf { s -> s.isNotBlank() } } } ?: emptyList(),
      learningSpeed = LearningSpeed.of(o.optString("learningSpeed")) ?: LearningSpeed.BALANCED,
      indexPaused = o.optBoolean("indexPaused", false),
      language = o.optString("language").takeIf { it.isNotBlank() },
      textSize = TextSize.of(o.optString("textSize")),
      contrast = ContrastChoice.of(o.optString("contrast")),
    )
  } catch (_: Exception) {
    Settings()   // no file yet, or one we cannot read: start from the first-run screen
  }

  fun save(s: Settings) {
    dir.mkdirs()
    val part = File(dir, "settings.json.part")
    part.writeText(
      JSONObject().put("onboarded", s.onboarded).put("folders", JSONArray(s.folders)).put("theme", s.theme.id)
        .put("modelsDeclined", s.modelsDeclined).put("indexPhotos", s.indexPhotos).put("indexScreenshots", s.indexScreenshots)
        .put("indexDocuments", s.indexDocuments).put("indexVideos", s.indexVideos).put("indexAudio", s.indexAudio).put("excludedFolders", JSONArray(s.excludedFolders)).put("learningSpeed", s.learningSpeed.key)
        .put("indexPaused", s.indexPaused).put("language", s.language ?: "").put("textSize", s.textSize.id).put("contrast", s.contrast.id).toString(2),
    )
    Files.move(part.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
  }
}
