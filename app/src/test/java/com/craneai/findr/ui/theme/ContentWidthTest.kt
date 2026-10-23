package com.craneai.findr.ui.theme

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Wide windows keep a reading width; nothing at or below the cap moves (RC QA: a 870 dp row on the 1007 dp tablet on its side). */
class ContentWidthTest {
  @Test fun `phones and portrait tablets get exactly the base padding`() {
    for (w in listOf(320, 360, 411, 600, 601, 720, 744)) {
      assertEquals("width $w", 16.dp, ContentWidth.sideInset(w, 16.dp))
      assertEquals("width $w", 12.dp, ContentWidth.sideInset(w, 12.dp))
    }
  }

  @Test fun `the test tablet on its side keeps a 720 dp column of content with the gutters outside it`() {
    val w = 1007   // SM-X135G, 1340 px at 213 dpi
    val inset = ContentWidth.sideInset(w, 12.dp)
    val row = w - 2 * inset.value
    assertEquals(720f, row, 1.5f)   // the cap less two 12 dp gutters (the odd dp of integer halving)
  }

  @Test fun `the column never exceeds the cap`() {
    for (w in listOf(800, 1007, 1280, 1920)) {
      val content = w - 2 * ContentWidth.sideInset(w, 16.dp).value
      assertTrue("width $w gives $content", content <= ContentWidth.MAX_DP - 32 + 1)
    }
  }

  @Test fun `short windows keep one chip line on phones and stack it on a tablet on its side`() {
    for (w in listOf(320, 411, 640, 744)) assertTrue("width $w", ContentWidth.chipsOnOneLine(true, w))
    for (w in listOf(745, 1007, 1280)) assertTrue("width $w", !ContentWidth.chipsOnOneLine(true, w))
    for (w in listOf(360, 1007)) assertTrue("tall window $w never uses the short line", !ContentWidth.chipsOnOneLine(false, w))
  }
}
