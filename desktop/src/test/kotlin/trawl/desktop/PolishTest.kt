package trawl.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.craneai.findr.core.FileKind
import com.craneai.findr.index.CrawlRecord
import com.craneai.findr.index.FileState
import com.craneai.findr.index.IndexStore
import com.craneai.findr.index.Modality
import com.craneai.findr.index.Segment
import com.craneai.findr.index.SegmentKind
import com.craneai.findr.ui.common.formatBytes
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import trawl.desktop.privacy.PrivateThumbnails
import trawl.desktop.shell.FakeSearch
import trawl.desktop.shell.QuickPanelState
import trawl.desktop.shell.fakeFile
import trawl.desktop.ui.Icons
import trawl.desktop.ui.MeterText
import trawl.desktop.ui.StatusParts
import trawl.desktop.ui.SunlightTheme
import trawl.desktop.ui.index.IndexActions
import trawl.desktop.ui.index.IndexDetailPanel
import trawl.desktop.ui.index.MeterTexts
import trawl.desktop.ui.quick.QuickPanel
import trawl.desktop.ui.ThumbState
import trawl.desktop.ui.rememberThumb

/** The 2026-10-21 polish pass: each rough edge it fixed has a test that fails on the old behaviour. */
@OptIn(ExperimentalTestApi::class)
class PolishTest {
  @BeforeTest fun english() = Strings.use(Locale.ENGLISH)

  private val tmp: File = Files.createTempDirectory("trawl-polish").toFile()
  @AfterTest fun clean() { tmp.deleteRecursively() }

  // ---- 1. the Recent preview shows the document's opening text ----

  @Test fun leadTextJoinsTheFirstChunksAndCutsAtAWord() {
    assertNull(SearchApi.joinLead(emptyList(), 100))
    assertNull(SearchApi.joinLead(listOf("   ", "\n\t"), 100), "only whitespace is no text")
    assertEquals("one two three", SearchApi.joinLead(listOf("one  two", "three\n"), 100), "chunks join with one space, whitespace collapses")
    val long = SearchApi.joinLead(listOf("alpha beta gamma delta epsilon zeta eta theta"), 20)!!
    assertTrue(long.endsWith("…"), long)
    assertTrue(long.length <= 21, long)
    assertFalse(long.dropLast(1).endsWith("gam"), "cut at a word, not in the middle of one: $long")
  }

  @Test fun leadTextReadsTheStoredChunksOfARealIndex() {
    val store = IndexStore.open(File(tmp, "idx").also { it.mkdirs() }, log = {})
    try {
      store.upsertFromCrawl(listOf(CrawlRecord("desktop", 1, "file:///t/plan.pdf", "t/", "plan.pdf", FileKind.PDF, null, 1000, 1_700_000_000_000L, dateMillis = 1_700_000_000_000L)))
      val id = store.knownMediaStoreIds("desktop").getValue(1)
      store.saveSegments(
        id, listOf(
          Segment(SegmentKind.CHUNK, Modality.NONE, locStart = 1, text = "Quarterly plan for the lake road clinic."),
          Segment(SegmentKind.CHUNK, Modality.NONE, locStart = 2, text = "Budget lines and staffing."),
        ), FileState.UNDERSTOOD,
      )
      val api = SearchApi(SearchHooks({ store }, { null }, { false }, { 0 }, { null }, { 1.0 }, { _, _ -> 1.0 }, { Crawler.KINDS }))
      assertEquals("Quarterly plan for the lake road clinic. Budget lines and staffing.", api.leadText(id))
      assertNull(api.leadText(id + 99), "a file with no stored text has no lead")
      assertNull(SearchApi(SearchHooks({ null }, { null }, { false }, { 0 }, { null }, { 1.0 }, { _, _ -> 1.0 }, { Crawler.KINDS })).leadText(id), "an index that is not open has none")
    } finally { store.close() }
  }

  // ---- 2. Done is pinned under the scrolling detail ----

  @Test fun doneStaysPutWhileTheDetailScrolls() = runComposeUiTest {
    val scroll = androidx.compose.foundation.ScrollState(0)
    val actions = IndexActions(onPause = {}, onResume = {}, onReadOnBattery = {}, onRetryOpen = {}, onRetryUnreadable = {})
    val ix = IndexUi(phase = Phase.INDEXING, total = 4210, understood = 1060, wordsIn = 2530, modelReady = true, opening = false, current = "Quarterly report 2026.pdf")
    setContent {
      SunlightTheme { Box(Modifier.size(700.dp, 420.dp)) { IndexDetailPanel(ix, null, actions, null, onClose = {}, scroll = scroll) } }
    }
    waitForIdle()
    val done = onNodeWithText(t("action_done"))
    done.assertIsDisplayed()
    val before = done.getUnclippedBoundsInRoot()
    assertTrue(scroll.maxValue > 0, "the content is taller than the card, so it scrolls")
    runOnIdle { kotlinx.coroutines.runBlocking { scroll.scrollTo(scroll.maxValue) } }
    waitForIdle()
    done.assertIsDisplayed()
    assertEquals(before.top, done.getUnclippedBoundsInRoot().top, "Done does not move with the content")
    assertTrue(before.bottom <= 420.dp, "Done sits inside the card at 420 dp tall")
  }

  // ---- 3. a click in the quick panel's margin closes it; a click on the card does not ----

  @Test fun clickingTheMarginClosesTheQuickPanelButTheCardDoesNot() = runComposeUiTest {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val closed = IntArray(1)
    val dismissed = IntArray(1)
    val state = QuickPanelState(FakeSearch(listOf(fakeFile(1, "invoice.pdf", FileKind.PDF))), scope, onClose = { closed[0]++ }, debounceMs = 30)
    try {
      setContent { SunlightTheme { Box(Modifier.size(700.dp, 500.dp)) { QuickPanel(state, Os.WINDOWS, null, inset = 28.dp, onDismiss = { dismissed[0]++ }) } } }
      waitForIdle()
      val px = { dp: Int -> with(density) { dp.dp.toPx() } }
      onRoot().performMouseInput { click(Offset(px(10), px(10))) }
      waitForIdle()
      assertEquals(1, dismissed[0], "a click in the transparent margin asks the window to hide")
      onRoot().performMouseInput { click(Offset(px(28 + 4), px(28 + 60))) }   // inside the card, in its padding beside the search box
      waitForIdle()
      assertEquals(1, dismissed[0], "a click on the card itself does not")
    } finally { scope.cancel() }
  }

  // ---- 4. the reading text is said once ----

  @Test fun namesOnlyIsSaidByTheCardAndNotAgainByTheStatusBar() {
    val ix = IndexUi(opening = false, phase = Phase.UP_TO_DATE, total = 10, modelReady = false, modelLoading = false)
    val line = MeterText.of(ix)
    assertTrue(line.namesOnly)
    assertTrue(StatusParts.cardShown(line, ix), "names-only is a warning, so the card is up")
    assertNull(StatusParts.summary(line, ix, cardShown = true), "so the status bar says nothing about it")
  }

  @Test fun pausedIsSaidOnceAndQuietReadingStaysInTheBar() {
    val paused = IndexUi(opening = false, phase = Phase.PAUSED, hold = Hold.USER, total = 10, modelReady = true)
    assertTrue(StatusParts.cardShown(MeterText.of(paused), paused))
    assertNull(StatusParts.summary(MeterText.of(paused), paused, true))
    val reading = IndexUi(opening = false, phase = Phase.INDEXING, total = 200, understood = 50, modelReady = true)
    val line = MeterText.of(reading)
    assertFalse(StatusParts.cardShown(line, reading), "ordinary reading is the quiet bar, no card")
    assertTrue(StatusParts.summary(line, reading, false)!!.startsWith(line.title))
  }

  // ---- 5. a pause has a pause icon ----

  @Test fun theCardsIconFitsTheState() {
    fun icon(ix: IndexUi) = MeterTexts.icon(ix, MeterTexts.of(ix))
    assertSame(Icons.Pause, icon(IndexUi(opening = false, phase = Phase.PAUSED, hold = Hold.USER, total = 10, modelReady = true)))
    assertSame(Icons.Pause, icon(IndexUi(opening = false, phase = Phase.PAUSED, hold = Hold.BATTERY, total = 10, modelReady = true)))
    assertSame(Icons.Warning, icon(IndexUi(opening = false, phase = Phase.PAUSED, hold = Hold.STORAGE_LOW, total = 10, modelReady = true)), "a pause that is a problem is still a warning")
    assertSame(Icons.Check, icon(IndexUi(opening = false, phase = Phase.UP_TO_DATE, total = 10, understood = 10, modelReady = true)))
    assertSame(Icons.Search, icon(IndexUi(opening = false, phase = Phase.INDEXING, total = 10, understood = 2, modelReady = true)))
  }

  // ---- 6. thumbnails: a decoded picture is there on the first frame ----

  private fun png(name: String): File {
    val f = File(tmp, name)
    ImageIO.write(BufferedImage(40, 30, BufferedImage.TYPE_INT_RGB).also { g -> g.graphics.apply { color = java.awt.Color.ORANGE; fillRect(0, 0, 40, 30) } }, "png", f)
    return f
  }

  @Test fun aDecodedThumbnailIsPeekableAndANewOneIsNot() {
    val f = png("a.png")
    assertNull(Thumbnails.peek(f, 64), "not decoded yet")
    val bmp = Thumbnails.load(f, 64)
    assertNotNull(bmp)
    assertSame(bmp, Thumbnails.peek(f, 64), "the same bitmap, with no decoding")
    assertNull(Thumbnails.peek(f, 128), "another size is another entry")
    assertSame(bmp, PrivateThumbnails.peek(f, 64, blurred = false))
    assertNull(PrivateThumbnails.peek(f, 64, blurred = true), "a blurred picture is a different one and is not there until it is blurred")
  }

  @Test fun aTileThatScrollsBackInStartsDecodedAndAFreshOneStartsAsAPlaceholder() = runComposeUiTest {
    val warm = png("warm.png"); Thumbnails.load(warm, 64)
    val cold = png("cold.png")
    var warmFirst: ThumbState? = null
    var coldFirst: ThumbState? = null
    setContent {
      val w = rememberThumb(warm, 64); if (warmFirst == null) warmFirst = w
      val c = rememberThumb(cold, 64); if (coldFirst == null) coldFirst = c
    }
    assertTrue(warmFirst!!.done && warmFirst!!.bitmap != null, "a cached picture is drawn on the very first frame")
    assertFalse(coldFirst!!.done, "an undecoded one starts as a placeholder")
    waitUntil(timeoutMillis = 5000) { Thumbnails.peek(cold, 64) != null }
  }

  // ---- 7. one measurement for the Privacy card's size ----

  @Test fun theCostLineTheRemoveButtonAndTheDialogAgree() {
    val def = AddOnCatalog.privacy
    // 22.6 MB on disk: the old card printed floor() = 22 beside "frees 23 MB".
    val installed = AddOnState.Installed(22_600_000L)
    val size = def.shownSize(installed)
    assertEquals(formatBytes(22_600_000L), size)
    assertTrue(t(def.costKey, size).contains(size))
    assertTrue(t("addons_remove", size).contains(size))
    assertTrue(t("addons_remove_body", size).contains(size))
    assertFalse(t(def.costKey, size).contains(" MB MB"), "the unit is part of the size, not printed twice")
    // Not installed: the manifest's size, through the same formatter as the Add button.
    assertEquals(formatBytes(def.sizeBytes), def.shownSize(AddOnState.NotInstalled))
    // An install whose size could not be measured (0) falls back to the manifest rather than printing "0 B".
    assertEquals(formatBytes(def.sizeBytes), def.shownSize(AddOnState.Installed(0)))
  }
}
