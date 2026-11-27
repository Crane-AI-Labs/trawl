package trawl.desktop.ui.quick

import androidx.compose.foundation.Image
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.defaultScrollbarStyle
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.ui.input.pointer.pointerInput
import trawl.desktop.ui.SunPanel
import trawl.desktop.ui.SunDivider
import trawl.desktop.ui.SnippetText
import trawl.desktop.ui.PathText
import trawl.desktop.ui.KindTile
import trawl.desktop.ui.KeyCap
import trawl.desktop.ui.Badge
import trawl.desktop.ui.dragFiles
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
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
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
import androidx.compose.ui.input.key.isShiftPressed
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

private val WHITESPACE = Regex("\\s+")
private val PANEL_SHAPE = RoundedCornerShape(SunPanel.Corner)
private val SQUARE_SHAPE = RoundedCornerShape(0.dp)
private val FIELD_SHAPE = RoundedCornerShape(12.dp)
private val ROW_SHAPE = RoundedCornerShape(12.dp)
private val THUMB_SHAPE = RoundedCornerShape(SunPanel.ThumbRadius)

/** Bounded decode pool for quick-panel thumbnails so rapid typing never spawns dozens of concurrent JPEG decodes. */
@OptIn(ExperimentalCoroutinesApi::class)
private val quickThumbDispatcher = Dispatchers.IO.limitedParallelism(4)

/**
 * What a key press means in the panel, or null to leave it to the text field. Pure (no window), so it is tested directly.
 * Open on Enter (full Trawl search for an un-selected query, or selected file after arrowing / on Recent); open the top hit directly on
 * Shift+Enter; show in folder on Command+Enter (Mac) or Ctrl+Enter (others); Esc closes; Up and Down move; Ctrl+N and Ctrl+P are not claimed.
 * Preview: Space while the search box is empty (a space in a query is a space), and Command+Y (Mac) or Ctrl+Y anywhere; with Shift the same
 * key asks for the system's full preview (macOS Quick Look). [previewOpen] is not needed to decide, only kept so the table reads whole.
 */
fun quickKeyFor(
  key: Key, down: Boolean, ctrl: Boolean, meta: Boolean, os: Os,
  shift: Boolean = false, queryBlank: Boolean = false, previewOpen: Boolean = false,
  rowSelected: Boolean = false,
): QuickKey? {
  if (!down) return null
  val mod = if (os == Os.MAC) meta else ctrl
  return when (key) {
    Key.DirectionDown -> QuickKey.DOWN
    Key.DirectionUp -> QuickKey.UP
    Key.PageDown -> QuickKey.LAST
    Key.PageUp -> QuickKey.FIRST
    Key.Escape -> QuickKey.CLOSE
    Key.Enter, Key.NumPadEnter -> when {
      mod -> QuickKey.SHOW_IN_FOLDER
      shift -> QuickKey.OPEN_TOP
      else -> QuickKey.OPEN
    }
    Key.Spacebar -> if ((queryBlank || rowSelected || previewOpen) && !mod) QuickKey.TOGGLE_PREVIEW else null
    Key.C -> if (mod && !shift && (queryBlank || rowSelected || previewOpen)) QuickKey.COPY else null
    Key.Y -> if (mod) (if (shift) QuickKey.FULL_PREVIEW else QuickKey.TOGGLE_PREVIEW) else null
    else -> null
  }
}

private fun KeyEvent.toQuickKey(os: Os, queryBlank: Boolean, previewOpen: Boolean, rowSelected: Boolean): QuickKey? =
  quickKeyFor(key, type == KeyEventType.KeyDown, isCtrlPressed, isMetaPressed, os, isShiftPressed, queryBlank, previewOpen, rowSelected)

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
  state: QuickPanelState, os: Os, shortcut: String?, focusNonce: Int = 0, modifier: Modifier = Modifier, focusKick: Int = 0, inset: Dp = SunPanel.ShadowInset,
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
  val answered by state.answered.collectAsState()
  val previewing by state.previewing.collectAsState()
  val copiedTick by state.copiedTick.collectAsState()
  var copiedFlash by remember { mutableStateOf(false) }
  LaunchedEffect(copiedTick) {
    if (copiedTick == 0) return@LaunchedEffect
    copiedFlash = true
    delay(1500)
    copiedFlash = false
  }
  val focus = remember { FocusRequester() }
  var fieldFocused by remember { mutableStateOf(false) }
  // Wait for the first Compose layout frame, then request focus with short post-frame retries until the text field actually takes focus.
  LaunchedEffect(focusNonce, focusKick) {
    withFrameNanos { }
    for (waitMs in longArrayOf(0L, 40L, 100L, 220L)) {
      if (waitMs > 0L) delay(waitMs)
      runCatching { focus.requestFocus() }
      if (fieldFocused) break
    }
  }
  // Closing the side preview disposes controls that may have held focus (e.g., Play button): restore focus immediately
  // to the search box so typing and a second Esc to dismiss Quick Panel always work without clicking.
  LaunchedEffect(previewing) {
    if (!previewing) {
      withFrameNanos { }
      runCatching { focus.requestFocus() }
    }
  }

  val floating = inset > 0.dp
  val shape = if (floating) PANEL_SHAPE else SQUARE_SHAPE
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
        .onPreviewKeyEvent { e -> e.toQuickKey(os, query.isBlank(), previewing, state.userSelectedRow.value)?.let { k -> if (k == QuickKey.FULL_PREVIEW) { state.fullPreview { blur.hides(it) }; true } else state.onKey(k) } ?: false },
    ) {
      Row(
        Modifier.fillMaxWidth().padding(SunPanel.Pad).heightIn(min = SunPanel.Field).clip(FIELD_SHAPE).background(tk.paper)
          .border(if (fieldFocused) 3.dp else Sun.Border, if (fieldFocused) tk.focus else tk.line, FIELD_SHAPE).padding(start = 16.dp, end = Sun.S8),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Icon(Icons.Search, contentDescription = null, tint = tk.ink, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(12.dp))
        Box(Modifier.weight(1f).heightIn(min = SunPanel.Field - 8.dp), contentAlignment = Alignment.CenterStart) {
          if (query.isEmpty()) Text(t("quick_hint"), style = SunType.Query, color = tk.ink2)
          BasicTextField(
            value = query, onValueChange = state::setQuery, singleLine = true,
            textStyle = SunType.Query.copy(color = tk.ink), cursorBrush = SolidColor(tk.ink),
            modifier = Modifier.fillMaxWidth().focusRequester(focus).onFocusChanged { fieldFocused = it.isFocused }.a11yLabel(t("quick_hint")),
          )
        }
        if (query.isNotEmpty()) A11yIconButton(Icons.Close, t("quick_clear"), { state.setQuery(""); runCatching { focus.requestFocus() } })
        else KeyCap("Esc", Modifier.padding(end = Sun.S4))
      }

      val status = when {
        locked -> t("quick_locked")
        copiedFlash -> tn("copied_files", 1, "1")
        answered == null -> ""
        query.isBlank() -> if (hits.isEmpty()) "" else t("quick_recent")
        answered != query -> ""   // the answer on screen is for an older query: say nothing rather than something wrong
        hits.isEmpty() -> t("quick_none", query.trim())
        else -> tn("quick_results", hits.size, hits.size)
      }
      // G4: how many of the rows are blurred pictures. A note, not a control: nothing is revealed from here (open Trawl for that).
      val hiddenN = remember(hits, locked, blur) { if (locked) 0 else QuickHidden.count(hits.map { it.file }, blur) }
      Row(
        Modifier.fillMaxWidth().padding(horizontal = SunPanel.Pad + Sun.S8, vertical = Sun.S2).heightIn(min = 20.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Sun.S8),
      ) {
        Text(status, style = SunType.Caption, color = tk.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).a11yLive())
        if (hiddenN > 0) QuickHiddenPill(hiddenN)
      }

      val split = previewing && !locked && hits.isNotEmpty()
      QuickResultsBody(state, hits, locked, split, blur, os, query, Modifier.weight(1f).fillMaxWidth())

      SunDivider()
      val mod = if (os == Os.MAC) "⌘" else "Ctrl"   // the key caps a Mac shows, as in the main window (MainUi.modName)
      Text(
        when {
          split && state.preview.full.available -> t("quick_footer_preview_full", mod)
          split -> t("quick_footer_preview", mod)
          shortcut != null -> t("quick_footer_key", mod, shortcut)
          else -> t("quick_footer", mod)
        }, style = SunType.PanelDetail, color = tk.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().background(tk.paper2).padding(horizontal = SunPanel.Pad + Sun.S8, vertical = Sun.S8),
      )
    }
  }
}

/**
 * Isolates `state.selected` reads from the outer [QuickPanel] so Up/Down arrow presses recompose only the two rows whose selection changed
 * (and the preview pane if open), never the search field, status header, footer, or unrelated rows.
 */
@Composable
private fun QuickResultsBody(
  state: QuickPanelState,
  hits: List<QuickHit>,
  locked: Boolean,
  split: Boolean,
  blur: PreviewBlur,
  os: Os,
  query: String,
  modifier: Modifier = Modifier,
) {
  val tk = LocalSunlight.current
  val list = rememberLazyListState()
  val selectedState = state.selected.collectAsState()
  val userSelectedState = state.userSelectedRow.collectAsState()
  val showImplicit = !state.hasFullSearchHandoff || split
  LaunchedEffect(state, hits) {
    state.selected.collect { sel ->
      if (hits.isNotEmpty()) list.scrollToItem(sel.coerceIn(0, hits.lastIndex))   // no animation: nothing to reduce
    }
  }
  Row(modifier) {
    Box(if (split) Modifier.width(SunPanel.ListBesidePreview).fillMaxHeight() else Modifier.weight(1f).fillMaxHeight()) {
      LazyColumn(
        Modifier.fillMaxSize(),
        state = list, contentPadding = PaddingValues(start = Sun.S8, end = 14.dp, top = Sun.S4, bottom = Sun.S4),
      ) {
        // Locked: no rows at all (the search answers nothing, and this makes sure a stale list is never drawn for a frame).
        if (!locked) itemsIndexed(hits, key = { _, h -> h.file.id }, contentType = { _, h -> h.file.kind }) { i, hit ->
          QuickRowSlot(i, hit, selectedState, userSelectedState, showImplicit, blur.hides(hit.file), split, state)
        }
      }
      if (!locked && hits.isNotEmpty()) {
        VerticalScrollbar(
          rememberScrollbarAdapter(list),
          Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = Sun.S4, horizontal = 2.dp),
          style = defaultScrollbarStyle().copy(unhoverColor = tk.line2.copy(alpha = 0.45f), hoverColor = tk.ink2),
        )
      }
    }
    if (split) {
      Box(Modifier.width(1.dp).fillMaxHeight().background(tk.line2))
      QuickPreviewSlot(hits, selectedState, blur, query, state, os, Modifier.weight(1f).fillMaxHeight())
    }
  }
}

@Composable
private fun QuickRowSlot(
  index: Int,
  hit: QuickHit,
  selectedState: State<Int>,
  userSelectedState: State<Boolean>,
  showImplicit: Boolean,
  hidden: Boolean,
  split: Boolean,
  state: QuickPanelState,
) {
  val isSelected by remember(index, selectedState, userSelectedState, showImplicit) {
    derivedStateOf { (userSelectedState.value || showImplicit) && selectedState.value == index }
  }
  QuickRow(
    hit = hit,
    selected = isSelected,
    hidden = hidden,
    onClick = { state.openAt(index) },
    onPreview = { if (split && isSelected) state.closePreview() else { state.select(index); if (!state.previewing.value) state.togglePreview() } },
    previewShown = split && isSelected,
  )
}

@Composable
private fun QuickPreviewSlot(
  hits: List<QuickHit>,
  selectedState: State<Int>,
  blur: PreviewBlur,
  query: String,
  state: QuickPanelState,
  os: Os,
  modifier: Modifier = Modifier,
) {
  val shown = hits.getOrNull(selectedState.value.coerceIn(0, (hits.size - 1).coerceAtLeast(0))) ?: return
  QuickPreview(
    shown, blur.hides(shown.file), query, state.preview, if (os == Os.MAC) "⌘" else "Ctrl",
    onOpen = state::open, onFolder = state::showInFolder, onFull = { state.fullPreview { blur.hides(it) } }, onClose = state::closePreview,
    modifier = modifier, onCopy = state::copy,
  )
}

/** The folder a hit lives in, as a person says it ("~/Documents/Tax"), from the file's real location rather than the folder it was found under. */
internal fun quickFolder(uri: String, fallback: String): String =
  runCatching { File(java.net.URI(uri)).parent }.getOrNull()?.let { PathText.tilde(it) }?.takeIf { it.isNotBlank() } ?: fallback

@Composable
private fun QuickRow(hit: QuickHit, selected: Boolean, hidden: Boolean, onClick: () -> Unit, onPreview: () -> Unit = {}, previewShown: Boolean = false) {
  val tk = LocalSunlight.current
  val where = remember(hit.file.uri, hit.file.relativePath) { quickFolder(hit.file.uri, hit.file.relativePath.substringBeforeLast('/', "")) }
  val source = remember { MutableInteractionSource() }
  val hovered by source.collectIsHoveredAsState()
  val kind = hit.file.kind
  val snippet = remember(hit.snippet, hidden) { if (hidden) null else hit.snippet?.replace(WHITESPACE, " ")?.trim()?.takeIf { it.isNotBlank() } }
  Row(
    Modifier.fillMaxWidth().padding(vertical = 1.dp).heightIn(min = SunPanel.Row).clip(ROW_SHAPE).hoverable(source)
      .background(if (selected || hovered) tk.paper2 else Color.Transparent, ROW_SHAPE)
      .then(if (selected) Modifier.border(3.dp, tk.focus, ROW_SHAPE) else Modifier)
      .dragFiles(hit.file.id) {
        if (hidden || hit.file.sensitive) emptyList()
        else listOfNotNull(runCatching { File(java.net.URI(hit.file.uri)) }.getOrNull())
      }
      .a11yClickable(if (hidden) t("quick_row_hidden", kindLabel(kind), where) else t("quick_row", hit.file.name, kindLabel(kind), where), ROW_SHAPE, onClick)
      .semantics { this.selected = selected }
      .padding(start = Sun.S12, end = Sun.S4, top = Sun.S8, bottom = Sun.S8),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    RowThumb(hit, hidden)
    Spacer(Modifier.width(Sun.S12))
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Sun.S8)) {
        Text(if (hidden) t("hidden_label") else hit.file.name, style = SunType.PanelTitle, color = tk.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        val visual = if (hidden) null else trawl.desktop.ui.FileTypeVisual.of(hit.file.name, kind)
        if (visual != null) trawl.desktop.ui.TypeBadge(visual)
        else if (kind == FileKind.PDF || kind == FileKind.VIDEO || kind == FileKind.AUDIO) Badge(kindLabel(kind))
      }
      Text(
        listOfNotNull(where.takeIf { it.isNotBlank() }, formatBytes(hit.file.sizeBytes)).joinToString("  ·  "),
        style = SunType.PanelDetail, color = tk.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis,
      )
      if (snippet != null) SnippetText(snippet, lines = 1, modifier = Modifier.padding(top = 2.dp), style = SunType.PanelDetail)
    }
    // The preview affordance: shows this row large beside the list without opening the file. A second click on the same row's eye closes it.
    val round = androidx.compose.foundation.shape.CircleShape
    Box(
      Modifier.size(Sun.Target).clip(round)
        .then(if (previewShown) Modifier.background(tk.marker, round) else Modifier)
        .a11yClickable(if (hidden) t("quick_preview_row_hidden") else t("quick_preview_row", hit.file.name), round, onPreview),
      contentAlignment = Alignment.Center,
    ) {
      Icon(trawl.desktop.ui.privacy.PrivacyIcons.Eye, contentDescription = null, tint = if (previewShown) tk.onMarker else tk.ink2, modifier = Modifier.size(Sun.Icon))
    }
  }
}

/** A small picture for photos, an icon tile for the rest (the main window's tile). Decoration: the row carries the name. */
@Composable
private fun RowThumb(hit: QuickHit, hidden: Boolean) {
  val tk = LocalSunlight.current
  val isPicture = hit.file.kind == FileKind.IMAGE || hit.file.kind == FileKind.SCREENSHOT
  if (!isPicture) { KindTile(hit.file.kind, SunPanel.Thumb, fileName = if (hidden) null else hit.file.name); return }
  val file = remember(hit.file.uri) { runCatching { File(java.net.URI(hit.file.uri)) }.getOrNull() }
  val mod = hit.file.modifiedMillis
  // Synchronous cache peek using IndexedFile.modifiedMillis avoids a 1-2 frame icon flash on cached thumbs and avoids stat() on the UI thread.
  // Hidden pictures go through PrivateThumbnails so sharp pixels are never cached while blurred.
  // Keyed mutableStateOf ensures flipping hidden=false->true (or changing file/mod) drops any prior bitmap immediately.
  var bmp by remember(hit.file.id, file, hidden, mod) {
    mutableStateOf(file?.let { PrivateThumbnails.peek(it, 160, blurred = hidden, modifiedMillis = mod) })
  }
  LaunchedEffect(hit.file.id, file, hidden, mod) {
    if (bmp != null || file == null) return@LaunchedEffect
    bmp = withContext(quickThumbDispatcher) {
      currentCoroutineContext().ensureActive()
      val loaded = runCatching { PrivateThumbnails.load(file, 160, blurred = hidden, modifiedMillis = mod) }.getOrNull()
      currentCoroutineContext().ensureActive()
      loaded
    }
  }
  val b = bmp
  if (b == null) { KindTile(hit.file.kind, SunPanel.Thumb); return }
  Box(Modifier.size(SunPanel.Thumb).clip(THUMB_SHAPE).background(tk.paper2).border(Sun.Hairline, tk.line2, THUMB_SHAPE), contentAlignment = Alignment.Center) {
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
