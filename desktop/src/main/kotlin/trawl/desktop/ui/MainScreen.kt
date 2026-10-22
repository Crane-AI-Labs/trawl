package trawl.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.delay
import trawl.desktop.ui.search.*
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.craneai.findr.core.KindFilter
import com.craneai.findr.core.LearningSpeed
import com.craneai.findr.ui.common.formatCount
import com.craneai.findr.ui.theme.LocalSunlight
import trawl.desktop.AppController
import trawl.desktop.Hold
import trawl.desktop.IndexUi
import trawl.desktop.Phase
import trawl.desktop.ResultRow
import trawl.desktop.ResultsUi
import trawl.desktop.Screen
import trawl.desktop.t
import trawl.desktop.tn

/** From this window width the status bar also has room for the "choose more" hint. */
private const val WIDE_HINTS_DP = 1400

/** A Compose key event as the pure [Keystroke] the shortcut table understands; null for key-up and for the separate typed-character event. */
internal fun KeyEvent.toStroke(): Keystroke? {
  if (type != KeyEventType.KeyDown) return null
  val cp = utf16CodePoint
  val k = when (key) {
    Key.DirectionUp -> K.UP
    Key.DirectionDown -> K.DOWN
    Key.DirectionLeft -> K.LEFT
    Key.DirectionRight -> K.RIGHT
    Key.MoveHome -> K.HOME
    Key.MoveEnd -> K.END
    Key.PageUp -> K.PAGE_UP
    Key.PageDown -> K.PAGE_DOWN
    Key.Enter, Key.NumPadEnter -> K.ENTER
    Key.Spacebar -> K.SPACE
    Key.Escape -> K.ESCAPE
    Key.Backspace -> K.BACKSPACE
    Key.Tab -> K.TAB
    Key.K -> K.LETTER_K
    Key.F -> K.LETTER_F
    Key.C -> K.LETTER_C
    Key.A -> K.LETTER_A
    Key.Y -> K.LETTER_Y
    Key.Menu -> K.MENU
    Key.F10 -> K.F10
    Key.Comma -> K.COMMA
    // "+" is Shift and "=" on most keyboards, so both mean "bigger"; the number pad has its own keys.
    Key.Plus, Key.Equals, Key.NumPadAdd -> K.PLUS
    Key.Minus, Key.NumPadSubtract -> K.MINUS
    Key.Zero, Key.NumPad0 -> K.ZERO
    else -> if (cp > 32 && !Character.isISOControl(cp)) K.CHAR else K.OTHER
  }
  val printable = if (cp > 32 && !Character.isISOControl(cp)) cp else 0
  return Keystroke(k, printable, ctrl = isCtrlPressed, meta = isMetaPressed, shift = isShiftPressed, alt = isAltPressed)
}

@Composable
fun MainScreen(c: AppController, ui: MainUi = remember { MainUi() }) {
  val query by c.query.collectAsState()
  val filter by c.filter.collectAsState()
  val settings by c.settings.collectAsState()
  val results by c.results.collectAsState()
  val index by c.index.collectAsState()
  val tk = LocalSunlight.current

  DisposableEffect(ui) { MainUi.current = ui; onDispose { if (MainUi.current === ui) MainUi.current = null } }
  // The shield for screenshots and screen sharing: on while sensitive files are listed (they are only listed once the person showed them).
  val sensitiveShown by c.privacy.revealed.collectAsState()
  LaunchedEffect(results, sensitiveShown) { c.privacy.setSensitiveOnScreen("results", sensitiveShown && results.rows.any { it.file.sensitive }) }
  DisposableEffect(Unit) { onDispose { c.privacy.setSensitiveOnScreen("results", false) } }
  LaunchedEffect(Unit) { ui.focusSearch() }

  BoxWithConstraints(Modifier.fillMaxSize()) {
    val widthDp = maxWidth.value.toInt()
    val split = Layout.mode(widthDp) == Layout.Mode.SPLIT
    val listW = Layout.listWidth(widthDp)
    val cols = Layout.gridColumns(listW - 28)
    ui.pageRows = ((maxHeight.value - 200f) / 64f).toInt().coerceAtLeast(4)

    // ---- SEARCH PACKAGE (A): the list's shape follows the sort and date (flat when chosen), Recent is a feed, Find similar is flat ----
    val view by c.view.collectAsState()
    val style = remember(results.browse, results.sort, results.date, results.similar) { listStyleOf(results) }
    val built = remember(results, ui.showWeak, cols, ui.recentAll) {
      ResultsLayout.build(results.rows, ui.showWeak, cols, style, if (ui.recentAll) Int.MAX_VALUE else ResultsLayout.RECENT_DOC_ROWS)
    }
    ui.order = built.ids
    val rowOf = remember(results) { results.rows.associateBy { it.file.id } }
    val selectedRow: ResultRow? = ui.selected?.let(rowOf::get)
    val listState = rememberLazyListState()
    val seedKey = "${results.query}|${results.similar?.id}"
    var atTop by remember { mutableStateOf(true) }

    LaunchedEffect(results) {
      // A new question (or a new "Find similar" seed) starts at the best hit with nothing else chosen; the same question refreshed (meaning
      // landing on the words, the index growing) keeps the selection and the choice by id and leaves the scroll where it is.
      val fresh = seedKey.trim() != ui.lastQuery.trim()
      ui.selected = ResultNav.reconcile(ui.selected, ui.lastQuery, seedKey, built.ids)
      if (fresh) { ui.picked = emptySet(); ui.anchor = ui.selected; ui.recentAll = false }
      else ui.setPick(Picking.prune(ui.pickState(), built.ids))
      ui.lastQuery = seedKey
      ui.menu = null
      // Meaning landing on the words reorders the rows. A lazy list keeps the row that was first on screen where it is, which would carry the
      // viewport down the new order; someone still at the top of the list stays at the top.
      if (!fresh && atTop) listState.scrollToItem(0)
    }
    LaunchedEffect(seedKey) { atTop = true; listState.scrollToItem(0) }
    LaunchedEffect(listState) {
      snapshotFlow { listState.isScrollInProgress }.collect { moving ->
        if (!moving) atTop = listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
      }
    }
    // Ages are drawn from the computer's clock (moved up to its newest file if the clock is behind) and redrawn every half minute.
    var clock by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); clock = System.currentTimeMillis() } }
    val fontScale = LocalDensity.current.fontScale
    val rowContext = RowContext(sort = results.sort, nowMs = clock + results.skewMs, stack = stackRows(fontScale, listW - 28))
    val leave = trawl.desktop.ui.privacy.rememberLeave()
    val privacyUi = trawl.desktop.ui.privacy.LocalPrivacyUi.current
    fun leaveAll(ids: Collection<Long>, action: () -> Unit) { if (privacyUi == null) action() else privacyUi.leave(ids, action) }
    val pickedNow = remember(ui.picked, built) { Picking.inOrder(built.ids, ui.picked).mapNotNull(rowOf::get).map { it.file } }
    val pickActions = PickActions(
      canShare = c.canShare,
      onCopy = { val ids = pickedNow.map { it.id }; leaveAll(ids) { c.copyFiles(pickedNow) } },
      onShow = { val ids = pickedNow.map { it.id }; leaveAll(ids) { c.showInFolders(pickedNow) } },
      onShare = { val ids = pickedNow.map { it.id }; leaveAll(ids) { c.share(pickedNow) } },
      onAll = { ui.setPick(Picking.all(ui.pickState(), built.ids)) }, onClose = { ui.clearPicked() },
    )
    LaunchedEffect(selectedRow == null) { if (selectedRow == null) ui.quickLook = false }
    LaunchedEffect(ui.selectAllSignal) {
      if (ui.selectAllSignal > 0) {
        val v = ui.valueFor(c.query.value)
        ui.tfv = v.copy(selection = TextRange(0, v.text.length))
      }
    }

    fun perform(a: KeyAction, k: Keystroke): Boolean {
      val sel = selectedRow
      when (a) {
        KeyAction.FocusSearch -> { ui.focusSearch(); if (k.key == K.LETTER_K || k.key == K.LETTER_F) ui.selectAllSignal++ }
        // A plain move lets go of a multiple choice and starts the next range from where the cursor lands (SEARCH PACKAGE A).
        is KeyAction.Move -> { ui.selected = ResultNav.move(built.sections, built.cols, ui.selected, a.dir); ui.clearPicked() }
        KeyAction.Home -> { ui.selected = ResultNav.first(built.sections); ui.clearPicked() }
        KeyAction.End -> { ui.selected = ResultNav.last(built.sections); ui.clearPicked() }
        is KeyAction.Page -> { ui.selected = ResultNav.page(built.sections, built.cols, ui.selected, a.down, ui.pageRows); ui.clearPicked() }
        is KeyAction.Step -> { ui.selected = ResultNav.step(built.ids, ui.selected, a.delta); ui.clearPicked() }
        // Enter with several chosen does nothing: opening a dozen files by one key press is never what was meant.
        // Everything that hands a file to something outside Trawl asks the privacy "leave" question first when a sensitive file is among them.
        KeyAction.Open -> if (!ui.multi) sel?.let { leave(it.file.id) { c.open(it.file) } }
        KeyAction.ShowInFolder -> if (ui.multi) leaveAll(pickedNow.map { it.id }) { c.showInFolders(pickedNow) } else sel?.let { leave(it.file.id) { c.showInFolder(it.file) } }
        KeyAction.CopyPath -> sel?.let { leave(it.file.id) { c.copyPath(it.file) } }
        // ---- SEARCH PACKAGE (A) ----
        KeyAction.CopyFiles -> if (ui.multi) leaveAll(pickedNow.map { it.id }) { c.copyFiles(pickedNow) } else sel?.let { leave(it.file.id) { c.copyFiles(listOf(it.file)) } }
        KeyAction.SelectAll -> ui.setPick(Picking.all(ui.pickState(), built.ids))
        is KeyAction.Extend -> ui.setPick(Picking.extend(ui.pickState(), built.ids, ResultNav.move(built.sections, built.cols, ui.selected, a.dir)))
        KeyAction.ClearPicked -> ui.clearPicked()
        KeyAction.ContextMenu -> sel?.let { ui.menu = MenuRequest(it.file.id, 56, 40) }
        KeyAction.ToggleQuickLook -> if (sel != null) { ui.quickLook = !ui.quickLook; ui.focusList() }
        KeyAction.CloseQuickLook -> ui.quickLook = false
        KeyAction.ClearQuery -> { c.setQuery(""); ui.tfv = TextFieldValue("") }
        KeyAction.Back -> Unit
        KeyAction.OpenSettings -> c.openSettings()
        is KeyAction.Zoom -> c.zoom(a.step)
        is KeyAction.TypeIntoSearch -> {
          // A character typed while a result has focus: send focus to the search box and let the typed character land there.
          // Backspace has no typed character, so it is applied here.
          val v = ui.valueFor(c.query.value)
          ui.tfv = v.copy(selection = TextRange(v.text.length))
          ui.focusSearch()
          if (a.char != 8) return false
          val shorter = v.text.dropLast(1)
          c.setQuery(shorter)
          ui.tfv = TextFieldValue(shorter, TextRange(shorter.length))
        }
      }
      return true
    }

    val onKey: (KeyEvent) -> Boolean = onKey@{ e ->
      val k = e.toStroke() ?: return@onKey false
      val sel = ui.tfv.selection
      val ctx = KeyContext(
        screen = Screen.MAIN, zone = ui.zone, quickLook = ui.quickLook, queryBlank = query.isBlank(), hasResults = built.ids.isNotEmpty(),
        isMac = MainUi.isMac, selectionInGrid = selectedRow?.let(::isTile) == true,
        caretAtStart = sel.collapsed && sel.start == 0, caretAtEnd = sel.collapsed && sel.end >= ui.valueFor(query).text.length,
        hasPicked = ui.multi, textSelected = !sel.collapsed,
      )
      val action = KeyMap.map(k, ctx) ?: return@onKey false
      perform(action, k)
    }

    CompositionLocalProvider(LocalRowContext provides rowContext) {
    Box(Modifier.fillMaxSize().onPreviewKeyEvent(onKey)) {
      Column(Modifier.fillMaxSize()) {
        // After a crash, once: offers a report to read (nothing is sent). Draws nothing otherwise.
        trawl.desktop.ui.report.CrashCard(c.reporter, openLogFolder = c::openLogFolder, modifier = Modifier.padding(horizontal = Sun.S20, vertical = Sun.S8))
        TopBar(c, ui, query)
        // SEARCH PACKAGE (A): the kind chips, then the date and sort chips (and "Similar to ..."), in one wrapping row.
        FilterBarRow(c, ui, view, Modifier.fillMaxWidth().padding(horizontal = Sun.S20).padding(bottom = Sun.S8)) {
          FilterChip(t("filter_all"), Icons.Apps, filter == KindFilter.ALL, { c.setFilter(KindFilter.ALL) })
          // A kind that is switched off in Settings has no chip: nothing of it is read or shown.
          if (settings.indexPhotos) FilterChip(t("filter_photos"), Icons.Photo, filter == KindFilter.PHOTOS, { c.setFilter(KindFilter.PHOTOS) })
          if (settings.indexScreenshots) FilterChip(t("filter_screenshots"), Icons.Phone, filter == KindFilter.SCREENSHOTS, { c.setFilter(KindFilter.SCREENSHOTS) })
          if (settings.indexDocuments) FilterChip(t("filter_documents"), Icons.Document, filter == KindFilter.DOCUMENTS, { c.setFilter(KindFilter.DOCUMENTS) })
          if (settings.indexVideos) FilterChip(t("filter_videos"), Icons.Video, filter == KindFilter.VIDEOS, { c.setFilter(KindFilter.VIDEOS) })
          if (settings.indexAudio) FilterChip(t("filter_audio"), Icons.Mic, filter == KindFilter.AUDIO, { c.setFilter(KindFilter.AUDIO) })
          // Privacy: how many pictures the blur holds back (press to show them all), and show or put away sensitive files. Neither is a filter.
          // They close the kind chips; the date and sort chips follow in the same wrapping row (SEARCH PACKAGE A), so on a narrow window the row
          // wraps instead of pushing them off the edge.
          trawl.desktop.ui.privacy.LocalPrivacyUi.current?.let { pui ->
            val files = remember(results) { results.rows.map { it.file } }
            trawl.desktop.ui.privacy.HiddenChip(trawl.desktop.privacy.LocalPreviewBlur.current, files)
            trawl.desktop.ui.privacy.SensitiveChip(pui)
          }
        }
        SunDivider()
        val line = remember(index) { MeterText.of(index) }
        // Package C's meter card (with its detail panel) takes the place of the old banner for every state that needs the person: a warning,
        // a pause, or an index that would not open. Normal reading is the quiet status line at the bottom, which opens the same panel.
        val needsYou = StatusParts.cardShown(line, index)
        Row(Modifier.weight(1f).fillMaxWidth()) {
          Column(Modifier.then(if (split) Modifier.width(listW.dp) else Modifier.weight(1f)).fillMaxHeight()) {
            // In the two-pane layout the card sits over the list only, so the preview keeps its full height.
            if (needsYou) Box(Modifier.padding(horizontal = Sun.S20, vertical = Sun.S8)) { trawl.desktop.ui.index.IndexMeter(c) }
            trawl.desktop.ui.search.RepairingNote(index.repairing)
            ListHeader(results)
            if (ui.multi) SelectionBar(pickedNow.size, pickActions)
            if (results.rows.isEmpty()) EmptyResults(results, filter, c, ui, Modifier.weight(1f).fillMaxWidth().pointerInput(Unit) { detectTapGestures { ui.focusSearch() } })
            else ResultsList(c, built, ui, listState, Modifier.weight(1f).fillMaxWidth())
          }
          if (split) {
            Box(Modifier.width(Sun.Hairline).fillMaxHeight().background(tk.line2))
            if (ui.multi) MultiPane(pickedNow, pickActions, Modifier.weight(1f).fillMaxHeight())
            else PreviewPane(selectedRow, results.browsing, c, Modifier.weight(1f).fillMaxHeight())
          }
        }
        StatusBar(line, index, results, showKeys = Layout.showKeyHints(widthDp), wide = widthDp >= WIDE_HINTS_DP, onPause = { c.setPaused(true) }, onDetails = { ui.detailOpen = true }, cardShown = needsYou)
      }
      if (ui.dateDialog) DateRangeDialog(c, ui)
      if (ui.detailOpen) {
        val actions = remember(c) {
          trawl.desktop.ui.index.IndexActions(
            onPause = { c.setPaused(true) }, onResume = { c.setPaused(false) }, onReadOnBattery = { c.setSpeed(LearningSpeed.BALANCED) },
            onRetryOpen = { c.service.retryOpen() }, onRetryUnreadable = { c.service.retryUnreadable() },
          )
        }
        trawl.desktop.ui.index.IndexDetailDialog(index, c.service.detail(), actions, trawl.desktop.Scope.enabledKinds(settings)) { ui.detailOpen = false }
      }
      if (ui.quickLook && selectedRow != null) {
        val position = built.ids.indexOf(selectedRow.file.id) + 1
        QuickLook(selectedRow, position, built.ids.size, c) { ui.quickLook = false }
      }
    }
    }
  }
}

@Composable
private fun TopBar(c: AppController, ui: MainUi, query: String) {
  Row(Modifier.fillMaxWidth().padding(horizontal = Sun.S20, vertical = Sun.S12), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Sun.S20)) {
    Wordmark(markSize = 28.dp)
    SearchField(c, ui, query, Modifier.weight(1f).widthIn(max = 760.dp))
    Spacer(Modifier.weight(0.0001f))
    SunOutlinedButton(onClick = c::openSettings) {
      Icon(Icons.Settings, contentDescription = null, modifier = Modifier.size(Sun.IconSmall))
      Spacer(Modifier.width(Sun.S8))
      Text(t("action_settings"), style = SunType.Label)
    }
  }
}

@Composable
private fun SearchField(c: AppController, ui: MainUi, query: String, modifier: Modifier) {
  val tk = LocalSunlight.current
  val shape = RoundedCornerShape(12.dp)
  val focused = ui.searchFocused
  Row(
    modifier.heightIn(min = Sun.Field).clip(shape).background(tk.paper).border(if (focused) 3.dp else Sun.Border, if (focused) tk.focus else tk.line, shape).padding(start = 14.dp, end = Sun.S8),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(Icons.Search, contentDescription = null, tint = tk.ink, modifier = Modifier.size(Sun.Icon))
    Spacer(Modifier.width(10.dp))
    Box(Modifier.weight(1f)) {
      BasicTextField(
        value = ui.valueFor(query),
        onValueChange = { ui.tfv = it; if (it.text != c.query.value) c.setQuery(it.text) },
        singleLine = true, textStyle = SunType.BodyLarge.copy(color = tk.ink), cursorBrush = SolidColor(tk.ink),
        modifier = Modifier.fillMaxWidth().focusRequester(ui.searchFocus).onFocusChanged { ui.searchFocused = it.isFocused }
          .semantics { contentDescription = t("search_hint") },
      )
      if (query.isEmpty()) Text(t("search_hint"), style = SunType.BodyLarge, color = tk.ink2)
    }
    if (query.isNotEmpty()) SunIconButton(Icons.Close, t("search_clear"), { c.setQuery(""); ui.focusSearch() })
    else KeyCap("${MainUi.modName} K", Modifier.padding(end = Sun.S4))
  }
}

@Composable
private fun ListHeader(results: ResultsUi) {
  val tk = LocalSunlight.current
  Row(Modifier.fillMaxWidth().padding(start = Sun.S20, end = Sun.S20, top = Sun.S12, bottom = Sun.S4), verticalAlignment = Alignment.Bottom) {
    val title = when {
      results.similar != null -> t("similar_chip", results.similar.name)
      results.browsing -> t("search_recent_title")
      else -> t("search_results_title")
    }
    Text(title, style = SunType.Heading, color = tk.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).a11yHeading())
    val n = results.rows.size
    val count = tn("search_count", n, formatCount(n))
    // A live region: a screen reader says the new count when the results change, without moving focus; the milliseconds are for the eyes only.
    Text(
      if (results.browsing) count else t("results_summary", count, t("search_took", results.tookMs)), style = SunType.Caption, color = tk.ink2,
      modifier = Modifier.semantics { contentDescription = "$title, $count"; liveRegion = LiveRegionMode.Polite },
    )
  }
}

/** The bottom line: what reading is doing, a slim bar while it works, Pause, and (on a wide window) the keys that matter. */
@Composable
private fun StatusBar(line: MeterLine, ix: IndexUi, results: ResultsUi, showKeys: Boolean, wide: Boolean, onPause: () -> Unit, onDetails: () -> Unit, cardShown: Boolean) {
  val tk = LocalSunlight.current
  val mod = MainUi.modName
  Column(Modifier.fillMaxWidth().background(tk.paper2)) {
    SunDivider()
    Row(
      Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(horizontal = Sun.S20, vertical = Sun.S4),
      verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Sun.S12),
    ) {
      // While the meter card is up it says what reading is doing (and has its own Details), so the bar does not say it a second time.
      val summary = StatusParts.summary(line, ix, cardShown)
      if (summary != null) {
        val upToDate = ix.phase == Phase.UP_TO_DATE && !line.namesOnly
        Icon(
          if (upToDate) Icons.Check else if (line.warning || ix.phase == Phase.PAUSED) Icons.Warning else Icons.Search,
          contentDescription = null, tint = if (line.error) tk.danger else tk.ink, modifier = Modifier.size(Sun.IconSmall),
        )
        Text(summary, style = SunType.Caption, color = tk.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(3f, fill = false))
        if (line.showBar) SunProgress(line.fraction, Modifier.width(120.dp), thickness = 8.dp)
        line.kinds?.let { Text(it, style = SunType.Caption, color = tk.ink2, maxLines = 1) }
      }
      if (!cardShown) SunTextButton(onClick = onDetails) { Text(t("index_detail_open"), style = SunType.Label) }
      if (line.working) SunTextButton(onClick = onPause) { Text(t("action_pause"), style = SunType.Label) }
      // SEARCH PACKAGE (A): the search's own progress ("12 so far · meaning in a moment"), between the reading status and the key hints
      SearchStatusLine(results, Modifier.weight(2f, fill = false))
      Spacer(Modifier.weight(0.01f))
      if (showKeys) {
        KeyHint("Up Down", t("key_select"))
        KeyHint("Enter", t("key_open"))
        KeyHint("Space", t("key_preview"))
        KeyHint("$mod K", t("key_search"))
        KeyHint("$mod Enter", t("key_folder"))
        KeyHint("$mod C", t("key_copy"))
        if (wide) KeyHint("Shift Up Down", t("key_choose_more"))
      }
    }
  }
}

@Composable
private fun KeyHint(cap: String, label: String) {
  val tk = LocalSunlight.current
  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Sun.S4)) {
    KeyCap(cap)
    Text(label, style = SunType.Caption, color = tk.ink2, maxLines = 1)
  }
}
