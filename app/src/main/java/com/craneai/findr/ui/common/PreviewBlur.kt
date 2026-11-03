package com.craneai.findr.ui.common

import android.graphics.Bitmap
import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import coil3.size.Size
import coil3.transform.Transformation
import com.craneai.findr.core.FindrLog
import com.craneai.findr.core.IndexedFile
import com.craneai.findr.core.PrivacyCheck
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
  /**
   * Scores an unscored picture on screen (blur until scored, internal/research/blur-until-scored.md). While it is
   * [PrivacyCheck.available], a picture with no score yet is blurred ([PrivateLook.Look.CHECKING]) until its score arrives.
   * [PrivacyCheck.None]: no scorer, so an unscored picture is drawn as it is (nothing could ever score it).
   */
  private val check: PrivacyCheck = PrivacyCheck.None,
) {
  private val revealed = mutableStateListOf<Long>()
  private var announced = false
  /** Scores that arrived while this screen is up, one state per picture, so an arriving score redraws only that picture. Main thread. */
  private val fresh = HashMap<Long, MutableState<Float?>>()
  /** Detail logging only (test phones): when each picture was first shown while being checked, for the time-to-unblur line. */
  private val firstChecking = HashMap<Long, Long>()
  /** Detail logging only: the look of each thumbnail on screen now (key: one token per thumbnail), for the on-screen sampler. */
  internal val onScreen = HashMap<Any, PrivateLook.Look>()

  private fun freshState(id: Long): MutableState<Float?> = fresh.getOrPut(id) { mutableStateOf(null) }

  /** How the picture is drawn now, before any reveal. Only an unscored picture reads its fresh-score state, so only it redraws when that arrives. */
  fun look(file: IndexedFile): PrivateLook.Look {
    if (!enabled) return PrivateLook.Look.CLEAR
    val freshScore = if (file.privacyUnscored) freshState(file.id).value else null
    return PrivateLook.look(file.looksPrivate, file.privacyUnscored, freshScore, check.available(), threshold)
  }

  /** A blurred thumbnail has just been drawn: the first one per screen triggers [onFirstBlur] (which itself shows the note only once ever). */
  fun blurShown() {
    if (announced) return
    announced = true
    onFirstBlur()
  }

  /** The file's score is high enough for a blur, and the setting is on (whether or not it has been revealed). */
  fun looksPrivate(file: IndexedFile): Boolean = look(file) == PrivateLook.Look.PRIVATE

  /** The picture has no score yet, so it is blurred until it has one (whether or not it has been revealed). */
  fun checking(file: IndexedFile): Boolean = look(file) == PrivateLook.Look.CHECKING

  /** The thumbnail is blurred right now: it looks private or is still being checked, and the person has not revealed it. */
  fun hides(file: IndexedFile): Boolean = look(file) != PrivateLook.Look.CLEAR && file.id !in revealed

  /**
   * Scores a picture shown while [checking] it, ahead of the background pass. Call it from an effect tied to the thumbnail, so it is
   * cancelled (and the ask dropped) when the thumbnail leaves the screen. When the score arrives, the picture's thumbnails redraw.
   */
  suspend fun check(file: IndexedFile) {
    if (!checking(file)) return
    val score = check.score(file) ?: return   // could not be scored now: it stays blurred (a tap still shows it)
    freshState(file.id).value = score
    if (FindrLog.detail) {
      val since = firstChecking.remove(file.id)?.let { "${(System.nanoTime() - it) / 1_000_000}" } ?: "?"
      FindrLog.i(GATE_TAG, "checked id=${file.id} -> ${look(file)} after $since ms")
    }
  }

  /** Detail logging only: a thumbnail of [file] came on screen as [look] (one line per showing; the A06 exposure count reads it). */
  internal fun shown(file: IndexedFile, look: PrivateLook.Look) {
    if (!FindrLog.detail) return
    if (look == PrivateLook.Look.CHECKING) firstChecking.putIfAbsent(file.id, System.nanoTime())
    FindrLog.i(GATE_TAG, "shown id=${file.id} look=$look unscored=${file.privacyUnscored} revealed=${file.id in revealed} gate=${check.available()}")
  }

  /** Tap on the eye: shows the picture if the gate allows it. */
  fun reveal(file: IndexedFile): Boolean {
    if (!allowReveal()) {
      onDenied()
      return false
    }
    if (file.id !in revealed) revealed.add(file.id)
    return true
  }

  /** Detail logging only: every [SAMPLE_MS], how many picture thumbnails are on screen and how many are blurred while being checked. */
  internal suspend fun logOnScreen() {
    if (!FindrLog.detail) return
    while (true) {
      kotlinx.coroutines.delay(SAMPLE_MS)
      if (onScreen.isEmpty()) continue
      val n = onScreen.size
      val checking = onScreen.values.count { it == PrivateLook.Look.CHECKING }
      val private = onScreen.values.count { it == PrivateLook.Look.PRIVATE }
      FindrLog.i(GATE_TAG, "on screen n=$n checking=$checking private=$private clear=${n - checking - private}")
    }
  }

  companion object {
    val Off = PreviewBlur(enabled = false)
    const val GATE_TAG = "FindrBlurGate"
    const val SAMPLE_MS = 5_000L
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
