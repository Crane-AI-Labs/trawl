package com.craneai.findr.ui.search

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color as AColor
import android.graphics.Paint
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.craneai.findr.core.FileKind
import com.craneai.findr.core.IndexedFile
import com.craneai.findr.core.Locator
import com.craneai.findr.core.MatchReason
import com.craneai.findr.core.SearchHit
import com.craneai.findr.core.VideoStrip
import com.craneai.findr.ui.common.LocalPreviewBlur
import com.craneai.findr.ui.common.PreviewBlur
import com.craneai.findr.ui.common.PrivacyBlurTransformation
import com.craneai.findr.ui.common.VideoStripLoader
import com.craneai.findr.ui.theme.FindrTheme
import com.craneai.findr.ui.theme.ThemeMode
import com.craneai.findr.ui.theme.sunContentWidth
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The preview strip, drawn by the real [FileRow], [MediaCell] and [MomentPeekContent] with the real Sunlight theme and the real strings
 * (Robolectric, native graphics), on a phone and a tablet, light and dark, blurred and in Luganda. The pictures are seeded into
 * [VideoStripLoader]'s cache, which is exactly what a strip that is back on screen draws from; the decoder itself is measured on a
 * phone (scripts/strip_qa.py), not here.
 *
 * Writes PNGs to `$STRIP_SHOTS_DIR` (default app/build/strip-shots). `$STRIP_FRAMES_DIR` may hold `a_0..4.jpg` (a landscape video's
 * five frames) and `b_0..4.jpg` (a portrait one's); without it the frames are plain gradients, so the checks still run anywhere.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w360dp-h720dp-xxhdpi")
class MomentStripShotsTest {
  @get:Rule val rule = createComposeRule()

  @After fun emptyTheCache() = VideoStripLoader.clear()

  private val now = System.currentTimeMillis()

  private fun video(id: Long, name: String, looksPrivate: Float? = null) = IndexedFile(
    id = id, uri = "fake://video$id", relativePath = "Movies/$name", name = name, kind = FileKind.VIDEO, mimeType = "video/mp4",
    sizeBytes = 31_000_000, modifiedMillis = now - 3 * 86_400_000L, width = 1080, height = 1920, durationMs = 94_000, looksPrivate = looksPrivate,
  )

  private val staff = video(1, "staff_meeting.mp4")
  private val staffHit = SearchHit(staff, 1.0, MatchReason.WORDS, snippet = "…we will review the «budget» for next term before…", locator = Locator.Time(41_000, 44_000))
  private val voice = IndexedFile(
    id = 2, uri = "fake://voice2", relativePath = "Recordings/voice_note_0412.m4a", name = "voice_note_0412.m4a", kind = FileKind.AUDIO,
    mimeType = "audio/mp4", sizeBytes = 400_000, modifiedMillis = now - 86_400_000L, durationMs = 62_000,
  )
  private val voiceHit = SearchHit(voice, 0.9, MatchReason.WORDS, snippet = "…the «budget» is ready, call me when you can…", locator = Locator.Time(17_000, 20_000))
  private val pdf = IndexedFile(
    id = 3, uri = "fake://doc3", relativePath = "Documents/school_fees_term2.pdf", name = "school_fees_term2.pdf", kind = FileKind.PDF,
    mimeType = "application/pdf", sizeBytes = 120_000, modifiedMillis = now - 5 * 86_400_000L, pageCount = 3,
  )
  private val pdfHit = SearchHit(pdf, 0.8, MatchReason.WORDS, snippet = "Term 2 «budget» and fees for each class…", locator = Locator.Page(2))
  private val tile = video(4, "goal_celebration.mp4")
  private val tileHit = SearchHit(tile, 0.7, MatchReason.MEANING, locator = Locator.Time(23_000, null))

  private fun plan(momentMs: Long, durationMs: Long = 94_000) =
    VideoStrip.plan(momentMs, durationMs, VideoStrip.KeyframeList((0L..durationMs step 1_000).toList()))

  /** The five frames of a video: real pictures when `$STRIP_FRAMES_DIR` has them, else gradients. */
  private fun frames(set: String, blurred: Boolean): List<Bitmap> = (0 until 5).map { i ->
    val dir = System.getenv("STRIP_FRAMES_DIR")
    val file = dir?.let { File(it, "${set}_$i.jpg") }
    val bitmap = if (file != null && file.exists()) BitmapFactory.decodeFile(file.path) else gradient(i, set == "b")
    if (blurred) PrivacyBlurTransformation.blurred(bitmap) else bitmap
  }

  private fun gradient(i: Int, portrait: Boolean): Bitmap {
    val (w, h) = if (portrait) 144 to 256 else 256 to 144
    val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = Canvas(b)
    c.drawColor(AColor.rgb(60 + 30 * i, 110, 190 - 25 * i))
    c.drawCircle(w * (0.2f + 0.15f * i), h * 0.5f, h * 0.22f, Paint().apply { color = AColor.WHITE })
    return b
  }

  private fun seed(file: IndexedFile, momentMs: Long, set: String, hidden: Boolean = false) {
    val plan = plan(momentMs)
    VideoStripLoader.seed(file, momentMs, hidden, plan, frames(set, hidden))
  }

  private fun save(name: String) {
    val dir = File(System.getenv("STRIP_SHOTS_DIR") ?: "build/strip-shots").also { it.mkdirs() }
    File(dir, "$name.png").outputStream().use { rule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
  }

  private fun show(dark: Boolean, blur: PreviewBlur = PreviewBlur.Off, wide: Boolean = false, content: @Composable () -> Unit) {
    rule.setContent {
      FindrTheme(mode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT) {
        CompositionLocalProvider(LocalPreviewBlur provides blur) {
          Column(Modifier.background(MaterialTheme.colorScheme.background).then(if (wide) Modifier.sunContentWidth() else Modifier.fillMaxWidth())) { content() }
        }
      }
    }
    rule.waitForIdle()
  }

  /** What a results screen holds around a video's strip: the video row, a voice note with a time, a document, and a row of tiles. */
  @Composable private fun results(opened: MutableList<Long> = mutableListOf(), peeked: MutableList<Long> = mutableListOf()) {
    FileRow(staffHit, onOpen = {}, onShare = {}, onCopy = {}, onToggle = {}, onOpenAt = { opened += it })
    FileRow(voiceHit, onOpen = {}, onShare = {}, onCopy = {}, onToggle = {}, onOpenAt = { opened += it })
    FileRow(pdfHit, onOpen = {}, onShare = {}, onCopy = {}, onToggle = {}, onOpenAt = { opened += it })
    Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      MediaCell(tileHit, onOpen = {}, onShare = {}, onToggle = {}, modifier = Modifier.width(104.dp), onPeek = { peeked += 23_000 })
    }
  }

  @Test fun phoneLight() {
    seed(staff, 41_000, "a"); seed(tile, 23_000, "b")
    show(dark = false) { results() }
    save("phone-light")
  }

  @Test fun phoneDark() {
    seed(staff, 41_000, "a"); seed(tile, 23_000, "b")
    show(dark = true) { results() }
    save("phone-dark")
  }

  @Config(qualifiers = "w840dp-h900dp-xhdpi")
  @Test fun tabletLight() {
    seed(staff, 41_000, "a"); seed(tile, 23_000, "b")
    show(dark = false, wide = true) { results() }
    save("tablet-light")
  }

  @Config(qualifiers = "w840dp-h900dp-xhdpi")
  @Test fun tabletDark() {
    seed(staff, 41_000, "a"); seed(tile, 23_000, "b")
    show(dark = true, wide = true) { results() }
    save("tablet-dark")
  }

  @Test fun privateLookingVideoIsBlurredAndATapShowsItInsteadOfOpening() {
    val secret = staff.copy(looksPrivate = 0.97f)
    seed(secret, 41_000, "a", hidden = true)
    val opened = mutableListOf<Long>()
    val hit = staffHit.copy(file = secret)
    show(dark = false, blur = PreviewBlur(enabled = true)) {
      FileRow(hit, onOpen = {}, onShare = {}, onCopy = {}, onToggle = {}, onOpenAt = { opened += it })
    }
    save("phone-private-blur")
    // A blurred strip's tap is "show", not "open the video".
    rule.onAllNodesWithContentDescription("Open the video at 0:41, the matched moment", substring = true).assertCountEquals(1)
    rule.onNodeWithContentDescription("Open the video at 0:41, the matched moment", substring = true).performClick()
    assertTrue("a blurred picture opened the video: $opened", opened.isEmpty())
  }

  @Config(qualifiers = "lg-w360dp-h720dp-xxhdpi")
  @Test fun inLuganda() {
    seed(staff, 41_000, "a"); seed(tile, 23_000, "b")
    show(dark = false) { results() }
    save("phone-luganda")
  }

  @Test fun theGridTilesTimeLabelOpensThePeekSheetContent() {
    seed(tile, 23_000, "b")
    show(dark = false) {
      MomentPeekContent(tile, 23_000, hidden = false, frames = rememberStripFrames(tile, 23_000, hidden = false, enabled = true, debounce = false), onOpenAt = {}, onReveal = {})
    }
    save("phone-peek-sheet")
  }

  @Test fun tappingAPictureOpensTheVideoAtThePictureTime() {
    seed(staff, 41_000, "a")
    val opened = mutableListOf<Long>()
    show(dark = false) { FileRow(staffHit, onOpen = {}, onShare = {}, onCopy = {}, onToggle = {}, onOpenAt = { opened += it }) }
    val strip = plan(41_000)
    assertEquals("five pictures for a camera-like video", 5, strip.slots.size)
    // The strip is 37 s .. 45 s; the third picture is the matched moment.
    rule.onNodeWithContentDescription("Open the video at 0:37", substring = true).performClick()
    rule.onNodeWithContentDescription("Open the video at 0:45", substring = true).performClick()
    assertEquals(listOf(37_000L, 45_000L), opened)
  }

  @Test fun aVoiceNoteShowsItsWordsAndTimeButNoStrip() {
    seed(voice, 17_000, "a")
    show(dark = false) { FileRow(voiceHit, onOpen = {}, onShare = {}, onCopy = {}, onToggle = {}, onOpenAt = {}) }
    rule.onAllNodesWithContentDescription("Pictures from the video", substring = true).assertCountEquals(0)
  }

  @Test fun aCompactRowAndAChosenRowHaveNoStrip() {
    seed(staff, 41_000, "a")
    show(dark = false) {
      FileRow(staffHit, onOpen = {}, onShare = {}, onCopy = {}, onToggle = {}, compact = true, onOpenAt = {})
      FileRow(staffHit, onOpen = {}, onShare = {}, onCopy = {}, onToggle = {}, selecting = true, onOpenAt = {})
    }
    rule.onAllNodesWithContentDescription("Pictures from the video", substring = true).assertCountEquals(0)
  }
}
