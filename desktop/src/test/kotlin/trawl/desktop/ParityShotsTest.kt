package trawl.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.craneai.findr.core.FileKind
import com.craneai.findr.core.IndexedFile
import com.craneai.findr.core.MatchReason
import java.io.File
import java.nio.file.Files
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.jetbrains.skia.EncodedImageFormat
import trawl.desktop.shell.FakeSearch
import trawl.desktop.shell.QuickPanelState
import trawl.desktop.shell.fakeFile
import trawl.desktop.ui.MainUi
import trawl.desktop.ui.ModelsPanel
import trawl.desktop.ui.Notice
import trawl.desktop.ui.ProvideLayoutDirection
import trawl.desktop.ui.Sun
import trawl.desktop.ui.SunlightTheme
import trawl.desktop.ui.DocRow
import trawl.desktop.ui.quick.QuickPanel
import trawl.desktop.ui.search.RepairingNote
import trawl.desktop.ui.settings.FirstRunAddOns
import trawl.desktop.ui.settings.HeicRouteNote

/**
 * The pictures for the parity-close write-up: the screens this package added, drawn offscreen at laptop size (1512x982), light and dark,
 * with made-up names and numbers, into build/shots/parity-*.png. Nothing opens a window. A real ffmpeg-made test video stands behind the
 * video rows, so the frame on the row is a real decoded frame. The assertions only say that a drawing happened; the evidence is looking.
 */
@OptIn(ExperimentalComposeUiApi::class)
class ParityShotsTest {
  private val out = File("build/shots").also { it.mkdirs() }
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private val dirs = ArrayList<File>()
  private fun tmp() = Files.createTempDirectory("trawl-shots").toFile().canonicalFile.also { dirs += it }

  @AfterTest fun clean() { scope.cancel(); dirs.forEach { it.deleteRecursively() }; Strings.use(Locale.ENGLISH) }

  /** Draws [content] on a 1512x982 page, pumping the scene until [ready] says its background work has landed (or [waitMs] has passed). */
  private fun shoot(name: String, theme: ThemeChoice, waitMs: Long = 4_000, ready: () -> Boolean = { true }, content: @Composable () -> Unit) {
    Strings.use(Locale.ENGLISH)
    val scene = ImageComposeScene(width = 1512, height = 982, density = Density(1f)) {
      SunlightTheme(theme) {
        ProvideLayoutDirection(null) {
          Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() }
        }
      }
    }
    try {
      var t = 0L
      val start = System.currentTimeMillis()
      val end = start + waitMs
      do { t += 16_000_000; scene.render(t); Thread.sleep(40) } while ((!ready() || System.currentTimeMillis() - start < 800) && System.currentTimeMillis() < end)
      repeat(8) { t += 16_000_000; scene.render(t); Thread.sleep(20) }
      val bytes = scene.render(t + 16_000_000).encodeToData(EncodedImageFormat.PNG)!!.bytes
      File(out, "parity-$name-${theme.name.lowercase()}.png").writeBytes(bytes)
      assertTrue(bytes.size > 8_000, "$name drew something (${bytes.size} bytes)")
    } finally { scene.close() }
  }

  private fun both(name: String, waitMs: Long = 4_000, ready: () -> Boolean = { true }, content: @Composable () -> Unit) {
    for (theme in listOf(ThemeChoice.LIGHT, ThemeChoice.DARK)) shoot(name, theme, waitMs, ready, content)
  }

  private fun controller(downloads: File = tmp()): AppController {
    val dir = tmp()
    return AppController(dir, Platform.UserFolders(File(dir, "h"), File(dir, "d"), downloads), logSink = {}, scope = scope)
  }

  private fun ffmpeg(vararg args: String, to: File): Boolean = runCatching {
    val p = ProcessBuilder(listOf("ffmpeg", "-y", "-loglevel", "error") + args + to.absolutePath).redirectErrorStream(true).start()
    p.inputStream.readBytes(); p.waitFor() == 0 && to.length() > 0
  }.getOrDefault(false)

  private fun entry(id: Long, f: File, kind: FileKind, durationMs: Long?) = IndexedFile(
    id = id, uri = f.toURI().toString(), relativePath = "Movies/${f.name}", name = f.name, kind = kind, mimeType = null,
    sizeBytes = f.length().coerceAtLeast(1), modifiedMillis = System.currentTimeMillis() - 3 * 86_400_000L, durationMs = durationMs,
  )

  // ---- B9 and A16: a video row with the frame at the matched moment, and the "at 1:23" and length pills ----
  @Test fun videoAndRecordingRows() {
    val dir = tmp()
    val v1 = File(dir, "Birthday party.mp4"); val v2 = File(dir, "Clinic walkthrough.mp4"); val a1 = File(dir, "Voice note 14.m4a")
    val made = ffmpeg("-f", "lavfi", "-i", "testsrc2=size=640x360:rate=24", "-t", "12", "-pix_fmt", "yuv420p", "-g", "24", to = v1) &&
      ffmpeg("-f", "lavfi", "-i", "smptehdbars=size=640x360:rate=24", "-t", "12", "-pix_fmt", "yuv420p", "-g", "24", to = v2)
    assertTrue(made, "ffmpeg made the two test videos (Crane has ffmpeg)")
    a1.writeBytes(ByteArray(4_000))
    val c = controller()
    val ui = MainUi()
    val rows = listOf(
      ResultRow(entry(1, v1, FileKind.VIDEO, 12_000), "she blows out the candles", MatchReason.MEANING, null, true, startMs = 7_000),
      ResultRow(entry(2, v2, FileKind.VIDEO, 12_000), null, MatchReason.WORDS, null, true, startMs = 3_000),
      ResultRow(entry(3, a1, FileKind.AUDIO, 252_000), "remember to bring the blue folder on Thursday", MatchReason.BOTH, null, true, startMs = 83_000),
      ResultRow(entry(4, File(dir, "Holiday.mp4").also { it.writeBytes(ByteArray(10)) }, FileKind.VIDEO, 754_000), null, null, null, false, startMs = null),
    )
    val ready = { rows.take(2).all { c.frames.peek(c.fileOf(it.file), FrameThumbs.momentFor(it.startMs, it.file.durationMs)) != null } }
    try {
      both("video-rows", waitMs = 20_000, ready = ready) {
        Column(Modifier.width(1100.dp).padding(Sun.S20)) {
          rows.forEachIndexed { i, r -> DocRow(r, c, ui, selected = i == 0, listFocused = false) }
        }
      }
      assertTrue(ready(), "the real decoder gave a real frame for both videos")
    } finally { c.close() }
  }

  // ---- G4: the quick panel with the "N hidden" chip ----
  @Test fun quickPanelHiddenChip() {
    fun pic(id: Long, name: String, score: Float?) = IndexedFile(
      id = id, uri = "file:///tmp/$name", relativePath = name, name = name, kind = FileKind.IMAGE, mimeType = null, sizeBytes = 1, modifiedMillis = 0, looksPrivate = score,
    )
    val files = listOf(pic(1, "IMG_2041.jpg", 1f), pic(2, "IMG_2042.jpg", 1f), pic(3, "Beach day.jpg", 0f), fakeFile(4, "Tax notes.txt"))
    val state = QuickPanelState(FakeSearch(files), scope, onClose = {}, debounceMs = 30)
    both("quick-hidden", ready = { state.hits.value.size == 4 }) {
      Box(Modifier.fillMaxSize().padding(40.dp)) { QuickPanel(state, Os.WINDOWS, "Ctrl+Space", modifier = Modifier.size(760.dp, 520.dp), blurOn = true) }
    }
  }

  // ---- F3: delete the model zip? (the state a good install leaves: installed, the green line, and the question; then the answer after Delete it) ----
  @Test fun modelZipQuestion() {
    val dl = tmp(); val zip = File(dl, "trawl-models.zip").also { it.writeBytes(ByteArray(3_000_000)) }
    val c = controller(dl)
    try {
      val asked = c.model.value.copy(installing = false, installed = "EmbeddingGemma 2 (440M)", found = null, message = t("first_models_ready"), failed = false, zipOffer = zip)
      both("model-zip") { Column(Modifier.width(900.dp).padding(Sun.S20)) { ModelsPanel(c, asked) } }
      val answered = asked.copy(zipOffer = null, message = ModelZipOffer.delete(zip).first)
      both("model-zip-deleted") { Column(Modifier.width(900.dp).padding(Sun.S20)) { ModelsPanel(c, answered) } }
    } finally { c.close() }
  }

  // ---- F4: first-run add-ons ----
  @Test fun firstRunAddOns() {
    val c = controller()
    try {
      both("first-run-addons", waitMs = 3_000, ready = { false }) { Column(Modifier.width(900.dp).padding(Sun.S20)) { FirstRunAddOns(c.addOns, c.privacy) } }
    } finally { c.close() }
  }

  // ---- A20, B15, E21: the three short notes ----
  @Test fun theNotes() {
    both("notes") {
      Column(Modifier.width(900.dp).padding(Sun.S20)) {
        RepairingNote(true)
        HeicRouteNote(photosOn = true, os = Os.LINUX, present = { false })
        Notice(ForgetNote.line(Forgot(files = 3_210, bytes = 12_400_000)), bad = false)
      }
    }
  }
}
