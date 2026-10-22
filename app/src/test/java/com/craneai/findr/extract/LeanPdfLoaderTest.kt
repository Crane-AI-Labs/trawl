package com.craneai.findr.extract

import com.tom_roush.pdfbox.cos.COSName
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [LeanPdfLoader] opens a PDF without the catalog entries text extraction never reads (the structure tree that cost 259 MB
 * on the 1,161-page clinical guidelines), and [HeapFuse] stops a load that would take too much of the heap.
 */
class LeanPdfLoaderTest {
  @get:Rule val tmp = TemporaryFolder()

  /** A one-page PDF with a real cross-reference table. [extraRoot] is added to the catalog; objects 7.. are [extraObjects]. */
  private fun pdf(extraRoot: String = "", extraObjects: List<String> = emptyList()): File {
    val bodies = mutableListOf(
      "<< /Type /Catalog /Pages 2 0 R /Metadata 5 0 R $extraRoot >>",
      "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
      "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] /Contents 4 0 R >>",
      "<< /Length 0 >>\nstream\n\nendstream",
      "<< /Length 0 >>\nstream\n\nendstream",
      "<< /Title (Findr test) >>",
    ) + extraObjects
    val out = ByteArrayOutputStream()
    out.write("%PDF-1.4\n".toByteArray())
    val offsets = ArrayList<Int>()
    bodies.forEachIndexed { i, b ->
      offsets += out.size()
      out.write("${i + 1} 0 obj\n$b\nendobj\n".toByteArray())
    }
    val xref = out.size()
    out.write("xref\n0 ${bodies.size + 1}\n0000000000 65535 f \n".toByteArray())
    offsets.forEach { out.write(String.format("%010d 00000 n \n", it).toByteArray()) }
    out.write("trailer\n<< /Size ${bodies.size + 1} /Root 1 0 R /Info 6 0 R >>\nstartxref\n$xref\n%%EOF\n".toByteArray())
    return tmp.newFile().also { it.writeBytes(out.toByteArray()) }
  }

  private val roomy = HeapFuse(Long.MAX_VALUE, { 0L }, {})

  @Test fun `a plain PDF opens with its pages and info`() {
    LeanPdfLoader.load(pdf(), roomy).use { d ->
      assertEquals(1, d.numberOfPages)
      assertEquals("Findr test", d.documentInformation.title)
    }
  }

  @Test fun `the structure tree is never loaded but the page tree still is`() {
    val f = pdf(extraRoot = "/StructTreeRoot 7 0 R", extraObjects = listOf("<< /Type /StructTreeRoot /K [ 8 0 R ] >>", "<< /Type /StructElem /S /P >>"))
    LeanPdfLoader.load(f, roomy).use { d ->
      assertNull(d.documentCatalog.cosObject.getDictionaryObject(COSName.STRUCT_TREE_ROOT))
      assertNotNull(d.documentCatalog.cosObject.getDictionaryObject(COSName.PAGES))
      assertEquals(1, d.numberOfPages)
    }
  }

  @Test fun `a file that is not a PDF is an IOException, not a crash`() {
    val f = tmp.newFile().also { it.writeText("hello, this is plain text") }
    try {
      LeanPdfLoader.load(f, roomy).close()
      fail("expected an IOException")
    } catch (e: java.io.IOException) {
      // skipped, as intended
    }
  }

  @Test fun `a load over the heap budget stops with the fuse`() {
    val tight = HeapFuse(1L, { 10L * 1024 * 1024 }, {})
    try {
      LeanPdfLoader.load(pdf(), tight).close()
      fail("expected the fuse to blow")
    } catch (e: HeapFuseException) {
      assertTrue(e.message!!.contains("budget"))
    }
  }

  @Test fun `garbage the collector can free does not blow the fuse`() {
    var collected = false
    val fuse = HeapFuse(100L, { if (collected) 50L else 500L }, { collected = true })
    fuse.check()
    assertTrue(collected)
  }

  @Test fun `live data over budget blows the fuse after one collection`() {
    var collections = 0
    val fuse = HeapFuse(100L, { 500L }, { collections++ })
    try {
      fuse.check()
      fail("expected HeapFuseException")
    } catch (e: HeapFuseException) {
      assertEquals(1, collections)
    }
  }

  @Test fun `under budget never collects`() {
    var collections = 0
    HeapFuse(100L, { 99L }, { collections++ }).check()
    assertEquals(0, collections)
  }
}
