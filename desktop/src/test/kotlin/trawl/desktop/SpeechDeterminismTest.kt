package trawl.desktop

import com.craneai.findr.core.AudioTranscript
import com.craneai.findr.core.FixedBlocks
import com.craneai.findr.engine.SpeechLanguageGate
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import org.json.JSONArray
import org.json.JSONObject
import org.junit.jupiter.api.Assumptions.assumeTrue

class SpeechPcmFeedTest {
  private fun feed(bytes: ByteArray, sizes: List<Int>): List<FloatArray> {
    val assembler = PcmAssembler()
    val blocks = FixedBlocks(8192)
    val output = ArrayList<FloatArray>()
    var offset = 0
    var arrival = 0
    while (offset < bytes.size) {
      val length = minOf(sizes[arrival++ % sizes.size], bytes.size - offset)
      blocks.add(assembler.add(bytes.copyOfRange(offset, offset + length), length)) { output += it }
      offset += length
    }
    blocks.finish { output += it }
    return output
  }

  @Test fun oddPipeReadsProduceTheSameDetectorCallsAndSamples() {
    val bytes = ByteArray(100_006) { index -> (index * 31).toByte() }
    val expected = feed(bytes, listOf(16_384))
    for (sizes in listOf(listOf(1), listOf(3, 3199, 8193, 65_535), listOf(65_536, 1, 7))) {
      val actual = feed(bytes, sizes)
      assertEquals(expected.size, actual.size)
      for (index in expected.indices) assertContentEquals(expected[index], actual[index])
    }
  }

  @Test fun aFinalIncompleteSampleIsNotInvented() {
    val complete = byteArrayOf(0, 1, 0, 2)
    val expected = feed(complete, listOf(4))
    val actual = feed(complete + byteArrayOf(127), listOf(1, 2))
    assertContentEquals(expected.single(), actual.single())
  }

  @Test fun fileEndFlushesTheRemainderExactlyOnce() {
    val blocks = FixedBlocks(8192)
    val output = ArrayList<FloatArray>()
    blocks.add(FloatArray(8193) { index -> index.toFloat() }) { output += it }
    blocks.finish { output += it }
    blocks.finish { output += it }
    blocks.add(floatArrayOf(-1f)) { output += it }
    blocks.finish { output += it }
    assertEquals(listOf(8192, 1, 1), output.map { it.size })
    assertContentEquals(floatArrayOf(8192f), output[1])
    assertContentEquals(floatArrayOf(-1f), output[2])
  }
}

class SpeechDeterminismTest {
  private val packPath = System.getenv("TRAWL_SPEECH_TEST_PACK")
  private val clipsPath = System.getenv("TRAWL_SPEECH_TEST_CLIPS")
  private val gate = SpeechLanguageGate.fromAssets(ClasspathAssets)

  private fun fixtures(): Pair<File, File> {
    assumeTrue(packPath != null && clipsPath != null, "set TRAWL_SPEECH_TEST_PACK and TRAWL_SPEECH_TEST_CLIPS for the native speech regression")
    val pack = File(packPath!!)
    val clips = File(clipsPath!!)
    check(DesktopAsr.PARAKEET_FILES.all { File(pack, "${DesktopAsr.PARAKEET_DIR}/$it").isFile })
    check(File(pack, DesktopAsr.VAD_FILE).isFile)
    check(File(pack, "${DesktopAsr.CED_DIR}/model.int8.onnx").isFile)
    check(File(pack, "${DesktopAsr.CED_DIR}/class_labels_indices.csv").isFile)
    check(listOf("en_birthday", "en_fatherson").all { File(clips, "$it.wav").isFile })
    check(gate.common.value != null)
    return pack to clips
  }

  private class ChunkedDecoder(private val pcm: Map<String, ByteArray>, private val seed: Int?) : MediaDecoder {
    override val name = "fixed-pcm-regression"
    override fun probe(file: File) = MediaInfo(pcm.getValue(file.name).size / 32L, false, true)
    override fun frame(file: File, timeMs: Long, maxSide: Int): ByteArray? = null
    override fun close() = Unit
    override fun audio(file: File, startMs: Long, spanMs: Long, sink: (ByteArray, Int) -> Unit) {
      val bytes = pcm.getValue(file.name)
      var offset = minOf(bytes.size.toLong(), startMs * 32).toInt()
      val end = minOf(bytes.size.toLong(), (startMs + spanMs) * 32).toInt()
      val random = seed?.let { Random(it) }
      while (offset < end) {
        val wanted = if (random == null) 16_384 else listOf(3199, 6400, 8193, 16_000, 32_768, 65_535)[random.nextInt(6)]
        val length = minOf(wanted, end - offset)
        sink(bytes.copyOfRange(offset, offset + length), length)
        offset += length
      }
    }
  }

  private fun pcm(clips: File): Map<String, ByteArray> {
    val ffmpeg = System.getenv("TRAWL_SPEECH_FFMPEG") ?: "ffmpeg"
    return FfmpegDecoder(ffmpeg = ffmpeg).use { decoder ->
      listOf("en_birthday.wav", "en_fatherson.wav").associateWith { name ->
        val bytes = ByteArrayOutputStream()
        decoder.audio(File(clips, name), 0, 3_600_000) { piece, length -> bytes.write(piece, 0, length) }
        bytes.toByteArray()
      }
    }
  }

  private fun signature(transcript: AudioTranscript): String {
    val kept = gate.keep(transcript)
    return JSONObject().put("speech", JSONArray(transcript.speech.map { segment ->
      JSONObject().put("from", segment.startMs).put("to", segment.endMs).put("text", segment.text).put("sung", segment.sung)
        .put("words", JSONArray(segment.words.map { word -> JSONObject().put("from", word.startMs).put("to", word.endMs).put("text", word.text) }))
    })).put("sounds", JSONArray(transcript.sounds.map { tags -> JSONObject().put("from", tags.startMs).put("to", tags.endMs).put("labels", JSONArray(tags.labels)) }))
      .put("share", kept.verdict.share).put("english", kept.verdict.english).put("partial", kept.verdict.partial)
      .put("kept", JSONArray(kept.speech.map { it.text })).put("seconds", transcript.seconds).toString()
  }

  @Test fun freshEnginesIgnoreDecoderArrivalSizes() {
    val (pack, clips) = fixtures()
    val samples = pcm(clips)
    for (name in samples.keys) {
      val file = File(clips, name)
      val expected = DesktopAsr(pack).use { signature(it.transcribe(ChunkedDecoder(samples, null), file, 0, 3_600_000) { false }) }
      for (seed in 0 until 5) DesktopAsr(pack).use {
        assertEquals(expected, signature(it.transcribe(ChunkedDecoder(samples, seed), file, 0, 3_600_000) { false }), "$name, arrival seed $seed")
      }
    }
  }

  @Test fun reusedEngineResetsAtFileAndWindowBoundaries() {
    val (pack, clips) = fixtures()
    val samples = pcm(clips)
    val birthday = File(clips, "en_birthday.wav")
    val father = File(clips, "en_fatherson.wav")
    val expected = DesktopAsr(pack).use { signature(it.transcribe(ChunkedDecoder(samples, null), birthday, 0, 3_600_000) { false }) }
    DesktopAsr(pack).use { engine ->
      engine.transcribe(ChunkedDecoder(samples, 0), father, 0, 3_600_000) { false }
      assertEquals(expected, signature(engine.transcribe(ChunkedDecoder(samples, 1), birthday, 0, 3_600_000) { false }))
      engine.transcribe(ChunkedDecoder(samples, 2), birthday, 5000, 15_000) { false }
      assertEquals(expected, signature(engine.transcribe(ChunkedDecoder(samples, 3), birthday, 0, 3_600_000) { false }))
    }
  }
}
