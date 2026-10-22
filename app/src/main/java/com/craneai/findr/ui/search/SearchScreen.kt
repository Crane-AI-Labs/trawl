package com.craneai.findr.ui.search

import com.craneai.findr.engine.Edition
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.runtime.State
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import com.craneai.findr.ui.common.rememberSharedIcons
import androidx.compose.runtime.DisposableEffect
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import com.craneai.findr.ui.common.GridFling
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.draw.clip
import com.craneai.findr.core.KindFilter
import com.craneai.findr.core.SortOrder
import androidx.activity.compose.BackHandler
import androidx.compose.ui.platform.LocalFocusManager
import com.craneai.findr.ui.common.Wordmark
import com.craneai.findr.ui.theme.Sun
import com.craneai.findr.ui.theme.SunIconAction
import com.craneai.findr.ui.theme.SunType
import com.craneai.findr.ui.theme.sunBorder
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.craneai.findr.R
import com.craneai.findr.ui.theme.LocalSunlight
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.craneai.findr.ui.common.formatCount
import com.craneai.findr.core.PauseReason
import com.craneai.findr.core.IndexStatus
import com.craneai.findr.core.IndexPhase
import com.craneai.findr.core.IndexedFile
import com.craneai.findr.ui.app.AccessState
import com.craneai.findr.ui.app.UiPrefs
import com.craneai.findr.ui.common.BrandMark
import com.craneai.findr.ui.common.FileActions
import com.craneai.findr.ui.common.OpenResult
import com.craneai.findr.ui.common.UiCounters
import com.craneai.findr.ui.index.IndexDetailSheet
import com.craneai.findr.ui.index.IndexMeterCard
import com.craneai.findr.ui.index.IndexViewModel
import com.craneai.findr.ui.index.MeterActions
import com.craneai.findr.ui.privacy.LocalPrivacy
import com.craneai.findr.ui.privacy.SensitiveChip
import com.craneai.findr.ui.privacy.SensitiveLeaveDialog
import com.craneai.findr.ui.theme.LocalReduceMotion
import com.craneai.findr.ui.theme.Motion
import com.craneai.findr.ui.theme.motionTween
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Opens the system's "free up space" page. */
private fun Context.openStorageSettings() {
  try {
    startActivity(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
  } catch (_: ActivityNotFoundException) {
    // No storage page on this phone; the sheet already says what is wrong.
  }
}

/**
 * Home. Header on top, the live meter under it, results in the middle, and the search dock at the bottom. All
 * decisions (what to list, in what order, what is strong or weak) were made in the view models.
 */
@Composable
fun SearchScreen(
  searchViewModel: SearchViewModel,
  indexViewModel: IndexViewModel,
  deliveryViewModel: com.craneai.findr.ui.delivery.DeliveryViewModel,
  access: AccessState,
  onOpenSettings: () -> Unit,
  onAllowAccess: () -> Unit,
  onRequestNotifications: () -> Unit = {},
) {
  val state by searchViewModel.state.collectAsStateWithLifecycle()
  // The status is NOT collected here. It re-emits every couple of seconds while the worker runs, and a collector at this
  // level recomposes the whole screen (header, grid and every thumbnail cell) each time, mid-scroll. Only the meter, the
  // sheet and the empty state read it, each in its own small scope.
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val snackbar = remember { SnackbarHostState() }
  var showSheet by rememberSaveable { mutableStateOf(false) }
  val openSheet = remember { { showSheet = true } }
  val activity = LocalActivity.current
  // Onboarding v2: what setup no longer asks is asked here. The first search that finds something opens the notification
  // question; the first "Mark as sensitive" offers the app lock (once, and only on a phone that has a screen lock).
  val uiPrefs = remember { UiPrefs(context) }
  var firstSearchDone by remember { mutableStateOf(uiPrefs.firstSearchDone) }
  val foundSomething = state.query.isNotBlank() && state.resultsQuery == state.query && !state.searching && state.feed.isNotEmpty()
  LaunchedEffect(foundSomething) { if (foundSomething && !firstSearchDone) { uiPrefs.firstSearchDone = true; firstSearchDone = true } }
  val privacyForOffer = LocalPrivacy.current
  var offerLock by rememberSaveable { mutableStateOf(false) }
  LaunchedEffect(searchViewModel, privacyForOffer) {
    searchViewModel.markedSensitive.collect {
      val p = privacyForOffer ?: return@collect
      if (!uiPrefs.lockOffered && !p.prefs.lockEnabled.value && p.deviceLockAvailable()) { uiPrefs.lockOffered = true; offerLock = true }
    }
  }
  // J1: "fully drawn" is when the search box and the recent files are on screen, not when the process started.
  LaunchedEffect(state.loaded) {
    if (state.loaded) {
      withFrameNanos { }
      activity?.reportFullyDrawn()
      // First-run J2: the first typed question used to wait ~3 s for the model to load (cold). Home is drawn, so load it
      // now, while the person is still reaching for the search box. A no-op until a model and some meaning exist.
      if (access.allFiles) searchViewModel.prewarm()
    }
  }
  // J2: one frame after a new feed was composed, its pixels are out; the view model logs FindrPerf for it.
  LaunchedEffect(state.feed) { withFrameNanos { }; searchViewModel.feedDrawn() }
  val textState = rememberQueryState(state.query)
  BindQuery(textState, searchViewModel::setQuery)
  // Find similar leaves the box empty (the chip says what is searched): words typed before it was tapped must not stay in the box.
  LaunchedEffect(state.similar?.id) { if (state.similar != null && textState.text.isNotEmpty()) textState.clearText() }
  // Typing counts as interaction too: every keystroke starts a short window in which the worker waits between files.
  LaunchedEffect(textState) {
    snapshotFlow { textState.text.toString() }.drop(1).collect {
      searchViewModel.setInteracting(true)
      searchViewModel.setInteracting(false)
    }
  }

  val noApp = stringResource(R.string.snack_no_app)
  val sample = stringResource(R.string.snack_sample)
  val copied = stringResource(R.string.snack_copied)
  fun report(result: OpenResult) {
    val message = when (result) {
      OpenResult.OPENED -> return
      // Android 13+ already shows its own "Copied" pill; a second one on top of it reads as a stutter (seen on the Infinix).
      OpenResult.COPIED -> if (android.os.Build.VERSION.SDK_INT >= 33) return else copied
      OpenResult.NO_APP -> noApp
      OpenResult.SAMPLE -> sample
    }
    scope.launch {
      snackbar.currentSnackbarData?.dismiss()
      snackbar.showSnackbar(message)
    }
  }
  // Remembered so the grid's cells see the same lambdas on every recomposition and can be skipped.
  val reportState = rememberUpdatedState(::report)
  val onOpen: (IndexedFile) -> Unit = remember(context) { { reportState.value(FileActions.open(context, it)) } }
  // A sensitive item leaves the phone (Share) or goes on the clipboard (Copy) only after a plain confirmation. The files to hand over are
  // held by their ids (so it survives a rotation); one file from a row or tile, or every ticked file from the selection bar.
  var leaveIds by rememberSaveable { mutableStateOf<List<Long>>(emptyList()) }
  var leaveIsCopy by rememberSaveable { mutableStateOf(false) }
  val shareLimit = stringResource(R.string.select_share_limit, MAX_SHARE)
  fun leave(files: List<IndexedFile>, copy: Boolean) {
    if (files.isEmpty()) return
    if (!copy && files.size > MAX_SHARE) {
      scope.launch { snackbar.currentSnackbarData?.dismiss(); snackbar.showSnackbar(shareLimit) }
      return
    }
    if (files.any { it.sensitive }) { leaveIsCopy = copy; leaveIds = files.map { it.id } }
    else reportState.value(if (copy) FileActions.copyAll(context, files) else FileActions.shareAll(context, files))
  }
  val onShare: (IndexedFile) -> Unit = remember(context) { { f -> leave(listOf(f), copy = false) } }
  val onCopy: (IndexedFile) -> Unit = remember(context) { { f -> leave(listOf(f), copy = true) } }
  val onShowFolder: (IndexedFile) -> Unit = remember(context) { { reportState.value(FileActions.showInFolder(context, it)) } }
  // The file whose single-file sheet is open (null = closed). Opened from "More" on the selection bar with one file ticked.
  // Held by uri so it survives a rotation.
  var actionUri by rememberSaveable { mutableStateOf<String?>(null) }
  fun fileByUri(uri: String?): IndexedFile? = uri?.let { u -> state.feed.shownFiles().firstOrNull { it.uri == u } }
  val actionFile = fileByUri(actionUri)
  val selecting = state.selected.isNotEmpty()
  val leaveFiles = state.feed.shownFiles().filter { it.id in leaveIds }
  val focus = LocalFocusManager.current
  var pickingDates by rememberSaveable { mutableStateOf(false) }

  // While sensitive items are on screen the window is FLAG_SECURE (no screenshots, blank card in recents).
  val privacy = LocalPrivacy.current
  LaunchedEffect(privacy, state.showsSensitive) { privacy?.setSensitiveOnScreen("main", state.showsSensitive) }
  DisposableEffect(privacy) { onDispose { privacy?.setSensitiveOnScreen("main", false) } }
  val markedMsg = stringResource(R.string.snack_marked_sensitive)
  val unmarkedMsg = stringResource(R.string.snack_unmarked_sensitive)
  val markedManyMsg = stringResource(R.string.snack_marked_sensitive_many)
  val unmarkedManyMsg = stringResource(R.string.snack_unmarked_sensitive_many)
  // Whether the search box has focus. Held here, read only inside Results, so a focus change recomposes the list and not the screen.
  val fieldFocused = remember { mutableStateOf(false) }

  // A short window (a phone or tablet on its side): the header, cards, meter and chips are the first thing in the list, not fixed
  // above it, so they scroll away and the results get the screen (ShortWindow). A tall window keeps the stacked layout as it was.
  val short = rememberShortWindow()
  val widthDp = LocalConfiguration.current.screenWidthDp
  Column(
    Modifier
      .fillMaxSize()
      .background(MaterialTheme.colorScheme.background),
  ) {
    if (selecting) {
      val shown = state.feed.shownFiles()
      SelectionTopBar(
        count = state.selected.size,
        allSelected = shown.isNotEmpty() && state.selected.size >= shown.size,
        onSelectAll = searchViewModel::selectAll,
        onExit = searchViewModel::clearSelection,
      )
    } else if (!short) Header(onOpenSettings, Modifier.statusBarsPadding())
    // Home shows at most one card above the meter (crash report, missing access, models, pause note, notification question),
    // and a short strip instead of a full card on a wide, short or large-text window, so the results always keep their room.
    val searchingNow = state.query.isNotBlank()
    val homeCompact = rememberHomeCompact()
    val homeCards: @Composable () -> Unit = {
      HomeCardSlot(
        deliveryViewModel = deliveryViewModel,
        indexViewModel = indexViewModel,
        access = access,
        fieldFocused = fieldFocused,
        searching = searchingNow,
        firstSearchDone = firstSearchDone,
        onRequestNotifications = onRequestNotifications,
        onAllowAccess = onAllowAccess,
        compact = homeCompact,
      )
    }
    val meter: @Composable (Modifier) -> Unit = { m ->
      if (access.allFiles) {
        MeterUnlessTyping(
          fieldFocused = fieldFocused,
          indexViewModel = indexViewModel,
          compact = searchingNow || homeCompact,
          onClick = openSheet,
          modifier = m,
        )
      }
    }
    val sensitiveChip: @Composable () -> Unit = {
      SensitiveChip(state.hiddenCount, state.revealedForNow && state.showsSensitive, Modifier.padding(horizontal = Sun.Gutter, vertical = Sun.S4))
    }
    val meterPadding = Modifier.padding(horizontal = Sun.Gutter, vertical = Sun.S4)
    if (!short) {
      homeCards()
      meter(meterPadding)
      sensitiveChip()
    }
    val shortTop: (@Composable () -> Unit)? = if (!short) null else {
      {
        Column {
          // Wide enough: the wordmark, the meter and Settings share one line. Narrower (or choosing files, when the bar above
          // already says so), the header and the meter stack as they do on a phone.
          if (!selecting) ShortHeader(onOpenSettings, if (ShortWindow.mergedHeader(widthDp) && access.allFiles) meter else null)
          homeCards()
          if (selecting || !ShortWindow.mergedHeader(widthDp)) meter(meterPadding)
          sensitiveChip()
        }
      }
    }

    Box(Modifier.weight(1f).fillMaxWidth().then(if (short && !selecting) Modifier.statusBarsPadding() else Modifier)) {
      CompositionLocalProvider(LocalClockSkewMs provides state.clockSkewMs) {   // ages count from the proven "now" when the clock is behind its files
        Results(state, indexViewModel, onOpen, onShare, onCopy, searchViewModel::toggleSelected, searchViewModel::toggleWeak, searchViewModel::setInteracting,
          onFilter = searchViewModel::setFilter, listCovered = showSheet, onListVisible = searchViewModel::setListVisible,
          fieldFocused = fieldFocused,
          onSort = searchViewModel::setSort, onDatePreset = searchViewModel::setDatePreset, onPickDates = { pickingDates = true }, onClearDate = searchViewModel::clearDate,
          onClearSimilar = searchViewModel::clearSimilar, shortTop = shortTop)
      }
    }

    SnackbarHost(snackbar)
    if (selecting) {
      val picked = searchViewModel.selectedFiles()
      SelectionActionBar(
        onShare = { leave(picked, copy = false) },
        onCopy = { leave(picked, copy = true) },
        onMark = if (picked.any { !it.sensitive }) ({
          searchViewModel.markSensitive(picked, true)
          scope.launch { snackbar.currentSnackbarData?.dismiss(); snackbar.showSnackbar(markedManyMsg) }
        }) else null,
        onUnmark = if (picked.any { it.markedSensitive }) ({
          searchViewModel.markSensitive(picked, false)
          scope.launch { snackbar.currentSnackbarData?.dismiss(); snackbar.showSnackbar(unmarkedManyMsg) }
        }) else null,
        onMore = if (picked.size == 1) ({ actionUri = picked.single().uri }) else null,
      )
    } else SearchDock(
      textState = textState,
      status = dockStatus(state),
      fieldFocused = fieldFocused,
      onFocused = { fieldFocused.value = true; searchViewModel.prewarm(); searchViewModel.setSearchFocus(true) },
      onFocusLost = { fieldFocused.value = false; searchViewModel.setSearchFocus(false) },
      learning = { LiveLearningLine(indexViewModel) },
    )
  }

  // Choosing files: Back leaves selection first. Choosing starts with the keyboard put away and the search box not in use.
  BackHandler(enabled = selecting) { searchViewModel.clearSelection() }
  LaunchedEffect(selecting) {
    if (selecting) { focus.clearFocus(); fieldFocused.value = false; searchViewModel.setSearchFocus(false) }
  }
  if (pickingDates) DateRangeDialog(
    onApply = { from, to -> pickingDates = false; searchViewModel.setDateRange(from, to) },
    onDismiss = { pickingDates = false },
  )

  actionFile?.let { file ->
    FileActionSheet(
      file = file,
      onShare = { actionUri = null; searchViewModel.clearSelection(); onShare(file) },
      onCopy = { actionUri = null; searchViewModel.clearSelection(); onCopy(file) },
      onOpen = { actionUri = null; onOpen(file) },
      onShowFolder = { actionUri = null; onShowFolder(file) },
      onFindSimilar = if (Edition.FIND_SIMILAR) ({ actionUri = null; searchViewModel.findSimilar(file) }) else null,
      onMarkSensitive = { on ->
        actionUri = null
        searchViewModel.markSensitive(file, on)
        scope.launch { snackbar.currentSnackbarData?.dismiss(); snackbar.showSnackbar(if (on) markedMsg else unmarkedMsg) }
      },
      onDismiss = { actionUri = null },
    )
  }

  if (offerLock) com.craneai.findr.ui.privacy.LockOfferDialog(onDone = { offerLock = false })

  if (leaveFiles.isNotEmpty()) {
    SensitiveLeaveDialog(
      copy = leaveIsCopy,
      many = leaveFiles.size > 1,
      onConfirm = {
        leaveIds = emptyList()
        reportState.value(if (leaveIsCopy) FileActions.copyAll(context, leaveFiles) else FileActions.shareAll(context, leaveFiles))
      },
      onDismiss = { leaveIds = emptyList() },
    )
  }

  if (showSheet) {
    val status by indexViewModel.status.collectAsStateWithLifecycle()
    val settings by indexViewModel.settings.collectAsStateWithLifecycle()
    IndexDetailSheet(
      status = status,
      settings = settings,
      actions = MeterActions(
        onPause = indexViewModel::pause,
        onResume = indexViewModel::resume,
        onReadOnBattery = indexViewModel::allowOnBattery,
        onAllowAccess = onAllowAccess,
        onFreeSpace = { context.openStorageSettings() },
        onRetry = indexViewModel::retry,
      ),
      onDismiss = { showSheet = false },
    )
  }
}

/** The only place (besides the sheet) that follows the live status, so its updates redraw the meter and nothing else. */
@Composable
private fun LiveMeter(indexViewModel: IndexViewModel, compact: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
  val status by indexViewModel.status.collectAsStateWithLifecycle()
  IndexMeterCard(status = status, compact = compact, onClick = onClick, modifier = modifier)
}

/**
 * The meter, except while typing (box focused, keyboard up): then it folds into the line under the results (LiveLearningLine),
 * so the results get its room. It comes back as it was when the keyboard hides. Reads the keyboard here, not above.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MeterUnlessTyping(
  fieldFocused: State<Boolean>,
  indexViewModel: IndexViewModel,
  compact: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  if (fieldFocused.value && WindowInsets.isImeVisible) return
  LiveMeter(indexViewModel = indexViewModel, compact = compact, onClick = onClick, modifier = modifier)
}

/** How far learning has got, for the line under the results while typing. Equal values do not redraw. */
internal data class Learning(val understood: Int, val known: Int, val charger: Boolean, val paused: Boolean)

internal fun IndexStatus.learning(): Learning? {
  if (phase == IndexPhase.UP_TO_DATE || phase == IndexPhase.NOT_STARTED || filesKnown == 0 || filesUnderstood >= filesReadable) return null
  val charger = (needsCharger && !lightOnBattery) || pauseReason == PauseReason.NOT_CHARGING
  return Learning(filesUnderstood, filesReadable, charger, paused = phase == IndexPhase.PAUSED || phase == IndexPhase.ERROR)
}

/**
 * The meter and the "still learning" note, folded into one part of the line under the results while typing: "Still learning:
 * 1,240 of 3,000 · more results may appear". Nothing when every file is understood. Follows the live status in its own scope.
 */
@Composable
private fun LiveLearningLine(indexViewModel: IndexViewModel) {
  val learning by remember(indexViewModel) { indexViewModel.status.map { it.learning() }.distinctUntilChanged() }
    .collectAsStateWithLifecycle(initialValue = null)
  val l = learning ?: return
  val t = LocalSunlight.current
  val text = stringResource(
    when {
      l.charger -> R.string.typing_learning_charger
      l.paused -> R.string.typing_learning_paused
      else -> R.string.typing_learning
    },
    formatCount(l.understood), formatCount(l.known),
  )
  Row(verticalAlignment = Alignment.CenterVertically) {
    Icon(Icons.Outlined.Schedule, contentDescription = null, tint = t.ink2, modifier = Modifier.size(18.dp))
    Spacer(Modifier.width(Sun.S8))
    Text(text, style = SunType.Caption, color = t.ink2)
  }
}

/** The note above results while the index is still filling in; it says why meaning is not ready when it is the charger. */
@Composable
private fun LiveIncompleteNote(indexViewModel: IndexViewModel) {
  val charger by remember(indexViewModel) { indexViewModel.status.map { it.needsCharger && !it.lightOnBattery }.distinctUntilChanged() }
    .collectAsStateWithLifecycle(initialValue = false)
  IncompleteNote(waitingForCharger = charger)
}

/** Empty home: a fresh phone (nothing known yet) or a chip with nothing in it. Reads the file count here, not above. */
@Composable
private fun EmptyHome(indexViewModel: IndexViewModel) {
  val filesKnown by remember(indexViewModel) { indexViewModel.status.map { it.filesKnown }.distinctUntilChanged() }
    .collectAsStateWithLifecycle(initialValue = 0)
  if (filesKnown == 0) FreshPhone() else EmptyFilter()
}

/** The wordmark, and Settings as an icon with its word (every icon has its word). */
@Composable
private fun Header(onOpenSettings: () -> Unit, modifier: Modifier = Modifier) {
  val settings = stringResource(R.string.search_settings)
  Row(
    modifier.fillMaxWidth().padding(start = Sun.Gutter, end = Sun.S8, top = Sun.S4),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Wordmark(Modifier.weight(1f))
    SunIconAction(Icons.Outlined.Settings, settings, settings, onOpenSettings)
  }
}

/**
 * The header of a short window: the wordmark, the live meter in the space between, and Settings, on one line (it is the first thing
 * in the list, so it scrolls away). Without a [meter] (narrow window, no access) the space between is empty, as in [Header].
 */
@Composable
private fun ShortHeader(onOpenSettings: () -> Unit, meter: (@Composable (Modifier) -> Unit)?) {
  val settings = stringResource(R.string.search_settings)
  Row(
    Modifier.fillMaxWidth().padding(start = Sun.Gutter, end = Sun.S8, top = Sun.S4),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Wordmark()
    if (meter != null) meter(Modifier.weight(1f).padding(horizontal = Sun.S16)) else Spacer(Modifier.weight(1f))
    SunIconAction(Icons.Outlined.Settings, settings, settings, onOpenSettings)
  }
}

/** The line above the search box: how many so far while meaning is still coming, then how many were found. */
@Composable
private fun dockStatus(state: SearchUiState): DockStatus? {
  if ((state.query.isBlank() && state.similar == null) || state.nothingFound) return null
  // Every file the answer holds: those listed, plus the weaker ones still folded away.
  // (A date typed alone, "last week", lists that week's files without a search: the same count.)
  val shown = run {
    val listed = state.feed.count { it is FeedItem.Cell || it is FeedItem.Row }
    val folded = state.feed.filterIsInstance<FeedItem.WeakToggle>().firstOrNull()?.takeIf { !it.expanded }?.count ?: 0
    listed + folded
  }
  return when {
    state.searching && shown == 0 -> DockStatus(Icons.Outlined.Schedule, stringResource(R.string.search_status_words))
    state.searching -> DockStatus(Icons.Outlined.Schedule, pluralStringResource(R.plurals.search_status_so_far, shown, shown))
    else -> DockStatus(Icons.Outlined.Search, pluralStringResource(R.plurals.search_status_found, shown, shown))
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Results(
  state: SearchUiState,
  indexViewModel: IndexViewModel,
  onOpen: (IndexedFile) -> Unit,
  onShare: (IndexedFile) -> Unit,
  onCopy: (IndexedFile) -> Unit,
  onToggle: (IndexedFile) -> Unit,
  onToggleWeak: () -> Unit,
  onInteracting: (Boolean) -> Unit,
  onFilter: (KindFilter) -> Unit,
  listCovered: Boolean = false,
  onListVisible: (Boolean) -> Unit = {},
  fieldFocused: State<Boolean> = mutableStateOf(false),
  onSort: (SortOrder) -> Unit = {},
  onDatePreset: (com.craneai.findr.core.DatePreset) -> Unit = {},
  onPickDates: () -> Unit = {},
  onClearDate: () -> Unit = {},
  onClearSimilar: () -> Unit = {},
  /** A short window (ShortWindow): the header, cards, meter and chips, drawn as the first thing in the list so they scroll away with it. */
  shortTop: (@Composable () -> Unit)? = null,
) {
  val reduce = LocalReduceMotion.current
  val short = shortTop != null
  // Typing: the box has focus and the keyboard is up, so only a little of the list shows above the keyboard. Rows go compact
  // (one line each for name, what and where, matched words) and come back in full when the keyboard hides. Layout only.
  // While typing the filter line and the section headings step aside too, so the rows get the little room there is; the
  // filter is then changed from the box itself (SearchDock's filter button). Both come back when the keyboard hides.
  val compactRows = fieldFocused.value && WindowInsets.isImeVisible
  val selecting = state.selected.isNotEmpty()
  val shownFeed = remember(state.feed, compactRows) { if (compactRows) state.feed.filter { it !is FeedItem.Header && it !is FeedItem.PhotosPeek } else state.feed }
  // The filters lead the list: big tiles above the newest files, one compact line above search results. Inside the grid, so
  // they scroll away with it and the grid keeps the whole screen between the meter and the search box.
  // The sort and date chips sit under the kind filters. A date typed in the box ("last week") is a chip here too (never inside the box).
  val searching = (state.query.isNotBlank() || state.similar != null) && state.mode == SearchMode.RESULTS
  val filters: @Composable () -> Unit = if (short) ({
    // Short window: the kind, sort and date chips share one line (the tiles of the home screen are too tall for it too).
    FilterSortRow(
      state.filter, onFilter, state.sort, searching, state.date, onSort, onDatePreset, onPickDates, onClearDate,
      Modifier.padding(horizontal = Sun.S4, vertical = Sun.S4), similar = state.similar?.name, onClearSimilar = onClearSimilar,
    )
  }) else ({
    Column {
      if (state.mode == SearchMode.RESULTS) FilterRow(state.filter, onFilter, Modifier.padding(horizontal = Sun.S4, vertical = Sun.S4))
      else FilterTiles(state.filter, onFilter, Modifier.padding(horizontal = Sun.S4, vertical = Sun.S4))
      SortDateRow(
        state.sort, searching, state.date, onSort, onDatePreset, onPickDates, onClearDate, Modifier.padding(horizontal = Sun.S4, vertical = Sun.S4),
        similar = state.similar?.name, onClearSimilar = onClearSimilar,
      )
    }
  })
  val gridState = rememberLazyGridState()
  val scope = rememberCoroutineScope()
  // While the list moves (finger down or gliding) the index worker yields between files; it resumes ~1.5 s after.
  LaunchedEffect(gridState) {
    try {
      snapshotFlow { gridState.isScrollInProgress }.collect { onInteracting(it); if (!it) UiCounters.log("scroll-stopped") }
    } finally {
      onInteracting(false)
    }
  }
  // A new question or a different chip starts at the top, where the best match is.
  val userScrolled = remember { mutableStateOf(false) }
  LaunchedEffect(state.resultsQuery, state.filter, state.similar?.id) {
    userScrolled.value = false
    gridState.scrollToItem(0)
  }
  LaunchedEffect(gridState) {
    gridState.interactionSource.interactions.collect { if (it is DragInteraction.Start) userScrolled.value = true }
  }
  // Search answers in steps (words first, then meaning). When the meaning step puts new best matches above what was
  // first, the grid keeps its anchor on the old first item and the best match ends up above the screen (seen on the
  // A06: "motorcycle taxi" opened on the documents, the boda photo hidden above). requestScrollToItem is applied in
  // the next layout pass and overrides that anchoring. Not if the user has dragged the list themselves.
  val leadKey = state.feed.firstOrNull { it !is FeedItem.Header }?.key
  // Never while a finger is on the list or it is still gliding (the suspected cause of a reported "freezing at the top"): only a list
  // the user has not touched since the last question or chip is moved back to the top.
  LaunchedEffect(leadKey) {
    if (!userScrolled.value && !gridState.isScrollInProgress && gridState.firstVisibleItemIndex > 0) gridState.requestScrollToItem(0)
  }
  // The keyboard going down puts the filters row (sort, date, kind) back above the first file. The grid keeps its anchor on
  // that file, so the row would sit above the screen where nobody finds it: bring the top back when the list was not scrolled.
  LaunchedEffect(compactRows) {
    if (!compactRows && !userScrolled.value && !gridState.isScrollInProgress && gridState.firstVisibleItemIndex > 0) gridState.requestScrollToItem(0)
  }

  val notes: @Composable () -> Unit = {
    if (state.mode == SearchMode.RESULTS && state.repairing) RepairingNote()
    // While typing the note is folded into the line under the results (LiveLearningLine), with the meter.
    if (state.mode == SearchMode.RESULTS && state.indexIncomplete && !state.nothingFound && !compactRows) LiveIncompleteNote(indexViewModel)
  }
  // A short window keeps the top of the screen in the list: not while typing (the keyboard has taken the room; the meter and the
  // note fold into the line under the results then), and the notes ride with it.
  val showTop = short && !compactRows
  val showNotes = short && (state.mode == SearchMode.RESULTS && state.repairing || showTop)
  val shortLead: @Composable () -> Unit = { if (showTop) shortTop?.invoke(); if (showNotes) notes() }
  Column(Modifier.fillMaxSize()) {
    if (!short) notes()
    when {
      state.mode == SearchMode.RESULTS && state.nothingFound -> {
        // The empty-state bodies scroll by themselves (EmptyStates.kt), so they get a fixed height inside the lazy list: a
        // scrolling parent would hand them unbounded height, which Compose refuses.
        if (short) LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
          item { shortLead() }
          item { Box(Modifier.padding(horizontal = Sun.S12)) { filters() } }
          item {
            Box(Modifier.fillMaxWidth().height(SHORT_EMPTY_BODY), contentAlignment = Alignment.Center) {
              NothingFound(query = state.resultsQuery, indexIncomplete = state.indexIncomplete, date = state.date?.filter?.label, similarTo = state.similar?.name)
            }
          }
        } else {
          Box(Modifier.padding(horizontal = Sun.S12)) { filters() }
          Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            NothingFound(query = state.resultsQuery, indexIncomplete = state.indexIncomplete, date = state.date?.filter?.label, similarTo = state.similar?.name)
          }
        }
      }
      state.feed.isEmpty() && state.mode == SearchMode.RECENT && !state.searching -> {
        if (short) LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
          item { shortLead() }
          if (!compactRows) item { Box(Modifier.padding(horizontal = Sun.S12)) { filters() } }
          item {
            Box(Modifier.fillMaxWidth().height(SHORT_EMPTY_BODY), contentAlignment = Alignment.Center) {
              EmptyHome(indexViewModel)
            }
          }
        } else {
          if (!compactRows) Box(Modifier.padding(horizontal = Sun.S12)) { filters() }
          Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            EmptyHome(indexViewModel)
          }
        }
      }
      else -> {
        val gridIcons = rememberSharedIcons()
        // The grid is on screen unless the meter's sheet covers it: GPU encodes hold back meanwhile (FindrEngine.gpuShouldWait).
        DisposableEffect(listCovered) {
          if (!listCovered) onListVisible(true)
          onDispose { if (!listCovered) onListVisible(false) }
        }
        // How fast the grid moves, for GridFling: sampled every 100 ms while it scrolls, reset when it stops.
        LaunchedEffect(gridState) {
          snapshotFlow { gridState.isScrollInProgress }.collectLatest { scrolling ->
            if (!scrolling) { GridFling.fast = false; return@collectLatest }
            var lastIndex = gridState.firstVisibleItemIndex
            var lastNs = System.nanoTime()
            try {
              while (true) {
                delay(100)
                val index = gridState.firstVisibleItemIndex
                val now = System.nanoTime()
                GridFling.fast = abs(index - lastIndex) * 1e9 / (now - lastNs) > GridFling.FAST_CELLS_PER_S
                lastIndex = index; lastNs = now
              }
            } finally { GridFling.fast = false }
          }
        }
        LazyVerticalGrid(
          columns = GridCells.Adaptive(104.dp),
          state = gridState,
          modifier = Modifier.weight(1f).fillMaxWidth(),
          contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = if (compactRows) 2.dp else 6.dp, bottom = 12.dp),
          horizontalArrangement = Arrangement.spacedBy(6.dp),
          verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          // The filter lines stay while typing in RESULTS (the box holds no chips: its width is for the words, and an active kind,
          // sort or date shows here, highlighted, with its cross). The big home tiles do hide to leave room above the keyboard.
          // A short window: the top of the screen is the first thing in the list (full width, so it scrolls away with it).
          if (showTop || showNotes) {
            item(key = "short-top", span = { GridItemSpan(maxLineSpan) }, contentType = "short-top") { Column { shortLead() } }
          }
          if (!compactRows || state.mode == SearchMode.RESULTS) {
            item(key = if (state.mode == SearchMode.RESULTS) "filters-row" else "filters-tiles", span = { GridItemSpan(maxLineSpan) }, contentType = "filters") {
              filters()
            }
          }
          items(
            items = shownFeed,
            key = { it.key },
            span = { item -> if (item is FeedItem.Cell) GridItemSpan(1) else GridItemSpan(maxLineSpan) },
            contentType = { it::class },
          ) { item ->
            // Placement animation is for search answers re-ordering as the meaning step lands; on the recent grid (thousands
            // of cells) it is a per-cell cost on every scroll frame for a move that almost never happens.
            val motion = if (reduce || state.mode != SearchMode.RESULTS) Modifier else Modifier.animateItem()
            when (item) {
              is FeedItem.Header -> SectionHeader(stringResource(item.title), motion)
              is FeedItem.PhotosPeek -> PhotosPeekHeader(item, onOpen = {
                // The rest of the photo group: its own heading further down. The filters line is item 0 of the grid.
                val target = shownFeed.indexOfFirst { it.key == "header-${R.string.section_photos_videos}" }
                // Items before the feed: the short window's top (when shown) and the filters line (when shown).
                val lead = (if (showTop || showNotes) 1 else 0) + (if (!compactRows || state.mode == SearchMode.RESULTS) 1 else 0)
                if (target >= 0) scope.launch { gridState.animateScrollToItem(target + lead) }
              }, motion)
              is FeedItem.Cell -> MediaCell(
                item.hit,
                onOpen = { onOpen(item.hit.file) },
                onShare = { onShare(item.hit.file) },
                onToggle = { onToggle(item.hit.file) },
                modifier = motion,
                icons = gridIcons,
                selecting = selecting,
                selected = item.hit.file.id in state.selected,
              )
              is FeedItem.Row -> FileRow(
                item.hit,
                onOpen = { onOpen(item.hit.file) },
                onShare = { onShare(item.hit.file) },
                onCopy = { onCopy(item.hit.file) },
                onToggle = { onToggle(item.hit.file) },
                modifier = motion,
                compact = compactRows || short,
                selecting = selecting,
                selected = item.hit.file.id in state.selected,
              )
              is FeedItem.WeakToggle -> WeakToggle(item, onToggleWeak, motion)
            }
          }
        }
      }
    }
  }
}

@Composable
private fun SectionHeader(text: String, modifier: Modifier = Modifier) {
  Text(
    text,
    style = SunType.Label,
    color = MaterialTheme.colorScheme.onBackground,
    modifier = modifier.fillMaxWidth().padding(start = Sun.S4, top = Sun.S16, bottom = Sun.S4).semantics { heading() },
  )
}

/** "Photos and videos (12)" over the peeked cells. With more photos below it is a button that jumps to them. */
@Composable
private fun PhotosPeekHeader(item: FeedItem.PhotosPeek, onOpen: () -> Unit, modifier: Modifier = Modifier) {
  val title = stringResource(R.string.section_photos_videos_count, item.count)
  Row(
    modifier
      .fillMaxWidth()
      .padding(top = Sun.S16)
      .then(if (item.more) Modifier.clip(MaterialTheme.shapes.small).clickable(onClickLabel = stringResource(R.string.section_photos_videos), role = Role.Button, onClick = onOpen) else Modifier)
      .defaultMinSize(minHeight = if (item.more) Sun.Target else 0.dp)
      .padding(start = Sun.S4, bottom = Sun.S4),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(title, style = SunType.Label, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.weight(1f).semantics { heading() })
    if (item.more) Icon(Icons.Rounded.ExpandMore, contentDescription = null, tint = MaterialTheme.colorScheme.onBackground, modifier = Modifier.size(Sun.Icon))
  }
}

@Composable
private fun WeakToggle(item: FeedItem.WeakToggle, onToggle: () -> Unit, modifier: Modifier = Modifier) {
  val reduce = LocalReduceMotion.current
  val rotation by animateFloatAsState(if (item.expanded) 180f else 0f, motionTween(reduce, Motion.Quick), label = "weakChevron")
  val label = stringResource(if (item.expanded) R.string.search_weak_collapse else R.string.search_weak_expand)
  Column(modifier.fillMaxWidth()) {
    if (item.noStrongMatches) {
      Text(
        stringResource(R.string.search_weak_only),
        style = SunType.Body,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = Sun.S4, top = Sun.S12, end = Sun.S4, bottom = Sun.S4),
      )
    }
    // An outlined row that opens and closes the fold: a button, so it has an edge.
    Row(
      Modifier
        .padding(top = Sun.S8)
        .fillMaxWidth()
        .defaultMinSize(minHeight = Sun.Target)
        .clip(MaterialTheme.shapes.small)
        .clickable(onClickLabel = label, role = Role.Button, onClick = onToggle)
        .sunBorder(MaterialTheme.shapes.small)
        .padding(horizontal = Sun.S16),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        stringResource(R.string.search_weak_toggle, item.count),
        style = SunType.Label,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.weight(1f),
      )
      Icon(
        Icons.Rounded.ExpandMore,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.size(Sun.Icon).graphicsLayer { rotationZ = rotation },
      )
    }
    Spacer(Modifier.height(Sun.S8))
  }
}
