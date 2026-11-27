package trawl.desktop.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StructuredPreviewTest {
  @Test fun structuredKindClassifiesJsonTablesCodeAndProse() {
    assertEquals(StructuredKind.JSON, StructuredKind.of("config.json"))
    assertEquals(StructuredKind.JSON, StructuredKind.of("events.jsonl"))
    assertEquals(StructuredKind.TABLE, StructuredKind.of("budget.csv"))
    assertEquals(StructuredKind.TABLE, StructuredKind.of("export.tsv"))
    assertEquals(StructuredKind.TABLE, StructuredKind.of("Q4_Finance.xlsx"))
    assertEquals(StructuredKind.TABLE, StructuredKind.of("legacy.xls"))
    assertEquals(StructuredKind.CODE, StructuredKind.of("Main.kt"))
    assertEquals(StructuredKind.CODE, StructuredKind.of("schema.sql"))
    assertEquals(StructuredKind.CODE, StructuredKind.of("deploy.yaml"))

    // All prose/document formats (including Markdown) use soft-wrapped PROSE, never CODE
    for (name in listOf("README.md", "guide.markdown", "notes.txt", "memo.rtf", "brief.docx", "report.pdf", "letter.odt", "slides.pptx", "thread.eml", "server.log", "unknown.xyz")) {
      assertEquals(StructuredKind.PROSE, StructuredKind.of(name), "$name must classify as PROSE")
    }

    // Horizontal scroll is strictly limited to JSON, TABLE, and CODE
    assertTrue(StructuredKind.JSON.usesHorizontalScroll)
    assertTrue(StructuredKind.TABLE.usesHorizontalScroll)
    assertTrue(StructuredKind.CODE.usesHorizontalScroll)
    assertFalse(StructuredKind.PROSE.usesHorizontalScroll)

    assertTrue(StructuredKind.isMarkdown("README.md"))
    assertTrue(StructuredKind.isMarkdown("notes.markdown"))
    assertFalse(StructuredKind.isMarkdown("notes.txt"))
    assertFalse(StructuredKind.isMarkdown("Main.kt"))

    assertTrue(StructuredKind.isPlainDiskText("README.md"))
    assertTrue(StructuredKind.isPlainDiskText("notes.txt"))
    assertFalse(StructuredKind.isPlainDiskText("report.pdf"))
    assertFalse(StructuredKind.isPlainDiskText("brief.docx"))
  }

  @Test fun proseHighlighterStylesMarkdownHeadingsAndQueryOnlyWithZeroCodeKeywords() {
    val md = """
      # Release Notes
      ## What Changed
      This update works with local files and keeps data private or offline.
      You don't need to import from anywhere; it will return true results in seconds.
      Visit https://example.com #not-a-heading
    """.trimIndent()
    val headingColor = Color(0xFF111111)
    val styled = ProseHighlighter.highlight(
      raw = md,
      isMarkdown = true,
      headingColor = headingColor,
      query = "offline",
      markerBg = Color.Yellow,
      markerFg = Color.Black,
    )
    assertEquals(md, styled.text)

    // Headings are bolded
    val h1Start = md.indexOf("# Release Notes")
    val h1End = h1Start + "# Release Notes".length
    assertTrue(styled.spanStyles.any { it.start == h1Start && it.end == h1End && it.item.fontWeight == FontWeight.Bold })
    val h2Start = md.indexOf("## What Changed")
    val h2End = h2Start + "## What Changed".length
    assertTrue(styled.spanStyles.any { it.start == h2Start && it.end == h2End && it.item.fontWeight == FontWeight.Bold })

    // Query term is highlighted
    val qStart = md.indexOf("offline")
    assertTrue(styled.spanStyles.any { it.start == qStart && it.end == qStart + "offline".length && it.item.background == Color.Yellow })

    // Zero spans on prose words that happen to be code keywords, literals, apostrophes, URLs, or #hashtags
    val forbiddenWords = listOf("with", "and", "data", "or", "don't", "import", "from", "return", "true", " in ", "https://example.com", "#not-a-heading")
    for (w in forbiddenWords) {
      val s = md.indexOf(w)
      assertTrue(s >= 0, "word missing: $w")
      val e = s + w.length
      val overlapping = styled.spanStyles.filter { it.start < e && it.end > s }
      assertTrue(overlapping.isEmpty(), "Markdown prose word '${w.trim()}' must have ZERO syntax spans, got: $overlapping")
    }

    // Plain prose (.txt/.docx/.pdf/.rtf/.eml): even lines starting with "# " are NOT styled as Markdown headings
    val plain = "# 1 Priority\nReview contract with legal and finance before Friday."
    val plainStyled = ProseHighlighter.highlight(plain, isMarkdown = false, headingColor = headingColor, query = "contract", markerBg = Color.Yellow, markerFg = Color.Black)
    assertEquals(1, plainStyled.spanStyles.size, "plain prose should only have the single query marker span")
    assertEquals(Color.Yellow, plainStyled.spanStyles.single().item.background)

    // Safety net: even a direct CodeHighlighter.highlight(..., ext = "md") call must never emit SyntaxPalette code colors
    val directMd = CodeHighlighter.highlight(md, "md", SyntaxPalette.LIGHT)
    val paletteColors = setOf(
      SyntaxPalette.LIGHT.key, SyntaxPalette.LIGHT.string, SyntaxPalette.LIGHT.number,
      SyntaxPalette.LIGHT.literal, SyntaxPalette.LIGHT.comment, SyntaxPalette.LIGHT.tag,
    )
    assertTrue(directMd.spanStyles.none { it.item.color in paletteColors }, "CodeHighlighter on .md must not apply code syntax colors")
  }

  @Test fun jsonFormatterPrettyPrintsAndToleratesTruncatedInput() {
    val compact = """{"name":"Trawl","version":1.1,"offline":true,"empty":{},"tags":["fast","local"],"msg":"a, b: {c}"}"""
    val pretty = JsonFormatter.prettyPrint(compact)
    assertTrue(pretty.contains("{\n  \"name\": \"Trawl\",\n"), pretty)
    assertTrue(pretty.contains("\"empty\": {}"), pretty)
    assertTrue(pretty.contains("\"msg\": \"a, b: {c}\""), "strings with punctuation must stay intact:\n$pretty")
    assertTrue(pretty.contains("[\n    \"fast\",\n    \"local\"\n  ]"), pretty)

    // Partial/truncated preview never throws
    val partial = JsonFormatter.prettyPrint("""{"a":[1,2,{"b":"unterminated""")
    assertTrue(partial.contains("\"a\": ["), partial)
  }

  @Test fun jsonHighlighterStylesKeysStringsNumbersLiteralsAndQuery() {
    val pretty = JsonFormatter.prettyPrint("""{"active":true,"count":42,"city":"Kampala","note":null}""")
    val styled = JsonHighlighter.highlight(pretty, SyntaxPalette.LIGHT, query = "Kampala", markerBg = Color.Yellow, markerFg = Color.Black)
    assertEquals(pretty, styled.text)
    val colors = styled.spanStyles.map { it.item.color }.toSet()
    assertTrue(SyntaxPalette.LIGHT.key in colors)
    assertTrue(SyntaxPalette.LIGHT.string in colors)
    assertTrue(SyntaxPalette.LIGHT.number in colors)
    assertTrue(SyntaxPalette.LIGHT.literal in colors)
    assertTrue(styled.spanStyles.any { it.item.background == Color.Yellow })
  }

  @Test fun tableParserHandlesRfc4180CsvTsvAndPipeDelimitedExcel() {
    val csv = """
      Name,Role,City,Salary
      "Okello, Grace","Lead ""Data"" Analyst",Kampala,4500
      "Amina Hassan",Engineer,Nairobi,5200
    """.trimIndent()
    val parsedCsv = TableParser.parse(csv, "team.csv")
    assertNotNull(parsedCsv)
    assertNull(parsedCsv.sheetTitle)
    assertEquals(listOf("Name", "Role", "City", "Salary"), parsedCsv.headers)
    assertEquals(2, parsedCsv.rows.size)
    assertEquals(listOf("Okello, Grace", "Lead \"Data\" Analyst", "Kampala", "4500"), parsedCsv.rows[0])
    assertFalse(parsedCsv.truncatedRows)

    val tsv = "Item\tQty\tTotal\nCoffee\t3\t18000\nTea\t2\t8000"
    val parsedTsv = TableParser.parse(tsv, "order.tsv")
    assertNotNull(parsedTsv)
    assertEquals(listOf("Item", "Qty", "Total"), parsedTsv.headers)
    assertEquals(listOf("Coffee", "3", "18000"), parsedTsv.rows[0])

    val xlsx = "Q1 Budget\nDepartment | Planned | Actual\nEngineering | 120000 | 115000\nDesign | 45000 | 44000"
    val parsedXlsx = TableParser.parse(xlsx, "budget.xlsx")
    assertNotNull(parsedXlsx)
    assertEquals("Q1 Budget", parsedXlsx.sheetTitle)
    assertEquals(listOf("Department", "Planned", "Actual"), parsedXlsx.headers)
    assertEquals(2, parsedXlsx.rows.size)
    assertEquals(listOf("Engineering", "120000", "115000"), parsedXlsx.rows[0])
  }

  @Test fun codeHighlighterStylesKotlinSqlAndYamlTokens() {
    val kt = """
      // local search engine
      @Composable
      fun greet(count: Int = 3) = "hello"
    """.trimIndent()
    val styled = CodeHighlighter.highlight(kt, "kt", SyntaxPalette.DARK, query = "greet", markerBg = Color.Yellow, markerFg = Color.Black)
    val colors = styled.spanStyles.map { it.item.color }.toSet()
    assertTrue(SyntaxPalette.DARK.comment in colors)
    assertTrue(SyntaxPalette.DARK.tag in colors)
    assertTrue(SyntaxPalette.DARK.key in colors)
    assertTrue(SyntaxPalette.DARK.number in colors)
    assertTrue(SyntaxPalette.DARK.string in colors)
    assertTrue(styled.spanStyles.any { it.item.background == Color.Yellow })

    val sql = "SELECT id, name FROM files WHERE size > 100 -- filter"
    val sqlStyled = CodeHighlighter.highlight(sql, "sql", SyntaxPalette.LIGHT)
    assertTrue(sqlStyled.spanStyles.any { it.item.color == SyntaxPalette.LIGHT.key })
    assertTrue(sqlStyled.spanStyles.any { it.item.color == SyntaxPalette.LIGHT.comment })
  }

  @Test fun readDiskPreviewTextStripsBomCapsLengthAndRejectsBinary() {
    val dir = Files.createTempDirectory("trawl-struct-prev").toFile()
    try {
      val txt = dir.resolve("sample.json").apply { writeText("\uFEFF{\"ok\":true}") }
      assertEquals("{\"ok\":true}", readDiskPreviewText(txt))

      val bin = dir.resolve("fake.json").apply { writeBytes(byteArrayOf(0x00, 0x01, 0x02, 0x03)) }
      assertNull(readDiskPreviewText(bin))
    } finally {
      dir.deleteRecursively()
    }
  }
}
