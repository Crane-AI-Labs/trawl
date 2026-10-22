package com.craneai.findr.extract

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.SystemClock
import com.craneai.findr.core.FindrLog as Log
import com.craneai.findr.core.AudioDecoder
import com.craneai.findr.core.FormatNotReadable
import com.craneai.findr.core.AudioWindow
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

private const val TAG = "FindrAudio"

/**
 * Decodes the first audio track of a media file (audio or video) into 16 kHz mono 16-bit WAV windows for the audio
 * encoder.
 *
 * Streaming end to end: `MediaCodec` output goes straight through a mono downmix, the windowed-sinc [Resampler] and a
 * [WindowCutter], so memory is the codec buffers plus the windows kept (about 1 MB per 30 s window), never the decoded
 * file. Decoding stops as soon as `maxWindows` are cut. No audio track gives an empty list. If the codec fails
 * part-way, the windows already cut are returned (a bad tail shouldn't lose the first minutes).
 */
class MediaAudioDecoder(
  private val priority: Int? = null,        // MediaFormat.KEY_PRIORITY hint (0 realtime, 1 best effort); bench experiments
  private val operatingRate: Int? = null,   // MediaFormat.KEY_OPERATING_RATE hint; bench experiments
) : AudioDecoder {

  override suspend fun windows(context: Context, uri: Uri, windowMs: Long, hopMs: Long, maxWindows: Int, startMs: Long): List<AudioWindow> {
    val out = ArrayList<AudioWindow>()
    decode(context, uri, windowMs, hopMs, maxWindows, startMs) { out.add(it) }
    return out
  }

  override suspend fun stream(context: Context, uri: Uri, windowMs: Long, hopMs: Long, maxWindows: Int, startMs: Long, onWindow: (AudioWindow) -> Unit) {
    decode(context, uri, windowMs, hopMs, maxWindows, startMs, onWindow)
  }

  /** Returns how many windows were cut. */
  private suspend fun decode(
    context: Context, uri: Uri, windowMs: Long, hopMs: Long, maxWindows: Int, startMs: Long, onWindow: (AudioWindow) -> Unit,
  ): Int = withContext(Dispatchers.IO) {
    val started = SystemClock.elapsedRealtime()
    val ctx = currentCoroutineContext()
    var cut = 0
    val extractor = MediaExtractor()
    var codec: MediaCodec? = null
    try {
      extractor.setDataSource(context, uri, null)
      val track = (0 until extractor.trackCount).firstOrNull {
        extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
      } ?: return@withContext cut
      extractor.selectTrack(track)
      // A later window of a long file: jump there. Nothing before it is read or decoded (and no temporary copy of the file is made).
      if (startMs > 0) extractor.seekTo(startMs * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
      val format = extractor.getTrackFormat(track)
      val mime = format.getString(MediaFormat.KEY_MIME) ?: return@withContext cut

      val windowSamples = (windowMs * OUT_RATE / 1000).toInt().coerceAtLeast(1)
      val hopSamples = (hopMs * OUT_RATE / 1000).toInt().coerceAtLeast(1)
      val cutter = WindowCutter(windowSamples, hopSamples, maxWindows.coerceAtLeast(1), OUT_RATE) { start, samples ->
        onWindow(AudioWindow(startMs + start * 1000 / OUT_RATE, startMs + (start + samples.size) * 1000 / OUT_RATE, Wav.encode(samples, OUT_RATE)))
        cut++
      }

      codec = MediaCodec.createDecoderByType(mime)
      priority?.let { format.setInteger(MediaFormat.KEY_PRIORITY, it) }
      operatingRate?.let { format.setInteger(MediaFormat.KEY_OPERATING_RATE, it) }
      codec.configure(format, null, null, 0)
      codec.start()

      var rate = format.intOr(MediaFormat.KEY_SAMPLE_RATE, 44_100)
      var channels = format.intOr(MediaFormat.KEY_CHANNEL_COUNT, 2)
      var floatPcm = false
      var resampler: Resampler? = null
      var resamplerRate = 0
      var mono = FloatArray(0)

      var consumeNs = 0L   // our own downmix + resample + windowing, apart from the codec's time
      fun consume(out: ByteBuffer) {
        val t0 = System.nanoTime()
        out.order(ByteOrder.nativeOrder())
        val ch = channels.coerceAtLeast(1)
        val frames: Int
        val shorts = if (floatPcm) null else out.asShortBuffer()
        val floats = if (floatPcm) out.asFloatBuffer() else null
        frames = (if (floatPcm) floats!!.remaining() else shorts!!.remaining()) / ch
        if (mono.size < frames) mono = FloatArray(frames)
        for (f in 0 until frames) {
          var sum = 0f
          for (c in 0 until ch) sum += if (floatPcm) floats!!.get() * 32768f else shorts!!.get().toFloat()
          mono[f] = sum / ch
        }
        val converted: ShortArray = if (rate == OUT_RATE) {
          ShortArray(frames) { mono[it].toInt().coerceIn(-32768, 32767).toShort() }
        } else {
          if (resampler == null || resamplerRate != rate) {
            resampler = Resampler(rate, OUT_RATE)
            resamplerRate = rate
          }
          resampler!!.process(mono, frames)
        }
        cutter.push(converted)
        consumeNs += System.nanoTime() - t0
      }

      val info = MediaCodec.BufferInfo()
      var inputDone = false
      var outputDone = false
      var idle = 0
      // Feed every free input buffer and drain every ready output without waiting; block (briefly) only when a round
      // moved nothing. Waiting 10 ms for output after each 23 ms AAC frame made decoding cost ~0.16 s per audio second
      // on the A06, three quarters of the speech stage (the research notes).
      while (!outputDone && !cutter.full) {
        ctx.ensureActive()
        var moved = false
        while (!inputDone) {
          val i = codec.dequeueInputBuffer(0)
          if (i < 0) break
          val n = extractor.readSampleData(codec.getInputBuffer(i)!!, 0)
          if (n < 0) {
            codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            inputDone = true
          } else {
            codec.queueInputBuffer(i, 0, n, extractor.sampleTime, 0)
            extractor.advance()
          }
          moved = true
        }
        while (!outputDone && !cutter.full) {
          val o = codec.dequeueOutputBuffer(info, if (moved) 0 else TIMEOUT_US)
          when {
            o >= 0 -> {
              idle = 0
              moved = true
              if (info.size > 0) {
                val out = codec.getOutputBuffer(o)!!
                out.position(info.offset)
                out.limit(info.offset + info.size)
                consume(out)
              }
              codec.releaseOutputBuffer(o, false)
              if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
            }
            o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
              val f = codec.outputFormat
              rate = f.intOr(MediaFormat.KEY_SAMPLE_RATE, rate)
              channels = f.intOr(MediaFormat.KEY_CHANNEL_COUNT, channels)
              floatPcm = f.intOr(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT) == AudioFormat.ENCODING_PCM_FLOAT
            }
            else -> {   // INFO_TRY_AGAIN_LATER: nothing ready now
              if (!moved && inputDone && ++idle > MAX_IDLE) {
                Log.w(TAG, "decoder stalled after the last input; keeping $cut windows")
                outputDone = true
              }
              break
            }
          }
        }
      }
      if (outputDone) resampler?.finish()?.let { cutter.push(it) }
      cutter.finish()
      Log.i(TAG, "decoded $cut windows from $mime ($rate Hz, $channels ch) with ${codec?.name} in " +   // log-safe: the codec's name
        "${SystemClock.elapsedRealtime() - started} ms (ours ${consumeNs / 1_000_000} ms)")
      cut
    } catch (e: CancellationException) {
      throw e
    } catch (t: Throwable) {
      Log.w(TAG, "audio not fully decoded (${Log.brief(t)}); keeping $cut windows")
      com.craneai.findr.diag.Diag.handled(com.craneai.findr.diag.HandledKind.DECODER, t)
      // Nothing decoded at all (no codec for this format, or the file would not open): the caller is told, rather than getting an empty sound.
      if (cut == 0) throw FormatNotReadable("audio not opened: ${Log.brief(t)}", t)
      cut
    } finally {
      try {
        codec?.stop()
      } catch (_: Exception) {
      }
      try {
        codec?.release()
      } catch (_: Exception) {
      }
      try {
        extractor.release()
      } catch (_: Exception) {
      }
    }
  }

  private fun MediaFormat.intOr(key: String, default: Int): Int =
    if (containsKey(key)) getInteger(key) else default

  private companion object {
    const val OUT_RATE = 16_000
    const val TIMEOUT_US = 10_000L
    const val MAX_IDLE = 500   // ~5 s of nothing after end of input
  }
}
