package com.craneai.findr.ui.settings

import com.craneai.findr.ui.common.appString
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.craneai.findr.R
import com.craneai.findr.ui.theme.Sun
import com.craneai.findr.ui.theme.SunSectionHeading
import com.craneai.findr.ui.theme.SunTextButton
import com.craneai.findr.ui.theme.SunType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The notices screen: what Parakeet's CC BY 4.0, the Gemma terms and ML Kit's terms require, then the rest, then full texts. */
@Composable
fun LicencesScreen(onBack: () -> Unit) {
  Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
    SettingsTopBar(stringResource(R.string.licences_title), onBack, Modifier.statusBarsPadding())
    LazyColumn(
      Modifier.weight(1f).navigationBarsPadding(),
      contentPadding = PaddingValues(start = Sun.Gutter, end = Sun.Gutter, top = Sun.S8, bottom = Sun.S32),
    ) {
      item {
        Text(appString(R.string.licences_intro), style = SunType.Body, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
      item { SunSectionHeading(appString(R.string.licences_section_required)) }
      items(LicenceNotices.required) { NoticeRow(it) }
      item { SunSectionHeading(appString(R.string.licences_section_components)) }
      items(LicenceNotices.components) { NoticeRow(it) }
      item { SunSectionHeading(appString(R.string.licences_section_texts)) }
      items(LicenceNotices.texts) { FullText(it) }
    }
  }
}

@Composable
private fun NoticeRow(n: Notice) {
  Column(Modifier.fillMaxWidth().padding(vertical = Sun.S12)) {
    Text(n.name, style = SunType.BodyLarge.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold), color = MaterialTheme.colorScheme.onSurface)
    Text(n.meta, style = SunType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (n.link.isNotEmpty()) Text(n.link, style = SunType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(Sun.S4))
    Text(n.text, style = SunType.Body, color = MaterialTheme.colorScheme.onSurface)
  }
  HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun FullText(t: LicenceText) {
  val context = LocalContext.current
  var open by rememberSaveable(t.asset) { mutableStateOf(false) }
  var body by remember(t.asset) { mutableStateOf("") }
  LaunchedEffect(open) {
    if (open && body.isEmpty()) {
      body = withContext(Dispatchers.IO) {
        runCatching { context.assets.open(t.asset).bufferedReader().use { it.readText() } }.getOrDefault("")
      }
    }
  }
  val toggleDescription = stringResource(if (open) R.string.licences_hide_named else R.string.licences_show_named, t.title)
  Column(Modifier.fillMaxWidth().padding(vertical = Sun.S8)) {
    Text(t.title, style = SunType.Label, color = MaterialTheme.colorScheme.onSurface)
    SunTextButton(onClick = { open = !open }, modifier = Modifier.semantics { contentDescription = toggleDescription }) {
      Text(stringResource(if (open) R.string.licences_hide else R.string.licences_show), style = SunType.Label)
    }
    if (open) {
      Text(body, style = SunType.Caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
  }
  HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
}
