package com.craneai.findr.ui.atlas

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.craneai.findr.R
import com.craneai.findr.core.FileKind
import com.craneai.findr.core.Locator
import com.craneai.findr.core.MatchReason
import com.craneai.findr.core.SearchHit
import com.craneai.findr.ui.search.FileRow
import com.craneai.findr.ui.search.SelectionTopBar
import com.craneai.findr.ui.settings.SettingsScreen
import com.craneai.findr.ui.theme.ThemeMode
import kotlin.math.max
import kotlin.math.pow
import org.junit.Rule
import org.junit.Test

/** The 1.0.6 polish items that a picture alone would not hold: each check fails on the 1.0.5 code. */
class PolishLayoutTest : AtlasTestBase() {
  @get:Rule val rule = createEmptyComposeRule()

  private fun layoutOf(node: SemanticsNode): TextLayoutResult {
    val out = mutableListOf<TextLayoutResult>()
    node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(out)
    return out.single()
  }

  private fun node(tag: String) = rule.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().first()
  private fun textNode(text: String) = rule.onAllNodes(hasText(text), useUnmergedTree = true).fetchSemanticsNodes().first()

  /** Top-left and size without the parents' clip: a body that scrolls inside a fixed box reaches past it. */
  private fun unclipped(n: SemanticsNode) = Rect(n.positionInRoot.x, n.positionInRoot.y, n.positionInRoot.x + n.size.width, n.positionInRoot.y + n.size.height)

  // ---- P1: the meter's sentence is whole at large text, in English and Luganda ----
  @Test fun meterSentenceIsWholeAtLargeText() {
    val a = Atlas(rule, "t-polish-meter")
    val cells = listOf(Cfg(2.0f), Cfg(1.3f, false, Dev.PHONE, "lg"), Cfg(2.0f, false, Dev.PHONE, "lg"), Cfg(2.0f, false, Dev.SMALL))
    a.forEach(cells, "meter") { c ->
      for ((title0, status, delivery) in listOf(
        Triple(R.string.meter_title_indexing, Status.indexing, Deliveries.idle), Triple(R.string.meter_title_scanning, Status.scanning, Deliveries.idle),
        Triple(R.string.meter_title_needs_models, Status.indexingNoModel, Deliveries.downloading),
      )) {
        prefs()
        val v = Vms(AtlasEngine(status = status, delivery = delivery, corpus = Corpus.MEDIA_ONLY))
        a.launchSearch(c, v)
        val label = a.str(title0)
        val title = textNode(label)
        check(!layoutOf(title).hasVisualOverflow) { "${c.tag}: the meter's sentence '$label' is cut" }
      }
    }
    a.assertNoFailures()
  }

  // ---- P3: searching with no model yet, the answers (or the nothing-found words) start on the first screen ----
  @Test fun noModelAnswersOnFirstScreen() {
    val a = Atlas(rule, "t-polish-nomodel")
    val cells = listOf(Cfg(1.0f, false, Dev.TAB_A9), Cfg(1.0f, false, Dev.TAB_A9_LAND), Cfg(1.0f, false, Dev.TABLET), Cfg(2.0f), Cfg(2.0f, false, Dev.SMALL), Cfg(1.3f, false, Dev.PHONE, "lg"))
    a.forEach(cells, "nomodel") { c ->
      for (q in listOf("wedding", "market")) {
        prefs()
        val v = Vms(AtlasEngine(status = Status.indexingNoModel, corpus = Corpus.FULL, meaning = false))
        a.launchSearch(c, v)
        a.focusField(300); a.type(q); a.keyboardDown()
        val win = rule.onRoot().fetchSemanticsNode().boundsInRoot
        val items = rule.boxes("feed-item")
        val firstTop = items.minOfOrNull { it.top }
          ?: rule.onAllNodes(hasText(a.str(R.string.search_nothing_title)), useUnmergedTree = true).fetchSemanticsNodes().firstOrNull()?.let { unclipped(it).top }
          ?: error("${c.tag} '$q': neither answers nor the nothing-found words are drawn")
        // Above the lower quarter of the window: the strip, the meter and the chips leave the answers most of the screen.
        check(firstTop < win.top + win.height * 0.75f) { "${c.tag} '$q': the first answer starts at $firstTop of ${win.height} px" }
      }
    }
    a.assertNoFailures()
  }

  // ---- P2: at large text a storage value sits under its label, inside the window ----
  @Test fun storageValueUnderItsLabelAtLargeText() {
    val a = Atlas(rule, "t-polish-storage")
    a.forEach(listOf(Cfg(2.0f), Cfg(1.3f, false, Dev.PHONE, "lg")), "storage") { c ->
      prefs()
      val v = Vms(AtlasEngine(status = Status.indexing))
      a.launch(c) { SettingsScreen(v.index, v.addOns, v.delivery, ThemeMode.LIGHT, {}, {}, onBack = {}) }
      a.settle(900)
      val label = a.str(R.string.settings_storage_index)
      // The last storage row in view, so the first one (the index) is whole above it.
      a.scrollToStorage()
      a.settle(300)
      val l = unclipped(textNode(label))
      val hint = unclipped(textNode(a.str(R.string.settings_storage_index_hint)))
      // The value is the size beside or under the label ("52 MB"): found by its words, so the check reads the 1.0.5 layout too.
      val size = Regex("""^[0-9][0-9.,\u00a0 ]*\s?(B|KB|MB|GB)$""")
      val value = rule.onAllNodes(androidx.compose.ui.test.SemanticsMatcher("a size") { n ->
        n.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text)?.any { size.matches(it.text.trim()) } == true
      }, useUnmergedTree = true).fetchSemanticsNodes().map(::unclipped)
        .filter { it.top >= l.top - 1f }.minByOrNull { it.top } ?: error("${c.tag}: no storage value at or below the index row $l")
      check(value.top >= max(l.bottom, hint.bottom) - 1f) { "${c.tag}: the value $value floats beside its label $l / hint $hint" }
      rule.assertInsideWindow("${c.tag} storage", listOf(value, l, hint))
    }
    a.assertNoFailures()
  }

  // ---- P5: Find similar's chip keeps one line and its cross; the name is shortened, not broken ----
  @Test fun similarChipIsOneLineWithItsCross() {
    val a = Atlas(rule, "t-polish-similar")
    a.forEach(listOf(Cfg(1.0f), Cfg(2.0f), Cfg(2.0f, false, Dev.SMALL, "lg")), "similar") { c ->
      prefs()
      // In the real row above the list: it scrolls sideways, so nothing but the chip's own cap keeps the cross on screen.
      a.launch(c) {
        Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 12.dp)) {
          com.craneai.findr.ui.search.SortDateRow(com.craneai.findr.core.SortOrder.BEST, true, null, {}, {}, {}, {}, similar = "Screenshot_20261012-184455_WhatsApp.png")
        }
      }
      a.settle(300)
      val text = layoutOf(rule.onAllNodes(hasText("Screenshot_", substring = true), useUnmergedTree = true).fetchSemanticsNodes().first { it.config.getOrNull(SemanticsActions.GetTextLayoutResult) != null })
      check(text.lineCount == 1) { "${c.tag}: the chip's words take ${text.lineCount} lines" }
      // Unclipped: the row scrolls sideways, so a cross past the window's edge still has (clipped) bounds inside it.
      val cross = unclipped(rule.onAllNodes(androidx.compose.ui.test.hasContentDescription(a.str(R.string.similar_remove)), useUnmergedTree = true).fetchSemanticsNodes().first())
      val win = rule.onRoot().fetchSemanticsNode().boundsInRoot
      check(cross.right <= win.right + 1f) { "${c.tag}: the chip's cross $cross is past the window's edge ${win.right}" }
    }
    a.assertNoFailures()
  }

  // ---- P6: in a short window the empty body grows with its words instead of cutting them ----
  @Test fun shortWindowEmptyBodyHoldsItsWords() {
    val a = Atlas(rule, "t-polish-short")
    a.forEach(listOf(Cfg(2.0f, false, Dev.LAND), Cfg(2.0f, false, Dev.SPLIT), Cfg(2.0f, false, Dev.LAND, "lg")), "short-empty") { c ->
      prefs()
      val v = Vms(AtlasEngine(status = Status.indexing, corpus = Corpus.FULL))
      a.launchSearch(c, v)
      a.focusField(160); a.type("zzqx"); a.keyboardDown()
      // The last words of the body: the "still reading" sentence that goes under nothing-found while the index fills in.
      val lastNode = rule.onAllNodes(hasText(a.str(R.string.search_nothing_incomplete, a.str(R.string.app_name)), substring = true), useUnmergedTree = true)
        .fetchSemanticsNodes().firstOrNull() ?: error("${c.tag}: the still-reading sentence is missing")
      // (1) No scroller of its own: the nearest scrolling parent of the words is the list itself, the one that also holds the kind chips.
      var p: SemanticsNode? = lastNode.parent
      while (p != null && p.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange) == null) p = p.parent
      val scroller = p ?: error("${c.tag}: no scrolling parent at all")
      val chip = unclipped(textNode(a.str(R.string.filter_all)))
      check(unclipped(scroller).top <= chip.top + 1f) { "${c.tag}: the words scroll inside a box of their own (${unclipped(scroller)}), below the chips ($chip)" }
      // (2) The body is as tall as its words.
      val body = rule.onAllNodes(hasTestTag("empty-body"), useUnmergedTree = true).fetchSemanticsNodes().firstOrNull()
        ?: error("${c.tag}: no empty body (nothing found: ${v.search.state.value.nothingFound})")
      val box = unclipped(body)
      val last = unclipped(lastNode)
      check(last.bottom <= box.bottom + 1f) { "${c.tag}: the body's last words end at ${last.bottom}, below its box ${box.bottom} (cut or scrolled inside)" }
    }
    a.assertNoFailures()
  }

  // ---- P7: a video row does not change height after it is first drawn ----
  @Test fun videoRowKeepsItsHeight() {
    val a = Atlas(rule, "t-polish-strip")
    a.forEach(listOf(Cfg(1.0f), Cfg(2.0f)), "strip") { c ->
      for (durationMs in listOf(400L, 60_000L)) {
        prefs()
        val src = AtlasEngine(corpus = Corpus.FULL).files.first { it.kind == FileKind.VIDEO }
        val file = src.copy(id = src.id + 800_000 + durationMs, uri = "fake://polish/height/$durationMs/${c.tag}", durationMs = durationMs)
        val hit = SearchHit(file, 1.0, MatchReason.MEANING, locator = Locator.Time(durationMs / 2))
        a.launch(c) { Column(Modifier.fillMaxSize().statusBarsPadding()) { FileRow(hit, {}, {}, {}, {}, modifier = Modifier.testTag("video-row"), onOpenAt = {}) } }
        val first = node("video-row").boundsInRoot.height
        seedStrips(listOf(hit))   // the plan comes back: one picture for the short clip, five for the long one
        a.settle(2000)
        val after = node("video-row").boundsInRoot.height
        check(kotlin.math.abs(after - first) <= 1f) { "${c.tag} ${durationMs} ms clip: the row was $first px on its first frame and $after px once the strip was planned" }
      }
    }
    a.assertNoFailures()
  }

  // ---- P8: Share and Copy stand apart (8 dp, 16 dp at large text), in the list and in the quick panel ----
  @Test fun shareAndCopyStandApart() {
    val a = Atlas(rule, "t-polish-actions")
    a.forEach(listOf(Cfg(1.0f), Cfg(2.0f), Cfg(1.0f, false, Dev.TABLET), Cfg(2.0f, false, Dev.PHONE, "lg")), "actions") { c ->
      prefs()
      val hit = Vms().hitsFor("school").first { it.file.kind == FileKind.DOCUMENT || it.file.kind == FileKind.PDF }
      a.launch(c) { Column(Modifier.fillMaxSize().statusBarsPadding()) { FileRow(hit, {}, {}, {}, {}) } }
      a.settle(300)
      fun box(id: Int) = rule.onAllNodes(androidx.compose.ui.test.hasContentDescription(a.str(id, hit.file.name)), useUnmergedTree = true).fetchSemanticsNodes().first().boundsInRoot
      val share = box(R.string.action_share_file); val copy = box(R.string.action_copy_file)
      val gapDp = (copy.left - share.right) / a.activity.resources.displayMetrics.density
      val want = if (c.scale >= 1.3f) 16f else 8f
      check(gapDp >= want - 0.5f) { "${c.tag}: Share and Copy are ${"%.1f".format(gapDp)} dp apart (want $want)" }
    }
    a.assertNoFailures()
  }

  // ---- P9: Keep learning's Close reads on black (4.5:1 or more) ----
  @Test fun keepLearningCloseIsReadable() {
    val a = Atlas(rule, "t-polish-keep")
    a.forEach(listOf(Cfg(1.0f), Cfg(2.0f, true)), "keep") { c ->
      prefs()
      a.launch(c) {
        Box(Modifier.fillMaxSize()) {
          val composer = androidx.compose.runtime.currentComposer
          val m = Class.forName("com.craneai.findr.ui.keeplearning.KeepLearningActivityKt").declaredMethods.first { it.name == "Stopped" }
          m.isAccessible = true
          m.invoke(null, com.craneai.findr.ui.keeplearning.KeepLearning.Stop.HOT, {}, composer, 0)
        }
      }
      a.settle(600)
      val close = rule.onAllNodes(hasText(a.str(R.string.keep_close)), useUnmergedTree = true).fetchSemanticsNodes().first().boundsInRoot
      val img = rule.onAllNodes(isRoot())[0].captureToImage().asAndroidBitmap()
      var brightest = 0.0
      for (x in close.left.toInt().coerceAtLeast(0) until close.right.toInt().coerceAtMost(img.width)) for (y in close.top.toInt().coerceAtLeast(0) until close.bottom.toInt().coerceAtMost(img.height)) {
        val p = img.getPixel(x, y)
        fun lin(v: Int) = (v / 255.0).let { if (it <= 0.03928) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4) }
        val lum = 0.2126 * lin((p shr 16) and 0xff) + 0.7152 * lin((p shr 8) and 0xff) + 0.0722 * lin(p and 0xff)
        brightest = max(brightest, lum)
      }
      val contrast = (brightest + 0.05) / 0.05   // against the black screen
      check(contrast >= 4.5) { "${c.tag}: Close is ${"%.1f".format(contrast)}:1 on black" }
    }
    a.assertNoFailures()
  }

  // ---- P9: the selection bar keeps the whole count in Luganda at normal text ----
  @Test fun selectionCountIsWholeInLuganda() {
    val a = Atlas(rule, "t-polish-selbar")
    a.forEach(listOf(Cfg(1.0f, false, Dev.PHONE, "lg"), Cfg(1.0f, false, Dev.SMALL, "lg"), Cfg(1.0f)), "selbar") { c ->
      prefs()
      for (all in listOf(false, true)) {
        a.launch(c) { SelectionTopBar(count = 1, allSelected = all, onSelectAll = {}, onExit = {}) }
        val head = rule.onAllNodes(androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.Heading), useUnmergedTree = true)
          .fetchSemanticsNodes().single()
        check(!layoutOf(head).hasVisualOverflow) { "${c.tag} all=$all: the count is cut" }
      }
    }
    a.assertNoFailures()
  }
}
