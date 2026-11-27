package trawl.desktop

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runInterruptible
import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer

/**
 * Renders a single PDF page to a Compose [ImageBitmap] using the already-bundled Apache PDFBox 3.x library,
 * with a small bounded LRU cache, bounded 2-thread render pool (so rapid arrow-key scrolling through dozens of PDFs
 * skips superseded renders and never saturates Dispatchers.IO), and short negative-failure TTL for damaged/encrypted PDFs.
 */
object PdfThumbs {
  private const val MAX_CACHE_ENTRIES = 24
  private const val MAX_PDF_BYTES = 60L * 1024L * 1024L
  private const val RENDER_DPI = 96f
  private const val RETRY_MS = 30_000L

  private val pdfPool = Executors.newFixedThreadPool(2) { r ->
    Thread(r, "trawl-pdf-thumb").apply { isDaemon = true }
  }.asCoroutineDispatcher()

  private val cache = object : LinkedHashMap<String, ImageBitmap>(32, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?): Boolean = size > MAX_CACHE_ENTRIES
  }
  private val failedAt = HashMap<String, Long>()

  private fun key(file: File, page: Int, side: Int, modifiedMillis: Long? = null): String =
    "${file.absolutePath}|${modifiedMillis ?: file.lastModified()}|$page|$side"

  /** Instant cache lookup. Pass [modifiedMillis] from [com.craneai.findr.core.IndexedFile] to avoid UI-thread `stat()`. */
  fun peek(file: File, page: Int = 1, side: Int = 720, modifiedMillis: Long? = null): ImageBitmap? = synchronized(cache) {
    cache[key(file, page.coerceAtLeast(1), side, modifiedMillis)]
  }

  /** Cancellable suspend render on the bounded PDF pool: skips stale renders if the user already arrowed past this PDF. */
  suspend fun loadSuspend(file: File, page: Int = 1, side: Int = 720, modifiedMillis: Long? = null): ImageBitmap? {
    peek(file, page, side, modifiedMillis)?.let { return it }
    val ctx = currentCoroutineContext()
    ctx.ensureActive()
    val bmp = runInterruptible(pdfPool) {
      load(file, page, side, modifiedMillis, isCancelled = { !ctx.isActive || Thread.currentThread().isInterrupted })
    }
    ctx.ensureActive()
    return bmp
  }

  /** Renders 1-based [page] of [file], scaled to fit within [side] px. Must be called off the UI thread. */
  fun load(
    file: File,
    page: Int = 1,
    side: Int = 720,
    modifiedMillis: Long? = null,
    isCancelled: () -> Boolean = { Thread.currentThread().isInterrupted },
  ): ImageBitmap? {
    if (isCancelled()) return null
    val safePage = page.coerceAtLeast(1)
    val k = key(file, safePage, side, modifiedMillis)
    val now = System.currentTimeMillis()
    synchronized(cache) {
      cache[k]?.let { return it }
      failedAt[k]?.let { at -> if (now - at < RETRY_MS) return null }
    }
    if (!file.isFile || file.length() <= 0L || file.length() > MAX_PDF_BYTES) return null
    val bmp = runCatching {
      Loader.loadPDF(file).use { doc ->
        if (isCancelled() || doc.numberOfPages <= 0) return@use null
        val idx = (safePage - 1).coerceIn(0, doc.numberOfPages - 1)
        // Clamp render scale directly from cropBox before PDFBox allocates the raster buffer, preventing blueprint /
        // malformed /MediaBox [0 0 20000 20000] OOM bombs.
        val box = doc.getPage(idx).cropBox
        val maxDimPt = maxOf(box.width, box.height).coerceAtLeast(1f)
        val scale = minOf(RENDER_DPI / 72f, side.toFloat() / maxDimPt).coerceIn(0.05f, RENDER_DPI / 72f)
        val raw = PDFRenderer(doc).renderImage(idx, scale, ImageType.RGB)
        if (isCancelled()) return@use null
        scaleToFit(raw, side).toComposeImageBitmap()
      }
    }.getOrNull()
    synchronized(cache) {
      if (bmp != null) {
        cache[k] = bmp
        failedAt.remove(k)
      } else if (!isCancelled()) {
        failedAt[k] = System.currentTimeMillis()
      }
    }
    return bmp
  }

  internal fun clearCache() = synchronized(cache) {
    cache.clear()
    failedAt.clear()
  }

  private fun scaleToFit(src: BufferedImage, maxSide: Int): BufferedImage {
    val w = src.width
    val h = src.height
    val longest = maxOf(w, h)
    if (longest <= maxSide || longest <= 0) return src
    val ratio = maxSide.toDouble() / longest.toDouble()
    val nw = (w * ratio).toInt().coerceAtLeast(1)
    val nh = (h * ratio).toInt().coerceAtLeast(1)
    val out = BufferedImage(nw, nh, BufferedImage.TYPE_INT_RGB)
    val g = out.createGraphics()
    try {
      g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
      g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
      g.drawImage(src, 0, 0, nw, nh, null)
    } finally {
      g.dispose()
    }
    return out
  }
}
