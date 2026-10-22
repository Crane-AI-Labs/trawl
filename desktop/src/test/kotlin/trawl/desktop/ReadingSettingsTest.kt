package trawl.desktop

import com.craneai.findr.core.FileKind
import com.craneai.findr.core.LearningSpeed
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The settings file for "what to read", skipped folders, work speed, pause and language; and the pure rules behind them. */
class ReadingSettingsTest {
  private fun tmp() = Files.createTempDirectory("trawl-settings").toFile().canonicalFile

  @Test fun anOldSettingsFileLoadsUnchangedWithDefaultsForTheNewFields() {
    val dir = tmp()
    // Exactly what v1.0 wrote: no field for any of the new options.
    File(dir, "settings.json").writeText("""{"onboarded":true,"folders":["/a/b","/c"],"theme":"dark","modelsDeclined":true}""")
    val s = SettingsStore(dir).load()
    assertEquals(Settings(onboarded = true, folders = listOf("/a/b", "/c"), theme = ThemeChoice.DARK, modelsDeclined = true), s)
    assertTrue(s.indexPhotos && s.indexScreenshots && s.indexDocuments && s.indexVideos && s.indexAudio)
    assertEquals(emptyList(), s.excludedFolders)
    assertEquals(LearningSpeed.BALANCED, s.learningSpeed)
    assertFalse(s.indexPaused)
    assertNull(s.language)
    dir.deleteRecursively()
  }

  @Test fun everyNewFieldSurvivesASaveAndALoad() {
    val dir = tmp()
    val s = Settings(
      onboarded = true, folders = listOf("/x"), theme = ThemeChoice.LIGHT, indexPhotos = false, indexScreenshots = true, indexDocuments = false, indexVideos = false, indexAudio = true,
      excludedFolders = listOf("/x/private"), learningSpeed = LearningSpeed.FAST, indexPaused = true, language = "lg",
    )
    SettingsStore(dir).save(s)
    assertEquals(s, SettingsStore(dir).load())
    assertFalse(File(dir, "settings.json.part").exists(), "the temp file is renamed away")
    dir.deleteRecursively()
  }

  @Test fun anUnknownSpeedOrAnEmptyLanguageFallsBackToTheDefaults() {
    val dir = tmp()
    File(dir, "settings.json").writeText("""{"onboarded":true,"learningSpeed":"turbo","language":"","excludedFolders":["", "/q"]}""")
    val s = SettingsStore(dir).load()
    assertEquals(LearningSpeed.BALANCED, s.learningSpeed)
    assertNull(s.language)
    assertEquals(listOf("/q"), s.excludedFolders)
    dir.deleteRecursively()
  }

  @Test fun aBrokenFileStartsFromFirstRun() {
    val dir = tmp()
    File(dir, "settings.json").writeText("{ not json")
    assertEquals(Settings(), SettingsStore(dir).load())
    dir.deleteRecursively()
  }

  // ---- what to read ----------------------------------------------------------------------------------------------

  @Test fun documentsCoverPdfsAndEachSwitchTurnsOffItsOwnKind() {
    val media = setOf(FileKind.VIDEO, FileKind.AUDIO)
    assertEquals(setOf(FileKind.IMAGE, FileKind.SCREENSHOT, FileKind.DOCUMENT, FileKind.PDF) + media, Scope.enabledKinds(Settings()))
    assertEquals(setOf(FileKind.SCREENSHOT, FileKind.DOCUMENT, FileKind.PDF) + media, Scope.enabledKinds(Settings(indexPhotos = false)))
    assertEquals(setOf(FileKind.IMAGE, FileKind.DOCUMENT, FileKind.PDF) + media, Scope.enabledKinds(Settings(indexScreenshots = false)))
    assertEquals(setOf(FileKind.IMAGE, FileKind.SCREENSHOT) + media, Scope.enabledKinds(Settings(indexDocuments = false)))
    assertEquals(setOf(FileKind.IMAGE, FileKind.SCREENSHOT, FileKind.DOCUMENT, FileKind.PDF, FileKind.AUDIO), Scope.enabledKinds(Settings(indexVideos = false)))
    assertEquals(setOf(FileKind.IMAGE, FileKind.SCREENSHOT, FileKind.DOCUMENT, FileKind.PDF, FileKind.VIDEO), Scope.enabledKinds(Settings(indexAudio = false)))
    assertEquals(emptySet(), Scope.enabledKinds(Settings(indexPhotos = false, indexScreenshots = false, indexDocuments = false, indexVideos = false, indexAudio = false)))
  }

  // ---- skipped folders -------------------------------------------------------------------------------------------

  @Test fun aSkippedFolderTakesEverythingInsideItWithIt() {
    val skipped = Scope.paths(listOf("/home/me/Docs/private"))
    val p = { s: String -> File(s).toPath() }
    assertTrue(Scope.isSkipped(p("/home/me/Docs/private"), skipped))
    assertTrue(Scope.isSkipped(p("/home/me/Docs/private/deep/er/a.txt"), skipped))
    assertFalse(Scope.isSkipped(p("/home/me/Docs/private2/a.txt"), skipped), "a sibling that only shares the start of the name is not skipped")
    assertFalse(Scope.isSkipped(p("/home/me/Docs/a.txt"), skipped))
    assertFalse(Scope.isSkipped(p("/home/me/Docs/x/../private/../a.txt"), skipped))
    assertTrue(Scope.isSkipped(p("/home/me/Docs/x/../private/a.txt"), skipped), "dots are resolved before the check")
    assertFalse(Scope.isSkipped(p("/anything"), emptyList()))
  }

  @Test fun onlyAFolderInsideAChosenFolderCanBeSkipped() {
    val root = tmp(); val inside = File(root, "keep/out").apply { mkdirs() }; val elsewhere = tmp(); val file = File(root, "a.txt").apply { writeText("x") }
    val chosen = listOf(root.absolutePath)
    assertNull(Scope.skipProblem(inside, chosen))
    assertEquals(Scope.SkipProblem.IS_CHOSEN_FOLDER, Scope.skipProblem(root, chosen))
    assertEquals(Scope.SkipProblem.NOT_INSIDE, Scope.skipProblem(elsewhere, chosen))
    assertEquals(Scope.SkipProblem.NOT_A_FOLDER, Scope.skipProblem(file, chosen))
    assertEquals(Scope.SkipProblem.NOT_A_FOLDER, Scope.skipProblem(File(root, "nope"), chosen))
    root.deleteRecursively(); elsewhere.deleteRecursively()
  }

  @Test fun theSkipListNeverHoldsAFolderThatAnotherEntryCovers() {
    val a = "/r/one"; val inner = "/r/one/two"; val other = "/r/three"
    assertEquals(listOf(a), Scope.withSkipped(listOf(a), inner), "already covered by the parent")
    assertEquals(listOf(a), Scope.withSkipped(listOf(inner), a), "the parent replaces what it covers")
    assertEquals(listOf(a, other), Scope.withSkipped(listOf(a), other))
    assertEquals(listOf(a), Scope.withSkipped(listOf(a), a), "adding twice changes nothing")
  }

  @Test fun removingAChosenFolderDropsItsSkipEntries() {
    val kept = Scope.pruneSkipped(listOf("/r/one/x", "/s/two/y", "/r/one"), chosen = listOf("/r/one"))
    assertEquals(listOf("/r/one/x"), kept, "/s/two/y is outside every chosen folder; a skip entry equal to a chosen folder is not a skip")
  }

  // ---- work speed ------------------------------------------------------------------------------------------------

  @Test fun theSpeedsMapToThreadsAndOnlyGentleWaitsForTheCharger() {
    assertEquals(2, WorkSpeed.threads(LearningSpeed.GENTLE, cores = 8))
    assertEquals(1, WorkSpeed.threads(LearningSpeed.GENTLE, cores = 2))
    assertEquals(1, WorkSpeed.threads(LearningSpeed.GENTLE, cores = 1))
    assertNull(WorkSpeed.threads(LearningSpeed.BALANCED, cores = 8), "Balanced leaves the thread count to the engine: today's behaviour")
    assertEquals(4, WorkSpeed.threads(LearningSpeed.FAST, cores = 8), "half the cores: more threads than that measured slower")
    assertEquals(8, WorkSpeed.threads(LearningSpeed.FAST, cores = 16))
    assertEquals(4, WorkSpeed.threads(LearningSpeed.FAST, cores = 4))
    assertEquals(2, WorkSpeed.threads(LearningSpeed.FAST, cores = 2), "never more threads than cores")
    assertTrue(WorkSpeed.waitsForCharger(LearningSpeed.GENTLE))
    assertFalse(WorkSpeed.waitsForCharger(LearningSpeed.BALANCED))
    assertFalse(WorkSpeed.waitsForCharger(LearningSpeed.FAST))
  }

  // ---- battery ---------------------------------------------------------------------------------------------------

  private fun supply(root: File, name: String, vararg kv: Pair<String, String>) {
    val d = File(root, name).apply { mkdirs() }
    kv.forEach { (k, v) -> File(d, k).writeText("$v\n") }
  }

  @Test fun linuxPowerSupplies() {
    val laptopOnBattery = tmp().also {
      supply(it, "BAT0", "type" to "Battery", "status" to "Discharging")
      supply(it, "AC", "type" to "Mains", "online" to "0")
    }
    assertEquals(true, Power.parseLinux(laptopOnBattery))
    val laptopPlugged = tmp().also {
      supply(it, "BAT0", "type" to "Battery", "status" to "Charging")
      supply(it, "AC", "type" to "Mains", "online" to "1")
    }
    assertEquals(false, Power.parseLinux(laptopPlugged))
    val desktop = tmp().also { supply(it, "AC", "type" to "Mains", "online" to "1") }
    assertNull(Power.parseLinux(desktop), "no battery at all: nothing to wait for")
    val mouse = tmp().also { supply(it, "hidpp_battery_0", "type" to "Battery", "scope" to "Device", "status" to "Discharging") }
    assertNull(Power.parseLinux(mouse), "a wireless mouse's battery is not the computer's")
    assertNull(Power.parseLinux(File("/definitely/not/here")))
    listOf(laptopOnBattery, laptopPlugged, desktop, mouse).forEach { it.deleteRecursively() }
  }

  @Test fun macAndWindowsPowerAnswers() {
    assertEquals(true, Power.parsePmset("Now drawing from 'Battery Power'\n -InternalBattery-0 (id=1)\t83%; discharging; 4:10 remaining"))
    assertEquals(false, Power.parsePmset("Now drawing from 'AC Power'\n -InternalBattery-0 (id=1)\t83%; charging"))
    assertNull(Power.parsePmset("something else"))
    assertNull(Power.parsePmset(null))
    assertEquals(true, Power.parseWindows(0))
    assertEquals(false, Power.parseWindows(1))
    assertNull(Power.parseWindows(255))
    assertNull(Power.parseWindows(null))
  }

  @Test fun theBatteryAnswerIsRememberedForAWhileThenAskedAgain() {
    var clock = 0L; var asked = 0; var answer: Boolean? = true
    val w = BatteryWatch(ttlMs = 1000, ask = { asked++; answer }, now = { clock })
    assertEquals(true, w.onBattery()); assertEquals(true, w.onBattery()); assertEquals(1, asked)
    answer = false; clock = 999
    assertEquals(true, w.onBattery(), "still inside the window")
    clock = 1000
    assertEquals(false, w.onBattery()); assertEquals(2, asked)
  }
}
