package com.craneai.findr.ui.search

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.craneai.findr.R
import com.craneai.findr.core.FileKind
import com.craneai.findr.core.FindrSettings
import com.craneai.findr.core.Forgotten
import com.craneai.findr.core.IndexStatus
import com.craneai.findr.core.IndexedFile
import com.craneai.findr.core.KindFilter
import com.craneai.findr.core.MatchReason
import com.craneai.findr.core.ResultView
import com.craneai.findr.core.SearchEngine
import com.craneai.findr.core.SearchHit
import com.craneai.findr.core.SearchResults
import com.craneai.findr.core.SortOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZonedDateTime

/** Unit tests for Android 1.2.0 parity features: Date Modified/Created sorts, FileSubType, FileTypeVisual, and Grid/List view mode. */
@OptIn(ExperimentalCoroutinesApi::class)
class ParityUiLogicTest {
  private val zone = ZoneId.of("Africa/Kampala")
  private val now = ZonedDateTime.of(2026, 10, 16, 12, 0, 0, 0, zone)
  private val dispatcher = StandardTestDispatcher()

  @Before fun setUp() { Dispatchers.setMain(dispatcher) }
  @After fun tearDown() { Dispatchers.resetMain() }

  private class StubEngine(val files: List<IndexedFile>) : SearchEngine {
    override val status: StateFlow<IndexStatus> = MutableStateFlow(IndexStatus())
    override val settings: StateFlow<FindrSettings> = MutableStateFlow(FindrSettings())
    override suspend fun search(query: String, filter: KindFilter, limit: Int, view: ResultView): SearchResults {
      val matching = files.filter { it.kind in filter.kinds }
      return SearchResults(query, matching.map { SearchHit(it, 1.0, MatchReason.BOTH) }, strongCount = matching.size, tookMs = 1, indexIncomplete = false)
    }
    override suspend fun recent(filter: KindFilter, limit: Int, view: ResultView): List<IndexedFile> =
      files.filter { it.kind in filter.kinds }
    override fun startIndexing() {}
    override fun pauseIndexing() {}
    override fun resumeIndexing() {}
    override suspend fun deleteIndex(): Forgotten = error("unused")
    override suspend fun updateSettings(transform: (FindrSettings) -> FindrSettings) {}
  }

  private fun TestScope.vm(files: List<IndexedFile>) =
    SearchViewModel(StubEngine(files), clock = { now }, weekStart = { DayOfWeek.MONDAY }, feedDispatcher = dispatcher).also { advanceUntilIdle() }

  @Test fun sortChoicesIncludeBothModifiedAndCreatedDates() {
    assertEquals(
      listOf(SortOrder.NEWEST, SortOrder.OLDEST, SortOrder.CREATED_NEWEST, SortOrder.CREATED_OLDEST, SortOrder.LARGEST, SortOrder.SMALLEST, SortOrder.NAME),
      sortChoices(searching = false),
    )
    assertEquals(
      listOf(SortOrder.BEST, SortOrder.NEWEST, SortOrder.OLDEST, SortOrder.CREATED_NEWEST, SortOrder.CREATED_OLDEST, SortOrder.LARGEST, SortOrder.SMALLEST, SortOrder.NAME),
      sortChoices(searching = true),
    )
    assertEquals(R.string.sort_created_newest, SortOrder.CREATED_NEWEST.labelRes())
    assertEquals(R.string.sort_created_oldest, SortOrder.CREATED_OLDEST.labelRes())
    assertEquals(R.string.sort_largest, SortOrder.LARGEST.labelRes())
    assertEquals(R.string.sort_smallest, SortOrder.SMALLEST.labelRes())
    assertEquals(R.string.sort_chip_best, SortOrder.BEST.chipLabelRes())
    assertEquals(R.string.sort_chip_newest, SortOrder.NEWEST.chipLabelRes())
    assertEquals(R.string.sort_chip_oldest, SortOrder.OLDEST.chipLabelRes())
    assertEquals(R.string.sort_chip_created_newest, SortOrder.CREATED_NEWEST.chipLabelRes())
    assertEquals(R.string.sort_chip_created_oldest, SortOrder.CREATED_OLDEST.chipLabelRes())
    assertEquals(R.string.sort_chip_largest, SortOrder.LARGEST.chipLabelRes())
    assertEquals(R.string.sort_chip_smallest, SortOrder.SMALLEST.chipLabelRes())
    assertEquals(R.string.sort_chip_name, SortOrder.NAME.chipLabelRes())
  }

  @Test fun fileSubTypeMatchesExtensionsAndMapsPerKindFilter() {
    assertTrue(FileSubType.forFilter(KindFilter.ALL).isEmpty())
    assertEquals(
      listOf(FileSubType.PDF, FileSubType.WORD, FileSubType.SPREADSHEET, FileSubType.PRESENTATION, FileSubType.TEXT_NOTES, FileSubType.CODE_DATA),
      FileSubType.forFilter(KindFilter.DOCUMENTS),
    )
    assertTrue(FileSubType.PDF.matches("receipt.PDF"))
    assertFalse(FileSubType.PDF.matches("receipt.docx"))
    assertTrue(FileSubType.WORD.matches("proposal.docx"))
    assertTrue(FileSubType.SPREADSHEET.matches("budget.xlsx"))
    assertTrue(FileSubType.SPREADSHEET.matches("ledger.csv"))
    assertTrue(FileSubType.PRESENTATION.matches("deck.pptx"))
    assertTrue(FileSubType.CODE_DATA.matches("config.json"))
    assertTrue(FileSubType.PNG.matches("shot.png"))
    assertTrue(FileSubType.MP4.matches("clip.mp4"))
    assertTrue(FileSubType.M4A.matches("voice.m4a"))
    assertNull(FileSubType.extensionOf("README"))
    for (st in FileSubType.entries) {
      assertTrue("${st.name} menuLabelRes must be non-zero", st.menuLabelRes != 0)
      assertTrue("${st.name} chipLabelRes must be non-zero", st.chipLabelRes != 0)
    }
  }

  @Test fun fileTypeVisualMapsColorCodedTagsAndIcons() {
    val pdf = FileTypeVisual.of("invoice.pdf", FileKind.PDF)
    val docx = FileTypeVisual.of("notes.docx", FileKind.DOCUMENT)
    val xlsx = FileTypeVisual.of("budget.xlsx", FileKind.DOCUMENT)
    val pptx = FileTypeVisual.of("slides.pptx", FileKind.DOCUMENT)
    val md = FileTypeVisual.of("README.markdown", FileKind.DOCUMENT)
    val json = FileTypeVisual.of("data.json", FileKind.DOCUMENT)
    val vid = FileTypeVisual.of("movie.mp4", FileKind.VIDEO)
    val aud = FileTypeVisual.of("note.wav", FileKind.AUDIO)
    val photo = FileTypeVisual.of("IMG_001.jpg", FileKind.IMAGE)

    assertNotNull(pdf); assertEquals("PDF", pdf!!.tag)
    assertNotNull(docx); assertEquals("DOCX", docx!!.tag)
    assertNotNull(xlsx); assertEquals("XLSX", xlsx!!.tag)
    assertNotNull(pptx); assertEquals("PPTX", pptx!!.tag)
    assertNotNull(md); assertEquals("MD", md!!.tag)
    assertNotNull(json); assertEquals("JSON", json!!.tag)
    assertNotNull(vid); assertEquals("MP4", vid!!.tag)
    assertNotNull(aud); assertEquals("WAV", aud!!.tag)
    assertNull(photo)
    // Distinct accent colors for PDF, Word, Excel, PowerPoint
    assertEquals(4, setOf(pdf.lightAccent, docx.lightAccent, xlsx.lightAccent, pptx.lightAccent).size)

    // WCAG AA >= 4.5:1 contrast for all 8 Light accents on 14% tint over Sunlight paper (#FFFBF2) and paper2 (#F6F0E2)
    val paper = Color(0xFFFFFBF2)
    val paper2 = Color(0xFFF6F0E2)
    fun composite(fg: Color, alpha: Float, bg: Color) = Color(
      red = fg.red * alpha + bg.red * (1f - alpha),
      green = fg.green * alpha + bg.green * (1f - alpha),
      blue = fg.blue * alpha + bg.blue * (1f - alpha),
      alpha = 1f,
    )
    fun contrast(fg: Color, bg: Color): Double {
      val l1 = fg.luminance().toDouble()
      val l2 = bg.luminance().toDouble()
      return (maxOf(l1, l2) + 0.05) / (minOf(l1, l2) + 0.05)
    }
    listOf(pdf, docx, xlsx, pptx, md, json, vid, aud).forEach { style ->
      val onPaper = contrast(style.lightAccent, composite(style.lightAccent, 0.14f, paper))
      val onPaper2 = contrast(style.lightAccent, composite(style.lightAccent, 0.14f, paper2))
      assertTrue("${style.tag} contrast on paper ($onPaper) must be >= 4.5", onPaper >= 4.5)
      assertTrue("${style.tag} contrast on paper2 ($onPaper2) must be >= 4.5", onPaper2 >= 4.5)
    }
  }

  @Test fun displayDateMillisUsesModifiedUnlessCreatedSortSelected() {
    val file = IndexedFile(1, "fake://1", "Docs/a.pdf", "a.pdf", FileKind.PDF, null, 100, modifiedMillis = 2_000L, dateMillis = 1_000L)
    assertEquals(1_000L, displayDateMillis(file, SortOrder.CREATED_NEWEST))
    assertEquals(1_000L, displayDateMillis(file, SortOrder.CREATED_OLDEST))
    for (sort in listOf(SortOrder.BEST, SortOrder.NEWEST, SortOrder.OLDEST, SortOrder.LARGEST, SortOrder.SMALLEST, SortOrder.NAME)) {
      assertEquals("sort=$sort", 2_000L, displayDateMillis(file, sort))
    }
    val noModified = file.copy(modifiedMillis = 0L)
    assertEquals(1_000L, displayDateMillis(noModified, SortOrder.BEST))
  }

  @Test fun viewModelFiltersBySubTypeAndClearsWhenKindChanges() = runTest(dispatcher) {
    val files = listOf(
      IndexedFile(1, "fake://1", "Docs/a.pdf", "a.pdf", FileKind.PDF, null, 100, 100),
      IndexedFile(2, "fake://2", "Docs/b.docx", "b.docx", FileKind.DOCUMENT, null, 200, 200),
      IndexedFile(3, "fake://3", "Docs/c.xlsx", "c.xlsx", FileKind.DOCUMENT, null, 300, 300),
      IndexedFile(4, "fake://4", "DCIM/d.png", "d.png", FileKind.IMAGE, null, 400, 400),
    )
    val vm = vm(files)
    vm.setFilter(KindFilter.DOCUMENTS); advanceUntilIdle()
    assertEquals(3, vm.state.value.feed.shownFiles().size)

    vm.setSubType(FileSubType.SPREADSHEET); advanceUntilIdle()
    assertEquals(FileSubType.SPREADSHEET, vm.state.value.subType)
    assertEquals(listOf("c.xlsx"), vm.state.value.feed.shownFiles().map { it.name })

    // Switching to Photos clears the Documents-only subType automatically
    vm.setFilter(KindFilter.PHOTOS); advanceUntilIdle()
    assertNull(vm.state.value.subType)
    assertEquals(listOf("d.png"), vm.state.value.feed.shownFiles().map { it.name })
  }

  @Test fun listViewModeTurnsPhotoCellsIntoDenseRows() = runTest(dispatcher) {
    val files = listOf(
      IndexedFile(1, "fake://1", "DCIM/p1.jpg", "p1.jpg", FileKind.IMAGE, null, 1_500_000, 200),
      IndexedFile(2, "fake://2", "DCIM/p2.jpg", "p2.jpg", FileKind.IMAGE, null, 2_500_000, 100),
    )
    val vm = vm(files)
    assertEquals(ResultViewMode.GRID, vm.state.value.viewMode)
    assertTrue(vm.state.value.feed.filterIsInstance<FeedItem.Cell>().size == 2)

    vm.setViewMode(ResultViewMode.LIST); advanceUntilIdle()
    assertEquals(ResultViewMode.LIST, vm.state.value.viewMode)
    assertEquals(0, vm.state.value.feed.filterIsInstance<FeedItem.Cell>().size)
    assertEquals(2, vm.state.value.feed.filterIsInstance<FeedItem.Row>().size)

    // Back to GRID restores photo cells
    vm.setViewMode(ResultViewMode.GRID); advanceUntilIdle()
    assertEquals(2, vm.state.value.feed.filterIsInstance<FeedItem.Cell>().size)
  }

  @Test fun momentPlayerPreRollSubtracts1500msAndClampsToRange() {
    assertEquals(40_500L, preRollStartMs(42_000L))
    assertEquals(0L, preRollStartMs(1_500L))
    assertEquals(0L, preRollStartMs(400L))
    assertEquals(0L, preRollStartMs(0L))
    assertEquals(40_500L, clampedSeekMs(40_500L, 120_000L))
    assertEquals(120_000L, clampedSeekMs(150_000L, 120_000L))
    assertEquals(0L, clampedSeekMs(-5_000L, 120_000L))
    assertEquals(12_000L, clampedSeekMs(12_000L, 0L))
  }

  @Test fun structuredPreviewFormatsJsonTablesAndCodeCleanly() {
    assertEquals(StructuredPreview.PreviewKind.JSON, StructuredPreview.kindOf("config.json"))
    assertEquals(StructuredPreview.PreviewKind.TABLE, StructuredPreview.kindOf("budget.csv"))
    assertEquals(StructuredPreview.PreviewKind.TABLE, StructuredPreview.kindOf("ledger.xlsx"))
    assertEquals(StructuredPreview.PreviewKind.CODE, StructuredPreview.kindOf("Main.kt"))
    assertNull(StructuredPreview.kindOf("photo.jpg"))
    assertTrue(StructuredPreview.isDirectPreviewable("config.json"))
    assertTrue(StructuredPreview.isDirectPreviewable("budget.csv"))
    assertFalse(StructuredPreview.isDirectPreviewable("ledger.xlsx"))
    assertTrue(StructuredPreview.isSpreadsheetBinary("ledger.xlsx"))

    // JSON pretty-printer + key/string/number/boolean tokenization
    val pretty = StructuredPreview.prettyJson("""{"name":"Trawl","version":1.2,"offline":true,"tags":[]}""")
    assertTrue(pretty.contains("  \"name\": \"Trawl\""))
    assertTrue(pretty.contains("\"tags\": []"))
    val jsonSpans = StructuredPreview.tokenizeJson(pretty)
    assertTrue(jsonSpans.any { it.kind == StructuredPreview.TokenKind.KEY && pretty.substring(it.start, it.end) == "\"name\"" })
    assertTrue(jsonSpans.any { it.kind == StructuredPreview.TokenKind.STRING && pretty.substring(it.start, it.end) == "\"Trawl\"" })
    assertTrue(jsonSpans.any { it.kind == StructuredPreview.TokenKind.NUMBER && pretty.substring(it.start, it.end) == "1.2" })
    assertTrue(jsonSpans.any { it.kind == StructuredPreview.TokenKind.BOOLEAN_NULL && pretty.substring(it.start, it.end) == "true" })

    // RFC-4180 CSV with quoted commas and escaped quotes
    val csv = "Item,Note,Amount\n\"School, Term 2\",\"Paid \"\"full\"\"\",450000\nBooks,Math,35000"
    val table = StructuredPreview.parseDelimited(csv)
    assertEquals(listOf("Item", "Note", "Amount"), table.headers)
    assertEquals(2, table.rows.size)
    assertEquals(listOf("School, Term 2", "Paid \"full\"", "450000"), table.rows[0])
    assertEquals(listOf("Books", "Math", "35000"), table.rows[1])

    // Spreadsheet tab-separated extraction
    val xlsx = StructuredPreview.parseSpreadsheetText("Month\tTotal\nJan\t120\nFeb\t150")
    assertEquals(listOf("Month", "Total"), xlsx.headers)
    assertEquals(2, xlsx.rows.size)

    // Code highlighter (comments, strings, keywords, numbers)
    val kt = "fun score(x: Int): Int {\n  // fast path\n  return x + 42\n}"
    val codeSpans = StructuredPreview.tokenizeCode(kt, "kt")
    assertTrue(codeSpans.any { it.kind == StructuredPreview.TokenKind.KEYWORD && kt.substring(it.start, it.end) == "fun" })
    assertTrue(codeSpans.any { it.kind == StructuredPreview.TokenKind.COMMENT && kt.substring(it.start, it.end) == "// fast path" })
    assertTrue(codeSpans.any { it.kind == StructuredPreview.TokenKind.NUMBER && kt.substring(it.start, it.end) == "42" })
  }

  @Test fun prosePreviewCoversAllDocumentsWithHeadingsAndQueryMatchesOnly() {
    for (name in listOf("PROMPTS.md", "notes.markdown", "readme.txt", "audit.log", "draft.rst", "plan.org", "mail.eml")) {
      assertEquals(name, StructuredPreview.PreviewKind.PROSE, StructuredPreview.kindOf(name))
      assertTrue(name, StructuredPreview.isDirectPreviewable(name))
      assertFalse(name, StructuredPreview.isDocumentExtracted(name))
    }
    for (name in listOf("report.pdf", "proposal.docx", "letter.rtf", "slides.pptx", "memo.odt")) {
      assertEquals(name, StructuredPreview.PreviewKind.PROSE, StructuredPreview.kindOf(name))
      assertFalse(name, StructuredPreview.isDirectPreviewable(name))
      assertTrue(name, StructuredPreview.isDocumentExtracted(name))
    }
    assertTrue(StructuredPreview.isMarkdown("PROMPTS.md"))
    assertFalse(StructuredPreview.isMarkdown("notes.txt"))

    // Markdown: # headings + query match markers ONLY — zero purple code-keyword tokens on prose words (and/with/or/in/is/from)
    val md = "# Field Notes\nWe met with the team and reviewed budget or schedule in Kampala.\n## Next steps\nFollow up from Monday."
    val mdSpans = StructuredPreview.tokenizeProse(md, markdown = true, query = "budget Kampala")
    assertTrue(mdSpans.none { it.kind == StructuredPreview.TokenKind.KEYWORD })
    assertTrue(mdSpans.none { it.kind == StructuredPreview.TokenKind.STRING || it.kind == StructuredPreview.TokenKind.NUMBER })
    assertEquals(2, mdSpans.count { it.kind == StructuredPreview.TokenKind.HEADING })
    assertEquals(listOf("budget", "Kampala"), mdSpans.filter { it.kind == StructuredPreview.TokenKind.MATCH }.map { md.substring(it.start, it.end) })

    // Non-Markdown prose (TXT/PDF/DOCX): never highlights # lines as headings, only query matches
    val txt = "# not a markdown heading\nInvoice for school fees and books with receipt."
    val txtSpans = StructuredPreview.tokenizeProse(txt, markdown = false, query = "school receipt")
    assertTrue(txtSpans.none { it.kind == StructuredPreview.TokenKind.HEADING || it.kind == StructuredPreview.TokenKind.KEYWORD })
    assertEquals(listOf("school", "receipt"), txtSpans.filter { it.kind == StructuredPreview.TokenKind.MATCH }.map { txt.substring(it.start, it.end) })
  }
}
