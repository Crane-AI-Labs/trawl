package trawl.desktop

import com.craneai.findr.core.AudioTranscript
import com.craneai.findr.core.FileKind
import com.craneai.findr.core.FormatNotReadable
import com.craneai.findr.core.SoundTags
import com.craneai.findr.core.SpeechSegment
import com.craneai.findr.engine.EnglishWords
import com.craneai.findr.engine.SpeechLanguageGate
import com.craneai.findr.index.CrawlRecord
import com.craneai.findr.index.FileState
import com.craneai.findr.index.Fp16
import com.craneai.findr.index.IndexStore
import com.craneai.findr.index.Modality
import com.craneai.findr.index.SegmentKind
import com.craneai.findr.search.VectorIndex
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CancellationException
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

private fun tmpDir(): File = Files.createTempDirectory("trawl-media").toFile().canonicalFile

/** How a video is cut into steps and pictures: the phone's numbers. */
class MediaScheduleTest {
  @Test fun aShortFileIsOneStepAndALongOneIsCutIntoMinutes() {
    assertEquals(1, MediaSchedule.windowCount(null))
    assertEquals(1, MediaSchedule.windowCount(0))
    assertEquals(1, MediaSchedule.windowCount(120_000))
    assertEquals(3, MediaSchedule.windowCount(120_001))
    assertEquals(10, MediaSchedule.windowCount(10 * 60_000L))
    assertEquals(37, MediaSchedule.windowCount(36 * 60_000L + 1))
    assertEquals(MediaSchedule.MAX_MEDIA_WINDOWS, MediaSchedule.windowCount(100L * 3_600_000))
    assertFalse(MediaSchedule.isWindowed(90_000))
    assertTrue(MediaSchedule.isWindowed(150_000))
  }

  @Test fun nineFramesInTheFirstThreeMinutesSixAfterAndOneAMinutePastThirty() {
    val long = 60L * 60_000
    assertEquals(listOf(9, 9, 9, 6, 6), (0..4).map { MediaSchedule.framesInWindow(it, long) })
    assertEquals(6, MediaSchedule.framesInWindow(29, long))
    assertEquals(1, MediaSchedule.framesInWindow(30, long))
    assertEquals(1, MediaSchedule.framesInWindow(59, long))
    assertTrue(MediaSchedule.isDeepWindow(29) && !MediaSchedule.isDeepWindow(30) && MediaSchedule.isDeepWindow(null))
  }

  @Test fun aShortLastWindowGetsItsShareOfTheFramesAndAtLeastOne() {
    // 3 minutes and 6 seconds: window 3 holds 6 seconds, a tenth of the minute
    assertEquals(1, MediaSchedule.framesInWindow(3, 186_000))
    assertEquals(9, MediaSchedule.framesInWindow(0, 186_000))
    assertEquals(3, MediaSchedule.framesInWindow(3, 3 * 60_000L + 30_000), "half a minute at six a minute")
  }

  @Test fun aWholeShortVideoGetsAFrameEveryFiveSecondsThenEveryTen() {
    assertEquals(1, MediaSchedule.framesFor(0))
    assertEquals(1, MediaSchedule.framesFor(3))
    assertEquals(6, MediaSchedule.framesFor(30))
    assertEquals(12, MediaSchedule.framesFor(60))
    assertEquals(18, MediaSchedule.framesFor(120))
    assertEquals(24, MediaSchedule.framesFor(100_000))
  }

  @Test fun shotsAreEvenlySpacedAndCoverTheStretchWithNoGap() {
    val s = MediaSchedule.shots(60_000, 60_000, 6)
    assertEquals(6, s.size)
    assertEquals(60_000, s.first().startMs)
    assertEquals(120_000, s.last().endMs)
    s.zipWithNext { a, b -> assertEquals(a.endMs, b.startMs) }
    s.forEach { assertTrue(it.midMs in it.startMs until it.endMs) }
    assertEquals(65_000, s.first().midMs)
    // a one millisecond stretch still gives a shot, and asking for none gives one
    assertEquals(1, MediaSchedule.shots(0, 1, 0).size)
  }

  @Test fun theStepAFileIsAtComesFromItsDetail() {
    assertEquals(0, MediaSchedule.nextWindow(null))
    assertEquals(0, MediaSchedule.nextWindow("9 frames"))
    assertEquals(7, MediaSchedule.nextWindow("9 frames, next=7 skipped=2"))
    assertTrue(MediaSchedule.hasNext("x next=3"))
    assertFalse(MediaSchedule.hasNext("done"))
  }

  private fun solid(c: Color) = BufferedImage(90, 60, BufferedImage.TYPE_INT_RGB).also { i -> i.createGraphics().apply { color = c; fillRect(0, 0, 90, 60); dispose() } }
  private fun ramp(rising: Boolean) = BufferedImage(90, 60, BufferedImage.TYPE_INT_RGB).also { i ->
    for (x in 0 until 90) for (y in 0 until 60) { val v = if (rising) x * 255 / 89 else 255 - x * 255 / 89; i.setRGB(x, y, (v shl 16) or (v shl 8) or v) }
  }

  @Test fun theSameLookingFramesShareAHashAndDifferentOnesDoNot() {
    val a = MediaSchedule.differenceHash(ramp(true))
    val b = MediaSchedule.differenceHash(ramp(true))
    val c = MediaSchedule.differenceHash(ramp(false))
    assertEquals(a, b)
    assertTrue(MediaSchedule.similar(a, b))
    assertFalse(MediaSchedule.similar(a, c), "a rising and a falling ramp are different pictures")
    // two flat colours are both "no edges": the same look, as on the phone (a held slide)
    assertTrue(MediaSchedule.similar(MediaSchedule.differenceHash(solid(Color.RED)), MediaSchedule.differenceHash(solid(Color.BLUE))))
  }
}

/** Sound handling that needs no model: the cap on a speech segment, bytes into samples, junk filtering. */
class SoundRulesTest {
  @Test fun aSegmentWithinTheCapIsLeftWhole() {
    assertEquals(listOf(0 until 100), SpeechSplit.ranges(FloatArray(100), 100, 200))
  }

  @Test fun aLongSegmentIsCutIntoPiecesThatCoverItAndNeverExceedTheCap() {
    val rate = 16_000
    val n = 47 * rate
    val ranges = SpeechSplit.ranges(FloatArray(n) { 0.5f }, n, 15 * rate)
    assertEquals(0, ranges.first().first)
    assertEquals(n - 1, ranges.last().last)
    ranges.zipWithNext { a, b -> assertEquals(a.last + 1, b.first, "no gap and no overlap") }
    ranges.forEach { assertTrue(it.last + 1 - it.first <= 15 * rate, "piece of ${it.last + 1 - it.first}") }
    assertTrue(ranges.last().last + 1 - ranges.last().first >= rate, "the last piece is worth recognising")
  }

  @Test fun theCutLandsOnTheQuietestStretchNearTheEnd() {
    val rate = 16_000
    val n = 25 * rate
    val s = FloatArray(n) { 0.6f }
    val quiet = 13 * rate   // one second of silence, 2 s before the 15 s cap
    for (i in quiet until quiet + rate) s[i] = 0f
    val first = SpeechSplit.ranges(s, n, 15 * rate).first()
    assertTrue(first.last + 1 in quiet..(quiet + rate), "cut at ${first.last + 1}")
  }

  @Test fun bytesBecomeSamplesEvenWhenAPieceEndsInTheMiddleOfOne() {
    // 1000, -1000 and 32767 as little-endian 16-bit, delivered in pieces of 1, 3 and 2 bytes
    val bytes = byteArrayOf(0xE8.toByte(), 0x03, 0x18, 0xFC.toByte(), 0xFF.toByte(), 0x7F)
    val a = PcmAssembler()
    val out = ArrayList<Float>()
    var at = 0
    for (len in listOf(1, 3, 2)) { out += a.add(bytes.copyOfRange(at, at + len), len).toList(); at += len }
    assertEquals(3, out.size)
    assertEquals(1000 / 32768f, out[0])
    assertEquals(-1000 / 32768f, out[1])
    assertEquals(32767 / 32768f, out[2])
    assertEquals(0, PcmAssembler().add(ByteArray(0), 0).size)
  }

  @Test fun loopingAndImpossiblyFastTextIsDroppedAndRepeatsAreTrimmed() {
    assertEquals("", DesktopAsr.clean("", 3.0))
    assertEquals("hello world", DesktopAsr.clean("  hello   world ", 2.0))
    assertEquals("the the the the", DesktopAsr.clean("the the the the the the the the", 20.0))
    assertEquals("", DesktopAsr.clean("one two three four five six seven eight nine ten", 1.0), "ten words in a second is not speech")
  }
}

/** What ffprobe says, and the helper protocol both native helpers speak. */
class DecoderProtocolTest {
  @Test fun ffprobeJsonGivesLengthAndWhichStreamsExist() {
    val i = FfmpegDecoder.parseProbe("""{"streams":[{"codec_type":"video","disposition":{"attached_pic":0}},{"codec_type":"audio"}],"format":{"duration":"61.5"}}""")
    assertTrue(i.hasVideo && i.hasAudio)
    assertEquals(61_500L, i.durationMs)
  }

  @Test fun aCoverPictureIsNotAVideoAndAMissingLengthIsNull() {
    val i = FfmpegDecoder.parseProbe("""{"streams":[{"codec_type":"video","disposition":{"attached_pic":1}},{"codec_type":"audio"}],"format":{"duration":"N/A"}}""")
    assertFalse(i.hasVideo)
    assertTrue(i.hasAudio)
    assertNull(i.durationMs)
  }

  @Test fun noStreamsOrNoJsonIsAFileThatCannotBeOpened() {
    assertFailsWith<FormatNotReadable> { FfmpegDecoder.parseProbe("""{"streams":[{"codec_type":"data"}],"format":{}}""") }
    assertFailsWith<FormatNotReadable> { FfmpegDecoder.parseProbe("""{"format":{}}""") }
    assertFailsWith<FormatNotReadable> { FfmpegDecoder.parseProbe("garbage") }
  }

  @Test fun theLengthPrefixedChunksAreReadUntilTheEmptyOne() {
    val raw = ByteArrayOutputStream().also { o ->
      val d = java.io.DataOutputStream(o)
      d.writeInt(3); d.write(byteArrayOf(1, 2, 3)); d.writeInt(2); d.write(byteArrayOf(4, 5)); d.writeInt(0); d.write(byteArrayOf(9))
    }.toByteArray()
    val got = ByteArrayOutputStream()
    val din = java.io.DataInputStream(raw.inputStream())
    HelperDecoder.chunks(din) { b, n -> got.write(b, 0, n) }
    assertContentEquals(byteArrayOf(1, 2, 3, 4, 5), got.toByteArray())
    assertEquals(9, din.read(), "the empty chunk ends the answer and nothing after it is eaten")
  }

  @Test fun aHeaderLineIsReadWithoutSwallowingTheBinaryAfterIt() {
    val raw = "{\"ok\":true}\r\n".toByteArray() + byteArrayOf(7, 8)
    val s = raw.inputStream()
    assertEquals("{\"ok\":true}", HelperDecoder.readLine(s))
    assertEquals(7, s.read())
  }

  /** A stand-in for media-apple / media-windows.ps1 written in sh: answers probe, frame and audio by the protocol. */
  private fun fakeHelper(dir: File): List<String> {
    val f = File(dir, "helper.sh")
    f.writeText(
      """
      while IFS="${'\t'}" read cmd path a b; do
        case "${'$'}cmd" in
          probe) if [ "${'$'}{path##*/}" = "bad" ]; then printf '{"ok":false,"error":"not a media file","chunked":false}\n'; else printf '{"ok":true,"hasVideo":true,"hasAudio":false,"durationMs":4200,"chunked":false}\n'; fi ;;
          frame) printf '{"ok":true,"chunked":true}\n\000\000\000\003abc\000\000\000\000' ;;
          audio) printf '{"ok":true,"chunked":true}\n\000\000\000\004\001\000\002\000\000\000\000\002xy\000\000\000\000' ;;
        esac
      done
      """.trimIndent() + "\n",
    )
    return listOf("sh", f.absolutePath)
  }

  @Test fun aHelperThatSpeaksTheProtocolAnswersProbeFrameAndAudio() {
    assumeTrue(Platform.os != Os.WINDOWS)
    val dir = tmpDir()
    HelperDecoder(fakeHelper(dir), "fake").use { d ->
      val info = d.probe(File("/x/y.mp4"))
      assertTrue(info.hasVideo)
      assertFalse(info.hasAudio)
      assertEquals(4200L, info.durationMs)
      assertContentEquals("abc".toByteArray(), d.frame(File("/x/y.mp4"), 1000, 336))
      val pcm = ByteArrayOutputStream()
      d.audio(File("/x/y.mp4"), 0, 15_000) { b, n -> pcm.write(b, 0, n) }
      assertContentEquals(byteArrayOf(1, 0, 2, 0, 'x'.code.toByte(), 'y'.code.toByte()), pcm.toByteArray())
      // a second probe on the same running helper still works
      assertEquals(4200L, d.probe(File("/x/z.mp4")).durationMs)
    }
    dir.deleteRecursively()
  }

  @Test fun aFileTheHelperRefusesBecomesFormatNotReadableAndTheHelperGoesOn() {
    assumeTrue(Platform.os != Os.WINDOWS)
    val dir = tmpDir()
    HelperDecoder(fakeHelper(dir), "fake").use { d ->
      assertFailsWith<FormatNotReadable> { d.probe(File("bad")) }
      assertEquals(4200L, d.probe(File("/x/ok.mp4")).durationMs)
    }
    dir.deleteRecursively()
  }

  @Test fun aHelperThatCannotStartIsAFileThatCannotBeOpened() {
    val d = HelperDecoder(listOf("/no/such/helper-binary"), "ghost")
    assertFailsWith<FormatNotReadable> { d.probe(File("/x/y.mp4")) }
  }

  @Test fun theDecoderKindFollowsTheOperatingSystem() {
    assertEquals("avfoundation", MediaDecoders.kind(Os.MAC) { false })
    assertEquals("media-foundation", MediaDecoders.kind(Os.WINDOWS) { false })
    assertEquals("ffmpeg", MediaDecoders.kind(Os.LINUX) { true })
    assertEquals("none", MediaDecoders.kind(Os.LINUX) { false })
  }

  @Test fun secondsAreWrittenWithADotWhateverTheLanguage() {
    assertEquals("12.345", FfmpegDecoder.seconds(12_345))
    assertEquals("0.000", FfmpegDecoder.seconds(-5))
  }
}

/** The user's real ffmpeg, when there is one: makes a 4 second clip, then probes it and takes a picture and the sound. */
class FfmpegDecoderTest {
  private fun run(vararg cmd: String) {
    val p = ProcessBuilder(*cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
    check(p.waitFor() == 0) { "${cmd.first()} failed" }
  }

  @Test fun probeFrameAndAudioOfARealClip() {
    assumeTrue(MediaDecoders.ffmpegPresent(), "ffmpeg is not installed here")
    val dir = tmpDir()
    val clip = File(dir, "clip.mp4")
    run("ffmpeg", "-v", "error", "-y", "-f", "lavfi", "-i", "testsrc=duration=4:size=640x360:rate=25", "-f", "lavfi", "-i", "sine=frequency=440:duration=4",
      "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac", "-shortest", clip.absolutePath)
    FfmpegDecoder().use { d ->
      val info = d.probe(clip)
      assertTrue(info.hasVideo && info.hasAudio)
      assertTrue(info.durationMs!! in 3_800L..4_300L, "duration ${info.durationMs}")
      val jpeg = assertNotNull(d.frame(clip, 2_000, 336))
      val img = ImageIO.read(jpeg.inputStream())
      assertEquals(336, img.width, "long side fits the box")
      assertTrue(img.height in 180..190)
      val pcm = ByteArrayOutputStream()
      d.audio(clip, 1_000, 2_000) { b, n -> pcm.write(b, 0, n) }
      assertTrue(pcm.size() in 62_000..66_000, "2 s of 16 kHz mono 16-bit is 64000 bytes, got ${pcm.size()}")
    }
    dir.deleteRecursively()
  }

  @Test fun aFileThatIsNotMediaIsRefused() {
    assumeTrue(MediaDecoders.ffmpegPresent(), "ffmpeg is not installed here")
    val dir = tmpDir()
    val junk = File(dir, "junk.mp4").also { it.writeBytes(ByteArray(3000) { i -> (i * 31).toByte() }) }
    FfmpegDecoder().use { d -> assertFailsWith<FormatNotReadable> { d.probe(junk) } }
    dir.deleteRecursively()
  }

  @Test fun noFfmpegMeansADecoderThatSaysSoRatherThanCrashing() {
    val d = FfmpegDecoder(ffmpeg = "/no/such/ffmpeg", ffprobe = "/no/such/ffprobe")
    assertFailsWith<FormatNotReadable> { d.probe(File("/x/y.mp4")) }
    assertFailsWith<FormatNotReadable> { d.frame(File("/x/y.mp4"), 0) }
    assertFailsWith<FormatNotReadable> { d.audio(File("/x/y.mp4"), 0, 1000) { _, _ -> } }
  }
}

private class FakeDecoder(
  val info: MediaInfo,
  val failFrames: Boolean = false,
  /** Which picture each requested time gets: 0 flat, 1 rising ramp, 2 falling ramp, 3 stripes. */
  val look: (Long) -> Int = { 1 },
) : MediaDecoder {
  override val name = "fake"
  val frameTimes = ArrayList<Long>()
  override fun probe(file: File) = info
  override fun frame(file: File, timeMs: Long, maxSide: Int): ByteArray? {
    if (failFrames) throw FormatNotReadable("cannot decode")
    synchronized(frameTimes) { frameTimes += timeMs }
    return jpeg(look(timeMs))
  }
  override fun audio(file: File, startMs: Long, spanMs: Long, sink: (ByteArray, Int) -> Unit) {}
  override fun close() {}

  companion object {
    fun jpeg(look: Int): ByteArray {
      val img = BufferedImage(96, 64, BufferedImage.TYPE_INT_RGB)
      for (x in 0 until 96) for (y in 0 until 64) {
        val v = when (look) { 0 -> 128; 1 -> x * 255 / 95; 2 -> 255 - x * 255 / 95; else -> if ((x / 6) % 2 == 0) 250 else 5 }
        img.setRGB(x, y, (v shl 16) or (v shl 8) or v)
      }
      return ByteArrayOutputStream().also { ImageIO.write(img, "jpg", it) }.toByteArray()
    }
  }
}

private class FakeEmbedder(override val vision: Boolean = true) : MediaEmbedder {
  val images = java.util.concurrent.atomic.AtomicInteger()
  val documents = java.util.Collections.synchronizedList(ArrayList<String>())
  private fun unit() = FloatArray(Fp16.DIM).also { it[0] = 1f }
  override fun image(jpeg: ByteArray): FloatArray { images.incrementAndGet(); return unit() }
  override fun document(text: String): FloatArray { documents += text; return unit() }
}

private class FakeTranscriber(val said: AudioTranscript, val on: Boolean = true) : Transcriber {
  val calls = ArrayList<Pair<Long, Long>>()
  override fun available() = on
  override fun transcribe(decoder: MediaDecoder, file: File, startMs: Long, spanMs: Long, stop: () -> Boolean): AudioTranscript {
    calls += startMs to spanMs
    return AudioTranscript(said.speech.map { SpeechSegment(startMs + it.startMs, startMs + it.endMs, it.text) }, said.sounds, said.seconds)
  }
}

private val ENGLISH = "bring budget the quarterly meeting is moved to thursday afternoon please invoices and a dog barks".split(" ")
private fun gate() = SpeechLanguageGate(lazyOf(null), lazyOf(EnglishWords((ENGLISH.sorted().distinct().joinToString("\n") + "\n").toByteArray())))

private fun english() = AudioTranscript(
  listOf(SpeechSegment(1_000, 5_000, "the quarterly budget meeting is moved to thursday afternoon please bring the invoices")),
  listOf(SoundTags(0, 10_000, listOf("Dog", "Speech"))), 10.0,
)
private fun luganda() = AudioTranscript(
  listOf(SpeechSegment(1_000, 5_000, "ekyokulya kirungi nnyo abantu bonna basanyuse leero ssebo")),
  listOf(SoundTags(0, 10_000, listOf("Applause"))), 10.0,
)

private fun fileOf(kind: FileKind, durationMs: Long? = null) = com.craneai.findr.core.IndexedFile(
  id = 1, uri = "file:///tmp/x", relativePath = "", name = "x.mp4", kind = kind, mimeType = null, sizeBytes = 1, modifiedMillis = 1, durationMs = durationMs,
)

/** One step of one file, with the pictures, the speech and the language gate faked. */
class MediaProcessorTest {
  private fun processor(d: MediaDecoder, e: FakeEmbedder, t: Transcriber?, dir: File, stop: () -> Boolean = { false }) =
    MediaProcessor(d, e, t, gate(), { null }, dir, stop)

  @Test fun aShortVideoGetsItsFramesItsSpeechAndItsSoundLabels() {
    val dir = tmpDir(); val e = FakeEmbedder()
    val d = FakeDecoder(MediaInfo(10_000, hasVideo = true, hasAudio = true)) { t -> if (t < 5_000) 1 else 2 }
    val tr = FakeTranscriber(english())
    val step = processor(d, e, tr, dir).step(fileOf(FileKind.VIDEO, 10_000), File("/x"), MediaInfo(10_000, true, true), null, 1)
    assertNull(step.next)
    val frames = step.segments.filter { it.kind == SegmentKind.FRAME }
    assertEquals(2, frames.size, "two different looks, the rest are repeats: ${step.detail}")
    assertEquals(2, e.images.get())
    assertTrue(frames.all { it.vector != null && it.locStart != null && it.locEnd!! > it.locStart!! })
    assertEquals(0L, frames.first().locStart)
    val speech = step.segments.filter { it.kind == SegmentKind.TRANSCRIPT }
    assertTrue(speech.isNotEmpty() && speech.joinToString(" ") { it.text!! }.contains("quarterly budget"))
    assertTrue(speech.all { it.locStart != null }, "each spoken piece knows where it is")
    assertTrue(step.segments.any { it.kind == SegmentKind.SOUND && it.text!!.contains("Dog") })
    dir.deleteRecursively()
  }

  @Test fun onTheSaveDataPackAVideoIsReadByItsCaptionsAndSoundNotItsLooks() {
    val dir = tmpDir(); val e = FakeEmbedder(vision = false)
    val d = FakeDecoder(MediaInfo(10_000, hasVideo = true, hasAudio = true)) { t -> if (t < 5_000) 1 else 2 }
    val caption = object : Ocr { override val name = "fake"; override fun read(image: File) = "Buy more airtime today and win a prize"; override fun close() {} }
    val p = MediaProcessor(d, e, FakeTranscriber(english()), gate(), { caption }, dir)
    val step = p.step(fileOf(FileKind.VIDEO, 10_000), File("/x"), MediaInfo(10_000, true, true), null, 1)
    assertEquals(0, e.images.get(), "the text-only model never sees a picture")
    assertTrue(step.segments.none { it.kind == SegmentKind.FRAME })
    val words = step.segments.filter { it.kind == SegmentKind.OCR }
    assertTrue(words.isNotEmpty() && words.all { it.modality == Modality.OCR_TEXT && it.vector != null }, "the captions get text vectors")
    assertTrue(words.first().text!!.contains("airtime"), "and the caption words stay in the keyword index")
    assertTrue(step.segments.any { it.kind == SegmentKind.TRANSCRIPT }, "speech is the same on both packs")
    dir.deleteRecursively()
  }

  @Test fun aVideoOfOnePictureKeepsOneFrameForTheWholeVideo() {
    val dir = tmpDir(); val e = FakeEmbedder()
    val d = FakeDecoder(MediaInfo(60_000, true, false)) { 0 }
    val step = processor(d, e, null, dir).step(fileOf(FileKind.VIDEO, 60_000), File("/x"), MediaInfo(60_000, true, false), null, 1)
    val frames = step.segments.filter { it.kind == SegmentKind.FRAME }
    assertEquals(1, frames.size)
    assertEquals(12, d.frameTimes.size, "all twelve were taken")
    assertEquals(60_000L, frames.single().locEnd, "the one frame stands for the whole video")
    dir.deleteRecursively()
  }

  @Test fun aLongVideoIsReadAWindowAtATimeWithTheScheduledFrameCounts() {
    val dir = tmpDir(); val e = FakeEmbedder()
    val total = 40L * 60_000
    val info = MediaInfo(total, true, true)
    val windows = MediaSchedule.windowCount(total)
    assertEquals(40, windows)
    for ((w, frames) in listOf(0 to 9, 2 to 9, 3 to 6, 29 to 6, 30 to 1, 39 to 1)) {
      val d = FakeDecoder(info) { t -> ((t / 700) % 4).toInt() }
      val step = processor(d, e, FakeTranscriber(english()), dir).step(fileOf(FileKind.VIDEO, total), File("/x"), info, w, windows)
      assertEquals(frames, d.frameTimes.size, "window $w")
      assertTrue(d.frameTimes.all { it in w * 60_000L until (w + 1) * 60_000L }, "window $w frames lie inside it")
      assertEquals(if (w + 1 < windows) w + 1 else null, step.next)
      val late = step.segments.all { it.late == (w > 0) }
      assertTrue(late, "pieces of later windows are marked late")
    }
    dir.deleteRecursively()
  }

  @Test fun speechInAnotherLanguageKeepsOnlyTheSoundLabels() {
    val dir = tmpDir(); val e = FakeEmbedder()
    val info = MediaInfo(10_000, false, true)
    val step = processor(FakeDecoder(info), e, FakeTranscriber(luganda()), dir).step(fileOf(FileKind.AUDIO, 10_000), File("/x"), info, null, 1)
    assertEquals(emptyList(), step.segments.filter { it.kind == SegmentKind.TRANSCRIPT })
    assertEquals(1, step.segments.count { it.kind == SegmentKind.SOUND && it.text!!.contains("Applause") })
    assertTrue(step.detail!!.contains("not English"), step.detail)
    assertFalse(e.documents.any { it.contains("ekyokulya") }, "the junk is not even embedded")
    dir.deleteRecursively()
  }

  @Test fun aRecordingWithoutTheSpeechPackIsNotedSoItIsReadAgainWhenThePackArrives() {
    val dir = tmpDir()
    val info = MediaInfo(10_000, false, true)
    val step = processor(FakeDecoder(info), FakeEmbedder(), FakeTranscriber(english(), on = false), dir).step(fileOf(FileKind.AUDIO, 10_000), File("/x"), info, null, 1)
    assertEquals(emptyList(), step.segments)
    assertTrue(step.detail!!.contains(IndexStore.SPEECH_MISSING), step.detail)
    dir.deleteRecursively()
  }

  @Test fun aRecordingWithNoSoundIsAFileThatCannotBeOpened() {
    val dir = tmpDir()
    val info = MediaInfo(10_000, true, false)
    assertFailsWith<FormatNotReadable> {
      processor(FakeDecoder(info), FakeEmbedder(), FakeTranscriber(english()), dir).step(fileOf(FileKind.AUDIO, 10_000), File("/x"), info, null, 1)
    }
    dir.deleteRecursively()
  }

  @Test fun aDecoderThatCannotMakeAnyFrameFailsTheVideoButNotOneThatOnlyLostTheSound() {
    val dir = tmpDir()
    val info = MediaInfo(10_000, true, false)
    assertFailsWith<FormatNotReadable> {
      processor(FakeDecoder(info, failFrames = true), FakeEmbedder(), null, dir).step(fileOf(FileKind.VIDEO, 10_000), File("/x"), info, null, 1)
    }
    dir.deleteRecursively()
  }

  @Test fun stoppingBetweenFramesCancelsTheStepAndKeepsNothing() {
    val dir = tmpDir()
    val info = MediaInfo(60_000, true, false)
    var asked = 0
    assertFailsWith<CancellationException> {
      processor(FakeDecoder(info), FakeEmbedder(), null, dir, stop = { ++asked > 3 }).step(fileOf(FileKind.VIDEO, 60_000), File("/x"), info, null, 1)
    }
    dir.deleteRecursively()
  }
}

/** The window loop over a real index on disk. */
class MediaIndexerTest {
  private class Rig {
    val dir = tmpDir()
    val media = File(dir, "media").also { it.mkdirs() }
    val store = IndexStore.open(File(dir, "index"))
    val vectors = VectorIndex()
    val embedder = FakeEmbedder()
    fun add(name: String, kind: FileKind): com.craneai.findr.index.PendingFile {
      val f = File(media, name).also { it.writeBytes(ByteArray(64) { i -> i.toByte() }) }
      store.upsertFromCrawl(listOf(CrawlRecord(Crawler.VOLUME, Crawler.idOf(f.toPath()), f.toPath().toUri().toString(), "", name, kind, null, f.length(), f.lastModified())))
      return store.nextPending(10, setOf(kind), includePartial = true).first { it.file.name == name }
    }
    fun again(id: Long, kind: FileKind) = store.nextPending(10, setOf(kind), includePartial = true).first { it.file.id == id }
    fun indexer(d: MediaDecoder, t: Transcriber?, timeoutMs: Long = 60_000, log: (String) -> Unit = {}) =
      MediaIndexer(store, vectors, d, MediaProcessor(d, embedder, t, gate(), { null }, File(dir, "tmp")), log, timeoutMs)
    fun close() { store.close(); dir.deleteRecursively() }
  }

  @Test fun aShortVideoIsReadInOneStepAndBecomesSearchable() {
    val r = Rig()
    val p = r.add("short.mp4", FileKind.VIDEO)
    val d = FakeDecoder(MediaInfo(20_000, true, true)) { t -> if (t < 10_000) 1 else 2 }
    r.indexer(d, FakeTranscriber(english())).read(p) { true }
    val (state, detail) = r.store.stateOf(p.file.id)!!
    assertEquals(FileState.UNDERSTOOD, state, detail)
    assertTrue(r.vectors.size >= 3, "two frames and the speech have vectors: ${r.vectors.size}")
    r.close()
  }

  @Test fun aLongVideoIsPartialBetweenWindowsAndAStopLeavesItWhereItWas() {
    val r = Rig()
    val p = r.add("long.mp4", FileKind.VIDEO)
    val d = FakeDecoder(MediaInfo(4 * 60_000L, true, true)) { t -> ((t / 5_000) % 4).toInt() }
    var steps = 0
    r.indexer(d, FakeTranscriber(english())).read(p) { ++steps < 2 }   // go on after the first window, stop after the second
    val (state, detail) = r.store.stateOf(p.file.id)!!
    assertEquals(FileState.PARTIAL, state)
    assertTrue(detail!!.contains("next=2"), detail)
    assertEquals(2, steps)
    // a restart goes on from the first window not saved, and finishes
    val again = r.again(p.file.id, FileKind.VIDEO)
    assertEquals(2, MediaSchedule.nextWindow(again.detail))
    val d2 = FakeDecoder(MediaInfo(4 * 60_000L, true, true)) { t -> ((t / 5_000) % 4).toInt() }
    r.indexer(d2, FakeTranscriber(english())).read(again) { true }
    assertEquals(FileState.UNDERSTOOD, r.store.stateOf(p.file.id)!!.first)
    assertTrue(d2.frameTimes.all { it >= 2 * 60_000L }, "windows 0 and 1 were not read again: ${d2.frameTimes.minOrNull()}")
    r.close()
  }

  @Test fun theLengthTheFileDidNotKnowIsLearnedFromTheDecoder() {
    val r = Rig()
    val p = r.add("len.mp4", FileKind.VIDEO)
    assertNull(p.file.durationMs)
    r.indexer(FakeDecoder(MediaInfo(30_000, true, false)), null).read(p) { true }
    val seen = r.store.nextPending(10, setOf(FileKind.VIDEO), includePartial = true)
    assertTrue(seen.none { it.file.id == p.file.id }, "it is done, so it is not pending")
    r.close()
  }

  @Test fun aFileTheComputerCannotOpenIsFailedWithTheCantOpenNoteAndTheLogNamesNoFile() {
    val r = Rig()
    val p = r.add("secret-holiday-name.mp4", FileKind.VIDEO)
    val bad = object : MediaDecoder by FakeDecoder(MediaInfo(1, true, true)) {
      override fun probe(file: File): MediaInfo = throw FormatNotReadable("could not open /home/me/secret-holiday-name.mp4")
    }
    val lines = ArrayList<String>()
    r.indexer(bad, null, log = { lines += it }).read(p) { true }
    val (state, detail) = r.store.stateOf(p.file.id)!!
    assertEquals(FileState.FAILED, state)
    assertEquals(IndexStore.CANT_OPEN, detail)
    assertTrue(lines.isNotEmpty() && lines.none { it.contains("secret") || it.contains("/home") }, lines.toString())
    r.close()
  }

  @Test fun aStepThatNeverEndsFailsTheFileInsteadOfBlockingTheQueue() {
    val r = Rig()
    val p = r.add("hang.mp4", FileKind.VIDEO)
    val hang = object : MediaDecoder by FakeDecoder(MediaInfo(10_000, true, false)) {
      override fun frame(file: File, timeMs: Long, maxSide: Int): ByteArray? { Thread.sleep(60_000); return null }
    }
    r.indexer(hang, null, timeoutMs = 300).read(p) { true }
    val (state, detail) = r.store.stateOf(p.file.id)!!
    assertEquals(FileState.FAILED, state)
    assertEquals("took too long", detail)
    r.close()
  }

  @Test fun aRecordingWithoutThePackIsLeftToBeReadAgainLater() {
    val r = Rig()
    val p = r.add("note.m4a", FileKind.AUDIO)
    r.indexer(FakeDecoder(MediaInfo(8_000, false, true)), FakeTranscriber(english(), on = false)).read(p) { true }
    val (state, detail) = r.store.stateOf(p.file.id)!!
    assertEquals(FileState.UNDERSTOOD, state)
    assertTrue(detail!!.contains(IndexStore.SPEECH_MISSING), detail)
    assertEquals(1, r.store.retryMissingSpeech(), "the pack arrives: the file is queued again")
    assertTrue(r.store.nextPending(10, setOf(FileKind.AUDIO), includePartial = true).any { it.file.id == p.file.id })
    r.close()
  }
}
