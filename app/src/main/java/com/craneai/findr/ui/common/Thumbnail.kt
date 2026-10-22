package com.craneai.findr.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import com.craneai.findr.R
import com.craneai.findr.ui.theme.SunType
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.allowRgb565
import coil3.request.crossfade
import coil3.request.transformations
import coil3.video.videoFrameMillis
import com.craneai.findr.core.FileKind
import com.craneai.findr.core.IndexedFile
import com.craneai.findr.ui.theme.LocalReduceMotion

/**
 * A file's picture. Always a tonal block with the kind's icon first (instant, free), then the real thumbnail on top
 * once Coil has decoded it at the size of the cell. Sample (`fake://`) files and anything Coil cannot decode keep the
 * block, so the grid never shows a broken-image glyph. Videos show a frame: the hit's moment when there is one.
 */
@Composable
fun FileThumbnail(
  file: IndexedFile,
  modifier: Modifier = Modifier,
  frameMs: Long? = null,
  iconSize: Dp = 28.dp,
  /** A painter shared by every cell of the grid ([SharedIcons]); null draws the kind's icon with a painter of its own. */
  iconPainter: Painter? = null,
  /** A blurred picture says why in words ("Looks private · tap to see"), where the tile is big enough to hold them. */
  hiddenWords: Boolean = false,
) {
  val tone = file.kind.tone()
  val context = LocalContext.current
  val reduceMotion = LocalReduceMotion.current
  val blur = LocalPreviewBlur.current
  val hidden = blur.hides(file)
  val loadable = file.kind.isVisual() && !file.uri.startsWith("fake://")
  var failed by remember(file.uri) { mutableStateOf(false) }
  // First choice is the system's small thumbnail (cheap); if MediaProvider cannot make one, decode the file itself.
  var fallback by remember(file.uri) { mutableStateOf(false) }

  // Sunlight: one flat paper-2 block for every kind (Color keys compare by value, so these are made once per theme).
  val brush = remember(tone.container) { SolidColor(tone.container) }
  val iconTint = remember(tone.content) { ColorFilter.tint(tone.content) }
  Box(
    // The block and (in the grid) its icon are drawn by this one node, not by child nodes: fewer nodes per cell to
    // compose, measure and record while a fling binds a row every few frames (the research notes).
    modifier.drawBehind {
      drawRect(brush)
      if (iconPainter != null) {
        val side = iconSize.toPx()
        translate((size.width - side) / 2, (size.height - side) / 2) {
          with(iconPainter) { draw(Size(side, side), alpha = 0.75f, colorFilter = iconTint) }
        }
      }
    },
    contentAlignment = Alignment.Center,
  ) {
    // The icon stays under the picture rather than being removed when it arrives: a state change per arriving thumbnail
    // recomposed the cell on the main thread during flings.
    if (iconPainter == null) Icon(file.kind.icon(), contentDescription = null, tint = tone.content, modifier = Modifier.size(iconSize).alpha(0.75f))
    if (loadable && !failed) {
      val systemThumb = !fallback && (file.kind == FileKind.IMAGE || (file.kind == FileKind.VIDEO && frameMs == null))
      val request = remember(file.uri, frameMs, reduceMotion, systemThumb, hidden) {
        ImageRequest.Builder(context)
          .data(if (systemThumb) MediaThumb(file.uri) else file.uri)
          .allowRgb565(true) // half the memory for a thumbnail nobody inspects pixel by pixel
          .crossfade(false) // a fade per cell while a fling binds a dozen of them costs frames; the system thumbnail is quick enough to pop in
          .apply {
            if (hidden) transformations(PrivacyBlurTransformation())   // a blurred thumbnail keeps only its shrunk copy
            if (file.kind == FileKind.VIDEO && !systemThumb) {
              videoFrameMillis(frameMs ?: ((file.durationMs ?: 0L) / 4).coerceIn(0L, 3_000L))
            }
          }
          .build()
      }
      AsyncImage(
        model = request,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize(),
        onError = { if (systemThumb) fallback = true else failed = true },
      )
    }
    if (hidden) {
      LaunchedEffect(Unit) { blur.blurShown() }   // the first blurred picture explains itself, once (blurHintOnce)
      // A lock says "hidden, tap to show", and on a grid tile the words say it too (never a symbol alone). The tap itself is
      // wired by the cell or row that holds this thumbnail.
      Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(iconSize + 10.dp).clip(CircleShape).background(Color(0xB3000000)), contentAlignment = Alignment.Center) {
          Icon(Icons.Rounded.Lock, contentDescription = null, tint = Color.White, modifier = Modifier.size(iconSize - 8.dp))
        }
        if (hiddenWords) {
          Text(
            stringResource(R.string.preview_hidden_words),
            style = SunType.CaptionBold,
            color = Color.White,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp, start = 4.dp, end = 4.dp).clip(RoundedCornerShape(6.dp))
              .background(Color(0xB3000000)).padding(horizontal = 6.dp, vertical = 2.dp),
          )
        }
      }
    }
  }
}
