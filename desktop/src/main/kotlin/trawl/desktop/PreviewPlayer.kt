package trawl.desktop

import java.io.File
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the preview's play button is doing. */
sealed interface PlayState {
  data object Idle : PlayState

  /** The decoder is opening the file and the first sound has not arrived. */
  data object Loading : PlayState

  /** Sound is coming out of the speakers. [fromMs] is where in the file it started; [elapsedMs] how much has played. */
  data class Playing(val fromMs: Long, val elapsedMs: Long) : PlayState

  /** The span finished or the person stopped it: back to a play button. */
  data object Done : PlayState

  /** This computer could not play it (no decoder, no sound output, a file that will not open). Said once, quietly. */
  data object Failed : PlayState
}

/** Where decoded sound goes. The real one is Java Sound; tests give a fake. */
interface PcmOut {
  fun write(bytes: ByteArray, length: Int)

  /** Milliseconds of sound that have actually played. */
  fun playedMs(): Long
  fun finish()
  fun close()
}

/** 16 kHz mono signed 16-bit little-endian PCM, which is what [MediaDecoder.audio] hands out, played through the system's default output. */
class JavaSoundOut private constructor(private val line: SourceDataLine) : PcmOut {
  override fun write(bytes: ByteArray, length: Int) { line.write(bytes, 0, length) }
  override fun playedMs(): Long = line.microsecondPosition / 1000
  override fun finish() { line.drain() }
  override fun close() { runCatching { line.stop() }; runCatching { line.close() } }

  companion object {
    val FORMAT = AudioFormat(16000f, 16, 1, true, false)

    /** Null when this computer has no output for that format (a server with no sound card, a machine whose output is busy). */
    fun open(): PcmOut? = runCatching {
      val l = AudioSystem.getSourceDataLine(FORMAT)
      l.open(FORMAT, 16000 * 2)   // half a second of buffer: stopping is prompt
      l.start()
      JavaSoundOut(l)
    }.getOrNull()
  }
}

/**
 * Plays a stretch of an audio or video file from a moment, for the preview: "play from 1:23". Cheap by construction: it asks the decoder
 * the computer already has for the same 16 kHz sound the indexer reads ([MediaDecoder.audio]) and plays [spanMs] of it, so there is no
 * media framework to ship and no second code path to keep right. It is a clip, not a player: no seeking, no volume (the system's own
 * volume applies), and a video is heard, not seen (its frame is shown beside the button). The full file is for the app Enter opens.
 *
 * One clip at a time: starting another, or [stop], ends the first. Each clip uses its own decoder process, closed when it ends, so
 * stopping can never leave a helper behind. Nothing is cached or written.
 */
class PreviewPlayer(
  private val decoder: () -> MediaDecoder?,
  private val output: () -> PcmOut? = JavaSoundOut::open,
  private val spanMs: Long = DEFAULT_SPAN_MS,
) {
  private val _state = MutableStateFlow<PlayState>(PlayState.Idle)
  val state: StateFlow<PlayState> = _state.asStateFlow()

  @Volatile private var run: Run? = null

  private class Run(val file: File) {
    @Volatile var stopped = false
    @Volatile var dec: MediaDecoder? = null
    @Volatile var out: PcmOut? = null
    @Volatile var thread: Thread? = null
  }

  private class Stopped : RuntimeException(null, null, false, false)

  /** Starts [file] at [fromMs]. Returns at once; [state] reports progress. */
  @Synchronized fun play(file: File, fromMs: Long) {
    stop()
    val r = Run(file)
    run = r
    _state.value = PlayState.Loading
    val t = Thread({ clip(r, fromMs.coerceAtLeast(0)) }, "trawl-preview-play").apply { isDaemon = true }
    r.thread = t
    t.start()
  }

  /** Ends whatever is playing. Safe to call at any time, from any thread. */
  @Synchronized fun stop() {
    val r = run ?: return
    r.stopped = true
    run = null
    runCatching { r.out?.close() }
    runCatching { r.dec?.close() }
    r.thread?.interrupt()
    if (_state.value is PlayState.Loading || _state.value is PlayState.Playing) _state.value = PlayState.Idle
  }

  private fun clip(r: Run, fromMs: Long) {
    var failed = false
    try {
      val d = decoder()
      r.dec = d
      val o = output()
      r.out = o
      if (d == null || o == null) { failed = true; return }
      if (r.stopped) return
      var first = true
      d.audio(r.file, fromMs, spanMs) { bytes, n ->
        if (r.stopped) throw Stopped()
        if (first) { first = false; _state.value = PlayState.Playing(fromMs, 0) }
        o.write(bytes, n)
        if (!r.stopped) _state.value = PlayState.Playing(fromMs, o.playedMs())
      }
      if (!r.stopped) { if (first) failed = true else o.finish() }
    } catch (_: Stopped) {
    } catch (_: Throwable) {
      if (!r.stopped) failed = true
    } finally {
      runCatching { r.out?.close() }
      runCatching { r.dec?.close() }
      // Only the clip that is still current reports its end; a stopped one has already been replaced or reset.
      synchronized(this) {
        if (run === r) {
          run = null
          _state.value = if (failed) PlayState.Failed else PlayState.Done
        }
      }
    }
  }

  companion object {
    /** Long enough to hear the passage the search matched, short enough that a wrong click costs nothing. */
    const val DEFAULT_SPAN_MS = 45_000L
  }
}
