package com.craneai.findr.ui.common

import android.app.Application
import coil3.request.Options
import coil3.size.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class MediaStoreThumbnailTest {
  private val keyer = MediaThumbKeyer()

  private fun options(w: Int = 320, h: Int = 320): Options =
    Options(RuntimeEnvironment.getApplication(), size = Size(w, h))

  @Test fun identicalPhotoAndSizeProducesSameKey() {
    val a = MediaThumb("content://media/external/images/media/42", modifiedMillis = 1_700_000_000_000L, sizeBytes = 123_456L)
    val b = MediaThumb("content://media/external/images/media/42", modifiedMillis = 1_700_000_000_000L, sizeBytes = 123_456L)
    assertEquals(keyer.key(a, options()), keyer.key(b, options()))
  }

  @Test fun inPlaceEditWithNewModifiedTimeProducesDifferentKey() {
    val before = MediaThumb("content://media/external/images/media/42", modifiedMillis = 1_700_000_000_000L, sizeBytes = 123_456L)
    val edited = MediaThumb("content://media/external/images/media/42", modifiedMillis = 1_700_000_005_000L, sizeBytes = 123_456L)
    assertNotEquals(keyer.key(before, options()), keyer.key(edited, options()))
  }

  @Test fun inPlaceEditWithNewSizeBytesProducesDifferentKey() {
    val before = MediaThumb("content://media/external/images/media/42", modifiedMillis = 1_700_000_000_000L, sizeBytes = 123_456L)
    val edited = MediaThumb("content://media/external/images/media/42", modifiedMillis = 1_700_000_000_000L, sizeBytes = 123_999L)
    assertNotEquals(keyer.key(before, options()), keyer.key(edited, options()))
  }

  @Test fun differentTargetSizesProduceDifferentKeys() {
    val thumb = MediaThumb("content://media/external/images/media/42", modifiedMillis = 1_700_000_000_000L, sizeBytes = 123_456L)
    assertNotEquals(keyer.key(thumb, options(128, 128)), keyer.key(thumb, options(512, 512)))
  }
}
