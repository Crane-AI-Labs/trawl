package trawl.desktop.ui

import com.craneai.findr.core.DateLabel
import com.craneai.findr.core.DatePreset
import com.craneai.findr.core.DateUnit
import com.craneai.findr.core.DayPart
import com.craneai.findr.core.FileKind
import com.craneai.findr.core.IndexedFile
import com.craneai.findr.core.KindFilter
import com.craneai.findr.core.MatchReason
import com.craneai.findr.core.DateFilter
import com.craneai.findr.core.DateRange
import com.craneai.findr.core.SortOrder
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import trawl.desktop.ActiveDate
import trawl.desktop.ClipboardPort
import trawl.desktop.Clip
import trawl.desktop.CopyOutcome
import trawl.desktop.Os
import trawl.desktop.ResultRow
import trawl.desktop.ResultsUi
import trawl.desktop.Share
import trawl.desktop.ShareFacility
import trawl.desktop.SimilarTo
import trawl.desktop.Strings
import trawl.desktop.ageBasis
import trawl.desktop.ui.search.Age
import trawl.desktop.ui.search.EmptyKind
import trawl.desktop.ui.search.Picking
import trawl.desktop.ui.search.RowText
import trawl.desktop.ui.search.SearchStatus
import trawl.desktop.ui.search.STACK_BELOW_DP
import trawl.desktop.ui.search.emptyKind
import trawl.desktop.ui.search.listStyleOf
import trawl.desktop.ui.search.sortChoices
import trawl.desktop.ui.search.stackRows
import trawl.desktop.ui.search.menuText
import trawl.desktop.ui.search.text

/** The search window's pure rules: ages, the row subtitle, which empty state, the status line, selection, key caps, copy and share. No window needed. */
class SearchUiLogicTest {
  @BeforeTest fun english() { Strings.use(Locale.ENGLISH) }

  private fun file(id: Long, name: String = "f$id.txt", kind: FileKind = FileKind.DOCUMENT) =
    IndexedFile(id, "file:///t/$name", "t/", name, kind, null, 10, 1)
  private fun row(id: Long) = ResultRow(file(id), null, MatchReason.WORDS, null, true)

  // ---- A17, A18: ages ----------------------------------------------------------------------------------------------------------------

  private val utc = ZoneId.of("UTC")
  private val now = 1_800_000_000_000L

  @Test fun anAgeReadsInMinutesHoursAndDaysThenBecomesADate() {
    assertEquals("Just now", Age.label(now - 10_000, now, Locale.ENGLISH, utc))
    assertEquals("5 min. ago", Age.label(now - 5 * Age.MINUTE, now, Locale.ENGLISH, utc))
    assertTrue(Age.label(now - 3 * Age.HOUR, now, Locale.ENGLISH, utc).contains("3"))
    assertEquals("2 days ago", Age.label(now - 2 * Age.DAY, now, Locale.ENGLISH, utc))
    val old = Age.label(now - 40 * Age.DAY, now, Locale.ENGLISH, utc)
    assertTrue(old.any { it.isDigit() } && !old.contains("ago"), "an old file shows its date: $old")
  }

  @Test fun aFileFromTheFutureNeverReadsAsInTheFuture() {
    // The computer's clock is behind its own files: the file is "3 days ahead". It reads as "now", not "in 3 days".
    val future = now + 3 * Age.DAY
    assertEquals(now, ageBasis(future, now))
    assertEquals(Age.label(now, now, Locale.ENGLISH, utc), Age.label(future, now, Locale.ENGLISH, utc))
    assertFalse(Age.label(future, now, Locale.ENGLISH, utc).contains("-"))
  }

  @Test fun aLanguageWithoutAgeWordsShowsADateInsteadOfEnglishWords() {
    Strings.use(Locale.forLanguageTag("lg"))
    if (!Strings.readsInLanguage("age_minutes")) {
      val s = Age.label(now - 5 * Age.MINUTE, now, Locale.forLanguageTag("lg"), utc)
      assertTrue(!s.contains("min") && s.any { it.isDigit() }, "a date and time, not English: $s")
    }
  }

  // ---- A17: the row subtitle ---------------------------------------------------------------------------------------------------------

  @Test fun theSubtitleIsAgeThenFolderAndTheSizeComesOnlyWhenSortedByLargest() {
    assertEquals("2 days ago  ·  Documents/Tax", RowText.subtitle("2 days ago", "Documents/Tax", "1.2 MB", SortOrder.BEST))
    assertEquals("2 days ago  ·  Documents/Tax", RowText.subtitle("2 days ago", "Documents/Tax", "1.2 MB", SortOrder.NEWEST))
    assertEquals("1.2 MB  ·  2 days ago  ·  Documents/Tax", RowText.subtitle("2 days ago", "Documents/Tax", "1.2 MB", SortOrder.LARGEST))
    assertEquals("2 days ago", RowText.subtitle("2 days ago", "", "1.2 MB", SortOrder.NAME), "no folder, no stray separator")
    assertEquals("", RowText.subtitle("", "", "", SortOrder.BEST))
  }

  // ---- H3: stacking --------------------------------------------------------------------------------------------------------------------

  @Test fun rowsStackWhenTheTextIsLargeOrTheListIsNarrow() {
    assertFalse(stackRows(1.0f, 700))
    assertFalse(stackRows(1.15f, STACK_BELOW_DP))
    assertTrue(stackRows(1.3f, 700))
    assertTrue(stackRows(1.0f, STACK_BELOW_DP - 1))
  }

  // ---- A19, A20, E14: empty states -----------------------------------------------------------------------------------------------------

  private val someDate = ActiveDate(DateFilter(DateLabel.Today, DateRange(1, 2)))

  @Test fun eachEmptyStateIsChosenFromWhatWasAsked() {
    assertEquals(EmptyKind.FRESH, emptyKind(ResultsUi(), KindFilter.ALL))
    assertEquals(EmptyKind.FILTERED, emptyKind(ResultsUi(), KindFilter.PHOTOS))
    assertEquals(EmptyKind.IN_DATES, emptyKind(ResultsUi(date = someDate), KindFilter.ALL))
    assertEquals(EmptyKind.IN_DATES, emptyKind(ResultsUi(query = "tax", words = "tax", date = someDate), KindFilter.ALL))
    assertEquals(EmptyKind.NO_MATCH, emptyKind(ResultsUi(query = "tax", words = "tax"), KindFilter.ALL))
    assertEquals(EmptyKind.SIMILAR, emptyKind(ResultsUi(similar = SimilarTo(1, "a.jpg")), KindFilter.PHOTOS))
  }

  // ---- A5: the status line -------------------------------------------------------------------------------------------------------------

  @Test fun theStatusLineFollowsTheProgressiveAnswer() {
    assertNull(SearchStatus.text(ResultsUi()), "the Recent list has none")
    assertEquals(Strings.get("search_status_words"), SearchStatus.text(ResultsUi(query = "tax", complete = false)))
    assertTrue(SearchStatus.text(ResultsUi(query = "tax", complete = false, rows = listOf(row(1), row(2), row(3))))!!.contains("3"))
    assertTrue(SearchStatus.text(ResultsUi(query = "tax", complete = true, rows = listOf(row(1), row(2))))!!.contains("2"))
    assertNull(SearchStatus.text(ResultsUi(query = "tax", complete = true)), "the empty state says it")
  }

  // ---- A12, A23: the shape of the list ------------------------------------------------------------------------------------------------

  @Test fun theListIsGroupedFlatOrTheRecentFeed() {
    assertEquals(ListStyle.RESULTS, listStyleOf(ResultsUi(query = "tax", sort = SortOrder.BEST)))
    assertEquals(ListStyle.SORTED, listStyleOf(ResultsUi(query = "tax", sort = SortOrder.LARGEST)))
    assertEquals(ListStyle.SORTED, listStyleOf(ResultsUi(query = "tax", sort = SortOrder.BEST, date = someDate)))
    assertEquals(ListStyle.RECENT, listStyleOf(ResultsUi(sort = SortOrder.NEWEST)))
    assertEquals(ListStyle.SORTED, listStyleOf(ResultsUi(sort = SortOrder.OLDEST)), "browsing by another sort is a flat list")
    assertEquals(ListStyle.SORTED, listStyleOf(ResultsUi(sort = SortOrder.NEWEST, date = someDate)), "a date turns the feed into a flat list")
    assertEquals(ListStyle.SORTED, listStyleOf(ResultsUi(sort = SortOrder.BEST, similar = SimilarTo(1, "a.jpg"))))
  }

  @Test fun bestMatchIsOnlyOfferedWhereThereIsSomethingToBeBestFor() {
    assertFalse(SortOrder.BEST in sortChoices(searching = false))
    assertEquals(SortOrder.BEST, sortChoices(searching = true).first())
    assertEquals(5, sortChoices(true).size)
  }

  // ---- A22: selection ------------------------------------------------------------------------------------------------------------------

  private val ids = listOf(1L, 2, 3, 4, 5)

  @Test fun aPlainClickChoosesOneAndShiftTakesARangeFromTheAnchor() {
    var s = Picking.click(Picking.State(null), ids, 2, shift = false, toggle = false)
    assertEquals(2L, s.selected); assertTrue(s.picked.isEmpty()); assertFalse(s.several)
    s = Picking.click(s, ids, 4, shift = true, toggle = false)
    assertEquals(setOf(2L, 3, 4), s.picked); assertTrue(s.several)
    s = Picking.click(s, ids, 1, shift = true, toggle = false)
    assertEquals(setOf(1L, 2), s.picked, "the range starts again from the same anchor, either way round")
  }

  @Test fun commandOrControlClickAddsAndTakesAwayOneResult() {
    var s = Picking.click(Picking.State(1), ids, 3, shift = false, toggle = true)
    assertEquals(setOf(1L, 3), s.picked, "the row that had the cursor is the first of the set")
    s = Picking.click(s, ids, 3, shift = false, toggle = true)
    assertEquals(setOf(1L), s.picked)
  }

  @Test fun shiftArrowsExtendAndSelectAllTakesTheWholeList() {
    var s = Picking.click(Picking.State(null), ids, 2, false, false)
    s = Picking.extend(s, ids, 3); s = Picking.extend(s, ids, 4)
    assertEquals(setOf(2L, 3, 4), s.picked)
    s = Picking.extend(s, ids, 3)
    assertEquals(setOf(2L, 3), s.picked, "going back shrinks it")
    assertEquals(ids.toSet(), Picking.all(s, ids).picked)
    assertEquals(s, Picking.all(s, emptyList()))
  }

  @Test fun whenTheListChangesOnlyResultsStillInItStayChosen() {
    val s = Picking.State(2, linkedSetOf(1L, 2, 9), anchor = 9)
    val p = Picking.prune(s, ids)
    assertEquals(setOf(1L, 2), p.picked)
    assertNull(p.anchor)
  }

  // ---- H1: key caps --------------------------------------------------------------------------------------------------------------------

  @Test fun keyCapsAreSymbolsOnAMacAndWordsElsewhereAndAlwaysWordsToAScreenReader() {
    assertEquals("⌘⇧C", Keys.cap("Cmd Shift C", true))
    assertEquals("⌃⌥⏎", Keys.cap("Ctrl Alt Enter", true))
    assertEquals("⌘⏎", Keys.cap("Cmd Enter", true))
    assertEquals("↑ ↓", Keys.cap("Up Down", true))
    assertEquals("Ctrl Shift C", Keys.cap("Ctrl Shift C", false))
    assertEquals("Command Shift C", Keys.spoken("Cmd Shift C"))
    assertEquals("Command Shift C", Keys.spoken("⌘ ⇧ C"))
    assertEquals("Escape", Keys.spoken("Esc"))
  }

  // ---- B3: copy ------------------------------------------------------------------------------------------------------------------------

  private class FakePort(val filesOk: Boolean = true, val textOk: Boolean = true) : ClipboardPort {
    var files: List<File>? = null
    var text: String? = null
    override fun putFiles(files: List<File>): Boolean { if (filesOk) this.files = files; return filesOk }
    override fun putText(text: String): Boolean { if (textOk) this.text = text; return textOk }
  }

  private val dirs = ArrayList<File>()
  @AfterTest fun clean() { Strings.use(Locale.ENGLISH); dirs.forEach { it.deleteRecursively() };Share.facility = Share.forOs(trawl.desktop.Platform.os) }
  private fun real(vararg names: String): List<File> {
    val d = Files.createTempDirectory("trawl-clip").toFile().also { dirs += it }
    return names.map { File(d, it).apply { writeText("x") } }
  }

  @Test fun copyPutsFilesOnTheClipboardAsFiles() {
    val fs = real("a.txt", "b.txt")
    val port = FakePort()
    assertEquals(CopyOutcome.FILES, Clip.copyFiles(fs, port))
    assertEquals(fs, port.files)
    assertNull(port.text)
  }

  @Test fun whenFilesCannotGoOnTheClipboardThePathsDoAndTheCallerIsTold() {
    val fs = real("a.txt", "b.txt")
    val port = FakePort(filesOk = false)
    assertEquals(CopyOutcome.PATHS, Clip.copyFiles(fs, port))
    assertEquals(fs.joinToString("\n") { it.path }, port.text)
  }

  @Test fun aFileThatHasGoneIsCopiedAsItsPathNotPretendedToBeAFile() {
    val fs = real("a.txt")
    fs[0].delete()
    val port = FakePort()
    assertEquals(CopyOutcome.PATHS, Clip.copyFiles(fs, port))
    assertNull(port.files)
  }

  @Test fun copyFailsLoudlyWhenNothingWorksAndNeverThrows() {
    val fs = real("a.txt")
    assertEquals(CopyOutcome.FAILED, Clip.copyFiles(fs, FakePort(filesOk = false, textOk = false)))
    assertEquals(CopyOutcome.FAILED, Clip.copyFiles(emptyList(), FakePort()))
    val boom = object : ClipboardPort {
      override fun putFiles(files: List<File>): Boolean = error("no clipboard")
      override fun putText(text: String): Boolean = error("no clipboard")
    }
    assertEquals(CopyOutcome.FAILED, Clip.copyFiles(fs, boom))
    assertFalse(Clip.copyText("x", boom))
  }

  @Test fun oneCopyTakesAtMostTheCap() {
    val d = Files.createTempDirectory("trawl-clip").toFile().also { dirs += it }
    val many = (1..Clip.LIMIT + 20).map { File(d, "f$it.txt").apply { writeText("x") } }
    val port = FakePort()
    Clip.copyFiles(many, port)
    assertEquals(Clip.LIMIT, port.files!!.size)
  }

  @Test fun theGnomeFormIsTheWordCopyThenOneUriPerLine() {
    val fs = real("a b.txt")
    val s = Clip.gnomeCopiedText(fs)
    assertTrue(s.startsWith("copy\nfile://"))
    assertTrue(s.contains("a%20b.txt"))
  }

  // ---- B4: share -----------------------------------------------------------------------------------------------------------------------

  @Test fun shareIsHiddenWhereThereIsNoFacility() {
    assertNull(Share.forOs(Os.LINUX), "Linux has no system share sheet")
    Share.facility = Share.forOs(Os.LINUX)
    assertFalse(Share.available)
    assertFalse(Share.share(real("a.txt")))
  }

  @Test fun aFacilityMakesShareAvailableAndGetsAtMostTheCapOfFiles() {
    var got: List<File>? = null
    Share.facility = object : ShareFacility { override fun share(files: List<File>): Boolean { got = files; return true } }
    assertTrue(Share.available)
    val d = Files.createTempDirectory("trawl-share").toFile().also { dirs += it }
    assertTrue(Share.share((1..Clip.LIMIT + 5).map { File(d, "f$it") }))
    assertEquals(Clip.LIMIT, got!!.size)
    Share.facility = object : ShareFacility { override fun share(files: List<File>): Boolean = error("sheet crashed") }
    assertFalse(Share.share(listOf(File("x"))), "a crash in the system sheet is a plain failure")
  }

  // ---- strings: keys built at run time, which the literal-key test cannot see ------------------------------------------------------------

  @Test fun everyDateLabelHasItsWordsInEnglish() {
    val labels = listOf(
      DateLabel.Today, DateLabel.Yesterday, DateLabel.ThisWeek, DateLabel.LastWeek, DateLabel.ThisMonth, DateLabel.LastMonth,
      DateLabel.ThisYear, DateLabel.LastYear, DateLabel.Weekend, DateLabel.LastNight,
      DateLabel.Day(LocalDate.of(2026, 3, 5)), DateLabel.Month(YearMonth.of(2026, 3)), DateLabel.Year(2025),
      DateLabel.Custom(LocalDate.of(2026, 3, 5), LocalDate.of(2026, 3, 9)),
    ) + DayPart.entries.flatMap { p -> listOf(true, false).map { DateLabel.Part(p, it) } } +
      DateUnit.entries.flatMap { u -> listOf(1, 2, 7).flatMap { n -> listOf(DateLabel.Past(n, u), DateLabel.Ago(n, u)) } }
    for (l in labels) {
      val s = l.text(Locale.ENGLISH)
      assertTrue(s.isNotBlank() && !s.contains("["), "$l reads as '$s'")
    }
    for (p in DatePreset.entries) assertTrue(p.menuText().isNotBlank() && !p.menuText().contains("["))
  }

  @Test fun theCountedKeysBuiltAtRunTimeHaveOneAndOther() {
    val counted = listOf(
      "age_minutes", "age_hours", "recent_more_documents", "recent_more_photos", "search_status_so_far", "search_status_found",
    ) + listOf("date_past_", "date_ago_").flatMap { p -> listOf("days", "weeks", "months", "years").map { p + it } }
    for (k in counted) assertTrue(Strings.has("${k}_one") && Strings.has("${k}_other"), "$k needs one and other")
    for (k in listOf("age_now", "kind_plural_photos", "peek_see_all", "search_status_words", "date_any_time", "hidden_tile")) assertTrue(Strings.has(k), "$k missing")
  }
}
