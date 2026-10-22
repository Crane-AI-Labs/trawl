package com.craneai.findr.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FormatTest {
  @Test fun durationUnderAnHour() {
    assertEquals("0:42", formatDuration(42_000))
    assertEquals("12:05", formatDuration(725_000))
  }

  @Test fun durationOverAnHour() {
    assertEquals("1:02:05", formatDuration(3_725_000))
  }

  @Test fun durationNeverNegative() {
    assertEquals("0:00", formatDuration(-5))
  }

  @Test fun bytesUseDecimalUnits() {
    assertEquals("512 B", formatBytes(512))
    assertEquals("388 MB", formatBytes(388_000_000))
    assertEquals("1.4 GB", formatBytes(1_400_000_000))
  }

  @Test fun etaIsHonestAboutPrecision() {
    assertEquals(Eta.UnderAMinute, etaOf(20))
    assertEquals(Eta.Minutes(10), etaOf(600))
    val long = etaOf(6 * 3600) as Eta.Hours
    assertEquals(5, long.low)
    assertEquals(8, long.high)
  }

  @Test fun fractionIsSafeForEmptyTotals() {
    assertEquals(0f, fraction(3, 0), 0f)
    assertEquals(0.5f, fraction(5, 10), 0f)
    assertEquals(1f, fraction(12, 10), 0f)
  }

  @Test fun dayBuckets() {
    assertEquals(DayBucket.TODAY, dayBucket(100, 100))
    assertEquals(DayBucket.YESTERDAY, dayBucket(99, 100))
    assertEquals(DayBucket.THIS_WEEK, dayBucket(95, 100))
    assertEquals(DayBucket.EARLIER, dayBucket(80, 100))
  }

  @Test fun pickerFolderFromDocumentId() {
    assertEquals("DCIM/Private", relativeFolderFromDocumentId("primary:DCIM/Private"))
    assertEquals("Download", relativeFolderFromDocumentId("primary:Download/"))
    assertNull(relativeFolderFromDocumentId("primary:"))
    assertNull(relativeFolderFromDocumentId("1234-ABCD:Music"))
    assertNull(relativeFolderFromDocumentId("garbage"))
  }
}
