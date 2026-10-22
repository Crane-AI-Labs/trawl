package com.craneai.findr.ui.settings

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.craneai.findr.R
import com.craneai.findr.ui.theme.LocalSunlight
import com.craneai.findr.ui.theme.Palette
import com.craneai.findr.ui.theme.Sun
import com.craneai.findr.ui.theme.SunType
import com.craneai.findr.ui.theme.SunlightTokens
import com.craneai.findr.ui.theme.ThemeMode
import com.craneai.findr.ui.theme.isDark
import com.craneai.findr.ui.theme.rememberTokens
import com.craneai.findr.ui.theme.sunFocusRing
import com.craneai.findr.ui.theme.wallpaperColoursAvailable

/**
 * Settings, Appearance: which colours the app wears. Sunlight (the default) or the wallpaper's colours (Android 12 and later only;
 * below that the choice is not shown). Each option draws a live swatch of its own paper, ink, marker and action at the chosen
 * brightness, and choosing one applies at once (UiPrefs; MainActivity's theme follows the flow).
 */
@Composable
fun PalettePicker(palette: Palette, onPalette: (Palette) -> Unit, themeMode: ThemeMode, modifier: Modifier = Modifier) {
  if (!wallpaperColoursAvailable(Build.VERSION.SDK_INT)) return
  val dark = themeMode.isDark(isSystemInDarkTheme())
  Column(modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(Sun.S8)) {
    Text(stringResource(R.string.settings_colours), style = SunType.Label, color = MaterialTheme.colorScheme.onBackground)
    PaletteOption(
      title = stringResource(R.string.colours_sunlight),
      hint = stringResource(R.string.colours_sunlight_hint),
      swatch = rememberTokens(Palette.SUNLIGHT, dark),
      selected = palette == Palette.SUNLIGHT,
      onClick = { onPalette(Palette.SUNLIGHT) },
    )
    PaletteOption(
      title = stringResource(R.string.colours_wallpaper),
      hint = stringResource(R.string.colours_wallpaper_hint),
      swatch = rememberTokens(Palette.WALLPAPER, dark),
      selected = palette == Palette.WALLPAPER,
      onClick = { onPalette(Palette.WALLPAPER) },
    )
  }
}

@Composable
private fun PaletteOption(title: String, hint: String, swatch: SunlightTokens, selected: Boolean, onClick: () -> Unit) {
  val t = LocalSunlight.current
  val shape = MaterialTheme.shapes.small
  val fg = if (selected) t.onMarker else t.ink
  Row(
    Modifier
      .fillMaxWidth()
      .heightIn(min = Sun.SettingsRow)
      .sunFocusRing(shape)
      .clip(shape)
      .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
      .background(if (selected) t.marker else t.paper)
      .border(Sun.Border, if (selected) t.onMarker else t.line, shape)
      .padding(horizontal = Sun.S12, vertical = Sun.S8),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Swatch(swatch)
    Spacer(Modifier.width(Sun.S12))
    Column(Modifier.weight(1f)) {
      Text(title, style = SunType.BodyLarge.copy(fontWeight = FontWeight.Bold), color = fg)
      Text(hint, style = SunType.Body, color = if (selected) t.onMarker else t.ink2)
    }
  }
}

/** A small live picture of a palette: its paper with an ink line of text, the marker fill and the action button's colour. */
@Composable
private fun Swatch(s: SunlightTokens) {
  val edge = RoundedCornerShape(8.dp)
  Box(
    Modifier.size(width = 64.dp, height = 48.dp).clip(edge).background(s.paper).border(Sun.Border, s.line, edge)
      .clearAndSetSemantics { },
  ) {
    Column(Modifier.padding(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      Box(Modifier.size(width = 34.dp, height = 5.dp).clip(CircleShape).background(s.ink))
      Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(width = 22.dp, height = 18.dp).clip(RoundedCornerShape(4.dp)).background(s.marker).border(1.dp, s.onMarker, RoundedCornerShape(4.dp)))
        Box(Modifier.size(width = 22.dp, height = 18.dp).clip(RoundedCornerShape(4.dp)).background(s.action))
      }
    }
  }
}
