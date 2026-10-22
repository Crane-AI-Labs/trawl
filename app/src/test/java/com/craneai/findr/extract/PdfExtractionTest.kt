package com.craneai.findr.extract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [collectPdfPages] is the bounded loop [DocumentTextExtractor.pdf] drives with the real pdfbox stripper. These
 * tests stand in for the 13.5 MB clinical-guidelines PDF that ran pdfbox out of heap on a 192 MB phone (the research notes
 * §4): a synthetic many-page "PDF" (just a page-number -> text function) and a fake [HeapHeadroom] that runs low
 * partway through, in place of an actual low-memory device or a real giant file.
 */
class PdfExtractionTest {
  // PageCollector.add trims each page, so the expectation below is pre-trimmed too (no trailing ", ").
  private fun page(n: Int) = "page $n text, ".repeat(10).trim()

  /** Reports [free] bytes free until [page] pages have been asked for, then drops under the margin. */
  private fun headroomLowAfter(page: Int, free: Long = 100L * 1024 * 1024): HeapHeadroom {
    var asked = 0
    return HeapHeadroom { if (asked++ < page) free else 0L }
  }

  @Test fun `plenty of headroom reads every page, same as before the fix`() {
    val plenty = HeapHeadroom { 100L * 1024 * 1024 }
    var calls = 0
    val result = collectPdfPages(50, maxChars = 1_000_000, heapHeadroom = plenty) { n -> calls++; page(n) }
    assertEquals(50, calls)
    assertEquals(50, result.pages.size)
    assertFalse(result.truncated)
    assertEquals(page(1), result.pages[0])
    assertEquals(page(50), result.pages[49])
  }

  @Test fun `low heap stops before the risky page, keeping the pages read before it`() {
    var calls = 0
    // A 400-page "PDF": headroom runs low right after page 120, as the clinical-guidelines PDF's did partway through.
    val result = collectPdfPages(400, maxChars = 1_000_000, heapHeadroom = headroomLowAfter(page = 120)) { n ->
      calls++
      page(n)
    }
    assertEquals("page 121 (and everything after it) must never be attempted once the heap is low", 120, calls)
    assertEquals(120, result.pages.size)
    assertEquals(page(1), result.pages[0])
    assertEquals(page(120), result.pages[119])
    assertTrue("stopping early for memory must still mark the result truncated", result.truncated)
  }

  @Test fun `a page that itself runs out of memory stops immediately and keeps what came before it`() {
    var calls = 0
    val result = collectPdfPages(400, maxChars = 1_000_000, heapHeadroom = HeapHeadroom { 100L * 1024 * 1024 }) { n ->
      calls++
      if (n == 75) null else page(n)   // page 75 is where the real stripper would have thrown OutOfMemoryError
    }
    assertEquals(75, calls)
    assertEquals(74, result.pages.size)
    assertEquals(page(74), result.pages[73])
    assertTrue(result.truncated)
  }

  @Test fun `the char budget still cuts off a page and marks truncated, unaffected by the heap check`() {
    val plenty = HeapHeadroom { 100L * 1024 * 1024 }
    val result = collectPdfPages(10, maxChars = 15, heapHeadroom = plenty) { "0123456789" }
    assertTrue(result.truncated)
    assertTrue(result.pages.joinToString("").length <= 15)
  }

  @Test fun `the heap margin itself is what gates a page, not an arbitrary smaller number`() {
    val justAbove = HeapHeadroom { ExtractLimits.PDF_PAGE_HEAP_MARGIN_BYTES + 1 }
    val justBelow = HeapHeadroom { ExtractLimits.PDF_PAGE_HEAP_MARGIN_BYTES - 1 }
    assertEquals(1, collectPdfPages(1, 1_000, justAbove) { page(it) }.pages.size)
    assertTrue(collectPdfPages(1, 1_000, justBelow) { page(it) }.pages.isEmpty())
  }

  @Test fun `the page margin shrinks with a small heap and never grows past the full margin`() {
    assertEquals(ExtractLimits.PDF_PAGE_HEAP_MARGIN_BYTES, pdfPageHeapMargin(512L * 1024 * 1024))
    assertEquals(16L * 1024 * 1024, pdfPageHeapMargin(128L * 1024 * 1024))
    assertTrue(pdfPageHeapMargin(256L * 1024 * 1024) <= ExtractLimits.PDF_PAGE_HEAP_MARGIN_BYTES)
  }

  @Test fun `an explicit margin gates the page`() {
    val h = HeapHeadroom { 10L * 1024 * 1024 }
    assertEquals(1, collectPdfPages(1, 1_000, h, marginBytes = 8L * 1024 * 1024) { page(it) }.pages.size)
    assertTrue(collectPdfPages(1, 1_000, h, marginBytes = 12L * 1024 * 1024) { page(it) }.pages.isEmpty())
  }
}
