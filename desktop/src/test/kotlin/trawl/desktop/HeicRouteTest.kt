package trawl.desktop

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import java.io.File
import java.util.Locale
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import trawl.desktop.ui.SunlightTheme
import trawl.desktop.ui.settings.HeicRouteNote

/** B15: Settings says so when this computer has no way to open HEIC, HEIF or AVIF, and says what to install. */
@OptIn(ExperimentalTestApi::class)
class HeicRouteTest {
  @BeforeTest fun english() = Strings.use(Locale.ENGLISH)

  private val noOutput: (List<String>) -> String? = { null }

  @Test fun aMacAlwaysHasOne() = assertTrue(HeicRoute.available(Os.MAC, { false }, noOutput))

  @Test fun linuxNeedsHeifConvertOrAnImageMagickBuiltWithHeic() {
    assertTrue(HeicRoute.available(Os.LINUX, { it == "heif-convert" }, noOutput))
    val withHeic = "Format  Module    Mode  Description\n  HEIC* HEIC      rw+   Apple High efficiency Image Format\n   JPEG* JPEG      rw-   Joint Photographic\n"
    assertTrue(HeicRoute.available(Os.LINUX, { it == "convert" }, { withHeic }))
    val without = "Format  Module    Mode  Description\n   JPEG* JPEG      rw-   Joint Photographic\n    PNG* PNG        rw-   Portable\n"
    assertFalse(HeicRoute.available(Os.LINUX, { it == "convert" }, { without }), "ImageMagick without libheif cannot open it")
    assertFalse(HeicRoute.available(Os.LINUX, { false }, noOutput))
    assertFalse(HeicRoute.magickReadsHeic("  HEIC  HEIC      -w+   write only"), "a write-only HEIC entry does not read")
  }

  @Test fun windowsAsksForTheHeifExtensionAndOnlyAYesCounts() {
    assertTrue(HeicRoute.windowsProbe().last().contains("Microsoft.HEIFImageExtension"))
    assertTrue(HeicRoute.available(Os.WINDOWS, { false }, { "yes\r\n" }))
    assertFalse(HeicRoute.available(Os.WINDOWS, { false }, { "no\r\n" }))
    assertFalse(HeicRoute.available(Os.WINDOWS, { false }, noOutput), "PowerShell did not answer: say there is none rather than guess")
  }

  @Test fun pathLookupFindsAProgramInOneOfTheFolders() {
    val here = setOf(File("/usr/bin/heif-convert"))
    assertTrue(HeicRoute.onPath("heif-convert", "/opt/x:/usr/bin", isFile = { it in here }))
    assertFalse(HeicRoute.onPath("heif-convert", "/opt/x", isFile = { it in here }))
    assertFalse(HeicRoute.onPath("heif-convert", null, isFile = { true }))
  }

  private fun note(photosOn: Boolean, os: Os, present: Boolean, body: androidx.compose.ui.test.ComposeUiTest.() -> Unit) = runComposeUiTest {
    setContent { SunlightTheme { HeicRouteNote(photosOn, os, present = { present }) } }
    repeat(30) { Thread.sleep(30); mainClock.advanceTimeByFrame() }   // the answer comes back from the IO pool onto the test clock
    body()
  }

  @Test fun noRouteShowsTheLineForTheOs() {
    note(true, Os.LINUX, present = false) { onNodeWithText("libheif", substring = true).assertExists() }
    note(true, Os.WINDOWS, present = false) { onNodeWithText("HEIF Image Extensions", substring = true).assertExists() }
  }

  @Test fun noLineWhenThereIsARouteOrPhotosAreOff() {
    note(true, Os.LINUX, present = true) { onNodeWithText("libheif", substring = true).assertDoesNotExist() }
    note(false, Os.LINUX, present = false) { onNodeWithText("libheif", substring = true).assertDoesNotExist() }
  }

  @Test fun theSameStringsOnTheRealPathAreInTheEnglishFile() {
    assertEquals(true, t("settings_heic_missing_linux").startsWith("This computer cannot open HEIC"))
  }
}
