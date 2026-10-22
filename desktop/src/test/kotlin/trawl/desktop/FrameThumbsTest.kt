package trawl.desktop

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import com.craneai.findr.core.FileKind
import com.craneai.findr.core.IndexedFile
import com.craneai.findr.ui.common.formatDuration
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import trawl.desktop.ui.MediaPills
import trawl.desktop.ui.VideoFrameTile

private fun jpeg(): ByteArray = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(32, 18, BufferedImage.TYPE_INT_RGB), "jpg", it) }.toByteArray()

private class FrameFake(var bytes: ByteArray? = jpeg()) : MediaDecoder {
  val asked = java.util.Collections.synchronizedList(ArrayList<Long>())
  @Volatile var closed = false
  override val name = "fake"
  override fun probe(file: File) = MediaInfo(60_000, true, true)
  override fun frame(file: File, timeMs: Long, maxSide: Int): ByteArray? { asked += timeMs; return bytes }
  override fun audio(file: File, startMs: Long, spanMs: Long, sink: (ByteArray, Int) -> Unit) = Unit
  override fun close() { closed = true }
}

private fun video(sensitiveFlags: Int = 0, kind: FileKind = FileKind.VIDEO, durationMs: Long? = 60_000) = IndexedFile(
  id = 7, uri = "file:///tmp/clip.mp4", relativePath = "", name = "clip.mp4", kind = kind, mimeType = null, sizeBytes = 1, modifiedMillis = 1,
  durationMs = durationMs, sensitiveFlags = sensitiveFlags,
)

/** B9: the frame at the hit moment on a video row, from the same decoder call the indexer uses. */
@OptIn(ExperimentalTestApi::class)
class FrameThumbsTest {
  private fun clip(): File = Files.createTempFile("trawl-clip", ".mp4").toFile().apply { deleteOnExit(); writeText("x") }

  @Test fun theFrameIsAskedAtTheHitMomentAndKeptForTheNextTime() {
    val d = FrameFake(); val frames = FrameThumbs({ d }); val f = clip()
    val first = frames.load(f, 83_400)
    assertNotNull(first)
    assertEquals(listOf(83_000L), d.asked.toList(), "the moment, to the half second the keyframe is no nearer than")
    assertSame(first, frames.peek(f, 83_100), "a nearby moment is the same picture, with no decoding")
    assertSame(first, frames.load(f, 83_400))
    assertEquals(1, d.asked.size)
    frames.close(); assertTrue(d.closed)
  }

  @Test fun aVideoThatCannotBeOpenedIsNotAskedAgainUntilTheRetryTime() {
    var now = 1_000L
    val d = FrameFake(bytes = null); val frames = FrameThumbs({ d }, { now }); val f = clip()
    assertNull(frames.load(f, 0)); assertNull(frames.load(f, 0))
    assertEquals(1, d.asked.size, "the second look did not call the decoder")
    assertTrue(frames.recentlyFailed(f, 0))
    now += FrameThumbs.RETRY_MS + 1
    d.bytes = jpeg()
    assertNotNull(frames.load(f, 0), "after the wait it tries again, and now it works")
  }

  @Test fun aComputerWithNoDecoderGivesNoFrameAndNoCrash() {
    val frames = FrameThumbs({ null }); assertNull(frames.load(clip(), 0))
  }

  @Test fun theMomentIsTheHitOrAtenthOfTheWayInCappedAtTenSeconds() {
    assertEquals(83_000L, FrameThumbs.momentFor(83_000, 600_000))
    assertEquals(6_000L, FrameThumbs.momentFor(null, 60_000))
    assertEquals(10_000L, FrameThumbs.momentFor(null, 3_600_000))
    assertEquals(0L, FrameThumbs.momentFor(null, null))
  }

  @Test fun onlyAnOrdinaryVisibleVideoShowsAFrame() {
    assertTrue(FrameThumbs.mayShow(video(), hidden = false))
    assertFalse(FrameThumbs.mayShow(video(), hidden = true), "a row the blur hides keeps its icon")
    assertFalse(FrameThumbs.mayShow(video(sensitiveFlags = 1), hidden = false), "a file marked private is never decoded for the list")
    assertFalse(FrameThumbs.mayShow(video(sensitiveFlags = 2), hidden = false), "nor one in a private folder")
    assertFalse(FrameThumbs.mayShow(video(kind = FileKind.IMAGE), hidden = false))
  }

  @Test fun aSensitiveVideoRowNeverCallsTheDecoder() = runComposeUiTest {
    val d = FrameFake(); val frames = FrameThumbs({ d })
    val f = clip()
    setContent { VideoFrameTile(ResultRow(video(sensitiveFlags = 1), null, null, null, true, 5_000), f, frames, hidden = false) }
    waitForIdle(); Thread.sleep(200); waitForIdle()
    assertTrue(d.asked.isEmpty())
  }

  @Test fun anOrdinaryVideoRowAsksForTheFrameAtItsMoment() = runComposeUiTest {
    val d = FrameFake(); val frames = FrameThumbs({ d }); val f = clip()
    setContent { VideoFrameTile(ResultRow(video(), null, null, null, true, 12_000), f, frames, hidden = false) }
    val until = System.currentTimeMillis() + 5_000
    while (d.asked.isEmpty() && System.currentTimeMillis() < until) { Thread.sleep(20); waitForIdle() }
    assertEquals(listOf(12_000L), d.asked.toList())
  }
}

/** A16: what a video or recording row says about time. */
class MediaPillsTest {
  @Test fun aVideoHitShowsTheMomentStrongAndThenTheLength() {
    val p = MediaPills.of(FileKind.VIDEO, 83_000, 252_000)
    assertEquals(listOf(t("badge_at_time", formatDuration(83_000)), formatDuration(252_000)), p.map { it.text })
    assertEquals(listOf(true, false), p.map { it.strong })
  }

  @Test fun aRecordingWithNoHitMomentStillShowsItsLength() {
    assertEquals(listOf(formatDuration(95_000)), MediaPills.of(FileKind.AUDIO, null, 95_000).map { it.text })
  }

  @Test fun anUnknownOrZeroLengthAddsNoPill() {
    assertTrue(MediaPills.of(FileKind.VIDEO, null, null).isEmpty())
    assertTrue(MediaPills.of(FileKind.AUDIO, null, 0).isEmpty())
  }

  @Test fun otherKindsKeepTheirOldMomentBadgeAndGetNoLength() {
    assertEquals(1, MediaPills.of(FileKind.PDF, 5_000, 99_000).size)
    assertTrue(MediaPills.of(FileKind.IMAGE, null, 99_000).isEmpty())
  }
}
