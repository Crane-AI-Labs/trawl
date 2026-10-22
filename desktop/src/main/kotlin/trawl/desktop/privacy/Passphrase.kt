package trawl.desktop.privacy

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * What is stored for the app-lock passphrase: the algorithm, the work factor, a random salt and the derived hash. Never the
 * passphrase, never a reversible form of it. Written to `privacy.json`, so what the file holds is exactly these four fields.
 */
data class PassphraseRecord(val algo: String, val iterations: Int, val saltB64: String, val hashB64: String)

/**
 * The passphrase half of the app lock: PBKDF2-HMAC-SHA256 (a standard slow key derivation in the JDK, no extra library),
 * 600,000 rounds (OWASP's 2023 figure for this algorithm), a fresh 16-byte random salt per passphrase and a 256-bit result,
 * compared in constant time. The caller's `CharArray` is wiped after use.
 *
 * It protects who can LOOK AT results in the app. It is not encryption of the index (see PrivacyController).
 */
object Passphrase {
  const val ALGO = "PBKDF2WithHmacSHA256"
  const val ITERATIONS = 600_000
  const val MIN_LENGTH = 8
  private const val SALT_BYTES = 16
  private const val HASH_BITS = 256

  enum class Problem { TOO_SHORT, BLANK }

  /** Null when [pass] is acceptable as a new passphrase. Length counts characters, not bytes. */
  fun problem(pass: CharArray): Problem? = when {
    pass.all { it.isWhitespace() } -> Problem.BLANK
    pass.size < MIN_LENGTH -> Problem.TOO_SHORT
    else -> null
  }

  /** Derives a record for [pass]. Does not wipe [pass]: the caller owns it (and should call [wipe]). */
  fun create(pass: CharArray, iterations: Int = ITERATIONS, random: SecureRandom = SecureRandom()): PassphraseRecord {
    val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
    val hash = derive(pass, salt, iterations)
    return PassphraseRecord(ALGO, iterations, Base64.getEncoder().encodeToString(salt), Base64.getEncoder().encodeToString(hash))
  }

  /** True when [pass] is the passphrase [record] was made from. Constant-time on the hash; false (never throws) for a damaged record. */
  fun verify(pass: CharArray, record: PassphraseRecord): Boolean = try {
    if (record.algo != ALGO || record.iterations < 1) false
    else {
      val salt = Base64.getDecoder().decode(record.saltB64)
      val want = Base64.getDecoder().decode(record.hashB64)
      MessageDigest.isEqual(derive(pass, salt, record.iterations), want)
    }
  } catch (_: Exception) {
    false
  }

  fun wipe(pass: CharArray) = pass.fill('\u0000')

  private fun derive(pass: CharArray, salt: ByteArray, iterations: Int): ByteArray {
    val spec = PBEKeySpec(pass, salt, iterations, HASH_BITS)
    try {
      return SecretKeyFactory.getInstance(ALGO).generateSecret(spec).encoded
    } finally {
      spec.clearPassword()
    }
  }
}

/**
 * Slows down guessing at the lock screen: the first [FREE] wrong tries cost nothing, then each further miss doubles the wait
 * (2, 4, 8 ... up to [MAX_WAIT_MS]). A pure state machine on an injected clock. It lives in memory: it deters someone sitting at
 * the open app, not someone with the files (who can ignore the app entirely: see PrivacyController).
 */
class AttemptLimiter(private val now: () -> Long = System::currentTimeMillis) {
  private var misses = 0
  private var blockedUntil = 0L

  /** Milliseconds until another try is allowed; 0 when one is allowed now. */
  fun waitMs(): Long = (blockedUntil - now()).coerceAtLeast(0)

  fun failed() {
    misses++
    if (misses > FREE) blockedUntil = now() + (BASE_WAIT_MS shl (misses - FREE - 1).coerceAtMost(20)).coerceAtMost(MAX_WAIT_MS)
  }

  fun succeeded() {
    misses = 0
    blockedUntil = 0
  }

  companion object {
    const val FREE = 3
    const val BASE_WAIT_MS = 2_000L
    const val MAX_WAIT_MS = 300_000L
  }
}
