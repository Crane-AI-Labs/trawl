package com.craneai.findr.ui.delivery

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
import com.craneai.findr.core.DeliveryState
import com.craneai.findr.core.ModelChoice
import com.craneai.findr.ui.theme.LocalSunlight
import com.craneai.findr.ui.theme.Sun
import com.craneai.findr.ui.theme.SunType
import com.craneai.findr.ui.theme.sunFocusRing

/**
 * The first-run model pack choice: Standard (recommended, the default) or Save data. One radio group of two rows, each saying in one
 * line what it costs and what it gains (the facts are measured: internal/research/save-data-choice.md). Selected is the marker
 * fill under ink, like every Sunlight choice, so it survives greyscale and sunlight. It is shown only where [DeliveryState.choiceOffered]
 * (a 2 GB phone is always on Save data and is not asked) and nothing is installed yet.
 */
@Composable
fun ModelChoicePicker(state: DeliveryState, onChoice: (ModelChoice) -> Unit, modifier: Modifier = Modifier) {
  Column(modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(Sun.S8)) {
    Text(stringResource(R.string.models_choice_title), style = SunType.Label, color = MaterialTheme.colorScheme.onBackground)
    ChoiceRow(
      title = stringResource(R.string.models_choice_standard, state.standardMb),
      hint = stringResource(R.string.models_choice_standard_hint),
      selected = state.choice == ModelChoice.STANDARD,
    ) { onChoice(ModelChoice.STANDARD) }
    ChoiceRow(
      title = stringResource(R.string.models_choice_save, state.saveDataMb),
      hint = stringResource(R.string.models_choice_save_hint),
      selected = state.choice == ModelChoice.SAVE_DATA,
    ) { onChoice(ModelChoice.SAVE_DATA) }
  }
}

@Composable
private fun ChoiceRow(title: String, hint: String, selected: Boolean, onClick: () -> Unit) {
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
