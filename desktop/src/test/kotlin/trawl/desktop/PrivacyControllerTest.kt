package trawl.desktop

import com.craneai.findr.core.FileKind
import com.craneai.findr.core.IndexedFile
import com.craneai.findr.index.PrivacyRules
import com.craneai.findr.privacy.PrivacyGate
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import trawl.desktop.privacy.AttemptLimiter
import trawl.desktop.privacy.Later
import trawl.desktop.privacy.OsAvailability
import trawl.desktop.privacy.OsOutcome
import trawl.desktop.privacy.OsPrompt
import trawl.desktop.privacy.OsUnlock
import trawl.desktop.privacy.OwnWindowFocus
import trawl.desktop.privacy.PassResult
import trawl.desktop.privacy.Passphrase
import trawl.desktop.privacy.PrivacyController
import trawl.desktop.privacy.PrivacyController.ChangeResult
import trawl.desktop.privacy.PrivacyIndex
import trawl.desktop.privacy.PrivacySettings
import trawl.desktop.privacy.PrivacyStore
import trawl.desktop.privacy.Purpose

/** The lock rules as the desktop drives them: focus, sleep, idle, the prompt in flight, and who may turn the lock off. */
class PrivacyControllerTest {
  private class Clock { var ms = 1_000_000L }

  private class FakeLater : Later {
    class Job(val at: Long, val block: () -> Unit, var live: Boolean = true)
    val jobs = ArrayList<Job>()
    var clock: Clock? = null
    override fun after(ms: Long, block: () -> Unit): () -> Unit {
      val j = Job((clock?.ms ?: 0) + ms, block)
      jobs += j
      return { j.live = false }
    }
    /** Runs whatever is due at the clock's current time. */
    fun runDue() {
      val now = clock!!.ms
      jobs.filter { it.live && it.at <= now }.forEach { it.live = false; it.block() }
    }
  }

  private class FakeOs(var avail: OsAvailability = OsAvailability.Available(OsPrompt.TOUCH_ID), var answer: OsOutcome = OsOutcome.OK) : OsUnlock {
    var prompts = 0
    override fun availability() = avail
    override fun authenticate(reason: String): OsOutcome { prompts++; return answer }
  }

  private class FakeIndex(val ids: Set<Long>) : PrivacyIndex {
    var rules = PrivacyRules.NONE
    var learned: PrivacyRules? = null
    override fun applyRules(rules: PrivacyRules): Int { this.rules = rules; return 0 }
    override fun setMarked(fileId: Long, on: Boolean): PrivacyRules? {
      if (fileId !in ids) return null
      val key = "f$fileId"
      rules = rules.copy(markedFiles = if (on) rules.markedFiles + key else rules.markedFiles - key)
      return rules
    }
    override fun takeLearnedRules(): PrivacyRules? = learned.also { learned = null }
    override fun isSensitive(fileId: Long) = "f$fileId" in rules.markedFiles
    override fun sensitiveCount() = rules.markedFiles.size
    override fun nextForPrivateScore(limit: Int, kinds: Set<FileKind>): List<IndexedFile> = emptyList()
    override fun saveLooksPrivate(fileId: Long, score: Float?) {}
    override fun ensurePrivateModel(model: String) = false
    override fun clearLooksPrivate() = 0
    override fun privateScoreCounts() = 0 to 0
  }

  private class Rig(val dir: File = Files.createTempDirectory("pc").toFile().also { it.deleteOnExit() }) {
    val clock = Clock()
    var wall = 5_000_000L
    val later = FakeLater().also { it.clock = clock }
    val os = FakeOs()
    val limiter = AttemptLimiter { clock.ms }
    fun controller() = PrivacyController(PrivacyStore(dir), os, { clock.ms }, { wall }, later, limiter, passphraseIterations = 1000)
      .also { it.refreshOs() }
    fun advance(ms: Long) { clock.ms += ms; wall += ms }
  }

  private val pass = "correct horse battery"
  private fun p() = pass.toCharArray()

  @Test fun `with no lock a new process is not locked and sensitive results are hidden`() {
    val c = Rig().controller()
    assertFalse(c.locked.value)
    assertFalse(c.revealed.value)
  }

  @Test fun `turning the lock on stores a hash, and a new process starts locked`() {
    val rig = Rig()
    val a = rig.controller()
    assertEquals(ChangeResult.OK, a.enableLock(p()))
    assertFalse(a.locked.value, "just enabled by someone who is looking at it")
    val b = rig.controller()
    assertTrue(b.locked.value, "a new process always starts locked")
    assertFalse(File(rig.dir, "privacy.json").readText().contains(pass))
  }

  @Test fun `a weak or blank passphrase is refused and the lock stays off`() {
    val c = Rig().controller()
    assertEquals(ChangeResult.WEAK, c.enableLock("short".toCharArray()))
    assertEquals(ChangeResult.BLANK, c.enableLock("            ".toCharArray()))
    assertFalse(c.settings.value.lockEnabled)
  }

  @Test fun `the passphrase unlocks and a wrong one does not`() {
    val rig = Rig()
    rig.controller().enableLock(p())
    val c = rig.controller()
    assertEquals(PassResult.Wrong, c.authWithPassphrase(Purpose.UNLOCK, "wrong wrong wrong".toCharArray()))
    assertTrue(c.locked.value)
    assertEquals(PassResult.Ok, c.authWithPassphrase(Purpose.UNLOCK, p()))
    assertFalse(c.locked.value)
  }

  @Test fun `guessing is slowed after three misses, even for the right passphrase`() {
    val rig = Rig()
    rig.controller().enableLock(p())
    val c = rig.controller()
    repeat(3) { assertEquals(PassResult.Wrong, c.authWithPassphrase(Purpose.UNLOCK, "nope nope nope".toCharArray())) }
    assertTrue(c.authWithPassphrase(Purpose.UNLOCK, "nope nope nope".toCharArray()) is PassResult.Wait)
    assertTrue(c.authWithPassphrase(Purpose.UNLOCK, p()) is PassResult.Wait)
    assertTrue(c.locked.value)
    rig.advance(3_000)
    assertEquals(PassResult.Ok, c.authWithPassphrase(Purpose.UNLOCK, p()))
  }

  @Test fun `the system prompt unlocks, a denial does not`() {
    val rig = Rig()
    rig.controller().enableLock(p())
    val c = rig.controller()
    rig.os.answer = OsOutcome.DENIED
    assertEquals(OsOutcome.DENIED, c.authWithOs(Purpose.UNLOCK, "r"))
    assertTrue(c.locked.value)
    rig.os.answer = OsOutcome.OK
    assertEquals(OsOutcome.OK, c.authWithOs(Purpose.UNLOCK, "r"))
    assertFalse(c.locked.value)
  }

  @Test fun `with the system prompt switched off or missing it is never started`() {
    val rig = Rig()
    rig.controller().enableLock(p(), useOsPrompt = false)
    val c = rig.controller()
    assertEquals(OsOutcome.UNAVAILABLE, c.authWithOs(Purpose.UNLOCK, "r"))
    assertEquals(0, rig.os.prompts)
    assertFalse(c.canAutoPrompt())
    rig.os.avail = OsAvailability.Unavailable("none")
    val d = rig.controller()
    assertNull(d.authMethods().os)
    assertTrue(d.authMethods().passphrase)
  }

  @Test fun `no second prompt starts over one in flight`() {
    val rig = Rig()
    rig.controller().enableLock(p())
    lateinit var c: PrivacyController
    var inner: OsOutcome? = null
    val os = object : OsUnlock {
      override fun availability() = OsAvailability.Available(OsPrompt.TOUCH_ID)
      override fun authenticate(reason: String): OsOutcome { inner = c.authWithOs(Purpose.UNLOCK, "again"); return OsOutcome.DENIED }
    }
    c = PrivacyController(PrivacyStore(rig.dir), os, { rig.clock.ms }, { rig.wall }, rig.later, rig.limiter, passphraseIterations = 1000).also { it.refreshOs() }
    c.authWithOs(Purpose.UNLOCK, "first")
    assertEquals(OsOutcome.CANCELLED, inner)
  }

  @Test fun `leaving for a moment does not lock, leaving past the timeout does`() {
    val rig = Rig()
    rig.controller().enableLock(p())
    val c = rig.controller()
    c.authWithPassphrase(Purpose.UNLOCK, p())
    c.onWindowFocus("main", true)
    // a dialog steals the focus for a blink
    c.onWindowFocus("main", false)
    rig.advance(100); c.onWindowFocus("main", true)
    rig.advance(1_000); rig.later.runDue()
    assertFalse(c.locked.value)
    // really leaving, for longer than five minutes
    c.onWindowFocus("main", false)
    rig.advance(500); rig.later.runDue()
    rig.advance(6 * 60_000L)
    c.onWindowFocus("main", true)
    assertTrue(c.locked.value)
  }

  @Test fun `lock after zero locks at every return to Trawl`() {
    val rig = Rig()
    val a = rig.controller()
    a.enableLock(p())
    a.setLockAfterMinutes(0)
    val c = rig.controller()
    c.authWithPassphrase(Purpose.UNLOCK, p())
    c.onWindowFocus("main", true)
    c.onWindowFocus("main", false)
    rig.advance(500); rig.later.runDue()
    rig.advance(2_000)
    c.onWindowFocus("main", true)
    assertTrue(c.locked.value)
  }

  @Test fun `Trawls own dialog taking the keyboard is not leaving, however long it stays open`() {
    val rig = Rig()
    rig.controller().also { it.enableLock(p()); it.setLockAfterMinutes(0) }   // zero: locks at every return, the strictest setting
    val c = rig.controller()
    c.authWithPassphrase(Purpose.UNLOCK, p())
    val dialog = Any()
    c.onWindowFocus("main", true)
    OwnWindowFocus.report(c, Any())             // AWT: a window of this process has the focus
    // the folder chooser opens: AWT says no window for an instant, then the dialog; the main window reports its loss
    OwnWindowFocus.report(c, null)
    c.onWindowFocus("main", false)
    OwnWindowFocus.report(c, dialog)
    rig.advance(30_000); rig.later.runDue()      // the person browses folders for half a minute
    rig.advance(60_000); rig.later.runDue()
    assertFalse(c.locked.value, "a dialog of ours is not leaving")
    // the dialog closes and the main window gets the focus back
    OwnWindowFocus.report(c, null)
    c.onWindowFocus("main", true)
    OwnWindowFocus.report(c, Any())
    rig.advance(1_000); rig.later.runDue()
    assertFalse(c.locked.value)
    // really leaving: the focus goes to another application, so no window of ours has it
    c.onWindowFocus("main", false)
    OwnWindowFocus.report(c, null)
    rig.advance(500); rig.later.runDue()
    rig.advance(2_000)
    c.onWindowFocus("main", true)
    assertTrue(c.locked.value, "leaving for another app still locks")
  }

  @Test fun `two windows share one lock and hopping between them is not leaving`() {
    val rig = Rig()
    rig.controller().enableLock(p())
    val c = rig.controller()
    c.authWithPassphrase(Purpose.UNLOCK, p())
    c.onWindowFocus("main", true)
    c.onWindowFocus("main", false)
    c.onWindowFocus("panel", true)
    rig.advance(1_000); rig.later.runDue()
    assertFalse(c.locked.value)
  }

  @Test fun `the screen going off or the computer sleeping locks at once`() {
    val rig = Rig()
    rig.controller().also { it.enableLock(p()); it.setLockAfterMinutes(30) }   // so the idle timer cannot be what locks it
    val c = rig.controller()
    c.authWithPassphrase(Purpose.UNLOCK, p())
    c.onScreenOff()
    assertTrue(c.locked.value)

    c.authWithPassphrase(Purpose.UNLOCK, p())
    assertFalse(c.locked.value)
    rig.advance(10 * 60_000L)   // the wall clock jumps far more than a tick: the computer slept
    c.tick()
    assertTrue(c.locked.value)
  }

  @Test fun `nobody touching Trawl for the lock-after time locks it, input keeps it open`() {
    val rig = Rig()
    val a = rig.controller()
    a.enableLock(p())
    a.setLockAfterMinutes(1)
    val c = rig.controller()
    c.authWithPassphrase(Purpose.UNLOCK, p())
    rig.advance(30_000); c.onInput(); c.tick()
    assertFalse(c.locked.value)
    rig.advance(30_000); c.tick()
    assertFalse(c.locked.value)
    rig.advance(31_000); c.tick()
    assertTrue(c.locked.value)
  }

  @Test fun `sensitive results reveal with a proof and hide again`() {
    val rig = Rig()
    rig.controller().enableLock(p())
    val c = rig.controller()
    c.authWithPassphrase(Purpose.UNLOCK, p())
    assertTrue(c.revealNeedsProof())
    assertFalse(c.revealWithoutProof())
    assertFalse(c.revealed.value)
    assertEquals(PassResult.Ok, c.authWithPassphrase(Purpose.REVEAL, p()))
    assertTrue(c.revealed.value)
    c.hideSensitive()
    assertFalse(c.revealed.value)
  }

  @Test fun `with no lock at all a reveal needs no proof`() {
    val rig = Rig()
    rig.os.avail = OsAvailability.Unavailable("none")
    val c = rig.controller()
    assertFalse(c.revealNeedsProof())
    assertTrue(c.revealWithoutProof())
    assertTrue(c.revealed.value)
  }

  @Test fun `the reveal ends by itself after the grace period away`() {
    val rig = Rig()
    rig.controller().enableLock(p())
    val c = rig.controller()
    c.authWithPassphrase(Purpose.UNLOCK, p())
    c.authWithPassphrase(Purpose.REVEAL, p())
    c.onWindowFocus("main", true)
    c.onWindowFocus("main", false)
    rig.advance(500); rig.later.runDue()
    assertTrue(c.revealed.value)
    rig.advance(PrivacyGate.DEFAULT_REVEAL_GRACE_MS + 1_000); rig.later.runDue()
    assertFalse(c.revealed.value)
  }

  @Test fun `the lock cannot be switched off without a fresh proof`() {
    val rig = Rig()
    rig.controller().enableLock(p())
    val c = rig.controller()
    c.authWithPassphrase(Purpose.UNLOCK, p())
    assertEquals(ChangeResult.NEEDS_PROOF, c.disableLock())
    assertTrue(c.settings.value.lockEnabled)
    assertEquals(ChangeResult.NEEDS_PROOF, c.enableLock("another passphrase".toCharArray()), "nor the passphrase replaced")
    assertEquals(PassResult.Ok, c.authWithPassphrase(Purpose.CHANGE, p()))
    assertEquals(ChangeResult.OK, c.disableLock())
    assertFalse(c.settings.value.lockEnabled)
    assertNull(c.settings.value.passphrase)
  }

  @Test fun `the proof for a change runs out after a minute`() {
    val rig = Rig()
    rig.controller().enableLock(p())
    val c = rig.controller()
    c.authWithPassphrase(Purpose.UNLOCK, p())
    c.authWithPassphrase(Purpose.CHANGE, p())
    rig.advance(PrivacyController.FRESH_MS + 1)
    assertEquals(ChangeResult.NEEDS_PROOF, c.disableLock())
  }

  @Test fun `marks are saved, applied to a rebuilt index and counted`() {
    val rig = Rig()
    val c = rig.controller()
    val ix = FakeIndex(setOf(1, 2, 3))
    c.bind(ix)
    assertEquals(2, c.mark(listOf(1, 2, 99), true))
    assertTrue(c.anySensitive(listOf(2, 3)))
    assertFalse(c.anySensitive(listOf(3)))
    assertEquals(1, c.countSensitive(listOf(1, 3)))
    // a new process with a rebuilt (empty) index gets the marks back
    val c2 = rig.controller()
    val rebuilt = FakeIndex(setOf(1, 2, 3))
    c2.bind(rebuilt)
    assertEquals(setOf("f1", "f2"), rebuilt.rules.markedFiles)
    c2.mark(listOf(1), false)
    assertFalse(rebuilt.isSensitive(1))
  }

  @Test fun `what the crawler learns about a marked file is kept`() {
    val rig = Rig()
    val c = rig.controller()
    val ix = FakeIndex(setOf(1))
    c.bind(ix)
    ix.learned = PrivacyRules(emptySet(), setOf("moved/new-name"))
    c.tick()
    assertEquals(setOf("moved/new-name"), c.settings.value.rules.markedFiles)
  }

  @Test fun `the lock offer shows once, and only while there is no lock`() {
    val c = Rig().controller()
    assertTrue(c.shouldOfferLock())
    c.lockOfferShown()
    assertFalse(c.shouldOfferLock())
  }

  @Test fun `the capture shield follows the setting and any window showing a sensitive result`() {
    val c = Rig().controller()
    assertFalse(c.secureWindow.value)
    c.setSensitiveOnScreen("main", true)
    assertTrue(c.secureWindow.value)
    c.setSensitiveOnScreen("main", false)
    assertFalse(c.secureWindow.value)
    c.setHideFromCapture(true)
    assertTrue(c.secureWindow.value)
  }

  @Test fun `a private folder is stored relative to its root and a root itself cannot be marked`() {
    val root = File("/home/u/Pictures")
    assertEquals("Trips/Private", PrivacyController.relativeFolder(File("/home/u/Pictures/Trips/Private"), listOf(root)))
    assertNull(PrivacyController.relativeFolder(root, listOf(root)))
    assertNull(PrivacyController.relativeFolder(File("/elsewhere/x"), listOf(root)))
  }

  @Test fun `declined packs and dismissed updates are remembered`() {
    val rig = Rig()
    val c = rig.controller()
    c.setDeclined("privacy", true)
    c.dismissUpdate("privacy", 3)
    val d = rig.controller()
    assertTrue(d.declined("privacy"))
    assertTrue(d.updateDismissed("privacy", 3))
    assertFalse(d.updateDismissed("privacy", 4))
  }

  @Test fun `a damaged settings file is reported not hidden`() {
    val rig = Rig()
    File(rig.dir, "privacy.json").writeText("garbage")
    assertEquals(trawl.desktop.privacy.LoadState.DAMAGED, rig.controller().loadState)
  }

  @Test fun `settings defaults match the documented choices`() {
    val s = PrivacySettings()
    assertEquals(listOf(0, 1, 5, 15, 30), PrivacySettings.LOCK_AFTER_CHOICES)
    assertTrue(s.blurEnabled)
    assertFalse(s.lockEnabled)
    assertTrue(Passphrase.ITERATIONS > 0)
  }
}
