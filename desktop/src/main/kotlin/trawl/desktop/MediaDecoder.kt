package trawl.desktop

import com.craneai.findr.core.FormatNotReadable
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.InputStream
import java.util.Locale
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/** What a decoder found out about a file without reading it all. [durationMs] null: it does not say. */
class MediaInfo(val durationMs: Long?, val hasVideo: Boolean, val hasAudio: Boolean)

/**
 * The computer's own way of opening a video or a recording. A desktop JVM has no decoders (a phone's Android gives them away), so the
 * work is done by a program already on the computer, never one we ship:
 *  - macOS: AVFoundation, through a small Swift helper compiled into the app ([HelperDecoder], native/media-apple.swift);
 *  - Windows: Media Foundation, through a PowerShell helper ([HelperDecoder], native/media-windows.ps1);
 *  - Linux: the ffmpeg the person has installed ([FfmpegDecoder]).
 * Every call is one file; a decoder that cannot open it throws [FormatNotReadable], so the file is marked "can't be opened" and the queue moves on.
 */
interface MediaDecoder : AutoCloseable {
  /** For the problem report and Settings: "avfoundation", "media-foundation" or "ffmpeg". */
  val name: String

  fun probe(file: File): MediaInfo

  /** The picture nearest [timeMs] (a keyframe, not the exact frame, as on the phone), upright, long side at most [maxSide], as a JPEG. Null when there is none. */
  fun frame(file: File, timeMs: Long, maxSide: Int = MediaSchedule.EMBED_MAX_SIDE): ByteArray?

  /** Sound from [startMs] for at most [spanMs] as 16 kHz mono 16-bit little-endian PCM, handed to [sink] as it arrives. */
  fun audio(file: File, startMs: Long, spanMs: Long, sink: (bytes: ByteArray, length: Int) -> Unit)
}

/** The user's ffmpeg and ffprobe, run as separate programs (nothing of ffmpeg is in Trawl, so its licence is the person's own install's). */
class FfmpegDecoder(private val ffmpeg: String = "ffmpeg", private val ffprobe: String = "ffprobe", private val timeoutS: Long = 300) : MediaDecoder {
  override val name = "ffmpeg"

  override fun probe(file: File): MediaInfo {
    val out = run(
      listOf(ffprobe, "-v", "error", "-print_format", "json", "-show_entries",
        "format=duration:stream=codec_type,disposition", file.absolutePath), timeoutS = 60,
    )
    return parseProbe(out.toString(Charsets.UTF_8))
  }

  override fun frame(file: File, timeMs: Long, maxSide: Int): ByteArray? {
    val scale = "scale='if(gt(iw,ih),min($maxSide,iw),-2)':'if(gt(iw,ih),-2,min($maxSide,ih))',format=yuvj420p"
    val bytes = run(
      listOf(ffmpeg, "-nostdin", "-v", "error", "-noaccurate_seek", "-ss", seconds(timeMs), "-i", file.absolutePath, "-an", "-sn",
        "-frames:v", "1", "-vf", scale, "-q:v", "2", "-f", "image2pipe", "-c:v", "mjpeg", "-"), timeoutS = 120,
    )
    return bytes.takeIf { it.isNotEmpty() }
  }

  override fun audio(file: File, startMs: Long, spanMs: Long, sink: (ByteArray, Int) -> Unit) {
    val cmd = listOf(ffmpeg, "-nostdin", "-v", "error", "-ss", seconds(startMs), "-t", seconds(spanMs), "-i", file.absolutePath,
      "-vn", "-sn", "-ac", "1", "-ar", "16000", "-f", "s16le", "-")
    val p = try { ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.DISCARD).start() } catch (e: java.io.IOException) { throw FormatNotReadable("ffmpeg is not installed", e) }
    var timedOut = false
    val watchdog = Thread { try { Thread.sleep(timeoutS * 1000); timedOut = true; p.destroyForcibly() } catch (_: InterruptedException) { } }.also { it.isDaemon = true; it.start() }
    try {
      val buf = ByteArray(1 shl 16)
      p.inputStream.use { input -> while (true) { val n = input.read(buf); if (n < 0) break; if (n > 0) sink(buf, n) } }
      p.waitFor(5, TimeUnit.SECONDS)
      if (timedOut) throw FormatNotReadable("ffmpeg did not finish in $timeoutS s")
    } finally { watchdog.interrupt(); p.destroy() }
  }

  private fun run(cmd: List<String>, timeoutS: Long): ByteArray {
    val p = try { ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.DISCARD).start() } catch (e: java.io.IOException) { throw FormatNotReadable("${cmd.first()} is not installed", e) }
    // A decoder stuck on a damaged file is killed when the time is up; that ends its output, so the read below returns.
    var timedOut = false
    val watchdog = Thread { try { Thread.sleep(timeoutS * 1000); timedOut = true; p.destroyForcibly() } catch (_: InterruptedException) { } }.also { it.isDaemon = true; it.start() }
    try {
      p.outputStream.close()
      val out = p.inputStream.readBytes()
      p.waitFor(5, TimeUnit.SECONDS)
      if (timedOut) throw FormatNotReadable("${File(cmd.first()).name} did not finish in $timeoutS s")
      return out
    } finally { watchdog.interrupt(); p.destroy() }
  }

  companion object {
    fun seconds(ms: Long): String = String.format(Locale.ROOT, "%.3f", ms.coerceAtLeast(0) / 1000.0)

    /** ffprobe's JSON: the container's duration (absent or "N/A" for some streams), and which kinds of stream it holds (a cover picture is not video). */
    fun parseProbe(json: String): MediaInfo {
      val o = try { JSONObject(json) } catch (e: Exception) { throw FormatNotReadable("not a media file", e) }
      val streams = o.optJSONArray("streams") ?: throw FormatNotReadable("no streams")
      var video = false; var audio = false
      for (i in 0 until streams.length()) {
        val s = streams.getJSONObject(i)
        val cover = s.optJSONObject("disposition")?.optInt("attached_pic", 0) == 1
        when (s.optString("codec_type")) {
          "video" -> if (!cover) video = true
          "audio" -> audio = true
        }
      }
      val d = o.optJSONObject("format")?.optString("duration", "")?.toDoubleOrNull()?.takeIf { it > 0 && it.isFinite() }
      if (!video && !audio) throw FormatNotReadable("no picture or sound in it")
      return MediaInfo(d?.let { Math.round(it * 1000) }, video, audio)
    }
  }

  override fun close() {}
}

/**
 * A helper program that stays running and answers one request per line on its standard input (the way the OCR helpers do, so the
 * cost of starting it is paid once). Requests, tab-separated: `probe PATH`, `frame PATH MS MAXSIDE`, `audio PATH STARTMS SPANMS`.
 * Every answer is a JSON line; a picture or a stretch of sound follows it as length-prefixed chunks (4 bytes, big-endian, then that
 * many bytes; a length of 0 ends it). Written the same way by native/media-apple.swift and native/media-windows.ps1, so one reader
 * serves both and the helper can be tried by hand: `echo -e "probe\t/some/file.mp4" | media-apple`.
 */
class HelperDecoder(private val command: List<String>, override val name: String, private val timeoutS: Long = 300) : MediaDecoder {
  private var proc: Process? = null
  private var input: DataInputStream? = null

  @Synchronized private fun ensure(): Pair<Process, DataInputStream> {
    proc?.takeIf { it.isAlive }?.let { return it to input!! }
    val p = ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start()
    proc = p; input = DataInputStream(BufferedInputStream(p.inputStream, 1 shl 16))
    return p to input!!
  }

  @Synchronized private fun <T> call(request: String, readBody: (JSONObject, DataInputStream) -> T): T {
    val (p, din) = try { ensure() } catch (e: java.io.IOException) { throw FormatNotReadable("$name helper did not start", e) }
    // A helper that stops answering is killed (and started again for the next file), not waited for.
    val watchdog = Thread {
      try { Thread.sleep(timeoutS * 1000); p.destroyForcibly() } catch (_: InterruptedException) { }
    }.also { it.isDaemon = true; it.start() }
    try {
      p.outputStream.write((request + "\n").toByteArray(Charsets.UTF_8)); p.outputStream.flush()
      val header = JSONObject(readLine(din) ?: throw FormatNotReadable("$name helper ended"))
      if (!header.optBoolean("ok", false)) { drainChunks(header, din); throw FormatNotReadable(header.optString("error", "$name could not open it")) }
      return readBody(header, din)
    } catch (e: FormatNotReadable) {
      throw e
    } catch (e: java.io.IOException) {
      p.destroyForcibly()
      throw FormatNotReadable("$name helper failed: ${e.message}", e)
    } catch (t: Throwable) {
      p.destroyForcibly()   // stopped half-way through an answer: the rest of it is still in the pipe, so this process cannot be used again
      throw t
    } finally { watchdog.interrupt() }
  }

  private fun drainChunks(header: JSONObject, din: DataInputStream) { if (header.optBoolean("chunked", false)) chunks(din) { _, _ -> } }

  override fun probe(file: File): MediaInfo = call("probe\t${file.absolutePath}") { h, _ ->
    MediaInfo(if (h.has("durationMs")) h.optLong("durationMs").takeIf { it > 0 } else null, h.optBoolean("hasVideo"), h.optBoolean("hasAudio"))
  }

  override fun frame(file: File, timeMs: Long, maxSide: Int): ByteArray? = call("frame\t${file.absolutePath}\t$timeMs\t$maxSide") { h, din ->
    val out = ByteArrayOutputStream()
    if (h.optBoolean("chunked", false)) chunks(din) { b, n -> out.write(b, 0, n) }
    out.toByteArray().takeIf { it.isNotEmpty() }
  }

  override fun audio(file: File, startMs: Long, spanMs: Long, sink: (ByteArray, Int) -> Unit) {
    call("audio\t${file.absolutePath}\t$startMs\t$spanMs") { h, din -> if (h.optBoolean("chunked", false)) chunks(din, sink) }
  }

  override fun close() { runCatching { proc?.outputStream?.close() }; runCatching { proc?.destroy() }; proc = null }

  companion object {
    /** One line of ASCII/UTF-8 up to the newline, byte by byte (a buffered reader would swallow the binary that follows). */
    fun readLine(din: InputStream): String? {
      val b = ByteArrayOutputStream()
      while (true) {
        val c = din.read()
        if (c < 0) return if (b.size() == 0) null else b.toString(Charsets.UTF_8)
        if (c == '\n'.code) return b.toString(Charsets.UTF_8).trimEnd('\r')
        b.write(c)
      }
    }

    /** The length-prefixed chunks of an answer, until the empty one. */
    fun chunks(din: DataInputStream, sink: (ByteArray, Int) -> Unit) {
      var buf = ByteArray(1 shl 16)
      while (true) {
        val n = din.readInt()
        if (n <= 0) return
        if (n > 64 * 1024 * 1024) throw java.io.IOException("chunk of $n bytes")
        if (buf.size < n) buf = ByteArray(n)
        din.readFully(buf, 0, n)
        sink(buf, n)
      }
    }
  }
}

/** Which decoder this computer has, and what Settings says about it. */
object MediaDecoders {
  /** "avfoundation", "media-foundation", "ffmpeg" or "none": what this computer reads video and sound with. Cheap; Settings shows it. */
  fun kind(os: Os = Platform.os, ffmpegPresent: () -> Boolean = ::ffmpegPresent): String = when (os) {
    Os.MAC -> "avfoundation"
    Os.WINDOWS -> "media-foundation"
    Os.LINUX -> if (ffmpegPresent()) "ffmpeg" else "none"
  }

  fun choose(dataDir: File, os: Os = Platform.os): MediaDecoder? = when (kind(os)) {
    "avfoundation" -> HelperDecoder(listOf(appleHelper(File(dataDir, "helpers")).absolutePath), "avfoundation")
    "media-foundation" -> HelperDecoder(
      listOf("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", Helpers.extract("media-windows.ps1", File(dataDir, "helpers")).absolutePath),
      "media-foundation",
    )
    "ffmpeg" -> FfmpegDecoder()
    else -> null
  }

  /** ffmpeg and ffprobe both answer `-version` (the two programs come together in every package; one without the other cannot read the length). */
  fun ffmpegPresent(): Boolean = listOf("ffmpeg", "ffprobe").all { tool ->
    try {
      val p = ProcessBuilder(tool, "-version").redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
      p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0
    } catch (_: Exception) { false }
  }

  private fun appleHelper(dir: File): File {
    val ready = Helpers::class.java.classLoader.getResource("media-apple") != null
    return if (ready) Helpers.extract("media-apple", dir).also { it.setExecutable(true) } else buildApple(Helpers.extract("media-apple.swift", dir), dir)
  }

  /** A developer run of a bare jar has no compiled helper: compile the Swift source on first use. */
  private fun buildApple(source: File, dir: File): File {
    val bin = File(dir, "media-apple")
    if (!bin.exists() || bin.lastModified() < source.lastModified()) {
      val p = ProcessBuilder("swiftc", "-O", source.absolutePath, "-o", bin.absolutePath).redirectErrorStream(true).start()
      val log = p.inputStream.bufferedReader().readText()
      check(p.waitFor() == 0) { "swiftc failed: $log" }
    }
    return bin
  }
}

/** Copies a helper out of the app's resources (they cannot run from inside a jar). Rewritten when the app's copy differs. */
object Helpers {
  fun extract(name: String, dir: File): File {
    dir.mkdirs()
    val out = File(dir, name)
    val bytes = Helpers::class.java.classLoader.getResourceAsStream(name)?.use { it.readBytes() } ?: error("helper $name is missing from the app")
    if (!out.isFile || !out.readBytes().contentEquals(bytes)) out.writeBytes(bytes)
    return out
  }
}
