package com.craneai.findr.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import com.craneai.findr.R
import com.craneai.findr.core.LearningSpeed
import com.craneai.findr.ui.theme.LocalSunlight
import com.craneai.findr.ui.theme.Sun
import com.craneai.findr.ui.theme.SunType
import com.craneai.findr.ui.theme.sunFocusRing

/**
 * Settings, Reading: "how hard should Trawl work", one choice of three (Gentle, Balanced, Fast) that replaced the two battery
 * switches. Each option says in one line what it costs and what it gains; the mapping onto the real knobs is
 * [com.craneai.findr.engine.LearningProfile]. Choosing one applies at once.
 */
@Composable
fun LearningSpeedPicker(speed: LearningSpeed, onSpeed: (LearningSpeed) -> Unit, modifier: Modifier = Modifier) {
  Column(modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(Sun.S8)) {
    Text(stringResource(R.string.learn_speed_title), style = SunType.Label, color = MaterialTheme.colorScheme.onBackground)
    SpeedOption(stringResource(R.string.learn_gentle), stringResource(R.string.learn_gentle_hint), speed == LearningSpeed.GENTLE) { onSpeed(LearningSpeed.GENTLE) }
    SpeedOption(stringResource(R.string.learn_balanced), stringResource(R.string.learn_balanced_hint), speed == LearningSpeed.BALANCED) { onSpeed(LearningSpeed.BALANCED) }
    SpeedOption(stringResource(R.string.learn_fast), stringResource(R.string.learn_fast_hint), speed == LearningSpeed.FAST) { onSpeed(LearningSpeed.FAST) }
  }
}

@Composable
private fun SpeedOption(title: String, hint: String, selected: Boolean, onClick: () -> Unit) {
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
    Column(Modifier.weight(1f)) {
      Text(title, style = SunType.BodyLarge.copy(fontWeight = FontWeight.Bold), color = fg)
      Text(hint, style = SunType.Body, color = if (selected) t.onMarker else t.ink2)
    }
  }
}
