package com.craneai.findr.index

import com.craneai.findr.core.FileKind
import com.craneai.findr.core.FindrSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** "What should Findr search?": which group a file is in, and how the switches, added folders and private places combine. */
class FolderGroupsTest {

  private fun group(path: String, kind: FileKind = FileKind.IMAGE) = FolderGroups.groupOf(path, kind).id

  @Test fun realPhonePathsLandInTheirGroup() {
    assertEquals(FolderGroups.CAMERA, group("DCIM/Camera/"))
    assertEquals(FolderGroups.CAMERA, group("DCIM/Camera/", FileKind.VIDEO))
    assertEquals(FolderGroups.SCREENSHOTS, group("DCIM/Screenshots/", FileKind.SCREENSHOT))
    assertEquals(FolderGroups.SCREENSHOTS, group("Pictures/Screenshots/", FileKind.SCREENSHOT))
    assertEquals(FolderGroups.DOWNLOADS, group("Download/", FileKind.PDF))
    assertEquals(FolderGroups.DOWNLOADS, group("Download/Browser/", FileKind.IMAGE))
    assertEquals(FolderGroups.DOCUMENTS, group("Documents/", FileKind.DOCUMENT))
    assertEquals(FolderGroups.DOCUMENTS, group("Pictures/", FileKind.PDF))        // a PDF anywhere else is still a document
    assertEquals(FolderGroups.MUSIC, group("Music/Album/", FileKind.AUDIO))
    assertEquals(FolderGroups.MUSIC, group("Recordings/Voice Recorder/", FileKind.AUDIO))
    assertEquals(FolderGroups.MUSIC, group("Android/media/com.sec.android.app.voicerecorder/", FileKind.AUDIO))
    assertEquals(FolderGroups.OTHER, group("Pictures/Instagram/"))
    assertEquals(FolderGroups.OTHER, group("", FileKind.OTHER))
  }

  @Test fun whatsAppIsSplitByWhatTheFileIsOnBothOldAndNewLayouts() {
    val newLayout = "Android/media/com.whatsapp/WhatsApp/Media/"
    assertEquals(FolderGroups.WHATSAPP_IMAGES, group("${newLayout}WhatsApp Images/Sent/"))
    assertEquals(FolderGroups.WHATSAPP_VIDEO, group("${newLayout}WhatsApp Video/", FileKind.VIDEO))
    assertEquals(FolderGroups.WHATSAPP_VOICE, group("${newLayout}WhatsApp Voice Notes/202641/", FileKind.AUDIO))
    assertEquals(FolderGroups.WHATSAPP_DOCUMENTS, group("${newLayout}WhatsApp Documents/", FileKind.PDF))
    assertEquals(FolderGroups.WHATSAPP_STATUS, group("${newLayout}.Statuses/", FileKind.IMAGE))
    // Older phones, and WhatsApp Business.
    assertEquals(FolderGroups.WHATSAPP_IMAGES, group("Pictures/WhatsApp Images/"))
    assertEquals(FolderGroups.WHATSAPP_DOCUMENTS, group("Download/WhatsApp Documents/", FileKind.PDF))
    assertEquals(FolderGroups.WHATSAPP_IMAGES, group("Android/media/com.whatsapp.w4b/WhatsApp Business/Media/WhatsApp Business Images/"))
  }

  @Test fun everyGroupHasAnIdAndOtherCatchesTheRest() {
    assertEquals(FolderGroups.ALL.size, FolderGroups.ALL.map { it.id }.toSet().size)
    assertTrue(FolderGroups.ALL.last().id == FolderGroups.OTHER)
    assertTrue(FolderGroups.byId(FolderGroups.OTHER)!!.rules.isEmpty())
  }

  // ---- the switches --------------------------------------------------------------------------------------------

  private fun skip(
    path: String, name: String = "a.jpg", kind: FileKind = FileKind.IMAGE, scope: FolderScope = FolderScope(),
    excluded: Set<String> = emptySet(), noMedia: Boolean = false,
  ) = Rules.skipReason(path, name, kind, 200_000, 1200, 900, excluded, noMedia, scope)

  @Test fun withNoChoicesEverythingTodayIsStillRead() {
    // The migration promise: an install that never saw this feature reads what it always read.
    for ((path, kind) in listOf(
      "DCIM/Camera/" to FileKind.IMAGE, "DCIM/Screenshots/" to FileKind.SCREENSHOT, "Download/" to FileKind.PDF,
      "Documents/" to FileKind.DOCUMENT, "Music/" to FileKind.AUDIO, "Pictures/Instagram/" to FileKind.IMAGE,
      "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Voice Notes/" to FileKind.AUDIO,
    )) assertNull(path, skip(path, "x.bin", kind, FolderScope()))
  }

  @Test fun aSwitchedOffGroupSkipsOnlyItsOwnFiles() {
    val off = FolderScope(mapOf(FolderGroups.CAMERA to false))
    assertEquals("turned off: camera", skip("DCIM/Camera/", scope = off))
    assertNull(skip("DCIM/Screenshots/", "s.png", FileKind.SCREENSHOT, scope = off))
    assertNull(skip("Download/", "r.pdf", FileKind.PDF, scope = off))
  }

  @Test fun whatsAppGroupsSwitchIndependently() {
    val base = "Android/media/com.whatsapp/WhatsApp/Media/"
    val noVoice = FolderScope(mapOf(FolderGroups.WHATSAPP_VOICE to false))
    assertEquals("turned off: whatsapp_voice", skip("${base}WhatsApp Voice Notes/", "n.opus", FileKind.AUDIO, noVoice))
    assertNull(skip("${base}WhatsApp Images/", "i.jpg", FileKind.IMAGE, noVoice))
    assertNull(skip("${base}WhatsApp Documents/", "d.pdf", FileKind.PDF, noVoice))
  }

  @Test fun anAddedFolderIsReadEvenWhenItsGroupIsOff() {
    val scope = FolderScope(mapOf(FolderGroups.DOWNLOADS to false), includedFolders = setOf("Download/Receipts"))
    assertEquals("turned off: downloads", skip("Download/", "a.pdf", FileKind.PDF, scope))
    assertNull(skip("Download/Receipts/2026/", "b.pdf", FileKind.PDF, scope))
    assertEquals("turned off: downloads", skip("Download/Receipts2/", "c.pdf", FileKind.PDF, scope))   // a prefix of the name is not the folder
  }

  @Test fun anExcludedFolderBeatsAnAddedFolderAndEverySwitch() {
    val scope = FolderScope(includedFolders = setOf("DCIM/Camera/Trip"))
    assertEquals("excluded folder", skip("DCIM/Camera/Trip/", scope = scope, excluded = setOf("DCIM/Camera")))
  }

  @Test fun privatePlacesStayOutWhateverTheSwitchesAndAddedFoldersSay() {
    val everything = FolderScope.EVERYTHING.copy(includedFolders = setOf("DCIM", "Android", "Pictures"))
    assertTrue(skip("Android/data/com.x/files/", scope = everything)!!.contains("app-private"))
    assertTrue(skip("Android/obb/com.x/", scope = everything)!!.contains("app-private"))
    assertEquals("hidden folder", skip("Pictures/.private/", scope = everything))
    assertEquals("hidden folder", skip("DCIM/.hidden-album/", scope = everything))
    assertEquals(".nomedia folder", skip("Pictures/Trip/", scope = everything, noMedia = true))
    assertEquals("hidden file", skip("Pictures/", ".secret.jpg", scope = everything))
    assertEquals("trashed", skip("Pictures/.trashed-1700000000/", scope = everything))
  }

  @Test fun vaultAndSecureFolderAppsAreNeverRead() {
    for (folder in listOf("Secure Folder", "SecureFolder", "Vault", "Gallery Vault", "Hide It Pro", "Keepsafe", "Private Mode")) {
      assertEquals(folder, "private folder", skip("Pictures/$folder/", scope = FolderScope.EVERYTHING))
    }
  }

  @Test fun whatsAppStatusIsOffUntilAskedForAndIsTheOnlyHiddenFolderASwitchOpens() {
    val status = "Android/media/com.whatsapp/WhatsApp/Media/.Statuses/"
    assertEquals("WhatsApp status", skip(status))                                                    // default: off, as before
    assertNull(skip(status, scope = FolderScope(mapOf(FolderGroups.WHATSAPP_STATUS to true))))
    // Turning Status on does not open any other hidden folder.
    assertEquals("hidden folder", skip("Pictures/.private/", scope = FolderScope(mapOf(FolderGroups.WHATSAPP_STATUS to true))))
  }

  @Test fun scopeChoicesComeFromSettingsAndTheEditHelpersKeepTheListsConsistent() {
    var s = FindrSettings()
    assertTrue(s.folderScope().isOn(FolderGroups.CAMERA))
    assertFalse(s.folderScope().isOn(FolderGroups.WHATSAPP_STATUS))
    s = s.withGroup(FolderGroups.CAMERA, false)
    assertFalse(s.folderScope().isOn(FolderGroups.CAMERA))
    assertTrue(s.folderScope().isOn(FolderGroups.SCREENSHOTS))
    // Adding a folder that was excluded un-excludes it, and excluding an added folder drops it from the added list.
    s = s.withExcludedFolder("Pictures/Trip").withIncludedFolder("/Pictures/Trip/")
    assertEquals(setOf("Pictures/Trip"), s.includedFolders)
    assertTrue(s.excludedFolders.isEmpty())
    s = s.withExcludedFolder("Pictures/Trip")
    assertTrue(s.includedFolders.isEmpty())
    assertEquals(setOf("Pictures/Trip"), s.excludedFolders)
    assertEquals(s, s.withIncludedFolder(""))
    assertTrue(s.withoutExcludedFolder("Pictures/Trip").excludedFolders.isEmpty())
  }

  @Test fun anUnknownGroupIdIsOnSoANewerSettingsFileNeverHidesFiles() {
    assertTrue(FolderScope(mapOf("from_the_future" to false)).isOn("from_the_future"))
  }
}
