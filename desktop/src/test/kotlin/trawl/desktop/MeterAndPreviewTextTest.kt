package trawl.desktop

import com.craneai.findr.core.FileKind
import com.craneai.findr.core.IndexedFile
import java.time.ZoneOffset
import java.util.Locale
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import trawl.desktop.ui.MeterText
import trawl.desktop.ui.PathText
import trawl.desktop.ui.PreviewFacts

/** The sentences the window builds from numbers: the reading status bar, the preview's facts, and the home folder shown as "~". */
class MeterAndPreviewTextTest {
  @BeforeTest fun english() = Strings.use(Locale.ENGLISH)

  // --- the status bar ---

  @Test fun upToDateSaysSoAndHasNoBar() {
    val m = MeterText.of(IndexUi(opening = false, phase = Phase.UP_TO_DATE, total = 80, understood = 80, modelReady = true))
    assertEquals(t("meter_title_up_to_date"), m.title)
    assertFalse(m.showBar); assertFalse(m.working); assertFalse(m.warning)
    assertNull(m.fraction)
  }

  @Test fun readingShowsABarWithTheFractionDone() {
    val m = MeterText.of(IndexUi(opening = false, phase = Phase.INDEXING, total = 200, understood = 50, modelReady = true, photos = 3, documents = 2))
    assertEquals(t("meter_title_indexing"), m.title)
    assertTrue(m.showBar); assertTrue(m.working)
    assertEquals(0.25f, m.fraction)
    assertNotNull(m.kinds)
  }

  @Test fun scanningHasABarOfUnknownLength() {
    val m = MeterText.of(IndexUi(opening = false, phase = Phase.SCANNING, scanned = 1200))
    assertEquals(t("meter_title_scanning"), m.title)
    assertTrue(m.showBar)
    assertNull(m.fraction)
  }

  @Test fun noModelIsAWarningNotProgress() {
    val m = MeterText.of(IndexUi(opening = false, phase = Phase.UP_TO_DATE, total = 10, modelReady = false, modelLoading = false))
    assertTrue(m.namesOnly); assertTrue(m.warning)
    assertEquals(t("meter_title_waiting_models"), m.title)
    assertFalse(m.working)
    assertFalse(m.showBar)
  }

  @Test fun readingWordsWithoutAModelIsRealProgress() {
    val m = MeterText.of(IndexUi(opening = false, phase = Phase.INDEXING, total = 10, understood = 5, modelReady = false, readingWords = true))
    assertFalse(m.namesOnly)
    assertEquals(t("meter_title_words"), m.title)
    assertTrue(m.working)
    assertEquals(0.5f, m.fraction)
  }

  @Test fun anErrorIsShownAsAWarningWithItsText() {
    val m = MeterText.of(IndexUi(opening = false, phase = Phase.ERROR, error = "disk full"))
    assertTrue(m.error); assertTrue(m.warning)
    assertEquals("disk full", m.sub)
    assertEquals(t("meter_title_error"), m.title)
  }

  @Test fun pausedSaysWhoPausedIt() {
    assertEquals(t("pause_user"), MeterText.of(IndexUi(opening = false, phase = Phase.PAUSED, hold = Hold.USER)).title)
    assertEquals(t("pause_not_charging"), MeterText.of(IndexUi(opening = false, phase = Phase.PAUSED, hold = Hold.BATTERY)).title)
  }

  @Test fun theEtaIsPutInWordsAtEachScale() {
    assertEquals(t("meter_eta_under_minute"), MeterText.etaText(20_000))
    assertTrue(MeterText.etaText(5 * 60_000L).contains("5"), MeterText.etaText(5 * 60_000L))
    assertTrue(MeterText.etaText(3 * 3_600_000L).isNotBlank())
  }

  // --- the preview's facts ---

  private fun file(kind: FileKind, modified: Long = 1_700_000_000_000L, taken: Long = modified, w: Int? = null, h: Int? = null, ms: Long? = null, size: Long = 2_500_000) =
    IndexedFile(1, "file:///x", "x", "x", kind, null, size, modified, w, h, ms, dateMillis = taken)

  private fun facts(f: IndexedFile, folder: String = "~/Pictures") = PreviewFacts.of(f, folder, Locale.ENGLISH, ZoneOffset.UTC).associate { it.label to it.value }

  @Test fun aDocumentShowsFolderSizeModifiedAndType() {
    val m = facts(file(FileKind.DOCUMENT))
    assertEquals("~/Pictures", m[t("fact_folder")])
    assertEquals("2.5 MB", m[t("fact_size")])
    assertEquals("Nov 14, 2023", m[t("fact_modified")])
    assertEquals(t("kind_document"), m[t("fact_kind")])
    assertNull(m[t("fact_dimensions")])
    assertNull(m[t("fact_taken")])
  }

  @Test fun aPhotoWithACaptureDateSaysTakenAndGivesItsDimensions() {
    val m = facts(file(FileKind.IMAGE, modified = 1_700_000_000_000L, taken = 1_600_000_000_000L, w = 4032, h = 3024))
    assertEquals("Sep 13, 2020", m[t("fact_taken")])
    assertNull(m[t("fact_modified")])
    assertEquals("4032 × 3024", m[t("fact_dimensions")])
  }

  @Test fun aPhotoWithoutACaptureDateSaysModified() {
    val m = facts(file(FileKind.SCREENSHOT))
    assertNotNull(m[t("fact_modified")])
    assertNull(m[t("fact_taken")])
  }

  @Test fun aVideoOrRecordingShowsItsLengthWithTheType() {
    val m = facts(file(FileKind.VIDEO, ms = 83_000))
    val type = m.getValue(t("fact_kind"))
    assertTrue(type.startsWith(t("kind_video")), type)
    assertTrue(type.contains("1:23"), type)
  }

  @Test fun aBlankFolderOrNoDateLeavesThoseLinesOut() {
    assertFalse(facts(file(FileKind.DOCUMENT), folder = "").containsKey(t("fact_folder")))
    val undated = facts(file(FileKind.DOCUMENT, modified = 0, taken = 0))
    assertFalse(undated.containsKey(t("fact_modified")))
  }

  @Test fun onlyPicturesCountAsMedia() {
    assertTrue(PreviewFacts.isMedia(FileKind.IMAGE)); assertTrue(PreviewFacts.isMedia(FileKind.SCREENSHOT))
    assertFalse(PreviewFacts.isMedia(FileKind.PDF)); assertFalse(PreviewFacts.isMedia(FileKind.VIDEO))
  }

  // --- paths ---

  @Test fun theHomeFolderIsShownAsATilde() {
    assertEquals("~", PathText.tilde("/Users/me", "/Users/me"))
    assertEquals("~/Documents/a b", PathText.tilde("/Users/me/Documents/a b", "/Users/me"))
    assertEquals("~/x", PathText.tilde("/Users/me/x", "/Users/me/"), "a trailing slash on home is ignored")
    assertEquals("~\\Pictures", PathText.tilde("C:\\Users\\me\\Pictures", "C:\\Users\\me"))
  }

  @Test fun otherPathsAreLeftAlone() {
    assertEquals("/Users/mellow/x", PathText.tilde("/Users/mellow/x", "/Users/me"), "a longer name that starts the same is not home")
    assertEquals("/mnt/data", PathText.tilde("/mnt/data", "/Users/me"))
    assertEquals("/Users/me", PathText.tilde("/Users/me", ""), "no known home: unchanged")
  }

  // --- the quick panel's folder line ---

  @Test fun quickPanelNamesTheRealFolder() {
    val folder = trawl.desktop.ui.quick.quickFolder("file:///data/Tax/2026/invoice.pdf", "Documents")
    assertTrue(folder.endsWith("Tax/2026"), folder)
    assertEquals("Documents", trawl.desktop.ui.quick.quickFolder("not a uri", "Documents"), "an unreadable location falls back to what the index knew")
  }
}
