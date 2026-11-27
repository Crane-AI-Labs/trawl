package com.craneai.findr.harness

import com.craneai.findr.index.Modality
import com.craneai.findr.index.SegmentKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SeesReplayModalityTest {
  @Test fun savedModalityIsPreservedRatherThanGuessedFromKind() {
    assertEquals(Modality.SOUND, AppPathHarness.modalityOf(SegmentKind.SOUND, "sound", true))
    assertEquals(Modality.TEXT, AppPathHarness.modalityOf(SegmentKind.OCR, "text", true))
    assertEquals(Modality.AUDIO, AppPathHarness.modalityOf(SegmentKind.AUDIO, "audio", true))
  }

  @Test fun missingModalityUsesTheProductionKindsDefault() {
    assertEquals(Modality.SOUND, AppPathHarness.modalityOf(SegmentKind.SOUND, null, true))
    assertEquals(Modality.AUDIO, AppPathHarness.modalityOf(SegmentKind.AUDIO, null, true))
    assertEquals(Modality.OCR_TEXT, AppPathHarness.modalityOf(SegmentKind.OCR, null, true))
  }

  @Test fun wordsOnlySegmentsHaveNoModalityAndSeesCannotHaveAVector() {
    assertEquals(Modality.NONE, AppPathHarness.modalityOf(SegmentKind.SEES, null, false))
    assertEquals(Modality.NONE, AppPathHarness.modalityOf(SegmentKind.SOUND, "sound", false))
    assertThrows(IllegalStateException::class.java) { AppPathHarness.modalityOf(SegmentKind.SEES, null, true) }
    assertThrows(IllegalArgumentException::class.java) { AppPathHarness.modalityOf(SegmentKind.SOUND, "none", true) }
    assertThrows(IllegalStateException::class.java) { AppPathHarness.modalityOf(SegmentKind.SOUND, "unknown", true) }
  }
}
