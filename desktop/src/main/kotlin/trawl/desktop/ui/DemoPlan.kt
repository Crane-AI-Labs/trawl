package trawl.desktop.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import trawl.desktop.Os

/**
 * The first screen's demo, as data and timing with no drawing in it, so the pace and the loop are tests. It is the website's hero demo moved
 * to the laptop: a search box types a description at a human pace, what it finds eases in, holds, and the next description follows.
 */
object DemoPlan {
  enum class Scene(val queryKey: String, val foundCount: Int) {
    BIRTHDAY("demo_q_birthday", 3),
    WIFI("demo_q_wifi", 1),
    RECEIPT("demo_q_receipt", 1),
    DOGS("demo_q_dogs", 2),
    VOICE("demo_q_voice", 1),
  }

  val scenes: List<Scene> = Scene.entries

  /** A short pause before the first description is typed after the screen is painted, and between the end of typing and the results. */
  const val FIRST_DELAY_MS = 700L
  const val AFTER_TYPE_MS = 360L

  /** How long what was found stays on screen before the next search starts. */
  const val HOLD_MS = 3600L

  /** The gap after the old results leave and before the first letter of the next search. */
  const val GAP_MS = 380L

  /** Each letter takes 60 to 114 ms, like the website: unhurried, a little uneven, the same every loop (so a test can count it). */
  const val TYPE_MIN_MS = 60L
  const val TYPE_SPREAD_MS = 55L

  fun next(scene: Int): Int = (scene + 1) % scenes.size

  /** The wait before letter [index] (0-based) of scene [scene] appears. Deterministic: a hash, not a random number. */
  fun typeDelay(scene: Int, index: Int): Long {
    val h = (scene * 31 + index * 17 + 7) * 2654435761L
    return TYPE_MIN_MS + ((h ushr 8) and 0xFFFF) % TYPE_SPREAD_MS
  }

  /** From the end of the hold to the results of the next scene being on screen, for a query of [length] letters. */
  fun transitionMs(scene: Int, length: Int): Long = GAP_MS + (0 until length).sumOf { typeDelay(scene, it) } + AFTER_TYPE_MS

  /** One whole loop, for queries of the given lengths, hold included. */
  fun loopMs(lengths: List<Int>): Long = lengths.indices.sumOf { HOLD_MS + transitionMs(next(it), lengths[next(it)]) }
}

/** The demo's moving parts: which scene, how many letters are typed, whether the results are showing. Plain observable state, driven by [run]. */
class DemoState(private val queries: List<String>) {
  enum class Phase { TYPING, SHOWN }

  var scene by mutableIntStateOf(0)
  var typed by mutableIntStateOf(queries[0].length)
  var shown by mutableStateOf(true)
  var phase by mutableStateOf(Phase.SHOWN)

  val query: String get() = queries[scene].take(typed)
  val complete: Boolean get() = phase == Phase.SHOWN

  /** The calm end state of the current scene: the whole query, the results. What Pause and reduced motion show. */
  fun finish() {
    typed = queries[scene].length
    shown = true
    phase = Phase.SHOWN
  }

  /** Jump to a scene in either state; the screenshot driver uses it to shoot a frame sequence. */
  fun show(sceneIndex: Int, lettersTyped: Int = queries[sceneIndex].length, resultsShown: Boolean = true) {
    scene = sceneIndex
    typed = lettersTyped.coerceIn(0, queries[sceneIndex].length)
    shown = resultsShown
    phase = if (resultsShown) Phase.SHOWN else Phase.TYPING
  }

  private fun startNext() {
    scene = DemoPlan.next(scene)
    typed = 0
    shown = false
    phase = Phase.TYPING
  }

  /**
   * Plays on from wherever the state is, until the caller cancels it (a pause, hovering, a hidden window). Every step is one small change to
   * the state, so a cancel leaves a state this function can pick up again: half-typed text resumes typing, shown results resume the hold.
   * [wait] is the delay (replaced in tests so no time passes).
   */
  suspend fun run(wait: suspend (Long) -> Unit = { delay(it) }) {
    while (true) {
      if (phase == Phase.SHOWN) {
        wait(DemoPlan.HOLD_MS)
        startNext()
        wait(DemoPlan.GAP_MS)
      }
      val q = queries[scene]
      while (typed < q.length) {
        wait(DemoPlan.typeDelay(scene, typed))
        typed++
      }
      wait(DemoPlan.AFTER_TYPE_MS)
      shown = true
      phase = Phase.SHOWN
    }
  }
}

/** Whether the computer is set to cut down on motion. Each system answers a different way; each answer is parsed by a pure function. */
object ReduceMotion {
  /** macOS: `defaults read com.apple.universalaccess reduceMotion` prints 1. */
  fun fromMac(output: String?): Boolean = output?.trim() == "1"

  /** GNOME: `gsettings get org.gnome.desktop.interface enable-animations` prints false when animations are off. */
  fun fromGnome(output: String?): Boolean = output?.trim()?.equals("false", ignoreCase = true) == true

  /**
   * Windows: "Show animations in Windows" (Settings, Accessibility, Visual effects) off clears the window-animation flag, which
   * `reg query "HKCU\Control Panel\Desktop\WindowMetrics" /v MinAnimate` prints as `MinAnimate REG_SZ 0`.
   */
  fun fromWindows(output: String?): Boolean {
    val line = output?.lineSequence()?.firstOrNull { it.contains("MinAnimate") } ?: return false
    return line.trim().split(Regex("\\s+")).lastOrNull() == "0"
  }

  /** TRAWL_REDUCE_MOTION=1 or 0 overrides the system (screenshots, and anyone who wants it either way). */
  fun fromOverride(value: String?): Boolean? = when (value?.trim()) {
    "1", "true" -> true
    "0", "false" -> false
    else -> null
  }

  private fun run(vararg cmd: String): String? = runCatching {
    val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
    if (!p.waitFor(1500, TimeUnit.MILLISECONDS)) { p.destroyForcibly(); return null }
    if (p.exitValue() != 0) null else p.inputStream.readBytes().toString(Charsets.UTF_8)
  }.getOrNull()

  /** Asks the system once. Slow (starts a program), so call it off the UI thread. A system that cannot be asked is taken as "motion is fine". */
  fun detect(os: Os, env: Map<String, String> = System.getenv()): Boolean {
    fromOverride(env["TRAWL_REDUCE_MOTION"])?.let { return it }
    return when (os) {
      Os.MAC -> fromMac(run("defaults", "read", "com.apple.universalaccess", "reduceMotion"))
      Os.WINDOWS -> fromWindows(run("reg", "query", "HKCU\\Control Panel\\Desktop\\WindowMetrics", "/v", "MinAnimate"))
      Os.LINUX -> fromGnome(run("gsettings", "get", "org.gnome.desktop.interface", "enable-animations"))
    }
  }
}

/** Which kind of input was last used, so "focus" can mean keyboard focus: a click on a button leaves focus there, and that must not freeze the demo. */
object InputModality {
  @Volatile var keyboard: Boolean = true
}

/** Whether the window is showing. The demo does no work while it is minimised. */
object WindowActivity {
  var minimized by mutableStateOf(false)
}
