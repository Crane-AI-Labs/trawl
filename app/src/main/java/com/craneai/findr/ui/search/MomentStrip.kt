package com.craneai.findr.ui.search

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.craneai.findr.R
import com.craneai.findr.core.IndexedFile
import com.craneai.findr.core.VideoStrip
import com.craneai.findr.ui.common.GridFling
import com.craneai.findr.ui.common.VideoStripLoader
import com.craneai.findr.ui.common.formatDuration
import com.craneai.findr.ui.theme.LocalSunlight
import com.craneai.findr.ui.theme.Sun
import com.craneai.findr.ui.theme.SunType
import kotlinx.coroutines.delay

/**
 * The pictures of one strip as they arrive: the plan (which moments) and one slot per picture, empty until the decoder delivers it.
 * Written from the loader's threads (snapshot state is safe for that), read by the strip.
 */
@Stable
class StripFrames(val plan: VideoStrip.Plan) {
  val bitmaps = mutableStateListOf<Bitmap?>().also { list -> repeat(plan.slots.size) { list.add(null) } }
}

/** How many empty tiles stand in while the plan is being made (the same five a strip usually has, so nothing jumps). */
private const val PLACEHOLDER_TILES = 5

/**
 * Fetches the strip's pictures for [file] at its matched moment [momentMs] while this is on screen, and gives them up the moment
 * it scrolls away (the effect is cancelled with the item). Null until the plan is made.
 *
 * Held back, in this order: [enabled] false (typing, choosing files, a short window: nothing is fetched); a short debounce, so a
 * row flicked past decodes nothing ([debounce] false for the sheet, which the person asked for); and a fast fling
 * ([GridFling.fast], the same rule the thumbnails follow). Blurred files ([hidden]) get blurred pictures only.
 */
@Composable
internal fun rememberStripFrames(file: IndexedFile, momentMs: Long, hidden: Boolean, enabled: Boolean, debounce: Boolean = true): StripFrames? {
  val context = LocalContext.current
  // Back on screen with the pictures still in memory: no empty tiles, no wait.
  var frames by remember(file.uri, momentMs, hidden, enabled) {
    mutableStateOf(
      if (!enabled) null else VideoStripLoader.cachedPlan(file, momentMs)?.let { plan ->
        StripFrames(plan).also { f -> plan.slots.forEachIndexed { i, slot -> f.bitmaps[i] = VideoStripLoader.cachedFrame(file, slot, hidden) } }
      },
    )
  }
  if (enabled) {
    LaunchedEffect(file.uri, momentMs, hidden) {
      val done = frames?.bitmaps?.all { it != null } == true
      if (done) return@LaunchedEffect
      if (debounce) delay(VideoStripLoader.DEBOUNCE_MS)
      while (GridFling.fast) delay(32)
      var holder: StripFrames? = null
      VideoStripLoader.load(
        context, file, momentMs, hidden,
        onPlan = { plan -> holder = StripFrames(plan).also { frames = it } },
        onFrame = { i, bitmap -> holder?.bitmaps?.set(i, bitmap) },
      )
    }
  }
  return frames
}

/** Whether a strip should take room: yes while it is being made, and when the plan found at least two different pictures. */
internal fun StripFrames?.takesRoom(): Boolean = this == null || plan.worthShowing

/**
 * Five small pictures from just before to just after a matched moment in a video, like a scrub bar. The picture nearest the match
 * carries its time on the marker (a fill under ink, as everywhere in Sunlight). Tapping a picture opens the video at the time that
 * picture is from ([onOpenAt]), so a timestamp that is a few seconds off still lands the eye on the right spot.
 *
 * While the pictures load the strip shows empty tiles of the same size, so a row never changes height when they arrive.
 * [hidden]: the pictures are blurred ("Blur private-looking previews"); a tap shows them ([onReveal]) instead of opening the video.
 */
@Composable
internal fun MomentStrip(
  frames: StripFrames?,
  momentMs: Long,
  hidden: Boolean,
  onOpenAt: (Long) -> Unit,
  onReveal: () -> Unit,
  modifier: Modifier = Modifier,
  tileHeight: Dp = Sun.Target,
) {
  val t = LocalSunlight.current
  val plan = frames?.plan
  val around = stringResource(R.string.strip_group, formatDuration(momentMs))
  Row(
    modifier
      .widthIn(max = STRIP_MAX_WIDTH)   // before fillMaxWidth: the cap has to bound the fill, not be overridden by it
      .fillMaxWidth()
      .semantics { contentDescription = around },
    horizontalArrangement = Arrangement.spacedBy(Sun.S4),
  ) {
    val count = plan?.slots?.size ?: PLACEHOLDER_TILES
    for (i in 0 until count) {
      val slot = plan?.slots?.get(i)
      val bitmap = frames?.bitmaps?.get(i)
      val isMatch = plan != null && i == plan.anchor
      Box(
        Modifier
          .weight(1f)
          .height(tileHeight)
          .clip(MaterialTheme.shapes.extraSmall)
          .background(t.paper2)
          .then(
            if (slot != null && bitmap != null) {
              val at = formatDuration(slot.atMs)
              val label = stringResource(if (isMatch) R.string.strip_open_at_match else R.string.strip_open_at, at)
              val showLabel = stringResource(R.string.action_show_picture)
              val hiddenNote = stringResource(R.string.preview_hidden)
              Modifier
                .clickable(onClickLabel = if (hidden) showLabel else label, role = Role.Button) { if (hidden) onReveal() else onOpenAt(slot.atMs) }
                .semantics { contentDescription = if (hidden) "$label. $hiddenNote" else label }
            } else Modifier,
          ),
      ) {
        if (bitmap != null) {
          Image(bitmap.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
          if (isMatch && slot != null) {
            Text(
              formatDuration(slot.atMs),
              style = SunType.CaptionBold,
              color = t.onMarker,
              maxLines = 1,
              overflow = TextOverflow.Clip,
              modifier = Modifier.align(Alignment.BottomStart).padding(4.dp).clip(MaterialTheme.shapes.extraSmall).background(t.marker).padding(horizontal = 4.dp),
            )
          }
        }
      }
    }
  }
}

/** Wide windows: tiles stop growing at about 100 dp so the strip stays a strip. */
private val STRIP_MAX_WIDTH = 520.dp

/**
 * A video found by what it shows has a tile in the grid and no room for a strip on it; its time label opens this sheet instead.
 * The same strip, bigger, under the file name, with the hint that a tap opens the video at that spot.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MomentPeekSheet(
  file: IndexedFile,
  momentMs: Long,
  hidden: Boolean,
  onOpenAt: (Long) -> Unit,
  onReveal: () -> Unit,
  onDismiss: () -> Unit,
) {
  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    containerColor = MaterialTheme.colorScheme.background,
    tonalElevation = 0.dp,
  ) {
    MomentPeekContent(file, momentMs, hidden, rememberStripFrames(file, momentMs, hidden, enabled = true, debounce = false), onOpenAt, onReveal)
  }
}

/** The sheet's body, apart from the sheet itself so a screenshot test can draw it. */
@Composable
internal fun MomentPeekContent(
  file: IndexedFile,
  momentMs: Long,
  hidden: Boolean,
  frames: StripFrames?,
  onOpenAt: (Long) -> Unit,
  onReveal: () -> Unit,
) {
  Column(
    Modifier.padding(horizontal = Sun.Gutter).padding(bottom = Sun.S16).navigationBarsPadding(),
    verticalArrangement = Arrangement.spacedBy(Sun.S12),
  ) {
    Text(
      file.name,
      style = SunType.BodyLarge.copy(fontWeight = FontWeight.Bold),
      color = MaterialTheme.colorScheme.onBackground,
      maxLines = 2,
      overflow = TextOverflow.MiddleEllipsis,
    )
    Text(stringResource(R.string.strip_peek_around, formatDuration(momentMs)), style = SunType.Body, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (frames.takesRoom()) MomentStrip(frames, momentMs, hidden, onOpenAt, onReveal, Modifier.fillMaxWidth(), tileHeight = 80.dp)
    Text(stringResource(R.string.strip_peek_hint), style = SunType.Body, color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
}
