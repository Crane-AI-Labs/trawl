package com.craneai.findr.ui.report

import com.craneai.findr.ui.common.HomeBanner
import com.craneai.findr.ui.common.appString
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.craneai.findr.R
import com.craneai.findr.diag.BuiltReport
import com.craneai.findr.diag.Diag
import com.craneai.findr.diag.ReportSender
import com.craneai.findr.diag.SendResult
import com.craneai.findr.ui.settings.SettingsDialog
import com.craneai.findr.ui.settings.SunErrorText
import com.craneai.findr.ui.theme.LocalSunlight
import com.craneai.findr.ui.theme.Sun
import com.craneai.findr.ui.theme.SunButton
import com.craneai.findr.ui.theme.SunCard
import com.craneai.findr.ui.theme.SunOutlinedButton
import com.craneai.findr.ui.theme.SunTextButton
import com.craneai.findr.ui.theme.SunType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * After a crash or a freeze, the next launch asks once whether to send a report. Send opens the person's own email app
 * with the report attached (nothing leaves the phone until they press Send there); View report shows the whole text first.
 * Shows nothing when there is nothing to ask about.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CrashReportCard(modifier: Modifier = Modifier, compact: Boolean = false) {
  val pending by Diag.pending.collectAsStateWithLifecycle()
  if (pending <= 0) return
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  var viewing by rememberSaveable { mutableStateOf(false) }
  var message by remember { mutableStateOf<Int?>(null) }
  val send: () -> Unit = {
    scope.launch {
      val report = withContext(Dispatchers.IO) { Diag.buildReport(null) }
      if (ReportSender.email(context, report) == SendResult.STARTED) Diag.acknowledge() else message = R.string.report_no_email
    }
  }
  if (compact) {
    // The short form (Home on a short, wide or large-text screen): Send, and Not now as the way out. The report is read in the
    // person's own email app before anything is sent, exactly as with the full card.
    HomeBanner(
      title = appString(R.string.report_card_title),
      modifier = modifier,
      body = message?.let { stringResource(it) },
      dismissLabel = stringResource(R.string.report_not_now),
      onDismiss = { Diag.acknowledge() },
      color = LocalSunlight.current.paper2,
    ) {
      SunButton(onClick = send) { Text(stringResource(R.string.report_send), style = SunType.Label) }
      SunOutlinedButton(onClick = { viewing = true }) { Text(stringResource(R.string.report_view), style = SunType.Label) }
    }
    if (viewing) ReportDialog(note = null, onDismiss = { viewing = false }, onSent = { viewing = false; Diag.acknowledge() })
    return
  }
  SunCard(modifier = modifier.fillMaxWidth(), color = LocalSunlight.current.paper2) {
    Column(Modifier.padding(Sun.S16), verticalArrangement = Arrangement.spacedBy(Sun.S8)) {
      Text(appString(R.string.report_card_title), style = SunType.Heading, color = MaterialTheme.colorScheme.onSurface)
      Text(stringResource(R.string.report_card_body), style = SunType.Body, color = MaterialTheme.colorScheme.onSurface)
      message?.let { SunErrorText(stringResource(it)) }
      FlowRow(horizontalArrangement = Arrangement.spacedBy(Sun.S8), verticalArrangement = Arrangement.spacedBy(Sun.S8)) {
        SunButton(onClick = send) { Text(stringResource(R.string.report_send), style = SunType.Label) }
        SunOutlinedButton(onClick = { viewing = true }) { Text(stringResource(R.string.report_view), style = SunType.Label) }
        SunTextButton(onClick = { Diag.acknowledge() }) { Text(stringResource(R.string.report_not_now), style = SunType.Label) }
      }
    }
  }
  if (viewing) ReportDialog(note = null, onDismiss = { viewing = false }, onSent = { viewing = false; Diag.acknowledge() })
}

/** Settings → Report a problem: works any time, with an optional description. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReportProblemSection() {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  var note by rememberSaveable { mutableStateOf("") }
  var viewing by rememberSaveable { mutableStateOf(false) }
  var message by remember { mutableStateOf<Int?>(null) }
  var focused by remember { mutableStateOf(false) }
  val t = LocalSunlight.current
  val noteLabel = stringResource(R.string.settings_report_note)
  Column(verticalArrangement = Arrangement.spacedBy(Sun.S8)) {
    Text(appString(R.string.settings_report_hint), style = SunType.Body, color = MaterialTheme.colorScheme.onSurfaceVariant)
    // A visible label above the field (a floating label is small and moves); the 2 dp edge is 3 dp and focus-coloured while typing.
    Text(noteLabel, style = SunType.Label, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(top = Sun.S4))
    val shape = MaterialTheme.shapes.small
    TextField(
      value = note,
      onValueChange = { note = it.take(com.craneai.findr.diag.ReportBuilder.NOTE_MAX) },
      textStyle = SunType.BodyLarge,
      minLines = 3,
      maxLines = 6,
      shape = shape,
      colors = TextFieldDefaults.colors(
        focusedContainerColor = t.paper2,
        unfocusedContainerColor = t.paper2,
        focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
        unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
        cursorColor = t.ink,
      ),
      modifier = Modifier
        .fillMaxWidth()
        .heightIn(min = Sun.Field)
        .onFocusChanged { focused = it.isFocused }
        .border(if (focused) Sun.FocusRing else Sun.Border, if (focused) t.focus else t.line, shape)
        .semantics { contentDescription = noteLabel },
    )
    message?.let { SunErrorText(stringResource(it)) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Sun.S8), verticalArrangement = Arrangement.spacedBy(Sun.S8)) {
      SunButton(
        onClick = {
          scope.launch {
            val report = withContext(Dispatchers.IO) { Diag.buildReport(note) }
            message = if (ReportSender.email(context, report) == SendResult.STARTED) null else R.string.report_no_email
          }
        },
      ) { Text(stringResource(R.string.report_send_email), style = SunType.Label) }
      SunOutlinedButton(onClick = { viewing = true }) { Text(stringResource(R.string.report_view), style = SunType.Label) }
    }
    Text(appString(R.string.settings_report_kept), style = SunType.Body, color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
  if (viewing) ReportDialog(note = note, onDismiss = { viewing = false }, onSent = { viewing = false })
}

/** The whole report text, and the ways to send it. WhatsApp shows only when a number is set (and the second step only when WhatsApp is installed). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReportDialog(note: String?, onDismiss: () -> Unit, onSent: () -> Unit) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  var report by remember { mutableStateOf<BuiltReport?>(null) }
  var message by remember { mutableStateOf<Int?>(null) }
  androidx.compose.runtime.LaunchedEffect(note) { report = withContext(Dispatchers.IO) { Diag.buildReport(note) } }
  val t = LocalSunlight.current
  SettingsDialog(
    title = stringResource(R.string.report_dialog_title),
    confirmLabel = stringResource(R.string.report_send_email),
    dismissLabel = stringResource(R.string.report_close),
    confirmEnabled = report != null,
    onConfirm = {
      report?.let {
        scope.launch {
          if (ReportSender.email(context, it) == SendResult.STARTED) { message = null; onSent() } else message = R.string.report_no_email
        }
      }
    },
    onDismiss = onDismiss,
  ) {
    Text(stringResource(R.string.report_dialog_hint), style = SunType.Body, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(Sun.S8))
    val r = report
    // The report is shown as it is sent: a monospace block on paper-2 inside an edge. 14 sp is the smallest size in the app.
    Column(
      Modifier
        .fillMaxWidth()
        .heightIn(max = 320.dp)
        .border(Sun.Border, t.line, MaterialTheme.shapes.small)
        .verticalScroll(rememberScrollState())
        .padding(Sun.S12),
    ) {
      Text(
        r?.text ?: stringResource(R.string.report_building),
        fontFamily = FontFamily.Monospace,
        fontSize = 14.sp,
        lineHeight = 18.sp,
        color = MaterialTheme.colorScheme.onSurface,
      )
    }
    message?.let { SunErrorText(stringResource(it), Modifier.padding(top = Sun.S8)) }
    if (ReportSender.whatsAppConfigured()) {
      FlowRow(
        horizontalArrangement = Arrangement.spacedBy(Sun.S8),
        verticalArrangement = Arrangement.spacedBy(Sun.S8),
        modifier = Modifier.padding(top = Sun.S8),
      ) {
        SunOutlinedButton(
          enabled = r != null,
          onClick = { r?.let { message = if (ReportSender.whatsAppSummary(context, it) == SendResult.STARTED) null else R.string.report_no_whatsapp } },
        ) { Text(stringResource(R.string.report_send_whatsapp), style = SunType.Label) }
        if (ReportSender.whatsAppInstalled(context)) {
          SunOutlinedButton(
            enabled = r != null,
            onClick = { r?.let { message = if (ReportSender.whatsAppFile(context, it) == SendResult.STARTED) null else R.string.report_no_whatsapp } },
          ) { Text(stringResource(R.string.report_send_whatsapp_file), style = SunType.Label) }
        }
      }
    }
  }
}
