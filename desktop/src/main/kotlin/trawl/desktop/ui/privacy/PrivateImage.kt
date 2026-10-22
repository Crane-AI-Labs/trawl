package trawl.desktop.ui.privacy

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.craneai.findr.core.IndexedFile
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import trawl.desktop.privacy.LocalPreviewBlur
import trawl.desktop.privacy.PrivateThumbnails
import androidx.compose.runtime.CompositionLocalProvider

/**
 * A picture for the grid or the preview pane that is blurred when [LocalPreviewBlur] says so. Use it where `Thumbnails.load` was called.
 *
 * The bitmap holder is keyed on whether the picture is hidden, so the moment a picture is covered the sharp bitmap is gone from
 * this composable (a stale one is never drawn while the blurred one loads), and a blurred picture is decoded through
 * [PrivateThumbnails], which keeps only the blurred pixels.
 */
@Composable
fun PrivateImage(
  row: IndexedFile,
  file: File,
  side: Int,
  modifier: Modifier = Modifier,
  contentScale: ContentScale = ContentScale.Crop,
  contentDescription: String? = null,
  placeholder: @Composable () -> Unit = {},
) {
  val blur = LocalPreviewBlur.current
  val hidden = blur.hides(row)
  // Start from the picture if it is already decoded (a tile scrolling back into view must not flash empty), else from the placeholder.
  var bitmap by remember(row.id, file, hidden, side) { mutableStateOf(PrivateThumbnails.peek(file, side, blurred = hidden)) }
  LaunchedEffect(row.id, file, hidden, side) {
    if (hidden) blur.blurShown()
    if (bitmap == null) bitmap = withContext(Dispatchers.IO) { runCatching { PrivateThumbnails.load(file, side, blurred = hidden) }.getOrNull() }
  }
  val shown = bitmap
  // The placeholder (a tinted tile and the kind's icon) fades into the picture rather than snapping, and is never an empty white box.
  Crossfade(shown, modifier, animationSpec = tween(120), label = "thumb") { b ->
    if (b != null) Image(b, contentDescription, Modifier.fillMaxSize(), contentScale = contentScale) else placeholder()
  }
}

/**
 * Runs an action that hands a file to something outside Trawl (open, copy a path) now, or after the "leave" dialog when the file is sensitive.
 * Use it as `leave(file.id) { c.open(file) }`; with no privacy UI around (a test) it just runs the action.
 */
@Composable
fun rememberLeave(): (Long, () -> Unit) -> Unit {
  val ui = LocalPrivacyUi.current
  return { id, action -> if (ui == null) action() else ui.leave(listOf(id), action) }
}

/** Gives a screen's rows and preview the blur [ui] built for them. */
@Composable
fun WithPrivacy(ui: PrivacyUi, packInstalled: Boolean, content: @Composable () -> Unit) {
  val blur = ui.rememberBlur(packInstalled)
  CompositionLocalProvider(LocalPreviewBlur provides blur, LocalPrivacyUi provides ui) { content() }
}
