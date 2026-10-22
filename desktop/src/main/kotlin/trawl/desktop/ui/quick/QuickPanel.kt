package trawl.desktop.ui.quick

import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import trawl.desktop.ui.SunPanel
import trawl.desktop.ui.SunDivider
import trawl.desktop.ui.SnippetText
import trawl.desktop.ui.PathText
import trawl.desktop.ui.KindTile
import trawl.desktop.ui.KeyCap
import trawl.desktop.ui.Badge
import com.craneai.findr.ui.common.formatBytes
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.shadow
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.craneai.findr.core.FileKind
import com.craneai.findr.ui.theme.LocalSunlight
import java.io.File
import trawl.desktop.Os
import trawl.desktop.Thumbnails
import trawl.desktop.privacy.PreviewBlur
import trawl.desktop.privacy.PrivateThumbnails
import com.craneai.findr.core.PrivateLook
import trawl.desktop.t
import trawl.desktop.tn
import trawl.desktop.shell.QuickHit
import trawl.desktop.shell.QuickKey
import trawl.desktop.shell.QuickPanelState
import trawl.desktop.ui.A11yIconButton
import trawl.desktop.ui.Icons
import trawl.desktop.ui.Sun
import trawl.desktop.ui.SunType
import trawl.desktop.ui.a11yClickable
import trawl.desktop.ui.a11yLabel
import trawl.desktop.ui.a11yLive
import trawl.desktop.ui.kindLabel

/**
 * What a key press means in the panel, or null to leave it to the text field. Pure (no window), so it is tested directly.
 * Open on Enter; show in folder on Command+Enter (Mac) or Ctrl+Enter (others); Esc closes; Up and Down move; Ctrl+N and Ctrl+P are not claimed.
 */
fun quickKeyFor(key: Key, down: Boolean, ctrl: Boolean, meta: Boolean, os: Os): QuickKey? {
  if (!down) return null
  return when (key) {
    Key.DirectionDown -> QuickKey.DOWN
    Key.DirectionUp -> QuickKey.UP
    Key.PageDown -> QuickKey.LAST
    Key.PageUp -> QuickKey.FIRST
    Key.Escape -> QuickKey.CLOSE
    Key.Enter, Key.NumPadEnter -> if (if (os == Os.MAC) meta else ctrl) QuickKey.SHOW_IN_FOLDER else QuickKey.OPEN
    else -> null
  }
}

private fun KeyEvent.toQuickKey(os: Os): QuickKey? = quickKeyFor(key, type == KeyEventType.KeyDown, isCtrlPressed, isMetaPressed, os)

/**
 * The quick panel: a search box, a short list, one footer line. Keys are caught before the text field sees them, so Up and Down move the
 * selection instead of the cursor while typing continues to work. [focusNonce] changes each time the panel is shown, to put the cursor
 * back in the box.
 *
 * Styled from [SunPanel], the same tokens the main window's laptop layout uses: a rounded paper surface with a hairline edge and a soft
 * shadow, the 44 dp search field, and rows with the main window's name / badge / folder / match layout. [inset] is the transparent margin
 * the shadow is drawn in; the window passes 0 when the desktop cannot draw a see-through window (a square panel with a 2 dp edge instead).
 */
@Composable
fun QuickPanel(
  state: QuickPanelState, os: Os, shortcut: String?, focusNonce: Int = 0, modifier: Modifier = Modifier, inset: Dp = SunPanel.ShadowInset,
  /** Whether the blur is on (the setting and the Privacy pack). A picture that looks private is then shown blurred, with no name and no excerpt. */
  blurOn: Boolean = false,
  /** A click in the transparent margin around the panel (outside the card) calls this; the window passes "hide". */
  onDismiss: (() -> Unit)? = null,
) {
  val locked by state.locked.collectAsState()
  // The panel's own bar is lower than the main window's (it opens over other apps) and it has no eye: nothing is revealed here.
  val blur = remember(blurOn) { PreviewBlur(enabled = blurOn, allowReveal = { false }, threshold = PrivateLook.THRESHOLD) }
  val tk = LocalSunlight.current
  val query by state.query.collectAsState()
  val hits by state.hits.collectAsState()
  val selected by state.selected.collectAsState()
  val answered by state.answered.collectAsState()
  val focus = remember { FocusRequester() }
  var fieldFocused by remember { mutableStateOf(true) }
  val list = rememberLazyListState()
  LaunchedEffect(focusNonce) { runCatching { focus.requestFocus() } }
  LaunchedEffect(selected, hits) { if (hits.isNotEmpty()) list.scrollToItem(selected.coerceIn(0, hits.lastIndex)) }   // no animation: nothing to reduce

  val floating = inset > 0.dp
  val shape = RoundedCornerShape(if (floating) SunPanel.Corner else 0.dp)
  Box(
    modifier.fillMaxSize()
      .then(if (floating) Modifier.background(SunPanel.MarginScrim) else Modifier)
      .then(if (floating && onDismiss != null) Modifier.pointerInput(onDismiss) { detectTapGestures { onDismiss() } } else Modifier)
      .padding(inset),
  ) {
    Column(
      Modifier.fillMaxSize()
        .then(if (floating) Modifier.shadow(SunPanel.Elevation, shape, clip = false, ambientColor = SunPanel.ShadowAmbient, spotColor = SunPanel.ShadowSpot) else Modifier).clip(shape)
        // a click on the card itself (not on a row or the field) is the card's own: it must not reach the margin handler behind it
        .pointerInput(Unit) { detectTapGestures { } }
        .background(MaterialTheme.colorScheme.background).border(if (floating) SunPanel.Edge else Sun.Border, tk.line2, shape)
        .onPreviewKeyEvent { e -> e.toQuickKey(os)?.let(state::onKey) ?: false },
    ) {
      val fieldShape = RoundedCornerShape(12.dp)
      Row(
        Modifier.fillMaxWidth().padding(SunPanel.Pad).heightIn(min = Sun.Field).clip(fieldShape).background(tk.paper)
          .border(if (fieldFocused) 3.dp else Sun.Border, if (fieldFocused) tk.focus else tk.line, fieldShape).padding(start = 14.dp, end = Sun.S8),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Icon(Icons.Search, contentDescription = null, tint = tk.ink, modifier = Modifier.size(Sun.Icon))
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f).heightIn(min = Sun.Field - 8.dp), contentAlignment = Alignment.CenterStart) {
          if (query.isEmpty()) Text(t("quick_hint"), style = SunType.BodyLarge, color = tk.ink2)
          BasicTextField(
            value = query, onValueChange = state::setQuery, singleLine = true,
            textStyle = SunType.BodyLarge.copy(color = tk.ink), cursorBrush = SolidColor(tk.ink),
            modifier = Modifier.fillMaxWidth().focusRequester(focus).onFocusChanged { fieldFocused = it.isFocused }.a11yLabel(t("quick_hint")),
          )
        }
        if (query.isNotEmpty()) A11yIconButton(Icons.Close, t("quick_clear"), { state.setQuery(""); runCatching { focus.requestFocus() } })
        else KeyCap("Esc", Modifier.padding(end = Sun.S4))
      }

      val status = when {
        locked -> t("quick_locked")
        answered == null -> ""
        query.isBlank() -> if (hits.isEmpty()) "" else t("quick_recent")
        answered != query -> ""   // the answer on screen is for an older query: say nothing rather than something wrong
        hits.isEmpty() -> t("quick_none", query.trim())
        else -> tn("quick_results", hits.size, hits.size)
      }
      // G4: how many of the rows are blurred pictures. A note, not a control: nothing is revealed from here (open Trawl for that).
      val hiddenN = if (locked) 0 else QuickHidden.count(hits.map { it.file }, blur)
      Row(
        Modifier.fillMaxWidth().padding(horizontal = SunPanel.Pad + Sun.S8, vertical = Sun.S2).heightIn(min = 20.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Sun.S8),
      ) {
        Text(status, style = SunType.Caption, color = tk.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).a11yLive())
        if (hiddenN > 0) QuickHiddenPill(hiddenN)
      }

      LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, contentPadding = PaddingValues(horizontal = Sun.S8, vertical = Sun.S4)) {
        // Locked: no rows at all (the search answers nothing, and this makes sure a stale list is never drawn for a frame).
        if (!locked) itemsIndexed(hits, key = { _, h -> h.file.id }) { i, hit -> QuickRow(hit, i == selected, blur.hides(hit.file), onClick = { state.openAt(i) }) }
      }

      SunDivider()
      val mod = if (os == Os.MAC) "⌘" else "Ctrl"   // the key caps a Mac shows, as in the main window (MainUi.modName)
      Text(
        if (shortcut != null) t("quick_footer_key", mod, shortcut) else t("quick_footer", mod), style = SunType.Caption, color = tk.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().background(tk.paper2).padding(horizontal = SunPanel.Pad + Sun.S8, vertical = Sun.S8),
      )
    }
  }
}

/** The folder a hit lives in, as a person says it ("~/Documents/Tax"), from the file's real location rather than the folder it was found under. */
internal fun quickFolder(uri: String, fallback: String): String =
  runCatching { File(java.net.URI(uri)).parent }.getOrNull()?.let { PathText.tilde(it) }?.takeIf { it.isNotBlank() } ?: fallback

@Composable
private fun QuickRow(hit: QuickHit, selected: Boolean, hidden: Boolean, onClick: () -> Unit) {
  val tk = LocalSunlight.current
  val shape = RoundedCornerShape(10.dp)
  val where = quickFolder(hit.file.uri, hit.file.relativePath.substringBeforeLast('/', ""))
  val source = remember { MutableInteractionSource() }
  val hovered by source.collectIsHoveredAsState()
  val kind = hit.file.kind
  val snippet = if (hidden) null else hit.snippet?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotBlank() }
  Row(
    Modifier.fillMaxWidth().padding(vertical = 1.dp).heightIn(min = SunPanel.Row).clip(shape).hoverable(source)
      .background(if (selected || hovered) tk.paper2 else Color.Transparent, shape)
      .then(if (selected) Modifier.border(3.dp, tk.focus, shape) else Modifier)
      .a11yClickable(if (hidden) t("quick_row_hidden", kindLabel(kind), where) else t("quick_row", hit.file.name, kindLabel(kind), where), shape, onClick)
      .semantics { this.selected = selected }
      .padding(horizontal = Sun.S12, vertical = Sun.S8),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    RowThumb(hit, hidden)
    Spacer(Modifier.width(Sun.S12))
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Sun.S8)) {
        Text(if (hidden) t("hidden_label") else hit.file.name, style = SunType.Label, color = tk.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        if (kind == FileKind.PDF || kind == FileKind.VIDEO || kind == FileKind.AUDIO) Badge(kindLabel(kind))
      }
      Text(
        listOfNotNull(where.takeIf { it.isNotBlank() }, formatBytes(hit.file.sizeBytes)).joinToString("  ·  "),
        style = SunType.Caption, color = tk.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis,
      )
      if (snippet != null) SnippetText(snippet, lines = 1, modifier = Modifier.padding(top = 2.dp), style = SunType.Caption)
    }
  }
}

/** A small picture for photos, an icon tile for the rest (the main window's tile). Decoration: the row carries the name. */
@Composable
private fun RowThumb(hit: QuickHit, hidden: Boolean) {
  val tk = LocalSunlight.current
  val isPicture = hit.file.kind == FileKind.IMAGE || hit.file.kind == FileKind.SCREENSHOT
  // A hidden picture goes through PrivateThumbnails, which keeps only the blurred pixels (Thumbnails would cache the sharp ones).
  val bmp by produceState<ImageBitmap?>(null, hit.file.id, hidden) {
    value = if (isPicture) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { PrivateThumbnails.load(File(java.net.URI(hit.file.uri)), 96, blurred = hidden) }.getOrNull() } else null
  }
  val b = bmp
  if (b == null) { KindTile(hit.file.kind); return }
  val shape = RoundedCornerShape(8.dp)
  Box(Modifier.size(Sun.Thumb).clip(shape).background(tk.paper2).border(Sun.Hairline, tk.line2, shape), contentAlignment = Alignment.Center) {
    Image(b, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
  }
}

/** The "N hidden" count in the quick panel: the rows whose picture is blurred (the panel has no eye, so none can be shown here). */
object QuickHidden {
  fun count(files: Collection<com.craneai.findr.core.IndexedFile>, blur: PreviewBlur): Int = blur.hiddenCount(files)
}

/** A quiet pill with the closed eye and "3 hidden", read aloud with where to go to show them. */
@Composable
private fun QuickHiddenPill(count: Int) {
  val tk = LocalSunlight.current
  Row(
    Modifier.clip(androidx.compose.foundation.shape.CircleShape).background(tk.paper).border(1.dp, tk.line2, androidx.compose.foundation.shape.CircleShape)
      .padding(horizontal = 8.dp, vertical = 1.dp).a11yLabel(tn("quick_hidden_note", count, count)),
    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    Icon(trawl.desktop.ui.privacy.PrivacyIcons.EyeOff, contentDescription = null, tint = tk.ink, modifier = Modifier.size(14.dp))
    Text(tn("hidden_chip", count), style = SunType.CaptionBold, color = tk.ink, maxLines = 1)
  }
}
