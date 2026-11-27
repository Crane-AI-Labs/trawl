package com.craneai.findr.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.ImageSearch
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
 * with the action ("Share school_fees_term2.pdf"). One component on the phone, the tablet and the quick panel. The pair stands
 * apart: at large text the two words touched and read as one phrase, "Share Copy" (UI audit P8, D19).
 */
@Composable
fun RowActions(name: String, onShare: () -> Unit, onCopy: () -> Unit, modifier: Modifier = Modifier) {
  // 8 dp at normal text, 16 dp at large text, where the words are twice as tall and 8 dp still read as one space.
  val gap = if (androidx.compose.ui.platform.LocalDensity.current.fontScale >= 1.29f) Sun.S16 else Sun.S8
  Row(modifier, horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.CenterVertically) {
    SunIconAction(Icons.Outlined.Share, stringResource(R.string.action_share), stringResource(R.string.action_share_file, name), onShare)
    SunIconAction(Icons.Outlined.ContentCopy, stringResource(R.string.action_copy), stringResource(R.string.action_copy_file, name), onCopy)
  }
}

/**
 * The one visible action on a photo, screenshot, meme or video tile. A dark disc keeps the white icon readable on any
 * picture, and the touch area is the full 56 dp ([Sun.Target], over the 48 dp minimum) even though the disc is 30 dp. [fileName]
 * is spoken with the action.
 */
@Composable
fun ShareBadge(onClick: () -> Unit, modifier: Modifier = Modifier, painter: Painter? = null, fileName: String? = null) {
  // With the file's name, so a screen of badges is not "Share, Share, Share" to TalkBack: "Share meme_1.jpg".
  val label = if (fileName != null) stringResource(R.string.action_share_file, fileName) else stringResource(R.string.action_share)
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

/** One action button in the uniform 2-column action grid inside [FileActionSheet]. */
private data class SheetActionSpec(
  val icon: ImageVector,
  val label: String,
  val onClick: () -> Unit,
  val primary: Boolean = false,
  val hint: String? = null,
)

/**
 * What a long-press or "More" opens on any result: the things a person does with a file,
 * laid out in a uniform 2-column grid of equal-width, equal-height buttons (`Modifier.weight(1f)`),
 * matching Desktop 1.1.0's action grid.
 */
@OptIn(ExperimentalMaterial3Api::class)
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
  /** In-app structured preview (JSON, CSV/Excel, code); null leaves the action out. */
  onPreview: (() -> Unit)? = null,
) {
  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    containerColor = MaterialTheme.colorScheme.background,
    tonalElevation = 0.dp,
  ) {
    val stacked = androidx.compose.ui.platform.LocalDensity.current.fontScale >= 1.3f
    Column(
      Modifier
        .fillMaxWidth()
        .verticalScroll(rememberScrollState())
        .padding(horizontal = Sun.Gutter)
        .padding(bottom = Sun.S16)
        .navigationBarsPadding(),
      verticalArrangement = Arrangement.spacedBy(Sun.S12),
    ) {
      Text(
        file.name,
        style = SunType.BodyLarge.copy(fontWeight = FontWeight.Bold),
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(horizontal = 4.dp),
      )
      val actions = buildList {
        add(SheetActionSpec(Icons.Outlined.Share, stringResource(R.string.action_share), onShare, primary = true))
        if (onPreview != null) {
          add(SheetActionSpec(Icons.Outlined.Description, stringResource(R.string.action_preview), onPreview))
        }
        add(SheetActionSpec(Icons.AutoMirrored.Outlined.OpenInNew, stringResource(R.string.action_open), onOpen))
        add(SheetActionSpec(Icons.Outlined.ContentCopy, stringResource(R.string.action_copy), onCopy))
        add(SheetActionSpec(Icons.Outlined.FolderOpen, stringResource(R.string.action_show_folder), onShowFolder))
        if (onFindSimilar != null && file.kind in SIMILAR_KINDS) {
          add(SheetActionSpec(Icons.Outlined.ImageSearch, stringResource(R.string.action_find_similar), onFindSimilar, hint = stringResource(R.string.action_find_similar_help)))
        }
        // User marking is one of the only two ways a file becomes sensitive (the other is a private folder). Never a guess.
        // A file that is sensitive only because of its folder is un-hidden by changing the folder, not here.
        if (!file.sensitive) {
          add(SheetActionSpec(Icons.Outlined.VisibilityOff, stringResource(R.string.action_mark_sensitive), { onMarkSensitive(true) }))
        } else if (file.markedSensitive) {
          add(SheetActionSpec(Icons.Outlined.Visibility, stringResource(R.string.action_unmark_sensitive), { onMarkSensitive(false) }))
        }
      }
      Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Sun.S8)) {
        if (stacked) {
          actions.forEach { action ->
            SheetAction(action, Modifier.fillMaxWidth().heightIn(min = Sun.Target))
          }
        } else {
          actions.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Sun.S8)) {
              pair.forEach { action ->
                SheetAction(action, Modifier.weight(1f).heightIn(min = Sun.Target))
              }
              if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
          }
        }
      }
    }
  }
}

@Composable
private fun SheetAction(action: SheetActionSpec, modifier: Modifier = Modifier) {
  val t = LocalSunlight.current
  val bg = if (action.primary) t.action else t.paper
  val fg = if (action.primary) t.onAction else t.ink
  val shape = MaterialTheme.shapes.small
  Row(
    modifier
      .clip(shape)
      .background(bg, shape)
      .border(Sun.Border, if (action.primary) t.action else t.line, shape)
      .clickable(role = Role.Button, onClick = action.onClick)
      .padding(horizontal = Sun.S12, vertical = Sun.S8)
      .semantics(mergeDescendants = true) {
        contentDescription = if (action.hint == null) action.label else "${action.label}. ${action.hint}"
      },
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(Sun.S8),
  ) {
    Icon(action.icon, contentDescription = null, tint = fg, modifier = Modifier.size(20.dp))
    Text(
      action.label,
      style = SunType.Label,
      color = fg,
      maxLines = 2,
      overflow = TextOverflow.Ellipsis,
      textAlign = TextAlign.Start,
      modifier = Modifier.weight(1f).clearAndSetSemantics { },
    )
  }
}
