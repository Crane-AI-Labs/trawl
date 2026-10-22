package com.craneai.findr.ui.delivery

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.craneai.findr.ui.common.appString
import com.craneai.findr.ui.settings.SunErrorText
import com.craneai.findr.ui.theme.Sun
import com.craneai.findr.ui.theme.SunCard
import com.craneai.findr.ui.theme.SunOutlinedButton
import com.craneai.findr.ui.theme.SunTextButton
import com.craneai.findr.ui.theme.SunType

/**
 * Settings → Add-ons: a gentle note that a better model bundle exists. It says what installing costs: an update that changes how
 * documents are stored makes the app read the files again (on the charger, search keeps working); one that does not, does not.
 * The Get button opens the browser, like the first-run card; the downloaded file is picked up and verified the same way.
 * Shows nothing when there is no newer version or the person said "Not now" for this version.
 */
@Composable
fun UpdateNoticeCard(viewModel: DeliveryViewModel, modifier: Modifier = Modifier) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  val update = state.update ?: return
  val context = LocalContext.current
  var noBrowser by remember { mutableStateOf(false) }
  SunCard(modifier.fillMaxWidth()) {
    Column(Modifier.padding(Sun.S16)) {
      Text(
        stringResource(R.string.models_update_title),
        style = SunType.BodyLarge.copy(fontWeight = FontWeight.Bold),
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.semantics { heading() },
      )
      Spacer(Modifier.height(Sun.S4))
      Text(
        appString(if (update.kind.rereadsFiles) R.string.models_update_body_reread else R.string.models_update_body_light),
        style = SunType.Body,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Spacer(Modifier.height(Sun.S12))
      SunOutlinedButton(onClick = { noBrowser = !openInBrowser(context, viewModel.downloadUrl()) }, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.models_update_get, update.sizeMb), style = SunType.Label)
      }
      if (noBrowser) {
        Spacer(Modifier.height(Sun.S8))
        SunErrorText(stringResource(R.string.models_no_browser))
      }
      SunTextButton(onClick = viewModel::dismissUpdate) { Text(stringResource(R.string.models_update_not_now)) }
    }
  }
}
