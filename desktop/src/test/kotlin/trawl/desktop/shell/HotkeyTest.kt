package trawl.desktop.shell

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import trawl.desktop.Os

/** A native layer that can be told which keys are "taken", and records what it was asked. */
class FakeNative(private val taken: Set<String> = emptySet(), private val always: HotkeyResult? = null) : GlobalHotkey {
  val log = mutableListOf<String>()
  var registered: Hotkey? = null

  override fun register(h: Hotkey, onPressed: () -> Unit): HotkeyResult {
    log += "reg ${h.spec}"
    always?.let { return it }
    if (h.spec in taken) return HotkeyResult.Taken()
    registered = h
    return HotkeyResult.Ok
  }

  override fun unregister() { log += "unreg"; registered = null }
}

class HotkeyTest {
  @Test fun parseAcceptsTheStoredFormAndFriendlyNames() {
    assertEquals(Hotkey(setOf(Mod.CTRL, Mod.ALT), "SPACE"), Hotkey.parse("ctrl+alt+space"))
    assertEquals(Hotkey(setOf(Mod.META, Mod.SHIFT), "SPACE"), Hotkey.parse("Command+Shift+Space"))
    assertEquals(Hotkey(setOf(Mod.ALT), "K"), Hotkey.parse("option+k"))
    assertEquals(Hotkey(setOf(Mod.META), "F7"), Hotkey.parse("super+F7"))
    assertEquals(Hotkey(setOf(Mod.CTRL), "5"), Hotkey.parse(" ctrl + 5 "))
  }

  @Test fun parseRefusesWhatWouldStealTypingOrIsNotAKey() {
    assertNull(Hotkey.parse(null))
    assertNull(Hotkey.parse(""))
    assertNull(Hotkey.parse("space"))             // no modifier
    assertNull(Hotkey.parse("shift+a"))           // Shift alone is just a capital letter
    assertNull(Hotkey.parse("ctrl+enter"))        // not a key the three native layers all name
    assertNull(Hotkey.parse("ctrl+banana+k"))     // unknown modifier
    assertNull(Hotkey.parse("ctrl+alt+"))         // no key
    assertNull(Hotkey.parse("ctrl+f13"))
  }

  @Test fun specRoundTripsAndIsOrdered() {
    val h = Hotkey(setOf(Mod.SHIFT, Mod.META, Mod.CTRL), "SPACE")
    assertEquals("ctrl+shift+meta+space", h.spec)
    assertEquals(h, Hotkey.parse(h.spec))
    for (os in Os.entries) for (c in Hotkey.candidates(os)) assertEquals(c, Hotkey.parse(c.spec))
  }

  @Test fun labelsUseTheOsOwnWords() {
    val d = Hotkey.default(Os.MAC)
    assertEquals("Control+Shift+Space", d.label(Os.MAC))   // Apple lists modifiers Control, Option, Shift, Command
    assertEquals("Shift+Command+Space", Hotkey.parse("shift+meta+space")!!.label(Os.MAC))
    assertEquals("Ctrl+Alt+Space", Hotkey.default(Os.WINDOWS).label(Os.WINDOWS))
    assertEquals("Ctrl+Alt+Space", Hotkey.default(Os.LINUX).label(Os.LINUX))
    assertEquals("Control+Option+Shift+Command+K", Hotkey(setOf(Mod.CTRL, Mod.ALT, Mod.META, Mod.SHIFT), "K").label(Os.MAC))
    assertEquals("Alt+Windows+K", Hotkey(setOf(Mod.META, Mod.ALT), "K").label(Os.WINDOWS))
    assertEquals("Alt+Super+K", Hotkey(setOf(Mod.META, Mod.ALT), "K").label(Os.LINUX))
  }

  @Test fun defaultsAreUsableAndOwnedByNobodyWeKnowOf() {
    for (os in Os.entries) {
      val d = Hotkey.default(os)
      assertTrue(d.usable, "$os default usable")
      assertNull(Hotkey.knownOwner(d, os), "$os default must not be a shortcut the system or a mainstream launcher owns")
    }
  }

  @Test fun theMacDefaultHasNoKnownOwner() {
    assertNull(Hotkey.knownOwner(Hotkey.default(Os.MAC), Os.MAC))
    for (h in Hotkey.candidates(Os.MAC)) assertNull(Hotkey.knownOwner(h, Os.MAC), "$h")
  }

  @Test fun theDefaultIsNotOptionSpaceOrAnyOfTheKnownLauncherKeys() {
    assertNotEquals(Hotkey(setOf(Mod.ALT), "SPACE"), Hotkey.default(Os.MAC))
    assertNotEquals(Hotkey(setOf(Mod.ALT), "SPACE"), Hotkey.default(Os.WINDOWS))
    assertEquals("ctrl+shift+space", Hotkey.default(Os.MAC).spec)
    assertEquals("ctrl+alt+space", Hotkey.default(Os.WINDOWS).spec)
  }

  private fun assertNotEquals(a: Any, b: Any) = assertFalse(a == b)

  @Test fun fallbackOrderIsTheDefaultThenTheAlternatesWithNoRepeatsAndNoHardOwners() {
    for (os in Os.entries) {
      val c = Hotkey.candidates(os)
      assertEquals(Hotkey.default(os), c.first())
      assertEquals(Hotkey.alternates(os), c.drop(1))
      assertEquals(c.size, c.toSet().size, "$os: no key listed twice")
      assertTrue(c.size in 2..4, "$os: a short list, not a search")
      for (h in c) {
        assertTrue(h.usable)
        assertTrue(Hotkey.knownOwner(h, os)?.hard != true, "$os: $h is owned by the system")
      }
    }
    assertEquals(listOf("ctrl+shift+space", "ctrl+alt+shift+space", "alt+shift+meta+space"), Hotkey.candidates(Os.MAC).map { it.spec })
    assertEquals(listOf("ctrl+alt+space", "ctrl+alt+shift+space", "ctrl+alt+meta+space"), Hotkey.candidates(Os.WINDOWS).map { it.spec })
  }

  @Test fun knownOwnersCoverTheSystemKeysTheResearchFound() {
    fun owner(spec: String, os: Os) = Hotkey.knownOwner(Hotkey.parse(spec)!!, os)
    assertTrue(owner("meta+space", Os.MAC)!!.hard)                // Spotlight
    assertTrue(owner("shift+meta+space", Os.MAC)!!.name.startsWith("1Password") && !owner("shift+meta+space", Os.MAC)!!.hard)
    assertTrue(owner("meta+alt+space", Os.MAC)!!.hard)            // Finder search
    assertTrue(owner("ctrl+meta+space", Os.MAC)!!.hard)           // emoji viewer
    assertFalse(owner("alt+space", Os.MAC)!!.hard)                // Alfred, Raycast, ChatGPT, Claude: soft
    assertFalse(owner("ctrl+space", Os.MAC)!!.hard)               // input source: off unless the person turned it on
    assertFalse(owner("alt+space", Os.WINDOWS)!!.hard)            // window menu, PowerToys Run
    assertTrue(owner("meta+space", Os.WINDOWS)!!.hard)            // input language
    assertNotNull(owner("alt+space", Os.LINUX))
    assertNull(owner("ctrl+alt+space", Os.WINDOWS))
  }

  @Test fun windowsCodes() {
    val h = Hotkey.default(Os.WINDOWS)
    assertEquals(HotkeyCodes.WIN_CTRL or HotkeyCodes.WIN_ALT or HotkeyCodes.WIN_NOREPEAT, HotkeyCodes.winMods(h))
    assertEquals(0x20, HotkeyCodes.winVk("SPACE"))
    assertEquals(0x41, HotkeyCodes.winVk("A"))
    assertEquals(0x35, HotkeyCodes.winVk("5"))
    assertEquals(0x70, HotkeyCodes.winVk("F1"))
    assertEquals(0x7B, HotkeyCodes.winVk("F12"))
    assertEquals(HotkeyCodes.WIN_META or HotkeyCodes.WIN_SHIFT or HotkeyCodes.WIN_NOREPEAT, HotkeyCodes.winMods(Hotkey(setOf(Mod.META, Mod.SHIFT), "K")))
  }

  @Test fun x11Codes() {
    assertEquals(HotkeyCodes.X_CONTROL or HotkeyCodes.X_MOD1, HotkeyCodes.xMods(Hotkey.default(Os.LINUX)))
    assertEquals(HotkeyCodes.X_MOD4 or HotkeyCodes.X_SHIFT, HotkeyCodes.xMods(Hotkey(setOf(Mod.META, Mod.SHIFT), "K")))
    assertEquals(0x20L, HotkeyCodes.xKeysym("SPACE"))
    assertEquals('a'.code.toLong(), HotkeyCodes.xKeysym("A"))
    assertEquals('7'.code.toLong(), HotkeyCodes.xKeysym("7"))
    assertEquals(0xFFBEL, HotkeyCodes.xKeysym("F1"))
    assertEquals(0xFFC9L, HotkeyCodes.xKeysym("F12"))
    // Caps Lock and Num Lock must not break the grab.
    assertEquals(setOf(0, 2, 0x10, 0x12), HotkeyCodes.xLockVariants().toSet())
  }

  @Test fun macCodes() {
    assertEquals(HotkeyCodes.MAC_CONTROL or HotkeyCodes.MAC_SHIFT, HotkeyCodes.macMods(Hotkey.default(Os.MAC)))
    assertEquals(256 or 512 or 2048 or 4096, HotkeyCodes.macMods(Hotkey(setOf(Mod.META, Mod.SHIFT, Mod.ALT, Mod.CTRL), "K")))
    assertEquals(49, HotkeyCodes.macKeyCode("SPACE"))
    assertEquals(0, HotkeyCodes.macKeyCode("A"))
    assertEquals(40, HotkeyCodes.macKeyCode("K"))
    assertEquals(18, HotkeyCodes.macKeyCode("1"))
    assertEquals(29, HotkeyCodes.macKeyCode("0"))
    assertEquals(122, HotkeyCodes.macKeyCode("F1"))
    assertEquals(111, HotkeyCodes.macKeyCode("F12"))
    // Every key a shortcut may use has a distinct code on every OS.
    for (codes in listOf(Hotkey.KEYS.map(HotkeyCodes::macKeyCode), Hotkey.KEYS.map(HotkeyCodes::winVk), Hotkey.KEYS.map(HotkeyCodes::xKeysym))) {
      assertEquals(Hotkey.KEYS.size, codes.toSet().size)
    }
  }
}
