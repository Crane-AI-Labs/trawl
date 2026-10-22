package com.craneai.findr.core

import kotlin.math.exp
import kotlin.math.max

/**
 * "Looks private": a score from 0 to 1 for how much a picture resembles nudity, computed once at index time and stored in
 * `files.looks_private`. It does exactly one thing: it decides whether a thumbnail is shown blurred (until the user taps it).
 *
 * It must never rank, filter or hide a result. Classifiers like this one are biased (they flag women and swimwear more
 * often, and close-ups of skin of any tone), so a wrong score may cost the user one extra tap and nothing else: the file is
 * still found, still listed, still opens normally. Nothing in the search path reads this value, and
 * PrivateLookTest.rankingNeverSeesTheScore keeps it that way.
 *
 * Model: Marqo/nsfw-image-detection-384 (Apache-2.0), a ViT-tiny/16 at 384 px. Its probabilities are label-smoothed, so they
 * never exceed about 0.96: a threshold near 1 would blur nothing, which is why [THRESHOLD] sits at 0.9.
 */
object PrivateLook {
  /** Which model and preprocessing wrote the scores; a different value clears them so the pictures are scored again. */
  const val MODEL = "marqo-nsfw-384:vit-tiny:squash384-bicubic:v1"

  /**
   * At or above this a thumbnail is blurred. Picked by measuring on non-explicit pictures (faces by race and gender, skin close-ups by skin tone, swimwear, sport, traditional dress): at 0.9 it blurs
   * 0.1% of ordinary face photos, no sport or traditional dress, about one bikini photo in six, and trades recall for it
   * (the model's own published curve keeps most of its recall up to here and falls away above 0.93).
   */
  const val THRESHOLD = 0.9f

  /**
   * The main app blurs by default, so it uses a stricter bar than the quick panel (which stays at [THRESHOLD], because it opens
   * over other apps): the lowest at which everyday photos of women AND men are blurred at or under about 1% (Open Images, n = 347
   * women, 381 men: 0.9% and 0% at 0.94; at 0.93 women are 1.2%, at the old 0.9 they were 3.7% against 0.3%). The price is
   * recall: the model's published curve falls away above 0.93, so the main app blurs fewer true positives than the panel.
   * Probabilities are label-smoothed and never exceed about 0.96, so 0.97 and above would blur nothing. See
   * the research notes for the sweep.
   */
  const val MAIN_APP_THRESHOLD = 0.94f

  /** True when [score] (null: not scored, or unreadable) is high enough to blur the thumbnail. */
  fun blurs(score: Float?, threshold: Float = THRESHOLD): Boolean = score != null && score >= threshold

  /** P(NSFW) from the classifier's two logits (index 0 NSFW, index 1 SFW). */
  fun probability(nsfwLogit: Float, sfwLogit: Float): Float {
    val m = max(nsfwLogit, sfwLogit)
    val a = exp((nsfwLogit - m).toDouble())
    val b = exp((sfwLogit - m).toDouble())
    return (a / (a + b)).toFloat()
  }

  /** A video's score: its most private-looking keyframe. */
  fun ofFrames(scores: List<Float>): Float? = scores.maxOrNull()
}
