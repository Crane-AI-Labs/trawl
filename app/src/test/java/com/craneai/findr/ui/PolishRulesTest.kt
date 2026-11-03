package com.craneai.findr.ui

import com.craneai.findr.R
import com.craneai.findr.core.IndexPhase
import com.craneai.findr.core.IndexStatus
import com.craneai.findr.engine.IndexWorker
import com.craneai.findr.ui.common.FileNames
import com.craneai.findr.ui.index.MeterLayout
import com.craneai.findr.ui.search.HomeCards
import com.craneai.findr.ui.search.IncompleteNoteKind
import com.craneai.findr.ui.search.incompleteNote
import com.craneai.findr.ui.search.stripMayShow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure rules behind the 1.0.6 polish (UI audit P1, P3, P5, P7 and the polite-wait notification line). */
class PolishRulesTest {

  // ---- P1: the meter's sentence ----
  @Test fun `the meter sentence may wrap at large text, even in its short form`() {
    assertEquals(1, MeterLayout.titleLines(compact = true, large = false))
    assertEquals(3, MeterLayout.titleLines(compact = true, large = true))
    assertEquals(3, MeterLayout.titleLines(compact = false, large = true))
    assertEquals(2, MeterLayout.titleLines(compact = false, large = false))
    assertTrue(MeterLayout.percentBelowTitle(large = true))
    assertFalse(MeterLayout.percentBelowTitle(large = false))
    // While a query is active it stays one line at any size: the answers need the room.
    assertEquals(1, MeterLayout.titleLines(compact = true, large = true, searching = true))
    assertFalse(MeterLayout.percentBelowTitle(large = true, searching = true))
  }

  // ---- P5: names inside a sentence ----
  private fun shown(name: String, large: Boolean = false) = FileNames.visible(FileNames.middle(name, large))

  @Test fun `a long name keeps its start, its end and its extension`() {
    val name = "IMG_20261003_121502.jpg"
    val large = shown(name, large = true)
    assertEquals("IMG_2…1502.jpg", large)
    assertTrue(large.length <= FileNames.LARGE)
    assertEquals(name, FileNames.middle(name))   // 23 characters fit at normal text
    val long = "Screenshot_20261012-184455_WhatsApp.png"
    val normal = shown(long)
    assertTrue(normal, normal.endsWith("sApp.png") && normal.length <= FileNames.NORMAL && normal.contains('…'))
    assertTrue(shown(long, large = true).endsWith(".png"))
  }

  @Test fun `the shortened name stays one word`() {
    // Word joiners on both sides of the ellipsis: a line may not break inside the shortened name.
    assertEquals("IMG_2\u2060…\u20601502.jpg", FileNames.middle("IMG_20261003_121502.jpg", large = true))
  }

  @Test fun `a long extension is kept whole`() {
    val s = shown("Kampala_Parents_School_fees_receipt.jpeg", large = true)
    assertTrue(s, s.endsWith("eipt.jpeg"))
    assertTrue(s.length <= FileNames.LARGE)
  }

  @Test fun `short names and emoji are never cut in half`() {
    assertEquals("a.pdf", FileNames.middle("a.pdf", large = true))
    val emoji = "🎉🎉🎉🎉🎉🎉🎉🎉🎉🎉🎉🎉🎉🎉🎉🎉.jpg"
    val s = shown(emoji, large = true)
    assertEquals(FileNames.LARGE, s.codePointCount(0, s.length))
    assertTrue(s.endsWith("🎉.jpg"))
  }

  // ---- P3: the models strip and the note while searching ----
  @Test fun `the models strip folds while searching until it is opened`() {
    assertTrue(HomeCards.foldWhileSearching(searching = true, opened = false))
    assertFalse(HomeCards.foldWhileSearching(searching = true, opened = true))
    assertFalse(HomeCards.foldWhileSearching(searching = false, opened = false))
  }

  @Test fun `no still-learning note while the model is not on the phone`() {
    val indexing = IndexStatus(phase = IndexPhase.INDEXING, filesKnown = 10, filesSearchable = 10)
    assertEquals(IncompleteNoteKind.NONE, incompleteNote(indexing.copy(modelReady = false)))
    assertEquals(IncompleteNoteKind.MEANING, incompleteNote(indexing))
    assertEquals(IncompleteNoteKind.CHARGER, incompleteNote(indexing.copy(needsCharger = true)))
    assertEquals(IncompleteNoteKind.MEANING, incompleteNote(indexing.copy(needsCharger = true, lightOnBattery = true)))
  }

  // ---- P7: which video rows keep room for a strip ----
  @Test fun `a clip too short for two pictures never reserves a strip`() {
    assertFalse(stripMayShow(200, 400))
    assertTrue(stripMayShow(30_000, 60_000))
    assertTrue(stripMayShow(1_000, 2_000))
    assertTrue(stripMayShow(5_000, null))   // length unknown: room is kept, as before
  }

  // ---- The polite-wait notification: says "another app" only when the phone is unlocked ----
  @Test fun `the polite line names another app only when the phone is unlocked`() {
    assertEquals(R.string.notif_waiting_other_app, IndexWorker.politeLine(keyguardLocked = false))
    assertEquals(R.string.notif_waiting_polite, IndexWorker.politeLine(keyguardLocked = true))
  }
}
