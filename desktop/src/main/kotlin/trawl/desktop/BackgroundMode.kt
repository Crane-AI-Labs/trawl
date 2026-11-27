package trawl.desktop

import com.craneai.findr.core.LearningSpeed

/** Operating-system priority tier chosen by [BackgroundMode]. */
enum class BackgroundTier {
  /** Normal foreground priority: a window or the quick panel is open, or speed is Fast. */
  FOREGROUND,
  /** macOS middle tier while the person is away on AC power: `PRIO_DARWIN_BG` lifted, worker threads at `QOS_UTILITY`, disk at `IOPOL_UTILITY`. */
  UTILITY,
  /** Full background throttle (`PRIO_DARWIN_BG` + disk throttle on macOS, EcoQoS on Windows, systemd scope weight on Linux). */
  BACKGROUND,
}

/**
 * A way to put the whole app behind the person's own programs, and to take it back out at once. One per operating system
 * ([Qos.Process] on a Mac, [LinuxScope] on Linux, [WindowsProcess] on Windows). Both calls are safe to repeat.
 */
interface Throttle {
  /** Behind the person's programs, as [speed] says. Returns what was done, or null when the system refused (nothing changed). */
  fun enter(speed: LearningSpeed): String?
  /** Whether this platform provides a Utility middle tier ([enterUtility]) for unattended AC indexing. */
  val supportsUtility: Boolean get() = false
  /** Middle tier while the person is away on AC power (macOS Utility QoS). Returns what was done, or null when unsupported/refused. */
  fun enterUtility(speed: LearningSpeed): String? = null
  /** Back to normal. Returns what was done, or null when the system refused. */
  fun leave(): String?

  companion object {
    /** The throttle for this system, or null when [BackgroundTuning.lowPriority] is off or the system has no such thing (or will not give it). */
    fun forThisSystem(
      os: Os = Platform.os, on: Boolean = BackgroundTuning.lowPriority != 0, windowsLevel: Int = BackgroundTuning.windowsBackground,
    ): Throttle? = when {
      !on -> null
      os == Os.MAC -> object : Throttle {
        override val supportsUtility = true
        override fun enter(speed: LearningSpeed) = Qos.Process.enter()?.also { HelperProcs.syncTier(BackgroundTier.BACKGROUND, Os.MAC) }
        override fun enterUtility(speed: LearningSpeed) = Qos.Process.enterUtility()?.also { HelperProcs.syncTier(BackgroundTier.UTILITY, Os.MAC) }
        override fun leave() = Qos.Process.leave()?.also { HelperProcs.syncTier(BackgroundTier.FOREGROUND, Os.MAC) }
      }
      os == Os.LINUX -> LinuxScope.open()
      os == Os.WINDOWS && windowsLevel > 0 -> WindowsProcess(eco = windowsLevel >= 2)
      else -> null
    }
  }
}

/** Windows: the whole process at below-normal priority, and with [eco] EcoQoS as well (Task Manager's efficiency mode); see [WinPriority.thisProcess]. */
class WindowsProcess(private val eco: Boolean) : Throttle {
  override fun enter(speed: LearningSpeed): String? = WinPriority.thisProcess(eco)?.also { HelperProcs.syncTier(BackgroundTier.BACKGROUND, Os.WINDOWS) }
  override fun leave(): String? = WinPriority.restoreThisProcess()?.also { HelperProcs.syncTier(BackgroundTier.FOREGROUND, Os.WINDOWS) }
}

/**
 * Decides when the app as a whole is "in the background" and tells the [Throttle]. The rule, by speed:
 *  - Fast: never. The person asked for the index to come first.
 *  - Gentle: whenever no window of Trawl is showing.
 *  - Balanced: whenever no window is showing, or the window is showing but is not the one the person is typing in.
 *  - Always foreground while the quick panel is open: it opens on a hotkey, and must not open inside a throttled process.
 *
 * On macOS, when [allowUtility] is enabled and [awayOnAc] is true (person away from keyboard on AC power), background mode uses
 * [BackgroundTier.UTILITY] (lifting `PRIO_DARWIN_BG` while worker threads use `QOS_UTILITY` and [Politeness] keeps capping CPU),
 * falling back to full [BackgroundTier.BACKGROUND] as soon as the person returns or unplugs.
 *
 * Any doubt is foreground: a failure while deciding, or an unknown speed, clears the throttle. Whoever brings a window forward calls
 * [refresh] first thing (before the window paints), so the clear happens before the person sees anything. [close] always clears.
 */
class BackgroundMode(
  private val throttle: Throttle?,
  private val speed: () -> LearningSpeed,
  private val log: (String) -> Unit = {},
  private val awayOnAc: () -> Boolean = { false },
  private val allowUtility: Boolean = false,
  private val onTierChanged: (BackgroundTier) -> Unit = {},
  /** True while the index is still performing its initial open/vector attach: never disk-throttle startup. */
  private val opening: () -> Boolean = { false },
) {
  @Volatile var mainVisible = true
  @Volatile var mainFocused = true
  @Volatile var panelOpen = false

  @Volatile var inBackground = false; private set
  @Volatile var tier: BackgroundTier = BackgroundTier.FOREGROUND; private set
  private var enteredAs: Pair<LearningSpeed, BackgroundTier>? = null

  val available: Boolean get() = throttle != null

  fun wants(s: LearningSpeed = speed()): Boolean = when {
    panelOpen -> false
    opening() -> false
    s == LearningSpeed.FAST -> false
    s == LearningSpeed.GENTLE -> !mainVisible
    else -> !mainVisible || !mainFocused
  }

  /** Which OS priority tier should be active for [s] right now. */
  fun desiredTier(s: LearningSpeed = speed()): BackgroundTier = when {
    !wants(s) -> BackgroundTier.FOREGROUND
    allowUtility && throttle?.supportsUtility == true && awayOnAc() -> BackgroundTier.UTILITY
    else -> BackgroundTier.BACKGROUND
  }

  /** Puts the app where [desiredTier] says. Cheap when nothing changed. */
  @Synchronized fun refresh() {
    val t = throttle ?: return
    val s = try { speed() } catch (_: Throwable) { null }
    val wantTier = try { if (s != null) desiredTier(s) else BackgroundTier.FOREGROUND } catch (_: Throwable) { BackgroundTier.FOREGROUND }
    try {
      if (wantTier != BackgroundTier.FOREGROUND && s != null) {
        val target = s to wantTier
        if (enteredAs != target) {   // (a refusal is remembered for this speed+tier, so it is not asked again at every change)
          val done = if (wantTier == BackgroundTier.UTILITY) t.enterUtility(s) else t.enter(s)
          val label = if (wantTier == BackgroundTier.UTILITY) "utility" else "background"
          if (done != null) {
            log("$label: $done")
            tier = wantTier; inBackground = true; enteredAs = target
            onTierChanged(tier)
          } else {
            if (inBackground) runCatching { t.leave() }
            log("$label: the system refused, running normally")
            tier = BackgroundTier.FOREGROUND; inBackground = false; enteredAs = target
            onTierChanged(tier)
          }
        }
      } else if (enteredAs != null) {
        val done = if (inBackground) t.leave() else null
        if (inBackground) log("foreground: ${done ?: "the system refused, check"}")
        tier = BackgroundTier.FOREGROUND; inBackground = false; enteredAs = null
        onTierChanged(tier)
      }
    } catch (e: Throwable) {
      // A throttle that fails must never leave the person looking at a slow app: try once more to clear it.
      if (inBackground) runCatching { t.leave() }
      tier = BackgroundTier.FOREGROUND; inBackground = false
      enteredAs = if (wantTier != BackgroundTier.FOREGROUND && s != null) s to wantTier else null
      onTierChanged(tier)
      log("background: ${e.javaClass.simpleName}, running normally")
    }
  }

  fun close() {
    mainVisible = true; mainFocused = true; panelOpen = true
    refresh()
  }
}
