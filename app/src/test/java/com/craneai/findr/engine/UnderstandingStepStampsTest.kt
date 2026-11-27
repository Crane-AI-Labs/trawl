package com.craneai.findr.engine

import com.craneai.findr.core.FileKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UnderstandingStepStampsTest {
  @Test fun aFreshVideoReadIsStampedForItsPicturesAndItsSoundtrack() =
    assertEquals("video-reading=1 soundtrack-reading=1", UnderstandingStep.readingStamps(FileKind.VIDEO))

  @Test fun aFreshRecordingReadIsStampedForItsSoundtrackOnly() =
    assertEquals("soundtrack-reading=1", UnderstandingStep.readingStamps(FileKind.AUDIO))

  @Test fun otherFilesCarryNoReadingStamp() {
    assertNull(UnderstandingStep.readingStamps(FileKind.IMAGE))
    assertNull(UnderstandingStep.readingStamps(FileKind.DOCUMENT))
  }

  /** A fresh read made while a speech pack is in use already has its reading: stamped, so the pack's catch-up skips it. */
  @Test fun aFreshSoundtrackReadCarriesTheSpeechPackInUse() {
    assertEquals("soundtrack-reading=1 speech-pack=lg-student@2026112201", UnderstandingStep.readingStamps(FileKind.AUDIO, "lg-student@2026112201"))
    assertEquals("video-reading=1 soundtrack-reading=1 speech-pack=sw-student@2026112201", UnderstandingStep.readingStamps(FileKind.VIDEO, "sw-student@2026112201"))
    assertNull(UnderstandingStep.readingStamps(FileKind.IMAGE, "lg-student@2026112201"))
  }
}
