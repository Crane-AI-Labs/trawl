package com.craneai.findr.core

import com.craneai.findr.R
import com.craneai.findr.engine.AsrModelPack
import com.craneai.findr.engine.PackFilesRegistry
import com.craneai.findr.engine.PrivatePack
import com.craneai.findr.engine.QuickLookPack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PackCatalogTest {
  @Test fun idsAreUniqueAndLookUpWorks() {
    val ids = PackCatalog.all.map { it.id }
    assertEquals(ids.size, ids.toSet().size)
    ids.forEach { assertEquals(it, PackCatalog.byId(it)?.id) }
    assertNull(PackCatalog.byId("nope"))
  }

  @Test fun theSpeechSizeIsTheSumOfTheFilesItInstalls() {
    val required = AsrModelPack.files.filter { it.required }.sumOf { it.size }
    assertEquals(required, PackCatalog.speech.sizeBytes)
    assertEquals(142L, PackCatalog.speech.sizeMb)
  }

  @Test fun theSpeechCopyQuotesTheMeasuredNumbers() {
    val s = PackCatalog.speech
    assertEquals(R.string.pack_speech_benefit, s.benefit.res)
    assertEquals("the right file first 81% of the time instead of 40%", listOf(81, 40), s.benefit.args)
    assertEquals(R.plurals.pack_speech_cost, s.cost.res)
    assertEquals("13 seconds per minute of video, and 13 picks the plural form", listOf(13), s.cost.args)
    assertEquals(13, s.cost.quantity)
  }

  @Test fun quickLookIsAvailableWithItsFilesAndItsOwnSetting() {
    val q = PackCatalog.quickLook
    assertTrue(q.available)
    assertEquals("trawl-quicklook-models.zip", q.archiveName)
    assertEquals(QuickLookPack.files.sumOf { it.size }, q.sizeBytes)
    assertEquals(117L, q.sizeMb)
    assertEquals(QuickLookPack.files, PackFilesRegistry.of(q.id)!!.manifest)
    assertTrue(PackFilesRegistry.of(q.id)!!.archive.matches("trawl-quicklook-models (1).zip"))
    val off = q.withEnabled(FindrSettings(), false)
    assertFalse(off.quickLook)
    assertFalse(q.enabledIn(off))
    assertTrue(q.enabledIn(q.withEnabled(off, true)))
    assertTrue("on by default once installed", q.enabledIn(FindrSettings()))
  }

  @Test fun theQuickLookCopyQuotesTheMeasuredNumbers() {
    val q = PackCatalog.quickLook
    val facts = PackCatalog.Facts
    assertEquals(R.plurals.pack_quicklook_benefit, q.benefit.res)
    assertEquals(
      listOf(PackCopy.Grouped(facts.QUICK_LOOK_PHOTOS_PER_HOUR_AFTER), facts.QUICK_LOOK_PHOTOS_PER_HOUR_BEFORE),
      q.benefit.args,
    )
    assertEquals(facts.QUICK_LOOK_PHOTOS_PER_HOUR_AFTER, q.benefit.quantity)
    assertEquals(R.string.pack_quicklook_cost, q.cost.res)
    assertEquals(listOf(facts.QUICK_LOOK_SLOWDOWN_PCT), q.cost.args)
  }

  @Test fun theBlurPackIsAnAddOnWithItsOwnSwitchAndASizeThatMatchesItsFiles() {
    val p = PackCatalog.privacy
    assertTrue(p.available)
    assertEquals("trawl-private-models.zip", p.archiveName)
    assertEquals(PrivatePack.files.sumOf { it.size }, p.sizeBytes)
    assertEquals(PackCatalog.Facts.PRIVATE_SIZE_BYTES, p.sizeBytes)
    assertEquals(22L, p.sizeMb)
    assertEquals(PrivatePack.files, PackFilesRegistry.of(p.id)!!.manifest)
    assertEquals("private", PackFilesRegistry.of(p.id)!!.dir)
    assertTrue(PackFilesRegistry.of(p.id)!!.archive.matches("trawl-private-models (1).zip"))
    assertTrue("the model and its licence travel together", PrivatePack.files.any { it.path == PrivatePack.LICENCE })
    assertEquals("recommended for the panel's users", R.string.pack_privacy_recommended, p.recommendedFor?.res)
    assertEquals(listOf(PackCatalog.Facts.PRIVATE_SLOWDOWN_PCT), p.cost.args)
    assertTrue("the shipped slowdown stays inside the 5% budget", PackCatalog.Facts.PRIVATE_SLOWDOWN_PCT <= 5)
  }

  @Test fun theBlurPackSwitchIsNotTheBlurSetting() {
    val p = PackCatalog.privacy
    val off = p.withEnabled(FindrSettings(), false)
    assertFalse(off.privateScoring)
    assertTrue("installing the pack must not turn the main app's blur on", !p.withEnabled(FindrSettings(blurPrivatePreviews = false), true).blurPrivatePreviews)
    assertTrue("and turning the pack off must not turn it off", p.withEnabled(FindrSettings(), false).blurPrivatePreviews)
    assertTrue("scoring is on by default once the pack is in", p.enabledIn(FindrSettings()))
    assertFalse(p.enabledIn(off))
  }

  @Test fun everyAvailablePackHasFilesAndEveryPackNamesItsFile() {
    PackCatalog.all.forEach {
      assertTrue(it.nameRes != 0 && it.benefit.res != 0 && it.cost.res != 0 && it.archiveName.endsWith(".zip"))
      if (it.available) assertNotNull(it.id, PackFilesRegistry.of(it.id))
    }
  }

  @Test fun theSpeechSwitchDrivesTheSpeechSetting() {
    val off = PackCatalog.speech.withEnabled(FindrSettings(), false)
    assertFalse(off.speechToText)
    assertFalse(PackCatalog.speech.enabledIn(off))
    assertTrue(PackCatalog.speech.enabledIn(PackCatalog.speech.withEnabled(off, true)))
  }
  @Test fun everyPackIsShownUnderItsTrawlNameAndBothOldAndNewZipNamesStillInstall() {
    for (def in PackCatalog.all) {
      val files = PackFilesRegistry.of(def.id) ?: continue
      assertTrue(def.archiveName, def.archiveName.startsWith("trawl-") && !def.archiveName.contains("findr"))
      assertTrue("the name shown is a name the app picks up", files.archive.matches(def.archiveName))
      val old = def.archiveName.replace("trawl-", "findr-")
      assertTrue("a zip downloaded before 1.0.3 as $old still installs", files.archive.matches(old))
      assertTrue(files.archive.matches(old.replace(".zip", " (2).zip")))
      assertTrue(files.archive.matches(def.archiveName.uppercase()))
      assertFalse("look-alikes are not packs", files.archive.matches("my-" + def.archiveName.replace("-models", "-notes")))
    }
    assertEquals("trawl-speech-models.zip", PackCatalog.speech.archiveName)
    assertEquals("trawl-speech-models.zip", AsrModelPack.ARCHIVE_NAME)
    // The folder inside an archive is internal and unchanged, so a pack built either way has the same layout.
    assertEquals("findr-speech-models", PackFilesRegistry.speech.folderName)
  }
}
