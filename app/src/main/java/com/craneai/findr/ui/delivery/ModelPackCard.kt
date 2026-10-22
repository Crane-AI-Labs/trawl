package com.craneai.findr.ui.delivery

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.craneai.findr.R
import com.craneai.findr.core.DeliveryPhase
import com.craneai.findr.core.ModelChoice
import com.craneai.findr.ui.common.appString
import com.craneai.findr.ui.settings.SunErrorText
import com.craneai.findr.ui.theme.Sun
import com.craneai.findr.ui.theme.SunCard
import com.craneai.findr.ui.theme.SunOutlinedButton
import com.craneai.findr.ui.theme.SunTextButton
import com.craneai.findr.ui.theme.SunType

/**
 * Settings → Add-ons: which model pack this phone holds, and the way up. Someone on Save data (chosen at first run, or simply
 * downloaded from the website) can get the Standard pack here: the signed bundle installs over the small one (a fuller class
 * always upgrades), the file list and names stay (words inside documents come back as files are read again), and the files are read again with the better model. The card says so
 * before the download, and says to close and open the app once after it (the running app keeps the stack it started with).
 * Someone on Standard sees what they have. Not shown on a 2 GB phone (always Save data, nothing to choose) or with no signed pack
 * (first run has its own card).
 */
@Composable
fun ModelPackCard(viewModel: DeliveryViewModel, modifier: Modifier = Modifier) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  val installed = state.installedChoice
  if (!state.choiceOffered || installed == null) return
  // Downloads is watched while this card is on screen, so a finished download is added by itself (as on the first-run card).
  DisposableEffect(viewModel) {
    viewModel.watch(true)
    onDispose { viewModel.watch(false) }
  }
  val context = LocalContext.current
  var noBrowser by remember { mutableStateOf(false) }
  val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
    result.data?.data?.let { viewModel.installFrom(it.toString()) }
  }
  SunCard(modifier.fillMaxWidth()) {
    Column(Modifier.padding(Sun.S16)) {
      Text(
        stringResource(R.string.models_pack_title),
        style = SunType.BodyLarge.copy(fontWeight = FontWeight.Bold),
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.semantics { heading() },
      )
      Spacer(Modifier.height(Sun.S4))
      if (state.upgradedToStandard && state.phase != DeliveryPhase.INSTALLING) {
        Text(appString(R.string.models_pack_upgraded), style = SunType.Body, color = MaterialTheme.colorScheme.onSurface)
        if (state.phase == DeliveryPhase.DONE) { Spacer(Modifier.height(Sun.S8)); Done(state, viewModel, confirmDone = false) }
        return@Column
      }
      Text(
        stringResource(if (installed == ModelChoice.SAVE_DATA) R.string.models_pack_save_body else R.string.models_pack_standard_body),
        style = SunType.Body,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      if (installed != ModelChoice.SAVE_DATA) return@Column
      Spacer(Modifier.height(Sun.S8))
      when (state.phase) {
        DeliveryPhase.IDLE -> {
          Text(appString(R.string.models_pack_upgrade_note), style = SunType.Body, color = MaterialTheme.colorScheme.onSurfaceVariant)
          Spacer(Modifier.height(Sun.S12))
          SunOutlinedButton(
            onClick = {
              viewModel.setChoice(ModelChoice.STANDARD)
              noBrowser = !openInBrowser(context, viewModel.downloadUrl())
            },
            modifier = Modifier.fillMaxWidth(),
          ) { Text(stringResource(R.string.models_pack_get_standard, state.standardMb), style = SunType.Label) }
          if (noBrowser) {
            Spacer(Modifier.height(Sun.S8))
            SunErrorText(stringResource(R.string.models_no_browser))
          }
          SunTextButton(onClick = { viewModel.setChoice(ModelChoice.STANDARD); picker.launch(chooseFileIntent()) }) {
            Text(stringResource(R.string.models_choose))
          }
        }
        DeliveryPhase.DOWNLOADING -> Downloading(state)
        DeliveryPhase.INSTALLING -> Installing(state, viewModel::cancel)
        DeliveryPhase.REFUSED -> {
          SunErrorText(refusalText(state))
          SunTextButton(onClick = viewModel::acknowledge) { Text(stringResource(R.string.models_ok)) }
        }
        DeliveryPhase.DONE -> Done(state, viewModel, confirmDone = true)
      }
    }
  }
}
