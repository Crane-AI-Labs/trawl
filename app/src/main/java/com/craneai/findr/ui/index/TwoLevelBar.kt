package com.craneai.findr.ui.index

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.graphics.drawscope.Stroke
import com.craneai.findr.ui.theme.LocalSunlight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.craneai.findr.ui.theme.LocalReduceMotion
import com.craneai.findr.ui.theme.Motion
import com.craneai.findr.ui.theme.motionTween

/**
 * A progress bar with two fills, both marker yellow inside an ink edge (Sunlight: the marker is a fill, never an edge): a pale
 * one for "searchable by name and words" (cheap, fast) and a solid one for "understood by meaning" (slow). The solid fill
 * should never be ahead of the pale one. The animation is brief and snaps when the user has turned animations off.
 */
@Composable
fun TwoLevelBar(
  searchable: Float,
  understood: Float,
  modifier: Modifier = Modifier,
  height: Dp = 6.dp,
  searchableColor: Color = LocalSunlight.current.marker.copy(alpha = 0.40f),
  understoodColor: Color = LocalSunlight.current.marker,
  trackColor: Color = LocalSunlight.current.paper,
  edgeColor: Color = LocalSunlight.current.line,
) {
  val reduce = LocalReduceMotion.current
  val s by animateFloatAsState(searchable.coerceIn(0f, 1f), motionTween(reduce, Motion.Fill), label = "searchable")
  val u by animateFloatAsState(understood.coerceIn(0f, 1f), motionTween(reduce, Motion.Fill), label = "understood")
  Canvas(
    modifier
      .fillMaxWidth()
      .height(height)
      .clearAndSetSemantics { }, // the parent describes the numbers; the bar is decoration for TalkBack
  ) {
    val r = CornerRadius(size.height / 2f)
    val edge = minOf(1.5.dp.toPx(), size.height / 4f)
    drawRoundRect(trackColor, size = size, cornerRadius = r)
    if (s > 0f) drawRoundRect(searchableColor, size = Size(size.width * s, size.height), cornerRadius = r)
    if (u > 0f) drawRoundRect(understoodColor, topLeft = Offset.Zero, size = Size(size.width * maxOf(u, 0.001f), size.height), cornerRadius = r)
    // The ink edge goes on last, over the fills, so the bar reads in sunlight and in greyscale.
    drawRoundRect(edgeColor, topLeft = Offset(edge / 2, edge / 2), size = Size(size.width - edge, size.height - edge), cornerRadius = r, style = Stroke(edge))
  }
}
