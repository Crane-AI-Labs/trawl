package com.craneai.findr.ui.delivery

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.craneai.findr.R
import com.craneai.findr.core.DeliveryPhase
import com.craneai.findr.core.DeliveryState
import com.craneai.findr.core.Refusal
import com.craneai.findr.ui.app.AccessState
import com.craneai.findr.ui.common.HomeBanner
import com.craneai.findr.ui.common.appString
import com.craneai.findr.ui.common.formatBytes
import com.craneai.findr.ui.settings.SunErrorText
import com.craneai.findr.ui.theme.LocalSunlight
import com.craneai.findr.ui.theme.Sun
import com.craneai.findr.ui.theme.SunButton
import com.craneai.findr.ui.theme.SunCard
import com.craneai.findr.ui.theme.SunOutlinedButton
import com.craneai.findr.ui.theme.SunTextButton
import com.craneai.findr.ui.theme.SunType
import com.craneai.findr.ui.theme.sunBorder

/**
 * First run: the phone has no search model. One card, one idea at a time: Get the models (the browser downloads the bundle for
 * this phone's class), watch Downloads and add it by itself with progress, or Choose the file. A wrong or damaged file gets one
 * plain sentence. After a verified install the card asks once whether to remove the downloaded file.
 *
 * [framed] false drops the SunCard wrapper and the title, for the onboarding page, which has its own page chrome; [showBody] false
 * drops the explanation for the same reason. [confirmDone] false drops the "The models are installed" line, for Home: the person
 * already saw that on the onboarding page (or sees the meter move), so only the remove-the-file question is left to ask.
 *
 * [compact] is the Home strip for landscape, short screens and large text (see HomeBanner): the same states and the same actions
 * in one or two lines. [onHide] puts the strip away for now (it is never offered for the remove-the-file question, whose own
 * "No, keep it" is the way out).
 */
@Composable
fun GetModelsCard(
  viewModel: DeliveryViewModel,
  access: AccessState,
  modifier: Modifier = Modifier,
  framed: Boolean = true,
  showBody: Boolean = true,
  confirmDone: Boolean = true,
  compact: Boolean = false,
  onHide: (() -> Unit)? = null,
) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  // Watch Downloads only while a card that asks for the file is on screen.
  DisposableEffect(viewModel) {
    viewModel.watch(true)
    onDispose { viewModel.watch(false) }
  }
  val context = LocalContext.current
  var noBrowser by remember { mutableStateOf(false) }
  val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
    result.data?.data?.let { viewModel.installFrom(it.toString()) }
  }
  val onGet = { noBrowser = !openInBrowser(context, viewModel.downloadUrl()) }
  val onChoose = { picker.launch(chooseFileIntent()) }

  if (compact) {
    ModelsBanner(state, access, noBrowser, viewModel, confirmDone, onGet, onChoose, onHide, modifier)
    return
  }

  val body: @Composable () -> Unit = {
    Column(if (framed) Modifier.padding(Sun.S16) else Modifier) {
      if (framed) {
        Text(
          stringResource(R.string.models_title),
          style = SunType.BodyLarge.copy(fontWeight = FontWeight.Bold),
          color = MaterialTheme.colorScheme.onSurface,
          modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(Sun.S4))
      }
      when (state.phase) {
        DeliveryPhase.IDLE -> Idle(state, access, noBrowser, showBody, onGet, onChoose)
        DeliveryPhase.DOWNLOADING -> Downloading(state)
        DeliveryPhase.INSTALLING -> Installing(state, viewModel::cancel)
        DeliveryPhase.REFUSED -> Refused(state, onGet, onChoose, viewModel::acknowledge)
        DeliveryPhase.DONE -> Done(state, viewModel, confirmDone)
      }
    }
  }
  if (framed) SunCard(modifier.fillMaxWidth()) { body() } else Column(modifier.fillMaxWidth()) { body() }
}

@Composable
private fun Idle(state: DeliveryState, access: AccessState, noBrowser: Boolean, showBody: Boolean, onGet: () -> Unit, onChoose: () -> Unit) {
  if (showBody) {
    Text(appString(R.string.models_body, state.sizeMb), style = SunType.Body, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(Sun.S12))
  }
  SunButton(onClick = onGet, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.models_get, state.sizeMb), style = SunType.Label) }
  if (noBrowser) {
    Spacer(Modifier.height(Sun.S8))
    SunErrorText(stringResource(R.string.models_no_browser))
  }
  Spacer(Modifier.height(Sun.S8))
  Text(
    if (access.allFiles) appString(R.string.models_hint_wait) else appString(R.string.models_hint_access),
    style = SunType.Caption,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
  )
  Spacer(Modifier.height(Sun.S12))
  SunOutlinedButton(onClick = onChoose, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.models_choose), style = SunType.Label) }
  Text(
    stringResource(R.string.models_hint_choose),
    style = SunType.Caption,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(top = Sun.S4),
  )
}

@Composable
private fun Downloading(state: DeliveryState) {
  Text(stringResource(R.string.models_downloading, formatBytes(state.doneBytes)), style = SunType.Body, color = MaterialTheme.colorScheme.onSurface)
  Spacer(Modifier.height(Sun.S8))
  ProgressBar(null)
  Spacer(Modifier.height(Sun.S8))
  Text(appString(R.string.models_hint_wait), style = SunType.Caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Installing(state: DeliveryState, onCancel: () -> Unit) {
  val fraction = if (state.totalBytes > 0) (state.doneBytes.toFloat() / state.totalBytes).coerceIn(0f, 1f) else null
  Text(
    if (fraction != null) stringResource(R.string.models_installing_pct, (fraction * 100).toInt()) else stringResource(R.string.models_installing),
    style = SunType.Body,
    color = MaterialTheme.colorScheme.onSurface,
  )
  Spacer(Modifier.height(Sun.S8))
  ProgressBar(fraction)
  SunTextButton(onClick = onCancel) { Text(stringResource(R.string.models_cancel)) }
}

@Composable
private fun refusalText(state: DeliveryState): String = when (state.refusal) {
  Refusal.WRONG_CLASS -> stringResource(R.string.models_refused_wrong_class)
  Refusal.NOT_SIGNED, Refusal.BAD_SIGNATURE, Refusal.BAD_MANIFEST -> appString(R.string.models_refused_not_signed)
  Refusal.OLDER_VERSION -> appString(R.string.models_refused_older)
  Refusal.NO_SPACE -> stringResource(R.string.models_refused_no_space, state.sizeMb)
  Refusal.CANCELLED -> stringResource(R.string.models_refused_cancelled)
  else -> appString(R.string.models_refused_damaged)
}

@Composable
private fun Refused(state: DeliveryState, onGet: () -> Unit, onChoose: () -> Unit, onAcknowledge: () -> Unit) {
  SunErrorText(refusalText(state))
  Spacer(Modifier.height(Sun.S12))
  SunButton(onClick = onGet, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.models_get, state.sizeMb), style = SunType.Label) }
  Spacer(Modifier.height(Sun.S8))
  SunOutlinedButton(onClick = onChoose, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.models_choose), style = SunType.Label) }
  SunTextButton(onClick = onAcknowledge) { Text(stringResource(R.string.models_ok)) }
}

@Composable
private fun Done(state: DeliveryState, viewModel: DeliveryViewModel, confirmDone: Boolean) {
  if (confirmDone) {
    Text(
      if (state.refusal == Refusal.ALREADY_INSTALLED) stringResource(R.string.models_done_already) else stringResource(R.string.models_done),
      style = SunType.Body,
      color = MaterialTheme.colorScheme.onSurface,
    )
  }
  val removable = state.removable
  if (removable != null) {
    if (confirmDone) Spacer(Modifier.height(Sun.S12))
    Text(
      stringResource(R.string.models_free_question, formatBytes(removable.bytes)),
      style = SunType.BodyLarge.copy(fontWeight = FontWeight.Bold),
      color = MaterialTheme.colorScheme.onSurface,
    )
    Spacer(Modifier.height(Sun.S8))
    SunButton(onClick = viewModel::removeDownloaded, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.models_free_yes), style = SunType.Label) }
    Spacer(Modifier.height(Sun.S8))
    SunOutlinedButton(onClick = viewModel::keepDownloaded, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.models_free_no), style = SunType.Label) }
  } else if (confirmDone) {
    SunTextButton(onClick = viewModel::acknowledge) { Text(stringResource(R.string.models_ok)) }
  }
}

/** The compact form: the same phases as the card, as a strip. */
@Composable
private fun ModelsBanner(
  state: DeliveryState,
  access: AccessState,
  noBrowser: Boolean,
  viewModel: DeliveryViewModel,
  confirmDone: Boolean,
  onGet: () -> Unit,
  onChoose: () -> Unit,
  onHide: (() -> Unit)?,
  modifier: Modifier,
) {
  val close = stringResource(R.string.report_close)
  val get: @Composable () -> Unit = { SunButton(onClick = onGet) { Text(stringResource(R.string.models_get, state.sizeMb), style = SunType.Label) } }
  val choose: @Composable () -> Unit = { SunTextButton(onClick = onChoose) { Text(stringResource(R.string.models_choose), style = SunType.Label) } }
  when (state.phase) {
    DeliveryPhase.IDLE -> HomeBanner(
      title = stringResource(R.string.models_title),
      modifier = modifier,
      body = if (noBrowser) stringResource(R.string.models_no_browser) else if (access.allFiles) appString(R.string.models_hint_wait) else appString(R.string.models_hint_access),
      dismissLabel = close,
      onDismiss = onHide,
    ) { get(); choose() }
    DeliveryPhase.DOWNLOADING -> HomeBanner(
      title = stringResource(R.string.models_downloading, formatBytes(state.doneBytes)),
      modifier = modifier,
      dismissLabel = close,
      onDismiss = onHide,
      below = { ProgressBar(null) },
    )
    DeliveryPhase.INSTALLING -> {
      val fraction = if (state.totalBytes > 0) (state.doneBytes.toFloat() / state.totalBytes).coerceIn(0f, 1f) else null
      HomeBanner(
        title = if (fraction != null) stringResource(R.string.models_installing_pct, (fraction * 100).toInt()) else stringResource(R.string.models_installing),
        modifier = modifier,
        dismissLabel = close,
        onDismiss = onHide,
        below = { ProgressBar(fraction) },
      ) { SunTextButton(onClick = viewModel::cancel) { Text(stringResource(R.string.models_cancel), style = SunType.Label) } }
    }
    DeliveryPhase.REFUSED -> HomeBanner(
      title = refusalText(state),
      modifier = modifier,
      dismissLabel = close,
      onDismiss = viewModel::acknowledge,
    ) { get(); choose() }
    DeliveryPhase.DONE -> {
      val removable = state.removable
      if (removable != null) {
        HomeBanner(title = stringResource(R.string.models_free_question, formatBytes(removable.bytes)), modifier = modifier) {
          SunButton(onClick = viewModel::removeDownloaded) { Text(stringResource(R.string.models_free_yes), style = SunType.Label) }
          SunTextButton(onClick = viewModel::keepDownloaded) { Text(stringResource(R.string.models_free_no), style = SunType.Label) }
        }
      } else if (confirmDone) {
        HomeBanner(
          title = if (state.refusal == Refusal.ALREADY_INSTALLED) stringResource(R.string.models_done_already) else stringResource(R.string.models_done),
          modifier = modifier,
          dismissLabel = close,
          onDismiss = viewModel::acknowledge,
        )
      }
    }
  }
}

@Composable
private fun ProgressBar(fraction: Float?) {
  val t = LocalSunlight.current
  val bar = Modifier.fillMaxWidth().height(14.dp).clip(CircleShape).sunBorder(CircleShape)
  if (fraction != null) {
    LinearProgressIndicator(progress = { fraction }, modifier = bar, color = t.marker, trackColor = t.paper, gapSize = 0.dp, drawStopIndicator = {})
  } else {
    LinearProgressIndicator(modifier = bar, color = t.marker, trackColor = t.paper, gapSize = 0.dp)
  }
}

/** Opens [url] in the person's browser. False when no app can: the card then says how to get the file another way. */
internal fun openInBrowser(context: Context, url: String): Boolean {
  if (url.isBlank()) return false
  return try {
    context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    true
  } catch (_: ActivityNotFoundException) {
    false
  }
}

/** ACTION_OPEN_DOCUMENT for the bundle. A write grant is asked for so that, after a verified install, the same file can be removed. */
internal fun chooseFileIntent(): Intent =
  Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
    addCategory(Intent.CATEGORY_OPENABLE)
    type = "*/*"
    putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
  }

/**
 * Whether Home has a Get-the-models card to show. It is wanted while the phone has no model, and while a delivery is in any state
 * other than idle (so the "remove the downloaded file?" question and a refusal are never cut off by the model appearing under them).
 * Once the models are in and there is no file to ask about, it is not wanted any more and the delivery is acknowledged by itself:
 * no "installed, OK" card to tap away (onboarding v2). Home arbitrates which single card to show (HomeCards); this only answers
 * for this one.
 */
@Composable
fun modelsCardWanted(viewModel: DeliveryViewModel): Boolean {
  val noModel by viewModel.noModel.collectAsStateWithLifecycle()
  val busy by remember { viewModel.state.map { it.phase != DeliveryPhase.IDLE }.distinctUntilChanged() }.collectAsStateWithLifecycle(false)
  val finished by remember { viewModel.state.map { it.phase == DeliveryPhase.DONE && it.removable == null }.distinctUntilChanged() }
    .collectAsStateWithLifecycle(false)
  LaunchedEffect(finished) { if (finished) viewModel.acknowledge() }
  return !finished && (noModel || busy)
}

/**
 * What a "hide" on the Home strip is remembered against: hiding the download strip hides the whole download (downloading and
 * installing), and any other phase on its own. A new phase brings the strip back, so a refusal or the remove question is never missed.
 */
@Composable
fun modelsHideKey(viewModel: DeliveryViewModel): String {
  val key by remember {
    viewModel.state.map { if (it.phase == DeliveryPhase.DOWNLOADING || it.phase == DeliveryPhase.INSTALLING) "progress" else it.phase.name }.distinctUntilChanged()
  }.collectAsStateWithLifecycle("")
  return key
}
