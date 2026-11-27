package com.craneai.findr.ui.common

import com.craneai.findr.core.FileKind
import com.craneai.findr.core.IndexedFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import androidx.compose.runtime.snapshots.Snapshot
import com.craneai.findr.core.PrivacyCheck
import com.craneai.findr.core.PrivateLook
import kotlinx.coroutines.runBlocking

class PreviewBlurTest {
  private fun file(id: Long, score: Float?, unscored: Boolean = false) =
    IndexedFile(id, "content://x/$id", "DCIM/", "p$id.jpg", FileKind.IMAGE, "image/jpeg", 1_000, 1_000, looksPrivate = score, privacyUnscored = unscored)

  /** A scorer that answers at once with [answer] for every picture, and counts the asks. */
  private class Scorer(val answer: Float?, val on: Boolean = true) : PrivacyCheck {
    var asks = 0
    override fun available() = on
    override suspend fun score(file: IndexedFile): Float? { asks++; return answer }
  }

  @Test fun anUnscoredPictureIsBlurredUntilItIsScored() {
    val blur = PreviewBlur(enabled = true, threshold = PrivateLook.MAIN_APP_THRESHOLD, check = Scorer(0.1f))
    val f = file(1, null, unscored = true)
    assertTrue(blur.hides(f))
    assertTrue(blur.checking(f))
    assertFalse("checking is not \"looks private\"", blur.looksPrivate(f))
  }

  @Test fun aSafeScoreUnblursItAndAPrivateScoreKeepsItBlurred() = runBlocking {
    val safe = PreviewBlur(enabled = true, threshold = PrivateLook.MAIN_APP_THRESHOLD, check = Scorer(0.1f))
    val f = file(1, null, unscored = true)
    safe.check(f)
    assertFalse(safe.hides(f))
    assertFalse(safe.checking(f))
    val private = PreviewBlur(enabled = true, threshold = PrivateLook.MAIN_APP_THRESHOLD, check = Scorer(0.97f))
    private.check(f)
    assertTrue(private.hides(f))
    assertTrue(private.looksPrivate(f))
    assertFalse(private.checking(f))
    val unreadable = PreviewBlur(enabled = true, check = Scorer(-1f))
    unreadable.check(f)
    assertFalse("an unreadable picture is drawn as it is, as the pass leaves it", unreadable.hides(f))
  }

  @Test fun scoredPicturesNeverWaitForAScore() = runBlocking {
    val scorer = Scorer(0.99f)
    val blur = PreviewBlur(enabled = true, threshold = PrivateLook.MAIN_APP_THRESHOLD, check = scorer)
    val safe = file(1, 0.1f); val private = file(2, 0.97f)
    assertFalse(blur.hides(safe))
    assertTrue(blur.hides(private))
    blur.check(safe); blur.check(private)
    assertEquals("only an unscored picture is ever scored on demand", 0, scorer.asks)
    assertFalse(blur.hides(safe))
  }

  @Test fun withoutAScorerOrWithTheBlurOffNothingWaitsBlurred() = runBlocking {
    val f = file(1, null, unscored = true)
    assertFalse("no scorer (Go, or the pack is not in): nothing could score it", PreviewBlur(enabled = true, check = Scorer(0.1f, on = false)).hides(f))
    assertFalse(PreviewBlur(enabled = true).hides(f))
    val off = Scorer(0.1f)
    val blurOff = PreviewBlur(enabled = false, check = off)
    assertFalse(blurOff.hides(f))
    blurOff.check(f)
    assertEquals("the blur switched off asks for nothing", 0, off.asks)
  }

  @Test fun aScoreThatCannotBeHadNowKeepsItBlurred() = runBlocking {
    val blur = PreviewBlur(enabled = true, check = Scorer(null))   // the model failed to run
    val f = file(1, null, unscored = true)
    blur.check(f)
    assertTrue("fail closed: still blurred (a tap shows it)", blur.hides(f))
    assertTrue(blur.reveal(f))
    assertFalse(blur.hides(f))
  }

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

  @Test fun revealingOnePictureOnlyInvalidatesThatPicturesState() {
    val blur = PreviewBlur(enabled = true)
    val a = file(1, 0.95f); val b = file(2, 0.95f)
    val readsA = HashSet<Any>()
    val readsB = HashSet<Any>()
    Snapshot.observe(readObserver = { readsA += it }) { assertTrue(blur.hides(a)) }
    Snapshot.observe(readObserver = { readsB += it }) { assertTrue(blur.hides(b)) }
    val written = HashSet<Any>()
    Snapshot.observe(writeObserver = { written += it }) { assertTrue(blur.reveal(a)) }
    assertTrue("revealing picture A must write A's state", written.any { it in readsA })
    assertFalse("revealing picture A must NOT invalidate picture B's state", written.any { it in readsB })
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
