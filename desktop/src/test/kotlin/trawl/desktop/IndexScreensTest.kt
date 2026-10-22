package trawl.desktop

import androidx.compose.ui.unit.LayoutDirection
import com.craneai.findr.core.FileKind
import com.craneai.findr.core.IndexOpenProblem
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import trawl.desktop.ui.LanguagePage
import trawl.desktop.ui.Rtl
import trawl.desktop.ui.index.DetailFacts
import trawl.desktop.ui.index.KindLabels
import trawl.desktop.ui.index.Levels
import trawl.desktop.ui.index.MeterTexts

/** The words and numbers of the meter, the detail panel, the calm card and the language page, read for every state without drawing a pixel. */
class IndexScreensTest {
  @BeforeTest fun english() = Strings.use(Locale.ENGLISH)
  @AfterTest fun reset() = Strings.use(Locale.ENGLISH)

  private fun ix(
    phase: Phase = Phase.INDEXING, total: Int = 200, understood: Int = 50, wordsIn: Int = 120, model: Boolean = true,
    words: Boolean = false, hold: Hold? = null, eta: Long? = null,
  ) = IndexUi(phase = phase, total = total, understood = understood, wordsIn = wordsIn, modelReady = model, readingWords = words, hold = hold, etaMs = eta, opening = false)

  // ---- the two levels ---------------------------------------------------------------------------------------------------------

  @Test fun theSearchableLevelIsNeverShorterThanTheUnderstoodOne() {
    val l = Levels.of(ix(total = 200, understood = 50, wordsIn = 120))
    assertEquals(0.6f, l.searchable, 0.001f)
    assertEquals(0.25f, l.understood, 0.001f)
    assertEquals(60, l.searchablePercent); assertEquals(25, l.understoodPercent)
    // A count that disagrees (words in lagging behind meaning) still draws solid inside pale.
    val odd = Levels.of(ix(total = 100, understood = 80, wordsIn = 10))
    assertTrue(odd.searchable >= odd.understood)
  }

  @Test fun withNoModelNothingIsUnderstoodByMeaning() {
    val l = Levels.of(ix(model = false, understood = 40, wordsIn = 150))
    assertEquals(0f, l.understood)
    assertEquals(0.75f, l.searchable, 0.001f)
  }

  @Test fun whileTheWordsAreBeingReadTheCountIsTheWordsLevel() {
    val l = Levels.of(ix(model = false, words = true, understood = 60, wordsIn = 0, total = 120))
    assertEquals(0.5f, l.searchable, 0.001f)
    assertEquals(0f, l.understood)
  }

  @Test fun anEmptyIndexIsEmptyAndNeverDividesByZero() {
    assertEquals(Levels(0f, 0f), Levels.of(IndexUi(total = 0)))
    val full = Levels.of(ix(phase = Phase.UP_TO_DATE, total = 10, understood = 10, wordsIn = 10))
    assertEquals(1f, full.searchable); assertEquals(1f, full.understood)
  }

  // ---- the meter's words ------------------------------------------------------------------------------------------------------

  @Test fun everyStateOfTheMeterSpeaksWithNoMissingKey() {
    val problems = IndexOpenProblem.entries
    val states = buildList {
      for (phase in Phase.entries) for (model in listOf(true, false)) for (words in listOf(true, false)) for (eta in listOf(null, 90_000L, 7_200_000L)) {
        add(ix(phase = phase, model = model, words = words, eta = eta))
        add(ix(phase = phase, model = model, words = words, eta = eta).copy(unreadable = 3, reembed = 4 to 9, current = "x.pdf", modelLoading = !model))
      }
      for (h in Hold.entries) add(ix(phase = Phase.PAUSED, hold = h))
      for (p in problems) add(IndexUi(openProblem = p, opening = false))
      add(IndexUi(opening = true))
    }
    for (os in Os.entries) for (s in states) {
      val m = MeterTexts.of(s, os)
      assertFalse(m.title.contains("[") && m.title.startsWith("["), "title for $s: ${m.title}")
      assertFalse(m.sub.startsWith("["), "sub for $s: ${m.sub}")
      assertTrue(m.title.isNotBlank(), "a title for $s")
    }
    for (p in problems) assertFalse(MeterTexts.problem(p).startsWith("["), "a sentence for $p")
  }

  @Test fun aHoldNamesItsReasonAndItsNextStep() {
    val low = MeterTexts.of(ix(phase = Phase.PAUSED, hold = Hold.STORAGE_LOW), Os.MAC)
    assertEquals("Disk space is almost full", low.title)
    assertTrue(low.sub.contains("Free some space"))
    assertTrue(low.bad)
    val mac = MeterTexts.of(ix(phase = Phase.PAUSED, hold = Hold.NO_PERMISSION), Os.MAC)
    val win = MeterTexts.of(ix(phase = Phase.PAUSED, hold = Hold.NO_PERMISSION), Os.WINDOWS)
    assertTrue(mac.sub.contains("Privacy & Security"), "macOS is told where the switch is")
    assertFalse(win.sub.contains("Privacy & Security"))
    assertEquals(mac.title, win.title)
    assertNotEquals(MeterTexts.of(ix(phase = Phase.PAUSED, hold = Hold.USER)).title, low.title)
  }

  @Test fun theFinishedMeterOwnsUpToUnreadableFiles() {
    val ready = MeterTexts.of(ix(phase = Phase.UP_TO_DATE, total = 40, understood = 40, wordsIn = 40))
    assertEquals("All caught up", ready.title)
    assertEquals("40 files are ready to search.", ready.sub)
    val some = MeterTexts.of(ix(phase = Phase.UP_TO_DATE, total = 40, understood = 40, wordsIn = 40).copy(unreadable = 3))
    assertTrue(some.sub.startsWith("Ready to search. 3 files could not be read"))
    val one = MeterTexts.of(ix(phase = Phase.UP_TO_DATE, total = 40, understood = 40, wordsIn = 40).copy(unreadable = 1))
    assertTrue(one.sub.contains("1 file could not be read"))
  }

  @Test fun videosWaitingForAMissingDecoderAreNotAllCaughtUp() {
    val base = ix(phase = Phase.UP_TO_DATE, total = 40, understood = 40, wordsIn = 40).copy(mediaDecoder = "none", mediaNeedDecoder = 2)
    val linux = MeterTexts.of(base, Os.LINUX)
    assertEquals("Videos and recordings are waiting", linux.title)
    assertEquals("Install ffmpeg to read video and audio. 2 waiting.", linux.sub)
    assertTrue(linux.bad)
    val win = MeterTexts.of(base, Os.WINDOWS)
    assertFalse(win.sub.contains("ffmpeg"), "only Linux is told to install ffmpeg")
    assertTrue(win.bad)
    assertEquals("All caught up", MeterTexts.of(base.copy(mediaNeedDecoder = 0), Os.LINUX).title)
  }

  @Test fun aModelUpgradeSaysHowFarTheReReadIs() {
    val m = MeterTexts.of(ix().copy(reembed = 1_200 to 3_400))
    assertTrue(m.sub.contains("1,200 of 3,400"), m.sub)
    assertTrue(m.sub.contains("Search keeps working"))
  }

  @Test fun theCalmCardNeverShowsAnException() {
    val m = MeterTexts.of(IndexUi(openProblem = IndexOpenProblem.BUSY, opening = false))
    assertEquals("Trawl could not open its index right now", m.title)
    assertTrue(MeterTexts.problem(IndexOpenProblem.STORAGE_FULL).contains("disk is full"))
    assertEquals(IndexOpenProblem.entries.size, IndexOpenProblem.entries.map { MeterTexts.problem(it) }.toSet().size, "one sentence per kind of trouble")
  }

  @Test fun theDiskWarningNeedsLowRoomAndAKnownFigure() {
    assertNull(MeterTexts.diskWarning(IndexUi(disk = DiskSpace.Level.OK, freeBytes = 50L * DiskSpace.MB)))
    assertNull(MeterTexts.diskWarning(IndexUi(disk = DiskSpace.Level.WARN, freeBytes = null)))
    val line = MeterTexts.diskWarning(IndexUi(disk = DiskSpace.Level.WARN, freeBytes = 1_500L * DiskSpace.MB))!!
    assertTrue(line.startsWith("Disk space is getting low"))
    val need = MeterTexts.diskWarning(IndexUi(disk = DiskSpace.Level.WARN, freeBytes = 1_500L * DiskSpace.MB), growthBytes = 2_000L * DiskSpace.MB)!!
    assertTrue(need.contains("still needs"))
  }

  // ---- the panel's facts ------------------------------------------------------------------------------------------------------

  @Test fun theSpeedIsFilesAnHourFromTheMeasuredRate() {
    assertNull(DetailFacts.perHour(IndexUi()))
    assertNull(DetailFacts.perHour(IndexUi(ratePerMin = 0.0)))
    assertEquals(300, DetailFacts.perHour(IndexUi(ratePerMin = 5.0)))
    assertEquals(1, DetailFacts.perHour(IndexUi(ratePerMin = 0.001)), "slow but not zero")
    val facts = DetailFacts.of(ix(eta = 3_600_000L).copy(ratePerMin = 5.0), null, freeBytes = null)
    assertEquals("300 files an hour", facts.first { it.label == "Speed" }.value)
    assertTrue(facts.any { it.label == "Time left" })
    val measuring = DetailFacts.of(ix(), null, freeBytes = null)
    assertEquals("Measuring", measuring.first { it.label == "Time left" }.value)
  }

  @Test fun aFinishedIndexShowsNoSpeedOrTimeLeft() {
    val facts = DetailFacts.of(ix(phase = Phase.UP_TO_DATE), null, freeBytes = 2_000L * DiskSpace.MB)
    assertTrue(facts.none { it.label == "Speed" || it.label == "Time left" })
    assertTrue(facts.any { it.label == "Free on this disk" })
  }

  @Test fun theWaitingLineAgreesWithTheNumber() {
    assertEquals("Nothing is waiting.", DetailFacts.waitingLine(0))
    assertEquals("1 file is waiting to be read.", DetailFacts.waitingLine(1))
    assertEquals("1,234 files are waiting to be read.", DetailFacts.waitingLine(1234))
  }

  @Test fun everyKindAndEveryFailureReasonHasWords() {
    for (k in FileKind.entries) assertTrue(Strings.has(KindLabels.key(k)), "kind ${k}")
    for (r in IndexAdmin.FailReason.entries) assertTrue(Strings.has(r.key), "reason ${r}")
    val row = IndexAdmin.KindRow(FileKind.IMAGE, known = 150, understood = 120, waiting = 20, failed = 2, bytes = 1)
    assertEquals("120 of 150 · 20 waiting · 2 could not be read", KindLabels.summary(row, on = true))
    assertEquals("switched off", KindLabels.summary(row, on = false))
    assertEquals("120 of 150", KindLabels.summary(row.copy(waiting = 0, failed = 0), on = true))
  }

  @Test fun everyNewCountedKeyHasBothForms() {
    for (base in listOf("index_kind_waiting", "index_kind_failed", "index_speed", "index_waiting_line", "index_failed_files", "index_ready_unreadable")) {
      assertTrue(Strings.has("${base}_one") && Strings.has("${base}_other"), base)
    }
  }

  // ---- the language page and the layout direction -----------------------------------------------------------------------------

  @Test fun theLanguagePageStartsOnTheComputersLanguage() {
    assertEquals("sw", LanguagePage.preselect(Locale.forLanguageTag("sw-KE")).tag)
    assertEquals("fr", LanguagePage.preselect(Locale.FRANCE).tag)
    assertEquals("ar", LanguagePage.preselect(Locale.forLanguageTag("ar-EG")).tag)
    assertEquals("en", LanguagePage.preselect(Locale.JAPAN).tag, "a language Trawl does not have falls back to English")
    assertEquals("lg", LanguagePage.selected("lg", Locale.FRANCE).tag, "the person's pick wins")
    assertEquals("fr", LanguagePage.selected(null, Locale.FRANCE).tag)
    assertEquals("en", LanguagePage.selected("zz", Locale.JAPAN).tag, "an unknown saved tag is ignored")
    assertEquals(14, Languages.shipped.size)
  }

  @Test fun onlyArabicOfTheShippedLanguagesReadsRightToLeft() {
    val rtl = Languages.shipped.filter { Rtl.isRtl(it.tag) }.map { it.tag }
    assertEquals(listOf("ar"), rtl)
    assertEquals(LayoutDirection.Rtl, Rtl.directionFor("ar", Locale.ENGLISH))
    assertEquals(LayoutDirection.Ltr, Rtl.directionFor("en", Locale.forLanguageTag("ar")), "the pick wins over the computer")
    assertEquals(LayoutDirection.Rtl, Rtl.directionFor(null, Locale.forLanguageTag("ar-SA")), "following the computer")
    assertEquals(LayoutDirection.Ltr, Rtl.directionFor(null, Locale.ENGLISH))
    assertTrue(Rtl.isRtl("ar-EG") && Rtl.isRtl("AR_eg"))
  }
}
