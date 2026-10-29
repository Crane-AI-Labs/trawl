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

  /** The watcher's loop without the phone: looks at a changing listing every 1.5 s, the way pollOnce does, and remembers what it saw. */
  private class Sim(val hasEnd: (DownloadEntry) -> Boolean) {
    var previous = emptyMap<String, Seen>()
    val skip = HashSet<String>()
    fun look(listing: List<DownloadEntry>, now: Long): DownloadStep {
      val step = DownloadsWatch.step(listing, previous, now, skip, hasEnd)
      previous = DownloadsWatch.look(listing, previous, now)
      if (step is DownloadStep.Install) skip += DownloadsWatch.key(step.entry)
      if (step is DownloadStep.Stopped) skip += DownloadsWatch.key(step.entry)
      return step
    }
  }

  private val whole = 670_154_240L

  @Test fun aFileUnchangedForTheStableWindowIsReadyOneStillGrowingIsNot() {
    val now = 5_000L
    val first = DownloadsWatch.scan(listOf(e("trawl-models.zip", 100, 4_000)), emptyMap(), now)
    assertTrue("modified 1 s ago and never seen: wait", first.ready.isEmpty())
    assertEquals("trawl-models.zip", first.settling?.name)
    val seen = mapOf("trawl-models.zip" to Seen(100, 4_000, since = now))
    val soon = DownloadsWatch.scan(listOf(e("trawl-models.zip", 100, 4_000)), seen, now + 1_500)
    assertTrue("two looks 1.5 s apart used to be enough; it is not any more", soon.ready.isEmpty())
    val later = DownloadsWatch.scan(listOf(e("trawl-models.zip", 100, 4_000)), seen, now + DownloadsWatch.STABLE_MS)
    assertEquals(listOf("trawl-models.zip"), later.ready.map { it.name })
    val grew = DownloadsWatch.scan(listOf(e("trawl-models.zip", 200, 5_000)), seen, now + DownloadsWatch.STABLE_MS)
    assertTrue(grew.ready.isEmpty())
  }

  @Test fun theStableClockRestartsWheneverLengthOrModifiedTimeChanges() {
    val a = listOf(e("trawl-models.zip", 100, 1_000))
    val first = DownloadsWatch.look(a, emptyMap(), 10_000)
    assertEquals(10_000L, first["trawl-models.zip"]!!.since)
    assertEquals(10_000L, DownloadsWatch.look(a, first, 20_000)["trawl-models.zip"]!!.since)   // unchanged: keeps its old start
    assertEquals(20_000L, DownloadsWatch.look(listOf(e("trawl-models.zip", 101, 1_000)), first, 20_000)["trawl-models.zip"]!!.since)
    assertEquals(20_000L, DownloadsWatch.look(listOf(e("trawl-models.zip", 100, 1_500)), first, 20_000)["trawl-models.zip"]!!.since)   // same size, written to
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
    for (name in listOf("Unconfirmed 123456.crdownload", "trawl-models.zip.part", "trawl-models.zip.download", "trawl-models.zip.opdownload", "Unconfirmed 9.opdownload", ".pending-1760000000-trawl-models.zip")) {
      val s = DownloadsWatch.scan(listOf(e(name, 50_000_000)), emptyMap(), 60_000)
      assertEquals(name, name, s.partial?.name)
      assertTrue(name, s.ready.isEmpty())
      assertTrue(name, DownloadsWatch.step(listOf(e(name, 50_000_000)), emptyMap(), 60_000, emptySet()) { true } is DownloadStep.Downloading)
    }
    assertNull(DownloadsWatch.scan(listOf(e("holiday.zip.part")), emptyMap(), 60_000).partial)   // someone else's partial file is not ours
  }

  @Test fun anEmptyFileIsNotReady() {
    assertTrue(DownloadsWatch.scan(listOf(e("trawl-models.zip", 0)), emptyMap(), 60_000).ready.isEmpty())
  }

  @Test fun aFileUnderItsFinalNameThatStallsWithoutAnEndIsNeverInstalled() {
    // The reported bug: the browser wrote the final name, got 300 MB in, and went quiet. Old rule: two looks 1.5 s apart, install.
    val sim = Sim { false }
    val half = e("trawl-models.zip", 300_000_000, 1_000)
    var t = 2_000L
    while (t < 100_000) {
      val step = sim.look(listOf(half), t)
      assertTrue("t=$t $step", step is DownloadStep.Downloading)
      assertEquals(300_000_000L, (step as DownloadStep.Downloading).bytes)
      t += 1_500
    }
  }

  @Test fun aStalledFileIsReportedStoppedOnceAfterTwoQuietMinutesAndNotAgain() {
    val sim = Sim { false }
    val half = e("trawl-models.zip", 300_000_000, 1_000)
    var stopped = 0
    var t = 2_000L
    while (t < 400_000) {
      val step = sim.look(listOf(half), t)
      if (step is DownloadStep.Stopped) { stopped++; assertTrue("not before ${DownloadsWatch.STALLED_MS}", t - 2_000 >= DownloadsWatch.STALLED_MS) }
      assertFalse(step is DownloadStep.Install)
      t += 1_500
    }
    assertEquals(1, stopped)
  }

  @Test fun anOldHalfFileFoundAtStartUpIsReportedStoppedAtOnceNotInstalled() {
    val step = DownloadsWatch.step(listOf(e("trawl-models.zip", 300_000_000, 1_000)), emptyMap(), 10_000_000, emptySet()) { false }
    assertTrue(step.toString(), step is DownloadStep.Stopped)
  }

  @Test fun aWholeFileIsInstalledOnceItHasHeldStillForTheWindowAndNotBefore() {
    val sim = Sim { true }
    val done = e("trawl-models.zip", whole, 100_000)
    val steps = generateSequence(100_500L) { it + 1_500 }.takeWhile { it <= 100_500 + 20_000 }.map { it to sim.look(listOf(done), it) }.toList()
    val firstInstall = steps.first { it.second is DownloadStep.Install }.first
    assertTrue("installed at $firstInstall, only ${firstInstall - 100_500} ms after first seen", firstInstall - 100_500 >= DownloadsWatch.STABLE_MS)
    assertTrue(steps.takeWhile { it.first < firstInstall }.all { it.second is DownloadStep.Downloading })
    assertEquals("once", 1, steps.count { it.second is DownloadStep.Install })
  }

  @Test fun anInPlaceDownloadGrownInChunksWithPausesNeverInstallsUntilItIsCompleteAndStill() {
    // Written to the final name in 20 chunks with 3 to 5 s pauses (a slow connection). hasEnd is true only for the last chunk.
    val chunk = whole / 20
    var written = 0L
    var lastWrite = 0L
    val sim = Sim { it.length >= whole }
    var t = 0L
    var nextWrite = 3_000L
    var pause = 3_000L
    var installedAt = -1L
    while (t < 200_000 && installedAt < 0) {
      if (written < whole && t >= nextWrite) {
        written = minOf(whole, written + chunk); lastWrite = t
        nextWrite = t + pause; pause = if (pause == 5_000L) 3_000L else pause + 1_000
      }
      if (written > 0) {
        val step = sim.look(listOf(e("trawl-models.zip", written, lastWrite)), t)
        if (step is DownloadStep.Install) installedAt = t
        else assertTrue("t=$t written=$written $step", step is DownloadStep.Downloading)
      }
      t += 1_500
    }
    assertTrue("must install in the end", installedAt > 0)
    assertEquals(whole, written)
    assertTrue("installed ${installedAt - lastWrite} ms after the last write", installedAt - lastWrite >= DownloadsWatch.STABLE_MS)
  }

  @Test fun aPreallocatedFileThatAlreadyEndsLikeAZipStillWaitsForTheWritesToStop() {
    // Size and end record are there from the start (the end was written first); only the modified time moves as the middle is filled.
    val sim = Sim { true }
    var t = 0L
    var mod = 1_000L
    while (t < 60_000) {
      mod = t                                   // a write just now
      val step = sim.look(listOf(e("trawl-models.zip", whole, mod)), t)
      assertTrue("t=$t $step", step is DownloadStep.Downloading)
      t += 3_000
    }
    // The writes stop: it is installed after the stable window, not before.
    val quiet = generateSequence(t) { it + 1_500 }.first { sim.look(listOf(e("trawl-models.zip", whole, mod)), it) is DownloadStep.Install }
    assertTrue(quiet - mod >= DownloadsWatch.STABLE_MS)
  }

  @Test fun aFileThatCompletesAfterItWasRefusedIsTriedAgainAndInstalls() {
    val sim = Sim { true }
    val stalled = e("trawl-models.zip", 300_000_000, 1_000)
    // First try: it held still with an end (say a preallocated file) and the install was attempted and refused.
    val first = generateSequence(2_000L) { it + 1_500 }.map { sim.look(listOf(stalled), it) }.first { it is DownloadStep.Install }
    assertEquals(stalled, (first as DownloadStep.Install).entry)
    // Same file again: not tried a second time.
    assertTrue(sim.look(listOf(stalled), 60_000) is DownloadStep.Nothing)
    // The browser resumes and finishes: a new length and modified time make a new key.
    val finished = e("trawl-models.zip", whole, 90_000)
    assertTrue(sim.look(listOf(finished), 91_000) is DownloadStep.Downloading)
    val again = generateSequence(92_500L) { it + 1_500 }.map { sim.look(listOf(finished), it) }.first { it is DownloadStep.Install }
    assertEquals(finished, (again as DownloadStep.Install).entry)
  }

  @Test fun aFileThatChangedWhileTheInstallRanIsStillDownloadingNotDamaged() {
    assertTrue(DownloadsWatch.changedSince(300, 1_000, 400, 1_000))        // it grew
    assertTrue(DownloadsWatch.changedSince(300, 1_000, 300, 2_000))        // preallocated: same size, written to
    assertFalse(DownloadsWatch.changedSince(300, 1_000, 300, 1_000))       // unchanged: the failure is real
    assertFalse(DownloadsWatch.changedSince(300, 1_000, 0, 0, exists = false))   // deleted: nothing is coming
  }

  @Test fun aStoppedFileIsNotReportedAgainOverANewDownload() {
    // After the person taps Get the models again, the old truncated file is still in Downloads; the new download is a different name.
    val sim = Sim { false }
    val old = e("trawl-models.zip", 300_000_000, 1_000)
    assertTrue(sim.look(listOf(old), 10_000_000) is DownloadStep.Stopped)
    val fresh = e("Unconfirmed 777.crdownload", 5_000_000, 10_100_000)
    val step = sim.look(listOf(old, fresh), 10_101_500)
    assertTrue(step.toString(), step is DownloadStep.Downloading)
    assertEquals(5_000_000L, (step as DownloadStep.Downloading).bytes)
  }
}
