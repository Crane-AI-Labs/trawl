package trawl.desktop.privacy

import com.craneai.findr.index.PrivacyRules
import com.craneai.findr.privacy.PrivacyGate
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Runs [block] once, [ms] from now. Injected so the tests control time. */
fun interface Later {
  fun after(ms: Long, block: () -> Unit): () -> Unit

  companion object {
    private val pool = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "trawl-privacy-timer").apply { isDaemon = true } }
    val Daemon = Later { ms, block ->
      val f: ScheduledFuture<*> = pool.schedule({ runCatching(block) }, ms, TimeUnit.MILLISECONDS)
      ({ f.cancel(false); Unit })
    }
  }
}

/** What a person can prove it is them with, right now. */
data class AuthMethods(val os: OsPrompt?, val passphrase: Boolean) {
  /** Nothing to check: no passphrase and no system prompt. Reveal opens straight away (there is nobody to lock out). */
  val none: Boolean get() = os == null && !passphrase
}

/** Why a proof is being asked for. UNLOCK opens the app, REVEAL shows sensitive results, CHANGE allows a change to the lock itself, UNHIDE lets a private folder be lifted. */
enum class Purpose { UNLOCK, REVEAL, CHANGE, UNHIDE }

sealed interface PassResult {
  data object Ok : PassResult
  data object Wrong : PassResult
  /** Too many wrong tries: wait this many milliseconds. */
  data class Wait(val ms: Long) : PassResult
  /** There is no passphrase to check against. */
  data object NotSet : PassResult
}

/**
 * The one place that ties the lock rules ([PrivacyGate], shared with the phone) to this computer: which Trawl windows have the
 * focus, the computer sleeping, nobody touching it, the system prompt ([OsUnlock]) and the passphrase ([Passphrase]). One per
 * process, shared by the main window and the quick-search panel, so unlocking in one unlocks the other and they lock together.
 *
 * WHAT THE LOCK IS, AND IS NOT. It gates the VIEW of results in this app: while locked, no result, preview, name or count is
 * shown, and sensitive files are left out of results until revealed. It does NOT encrypt the index, the settings or the files:
 * anyone who can read this user's files on disk (another account with access, a backup, malware running as the user) can read
 * the index with a SQLite viewer, and the passphrase hash sits in `privacy.json` next to it. A lock that claimed more would be
 * worse than none. Whole-disk encryption (FileVault, BitLocker, LUKS) is what protects data at rest.
 *
 * Thread-safe: every entry point takes the controller's lock. The slow parts (the passphrase derivation, the system prompt) run
 * with the lock released. State is published through [StateFlow]s the screens collect.
 */
class PrivacyController(
  private val store: PrivacyStore,
  val os: OsUnlock,
  private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
  private val wallClock: () -> Long = System::currentTimeMillis,
  private val later: Later = Later.Daemon,
  private val limiter: AttemptLimiter = AttemptLimiter(),
  private val passphraseIterations: Int = Passphrase.ITERATIONS,
) : PackChoices {
  private val lock = Any()
  private val gate: PrivacyGate
  private var index: PrivacyIndex? = null

  private val _settings: MutableStateFlow<PrivacySettings>
  /** The saved choices. */
  val settings: StateFlow<PrivacySettings> get() = _settings

  /** How the saved file was found at start: [LoadState.DAMAGED] means the marks and the lock were lost and the screen must say so. */
  val loadState: LoadState

  private val _locked: MutableStateFlow<Boolean>
  /** Show only the lock screen: no results. */
  val locked: StateFlow<Boolean> get() = _locked

  private val _revealed = MutableStateFlow(false)
  /** Sensitive results are shown. The index service reads this to include them. */
  val revealed: StateFlow<Boolean> get() = _revealed

  private val _secureWindow = MutableStateFlow(false)
  /** The window should be kept out of screenshots and screen sharing (the person asked, or a sensitive result is on screen). */
  val secureWindow: StateFlow<Boolean> get() = _secureWindow

  private val _osAvailability = MutableStateFlow<OsAvailability>(OsAvailability.Unavailable("not checked yet"))
  /** What the system prompt can do on this computer. Filled by [refreshOs], which runs the helper's no-prompt check. */
  val osAvailability: StateFlow<OsAvailability> get() = _osAvailability

  private val focused = HashSet<String>()
  private var backgroundedAt: Long? = null
  private var cancelSettle: (() -> Unit)? = null
  private var cancelGrace: (() -> Unit)? = null
  private val sensitiveOn = HashSet<String>()
  private var freshUntil = 0L
  private var lastInput = clock()
  private var lastWall = wallClock()

  init {
    val (loaded, state) = store.load()
    loadState = state
    _settings = MutableStateFlow(loaded)
    gate = PrivacyGate(configOf(loaded))
    gate.onProcessStart()   // a new process always starts locked (if the lock is on) and hidden
    _locked = MutableStateFlow(gate.isLocked)
    publish()
  }

  // ---- wiring ---------------------------------------------------------------------------------------------------

  /** Hands over the index. The saved rules are applied to it at once, so a rebuilt index hides the marked files again. */
  fun bind(index: PrivacyIndex) {
    synchronized(lock) { this.index = index }
    index.applyRules(_settings.value.rules)
    persistLearned()
  }

  /** Probes the system prompt (never shows it). Blocking, a few seconds at most: call off the UI thread. */
  fun refreshOs() {
    _osAvailability.value = os.availability()
  }

  private fun configOf(s: PrivacySettings) = PrivacyGate.Config(lockEnabled = s.lockEnabled, lockAfterMs = s.lockAfterMinutes * 60_000L)

  private fun publish() {
    synchronized(lock) {
      _locked.value = gate.isLocked
      _revealed.value = gate.isRevealed
      _secureWindow.value = _settings.value.hideFromCapture || sensitiveOn.isNotEmpty()
    }
  }

  private fun update(change: (PrivacySettings) -> PrivacySettings) {
    synchronized(lock) {
      val next = change(_settings.value)
      if (next == _settings.value) return
      _settings.value = next
      store.save(next)
    }
  }

  // ---- when to lock -----------------------------------------------------------------------------------------------

  /**
   * A Trawl window ([owner]: "main", "panel") gained or lost the keyboard focus. The computer is "away" when none has it for a
   * moment (a dialog or the system prompt taking the focus for an instant is not leaving). Ignored while a prompt is up.
   */
  fun onWindowFocus(owner: String, hasFocus: Boolean) {
    synchronized(lock) {
      if (gate.authInFlight) return
      if (hasFocus) focused += owner else focused -= owner
      lastInput = clock()
      if (focused.isNotEmpty()) {
        cancelSettle?.invoke(); cancelSettle = null
        cancelGrace?.invoke(); cancelGrace = null
        if (backgroundedAt != null) {
          gate.onForeground(clock())
          backgroundedAt = null
          publish()
        }
      } else if (backgroundedAt == null && cancelSettle == null) {
        val left = clock()
        cancelSettle = later.after(SETTLE_MS) { commitBackground(left) }
      }
    }
  }

  private fun commitBackground(left: Long) {
    synchronized(lock) {
      cancelSettle = null
      if (focused.isNotEmpty()) return
      gate.onBackground(left)
      backgroundedAt = left
      // The reveal ends on its own after the grace period, even if the person never comes back.
      cancelGrace = later.after(gate.config.revealGraceMs) { synchronized(lock) { gate.onRevealGraceElapsed(clock()); publish() } }
    }
  }

  /** The person touched the keyboard or mouse in a Trawl window. */
  fun onInput() {
    synchronized(lock) { lastInput = clock() }
  }

  /** The computer went to sleep or its screen locked: lock and hide at once, whatever the timeout says. */
  fun onScreenOff() {
    synchronized(lock) {
      gate.onScreenOff()
      publish()
    }
  }

  /**
   * Called every few seconds by [startWatcher]. Two things the JVM cannot be told about directly: the computer sleeping (a
   * wall-clock jump much larger than the tick) and nobody touching Trawl for the lock-after time (then it locks and hides, as the
   * phone does when its screen turns off). A lock-after of 0 means "every time Trawl comes back", so it has no idle timer.
   */
  fun tick() {
    val wall = wallClock()
    val now = clock()
    var learned = false
    synchronized(lock) {
      val slept = wall - lastWall > TICK_MS + SLEEP_JUMP_MS
      lastWall = wall
      val after = _settings.value.lockAfterMinutes * 60_000L
      val idle = after > 0 && now - lastInput >= after && !gate.authInFlight
      if (slept || idle) {
        gate.onScreenOff()
        lastInput = now
      }
      publish()
      learned = index != null
    }
    if (learned) persistLearned()
  }

  /** Starts the background tick. Returns a function that stops it. */
  fun startWatcher(): () -> Unit {
    val exec = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "trawl-privacy-watch").apply { isDaemon = true } }
    exec.scheduleWithFixedDelay({ runCatching { tick() } }, TICK_MS, TICK_MS, TimeUnit.MILLISECONDS)
    return { exec.shutdownNow(); Unit }
  }

  // ---- proving it is you --------------------------------------------------------------------------------------------

  fun authMethods(): AuthMethods {
    val s = _settings.value
    val prompt = (_osAvailability.value as? OsAvailability.Available)?.prompt?.takeIf { s.useOsPrompt }
    return AuthMethods(os = prompt, passphrase = s.passphrase != null)
  }

  /** The lock screen may start the system prompt by itself (the person has not just dismissed it, none is up). */
  fun canAutoPrompt(): Boolean = synchronized(lock) { gate.isLocked && !gate.authInFlight && authMethods().os != null }

  /**
   * Runs the system prompt for [purpose]. Blocking (up to minutes, while the person is deciding): call it off the UI thread.
   * [OsOutcome.OK] has already done what the purpose says (unlocked, revealed, or allowed a change for a minute).
   */
  fun authWithOs(purpose: Purpose, reason: String): OsOutcome {
    if (authMethods().os == null) return OsOutcome.UNAVAILABLE
    synchronized(lock) { if (!gate.beginAuth()) return OsOutcome.CANCELLED }
    val outcome = try { os.authenticate(reason) } catch (_: Exception) { OsOutcome.ERROR }
    synchronized(lock) {
      if (outcome == OsOutcome.OK) granted(purpose) else gate.endAuth()
      publish()
    }
    return outcome
  }

  /** Checks a typed passphrase for [purpose]. Slow on purpose (about half a second): call it off the UI thread. Wipes [pass]. */
  fun authWithPassphrase(purpose: Purpose, pass: CharArray): PassResult {
    try {
      val record: PassphraseRecord
      synchronized(lock) {
        record = _settings.value.passphrase ?: return PassResult.NotSet
        val wait = limiter.waitMs()
        if (wait > 0) return PassResult.Wait(wait)
      }
      val ok = Passphrase.verify(pass, record)
      synchronized(lock) {
        if (ok) { limiter.succeeded(); granted(purpose); publish(); return PassResult.Ok }
        limiter.failed()
        val wait = limiter.waitMs()
        return if (wait > 0) PassResult.Wait(wait) else PassResult.Wrong
      }
    } finally {
      Passphrase.wipe(pass)
    }
  }

  private fun granted(purpose: Purpose) {
    when (purpose) {
      Purpose.UNLOCK -> gate.onUnlocked()
      Purpose.REVEAL -> gate.onRevealedByUser()
      Purpose.CHANGE -> { gate.endAuth(); freshUntil = clock() + FRESH_MS }
      Purpose.UNHIDE -> gate.endAuth()   // proves it is the person, and nothing more: it does not open the lock-change window or show hidden pictures
    }
  }

  /** Sensitive results can be shown with no proof at all: there is no passphrase and no system prompt to check against. */
  fun revealNeedsProof(): Boolean = !gate.isRevealed && !authMethods().none

  /** Shows sensitive results when no proof is needed ([revealNeedsProof] false). False when a proof is still required. */
  fun revealWithoutProof(): Boolean = synchronized(lock) {
    if (gate.isRevealed) return true
    if (!authMethods().none) return false
    gate.onRevealedByUser(); publish(); true
  }

  /** Put sensitive results away again. */
  fun hideSensitive() {
    synchronized(lock) { gate.hide(); publish() }
  }

  /** A proof for CHANGE was given in the last minute (turning the lock off, changing the passphrase), or the lock is off so there is nothing to prove. */
  private fun mayChangeLock(): Boolean = !_settings.value.lockEnabled || clock() < freshUntil

  // ---- the lock settings --------------------------------------------------------------------------------------------

  enum class ChangeResult { OK, NEEDS_PROOF, WEAK, BLANK }

  /**
   * Turns the app lock on with [pass] (also the fallback if the system prompt is unavailable), or replaces the passphrase when the
   * lock is already on and a proof was just given. Derives the hash with the lock released. Wipes [pass].
   */
  fun enableLock(pass: CharArray, useOsPrompt: Boolean = true): ChangeResult {
    try {
      Passphrase.problem(pass)?.let { return if (it == Passphrase.Problem.BLANK) ChangeResult.BLANK else ChangeResult.WEAK }
      synchronized(lock) { if (!mayChangeLock()) return ChangeResult.NEEDS_PROOF }
      val record = Passphrase.create(pass, passphraseIterations)
      update { it.copy(lockEnabled = true, passphrase = record, useOsPrompt = useOsPrompt) }
      synchronized(lock) {
        gate.configure(configOf(_settings.value))   // just enabled by someone who proved they could: no lock-out of what they are looking at
        publish()
      }
      return ChangeResult.OK
    } finally {
      Passphrase.wipe(pass)
    }
  }

  /** Turns the app lock off. Needs a proof in the last minute ([Purpose.CHANGE]), because anyone at the open window could otherwise just turn it off. */
  fun disableLock(): ChangeResult = synchronized(lock) {
    if (!mayChangeLock()) return ChangeResult.NEEDS_PROOF
    update { it.copy(lockEnabled = false, passphrase = null) }
    gate.configure(configOf(_settings.value))
    freshUntil = 0
    publish()
    ChangeResult.OK
  }

  fun setLockAfterMinutes(minutes: Int) {
    update { it.copy(lockAfterMinutes = minutes.coerceAtLeast(0)) }
    synchronized(lock) { gate.configure(configOf(_settings.value)); publish() }
  }

  fun setUseOsPrompt(on: Boolean) = update { it.copy(useOsPrompt = on) }

  fun setBlurEnabled(on: Boolean) = update { it.copy(blurEnabled = on) }

  fun setHideFromCapture(on: Boolean) {
    update { it.copy(hideFromCapture = on) }
    publish()
  }

  /** A window tells us whether it is showing a sensitive result ([owner] names the window): the capture shield turns on and off with it. */
  fun setSensitiveOnScreen(owner: String, showing: Boolean) {
    synchronized(lock) { if (if (showing) sensitiveOn.add(owner) else sensitiveOn.remove(owner)) publish() }
  }

  // ---- marks --------------------------------------------------------------------------------------------------------

  /** Marks or unmarks files by hand and saves the marks (they live here, not in the index). Returns how many were found. */
  fun mark(fileIds: List<Long>, on: Boolean): Int {
    val ix = index ?: return 0
    var latest: PrivacyRules? = null
    var found = 0
    for (id in fileIds) ix.setMarked(id, on)?.let { latest = it; found++ }
    latest?.let { rules -> update { it.copy(rules = rules) } }
    return found
  }

  /** True when any of [fileIds] is sensitive: the leave dialog is asked before they are copied, shared or opened elsewhere. */
  fun anySensitive(fileIds: Collection<Long>): Boolean = index?.let { ix -> fileIds.any(ix::isSensitive) } ?: false

  /** How many of [fileIds] are sensitive (the number the leave dialog names). */
  fun countSensitive(fileIds: Collection<Long>): Int = index?.let { ix -> fileIds.count(ix::isSensitive) } ?: 0

  /** Marks a whole folder private (everything under it), or lifts that. [relative] is the folder relative to its chosen root ([relativeFolder]). */
  fun setPrivateFolder(relative: String, on: Boolean) {
    val key = PrivacyRules.normalize(relative)
    if (key.isEmpty()) return
    val current = _settings.value.rules
    val rules = current.copy(privateFolders = if (on) current.privateFolders + key else current.privateFolders - key)
    update { it.copy(rules = rules) }
    index?.applyRules(rules)
    persistLearned()
  }

  /** The index learned something about a marked file (new name, new place): keep it with the saved marks. */
  fun persistLearned() {
    val learned = index?.takeLearnedRules() ?: return
    update { it.copy(rules = learned) }
  }

  fun sensitiveCount(): Int = index?.sensitiveCount() ?: 0

  /** The lock offer after the first "Mark sensitive": true once, and only while no lock is set. */
  fun shouldOfferLock(): Boolean = !_settings.value.lockEnabled && !_settings.value.lockOfferShown

  fun lockOfferShown() = update { it.copy(lockOfferShown = true) }

  fun blurHintShown() = update { it.copy(blurHintShown = true) }

  // ---- add-on choices (PackChoices) ------------------------------------------------------------------------------------

  override fun declined(id: String): Boolean = id in _settings.value.declinedPacks

  override fun setDeclined(id: String, declined: Boolean) =
    update { it.copy(declinedPacks = if (declined) it.declinedPacks + id else it.declinedPacks - id) }

  /** The update notice for [id] was dismissed for [version]: not shown again for it. */
  fun dismissUpdate(id: String, version: Int) = update { it.copy(dismissedUpdates = it.dismissedUpdates + (id to version)) }

  fun updateDismissed(id: String, version: Int): Boolean = (_settings.value.dismissedUpdates[id] ?: 0) >= version

  /** Where `privacy.json` is, for the "forgot your passphrase" line (no recovery exists; deleting this file removes the lock and the marks). */
  val settingsFile: File get() = store.file

  companion object {
    /** A focus gap shorter than this (a dialog, the system prompt, hopping between Trawl's own windows) is not leaving. */
    const val SETTLE_MS = 400L
    const val TICK_MS = 5_000L
    /** A wall clock that moved this much more than the tick means the computer slept. */
    const val SLEEP_JUMP_MS = 30_000L
    /** How long a proof for [Purpose.CHANGE] stays good. */
    const val FRESH_MS = 60_000L

    /**
     * [folder] relative to the chosen root that holds it ("Photos/Private"), or null when it is outside every root or is a root
     * itself (a whole chosen folder cannot be marked private: remove it from Trawl instead).
     */
    fun relativeFolder(folder: File, roots: List<File>): String? {
      val f = folder.toPath().toAbsolutePath().normalize()
      for (root in roots.map { it.toPath().toAbsolutePath().normalize() }) {
        if (f != root && f.startsWith(root)) return root.relativize(f).joinToString("/") { it.toString() }
      }
      return null
    }
  }
}

/** Remembers choices the person made about add-ons, so nothing is installed behind their back. */
interface PackChoices {
  /** True after "Not now" or Remove: a pack file found in Downloads is left alone until the person says Add. */
  fun declined(id: String): Boolean
  fun setDeclined(id: String, declined: Boolean)
}
