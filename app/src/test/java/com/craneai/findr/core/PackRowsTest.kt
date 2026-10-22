package com.craneai.findr.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PackRowsTest {
  private fun rows(states: Map<String, PackState>, settings: FindrSettings = FindrSettings(), catalog: List<PackDef> = PackCatalog.all) =
    packRows(catalog, states, settings).associateBy { it.def.id }

  /** The catalog with quick look marked not yet available, for the "Coming soon" rules (every real pack is available today). */
  private val withUnavailable = PackCatalog.all.map { if (it.id == PackCatalog.QUICK_LOOK) it.copy(available = false) else it }

  @Test fun nothingOnDiskShowsEveryPackAsNotAdded() {
    val r = rows(emptyMap())
    assertEquals(PackStatus.NOT_INSTALLED, r.getValue(PackCatalog.SPEECH).status)
    assertEquals(PackStatus.NOT_INSTALLED, r.getValue(PackCatalog.QUICK_LOOK).status)
    assertFalse(r.getValue(PackCatalog.SPEECH).on)
    assertEquals(PackStatus.COMING_SOON, rows(emptyMap(), catalog = withUnavailable).getValue(PackCatalog.QUICK_LOOK).status)
  }

  @Test fun quickLooksSwitchFollowsTheQuickLookSetting() {
    val states = mapOf(PackCatalog.QUICK_LOOK to PackState.Installed(117_164_024))
    assertTrue(rows(states, FindrSettings(quickLook = true)).getValue(PackCatalog.QUICK_LOOK).on)
    assertFalse(rows(states, FindrSettings(quickLook = false)).getValue(PackCatalog.QUICK_LOOK).on)
  }

  @Test fun installedShowsTheSwitchFromTheSetting() {
    val states = mapOf(PackCatalog.SPEECH to PackState.Installed(142_000_000))
    val on = rows(states, FindrSettings(speechToText = true)).getValue(PackCatalog.SPEECH)
    assertEquals(PackStatus.INSTALLED, on.status)
    assertTrue(on.on)
    assertEquals(142_000_000L, on.installedBytes)
    assertFalse(rows(states, FindrSettings(speechToText = false)).getValue(PackCatalog.SPEECH).on)
  }

  @Test fun theSwitchIsOffWhenThePackIsNotInstalledEvenIfTheSettingIsOn() {
    val r = rows(emptyMap(), FindrSettings(speechToText = true)).getValue(PackCatalog.SPEECH)
    assertFalse(r.on)
  }

  @Test fun installingCarriesTheFraction() {
    val r = rows(mapOf(PackCatalog.SPEECH to PackState.Installing(25, 100))).getValue(PackCatalog.SPEECH)
    assertEquals(PackStatus.INSTALLING, r.status)
    assertEquals(0.25f, r.progress!!, 0.0001f)
    assertNull(rows(mapOf(PackCatalog.SPEECH to PackState.Installing(0, 0))).getValue(PackCatalog.SPEECH).progress)
    assertEquals(1f, PackState.Installing(300, 100).fraction!!, 0f)
  }

  @Test fun aFailureStaysVisibleAsNotAddedWithItsProblem() {
    val r = rows(mapOf(PackCatalog.SPEECH to PackState.Failed(PackProblem.NOT_FOUND, "x"))).getValue(PackCatalog.SPEECH)
    assertEquals(PackStatus.NOT_INSTALLED, r.status)
    assertEquals(PackProblem.NOT_FOUND, r.problem)
    assertEquals("x", r.problemDetail)
  }

  @Test fun filesOnThePhoneWinOverAnUnavailableFlag() {
    val r = rows(mapOf(PackCatalog.QUICK_LOOK to PackState.Installed(1)), catalog = withUnavailable).getValue(PackCatalog.QUICK_LOOK)
    assertEquals(PackStatus.INSTALLED, r.status)
  }

  @Test fun anUnavailablePackWithAFailedTryStaysComingSoon() {
    val r = rows(mapOf(PackCatalog.QUICK_LOOK to PackState.Failed(PackProblem.UNAVAILABLE)), catalog = withUnavailable)
      .getValue(PackCatalog.QUICK_LOOK)
    assertEquals(PackStatus.COMING_SOON, r.status)
  }

  @Test fun quickLookSpeechAndTheBlurPackAreAllOnByDefault() {
    assertEquals(listOf(PackCatalog.SPEECH, PackCatalog.QUICK_LOOK, PackCatalog.PRIVACY), PackCatalog.all.filter { it.byDefault }.map { it.id })
    assertTrue(FindrSettings().speechToText && FindrSettings().quickLook)   // the settings already default to on
    assertTrue(FindrSettings().privateScoring && FindrSettings().blurPrivatePreviews)
  }

  @Test fun onboardingAsksAboutNoPackAndDoesNotNagAboutAbsentFiles() {
    // Nothing in Downloads: still no question, for any of the three default packs.
    assertEquals(emptyList<String>(), offeredAtOnboarding(packRows(PackCatalog.all, emptyMap(), FindrSettings())).map { it.def.id })
    assertEquals(emptyList<String>(), idsToOfferAtOnboarding(packRows(PackCatalog.all, emptyMap(), FindrSettings())))
  }

  @Test fun aPackThatIsNotByDefaultIsStillOfferedUntilItIsInstalled() {
    // The mechanism a future opt-in pack uses: the page lists it, and leaves it out once it is on the phone.
    val optIn = PackCatalog.all.map { if (it.id == PackCatalog.PRIVACY) it.copy(byDefault = false) else it }
    assertEquals(listOf(PackCatalog.PRIVACY), idsToOfferAtOnboarding(packRows(optIn, emptyMap(), FindrSettings())))
    assertEquals(emptyList<String>(), idsToOfferAtOnboarding(packRows(optIn, mapOf(PackCatalog.PRIVACY to PackState.Installed(1)), FindrSettings())))
  }

  @Test fun aDefaultPackThatIsInstalledOrDeclinedNeverShowsAtOnboarding() {
    val states = mapOf(PackCatalog.SPEECH to PackState.Installed(1), PackCatalog.QUICK_LOOK to PackState.Failed(PackProblem.NOT_FOUND))
    assertTrue(offeredAtOnboarding(packRows(PackCatalog.all, states, FindrSettings())).none { it.def.byDefault })
  }

  @Test fun aCardTheUserJustActedOnStaysToShowAdded() {
    val optIn = PackCatalog.all.map { if (it.id == PackCatalog.PRIVACY) it.copy(byDefault = false) else it }
    val ids = idsToOfferAtOnboarding(packRows(optIn, emptyMap(), FindrSettings()))   // taken when the page opens
    val after = offeredAtOnboarding(packRows(optIn, mapOf(PackCatalog.PRIVACY to PackState.Installed(1)), FindrSettings()))
      .filter { it.def.id in ids }
    assertEquals(PackStatus.INSTALLED, after.single().status)
  }

  @Test fun theFakeEngineHasNoPacksToChange() {
    assertEquals(PackCatalog.all.map { it.id }.toSet(), NoPacks.states.value.keys)
    assertNull(NoPacks.howToAdd(PackCatalog.SPEECH))
  }
}
