package trawl.desktop

import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

/**
 * Pictures the JDK cannot open on its own. Two routes, tried in this order:
 *
 *  1. **In the app.** WebP, Photoshop, TGA, PNM, PCX and ICO/BMP readers (TwelveMonkeys ImageIO, BSD-3-Clause) register themselves with
 *     `javax.imageio`, so the ordinary `ImageIO.read` just works for them and no hook is needed. The JDK itself reads JPEG, PNG, GIF,
 *     BMP and TIFF.
 *  2. **The computer's own decoder** for HEIC, HEIF and AVIF (the phone's default camera format on many handsets, and what an iPhone
 *     sends): macOS `sips` (built in), Windows' imaging component (needs Microsoft's free "HEIF Image Extensions"), and on Linux
 *     `heif-convert` (libheif-examples) or ImageMagick `convert`, whichever is installed. Nothing is shipped for this, as with
 *     the video decoders ([MediaDecoder]): the file is converted to a temporary JPEG, read, and the copy deleted at once.
 *
 * A picture nobody here can open comes back null; the indexer then marks it "can't be opened" and the file stays findable by name.
 */
object ImageFormats {
  /** Extensions that need the computer's decoder. */
  val OS_ROUTE = setOf("heic", "heif", "heics", "heifs", "avif", "avci", "avcs")

  /** One way of turning [src] into a JPEG at [dst]. Returns whether it wrote one. A test swaps in a fake. */
  fun interface Converter { fun convert(src: File, dst: File): Boolean }

  @Volatile internal var converters: List<Converter> = defaultConverters(Platform.os)

  /** The picture, turned upright, or null when no route can open it. Blocking. */
  fun readUpright(file: File): BufferedImage? {
    direct(file)?.let { return it }
    if (file.extension.lowercase() !in OS_ROUTE) return null
    return viaConverter(file) { tmp -> direct(tmp) }
  }

  /** A preview at about [side] px on the short side, for the result grid, from a format only the computer can open. */
  fun thumbnail(file: File, side: Int): BufferedImage? {
    if (file.extension.lowercase() !in OS_ROUTE) return null
    return viaConverter(file) { tmp -> Thumbnails.decode(tmp, side) }
  }

  private fun direct(file: File): BufferedImage? = try {
    ImageIO.read(file)?.let { Exif.upright(it, Exif.orientation(file)) }   // the phone's decoder turns a photo upright; ImageIO does not
  } catch (_: Throwable) { null }

  private fun <T> viaConverter(file: File, use: (File) -> T?): T? {
    val tmp = Files.createTempFile("trawl-img-", ".jpg").toFile()
    val extra = File(tmp.path.removeSuffix(".jpg") + "-1.jpg")   // heif-convert numbers the pictures of a multi-image file
    try {
      for (c in converters) {
        val wrote = try { c.convert(file, tmp) } catch (_: Throwable) { false }
        val out = listOf(tmp, extra).firstOrNull { wrote && it.length() > 0 } ?: continue
        use(out)?.let { return it }
      }
      return null
    } finally { tmp.delete(); extra.delete() }
  }

  // ---- the commands (pure: a list of words in, a list of words out, tested on any machine) -------------------------------------

  fun sipsCommand(src: File, dst: File): List<String> = listOf("sips", "-s", "format", "jpeg", src.absolutePath, "--out", dst.absolutePath)

  fun heifConvertCommand(src: File, dst: File): List<String> = listOf("heif-convert", "-q", "92", src.absolutePath, dst.absolutePath)

  fun magickCommand(src: File, dst: File): List<String> = listOf("convert", src.absolutePath + "[0]", "-auto-orient", "-quality", "92", dst.absolutePath)

  /** The file names go in through the environment (TRAWL_SRC, TRAWL_DST), so no path is ever spliced into script text. */
  fun windowsCommand(): List<String> = listOf(
    "powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-Command",
    "Add-Type -AssemblyName PresentationCore; \$s=[System.IO.File]::OpenRead(\$env:TRAWL_SRC); " +
      "try { \$d=[System.Windows.Media.Imaging.BitmapDecoder]::Create(\$s,'PreservePixelFormat','None'); " +
      "\$e=New-Object System.Windows.Media.Imaging.JpegBitmapEncoder; \$e.QualityLevel=92; \$e.Frames.Add(\$d.Frames[0]); " +
      "\$o=[System.IO.File]::Create(\$env:TRAWL_DST); try { \$e.Save(\$o) } finally { \$o.Close() } } finally { \$s.Close() }",
  )

  fun defaultConverters(os: Os): List<Converter> = when (os) {
    Os.MAC -> listOf(Converter { s, d -> run(sipsCommand(s, d)) })
    Os.WINDOWS -> listOf(Converter { s, d -> run(windowsCommand(), mapOf("TRAWL_SRC" to s.absolutePath, "TRAWL_DST" to d.absolutePath)) })
    // heif-convert will not overwrite a file that exists, and the temp file was created empty: it is removed first.
    Os.LINUX -> listOf(
      Converter { s, d -> d.delete(); run(heifConvertCommand(s, d)) },
      Converter { s, d -> run(magickCommand(s, d)) },
    )
  }

  private fun run(cmd: List<String>, env: Map<String, String> = emptyMap(), timeoutS: Long = 60): Boolean {
    val p = try {
      ProcessBuilder(cmd).apply { environment().putAll(env); redirectErrorStream(true); redirectOutput(ProcessBuilder.Redirect.DISCARD) }.start()
    } catch (_: java.io.IOException) { return false }   // the program is not installed
    if (!p.waitFor(timeoutS, TimeUnit.SECONDS)) { p.destroyForcibly(); return false }
    return p.exitValue() == 0
  }
}

/**
 * B15: does this computer have a way to open HEIC, HEIF and AVIF pictures? [ImageFormats] uses the computer's own decoder for those, and
 * nothing is shipped for it, so a computer without one cannot read them and Settings says so (with what to install) instead of leaving
 * each picture with only its own "can't be opened" line. macOS always has one (`sips`). The answer is asked once per run, off the UI
 * thread, and is only ever a yes or a no about programs and components: nothing about the person's files.
 */
object HeicRoute {
  /** Windows: Microsoft's free "HEIF Image Extensions" is what lets the imaging component open HEIC/HEIF (and the AV1 one AVIF). */
  fun windowsProbe(): List<String> = listOf(
    "powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-Command",
    "if (Get-AppxPackage -Name Microsoft.HEIFImageExtension -ErrorAction SilentlyContinue) { 'yes' } else { 'no' }",
  )

  /** `convert -list format` lists a line like `HEIC* HEIC  rw+  ...` when ImageMagick was built with libheif. */
  fun magickReadsHeic(listing: String?): Boolean = listing != null && Regex("""(?m)^\s*HEI[CF]\*?\s+\S+\s+r""").containsMatchIn(listing)

  /** Whether [name] is an executable file in one of the folders of [pathEnv]. */
  fun onPath(name: String, pathEnv: String? = System.getenv("PATH"), isFile: (File) -> Boolean = { it.isFile && it.canExecute() }): Boolean =
    pathEnv.orEmpty().split(File.pathSeparatorChar).filter { it.isNotBlank() }.any { isFile(File(it, name)) }

  /** The pure decision, with the two questions to the system passed in so a test needs neither. */
  fun available(os: Os, exists: (String) -> Boolean, output: (List<String>) -> String?): Boolean = when (os) {
    Os.MAC -> true
    Os.WINDOWS -> output(windowsProbe())?.trim().equals("yes", ignoreCase = true)
    Os.LINUX -> exists("heif-convert") || (exists("convert") && magickReadsHeic(output(listOf("convert", "-list", "format"))))
  }

  private val answer: Boolean by lazy {
    available(Platform.os, { onPath(it) }, { cmd -> runCatching { probe(cmd) }.getOrNull() })
  }

  /** Blocking the first time (a PowerShell start on Windows); call it off the UI thread. */
  fun present(): Boolean = answer

  private fun probe(cmd: List<String>): String? {
    val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
    val text = p.inputStream.bufferedReader().readText()
    return if (p.waitFor(20, TimeUnit.SECONDS) && p.exitValue() == 0) text else { p.destroyForcibly(); null }
  }
}
