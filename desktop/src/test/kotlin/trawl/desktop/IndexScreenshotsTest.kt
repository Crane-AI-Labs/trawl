package trawl.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.craneai.findr.core.FileKind
import com.craneai.findr.core.IndexOpenProblem
import com.craneai.findr.core.StorageEstimate
import java.io.File
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue
import org.jetbrains.skia.EncodedImageFormat
import trawl.desktop.ui.LanguagePageContent
import trawl.desktop.ui.LanguagePage
import trawl.desktop.ui.ProvideLayoutDirection
import trawl.desktop.ui.SunlightTheme
import trawl.desktop.ui.index.IndexActions
import trawl.desktop.ui.index.IndexDetailPanel
import trawl.desktop.ui.index.IndexMeter

/**
 * Draws the new screens offscreen, with made-up numbers, into build/shots/ (light and dark), and checks each picture came out. Nothing
 * opens a window, so it runs on a machine with no display. The pictures are the evidence for the write-up; the assertions are only that
 * a drawing happened and was not blank.
 */
@OptIn(ExperimentalComposeUiApi::class)
class IndexScreenshotsTest {
  private val out = File("build/shots").also { it.mkdirs() }

  @AfterTest fun reset() = Strings.use(Locale.ENGLISH)

  private fun shoot(name: String, theme: ThemeChoice, w: Int, h: Int, lang: String? = null, before: () -> Unit = {}, content: @Composable () -> Unit) {
    Strings.use(if (lang == null) Locale.ENGLISH else Locale.forLanguageTag(lang))
    val scene = ImageComposeScene(width = w, height = h, density = Density(1f)) {
      SunlightTheme(theme) {
        ProvideLayoutDirection(lang) {
          Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() }
        }
      }
    }
    try {
      scene.render(0)
      before()
      scene.render(50_000_000)
      val image = scene.render(100_000_000)
      val bytes = image.encodeToData(EncodedImageFormat.PNG)!!.bytes
      val file = File(out, "$name-${theme.name.lowercase()}.png")
      file.writeBytes(bytes)
      assertTrue(bytes.size > 8_000, "$name drew something (${bytes.size} bytes)")
    } finally {
      scene.close()
    }
  }

  private val gb = 1024L * DiskSpace.MB
  private val actions = IndexActions(onPause = {}, onResume = {}, onReadOnBattery = {}, onRetryOpen = {}, onRetryUnreadable = {})
  private val none = { null as IndexAdmin.Detail? }

  private fun working() = IndexUi(
    phase = Phase.INDEXING, total = 4210, understood = 1060, wordsIn = 2530, modelReady = true, opening = false,
    photos = 2100, documents = 2110, etaMs = 4L * 3_600_000, ratePerMin = 18.0, current = "Quarterly report 2026.pdf", freeBytes = 40 * gb,
  )

  private fun detail() = IndexAdmin.Detail(
    rows = listOf(
      IndexAdmin.KindRow(FileKind.IMAGE, 1900, 520, 1380, 0, 3 * gb),
      IndexAdmin.KindRow(FileKind.SCREENSHOT, 420, 160, 260, 0, 180 * DiskSpace.MB),
      IndexAdmin.KindRow(FileKind.DOCUMENT, 1300, 280, 1008, 12, 240 * DiskSpace.MB),
      IndexAdmin.KindRow(FileKind.PDF, 520, 100, 412, 8, 1 * gb),
      IndexAdmin.KindRow(FileKind.VIDEO, 70, 0, 70, 0, 2 * gb),
    ),
    known = 4140, wordsIn = 2530, understood = 1060, waiting = 3060,
    failed = listOf(
      IndexAdmin.FailGroup(IndexAdmin.FailReason.TOO_BIG, 8, setOf(FileKind.PDF), "scan-archive-2019.pdf"),
      IndexAdmin.FailGroup(IndexAdmin.FailReason.CANT_OPEN, 9, setOf(FileKind.DOCUMENT), "old minutes.doc"),
      IndexAdmin.FailGroup(IndexAdmin.FailReason.DAMAGED, 3, setOf(FileKind.DOCUMENT), "budget.xlsx"),
    ),
    failedTotal = 20, indexBytes = 310 * DiskSpace.MB, modelBytes = 480 * DiskSpace.MB,
    estimate = StorageEstimate.Result(indexBytes = 640 * DiskSpace.MB, vectors = 31_000, ramBytes = 96 * DiskSpace.MB), reembed = null,
  )

  private val kindsOn = setOf(FileKind.IMAGE, FileKind.SCREENSHOT, FileKind.DOCUMENT, FileKind.PDF)

  @Test fun theMeterInEveryState() {
    for (theme in listOf(ThemeChoice.LIGHT, ThemeChoice.DARK)) shoot("meter-states", theme, 820, 1500) {
      Column(Modifier.padding(16.dp).width(788.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        IndexMeter(working(), none, actions)                                                                                  // two levels, working
        IndexMeter(working().copy(modelReady = false, readingWords = true, understood = 1800, wordsIn = 0, etaMs = 2_400_000, current = "Notes.docx"), none, actions)   // words, no model yet
        IndexMeter(working().copy(reembed = 1200 to 3400, understood = 3400, wordsIn = 4210, current = null, etaMs = null), none, actions)  // new model: read again
        IndexMeter(working().copy(disk = DiskSpace.Level.WARN, freeBytes = 1_400L * DiskSpace.MB), none, actions)            // disk getting low
        IndexMeter(IndexUi(phase = Phase.PAUSED, hold = Hold.STORAGE_LOW, total = 4210, understood = 1060, wordsIn = 2530, modelReady = true, opening = false, disk = DiskSpace.Level.LOW, freeBytes = 380L * DiskSpace.MB), none, actions)
        IndexMeter(IndexUi(phase = Phase.PAUSED, hold = Hold.NO_PERMISSION, total = 4210, understood = 1060, wordsIn = 2530, modelReady = true, opening = false), none, actions)
        IndexMeter(IndexUi(phase = Phase.UP_TO_DATE, total = 4140, understood = 4128, wordsIn = 4140, modelReady = true, opening = false, unreadable = 12), none, actions)
      }
    }
  }

  @Test fun theDetailPanel() {
    for (theme in listOf(ThemeChoice.LIGHT, ThemeChoice.DARK)) shoot("detail-panel", theme, 700, 1100) {
      Box(Modifier.padding(16.dp)) { IndexDetailPanel(working(), detail(), actions, kindsOn, onClose = {}) }
    }
    // The same panel scrolled to the bottom: what waits, what failed and why, the retry, Done.
    for (theme in listOf(ThemeChoice.LIGHT, ThemeChoice.DARK)) {
      val scroll = androidx.compose.foundation.ScrollState(0)
      shoot("detail-panel-lower", theme, 700, 820, before = { kotlinx.coroutines.runBlocking { scroll.scrollTo(100_000) } }) {
        Box(Modifier.padding(16.dp)) { IndexDetailPanel(working(), detail(), actions, kindsOn, onClose = {}, scroll = scroll) }
      }
    }
  }

  @Test fun theCalmCard() {
    for (theme in listOf(ThemeChoice.LIGHT, ThemeChoice.DARK)) shoot("open-problem", theme, 820, 520) {
      Column(Modifier.padding(16.dp).width(788.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        IndexMeter(IndexUi(openProblem = IndexOpenProblem.BUSY, opening = false), none, actions)
        IndexMeter(IndexUi(openProblem = IndexOpenProblem.STORAGE_FULL, opening = false), none, actions)
        IndexMeter(IndexUi(opening = true), none, actions)
      }
    }
  }

  @Test fun theLanguagePage() {
    for (theme in listOf(ThemeChoice.LIGHT, ThemeChoice.DARK)) {
      shoot("language-page", theme, 900, 820) {
        LanguagePageContent(LanguagePage.preselect(Locale.forLanguageTag("sw")), LanguagePage.preselect(Locale.forLanguageTag("sw")), {}, {})
      }
    }
  }

  @Test fun theArabicLayoutRunsRightToLeft() {
    for (theme in listOf(ThemeChoice.LIGHT, ThemeChoice.DARK)) {
      shoot("rtl-language-page", theme, 900, 820, lang = "ar") {
        LanguagePageContent(LanguagePage.preselect(Locale.forLanguageTag("ar")), LanguagePage.preselect(Locale.forLanguageTag("ar")), {}, {})
      }
      shoot("rtl-meter", theme, 820, 420, lang = "ar") {
        Column(Modifier.padding(16.dp).width(788.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
          IndexMeter(working(), none, actions)
          IndexMeter(IndexUi(phase = Phase.UP_TO_DATE, total = 4140, understood = 4140, wordsIn = 4140, modelReady = true, opening = false), none, actions)
        }
      }
    }
  }
}
