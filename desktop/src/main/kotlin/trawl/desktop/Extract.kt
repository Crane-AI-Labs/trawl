package trawl.desktop

import com.craneai.findr.engine.Chunker
import com.craneai.findr.extract.TextDecoding
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper

/** What a file gives the indexer: text pieces by page (documents) or a picture. Android's FileProcessor does the same per kind. */
object Extract {
  const val EMBED_MAX_SIDE = 768       // the phone's FileProcessor.EMBED_MAX_SIDE

  /** Plain text: decoded by the shared TextDecoding (BOM, UTF-16, UTF-8 / cp1252), then cut by the shared Chunker (400 / 60). */
  /** PDFBox logs font and glyph warnings through java.util.logging; keep the console readable. */
  private val quietPdfLogs: Unit by lazy {
    java.util.logging.Logger.getLogger("org.apache.pdfbox").level = java.util.logging.Level.SEVERE
    java.util.logging.Logger.getLogger("org.apache.fontbox").level = java.util.logging.Level.SEVERE
  }

  fun textChunks(file: File): List<List<String>> {
    val bytes = file.readBytes()
    return listOf(Chunker.chunks(TextDecoding.decode(bytes)))
  }

  /** PDF with a text layer: one chunk list per page (page number = index + 1). Scanned PDFs need the OCR path (not in the prototype). */
  fun pdfPages(file: File): List<List<String>> = Loader.loadPDF(file).use { doc ->
    quietPdfLogs
    val stripper = PDFTextStripper()
    (1..doc.numberOfPages).map { p ->
      stripper.startPage = p; stripper.endPage = p
      Chunker.chunks(stripper.getText(doc))
    }
  }

  /** The phone's picture preparation: longest side 768 px, JPEG quality 90 (scripts/eval_quality.py phone_jpeg). */
  fun phoneJpeg(file: File, maxSide: Int = EMBED_MAX_SIDE): ByteArray? {
    val src = ImageFormats.readUpright(file) ?: return null   // hook (Package C): ImageIO, then WebP/PSD/TGA readers, then the computer's own HEIC decoder; turned upright
    val scale = maxSide.toDouble() / maxOf(src.width, src.height)
    val w = if (scale < 1) maxOf(1, (src.width * scale).toInt()) else src.width
    val h = if (scale < 1) maxOf(1, (src.height * scale).toInt()) else src.height
    val rgb = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
    rgb.createGraphics().apply {
      setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
      setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
      drawImage(src, 0, 0, w, h, null); dispose()
    }
    val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
    val out = ByteArrayOutputStream()
    ImageIO.createImageOutputStream(out).use { ios ->
      writer.output = ios
      val param = writer.defaultWriteParam.apply { compressionMode = ImageWriteParam.MODE_EXPLICIT; compressionQuality = 0.90f }
      writer.write(null, IIOImage(rgb, null, null), param)
    }
    writer.dispose()
    return out.toByteArray()
  }
}

/** Text from a picture. One per OS: Apple Vision (macOS), Windows.Media.Ocr (Windows, later), Tesseract (the portable fallback). */
interface Ocr : AutoCloseable { fun read(image: File): String; val name: String }

/** Tesseract through its command line (Apache-2.0; `apt install tesseract-ocr`). The prototype's Linux reader; PP-OCRv5 on ONNX Runtime is the planned in-process one. */
class TesseractOcr(private val lang: String = "eng") : Ocr {
  override val name = "tesseract"
  override fun read(image: File): String {
    val p = ProcessBuilder("tesseract", image.absolutePath, "stdout", "-l", lang).redirectError(ProcessBuilder.Redirect.DISCARD).start()
    val text = p.inputStream.bufferedReader().readText()
    p.waitFor()
    return text
  }
  override fun close() {}
}

/** Windows.Media.Ocr through a long-lived Windows PowerShell helper (native/ocr-windows.ps1), same line protocol as the Apple one. Windows only. */
class WindowsOcr(script: File) : Ocr {
  override val name = "windows-media-ocr"
  private val proc = ProcessBuilder("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", script.absolutePath)
    .redirectError(ProcessBuilder.Redirect.DISCARD).start()
  private val stdin = proc.outputStream.bufferedWriter()
  private val stdout = proc.inputStream.bufferedReader()

  @Synchronized override fun read(image: File): String {
    stdin.write(image.absolutePath); stdin.newLine(); stdin.flush()
    val line = stdout.readLine() ?: return ""
    return org.json.JSONObject(line).optString("text", "")
  }

  override fun close() { runCatching { stdin.close() }; proc.destroy() }
}

/** Apple Vision through a long-lived Swift helper (native/ocr-stdin.swift, compiled once): path in, text out. macOS only. */
class AppleOcr(private val helper: File) : Ocr {
  override val name = "apple-vision"
  private val proc = ProcessBuilder(helper.absolutePath).redirectError(ProcessBuilder.Redirect.DISCARD).start()
  private val stdin = proc.outputStream.bufferedWriter()
  private val stdout = proc.inputStream.bufferedReader()

  @Synchronized override fun read(image: File): String {
    stdin.write(image.absolutePath); stdin.newLine(); stdin.flush()
    val line = stdout.readLine() ?: return ""
    return org.json.JSONObject(line).optString("text", "")
  }

  override fun close() { runCatching { stdin.close() }; proc.destroy() }

  companion object {
    /** Compiles the helper next to [dir] on first use. */
    fun build(source: File, dir: File): File {
      val bin = File(dir, "ocr-stdin")
      if (!bin.exists() || bin.lastModified() < source.lastModified()) {
        dir.mkdirs()
        val p = ProcessBuilder("swiftc", "-O", source.absolutePath, "-o", bin.absolutePath).redirectErrorStream(true).start()
        val log = p.inputStream.bufferedReader().readText()
        check(p.waitFor() == 0) { "swiftc failed: $log" }
      }
      return bin
    }
  }
}
