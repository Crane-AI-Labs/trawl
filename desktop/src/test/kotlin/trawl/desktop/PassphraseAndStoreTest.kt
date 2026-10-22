package trawl.desktop

import com.craneai.findr.index.PrivacyRules
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.json.JSONObject
import trawl.desktop.privacy.AttemptLimiter
import trawl.desktop.privacy.LoadState
import trawl.desktop.privacy.Passphrase
import trawl.desktop.privacy.PrivacySettings
import trawl.desktop.privacy.PrivacyStore

class PassphraseAndStoreTest {
  private val pass = "correct horse battery".toCharArray()

  @Test fun `a passphrase verifies and a wrong one does not`() {
    val rec = Passphrase.create(pass, iterations = 1000)
    assertTrue(Passphrase.verify("correct horse battery".toCharArray(), rec))
    assertFalse(Passphrase.verify("correct horse batterz".toCharArray(), rec))
    assertFalse(Passphrase.verify("".toCharArray(), rec))
  }

  @Test fun `the same passphrase gets a new salt and a different hash each time`() {
    val a = Passphrase.create(pass, iterations = 1000)
    val b = Passphrase.create(pass, iterations = 1000)
    assertNotEquals(a.saltB64, b.saltB64)
    assertNotEquals(a.hashB64, b.hashB64)
  }

  @Test fun `the real work factor is the standard one`() {
    assertEquals("PBKDF2WithHmacSHA256", Passphrase.ALGO)
    assertTrue(Passphrase.ITERATIONS >= 600_000)
    assertEquals(Passphrase.ITERATIONS, Passphrase.create(pass).iterations)
  }

  @Test fun `a damaged record never verifies and never throws`() {
    val rec = Passphrase.create(pass, iterations = 1000)
    assertFalse(Passphrase.verify(pass, rec.copy(saltB64 = "%%%not base64")))
    assertFalse(Passphrase.verify(pass, rec.copy(algo = "plain")))
    assertFalse(Passphrase.verify(pass, rec.copy(iterations = 0)))
  }

  @Test fun `new passphrases must be long enough and not blank`() {
    assertEquals(Passphrase.Problem.BLANK, Passphrase.problem("          ".toCharArray()))
    assertEquals(Passphrase.Problem.TOO_SHORT, Passphrase.problem("short".toCharArray()))
    assertNull(Passphrase.problem("long enough".toCharArray()))
  }

  @Test fun `wipe clears the caller's array`() {
    val p = "to be wiped".toCharArray()
    Passphrase.wipe(p)
    assertTrue(p.all { it == '\u0000' })
  }

  @Test fun `the limiter is free for three tries then doubles the wait`() {
    var t = 0L
    val l = AttemptLimiter { t }
    repeat(AttemptLimiter.FREE) { l.failed(); assertEquals(0, l.waitMs()) }
    l.failed()
    assertEquals(AttemptLimiter.BASE_WAIT_MS, l.waitMs())
    t += 2_000
    assertEquals(0, l.waitMs())
    l.failed()
    assertEquals(AttemptLimiter.BASE_WAIT_MS * 2, l.waitMs())
    repeat(40) { l.failed() }
    assertTrue(l.waitMs() <= AttemptLimiter.MAX_WAIT_MS)
    l.succeeded()
    assertEquals(0, l.waitMs())
  }

  private fun tmp(): File = Files.createTempDirectory("privacy-store").toFile().also { it.deleteOnExit() }

  @Test fun `the file holds only the algorithm, rounds, salt and hash, never the passphrase`() {
    val dir = tmp()
    val rec = Passphrase.create(pass, iterations = 1000)
    PrivacyStore(dir).save(PrivacySettings(lockEnabled = true, passphrase = rec))
    val text = File(dir, "privacy.json").readText()
    assertFalse(text.contains("correct horse battery"))
    val p = JSONObject(text).getJSONObject("passphrase")
    assertEquals(setOf("algo", "iterations", "salt", "hash"), p.keys().asSequence().toSet())
    assertEquals(rec.hashB64, p.getString("hash"))
  }

  @Test fun `settings and marks round trip`() {
    val dir = tmp()
    val s = PrivacySettings(
      lockEnabled = true, lockAfterMinutes = 15, useOsPrompt = false, passphrase = Passphrase.create(pass, iterations = 1000), blurEnabled = false,
      hideFromCapture = true, rules = PrivacyRules(setOf("Documents/Tax"), setOf("Pictures/a.jpg")), lockOfferShown = true,
      declinedPacks = setOf("privacy"), dismissedUpdates = mapOf("privacy" to 3),
    )
    PrivacyStore(dir).save(s)
    val (back, state) = PrivacyStore(dir).load()
    assertEquals(LoadState.OK, state)
    assertEquals(s, back)
  }

  @Test fun `no file is a fresh start`() {
    assertEquals(LoadState.FRESH, PrivacyStore(tmp()).load().second)
  }

  @Test fun `a damaged file falls back to the last good copy and says so`() {
    val dir = tmp()
    val store = PrivacyStore(dir)
    store.save(PrivacySettings(rules = PrivacyRules(emptySet(), setOf("a.jpg"))))
    store.save(PrivacySettings(rules = PrivacyRules(emptySet(), setOf("a.jpg", "b.jpg"))))
    File(dir, "privacy.json").writeText("{ not json")
    val (s, state) = store.load()
    assertEquals(LoadState.RECOVERED, state)
    assertEquals(setOf("a.jpg"), s.rules.markedFiles)
  }

  @Test fun `damage with no backup is reported, not treated as no privacy settings`() {
    val dir = tmp()
    File(dir, "privacy.json").writeText("garbage")
    val (s, state) = PrivacyStore(dir).load()
    assertEquals(LoadState.DAMAGED, state)
    assertFalse(s.lockEnabled)
  }

  @Test fun `a lock with no passphrase is damage`() {
    val dir = tmp()
    File(dir, "privacy.json").writeText("""{"format":1,"lockEnabled":true,"passphrase":null}""")
    assertEquals(LoadState.DAMAGED, PrivacyStore(dir).load().second)
  }

  @Test fun `saving leaves no half written file behind`() {
    val dir = tmp()
    PrivacyStore(dir).save(PrivacySettings())
    assertFalse(File(dir, "privacy.json.part").exists())
  }
}
