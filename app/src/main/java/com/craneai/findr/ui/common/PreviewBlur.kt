package com.craneai.findr.ui.common

import android.graphics.Bitmap
import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.staticCompositionLocalOf
import coil3.size.Size
import coil3.transform.Transformation
import com.craneai.findr.core.IndexedFile
import com.craneai.findr.R
import com.craneai.findr.core.PrivateLook
import com.craneai.findr.ui.app.UiPrefs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Whether thumbnails that look private ([PrivateLook]) are shown blurred, and which ones the person has revealed.
 *
 * A blur is all this ever does. The result list, its order and every file in it are the same with it on or off, and a
 * blurred file opens, shares and copies exactly like any other: the score is read here and nowhere in the search path.
 *
 *  - [enabled]: the setting (on by default in both the main app and the quick-search panel, once the add-on's model is in).
 *  - [threshold]: the score at which a thumbnail blurs; the main app uses the stricter [PrivateLook.MAIN_APP_THRESHOLD].
 *  - [onFirstBlur]: called once, the first time a blurred thumbnail is drawn (see [blurHintOnce]).
 *  - [allowReveal]: asked before a tap reveals a picture. The quick-search panel answers "is the phone unlocked", so a phone
 *    held up on its lock screen can't be made to show a blurred picture; it calls [onDenied] instead, to say why.
 *  - A reveal lasts until the screen that made this object goes away (it is not remembered anywhere).
 */
@Stable
class PreviewBlur(
  val enabled: Boolean,
  private val allowReveal: () -> Boolean = { true },
  private val onDenied: () -> Unit = {},
  private val threshold: Float = PrivateLook.THRESHOLD,
  private val onFirstBlur: () -> Unit = {},
) {
  private val revealed = mutableStateListOf<Long>()
  private var announced = false

  /** A blurred thumbnail has just been drawn: the first one per screen triggers [onFirstBlur] (which itself shows the note only once ever). */
  fun blurShown() {
    if (announced) return
    announced = true
    onFirstBlur()
  }

  /** The file's score is high enough for a blur, and the setting is on (whether or not it has been revealed). */
  fun looksPrivate(file: IndexedFile): Boolean = enabled && PrivateLook.blurs(file.looksPrivate, threshold)

  /** The thumbnail is blurred right now. */
  fun hides(file: IndexedFile): Boolean = looksPrivate(file) && file.id !in revealed

  /** Tap on the eye: shows the picture if the gate allows it. */
  fun reveal(file: IndexedFile): Boolean {
    if (!allowReveal()) {
      onDenied()
      return false
    }
    if (file.id !in revealed) revealed.add(file.id)
    return true
  }

  companion object {
    val Off = PreviewBlur(enabled = false)
  }
}

/**
 * The first time a preview is ever blurred, says so once, in words: blurring is on by default, so the first blurred picture would
 * otherwise look like a bug. The flag lives in [UiPrefs], shared by the app and the quick panel, so it is shown once in total.
 */
fun blurHintOnce(context: Context): () -> Unit = {
  val prefs = UiPrefs(context)
  if (!prefs.blurHintShown) {
    prefs.blurHintShown = true
    Toast.makeText(context, context.appString(R.string.blur_first_hint), Toast.LENGTH_LONG).show()
  }
}

val LocalPreviewBlur = staticCompositionLocalOf { PreviewBlur.Off }

/**
 * Makes a bitmap unrecognisable: shrunk to about [SIDE] px on its long side (halving first, so it averages instead of
 * skipping pixels), then drawn big again by the image view's smooth scaling. The shrunk bitmap is all that is kept, in memory
 * and in the cache, so the original pixels of a blurred thumbnail aren't held while it is blurred. Compose's own
 * `Modifier.blur` does nothing below Android 12 and Findr supports Android 11.
 */
internal class PrivacyBlurTransformation : Transformation() {
  override val cacheKey: String = "findr-privacy-blur-v1"

  override suspend fun transform(input: Bitmap, size: Size): Bitmap = blurred(input)

  companion object {
    const val SIDE = 12

    /** The long side's target is [SIDE]; the short side keeps the picture's shape. */
    fun targetSize(width: Int, height: Int): Pair<Int, Int> {
      val long = max(width, height).coerceAtLeast(1)
      val scale = SIDE.toFloat() / long
      return max(1, (width * scale).roundToInt()) to max(1, (height * scale).roundToInt())
    }

    fun blurred(input: Bitmap): Bitmap {
      val (w, h) = targetSize(input.width, input.height)
      var current = input
      // Halve while still more than twice the target: each step averages 2×2 pixels.
      while (current.width / 2 >= w * 2 && current.height / 2 >= h * 2) {
        val next = Bitmap.createScaledBitmap(current, current.width / 2, current.height / 2, true)
        if (current !== input) current.recycle()
        current = next
      }
      val out = Bitmap.createScaledBitmap(current, w, h, true)
      if (current !== input && current !== out) current.recycle()
      return out
    }
  }
}
