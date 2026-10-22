package com.craneai.findr.index

import com.craneai.findr.core.FileKind
import com.craneai.findr.core.FindrSettings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.LocalDateTime

/** The date a file shows: MediaStore's capture date, else the EXIF one, else the modified time (D24). */
class CaptureDateTest {
  private val kampala = ZoneId.of("Africa/Kampala")   // UTC+3, no daylight saving
  private val now = 1_790_000_000_000L                // 2026-09-21
  private val modifiedSec = 1_789_000_000L            // a copy made recently
  private val exif2024 = LocalDateTime.of(2024, 12, 25, 14, 3, 9).toInstant(ZoneOffset.ofHours(3)).toEpochMilli()

  // ---- parsing the EXIF text -----------------------------------------------------------------------------------------

  @Test fun parsesLocalTimeInThePhonesZone() {
    assertEquals(exif2024, CaptureDate.parse("2024:12:25 14:03:09", zone = kampala, nowMs = now))
  }

  @Test fun anOffsetFromTheCameraBeatsThePhonesZone() {
    val utc = LocalDateTime.of(2024, 12, 25, 14, 3, 9).toInstant(ZoneOffset.UTC).toEpochMilli()
    assertEquals(utc, CaptureDate.parse("2024:12:25 14:03:09", "+00:00", zone = kampala, nowMs = now))
    assertEquals(exif2024, CaptureDate.parse("2024:12:25 14:03:09", "+03:00", zone = ZoneId.of("UTC"), nowMs = now))
    assertEquals(exif2024, CaptureDate.parse("2024:12:25 14:03:09", "garbage", zone = kampala, nowMs = now))   // unreadable offset: the zone
  }

  @Test fun tolerantOfTrailingBytesAndPadding() {
    assertEquals(exif2024, CaptureDate.parse(" 2024:12:25 14:03:09\u0000", zone = kampala, nowMs = now))
    assertEquals(exif2024, CaptureDate.parse("2024:12:25 14:03:09.123", zone = kampala, nowMs = now))
  }

  @Test fun missingMalformedOrImplausibleIsZero() {
    for (bad in listOf(null, "", "   ", "0000:00:00 00:00:00", "2024-12-25 14:03:09", "yesterday", "1970:01:01 00:00:00", "1980:01:06 00:00:00", "2031:01:01 00:00:00", "2024:13:45 99:99:99")) {
      assertEquals("'$bad'", 0L, CaptureDate.parse(bad, zone = kampala, nowMs = now))
    }
  }

  @Test fun onlyFormatsThatCanHoldExif() {
    assertTrue(CaptureDate.mayHaveExif("image/jpeg"))
    assertTrue(CaptureDate.mayHaveExif("image/heic"))
    assertFalse(CaptureDate.mayHaveExif("image/gif"))
    assertFalse(CaptureDate.mayHaveExif("video/mp4"))
    assertFalse(CaptureDate.mayHaveExif("application/pdf"))
    assertFalse(CaptureDate.mayHaveExif(null))
  }

  // ---- the fallback order --------------------------------------------------------------------------------------------

  private val crawler = Crawler(IndexStore.open(Files.createTempDirectory("findr-cd").toFile()), FakePhone())
  private fun row(mime: String?, takenMs: Long = 0, name: String = "x") =
    MediaRow("external_primary", 1, "content://m/1", name, "DCIM/", mime, 100_000, modifiedSec, 100, 100, null, null, takenMs)

  @Test fun mediaStoreDateWinsOverExif() {
    val taken = exif2024 - 86_400_000L * 400
    assertEquals(taken, crawler.effectiveDate(row("image/jpeg", taken), now, exifMs = exif2024))
  }

  @Test fun exifIsUsedWhenMediaStoreHasNoDate() {
    assertEquals(exif2024, crawler.effectiveDate(row("image/jpeg"), now, exifMs = exif2024))
  }

  @Test fun modifiedTimeWhenNeitherHasADate() {
    assertEquals(modifiedSec * 1000, crawler.effectiveDate(row("image/jpeg"), now, exifMs = 0))
  }

  @Test fun anImplausibleMediaStoreDateFallsThroughToExif() {
    assertEquals(exif2024, crawler.effectiveDate(row("image/jpeg", takenMs = 1_000), now, exifMs = exif2024))              // 1970
    assertEquals(exif2024, crawler.effectiveDate(row("image/jpeg", takenMs = now + 30 * 86_400_000L), now, exifMs = exif2024))   // the future
  }

  @Test fun aDateTakenThatIsJustTheFileTimeCountsAsNoDate() {
    // Some MediaStore builds copy the modified time into DATE_TAKEN for pictures without EXIF: EXIF should still be tried.
    val copied = row("image/jpeg", takenMs = modifiedSec * 1000 + 400)
    assertTrue(crawler.needsExif(copied, now))
    assertEquals(exif2024, crawler.effectiveDate(copied, now, exifMs = exif2024))
    assertEquals(modifiedSec * 1000, crawler.effectiveDate(row("image/jpeg", takenMs = modifiedSec * 1000), now, exifMs = 0))
  }

  @Test fun documentsAndRecordingsAlwaysUseTheModifiedTime() {
    assertEquals(modifiedSec * 1000, crawler.effectiveDate(row("application/pdf", exif2024), now, exifMs = exif2024))
    assertEquals(modifiedSec * 1000, crawler.effectiveDate(row("audio/mpeg"), now, exifMs = exif2024))
    assertEquals(modifiedSec * 1000, crawler.effectiveDate(row(null), now, exifMs = exif2024))
  }

  @Test fun videosUseTheirCaptureDateButNeverReadExif() {
    val taken = exif2024 + 5
    assertEquals(taken, crawler.effectiveDate(row("video/mp4", taken), now))
    assertFalse(crawler.needsExif(row("video/mp4"), now))
  }

  @Test fun exifIsOnlyReadWhenNeeded() {
    assertTrue(crawler.needsExif(row("image/jpeg"), now))
    assertFalse(crawler.needsExif(row("image/jpeg", takenMs = exif2024), now))   // MediaStore already knows
    assertFalse(crawler.needsExif(row("image/gif"), now))
    assertFalse(crawler.needsExif(row("text/plain"), now))
  }

  // ---- through the crawler into the index ----------------------------------------------------------------------------

  private lateinit var dir: File
  private lateinit var store: IndexStore
  private lateinit var phone: FakePhone
  private val reads = ArrayList<Long>()
  private val exifOf = HashMap<Long, Long>()
  private fun crawlerWithExif() = Crawler(store, phone, settings = { FindrSettings() }, hasNoMedia = { false }, batchSize = 2,
    exifDate = { reads += it.id; exifOf[it.id] ?: 0L })
  private fun dateOf(name: String) = store.recent(FileKind.entries.toSet(), 50).first { it.name == name }.dateMillis

  @Before fun setUp() { dir = Files.createTempDirectory("findr-cd2").toFile(); store = IndexStore.open(dir); phone = FakePhone() }
  @After fun tearDown() { store.close(); dir.deleteRecursively() }

  @Test fun theIndexHoldsTheExifDateAndReadsOnlyWhatNeedsIt() {
    phone.put(1, "xender.jpg", "Xender/", "image/jpeg", mtimeSec = modifiedSec)                      // no MediaStore date, has EXIF
    phone.put(2, "whatsapp.jpg", "WhatsApp/", "image/jpeg", mtimeSec = modifiedSec)                  // no MediaStore date, no EXIF
    phone.put(3, "camera.jpg", "DCIM/", "image/jpeg", mtimeSec = modifiedSec, takenMs = exif2024 - 1_000_000_000)   // MediaStore knows
    phone.put(4, "notes.txt", "Documents/", "text/plain", mtimeSec = modifiedSec)
    exifOf[1] = exif2024
    crawlerWithExif().sync()
    assertEquals(exif2024, dateOf("xender.jpg"))
    assertEquals(modifiedSec * 1000, dateOf("whatsapp.jpg"))
    assertEquals(exif2024 - 1_000_000_000, dateOf("camera.jpg"))
    assertEquals(modifiedSec * 1000, dateOf("notes.txt"))
    assertEquals(setOf(1L, 2L), reads.toSet())   // not the camera photo (dated), not the note
  }

  @Test fun filesIndexedBeforeTheReaderExistedAreBackfilledOnceWithoutBeingReadAgain() {
    phone.put(1, "xender.jpg", "Xender/", "image/jpeg", mtimeSec = modifiedSec)
    Crawler(store, phone, hasNoMedia = { false }).sync()   // an older build: no reader
    assertEquals(modifiedSec * 1000, dateOf("xender.jpg"))
    val before = store.knownMediaStoreIds("external_primary")
    exifOf[1] = exif2024
    val report = crawlerWithExif().sync()   // nothing changed in MediaStore, but the reader is new: a full pass
    assertTrue(report.fullResync)
    assertEquals(1, report.unchanged)   // the file is not re-read for words and vectors, only its date moves
    assertEquals(before, store.knownMediaStoreIds("external_primary"))
    assertEquals(exif2024, dateOf("xender.jpg"))
    reads.clear()
    val again = crawlerWithExif().sync()   // and not again
    assertFalse(again.fullResync)
    assertTrue(reads.isEmpty())
  }
}
