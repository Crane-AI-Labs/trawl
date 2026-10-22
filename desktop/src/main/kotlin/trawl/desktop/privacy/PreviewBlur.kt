package trawl.desktop.privacy

import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.craneai.findr.core.IndexedFile
import com.craneai.findr.core.PrivateLook
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt
import trawl.desktop.Thumbnails

/**
 * Whether thumbnails and previews that look private ([PrivateLook]) are shown blurred, and which ones the person has revealed.
 *
 * A blur is all this ever does. The result list, its order and every file in it are the same with it on or off, and a blurred file
 * opens, copies and shares like any other: the score is read here and nowhere in the search path.
 *
 *  - [enabled]: the setting, AND the Privacy pack is installed (without it no picture has a score, so nothing would blur anyway).
 *  - [threshold]: the score at which a picture blurs; the main window uses the stricter [PrivateLook.MAIN_APP_THRESHOLD].
 *  - [allowReveal]: asked before a click reveals a picture. The quick panel answers "is the app unlocked".
 *  - A reveal lasts until the screen that made this object goes away: it is not remembered anywhere.
 */
@Stable
class PreviewBlur(
  val enabled: Boolean,
  private val allowReveal: () -> Boolean = { true },
  private val onDenied: () -> Unit = {},
  private val threshold: Float = PrivateLook.MAIN_APP_THRESHOLD,
  private val onFirstBlur: () -> Unit = {},
) {
  private val revealed = mutableStateListOf<Long>()
  private var announced = false

  /** A blurred picture has just been drawn: the first one per window triggers [onFirstBlur] (which itself says it only once ever). */
  fun blurShown() {
    if (announced) return
    announced = true
    onFirstBlur()
  }

  /** The file's score is high enough for a blur and the setting is on (whether or not it has been revealed). */
  fun looksPrivate(file: IndexedFile): Boolean = enabled && PrivateLook.blurs(file.looksPrivate, threshold)

  /** The picture is blurred right now. */
  fun hides(file: IndexedFile): Boolean = looksPrivate(file) && file.id !in revealed

  /** Click on the eye: shows the picture if the gate allows it. */
  fun reveal(file: IndexedFile): Boolean {
    if (!allowReveal()) {
      onDenied()
      return false
    }
    if (file.id !in revealed) revealed.add(file.id)
    return true
  }

  /** Blurs it again (the eye clicked a second time). */
  fun cover(file: IndexedFile) {
    revealed.remove(file.id)
  }

  /** How many of [files] are held back by the blur right now: the number the "hidden" chip shows. */
  fun hiddenCount(files: Collection<IndexedFile>): Int = files.count(::hides)

  /** Shows every picture in [files] that looks private. */
  fun revealAll(files: Collection<IndexedFile>): Boolean {
    if (!allowReveal()) {
      onDenied()
      return false
    }
    for (f in files) if (looksPrivate(f) && f.id !in revealed) revealed.add(f.id)
    return true
  }

  /** Some of [files] that look private have been shown (the chip then offers to hide them again). */
  fun anyRevealed(files: Collection<IndexedFile>): Boolean = enabled && files.any { it.id in revealed && PrivateLook.blurs(it.looksPrivate, threshold) }

  /** Blurs every picture in [files] again. */
  fun coverAll(files: Collection<IndexedFile>) {
    for (f in files) revealed.remove(f.id)
  }

  companion object {
    val Off = PreviewBlur(enabled = false)
  }
}

val LocalPreviewBlur = staticCompositionLocalOf { PreviewBlur.Off }

/**
 * Makes a picture unrecognisable: shrunk to about [SIDE] px on its long side (halving first, so it averages instead of skipping
 * pixels), then drawn big again with smooth bicubic scaling to [SHOWN] px. The result holds only about 12x12 pixels of the picture's
 * information, whatever the view does with it, so no zoom, crop or contrast stretch can bring the detail back.
 */
object Blurred {
  const val SIDE = 12
  const val SHOWN = 96

  /** The long side's target is [SIDE]; the short side keeps the picture's shape. */
  fun targetSize(width: Int, height: Int, side: Int = SIDE): Pair<Int, Int> {
    val long = max(width, height).coerceAtLeast(1)
    val scale = side.toFloat() / long
    return max(1, (width * scale).roundToInt()) to max(1, (height * scale).roundToInt())
  }

  fun of(input: BufferedImage): BufferedImage {
    val (w, h) = targetSize(input.width, input.height)
    var current = argb(input)
    // Halve while still more than twice the target: each step averages 2x2 pixels.
    while (current.width / 2 >= w * 2 && current.height / 2 >= h * 2) current = scale(current, current.width / 2, current.height / 2, bilinear = true)
    val small = scale(current, w, h, bilinear = true)
    val (bw, bh) = targetSize(w, h, SHOWN)
    return scale(small, bw, bh, bilinear = false)
  }

  private fun argb(src: BufferedImage): BufferedImage {
    if (src.type == BufferedImage.TYPE_INT_ARGB) return src
    return BufferedImage(src.width, src.height, BufferedImage.TYPE_INT_ARGB).also { out ->
      out.createGraphics().apply { drawImage(src, 0, 0, null); dispose() }
    }
  }

  private fun scale(src: BufferedImage, w: Int, h: Int, bilinear: Boolean): BufferedImage {
    val out = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
    out.createGraphics().apply {
      setRenderingHint(RenderingHints.KEY_INTERPOLATION, if (bilinear) RenderingHints.VALUE_INTERPOLATION_BILINEAR else RenderingHints.VALUE_INTERPOLATION_BICUBIC)
      setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
      drawImage(src, 0, 0, w, h, null)
      dispose()
    }
    return out
  }
}

/**
 * Pictures for the grid and the preview pane, blurred or sharp. The blurred ones are decoded straight from the file and cached ONLY
 * in their blurred form: [Thumbnails.load] caches the sharp picture, so a private-looking one must never go through it, or the sharp
 * pixels would sit in memory (and come back on a later draw) while the picture is meant to be hidden.
 */
object PrivateThumbnails {
  private const val KEEP = 200
  private val cache = object : LinkedHashMap<String, ImageBitmap>(KEEP, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>) = size > KEEP
  }

  /** The picture if it is already decoded in the form asked for (no decoding, no waiting): lets a tile that scrolls back into view draw at once. */
  fun peek(file: File, side: Int, blurred: Boolean): ImageBitmap? =
    if (!blurred) Thumbnails.peek(file, side) else synchronized(this) { cache["${file.absolutePath}|${file.lastModified()}"] }

  /** Blocking: call it off the UI thread. */
  fun load(file: File, side: Int, blurred: Boolean): ImageBitmap? {
    if (!blurred) return Thumbnails.load(file, side)
    val key = "${file.absolutePath}|${file.lastModified()}"
    synchronized(this) { cache[key] }?.let { return it }
    // Decode small: the blur keeps 12 px of it anyway.
    val img = Thumbnails.decode(file, Blurred.SHOWN) ?: return null
    val bmp = Blurred.of(img).toComposeImageBitmap()
    synchronized(this) { cache[key] = bmp }
    return bmp
  }

  /** Forget everything blurred (the Privacy pack was removed, or the blur turned off). */
  fun clear() = synchronized(this) { cache.clear() }
}
