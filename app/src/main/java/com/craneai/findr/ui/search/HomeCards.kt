package com.craneai.findr.ui.search

import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.craneai.findr.R
import com.craneai.findr.diag.Diag
import com.craneai.findr.ui.app.AccessState
import com.craneai.findr.ui.app.AppViewModel
import com.craneai.findr.ui.app.UiPrefs
import com.craneai.findr.ui.background.BackgroundAccess
import com.craneai.findr.ui.background.BackgroundPauseCard
import com.craneai.findr.ui.background.SamsungSleep
import com.craneai.findr.ui.background.SamsungSleepCard
import com.craneai.findr.ui.common.HomeBanner
import com.craneai.findr.ui.common.appString
import com.craneai.findr.ui.delivery.DeliveryViewModel
import com.craneai.findr.ui.delivery.GetModelsCard
import com.craneai.findr.ui.delivery.modelsCardWanted
import com.craneai.findr.ui.delivery.modelsHideKey
import com.craneai.findr.ui.index.IndexViewModel
import com.craneai.findr.ui.keeplearning.KeepLearning
import com.craneai.findr.ui.keeplearning.KeepLearningActivity
import com.craneai.findr.ui.report.CrashReportCard
import com.craneai.findr.ui.theme.Sun
import com.craneai.findr.ui.theme.SunButton
import com.craneai.findr.ui.theme.SunCard
import com.craneai.findr.ui.theme.SunTextButton
import com.craneai.findr.ui.theme.SunType
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** The cards Home can show above the results, in the order they win the one slot. */
internal enum class HomeCard { CRASH, ACCESS, MODELS, PAUSE, SAMSUNG, NOTIFICATIONS }

/**
 * Which Home card shows, and in what form. Pure, so it is unit-tested; the composables below only ask it.
 *
 * Two rules, from the RC QA run on the tablet in landscape (the full "Remove the downloaded file?" card took the screen and cut
 * the results off at the chip row):
 *  - One card at a time. Every card that wants the slot is listed in [HomeCard] order and the first one wins; the others wait.
 *  - A card is a strip ([compact]) when the screen is wide or short or the text is large, and always while the person is searching.
 */
internal object HomeCards {
  /** Settings' "Large" font is 1.3; the margin is for float rounding. */
  const val LARGE_FONT_SCALE = 1.29f
  const val SHORT_DP = 480

  /** Landscape (wider than tall), a short window (split screen, phone on its side), or large text: a full card would crowd the results. */
  fun compact(widthDp: Int, heightDp: Int, fontScale: Float): Boolean = widthDp > heightDp || heightDp < SHORT_DP || fontScale >= LARGE_FONT_SCALE

  /**
   * The one card to show. [wants] says which cards want the slot. [typing] (keyboard up on the search box) puts every card away, so
   * the results get the room; [searching] puts away the three that ask a favour (the pause note, the Samsung battery note and the notification question), which
   * are not worth a person's attention while they are looking for something. A card that is put away does not hand its slot to the
   * next one: that would show the wrong card at the wrong time.
   */
  fun pick(wants: Set<HomeCard>, typing: Boolean, searching: Boolean): HomeCard? {
    if (typing) return null
    val first = HomeCard.entries.firstOrNull { it in wants } ?: return null
    if (searching && (first == HomeCard.PAUSE || first == HomeCard.SAMSUNG || first == HomeCard.NOTIFICATIONS)) return null
    return first
  }

  /** While searching a card is always the short form. */
  fun asStrip(compact: Boolean, searching: Boolean): Boolean = compact || searching

  /** A full card never takes more than this share of the window's height; past it, the card scrolls inside itself. */
  const val FULL_CARD_MAX_SHARE = 0.4f
}

/** Whether Home should use the short forms on this window (landscape, short, or large text). Reads the live configuration. */
@Composable
internal fun rememberHomeCompact(): Boolean {
  val c = LocalConfiguration.current
  return HomeCards.compact(c.screenWidthDp, c.screenHeightDp, c.fontScale)
}

/**
 * The one slot above the meter. It holds at most one card: the crash report, the missing-access banner, the models delivery (Get /
 * progress / refusal / remove-the-file), the Transsion pause note, the Samsung battery note, or the notification question, in that order of priority. Each
 * has a short form for [compact] windows with an action and a way to put it away. The pause note and the notification question
 * (onboarding v2's just-in-time asks) wait until the person is not searching or typing.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HomeCardSlot(
  deliveryViewModel: DeliveryViewModel,
  indexViewModel: IndexViewModel,
  access: AccessState,
  fieldFocused: State<Boolean>,
  searching: Boolean,
  firstSearchDone: Boolean,
  onRequestNotifications: () -> Unit,
  onAllowAccess: () -> Unit,
  compact: Boolean,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val prefs = remember { UiPrefs(context) }
  var pauseDismissed by remember { mutableStateOf(prefs.pauseCardDismissed) }
  var samsungDismissed by remember { mutableStateOf(prefs.samsungCardDismissed) }
  var notificationsAsked by remember { mutableStateOf(prefs.notificationsAsked) }
  // Samsung: whether Battery is already Unrestricted. Read again every time the app comes back, since the button leaves for Settings.
  val samsung = remember { SamsungSleep.appliesHere() }
  var unrestricted by remember { mutableStateOf(samsung && SamsungSleep.isUnrestricted(context)) }
  LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { if (samsung) unrestricted = SamsungSleep.isUnrestricted(context) }
  // Put away for now, per card; the models strip is remembered against its phase (see modelsHideKey), the access one for the session.
  var accessHidden by rememberSaveable { mutableStateOf(false) }
  var modelsHiddenFor by rememberSaveable { mutableStateOf<String?>(null) }

  val crash by Diag.pending.collectAsStateWithLifecycle()
  val modelsWanted = modelsCardWanted(deliveryViewModel)
  val modelsKey = modelsHideKey(deliveryViewModel)
  // Read in this small scope: the status re-emits every couple of seconds, and only "are files still waiting" matters here.
  val waiting by remember(indexViewModel) { indexViewModel.status.map { it.filesKnown > 0 && KeepLearning.remaining(it) > 0 }.distinctUntilChanged() }
    .collectAsStateWithLifecycle(initialValue = false)

  // Where the worker has watched the phone work with the screen off, the "this phone pauses apps" card isn't needed (Infinix class).
  var screenOffWorks by remember { mutableStateOf(screenOffWorks(context)) }
  LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { screenOffWorks = screenOffWorks(context) }

  val wants = buildSet {
    if (crash > 0) add(HomeCard.CRASH)
    if (!access.allFiles && !accessHidden) add(HomeCard.ACCESS)
    if (modelsWanted && modelsHiddenFor != modelsKey) add(HomeCard.MODELS)
    if (BackgroundAccess.appliesHere() && !screenOffWorks && access.allFiles && waiting && !pauseDismissed) add(HomeCard.PAUSE)
    if (SamsungSleep.shouldShow(samsung, access.allFiles, waiting, unrestricted, samsungDismissed)) add(HomeCard.SAMSUNG)
    if (access.allFiles && firstSearchDone && AppViewModel.notificationsNeedRuntimePrompt() && !access.notifications && !notificationsAsked) add(HomeCard.NOTIFICATIONS)
  }
  val typing = fieldFocused.value && WindowInsets.isImeVisible
  val card = HomeCards.pick(wants, typing, searching) ?: return
  val strip = HomeCards.asStrip(compact, searching)

  val body: @Composable () -> Unit = {
    when (card) {
      HomeCard.CRASH -> CrashReportCard(Modifier, compact = strip)
      HomeCard.ACCESS -> AccessBanner(onAllow = onAllowAccess, compact = strip, onHide = { accessHidden = true })
      HomeCard.MODELS -> GetModelsCard(deliveryViewModel, access, confirmDone = false, compact = strip, onHide = { modelsHiddenFor = modelsKey })
      HomeCard.PAUSE -> BackgroundPauseCard(
        onKeepLearning = {
          prefs.pauseCardDismissed = true
          pauseDismissed = true
          context.startActivity(Intent(context, KeepLearningActivity::class.java))
        },
        onDismiss = { prefs.pauseCardDismissed = true; pauseDismissed = true },
        compact = strip,
      )
      HomeCard.SAMSUNG -> SamsungSleepCard(
        onOpen = { SamsungSleep.open(context) },
        onDismiss = { prefs.samsungCardDismissed = true; samsungDismissed = true },
        compact = strip,
      )
      HomeCard.NOTIFICATIONS -> NotificationAskCard(
        onAllow = { prefs.notificationsAsked = true; notificationsAsked = true; onRequestNotifications() },
        onNotNow = { prefs.notificationsAsked = true; notificationsAsked = true },
        compact = strip,
      )
    }
  }
  val slot = modifier.padding(horizontal = Sun.Gutter, vertical = Sun.S4)
  if (strip) {
    Box(slot) { body() }
  } else {
    // A full card on a tall window. Even so it never takes more than its share: past that it scrolls inside itself, and the
    // results keep the rest.
    val cap = (LocalConfiguration.current.screenHeightDp * HomeCards.FULL_CARD_MAX_SHARE).dp
    Box(slot.heightIn(max = cap).verticalScroll(rememberScrollState())) { body() }
  }
}

/** One short card: why, and Allow or Not now. Both answers close it for good; the system dialog shows only a limited number of times. */
@Composable
private fun NotificationAskCard(onAllow: () -> Unit, onNotNow: () -> Unit, modifier: Modifier = Modifier, compact: Boolean = false) {
  if (compact) {
    HomeBanner(
      title = stringResource(R.string.onb3_notif_title),
      modifier = modifier,
      body = appString(R.string.onb3_notif_body),
      dismissLabel = stringResource(R.string.models_update_not_now),
      onDismiss = onNotNow,
    ) { SunButton(onClick = onAllow) { Text(stringResource(R.string.onb3_notif_action), style = SunType.Label) } }
    return
  }
  SunCard(modifier.fillMaxWidth()) {
    Column(Modifier.padding(Sun.S16)) {
      Text(
        stringResource(R.string.onb3_notif_title),
        style = SunType.BodyLarge.copy(fontWeight = FontWeight.Bold),
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.semantics { heading() },
      )
      Spacer(Modifier.height(Sun.S4))
      Text(appString(R.string.onb3_notif_body), style = SunType.Body, color = MaterialTheme.colorScheme.onSurfaceVariant)
      Spacer(Modifier.height(Sun.S12))
      SunButton(onClick = onAllow, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.onb3_notif_action), style = SunType.Label) }
      SunTextButton(onClick = onNotNow, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text(stringResource(R.string.models_update_not_now)) }
    }
  }
}

private fun screenOffWorks(context: android.content.Context): Boolean = runCatching {
  val raw = context.getSharedPreferences(com.craneai.findr.engine.ScreenOffRecord.PREFS, android.content.Context.MODE_PRIVATE)
    .getString(com.craneai.findr.engine.ScreenOffRecord.KEY, null)
  com.craneai.findr.engine.ScreenOffRecord.decode(raw).verdict == com.craneai.findr.engine.ScreenOffRecord.Verdict.WORKS
}.getOrDefault(false)
