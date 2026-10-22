package com.craneai.findr.ui.common

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Share
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import com.craneai.findr.core.FileKind

/**
 * One painter per icon for every cell of the media grid. A VectorPainter rasterises into a bitmap of its own, so the
 * painter per cell that `Icon(imageVector)` makes meant a new texture upload for every newly composed cell: on the A06,
 * ~3,700 small uploads in a minute of flinging (`Bitmap#prepareToDraw` 32x32 share badges and 57x57 kind icons in a
 * perfetto trace, the research notes). Shared, each icon is rasterised and uploaded once. Only for cells drawn at one size
 * (a shared painter drawn at two sizes would rasterise again on every draw).
 */
@Stable
class SharedIcons(private val kinds: Map<FileKind, Painter>, val share: Painter) {
  fun kind(kind: FileKind): Painter = kinds.getValue(kind)
}

@Composable
fun rememberSharedIcons(): SharedIcons {
  val kinds = FileKind.entries.associateWith { rememberVectorPainter(it.icon()) }   // fixed order, so stable in composition
  val share = rememberVectorPainter(Icons.Outlined.Share)
  return remember(share, *kinds.values.toTypedArray()) { SharedIcons(kinds, share) }
}
