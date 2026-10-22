package trawl.desktop.ui

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.json.JSONObject

/** A rectangle in the units AWT windows use (points on a Retina Mac). */
data class Rect(val x: Int, val y: Int, val w: Int, val h: Int) {
  val right get() = x + w
  val bottom get() = y + h

  /** The area two rectangles share, in square units. */
  fun overlap(o: Rect): Long {
    val ow = (minOf(right, o.right) - maxOf(x, o.x)).coerceAtLeast(0)
    val oh = (minOf(bottom, o.bottom) - maxOf(y, o.y)).coerceAtLeast(0)
    return ow.toLong() * oh
  }
}

/** What was on screen when the window last closed (or settled). */
data class SavedWindow(val x: Int, val y: Int, val width: Int, val height: Int, val maximized: Boolean = false)

/** Where to put the window now. [x] and [y] null mean "centre it" (the first-run behaviour). */
data class WindowPlacement(val width: Int, val height: Int, val x: Int?, val y: Int?, val maximized: Boolean, val restored: Boolean)

/**
 * Remembers the window between runs and decides, from the screens that exist right now, whether the remembered placement is still sensible.
 * A window is restored only if its title bar can be grabbed on a current screen; otherwise (the external monitor is gone, the resolution
 * changed) it is opened the first-run way, centred and capped at 85 percent of the usable area. Pure, so every screen change is a test.
 */
object WindowMemory {
  /** The strip of the title bar that must be on a screen for the window to be reachable, in units. */
  private const val GRAB_W = 160
  private const val GRAB_H = 32

  /**
   * [screens] are the usable areas (screen minus menu bar and Dock or taskbar), primary first. [first] is the first-run plan for the primary
   * screen, used whenever nothing sensible is remembered.
   */
  fun plan(saved: SavedWindow?, screens: List<Rect>, first: WindowSizing.Plan): WindowPlacement {
    val fresh = WindowPlacement(first.width, first.height, null, null, maximized = false, restored = false)
    if (saved == null || screens.isEmpty()) return fresh
    if (saved.width <= 0 || saved.height <= 0) return fresh

    val grab = Rect(saved.x, saved.y, minOf(saved.width, GRAB_W), GRAB_H)
    val home = screens.filter { it.overlap(grab) >= (grab.w.toLong() * grab.h) / 2 }.maxByOrNull { it.overlap(Rect(saved.x, saved.y, saved.width, saved.height)) }
      ?: return fresh   // the title bar is off every screen: the monitor it was on is gone

    // Fit the size to the screen it is on: never smaller than the minimum, never bigger than the room.
    val maxW = home.w
    val maxH = home.h
    val w = saved.width.coerceIn(minOf(first.minWidth, maxW), maxW)
    val h = saved.height.coerceIn(minOf(first.minHeight, maxH), maxH)
    // And slide it back inside if a smaller screen left a corner hanging off.
    val x = saved.x.coerceIn(home.x, (home.right - w).coerceAtLeast(home.x))
    val y = saved.y.coerceIn(home.y, (home.bottom - h).coerceAtLeast(home.y))
    return WindowPlacement(w, h, x, y, saved.maximized, restored = true)
  }
}

/** `window-state.json` in the data folder: a few integers, written through a temp file and a rename so a crash never leaves half a file. */
class WindowStore(private val dir: File) {
  private val file = File(dir, "window-state.json")

  fun load(): SavedWindow? = try {
    val o = JSONObject(file.readText())
    SavedWindow(o.getInt("x"), o.getInt("y"), o.getInt("width"), o.getInt("height"), o.optBoolean("maximized", false))
  } catch (_: Exception) {
    null
  }

  fun save(w: SavedWindow) {
    runCatching {
      dir.mkdirs()
      val tmp = File(dir, "window-state.json.tmp")
      val o = JSONObject().put("x", w.x).put("y", w.y).put("width", w.width).put("height", w.height).put("maximized", w.maximized)
      tmp.writeText(o.toString(2))
      Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
  }
}
