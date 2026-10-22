package com.craneai.findr.engine

import com.craneai.findr.core.UpdateKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure halves of model delivery: the update notice's version compare and the Downloads watcher's "is it finished" rules. */
class ModelDeliveryTest {
  private val json = """{"format":1,"classes":{"standard":{"version":3,"size_mb":670,"update_kind":"text-reembed"},"lite":{"version":2,"size_mb":304}}}"""
  private val known = ModelsKnown.parse(json)

  @Test fun knownFileParsesPerClassAndUnknownKindIsTheCostlyOne() {
    assertEquals(3, known[BundleClass.STANDARD]?.version)
    assertEquals(UpdateKind.TEXT_REEMBED, known[BundleClass.STANDARD]?.kind)
    assertEquals(UpdateKind.FULL_REEMBED, known[BundleClass.LITE]?.kind)   // no kind given: never promise a cheaper update than we can prove
    assertTrue(ModelsKnown.parse("not json").isEmpty())
  }

  @Test fun noNoticeWhenNothingIsInstalledOrItIsCurrent() {
    assertNull(ModelsKnown.update(null, BundleClass.STANDARD, known, 0))   // first run: the get-models card, not an update
    assertNull(ModelsKnown.update(InstalledBundle("trawl-models", BundleClass.STANDARD, 3), BundleClass.STANDARD, known, 0))
    assertNull(ModelsKnown.update(InstalledBundle("trawl-models", BundleClass.STANDARD, 4), BundleClass.STANDARD, known, 0))
  }

  @Test fun noticeWhenInstalledIsOlderAndNotDismissed() {
    val u = ModelsKnown.update(InstalledBundle("trawl-models", BundleClass.STANDARD, 1), BundleClass.STANDARD, known, 0)
    assertNotNull(u)
    assertEquals(3, u!!.latestVersion); assertEquals(1, u.installedVersion); assertEquals(670, u.sizeMb)
    assertTrue(u.kind.rereadsFiles)
  }

  @Test fun dismissingHidesThatVersionButNotTheNext() {
    val installed = InstalledBundle("trawl-models", BundleClass.STANDARD, 1)
    assertNull(ModelsKnown.update(installed, BundleClass.STANDARD, known, 3))
    assertNotNull(ModelsKnown.update(installed, BundleClass.STANDARD, ModelsKnown.parse(json.replace("\"version\":3", "\"version\":4")), 3))
  }

  @Test fun aBundleOfTheOtherClassIsNeverComparedAcrossClasses() {
    assertNull(ModelsKnown.update(InstalledBundle("trawl-models-lite", BundleClass.LITE, 1), BundleClass.STANDARD, known, 0))
  }

  @Test fun onlyQueryAndPackUpdatesLeaveTheIndexAlone() {
    assertFalse(UpdateKind.QUERY_ONLY.rereadsFiles); assertFalse(UpdateKind.PACK_ONLY.rereadsFiles); assertFalse(UpdateKind.CONFIG.rereadsFiles)
    assertTrue(UpdateKind.TEXT_REEMBED.rereadsFiles); assertTrue(UpdateKind.FULL_REEMBED.rereadsFiles)
  }

  private fun e(name: String, len: Long = 670_000_000, mod: Long = 1_000) = DownloadEntry(name, len, mod)

  @Test fun aFileUnchangedSinceTheLastLookIsReadyOneStillGrowingIsNot() {
    val now = 5_000L
    val first = DownloadsWatch.scan(listOf(e("trawl-models.zip", 100, 4_000)), emptyMap(), now)
    assertTrue("modified 1 s ago and never seen: wait", first.ready.isEmpty())
    val same = DownloadsWatch.scan(listOf(e("trawl-models.zip", 100, 4_000)), mapOf("trawl-models.zip" to (100L to 4_000L)), now + 1_500)
    assertEquals(listOf("trawl-models.zip"), same.ready.map { it.name })
    val grew = DownloadsWatch.scan(listOf(e("trawl-models.zip", 200, 5_000)), mapOf("trawl-models.zip" to (100L to 4_000L)), now + 1_500)
    assertTrue(grew.ready.isEmpty())
  }

  @Test fun aFileThatWasAlreadyThereBeforeTheAppOpenedIsReadyOnTheFirstLook() {
    assertEquals(1, DownloadsWatch.scan(listOf(e("trawl-models-lite.zip", 300, 1_000)), emptyMap(), 60_000).ready.size)
  }

  @Test fun browserCopiesAndBothClassesAreCandidatesOtherZipsAreNot() {
    val names = listOf("trawl-models (1).zip", "trawl-models-lite.zip", "holiday.zip", "findr-speech-models.zip")
    val ready = DownloadsWatch.scan(names.map { e(it) }, emptyMap(), 60_000).ready.map { it.name }.toSet()
    assertEquals(setOf("trawl-models (1).zip", "trawl-models-lite.zip"), ready)
  }

  @Test fun partialDownloadsAreRecognisedAndNeverInstalled() {
    for (name in listOf("Unconfirmed 123456.crdownload", "trawl-models.zip.part", "trawl-models.zip.download", ".pending-1760000000-trawl-models.zip")) {
      val s = DownloadsWatch.scan(listOf(e(name, 50_000_000)), emptyMap(), 60_000)
      assertEquals(name, name, s.partial?.name)
      assertTrue(name, s.ready.isEmpty())
    }
    assertNull(DownloadsWatch.scan(listOf(e("holiday.zip.part")), emptyMap(), 60_000).partial)   // someone else's partial file is not ours
  }

  @Test fun anEmptyFileIsNotReady() {
    assertTrue(DownloadsWatch.scan(listOf(e("trawl-models.zip", 0)), emptyMap(), 60_000).ready.isEmpty())
  }
}
