package com.craneai.findr.ui.common

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Screenshot
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.craneai.findr.R
import com.craneai.findr.core.FileKind
import com.craneai.findr.core.KindFilter
import com.craneai.findr.ui.theme.LocalSunlight

/** Each kind of file has one icon, so a placeholder tells you what it is before the thumbnail loads. */
fun FileKind.icon(): ImageVector = when (this) {
  FileKind.DOCUMENT -> Icons.Outlined.Description
  FileKind.PDF -> Icons.Outlined.PictureAsPdf
  FileKind.IMAGE -> Icons.Outlined.Image
  FileKind.SCREENSHOT -> Icons.Outlined.Screenshot
  FileKind.VIDEO -> Icons.Outlined.Videocam
  FileKind.AUDIO -> Icons.Outlined.AudioFile
  FileKind.OTHER -> Icons.AutoMirrored.Outlined.InsertDriveFile
}

@StringRes
fun FileKind.labelRes(): Int = when (this) {
  FileKind.DOCUMENT -> R.string.kind_document
  FileKind.PDF -> R.string.kind_pdf
  FileKind.IMAGE -> R.string.kind_photo
  FileKind.SCREENSHOT -> R.string.kind_screenshot
  FileKind.VIDEO -> R.string.kind_video
  FileKind.AUDIO -> R.string.kind_audio
  FileKind.OTHER -> R.string.kind_other
}

@StringRes
fun KindFilter.labelRes(): Int = when (this) {
  KindFilter.ALL -> R.string.filter_all
  KindFilter.PHOTOS -> R.string.filter_photos
  KindFilter.SCREENSHOTS -> R.string.filter_screenshots
  KindFilter.VIDEOS -> R.string.filter_videos
  KindFilter.DOCUMENTS -> R.string.filter_documents
  KindFilter.AUDIO -> R.string.filter_audio
}

/** Whether a kind belongs in the thumbnail grid (looked at) as opposed to a row (read). */
fun FileKind.isVisual(): Boolean = this == FileKind.IMAGE || this == FileKind.SCREENSHOT || this == FileKind.VIDEO

class KindTone(val container: Color, val content: Color)

/**
 * The placeholder under a thumbnail: Sunlight has one colour, so every kind gets the same quiet tone (paper-2 with the
 * kind's icon in ink-2). The icon, not a colour, says what the file is.
 */
@Composable
fun FileKind.tone(): KindTone {
  val t = LocalSunlight.current
  return KindTone(t.paper2, t.ink2)
}

/** The icon on each filter tile. */
fun KindFilter.icon(): ImageVector = when (this) {
  KindFilter.ALL -> Icons.Outlined.GridView
  KindFilter.PHOTOS -> Icons.Outlined.Image
  KindFilter.SCREENSHOTS -> Icons.Outlined.Screenshot
  KindFilter.VIDEOS -> Icons.Outlined.Videocam
  KindFilter.DOCUMENTS -> Icons.Outlined.Description
  KindFilter.AUDIO -> Icons.Outlined.Mic
}
