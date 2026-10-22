package trawl.desktop.shell

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import trawl.desktop.Os
import trawl.desktop.Platform

/** What one native registration attempt came to. */
sealed interface HotkeyResult {
  data object Ok : HotkeyResult
  /** Another app (or the system) already holds this exact key. */
  data class Taken(val detail: String? = null) : HotkeyResult
  /** This session cannot listen globally at all (Wayland, no X server, native library missing). */
  data class Unsupported(val wayland: Boolean, val detail: String) : HotkeyResult
  data class Failed(val detail: String) : HotkeyResult
  /**
   * Wayland: the desktop's portal accepted a shortcut. The person's own desktop owns the key, so [trigger] is what *it* says the key is
   * ("Ctrl+Alt+Space"), or null when it bound nothing yet; [portalVersion] is the GlobalShortcuts interface version.
   */
  data class Portal(val trigger: String?, val portalVersion: Long = 1) : HotkeyResult
}

/**
 * One OS's way of hearing a key anywhere. Exactly one shortcut is registered at a time. [register] blocks until the OS has answered, so
 * the result is the truth; [onPressed] is then called from a native thread, once per press.
 */
interface GlobalHotkey : AutoCloseable {
  fun register(h: Hotkey, onPressed: () -> Unit): HotkeyResult
  fun unregister()
  override fun close() = unregister()
}

/** Where the shortcut stands, for Settings, the tray and the first-run line. */
sealed interface HotkeyStatus {
  data object Off : HotkeyStatus
  /** [fellBackFrom] is the default that was taken, when [hotkey] is one of its fallbacks. */
  data class Active(val hotkey: Hotkey, val fellBackFrom: Hotkey? = null, val warning: String? = null) : HotkeyStatus
  /** The default and every fallback were taken: nothing is registered. */
  data object AllTaken : HotkeyStatus
  /** The key the person chose is taken. */
  data class Taken(val hotkey: Hotkey) : HotkeyStatus
  /** Wayland with no portal that can do global shortcuts: the tray and `trawl --quick` are the fallback. */
  data object Wayland : HotkeyStatus
  /** Wayland: the desktop's portal holds the shortcut. [trigger] is the desktop's own description of the key, null if none is assigned yet. */
  data class Portal(val trigger: String?) : HotkeyStatus
  data class Failed(val detail: String) : HotkeyStatus
}

/** Choosing among the default and its fallbacks. Pure: [attempt] is the native call, so a test can say which keys are "taken". */
object HotkeyChooser {
  data class Outcome(val status: HotkeyStatus, val registered: Hotkey?)

  fun choose(candidates: List<Hotkey>, os: Os, attempt: (Hotkey) -> HotkeyResult): Outcome {
    for ((i, h) in candidates.withIndex()) {
      when (val r = attempt(h)) {
        HotkeyResult.Ok -> return Outcome(
          HotkeyStatus.Active(h, fellBackFrom = if (i == 0) null else candidates.first(), warning = Hotkey.knownOwner(h, os)?.name), h,
        )
        is HotkeyResult.Portal -> return Outcome(HotkeyStatus.Portal(r.trigger), null)
        is HotkeyResult.Taken -> continue
        is HotkeyResult.Unsupported -> return Outcome(if (r.wayland) HotkeyStatus.Wayland else HotkeyStatus.Failed(r.detail), null)
        is HotkeyResult.Failed -> return Outcome(HotkeyStatus.Failed(r.detail), null)
      }
    }
    return Outcome(HotkeyStatus.AllTaken, null)
  }
}

/** What the recorder in Settings says about a key combination the person just pressed. */
sealed interface ShortcutCheck {
  data class Ok(val hotkey: Hotkey, val warning: String?) : ShortcutCheck
  data object NeedModifier : ShortcutCheck
  data class Owned(val hotkey: Hotkey, val by: String) : ShortcutCheck
  data class Taken(val hotkey: Hotkey) : ShortcutCheck
  data class Failed(val detail: String) : ShortcutCheck
}

object ShortcutRecorder {
  /** Refuses what steals typing, what the system itself owns, and what another app has already registered ([attempt] is the native call). */
  fun check(h: Hotkey?, os: Os, attempt: (Hotkey) -> HotkeyResult): ShortcutCheck {
    if (h == null || !h.usable) return ShortcutCheck.NeedModifier
    val owner = Hotkey.knownOwner(h, os)
    if (owner != null && owner.hard) return ShortcutCheck.Owned(h, owner.name)
    return when (val r = attempt(h)) {
      HotkeyResult.Ok -> ShortcutCheck.Ok(h, owner?.name)
      is HotkeyResult.Taken -> ShortcutCheck.Taken(h)
      is HotkeyResult.Unsupported -> ShortcutCheck.Failed(r.detail)
      is HotkeyResult.Failed -> ShortcutCheck.Failed(r.detail)
      is HotkeyResult.Portal -> ShortcutCheck.Failed("the desktop chooses this shortcut")   // the recorder is not shown on Wayland
    }
  }
}

/**
 * Owns the one native registration for the life of the app and keeps [status] true. [apply] re-reads the person's choice; [probe] is the
 * recorder's trial: it registers the new key for real, and on failure puts the previous one back.
 */
class HotkeyService(
  private val os: Os = Platform.os,
  private val native: GlobalHotkey?,
  private val onPressed: () -> Unit,
) : AutoCloseable {
  private val _status = MutableStateFlow<HotkeyStatus>(HotkeyStatus.Off)
  val status: StateFlow<HotkeyStatus> = _status.asStateFlow()
  @Volatile private var current: Hotkey? = null

  /** The shortcut now registered, or null. */
  val active: Hotkey? get() = current

  @Synchronized
  fun apply(prefs: ShellPrefs) {
    native?.unregister()
    current = null
    if (!prefs.hotkeyOn) { _status.value = HotkeyStatus.Off; return }
    if (native == null) { _status.value = HotkeyStatus.Failed("no native layer"); return }
    val chosen = Hotkey.parse(prefs.hotkey)
    // The person's own key is tried alone; only the untouched default walks the fallback list.
    val candidates = if (chosen != null) listOf(chosen) else Hotkey.candidates(os)
    val out = HotkeyChooser.choose(candidates, os) { native.register(it, onPressed) }
    current = out.registered
    _status.value = if (chosen != null && out.status is HotkeyStatus.AllTaken) HotkeyStatus.Taken(chosen) else out.status
  }

  /** Try [h] for real. On success it stays registered (the caller then saves it); on failure the previous shortcut is restored. */
  @Synchronized
  fun probe(h: Hotkey?): ShortcutCheck {
    val before = current
    val result = ShortcutRecorder.check(h, os) { cand ->
      val n = native ?: return@check HotkeyResult.Failed("no native layer")
      n.unregister()
      current = null
      n.register(cand, onPressed).also { if (it == HotkeyResult.Ok) current = cand }
    }
    if (result !is ShortcutCheck.Ok) {
      // The trial may have unregistered the old key before failing; put it back.
      if (current == null && before != null && native != null && native.register(before, onPressed) == HotkeyResult.Ok) current = before
    } else {
      _status.value = HotkeyStatus.Active(result.hotkey, warning = result.warning)
    }
    return result
  }

  @Synchronized
  override fun close() { native?.close(); current = null }

  companion object {
    /** The native layer for this OS, or null where none can load. */
    fun nativeFor(os: Os = Platform.os, dataDir: java.io.File? = null): GlobalHotkey? = when (os) {
      Os.WINDOWS -> HotkeyWindows()
      Os.LINUX -> if (HotkeyPortal.isWayland()) HotkeyPortal() else HotkeyX11()
      Os.MAC -> dataDir?.let { HotkeyMac(it) }
    }
  }
}
