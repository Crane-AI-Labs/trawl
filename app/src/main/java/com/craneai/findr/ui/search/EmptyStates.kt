package com.craneai.findr.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.FolderOff
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.craneai.findr.R
import com.craneai.findr.ui.common.BrandMark
import com.craneai.findr.ui.common.HomeBanner
import com.craneai.findr.ui.common.appString
import com.craneai.findr.ui.theme.Sun
import com.craneai.findr.ui.theme.SunButton
import com.craneai.findr.ui.theme.SunCard
import com.craneai.findr.ui.theme.SunType

/** An honest "no": says what was searched, whether the app is still learning, and what to try. Never blames the person. */
@Composable
fun NothingFound(
  query: String, indexIncomplete: Boolean, modifier: Modifier = Modifier, date: com.craneai.findr.core.DateLabel? = null,
  /** "Find similar" found nothing: the name of the file it started from (the words below are about it, not a query). */
  similarTo: String? = null,
) {
  Column(
    modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = Sun.S32, vertical = Sun.S24),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
  ) {
    Icon(Icons.Rounded.SearchOff, contentDescription = null, tint = MaterialTheme.colorScheme.onBackground, modifier = Modifier.size(40.dp))
    Spacer(Modifier.height(Sun.S12))
    Text(
      stringResource(R.string.search_nothing_title),
      style = SunType.Heading,
      color = MaterialTheme.colorScheme.onBackground,
      textAlign = TextAlign.Center,
      modifier = Modifier.semantics { heading() },
    )
    Spacer(Modifier.height(Sun.S8))
    Text(
      if (similarTo != null) stringResource(R.string.similar_nothing_body, similarTo) else stringResource(R.string.search_nothing_body, query.trim()),
      style = SunType.BodyLarge,
      color = MaterialTheme.colorScheme.onBackground,
      textAlign = TextAlign.Center,
    )
    if (date != null) {
      Spacer(Modifier.height(Sun.S8))
      Text(
        stringResource(R.string.nothing_in_dates, dateLabelText(date)),
        style = SunType.Body,
        color = MaterialTheme.colorScheme.onBackground,
        textAlign = TextAlign.Center,
      )
    }
    Spacer(Modifier.height(Sun.S8))
    if (similarTo == null) Text(
      stringResource(R.string.search_nothing_try_all),
      style = SunType.Body,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      textAlign = TextAlign.Center,
    )
    if (indexIncomplete) {
      Spacer(Modifier.height(Sun.S8))
      Text(
        appString(R.string.search_nothing_incomplete),
        style = SunType.Body,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
      )
    }
  }
}

/** Shown before the app has found any file at all. */
@Composable
fun FreshPhone(modifier: Modifier = Modifier) {
  Column(
    modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = Sun.S32, vertical = Sun.S24),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    BrandMark(size = 48.dp)
    Spacer(Modifier.height(Sun.S16))
    Text(
      stringResource(R.string.search_fresh_title),
      style = SunType.Heading,
      color = MaterialTheme.colorScheme.onBackground,
      textAlign = TextAlign.Center,
      modifier = Modifier.semantics { heading() },
    )
    Spacer(Modifier.height(Sun.S8))
    Text(
      appString(R.string.search_fresh_body),
      style = SunType.Body,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      textAlign = TextAlign.Center,
    )
  }
}

@Composable
fun EmptyFilter(modifier: Modifier = Modifier) {
  Text(
    stringResource(R.string.search_recent_empty_filter),
    style = SunType.BodyLarge,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    textAlign = TextAlign.Center,
    modifier = modifier.fillMaxWidth().padding(Sun.S32),
  )
}

/** One line above the results while the index is still filling in: words now, meaning later (and why, when it is the charger). */
@Composable
fun IncompleteNote(waitingForCharger: Boolean, modifier: Modifier = Modifier) {
  Row(modifier.fillMaxWidth().padding(horizontal = Sun.Gutter, vertical = Sun.S4), verticalAlignment = Alignment.Top) {
    Icon(
      if (waitingForCharger) Icons.Outlined.BatteryChargingFull else Icons.Outlined.Schedule,
      contentDescription = null,
      tint = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(top = 1.dp).size(18.dp),
    )
    Spacer(Modifier.width(Sun.S8))
    Text(
      stringResource(if (waitingForCharger) R.string.search_incomplete_note_charger else R.string.search_incomplete_note),
      style = SunType.Caption,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

/** Above the results while the embedding engine failed its self-test: only names and words are searched until it is repaired. */
@Composable
fun RepairingNote(modifier: Modifier = Modifier) {
  // An error is an icon and words in the danger colour, never the colour alone.
  Row(modifier.fillMaxWidth().padding(horizontal = Sun.Gutter, vertical = Sun.S4), verticalAlignment = Alignment.Top) {
    Icon(Icons.Outlined.Build, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 1.dp).size(18.dp))
    Spacer(Modifier.width(Sun.S8))
    Text(stringResource(R.string.search_repairing_note), style = SunType.Caption, color = MaterialTheme.colorScheme.error)
  }
}

/** Shown instead of the meter while the app has no permission to see files: what is wrong, that it is safe, one action. */
@Composable
fun AccessBanner(onAllow: () -> Unit, modifier: Modifier = Modifier, compact: Boolean = false, onHide: (() -> Unit)? = null) {
  if (compact) {
    HomeBanner(
      title = appString(R.string.access_banner_title),
      modifier = modifier,
      body = stringResource(R.string.access_banner_body),
      dismissLabel = stringResource(R.string.report_close),
      onDismiss = onHide,
    ) { SunButton(onClick = onAllow) { Text(stringResource(R.string.action_allow_access), style = SunType.Label) } }
    return
  }
  SunCard(modifier.fillMaxWidth()) {
    Column(Modifier.padding(Sun.S16)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.FolderOff, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(Sun.Icon))
        Spacer(Modifier.width(Sun.S12))
        Text(appString(R.string.access_banner_title), style = SunType.BodyLarge.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.onSurface)
      }
      Spacer(Modifier.height(Sun.S4))
      Text(stringResource(R.string.access_banner_body), style = SunType.Body, color = MaterialTheme.colorScheme.onSurfaceVariant)
      Spacer(Modifier.height(Sun.S12))
      SunButton(onClick = onAllow, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_allow_access)) }
    }
  }
}
