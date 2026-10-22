package trawl.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.craneai.findr.ui.theme.LocalSunlight
import trawl.desktop.AppController
import trawl.desktop.ResultRow
import trawl.desktop.t
import trawl.desktop.tn
import com.craneai.findr.ui.common.formatCount
import trawl.desktop.ui.privacy.LocalPrivacyUi
import trawl.desktop.ui.search.ImageSearch
import trawl.desktop.ui.search.Share

private class MenuItem(val label: String, val icon: ImageVector, val cap: String?, val run: () -> Unit)

/** The chosen results' files in the list's own order (the menu and the batch buttons act on these). */
internal fun pickedFiles(ui: MainUi, c: AppController): List<com.craneai.findr.core.IndexedFile> {
  val byId = c.results.value.rows.associateBy { it.file.id }
  return trawl.desktop.ui.search.Picking.inOrder(ui.order, ui.picked).mapNotNull { byId[it]?.file }
}

/**
 * The right-click menu of a result: Open, Show in folder, Copy path, each with its shortcut (asking first when the file is sensitive), and
 * Mark as sensitive / Not sensitive. Our own popup, not the platform's, so it is
 * drawn in Sunlight and behaves the same on every computer; it sits where the pointer was and slides back inside the window if it would hang off.
 */
@Composable
fun ResultMenu(ui: MainUi, c: AppController, row: ResultRow) {
  val m = ui.menu
  if (m == null || m.id != row.file.id) return
  val tk = LocalSunlight.current
  val f = row.file
  val mod = MainUi.modName
  val close = { ui.menu = null }
  // ---- SEARCH PACKAGE (A): the entries; one list, one order, for the right-click and the "..." button (B6) ----
  // Everything that hands a file to something outside Trawl (open, copy, share) asks the privacy "leave" question first when any of the
  // files is sensitive (one question for a whole selection); marking is the privacy package's own entry.
  val privacy = LocalPrivacyUi.current
  val blur = trawl.desktop.privacy.LocalPreviewBlur.current
  fun leaving(ids: Collection<Long>, action: () -> Unit) { if (privacy == null) action() else privacy.leave(ids, action) }
  val several = ui.multi && f.id in ui.picked
  val items = if (several) {
    val files = pickedFiles(ui, c)
    val n = files.size
    val ids = files.map { it.id }
    buildList {
      add(MenuItem(tn("action_copy_n", n, formatCount(n)), Icons.Copy, "$mod C") { close(); leaving(ids) { c.copyFiles(files) } })
      add(MenuItem(t("action_show_folders"), Icons.FolderOpen, "$mod Enter") { close(); leaving(ids) { c.showInFolders(files) } })
      if (c.canShare) add(MenuItem(tn("action_share_n", n, formatCount(n)), Icons.Share, null) { close(); leaving(ids) { c.share(files) } })
      if (privacy != null && files.any { !it.sensitive }) {
        add(MenuItem(t("sensitive_mark"), Icons.Lock, null) { close(); privacy.mark(ids, true) })
      }
    }
  } else buildList {
    add(MenuItem(t("action_open"), Icons.OpenInNew, "Enter") { close(); leaving(listOf(f.id)) { c.open(f) } })
    add(MenuItem(t("action_show_folder"), Icons.FolderOpen, "$mod Enter") { close(); leaving(listOf(f.id)) { c.showInFolder(f) } })
    add(MenuItem(t("action_copy"), Icons.Copy, "$mod C") { close(); leaving(listOf(f.id)) { c.copyFiles(listOf(f)) } })
    add(MenuItem(t("action_copy_path"), Icons.Copy, "$mod Shift C") { close(); leaving(listOf(f.id)) { c.copyPath(f) } })
    if (c.canShare) add(MenuItem(t("action_share"), Icons.Share, null) { close(); leaving(listOf(f.id)) { c.share(listOf(f)) } })
    if (c.canFindSimilar(f)) add(MenuItem(t("action_find_similar"), Icons.ImageSearch, null) { close(); c.findSimilar(f, nameHidden = blur.hides(f)) })
    // Marking is the person's own word for "this one is private": the file then stays out of results until they show sensitive files.
    // A file that is only sensitive because of its folder cannot be unmarked here (the folder rule is in Settings, Privacy).
    if (privacy != null && (f.markedSensitive || !f.sensitive)) {
      add(MenuItem(if (f.markedSensitive) t("sensitive_unmark") else t("sensitive_mark"), Icons.Lock, null) { close(); privacy.mark(listOf(f.id), !f.markedSensitive) })
    }
  }
  var active by remember(m) { mutableIntStateOf(0) }
  val provider = remember(m) {
    object : PopupPositionProvider {
      override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val x = (anchorBounds.left + m.x).coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
        val y = (anchorBounds.top + m.y).coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0))
        return IntOffset(x, y)
      }
    }
  }
  val shape = RoundedCornerShape(10.dp)
  Popup(
    popupPositionProvider = provider, onDismissRequest = { close() }, properties = PopupProperties(focusable = true),
    onPreviewKeyEvent = { e ->
      if (e.type != KeyEventType.KeyDown) false
      else when (e.key) {
        Key.Escape -> { close(); true }
        Key.DirectionDown -> { active = (active + 1) % items.size; true }
        Key.DirectionUp -> { active = (active + items.size - 1) % items.size; true }
        Key.Enter, Key.NumPadEnter -> { items[active].run(); true }
        else -> false
      }
    },
  ) {
    Column(
      Modifier.widthIn(min = 240.dp).width(IntrinsicSize.Max).clip(shape).background(tk.paper).border(Sun.Border, tk.line, shape).padding(Sun.S4)
    ) {
      items.forEachIndexed { i, item -> MenuRow(item, active == i) }
    }
  }
}

@Composable
private fun MenuRow(item: MenuItem, keyboardActive: Boolean) {
  val tk = LocalSunlight.current
  val shape = RoundedCornerShape(7.dp)
  val source = remember { MutableInteractionSource() }
  val hovered by source.collectIsHoveredAsState()
  Row(
    Modifier.fillMaxWidth().clip(shape).pointerHoverIcon(PointerIcon.Hand).hoverable(source)
      .background(if (hovered || keyboardActive) tk.paper2 else androidx.compose.ui.graphics.Color.Transparent, shape)
      .menuActiveRing(keyboardActive, shape)
      .clickable(role = Role.Button, onClick = item.run)
      .heightIn(min = 34.dp).padding(horizontal = Sun.S12, vertical = Sun.S4),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(item.icon, contentDescription = null, tint = tk.ink, modifier = Modifier.size(Sun.IconSmall))
    Spacer(Modifier.width(Sun.S12))
    Text(item.label, style = SunType.Body, color = tk.ink, modifier = Modifier.weight(1f))
    item.cap?.takeIf { it.isNotBlank() }?.let {
      Spacer(Modifier.width(Sun.S16))
      KeyCap(it)
    }
  }
}
