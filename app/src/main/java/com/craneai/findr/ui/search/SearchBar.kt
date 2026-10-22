package com.craneai.findr.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.State
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.window.PopupProperties
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.craneai.findr.R
import com.craneai.findr.core.KindFilter
import com.craneai.findr.ui.common.icon
import com.craneai.findr.ui.common.labelRes
import com.craneai.findr.ui.theme.FilterTile
import com.craneai.findr.ui.theme.LocalSunlight
import com.craneai.findr.ui.theme.Sun
import com.craneai.findr.ui.theme.SunType

/** What the line above the search box says while a search runs (null: say nothing). */
data class DockStatus(val icon: ImageVector, val text: String)

/**
 * The bottom dock: one line that says where the search is ("14 so far · meaning in a moment"), then the search box. It lives
 * at the bottom because that is where a thumb rests on a tall phone, and the keyboard opens right under it. The line keeps its
 * place while a query is typed, so the results above never jump when it changes.
 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
fun SearchDock(
  textState: TextFieldState,
  status: DockStatus?,
  modifier: Modifier = Modifier,
  onFocused: () -> Unit = {},
  onFocusLost: () -> Unit = {},
  fieldFocused: State<Boolean> = mutableStateOf(false),
  /** While typing, how far learning has got (the meter folded into this line); draws nothing when there is nothing to say. */
  learning: @Composable () -> Unit = {},
) {
  // While typing (box focused, keyboard up) the learning line joins the count under the list. No filter chips live in the box:
  // the box keeps its whole width for the words, and the filters show (highlighted, with their cross) in the list's own filter lines.
  val typing = fieldFocused.value && WindowInsets.isImeVisible
  Column(
    modifier
      .fillMaxWidth()
      .background(MaterialTheme.colorScheme.background)
      .navigationBarsPadding()
      .imePadding()
      .padding(bottom = Sun.S8),
  ) {
    if (textState.text.isNotEmpty() || typing) {
      StatusLine(if (textState.text.isNotEmpty()) status else null, if (typing) learning else null, Modifier.padding(horizontal = Sun.Gutter, vertical = Sun.S4))
    } else Spacer(Modifier.height(Sun.S8))
    SearchField(textState, onFocused, onFocusLost, Modifier.padding(horizontal = Sun.Gutter))
  }
}

@Composable
private fun StatusLine(status: DockStatus?, learning: (@Composable () -> Unit)?, modifier: Modifier = Modifier) {
  val t = LocalSunlight.current
  // The search's count, then (while typing) how far learning has got, each on its own short line with its icon, so a long
  // translation wraps inside its own line instead of splitting the two.
  Column(modifier.fillMaxWidth().heightIn(min = 28.dp), verticalArrangement = Arrangement.Center) {
    if (status != null) {
      // TalkBack hears the count settle, without a word per keystroke being forced on it. The learning part is not live:
      // it changes with every file read and would talk over the person.
      Row(Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }, verticalAlignment = Alignment.CenterVertically) {
        Icon(status.icon, contentDescription = null, tint = t.ink2, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(Sun.S8))
        Text(status.text, style = SunType.Caption, color = t.ink2)
      }
    }
    learning?.invoke()
  }
}

/**
 * The six filters as Sunlight's tiles: two columns, each an icon and its word; the selected one is the marker under ink with
 * the ink edge kept. One is always selected. Shown above the newest files.
 */
@Composable
fun FilterTiles(filter: KindFilter, onFilter: (KindFilter) -> Unit, modifier: Modifier = Modifier) {
  val group = stringResource(R.string.filter_group)
  Column(
    modifier.fillMaxWidth().semantics { contentDescription = group }.selectableGroup(),
    verticalArrangement = Arrangement.spacedBy(Sun.S8),
  ) {
    KindFilter.entries.chunked(2).forEach { pair ->
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Sun.S8)) {
        pair.forEach { f ->
          FilterTile(stringResource(f.labelRes()), f.icon(), selected = f == filter, onClick = { onFilter(f) }, modifier = Modifier.weight(1f))
        }
      }
    }
  }
}

/** The same six filters in one compact, scrolling line, above search results (the results keep the room). */
@Composable
fun FilterRow(filter: KindFilter, onFilter: (KindFilter) -> Unit, modifier: Modifier = Modifier) {
  val group = stringResource(R.string.filter_group)
  Row(
    modifier
      .fillMaxWidth()
      .horizontalScroll(rememberScrollState())
      .semantics { contentDescription = group }
      .selectableGroup(),
    horizontalArrangement = Arrangement.spacedBy(Sun.S8),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    KindChips(filter, onFilter)
  }
}

/** The six kind filters as compact chips, for the line above the list (no container: the caller's row scrolls and groups them). */
@Composable
internal fun KindChips(filter: KindFilter, onFilter: (KindFilter) -> Unit) {
  KindFilter.entries.forEach { f ->
    FilterTile(stringResource(f.labelRes()), f.icon(), selected = f == filter, onClick = { onFilter(f) }, compact = true)
  }
}

/**
 * The search box: 60 dp, a 2 dp ink edge, 12 dp corners, the text at 18 sp. It is the only input on the home screen: from the
 * first letter the list above narrows (type-to-filter); nothing has to be submitted.
 */
@Composable
private fun SearchField(
  textState: TextFieldState,
  onFocused: () -> Unit,
  onFocusLost: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val focus = LocalFocusManager.current
  val t = LocalSunlight.current
  val shape = MaterialTheme.shapes.small
  val hint = stringResource(R.string.search_hint)
  val hintA11y = stringResource(R.string.search_hint_a11y)
  val clearLabel = stringResource(R.string.search_clear)
  var focused by remember { mutableStateOf(false) }

  BasicTextField(
    state = textState,
    modifier = modifier
      .fillMaxWidth()
      .heightIn(min = Sun.Field)
      // Prefetch on intent: the person tapped the box, so load the text model now and the first query does not wait for it.
      .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocused() else onFocusLost() }
      .semantics { contentDescription = hintA11y },
    textStyle = SunType.BodyLarge.copy(color = t.ink),
    cursorBrush = SolidColor(t.ink),
    lineLimits = TextFieldLineLimits.SingleLine,
    keyboardOptions = KeyboardOptions(
      capitalization = KeyboardCapitalization.None,
      autoCorrectEnabled = false,
      imeAction = ImeAction.Search,
    ),
    onKeyboardAction = KeyboardActionHandler { focus.clearFocus() },
    decorator = { inner ->
      Row(
        Modifier
          .fillMaxWidth()
          .heightIn(min = Sun.Field)
          .clip(shape)
          .background(t.paper2)
          // The edge thickens to the focus ring while the box has focus (ink in light, marker in dark).
          .border(if (focused) Sun.FocusRing else Sun.Border, if (focused) t.focus else t.line, shape)
          .padding(start = Sun.S16, end = Sun.S4),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Icon(Icons.Rounded.Search, contentDescription = null, tint = t.ink, modifier = Modifier.size(Sun.Icon))
        Spacer(Modifier.width(Sun.S12))
        Box(Modifier.weight(1f).padding(vertical = Sun.S8), contentAlignment = Alignment.CenterStart) {
          if (textState.text.isEmpty()) {
            Text(hint, style = SunType.BodyLarge, color = t.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
          }
          inner()
        }
        if (textState.text.isNotEmpty()) {
          IconButton(onClick = { textState.clearText() }) {
            Icon(Icons.Rounded.Close, contentDescription = clearLabel, tint = t.ink)
          }
        } else {
          Spacer(Modifier.width(Sun.S12))
        }
      }
    },
  )
}

/** Pushes what the user types into the view model, every keystroke (type-to-filter; only the meaning step waits for a pause). */
@Composable
fun BindQuery(textState: TextFieldState, onQuery: (String) -> Unit) {
  LaunchedEffect(textState) {
    snapshotFlow { textState.text.toString() }.collect { onQuery(it) }
  }
}

@Composable
fun rememberQueryState(initial: String): TextFieldState = rememberTextFieldState(initialText = initial)
