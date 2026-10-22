package trawl.desktop.shell

import java.util.Locale
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import trawl.desktop.Os
import trawl.desktop.Strings

class HotkeyServiceTest {
  @BeforeTest fun english() = Strings.use(Locale.ENGLISH)

  private fun svc(os: Os, native: GlobalHotkey?) = HotkeyService(os, native) {}

  // ---- fallback order ----

  @Test fun theDefaultIsUsedWhenFree() {
    val n = FakeNative()
    val s = svc(Os.MAC, n).also { it.apply(ShellPrefs()) }
    val st = assertIs<HotkeyStatus.Active>(s.status.value)
    assertEquals(Hotkey.default(Os.MAC), st.hotkey)
    assertNull(st.fellBackFrom)
    assertEquals(listOf("unreg", "reg ctrl+shift+space"), n.log)
  }

  @Test fun aTakenDefaultFallsBackInTheListedOrderAndSaysSo() {
    val n = FakeNative(taken = setOf("ctrl+shift+space"))
    val s = svc(Os.MAC, n).also { it.apply(ShellPrefs()) }
    val st = assertIs<HotkeyStatus.Active>(s.status.value)
    assertEquals("ctrl+alt+shift+space", st.hotkey.spec)
    assertEquals(Hotkey.default(Os.MAC), st.fellBackFrom)
    assertEquals(listOf("unreg", "reg ctrl+shift+space", "reg ctrl+alt+shift+space"), n.log)
  }

  @Test fun theSecondFallbackIsReachedWhenTwoAreTaken() {
    val n = FakeNative(taken = setOf("ctrl+alt+space", "ctrl+alt+shift+space"))
    val s = svc(Os.WINDOWS, n).also { it.apply(ShellPrefs()) }
    assertEquals("ctrl+alt+meta+space", assertIs<HotkeyStatus.Active>(s.status.value).hotkey.spec)
  }

  @Test fun whenEveryCandidateIsTakenNothingIsRegisteredAndTheStatusSaysSo() {
    val taken = Hotkey.candidates(Os.LINUX).map { it.spec }.toSet()
    val n = FakeNative(taken = taken)
    val s = svc(Os.LINUX, n).also { it.apply(ShellPrefs()) }
    assertEquals(HotkeyStatus.AllTaken, s.status.value)
    assertNull(s.active)
    assertNull(n.registered)
    assertEquals(Hotkey.candidates(Os.LINUX).size, n.log.count { it.startsWith("reg ") })
    assertTrue(ShortcutMessages.forStatus(s.status.value, Os.LINUX).first.contains("taken"))
  }

  @Test fun aShortcutThePersonChoseIsTriedAloneAndNeverReplaced() {
    val n = FakeNative(taken = setOf("ctrl+alt+k"))
    val s = svc(Os.WINDOWS, n).also { it.apply(ShellPrefs(hotkey = "ctrl+alt+k")) }
    assertEquals(HotkeyStatus.Taken(Hotkey.parse("ctrl+alt+k")!!), s.status.value)
    assertEquals(listOf("unreg", "reg ctrl+alt+k"), n.log)   // no fallbacks tried
    assertNull(s.active)
  }

  @Test fun offRegistersNothing() {
    val n = FakeNative()
    val s = svc(Os.MAC, n).also { it.apply(ShellPrefs(hotkeyOn = false)) }
    assertEquals(HotkeyStatus.Off, s.status.value)
    assertEquals(listOf("unreg"), n.log)
  }

  @Test fun waylandStopsTheWalkAndIsReportedAsWayland() {
    val n = FakeNative(always = HotkeyResult.Unsupported(wayland = true, detail = "Wayland"))
    val s = svc(Os.LINUX, n).also { it.apply(ShellPrefs()) }
    assertEquals(HotkeyStatus.Wayland, s.status.value)
    assertEquals(1, n.log.count { it.startsWith("reg ") })
    assertTrue(ShortcutMessages.forStatus(HotkeyStatus.Wayland, Os.LINUX).first.contains("trawl --quick"))
  }

  @Test fun aFailureIsNotTreatedAsTaken() {
    val n = FakeNative(always = HotkeyResult.Failed("no display"))
    val s = svc(Os.LINUX, n).also { it.apply(ShellPrefs()) }
    assertEquals(HotkeyStatus.Failed("no display"), s.status.value)
    assertEquals(1, n.log.count { it.startsWith("reg ") })
  }

  @Test fun aSoftOwnerIsAllowedWithAWarning() {
    val n = FakeNative()
    val s = svc(Os.MAC, n).also { it.apply(ShellPrefs(hotkey = "alt+space")) }
    val st = assertIs<HotkeyStatus.Active>(s.status.value)
    assertTrue(st.warning!!.contains("Raycast"))
  }

  // ---- the recorder's validation ----

  @Test fun theRecorderNeedsAModifierAndAKey() {
    val never: (Hotkey) -> HotkeyResult = { error("must not reach the native layer") }
    assertEquals(ShortcutCheck.NeedModifier, ShortcutRecorder.check(null, Os.MAC, never))
    assertEquals(ShortcutCheck.NeedModifier, ShortcutRecorder.check(Hotkey(setOf(Mod.SHIFT), "A"), Os.MAC, never))
    assertEquals(ShortcutCheck.NeedModifier, ShortcutRecorder.check(Hotkey(emptySet(), "SPACE"), Os.WINDOWS, never))
  }

  @Test fun theRecorderRefusesWhatTheSystemOwnsWithoutAskingTheNativeLayer() {
    val never: (Hotkey) -> HotkeyResult = { error("must not reach the native layer") }
    val c = ShortcutRecorder.check(Hotkey.parse("meta+space"), Os.MAC, never)
    assertEquals("Spotlight", assertIs<ShortcutCheck.Owned>(c).by)
    val (msg, good) = ShortcutMessages.forCheck(c, Os.MAC)
    assertFalse(good)
    assertEquals("Command+Space is used by Spotlight. Pick a different shortcut.", msg)
  }

  @Test fun theRecorderReportsATakenKeyInPlainWords() {
    val c = ShortcutRecorder.check(Hotkey.parse("ctrl+alt+j"), Os.WINDOWS) { HotkeyResult.Taken() }
    assertEquals(ShortcutCheck.Taken(Hotkey.parse("ctrl+alt+j")!!), c)
    assertEquals("Ctrl+Alt+J is already taken by another app. Pick a different shortcut.", ShortcutMessages.forCheck(c, Os.WINDOWS).first)
  }

  @Test fun theRecorderAcceptsAFreeKeyAndWarnsAboutASoftOwner() {
    val ok = ShortcutRecorder.check(Hotkey.parse("ctrl+alt+j"), Os.WINDOWS) { HotkeyResult.Ok }
    assertEquals(ShortcutCheck.Ok(Hotkey.parse("ctrl+alt+j")!!, null), ok)
    val soft = ShortcutRecorder.check(Hotkey.parse("alt+space"), Os.WINDOWS) { HotkeyResult.Ok }
    assertTrue(assertIs<ShortcutCheck.Ok>(soft).warning!!.contains("PowerToys"))
    assertTrue(ShortcutMessages.forCheck(soft, Os.WINDOWS).first.contains("PowerToys"))
  }

  @Test fun theRecorderPassesNativeFailuresThrough() {
    val c = ShortcutRecorder.check(Hotkey.parse("ctrl+alt+j"), Os.LINUX) { HotkeyResult.Failed("no display") }
    assertEquals(ShortcutCheck.Failed("no display"), c)
  }

  @Test fun aFailedTrialPutsThePreviousShortcutBack() {
    val n = FakeNative(taken = setOf("ctrl+alt+j"))
    val s = svc(Os.WINDOWS, n).also { it.apply(ShellPrefs()) }
    val before = s.active
    assertEquals(before, Hotkey.default(Os.WINDOWS))
    val r = s.probe(Hotkey.parse("ctrl+alt+j"))
    assertIs<ShortcutCheck.Taken>(r)
    assertEquals(before, s.active)
    assertEquals(before, n.registered)   // the old key is live again, not just remembered
  }

  @Test fun aSuccessfulTrialSwitchesTheLiveKeyAndTheStatus() {
    val n = FakeNative()
    val s = svc(Os.WINDOWS, n).also { it.apply(ShellPrefs()) }
    val r = s.probe(Hotkey.parse("ctrl+alt+j"))
    assertIs<ShortcutCheck.Ok>(r)
    assertEquals("ctrl+alt+j", s.active?.spec)
    assertEquals("ctrl+alt+j", n.registered?.spec)
    assertEquals("ctrl+alt+j", assertIs<HotkeyStatus.Active>(s.status.value).hotkey.spec)
  }

  @Test fun anUnusableTrialLeavesEverythingAlone() {
    val n = FakeNative()
    val s = svc(Os.MAC, n).also { it.apply(ShellPrefs()) }
    val log = n.log.toList()
    assertEquals(ShortcutCheck.NeedModifier, s.probe(Hotkey(setOf(Mod.SHIFT), "A")))
    assertEquals(log, n.log)
    assertEquals(Hotkey.default(Os.MAC), s.active)
  }

  // ---- the recorder's key reading ----

  @Test fun recorderKeysMapAwtCodes() {
    assertEquals("SPACE", RecorderKeys.keyName(0x20))
    assertEquals("A", RecorderKeys.keyName(0x41))
    assertEquals("Z", RecorderKeys.keyName(0x5A))
    assertEquals("0", RecorderKeys.keyName(0x30))
    assertEquals("F1", RecorderKeys.keyName(0x70))
    assertEquals("F12", RecorderKeys.keyName(0x7B))
    assertNull(RecorderKeys.keyName(0x0A))   // Enter
    assertNull(RecorderKeys.keyName(0x10))   // Shift itself
    assertNull(RecorderKeys.keyName(0x25))   // an arrow
    assertEquals(Hotkey(setOf(Mod.META, Mod.SHIFT), "SPACE"), RecorderKeys.fromEvent(0x20, ctrl = false, alt = false, shift = true, meta = true))
    assertNull(RecorderKeys.fromEvent(0x11, true, false, false, false))   // only Ctrl so far: keep listening
  }

  @Test fun everyKeyTheRecorderCanProduceRoundTripsThroughTheStoredForm() {
    for (code in (0x20..0x7B)) {
      val h = RecorderKeys.fromEvent(code, true, true, false, false) ?: continue
      assertEquals(h, Hotkey.parse(h.spec), "key code $code")
    }
  }
}
