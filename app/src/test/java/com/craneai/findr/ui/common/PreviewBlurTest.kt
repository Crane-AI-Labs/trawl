package com.craneai.findr.ui.common

import com.craneai.findr.core.FileKind
import com.craneai.findr.core.IndexedFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewBlurTest {
  private fun file(id: Long, score: Float?) =
    IndexedFile(id, "content://x/$id", "DCIM/", "p$id.jpg", FileKind.IMAGE, "image/jpeg", 1_000, 1_000, looksPrivate = score)

  @Test fun offNeverHidesAnything() {
    assertFalse(PreviewBlur.Off.hides(file(1, 0.99f)))
    assertFalse(PreviewBlur(enabled = false).looksPrivate(file(1, 0.99f)))
  }

  @Test fun onlyHighScoresAreHidden() {
    val blur = PreviewBlur(enabled = true)
    assertTrue(blur.hides(file(1, 0.95f)))
    assertFalse(blur.hides(file(2, 0.5f)))
    assertFalse(blur.hides(file(3, null)))
    assertFalse(blur.hides(file(4, -1f)))   // unreadable
  }

  @Test fun revealingShowsThatOnePictureOnly() {
    val blur = PreviewBlur(enabled = true)
    val a = file(1, 0.95f); val b = file(2, 0.95f)
    assertTrue(blur.reveal(a))
    assertFalse(blur.hides(a))
    assertTrue(blur.hides(b))
  }

  @Test fun aDeniedRevealKeepsThePictureHiddenAndSaysWhy() {
    var denied = 0
    var unlocked = false
    val blur = PreviewBlur(enabled = true, allowReveal = { unlocked }, onDenied = { denied++ })
    val a = file(1, 0.95f)
    assertFalse(blur.reveal(a))
    assertTrue(blur.hides(a))
    assertEquals(1, denied)
    unlocked = true
    assertTrue(blur.reveal(a))
    assertFalse(blur.hides(a))
    assertEquals(1, denied)
  }

  @Test fun theBlurTargetKeepsThePictureShapeAtAboutTwelvePixels() {
    assertEquals(12 to 9, PrivacyBlurTransformation.targetSize(4000, 3000))
    assertEquals(7 to 12, PrivacyBlurTransformation.targetSize(1080, 1920))
    assertEquals(12 to 12, PrivacyBlurTransformation.targetSize(512, 512))
    assertEquals(12 to 1, PrivacyBlurTransformation.targetSize(5000, 20))   // never zero
  }
}
