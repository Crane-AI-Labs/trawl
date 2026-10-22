package com.craneai.findr.ui.language

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.activity.compose.LocalActivity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.unit.dp
import com.craneai.findr.R
import com.craneai.findr.ui.theme.LocalSunlight
import com.craneai.findr.ui.theme.Sun
import com.craneai.findr.ui.theme.SunTextButton
import com.craneai.findr.ui.theme.SunType
import com.craneai.findr.ui.theme.sunBorder

/** Applies a choice of language: the system recreates the screen on Android 13+, and before that we do. */
fun applyLanguage(activity: Activity?, tag: String?) {
  val context = activity ?: return
  AppLocale.set(context, tag)
  if (AppLocale.needsRecreate) activity.recreate()
}

/**
 * The languages, each in its own name (and, under it, in English when that differs), as one radio group. Selected is the
 * marker under ink with the ink edge, like every Sunlight choice. A "preview" translation says so.
 * [systemOption]: the first row follows the phone's language (Settings); onboarding has no such row.
 */
@Composable
fun LanguageList(
  selectedTag: String?,
  onSelect: (String?) -> Unit,
  modifier: Modifier = Modifier,
  systemOption: Boolean = false,
) {
  Column(modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(Sun.S8)) {
    if (systemOption) {
      LanguageRow(
        title = stringResource(R.string.language_system),
        subtitle = stringResource(R.string.language_system_hint),
        titleLocale = null,
        preview = false,
        selected = selectedTag == null,
        onClick = { onSelect(null) },
      )
    }
    AppLanguages.shipped.forEach { lang ->
      LanguageRow(
        title = lang.nativeName,
        subtitle = lang.englishName.takeIf { it != lang.nativeName },
        titleLocale = lang.tag,
        preview = lang.preview,
        selected = selectedTag != null && selectedTag.substringBefore('-').equals(lang.tag, ignoreCase = true),
        onClick = { onSelect(lang.tag) },
      )
    }
  }
}

@Composable
private fun LanguageRow(title: String, subtitle: String?, titleLocale: String?, preview: Boolean, selected: Boolean, onClick: () -> Unit) {
  val t = LocalSunlight.current
  val shape = MaterialTheme.shapes.small
  val fg = if (selected) t.onMarker else t.ink
  Row(
    Modifier
      .fillMaxWidth()
      .heightIn(min = Sun.SettingsRow)
      .clip(shape)
      .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
      .background(if (selected) t.marker else t.paper)
      .border(Sun.Border, if (selected) t.onMarker else t.line2, shape)
      .padding(horizontal = Sun.S16, vertical = Sun.S8),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    RadioDot(selected, fg)
    Spacer(Modifier.width(Sun.S16))
    Column(Modifier.weight(1f)) {
      // The name is set in its own language, so it is shaped (Arabic) and read aloud (TalkBack) as that language.
      val bold = SunType.BodyLarge.copy(fontWeight = FontWeight.Bold)
      Text(title, style = if (titleLocale != null) bold.merge(TextStyle(localeList = LocaleList(titleLocale))) else bold, color = fg)
      if (subtitle != null) Text(subtitle, style = SunType.Body, color = if (selected) t.onMarker else t.ink2)
    }
    if (preview) {
      Spacer(Modifier.width(Sun.S8))
      Text(
        stringResource(R.string.language_preview),
        style = SunType.CaptionBold,
        color = fg,
        modifier = Modifier.sunBorder(CircleShape, color = fg).padding(horizontal = 10.dp, vertical = 2.dp),
      )
    }
  }
}

@Composable
private fun RadioDot(selected: Boolean, color: androidx.compose.ui.graphics.Color) {
  Box(Modifier.size(24.dp).border(Sun.Border, color, CircleShape), contentAlignment = Alignment.Center) {
    if (selected) Box(Modifier.size(12.dp).clip(CircleShape).background(color))
  }
}

/** Settings: the same list in a dialog, with "the phone's language" first. Choosing applies at once and closes it. */
@Composable
fun LanguageDialog(onDismiss: () -> Unit) {
  val activity = LocalActivity.current
  val current = activity?.let { AppLocale.current(it) }
  AlertDialog(
    onDismissRequest = onDismiss,
    modifier = Modifier.sunBorder(MaterialTheme.shapes.medium),
    shape = MaterialTheme.shapes.medium,
    containerColor = MaterialTheme.colorScheme.background,
    tonalElevation = 0.dp,
    title = { Text(stringResource(R.string.language_title), style = SunType.Heading) },
    text = {
      LanguageList(
        selectedTag = current,
        onSelect = { tag -> onDismiss(); applyLanguage(activity, tag) },
        systemOption = true,
        modifier = Modifier.verticalScroll(rememberScrollState()),
      )
    },
    confirmButton = { SunTextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_close)) } },
  )
}
