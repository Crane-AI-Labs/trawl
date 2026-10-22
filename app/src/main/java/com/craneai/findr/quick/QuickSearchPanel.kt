package com.craneai.findr.quick

import com.craneai.findr.ui.common.appString
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import com.craneai.findr.ui.theme.LocalSunlight
import com.craneai.findr.ui.theme.SCRIM_ALPHA
import com.craneai.findr.ui.theme.Sun
import com.craneai.findr.ui.theme.SunButton
import com.craneai.findr.ui.theme.SunTextButton
import com.craneai.findr.ui.theme.SunType
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import com.craneai.findr.R
import com.craneai.findr.core.IndexedFile
import com.craneai.findr.core.SearchHit
import com.craneai.findr.ui.search.BindQuery
import com.craneai.findr.ui.search.FileActionSheet
import com.craneai.findr.ui.search.FileRow
import com.craneai.findr.ui.privacy.SensitiveChip

/**
 * The panel: a dim scrim (tap it to close) and one card with the search box on top and the results under it.
 * It takes plain state and plain callbacks, so it can be previewed and tested without an Activity.
 */
@Composable
internal fun QuickSearchPanel(
  state: QuickUiState,
  mode: QuickMode,
  text: TextFieldState,
  reopen: Int,
  offerTile: Boolean,
  onQuery: (String) -> Unit,
  onFocused: () -> Unit,
  onTyping: (Boolean) -> Unit,
  onHitsDrawn: () -> Unit,
  onResultsShown: (Int) -> Unit,
  onClose: () -> Unit,
  onShare: (IndexedFile) -> Unit,
  onCopy: (IndexedFile) -> Unit,
  onOpen: (IndexedFile) -> Unit,
  onShowFolder: (IndexedFile) -> Unit,
  onMarkSensitive: (IndexedFile, Boolean) -> Unit,
  onUnlock: () -> Unit,
  onOpenFindr: () -> Unit,
  onAddTile: () -> Unit,
  onDeclineTile: () -> Unit,
) {
  val focus = remember { FocusRequester() }
  val keyboard = LocalSoftwareKeyboardController.current
  val listState = rememberLazyListState()

  BindQuery(text, onQuery)
  // Opened (or opened again): empty box, cursor in it, keyboard up.
  // While locked there is no box to focus, and nothing typed earlier may stay in it.
  LaunchedEffect(reopen, mode) {
    text.clearText()
    if (mode == QuickMode.SEARCH) {
      focus.requestFocus()
      keyboard?.show()
    } else {
      keyboard?.hide()
    }
  }
  // A new list is on screen one frame after it was composed: tell the view model, for the timing log.
  LaunchedEffect(state.hits) {
    if (state.hits.isNotEmpty()) {
      withFrameNanos { }
      onHitsDrawn()
    }
    listState.scrollToItem(0)
  }
  LaunchedEffect(state.resultsQuery, state.searching) {
    if (state.resultsQuery.isNotEmpty() && !state.searching) onResultsShown(state.hits.size)
  }
  LaunchedEffect(text) {
    snapshotFlow { text.text.isNotEmpty() }.collect { onTyping(it) }
  }

  Box(
    Modifier
      .fillMaxSize()
      .background(Color.Black.copy(alpha = SCRIM_ALPHA))   // Sunlight: the panel's only "shadow" is this scrim
      .clickable(interactionSource = null, indication = null, onClick = onClose)
      .imePadding(),
  ) {
    Surface(
      modifier = Modifier
        .align(Alignment.TopCenter)
        .statusBarsPadding()
        .navigationBarsPadding()
        .padding(12.dp)
        .widthIn(max = 560.dp)
        .fillMaxWidth()
        // A tap on the card is the card's: it must not fall through to the scrim and close the panel.
        .pointerInput(Unit) { detectTapGestures { } },
      shape = MaterialTheme.shapes.extraLarge,
      color = MaterialTheme.colorScheme.background,
      tonalElevation = 0.dp,
      shadowElevation = 0.dp,
      border = BorderStroke(Sun.Border, MaterialTheme.colorScheme.outline),
    ) {
      Column(Modifier.padding(top = Sun.S12)) {
        if (mode != QuickMode.SEARCH) {
          LockedCard(mode, onUnlock)
          return@Column
        }
        QuickField(text, focus, onFocused, Modifier.padding(horizontal = Sun.S12))
        // Where the search is: words at once, meaning a moment later (one line, so the rows under it never jump).
        if (text.text.isNotEmpty() && state.searching) {
          Row(Modifier.padding(horizontal = Sun.S16, vertical = Sun.S4), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Schedule, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(Sun.S8))
            Text(stringResource(R.string.search_status_words), style = SunType.Caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
          }
        }
        SensitiveChip(state.hiddenCount, state.revealedForNow && state.showsSensitive, Modifier.padding(horizontal = Sun.S12, vertical = Sun.S4))
        Body(
          state = state,
          listState = listState,
          offerTile = offerTile,
          onShare = onShare,
          onCopy = onCopy,
          onOpen = onOpen,
          onShowFolder = onShowFolder,
          onMarkSensitive = onMarkSensitive,
          onOpenFindr = onOpenFindr,
          onAddTile = onAddTile,
          onDeclineTile = onDeclineTile,
        )
      }
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Body(
  state: QuickUiState,
  listState: androidx.compose.foundation.lazy.LazyListState,
  offerTile: Boolean,
  onShare: (IndexedFile) -> Unit,
  onCopy: (IndexedFile) -> Unit,
  onOpen: (IndexedFile) -> Unit,
  onShowFolder: (IndexedFile) -> Unit,
  onMarkSensitive: (IndexedFile, Boolean) -> Unit,
  onOpenFindr: () -> Unit,
  onAddTile: () -> Unit,
  onDeclineTile: () -> Unit,
) {
  val imeUp = WindowInsets.isImeVisible
  // The file whose long-press sheet is open, by uri so a new result list closes it by itself.
  var sheetUri by rememberSaveable { mutableStateOf<String?>(null) }
  val sheetFile = sheetUri?.let { uri -> state.hits.firstOrNull { it.file.uri == uri }?.file }
  sheetFile?.let { file ->
    FileActionSheet(
      file = file,
      onShare = { sheetUri = null; onShare(file) },
      onCopy = { sheetUri = null; onCopy(file) },
      onOpen = { sheetUri = null; onOpen(file) },
      onShowFolder = { sheetUri = null; onShowFolder(file) },
      onMarkSensitive = { on -> sheetUri = null; onMarkSensitive(file, on) },
      onDismiss = { sheetUri = null },
    )
  }
  when {
    state.hits.isNotEmpty() -> Column {
      LazyColumn(
        state = listState,
        modifier = Modifier.weight(1f, fill = false),
        contentPadding = PaddingValues(horizontal = Sun.S8, vertical = Sun.S4),
      ) {
        items(state.hits, key = { it.file.id }) { hit ->
          FileRow(
            hit,
            onOpen = { onOpen(hit.file) },
            onShare = { onShare(hit.file) },
            onCopy = { onCopy(hit.file) },
            onToggle = { sheetUri = hit.file.uri },  // no selection in the panel: long-press still opens its sheet
            // The panel's box keeps focus while it is open: compact rows whenever the keyboard is up, as on the home screen.
            compact = imeUp,
          )
        }
      }
      if (offerTile) TileOfferCard(onAddTile, onDeclineTile)
    }
    state.notSetUp -> Message(appString(R.string.quick_not_set_up), actionLabel = appString(R.string.quick_open_findr), onAction = onOpenFindr)
    state.nothingFound -> Message(
      stringResource(R.string.quick_nothing, state.resultsQuery).let { base ->
        if (state.indexIncomplete) stringResource(R.string.message_then_sentence, base, appString(R.string.quick_nothing_incomplete)) else base
      },
    )
    else -> Spacer(Modifier.height(8.dp))
  }
}

/** What the panel says instead of a search box while the phone or Findr is locked. It names nothing and lists nothing. */
@Composable
private fun LockedCard(mode: QuickMode, onUnlock: () -> Unit) {
  Column(Modifier.fillMaxWidth().padding(horizontal = Sun.S24, vertical = Sun.S24), horizontalAlignment = Alignment.CenterHorizontally) {
    Icon(Icons.Rounded.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(40.dp))
    Spacer(Modifier.height(Sun.S12))
    if (mode == QuickMode.DEVICE_LOCKED) {
      Text(stringResource(R.string.quick_device_locked), style = SunType.Heading, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center)
    } else {
      Text(appString(R.string.lock_title), style = SunType.Heading, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center)
      Spacer(Modifier.height(Sun.S16))
      SunButton(onClick = onUnlock, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.lock_button)) }
    }
  }
}

@Composable
private fun Message(text: String, actionLabel: String? = null, onAction: () -> Unit = {}) {
  Column(Modifier.fillMaxWidth().padding(horizontal = Sun.S16, vertical = Sun.S16)) {
    Text(text, style = SunType.BodyLarge, color = MaterialTheme.colorScheme.onSurface)
    if (actionLabel != null) {
      Spacer(Modifier.height(Sun.S8))
      SunButton(onClick = onAction, modifier = Modifier.fillMaxWidth()) { Text(actionLabel) }
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TileOfferCard(onAdd: () -> Unit, onDecline: () -> Unit) {
  val lineColor = MaterialTheme.colorScheme.outline
  Column(
    Modifier
      .fillMaxWidth()
      .background(MaterialTheme.colorScheme.surfaceVariant)
      .drawBehind { drawLine(lineColor, Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = Sun.Border.toPx()) }
      .padding(horizontal = Sun.S16, vertical = Sun.S12),
  ) {
    Text(stringResource(R.string.quick_add_tile_title), style = SunType.BodyLarge.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.onSurface)
    Text(stringResource(R.string.quick_add_tile_body), style = SunType.Body, color = MaterialTheme.colorScheme.onSurfaceVariant)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Sun.S8, Alignment.End), modifier = Modifier.fillMaxWidth().padding(top = Sun.S8)) {
      SunTextButton(onClick = onDecline) { Text(stringResource(R.string.quick_add_tile_no)) }
      SunButton(onClick = onAdd) { Text(stringResource(R.string.quick_add_tile_yes)) }
    }
  }
}

/** The panel's box: the same as the home screen's (60 dp, 2 dp edge, 12 dp corners, 18 sp text). */
@Composable
private fun QuickField(text: TextFieldState, focus: FocusRequester, onFocused: () -> Unit, modifier: Modifier = Modifier) {
  val t = LocalSunlight.current
  val shape = MaterialTheme.shapes.small
  val hint = stringResource(R.string.quick_hint)
  val clearLabel = stringResource(R.string.quick_clear)
  BasicTextField(
    state = text,
    modifier = modifier
      .fillMaxWidth()
      .focusRequester(focus)
      // The person is about to type: load the text model now so the meaning pass does not wait for it.
      .onFocusChanged { if (it.isFocused) onFocused() }
      .semantics { contentDescription = hint },
    textStyle = SunType.BodyLarge.copy(color = t.ink),
    cursorBrush = SolidColor(t.ink),
    lineLimits = TextFieldLineLimits.SingleLine,
    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, imeAction = ImeAction.Search),
    onKeyboardAction = KeyboardActionHandler { },
    decorator = { inner ->
      Row(
        Modifier
          .fillMaxWidth()
          .heightIn(min = Sun.Field)
          .clip(shape)
          .background(t.paper2)
          .border(BorderStroke(Sun.Border, t.line), shape)
          .padding(start = Sun.S16, end = Sun.S4),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Icon(Icons.Rounded.Search, contentDescription = null, tint = t.ink, modifier = Modifier.size(Sun.Icon))
        Spacer(Modifier.width(Sun.S12))
        Box(Modifier.weight(1f).padding(vertical = Sun.S8), contentAlignment = Alignment.CenterStart) {
          if (text.text.isEmpty()) Text(hint, style = SunType.BodyLarge, color = t.ink2, maxLines = 1)
          inner()
        }
        if (text.text.isNotEmpty()) {
          IconButton(onClick = { text.setTextAndPlaceCursorAtEnd("") }) {
            Icon(Icons.Rounded.Close, contentDescription = clearLabel, tint = t.ink)
          }
        } else {
          Spacer(Modifier.width(Sun.S12))
        }
      }
    },
  )
}
