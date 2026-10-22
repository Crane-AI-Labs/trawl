package com.craneai.findr.ui.theme

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/** True when the user turned animations off (Developer options or "Remove animations"). Motion then snaps. */
val LocalReduceMotion = compositionLocalOf { false }

fun Context.animationsDisabled(): Boolean =
  Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

@Composable
fun rememberReduceMotion(): Boolean {
  val context = LocalContext.current
  return remember(context) { context.animationsDisabled() }
}

/** Brief, purposeful motion: one duration scale for the whole app. */
object Motion {
  const val Quick = 160
  const val Standard = 240
  const val Fill = 420
}

/** A tween that collapses to a snap when motion is reduced. */
fun <T> motionTween(reduce: Boolean, durationMs: Int = Motion.Standard): FiniteAnimationSpec<T> =
  if (reduce) snap() else tween(durationMs)
