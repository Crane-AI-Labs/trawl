package com.craneai.findr.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import com.craneai.findr.ui.theme.LocalSunlight
import com.craneai.findr.ui.theme.Sun
import com.craneai.findr.ui.theme.SunIconAction
import com.craneai.findr.ui.theme.SunType
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.ImageSearch
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.craneai.findr.R
import com.craneai.findr.core.FileKind
import com.craneai.findr.core.IndexedFile

/** The kinds "Find similar" works on: pictures and videos (their picture vectors). Documents are left out. */
private val SIMILAR_KINDS = setOf(FileKind.IMAGE, FileKind.SCREENSHOT, FileKind.VIDEO)

/**
 * Share and Copy at the end of a document or audio row: 56 dp each, the word under the icon, and TalkBack hears the file's name
 * with the action ("Share school_fees_term2.pdf").
 */
@Composable
fun RowActions(name: String, onShare: () -> Unit, onCopy: () -> Unit, modifier: Modifier = Modifier) {
  Row(modifier, verticalAlignment = Alignment.CenterVertically) {
    SunIconAction(Icons.Outlined.Share, stringResource(R.string.action_share), stringResource(R.string.action_share_file, name), onShare)
    SunIconAction(Icons.Outlined.ContentCopy, stringResource(R.string.action_copy), stringResource(R.string.action_copy_file, name), onCopy)
  }
}

/**
 * The one visible action on a photo, screenshot, meme or video tile. A dark disc keeps the white icon readable on any
 * picture, and the touch area is the full 48 dp even though the disc is smaller.
 */
@Composable
fun ShareBadge(onClick: () -> Unit, modifier: Modifier = Modifier, painter: Painter? = null) {
  val label = stringResource(R.string.action_share)
  if (painter != null) {
    // In the grid: one node that draws its disc and icon, instead of three nested ones per cell. 56 dp to touch, 30 dp to see.
    Box(
      modifier
        .size(Sun.Target)
        .clip(CircleShape)
        .clickable(onClickLabel = label, role = Role.Button, onClick = onClick)
        .semantics { contentDescription = label }
        .drawBehind {
          drawCircle(BADGE_DISC, radius = 15.dp.toPx())
          val side = 17.dp.toPx()
          translate((size.width - side) / 2, (size.height - side) / 2) {
            with(painter) { draw(Size(side, side), colorFilter = BADGE_TINT) }
          }
        },
    )
    return
  }
  Box(
    modifier
      .size(Sun.Target)
      .clip(CircleShape)
      .clickable(onClickLabel = label, role = Role.Button, onClick = onClick)
      .semantics { contentDescription = label },
    contentAlignment = Alignment.Center,
  ) {
    Box(Modifier.size(30.dp).clip(CircleShape).background(Color(0xB3000000)), contentAlignment = Alignment.Center) {
      Icon(Icons.Outlined.Share, contentDescription = null, tint = Color.White, modifier = Modifier.size(17.dp))
    }
  }
}

private val BADGE_DISC = Color(0xB3000000)
private val BADGE_TINT = ColorFilter.tint(Color.White)

/** What a long-press opens on any result: the things a person does with a file, as icons with their word under them. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FileActionSheet(
  file: IndexedFile,
  onShare: () -> Unit,
  onCopy: () -> Unit,
  onOpen: () -> Unit,
  onShowFolder: () -> Unit,
  onMarkSensitive: (Boolean) -> Unit,
  onDismiss: () -> Unit,
  /** "Find similar" for a photo, screenshot or video; null leaves the action out. */
  onFindSimilar: (() -> Unit)? = null,
) {
  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    containerColor = MaterialTheme.colorScheme.background,
    tonalElevation = 0.dp,
  ) {
    Column(Modifier.padding(horizontal = Sun.Gutter).padding(bottom = Sun.S16).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(Sun.S12)) {
      Text(
        file.name,
        style = SunType.BodyLarge.copy(fontWeight = FontWeight.Bold),
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(horizontal = 4.dp),
      )
      FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalArrangement = Arrangement.spacedBy(Sun.S8)) {
        SheetAction(Icons.Outlined.Share, stringResource(R.string.action_share), onShare, primary = true)
        SheetAction(Icons.Outlined.ContentCopy, stringResource(R.string.action_copy), onCopy)
        SheetAction(Icons.Outlined.OpenInNew, stringResource(R.string.action_open), onOpen)
        SheetAction(Icons.Outlined.FolderOpen, stringResource(R.string.action_show_folder), onShowFolder)
        if (onFindSimilar != null && file.kind in SIMILAR_KINDS) SheetAction(Icons.Outlined.ImageSearch, stringResource(R.string.action_find_similar), onFindSimilar, hint = stringResource(R.string.action_find_similar_help))
        // User marking is one of the only two ways a file becomes sensitive (the other is a private folder). Never a guess.
        // A file that is sensitive only because of its folder is un-hidden by changing the folder, not here.
        if (!file.sensitive) {
          SheetAction(Icons.Outlined.VisibilityOff, stringResource(R.string.action_mark_sensitive), { onMarkSensitive(true) })
        } else if (file.markedSensitive) {
          SheetAction(Icons.Outlined.Visibility, stringResource(R.string.action_unmark_sensitive), { onMarkSensitive(false) })
        }
      }
    }
  }
}

@Composable
private fun SheetAction(icon: ImageVector, label: String, onClick: () -> Unit, primary: Boolean = false, hint: String? = null) {
  val t = LocalSunlight.current
  Column(
    Modifier
      .widthIn(min = 72.dp)
      .clip(MaterialTheme.shapes.small)
      .clickable(role = Role.Button, onClick = onClick)
      .padding(horizontal = 6.dp, vertical = 8.dp)
      .semantics(mergeDescendants = true) { contentDescription = if (hint == null) label else "$label. $hint" },
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    // The main action is the strongest thing on the sheet: an ink disc. The others are outlined.
    val bg = if (primary) t.action else t.paper
    val fg = if (primary) t.onAction else t.ink
    Box(
      Modifier.size(Sun.Target).clip(CircleShape).background(bg).border(Sun.Border, t.line, CircleShape),
      contentAlignment = Alignment.Center,
    ) {
      Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(Sun.Icon))
    }
    Text(label, style = SunType.CaptionBold, color = t.ink, textAlign = TextAlign.Center, modifier = Modifier.clearAndSetSemantics { })
  }
}
