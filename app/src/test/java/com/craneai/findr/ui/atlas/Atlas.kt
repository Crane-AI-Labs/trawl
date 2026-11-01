package com.craneai.findr.ui.atlas

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import com.craneai.findr.engine.Edition
import com.craneai.findr.ui.common.LocalPreviewBlur
import com.craneai.findr.ui.common.PreviewBlur
import com.craneai.findr.ui.theme.FindrTheme
import com.craneai.findr.ui.theme.ThemeMode
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowLooper

/** The phones and tablets the atlas draws on. Sizes are what the real devices report in dp. */
enum class Dev(val tag: String, val q: String, val label: String) {
  PHONE("phone", "w411dp-h891dp-port-xhdpi", "phone 411x891dp"),
  TABLET("tablet", "w800dp-h1340dp-port-hdpi", "tablet 800x1340dp"),
  LAND("land", "w891dp-h411dp-land-xhdpi", "phone landscape 891x411dp"),
  TABLET_LAND("tabletland", "w1340dp-h800dp-land-hdpi", "tablet landscape 1340x800dp"),
  SMALL("small", "w360dp-h640dp-port-xhdpi", "small phone 360x640dp"),
  SPLIT("split", "w411dp-h430dp-port-xhdpi", "multi-window half 411x430dp"),
  /** Very tall windows: a scrolling page (Settings, Licences) drawn whole in one picture. */
  TALL("tall", "w411dp-h4200dp-port-hdpi", "phone, whole page 411dp wide"),
  TALLER("taller", "w411dp-h8400dp-port-hdpi", "phone, whole page (large font) 411dp wide"),
  TABLET_TALL("tablettall", "w800dp-h4200dp-port-hdpi", "tablet, whole page 800dp wide"),
}

/** One cell of the matrix: font scale, theme, device, language. */
data class Cfg(val scale: Float = 1.0f, val dark: Boolean = false, val dev: Dev = Dev.PHONE, val lang: String = "en") {
  val scaleTag get() = "s" + (scale * 10).roundToInt().toString().padStart(2, '0')
  val themeTag get() = if (dark) "dark" else "light"
  val tag get() = "${dev.tag}_${scaleTag}_${themeTag}_$lang"
  val qualifiers get() = dev.q.substringBeforeLast("-").let { "$lang-$it-${if (dark) "night" else "notnight"}-${dev.q.substringAfterLast("-")}" }

  companion object {
    val scales = listOf(1.0f, 1.3f, 2.0f)
    /** The full matrix of the brief: 3 font scales x light/dark x phone/tablet x English/Luganda = 24 cells. */
    private val fullAll: List<Cfg> = buildList {
      for (dev in listOf(Dev.PHONE, Dev.TABLET)) for (lang in listOf("en", "lg")) for (dark in listOf(false, true)) for (s in scales) add(Cfg(s, dark, dev, lang))
    }
    /** Six cells that cover every axis once: three scales, dark, tablet, Luganda. */
    val mini: List<Cfg> = listOf(Cfg(1.0f), Cfg(1.3f), Cfg(2.0f), Cfg(2.0f, true), Cfg(1.0f, false, Dev.TABLET), Cfg(1.0f, false, Dev.PHONE, "lg"))
    /** The full 24-cell matrix. The Go edition run draws the six-cell one (it has the same screens minus the models). */
    val full: List<Cfg> get() = if (Edition.GO) mini else fullAll
    /** English only: 12 cells. */
    val english: List<Cfg> get() = full.filter { it.lang == "en" }
    /** The lean set: phone, light + dark at all three scales, plus tablet and Luganda at 1.0 and 2.0 = 10 cells. */
    val lean: List<Cfg> get() = if (Edition.GO) mini else buildList {
      for (dark in listOf(false, true)) for (s in scales) add(Cfg(s, dark, Dev.PHONE, "en"))
      add(Cfg(1.0f, false, Dev.TABLET, "en")); add(Cfg(2.0f, false, Dev.TABLET, "en"))
      add(Cfg(1.0f, false, Dev.PHONE, "lg")); add(Cfg(2.0f, true, Dev.PHONE, "lg"))
    }
    /** Phone, light, English, three scales. */
    val scalesOnly: List<Cfg> = scales.map { Cfg(it) }
    val one: List<Cfg> = listOf(Cfg())
    /** Whole-page captures of scrolling screens: three scales, dark, tablet, Luganda. */
    val tall: List<Cfg> = listOf(
      Cfg(1.0f, false, Dev.TALL), Cfg(1.3f, false, Dev.TALL), Cfg(2.0f, false, Dev.TALLER), Cfg(2.0f, true, Dev.TALLER),
      Cfg(1.0f, true, Dev.TALL), Cfg(1.0f, false, Dev.TABLET_TALL), Cfg(1.0f, false, Dev.TALL, "lg"),
    )
  }
}

/** NONE: only the main window. CENTER/BOTTOM: dialogs and sheets drawn over it on a scrim. TOP_ONLY: just the last window (a menu or popup). */
enum class Overlay { NONE, CENTER, BOTTOM, TOP_ONLY }

/** Fake system bars: what the phone reports to an edge-to-edge window. */
data class Bars(val statusDp: Int = 28, val navDp: Int = 24, val imeDp: Int = 0)

/**
 * Drives the real app screens in a fresh Robolectric activity per configuration and writes PNGs.
 *
 * Why a fresh activity per cell: the qualifiers (size, night, language) and the font scale are read when an activity is created. Setting them
 * under a running activity does not re-create it in Robolectric, so every cell starts from a clean launch, which is also what a real
 * "font size changed while away" does.
 *
 * File name: `<id>__<device>_<scale>_<theme>_<lang>[_go].png`. Every shot also appends one line to `index/<group>.tsv`.
 */
class Atlas(val rule: ComposeTestRule, private val group: String) {
  private var scenario: ActivityScenario<ComponentActivity>? = null
  var cfg: Cfg = Cfg()
  var bars: Bars = Bars()
  val failures = mutableListOf<String>()
  var shots = 0

  val activity: ComponentActivity get() {
    var a: ComponentActivity? = null
    scenario!!.onActivity { a = it }
    return a!!
  }

  companion object {
    val outDir: File = File(System.getenv("ATLAS_DIR") ?: "build/atlas").also { it.mkdirs() }
    val indexDir: File = File(outDir, "index").also { it.mkdirs() }
    val edition: String = if (Edition.GO) "go" else "full"
  }

  private val indexFile = File(indexDir, "${group}${if (Edition.GO) "-go" else ""}.tsv")

  private var blurOn = false
  /** The app's privacy controller (lock, sensitive files), as MainActivity provides it; its lock is off, so screens show as normal. */
  private val privacy: com.craneai.findr.privacy.PrivacyController? by lazy {
    try {
      com.craneai.findr.privacy.PrivacyController(androidx.test.core.app.ApplicationProvider.getApplicationContext(), AtlasEngine())
    } catch (e: Throwable) { null }
  }
  private var current: (@Composable () -> Unit)? = null

  private fun setContentNow(content: @Composable () -> Unit) {
    val c = cfg
    current = content
    scenario!!.onActivity { a ->
      WindowCompat.setDecorFitsSystemWindows(a.window, false)
      a.setContent {
        FindrTheme(mode = if (c.dark) ThemeMode.DARK else ThemeMode.LIGHT) {
          CompositionLocalProvider(
            LocalPreviewBlur provides (if (blurOn) PreviewBlur(enabled = true) else PreviewBlur.Off),
            com.craneai.findr.ui.privacy.LocalPrivacy provides privacy,
          ) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { content() }
          }
        }
      }
    }
  }

  /** Launches a fresh activity under [c] and shows [content] in the real theme. */
  fun launch(c: Cfg, b: Bars = Bars(), blurPreviews: Boolean = false, content: @Composable () -> Unit) {
    close()
    cfg = c; bars = b; blurOn = blurPreviews
    RuntimeEnvironment.setQualifiers(c.qualifiers)
    RuntimeEnvironment.setFontScale(c.scale)
    scenario = ActivityScenario.launch(ComponentActivity::class.java)
    setContentNow(content)
    applyBars(b)
    settle()
  }

  /**
   * A configuration change under a RUNNING screen: the qualifiers and font scale change, the activity is destroyed and re-created, and the same
   * content (so the same view models, which a rotation keeps) is shown again. Saved state (rememberSaveable) comes back through the saved-state registry.
   * Pass [newContent] to build the screen again with NEW view models: that is what process death + restore does (saved state back, view models gone).
   */
  fun reconfigure(c: Cfg, newContent: (@Composable () -> Unit)? = null, b: Bars = bars) {
    val content = newContent ?: current!!
    cfg = c; bars = b
    RuntimeEnvironment.setQualifiers(c.qualifiers)
    RuntimeEnvironment.setFontScale(c.scale)
    scenario!!.recreate()
    setContentNow(content)
    applyBars(b)
    settle(500)
  }

  /** The activity goes to [state] (CREATED = stopped, in Recents or behind Home; STARTED = paused, e.g. in a split screen's other half) and back. */
  fun moveTo(state: androidx.lifecycle.Lifecycle.State) { scenario!!.moveToState(state); settle(200) }

  /** The system Back gesture / button. */
  fun back() {
    scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
    settle(500)
  }

  /** Reports status bar, gesture bar and (optionally) keyboard heights to the window, the way an edge-to-edge window gets them. */
  fun applyBars(b: Bars) {
    bars = b
    val a = activity
    val d = a.resources.displayMetrics.density
    fun px(dp: Int) = (dp * d).roundToInt()
    val insets = WindowInsetsCompat.Builder()
      .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, px(b.statusDp), 0, 0))
      .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, px(b.navDp)))
      .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, px(b.imeDp)))
      .setVisible(WindowInsetsCompat.Type.statusBars(), true)
      .setVisible(WindowInsetsCompat.Type.navigationBars(), true)
      .setVisible(WindowInsetsCompat.Type.ime(), b.imeDp > 0)
      .build()
    a.runOnUiThread { ViewCompat.dispatchApplyWindowInsets(a.window.decorView, insets) }
    settle()
  }

  /** Lets the view models' own timers (the 250 ms search pause), Compose frames and animations run. */
  fun settle(ms: Long = 0) {
    val step = 50L
    var left = ms
    do {
      val t = minOf(step, maxOf(left, 1L))
      ShadowLooper.idleMainLooper(t, TimeUnit.MILLISECONDS)
      rule.mainClock.advanceTimeBy(t)
      rule.waitForIdle()
      left -= t
    } while (left > 0)
  }

  /** Re-creates the activity under a new configuration, keeping nothing but what the app itself saves (a rotation or font change). */
  fun recreateAs(c: Cfg, content: @Composable () -> Unit) = launch(c, bars, content = content)

  fun shot(id: String, title: String, overlay: Overlay = Overlay.NONE, note: String = "") {
    val name = "${id}__${cfg.tag}${if (Edition.GO) "_go" else ""}"
    val roots = rule.onAllNodes(isRoot())
    val count = roots.fetchSemanticsNodes().size
    val base = roots[0].captureToImage().asAndroidBitmap()
    var out = base
    if (count > 1 && overlay == Overlay.TOP_ONLY) {
      out = roots[count - 1].captureToImage().asAndroidBitmap()
    } else if (count > 1 && overlay != Overlay.NONE) {
      out = base.copy(Bitmap.Config.ARGB_8888, true)
      val canvas = Canvas(out)
      canvas.drawColor(0x52000000)
      for (i in 1 until count) {
        val top = roots[i].captureToImage().asAndroidBitmap()
        val x = ((out.width - top.width) / 2f)
        val y = if (overlay == Overlay.BOTTOM) (out.height - top.height).toFloat() else ((out.height - top.height) / 2f)
        canvas.drawBitmap(top, x, y, Paint())
      }
    }
    File(outDir, "$name.png").outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
    synchronized(Atlas) {
      indexFile.appendText(listOf("$name.png", id, title, cfg.scaleTag.drop(1).let { (it.toInt() / 10f).toString() }, cfg.themeTag, cfg.dev.label, cfg.lang, edition, note).joinToString("\t") + "\n")
    }
    shots++
  }

  /**
   * A frame while the activity is paused or stopped. Compose's test rule only sees windows that are resumed, so the window's own view tree is
   * drawn straight into a bitmap instead (what the system keeps as the Recents thumbnail). Recorded in SKIPPED.txt when even that is impossible.
   */
  fun shotOrNote(id: String, title: String, why: String) {
    val name = "${id}__${cfg.tag}${if (Edition.GO) "_go" else ""}"
    try { shot(id, title); return } catch (e: IllegalStateException) {
      if (e.message?.contains("No compose hierarchies") != true) throw e
    }
    var bmp: Bitmap? = null
    try {
      scenario!!.onActivity { act ->
        val v = act.window.decorView
        if (v.width > 0 && v.height > 0) {
          val b = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
          v.draw(Canvas(b)); bmp = b
        }
      }
    } catch (e: Throwable) { bmp = null }
    val b = bmp
    if (b == null) {
      File(indexDir, "SKIPPED.txt").appendText("$name\t$title\t$why\n")
      return
    }
    File(outDir, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    synchronized(Atlas) {
      indexFile.appendText(listOf("$name.png", id, title, cfg.scaleTag.drop(1).let { (it.toInt() / 10f).toString() }, cfg.themeTag, cfg.dev.label, cfg.lang, edition, "drawn from the view tree (window not resumed)").joinToString("\t") + "\n")
    }
    shots++
  }

  fun str(@androidx.annotation.StringRes id: Int, vararg args: Any): String = activity.getString(id, *args)

  fun close() {
    scenario?.close()
    scenario = null
  }

  /** Runs [body] for every cell; one cell failing is recorded and the rest still run. */
  fun forEach(cells: List<Cfg>, label: String, body: (Cfg) -> Unit) {
    for (c in cells) {
      try { body(c) } catch (e: Throwable) {
        val msg = "$label ${c.tag}: ${e::class.simpleName}: ${e.message?.take(300)}"
        failures += msg
        File(indexDir, "FAILURES.txt").appendText(msg + "\n" + e.stackTraceToString().lines().take(14).joinToString("\n") + "\n\n")
      } finally { close() }
    }
  }

  fun assertNoFailures() {
    if (failures.isNotEmpty()) throw AssertionError("${failures.size} atlas cells failed:\n" + failures.joinToString("\n"))
  }
}
