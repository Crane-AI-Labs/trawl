package trawl.desktop.ui

import java.awt.Rectangle
import java.awt.Robot
import java.awt.Window
import java.io.File
import javax.imageio.ImageIO
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import trawl.desktop.AppController
import trawl.desktop.Phase
import trawl.desktop.Platform

/**
 * The screenshot driver for the README and the reports, switched on only by `TRAWL_DEMO_DIR`. It drives the controller (it calls the same
 * actions the buttons call; it does not click) and photographs the real window with the AWT Robot, so every picture is the app's own
 * rendering on that OS. It never runs for a normal person: the variable is not set.
 *
 * `TRAWL_DEMO_QUERIES` is `name:query|name:query`, each one answered and photographed after the thumbnails have loaded.
 */
class Demo(private val c: AppController, private val window: Window, private val out: File, private val log: (String) -> Unit = { System.err.println("[demo] $it") }) {
  private val queries: List<Pair<String, String>> =
    (System.getenv("TRAWL_DEMO_QUERIES") ?: "04-photo-dog:a dog|05-photo-beach:beach|06-document-word:school fees|07-screenshot-text:receipt total")
      .split("|").filter { ':' in it }.map { it.substringBefore(':') to it.substringAfter(':') }

  private interface Dwmapi : com.sun.jna.Library {
    fun DwmGetWindowAttribute(hwnd: com.sun.jna.Pointer, attribute: Int, rect: IntArray, size: Int): Int
  }

  /**
   * What a person sees of the window. On Windows 10 and later a window's bounds include an invisible 7 px resize border, so a plain
   * capture of them shows whatever lies behind; the desktop window manager reports the frame that is actually drawn (JNA, no network).
   */
  private fun visibleBounds(): Rectangle {
    val outer = Rectangle(window.locationOnScreen, window.size)
    if (!System.getProperty("os.name").startsWith("Windows")) return outer
    return runCatching {
      val hwnd = com.sun.jna.Native.getComponentPointer(window)
      val rc = IntArray(4)
      val dwm = com.sun.jna.Native.load("dwmapi", Dwmapi::class.java)
      check(dwm.DwmGetWindowAttribute(hwnd, 9, rc, 16) == 0)   // DWMWA_EXTENDED_FRAME_BOUNDS
      Rectangle(rc[0], rc[1], rc[2] - rc[0], rc[3] - rc[1])
    }.getOrDefault(outer)
  }

  /**
   * `TRAWL_DEMO_DENSITY=2` photographs a Retina screen: the window is drawn again, off screen, by Compose's own image scene at that density
   * (an X server without a HiDPI mode cannot show a 2x window, and this is exactly what a Mac's 2x window draws). The scene shows the same
   * controller, so the same data and theme; `TRAWL_WINDOW` gives its size in dp.
   */
  private val density: Float = System.getenv("TRAWL_DEMO_DENSITY")?.toFloatOrNull() ?: 1f

  @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
  private suspend fun sceneShot(name: String, settleMs: Long) {
    val dp = System.getenv("TRAWL_WINDOW")?.split('x')?.mapNotNull { it.trim().toIntOrNull() }?.takeIf { it.size == 2 } ?: listOf(1120, 800)
    val scene = androidx.compose.ui.ImageComposeScene(
      width = (dp[0] * density).toInt(), height = (dp[1] * density).toInt(), density = androidx.compose.ui.unit.Density(density),
      coroutineContext = kotlinx.coroutines.Dispatchers.Unconfined,
    ) { App(c) }
    try {
      var t = 0L
      val steps = (settleMs / 100).toInt().coerceAtLeast(5)
      repeat(steps) { scene.render(t); t += 100_000_000L; delay(100) }   // thumbnails decode off the UI thread; give them frames to land
      val data = scene.render(t).encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG) ?: error("encode failed")
      out.mkdirs()
      File(out, "$name.png").writeBytes(data.bytes)
      log("saved $name.png (${(dp[0] * density).toInt()}x${(dp[1] * density).toInt()} at ${density}x)")
    } finally { scene.close() }
  }

  private suspend fun shot(name: String, settleMs: Long = 1200) {
    if (density != 1f) { sceneShot(name, maxOf(settleMs, 1500)); return }
    delay(settleMs)
    window.toFront()
    delay(300)
    val r = visibleBounds()
    val robot = Robot()
    robot.mouseMove(r.x + r.width + 5, r.y + r.height + 5)   // park the pointer outside the window so no row shows a hover tint
    delay(250)
    val img = robot.createScreenCapture(r)
    out.mkdirs()
    ImageIO.write(img, "png", File(out, "$name.png"))
    log("saved $name.png (${img.width}x${img.height})")
  }

  /** Searches for [q] until it has results (or none, when [present] is false), nudging the search again every few seconds. */
  private suspend fun waitFor(q: String, present: Boolean, seconds: Int = 120): Boolean {
    repeat(seconds / 4) {
      c.setQuery("")
      delay(300)
      c.setQuery(q)
      val ok = withTimeoutOrNull(3_500L) { c.results.first { it.query == q && (it.rows.any { r -> r.file.name == "borehole-survey-notes.txt" } == present) } } != null
      if (ok) return true
    }
    return false
  }

  /** Folder watching, shown end to end: a file appears in Documents and becomes searchable; then it is deleted and stops being found. */
  private suspend fun watching() {
    val docs = Platform.userFolders().documents
    val f = File(docs, "borehole-survey-notes.txt")
    f.writeText("Borehole survey, Mukono village. The drilling crew found water at forty metres. The pump needs a solar panel and a tank.\n")
    val appeared = waitFor("borehole", present = true)
    log("watch: new file searchable = $appeared")
    shot("09-watch-new-file", 800)
    f.delete()
    val gone = waitFor("borehole", present = false)
    log("watch: deleted file gone = $gone")
    shot("10-watch-deleted", 800)
    c.setQuery("")
  }

  /**
   * `TRAWL_DEMO_MODE=settings`: wait until the folders are read, then photograph the whole Settings page top to bottom (the mouse wheel
   * scrolls it, as a person would) and the "Copied" notice. `TRAWL_DEMO_PREFIX` names the files, e.g. `light-1120x800`.
   */
  private suspend fun settingsPages(prefix: String = System.getenv("TRAWL_DEMO_PREFIX") ?: "settings", waitIndex: Boolean = true) {
    if (waitIndex) withTimeoutOrNull(20 * 60_000L) { c.index.first { it.modelReady && it.phase == Phase.UP_TO_DATE && it.total > 0 } }
    delay(2000)
    c.openSettings()
    delay(1500)
    val robot = Robot()
    var previous: java.awt.image.BufferedImage? = null
    for (i in 1..30) {
      shot("$prefix-%02d".format(i), 700)
      val img = ImageIO.read(File(out, "$prefix-%02d.png".format(i)))
      if (previous != null && sameImage(previous, img)) { File(out, "$prefix-%02d.png".format(i)).delete(); break }
      previous = img
      val r = visibleBounds()
      robot.mouseMove(r.x + r.width / 2, r.y + r.height / 2)
      val notches = maxOf(3, (r.height - 200) / 75)   // about a screen minus a little overlap, so no part of the page is skipped
      repeat(notches) { robot.mouseWheel(1); delay(40) }
    }
    c.copyDiagnostics()
    delay(600)
    shot("$prefix-notice", 500)
    log("settings pages done")
  }

  private fun sameImage(a: java.awt.image.BufferedImage, b: java.awt.image.BufferedImage): Boolean {
    if (a.width != b.width || a.height != b.height) return false
    for (y in 0 until a.height step 3) for (x in 0 until a.width step 3) if (a.getRGB(x, y) != b.getRGB(x, y)) return false
    return true
  }

  /**
   * `TRAWL_DEMO_MODE=nomodel`: the app with no models installed. The meter while the words are read (if the walk is slow enough to catch),
   * the meter once they are in, a word that only occurs inside documents (`TRAWL_DEMO_WORD`) found, and the Settings models panel.
   */
  private suspend fun noModel() {
    val prefix = System.getenv("TRAWL_DEMO_PREFIX") ?: "nomodel"
    val word = System.getenv("TRAWL_DEMO_WORD") ?: "utilities"
    if (withTimeoutOrNull(20_000L) { c.index.first { it.readingWords } } != null) shot("$prefix-01-reading-words", 0)
    withTimeoutOrNull(10 * 60_000L) { c.index.first { it.phase == Phase.UP_TO_DATE && it.total > 0 } }
    shot("$prefix-02-names-and-words")
    c.setQuery(word)
    withTimeoutOrNull(60_000L) { c.results.first { it.query == word && it.rows.isNotEmpty() } }
    shot("$prefix-03-word-found", 2500)
    c.setQuery("")
    c.openSettings()
    shot("$prefix-04-settings-models", 1500)
    log("nomodel done")
  }

  /**
   * `TRAWL_DEMO_MODE=laptop`: the scenes named in `TRAWL_DEMO_SCENES` (comma separated), each photographed as `<prefix>-<scene>.png`.
   * Scenes: `firstrun` (shot at once), `indexing` (part-way through a fresh index), then once the index is current: `recent` (blank
   * query), `docs`, `photos`, `none` (queries from `TRAWL_DEMO_Q_DOCS` and `TRAWL_DEMO_Q_PHOTOS`), `settings` (every page, scrolled),
   * `licences`. The scenes that need the keyboard or the mouse (selection, Quick Look, context menu) go through [laptopInteractive].
   */
  private suspend fun laptop() {
    val prefix = System.getenv("TRAWL_DEMO_PREFIX") ?: "laptop"
    val scenes = (System.getenv("TRAWL_DEMO_SCENES") ?: "firstrun").split(",").map { it.trim() }.filter { it.isNotEmpty() }
    val qDocs = System.getenv("TRAWL_DEMO_Q_DOCS") ?: "school fees"
    val qPhotos = System.getenv("TRAWL_DEMO_Q_PHOTOS") ?: "sunset over water"
    delay(2500)
    // The language page comes before the first screen on a fresh start; every scene but its own is shot past it.
    if ("language" in scenes) { shot("$prefix-language", 800); LanguagePage.pending = false; delay(1200) } else LanguagePage.pending = false
    if ("firstrun" in scenes) shot("$prefix-firstrun", 800)
    if ("animlive" in scenes) {
      // The demo running by itself, as a person would see it: one frame every 2 seconds, nothing held.
      delay(1000)
      for (i in 1..8) { shot("$prefix-live-%02d".format(i), 100); delay(1800) }
    }
    if ("anim" in scenes) {
      // One loop, pose by pose: the typing, the results arriving, and the next searches. The demo is held so each frame is exact.
      val frames = listOf(
        FirstRunDemo.Pose(0, 5, false), FirstRunDemo.Pose(0, 14, true), FirstRunDemo.Pose(1, 13, true), FirstRunDemo.Pose(2, 12, false),
        FirstRunDemo.Pose(2, 27, true), FirstRunDemo.Pose(3, 15, true), FirstRunDemo.Pose(4, 10, false), FirstRunDemo.Pose(4, 25, true),
      )
      FirstRunDemo.hold = true
      frames.forEachIndexed { i, f -> FirstRunDemo.pose = f; shot("$prefix-anim-%02d".format(i + 1), 1300) }
      FirstRunDemo.hold = false
      FirstRunDemo.pose = null
    }
    if ("indexing" in scenes) {
      c.start()
      withTimeoutOrNull(10 * 60_000L) { c.index.first { it.phase == Phase.INDEXING && it.understood >= 3 && it.understood < it.total } }
      shot("$prefix-indexing", 200)
      MainUi.current?.let { it.detailOpen = true; shot("$prefix-detail-indexing", 700); it.detailOpen = false; delay(300) }
      // Paused while there is still work: this (not a finished index) is what puts the meter card in place of the quiet status line.
      c.setPaused(true); delay(1500); shot("$prefix-paused", 200); c.setPaused(false)
    }
    if (scenes.any { it !in setOf("firstrun", "indexing", "anim", "animlive", "language") }) {
      withTimeoutOrNull(20 * 60_000L) { c.index.first { it.modelReady && it.phase == Phase.UP_TO_DATE && it.total > 0 } }
      delay(1500)
    }
    suspend fun ask(q: String) {
      c.setQuery(q)
      if (q.isNotBlank()) withTimeoutOrNull(60_000L) { c.results.first { it.query == q } }
      delay(3500)   // thumbnails decode in the background
    }
    for (scene in scenes) when (scene) {
      "recent" -> { ask(""); shot("$prefix-recent") }
      "docs" -> { ask(qDocs); shot("$prefix-docs", 600) }
      "photos" -> { ask(qPhotos); shot("$prefix-photos", 600) }
      "none" -> { ask("zzqxv wkrtp"); shot("$prefix-none", 600) }
      "settings" -> { ask(""); settingsPages(prefix = "$prefix-settings", waitIndex = false) }
      "detail" -> { ask(""); MainUi.current?.let { it.detailOpen = true; shot("$prefix-detail", 900); it.detailOpen = false; delay(300) } }
      "licences" -> { ask(""); c.openSettings(); delay(500); c.openLicences(); shot("$prefix-licences", 800); c.closeLicences(); c.closeSettings() }
    }
    laptopInteractive(prefix, scenes)
    searchScenes(prefix, scenes)
    log("laptop done")
  }

  /**
   * The scenes that need a selection, Quick Look or the context menu: they set the window's own [MainUi] state (what a click or a key sets),
   * so they only run in the real window (density 1). `select` picks the second hit, `quicklook` opens the large preview on it,
   * `menu` opens the right-click menu on it.
   */
  private suspend fun laptopInteractive(prefix: String, scenes: List<String>) {
    val wanted = scenes.filter { it in setOf("select", "quicklook", "menu") }
    if (wanted.isEmpty() || density != 1f) return
    val q = System.getenv("TRAWL_DEMO_Q_DOCS") ?: "school fees"
    c.setQuery(q)
    withTimeoutOrNull(60_000L) { c.results.first { it.query == q && it.rows.isNotEmpty() } }
    delay(3500)
    val ui = MainUi.current ?: return log("no main screen to drive")
    val rows = c.results.value.rows
    val id = (rows.getOrNull(1) ?: rows.firstOrNull() ?: return log("no results to select")).file.id
    ui.selected = id
    delay(800)
    for (scene in wanted) when (scene) {
      "select" -> shot("$prefix-select", 600)
      "quicklook" -> { ui.quickLook = true; shot("$prefix-quicklook", 800); ui.quickLook = false; delay(300) }
      "menu" -> { ui.menu = MenuRequest(id, 360, 260); shot("$prefix-menu", 600); ui.menu = null; delay(300) }
    }
  }

  // ---- SEARCH PACKAGE (A): the scenes for the sort and date chips, the live status line, choosing several results, the photo peek, Recent, Find similar and the empty states ----
  private val searchSceneNames = setOf("sort", "datechips", "daterange", "progressive", "multi", "peek", "similar", "menu-photo", "emptyfilter", "recent-all", "narrow-rows", "recent-doc", "recent-cold", "addon-card")

  /**
   * They set the window's own [MainUi] state and call the controller (what a click does), so they run in the real window only (density 1).
   * `progressive` needs `TRAWL_DEMO_MEANING_DELAY_MS` (a few seconds) so the words-first answer stays up long enough to photograph.
   */
  private suspend fun searchScenes(prefix: String, scenes: List<String>) {
    val wanted = scenes.filter { it in searchSceneNames }
    if (wanted.isEmpty() || density != 1f) return
    val ui = MainUi.current ?: return log("no main screen to drive")
    val qDocs = System.getenv("TRAWL_DEMO_Q_DOCS") ?: "school fees"
    val qPhotos = System.getenv("TRAWL_DEMO_Q_PHOTOS") ?: "sunset over water"
    suspend fun ask(q: String, settle: Long = 3000) {
      c.setQuery(q)
      withTimeoutOrNull(60_000L) { c.results.first { it.query == q && it.complete } }
      delay(settle)
    }
    suspend fun reset() { ui.sortMenu = false; ui.dateMenu = false; ui.dateDialog = false; c.clearSimilar(); c.clearDate(); c.setFilter(com.craneai.findr.core.KindFilter.ALL); ui.picked = emptySet(); ui.recentAll = false; c.setSort(defaultSortFor(false)); ask("", 1500) }
    for (scene in wanted) {
      reset()
      when (scene) {
        "sort" -> {
          ask(qDocs)
          ui.sortMenu = true; shot("$prefix-sort-menu", 500); ui.sortMenu = false; delay(200)
          c.setSort(com.craneai.findr.core.SortOrder.LARGEST); delay(2500); shot("$prefix-sort-largest", 500)
          c.setSort(com.craneai.findr.core.SortOrder.NEWEST); delay(2500); shot("$prefix-sort-newest", 500)
        }
        "datechips" -> {
          ask("")
          ui.dateMenu = true; shot("$prefix-date-menu", 500); ui.dateMenu = false; delay(200)
          c.setDatePreset(com.craneai.findr.core.DatePreset.THIS_MONTH); delay(2500); shot("$prefix-date-month", 500)
          c.clearDate(); delay(800)
          ask("$qDocs last year"); shot("$prefix-date-words-empty", 500)
        }
        "daterange" -> { ui.dateDialog = true; delay(600); shot("$prefix-date-range", 800); ui.dateDialog = false }
        "progressive" -> {
          c.setQuery(qDocs)
          val early = withTimeoutOrNull(60_000L) { c.results.first { it.query == qDocs && !it.complete } }
          log("progressive: words first = ${early != null} (${early?.rows?.size} rows)")
          delay(300); shot("$prefix-progressive-words", 200)
          withTimeoutOrNull(60_000L) { c.results.first { it.query == qDocs && it.complete } }
          delay(1200); shot("$prefix-progressive-done", 200)
        }
        "multi" -> {
          ask(qDocs)
          val ids = c.results.value.rows.take(4).map { it.file.id }
          if (ids.size >= 2) { ui.picked = ids.toCollection(LinkedHashSet()); ui.selected = ids.last(); ui.anchor = ids.first(); ui.focusList(); delay(800); shot("$prefix-multi", 600) }
          else log("multi: fewer than two results")
        }
        "peek" -> {
          val tries = (System.getenv("TRAWL_DEMO_Q_MIXED")
            ?: "school,money,water,family,report,travel,kampala,photo,health,clinic,vaccine,children,notes,project,field,market,people,village,trip,child,community,study,work").split(",")
          // The first query with more than four strong documents and strong photos too (a row of tiles then sits after the fourth document).
          val hit = tries.firstOrNull { q ->
            ask(q, 600)
            val rows = c.results.value.rows.filter { it.strong }
            log("peek: '$q' -> ${rows.count { !isTile(it) }} strong documents, ${rows.count(::isTile)} strong photos")
            rows.count { !isTile(it) } > ResultsLayout.PEEK_AFTER_ROWS && rows.any(::isTile) && isTile(rows.first()).not()
          }
          log("peek: query = $hit")
          // Start clean on that query (as a person typing it would), so the shot shows the list from the top and not the leftovers of the search above.
          if (hit != null) { reset(); ask(hit, 3000) }
          shot("$prefix-peek", 600)
        }
        "similar" -> {
          ask(qPhotos)
          val photo = c.results.value.rows.firstOrNull { isTile(it) }
          if (photo != null) { c.findSimilar(photo.file); delay(4000); shot("$prefix-similar", 600) } else log("similar: no photo to start from")
        }
        "menu-photo" -> {
          ask(qPhotos)
          val photo = c.results.value.rows.firstOrNull { isTile(it) }
          if (photo != null) { ui.selected = photo.file.id; delay(500); ui.menu = MenuRequest(photo.file.id, 40, 60); shot("$prefix-menu-photo", 600); ui.menu = null }
        }
        "emptyfilter" -> { c.setFilter(com.craneai.findr.core.KindFilter.VIDEOS); delay(2500); shot("$prefix-empty-filter", 500) }
        "recent-all" -> { ui.recentAll = true; delay(2500); shot("$prefix-recent-all", 500) }
        "narrow-rows" -> { ask(qDocs); shot("$prefix-rows", 500) }
        "recent-doc" -> {
          // Recent with a PDF (else any document) chosen: the preview pane must show the file's opening text, not "No text to show".
          ask("")
          val row = c.results.value.rows.firstOrNull { it.file.name.endsWith(".pdf", true) } ?: c.results.value.rows.firstOrNull { !isTile(it) }
          if (row != null) { ui.selected = row.file.id; delay(1500); shot("$prefix-recent-doc", 600) } else log("recent-doc: no document in Recent")
        }
        "recent-cold" -> {
          // Recent with the thumbnail cache emptied (as on a first look), photographed almost at once: tiles must not be blank white.
          ask("zzqxv wkrtp")
          try {
            val f = trawl.desktop.Thumbnails::class.java.getDeclaredField("cache"); f.isAccessible = true
            (f.get(trawl.desktop.Thumbnails) as MutableMap<*, *>).clear()
          } catch (t: Throwable) { log("recent-cold: could not empty the cache: ${t.javaClass.simpleName}") }
          c.setQuery("")
          shot("$prefix-recent-cold", 0)
        }
        "addon-card" -> {
          // The Privacy pack shown as installed, with a made-up size a little under the manifest's: the card, button and dialog must agree.
          try {
            val f = trawl.desktop.AddOnManager::class.java.getDeclaredField("_states"); f.isAccessible = true
            @Suppress("UNCHECKED_CAST") val st = f.get(c.addOns) as kotlinx.coroutines.flow.MutableStateFlow<Map<String, trawl.desktop.AddOnState>>
            val def = trawl.desktop.AddOnCatalog.privacy
            st.value = st.value + (def.id to trawl.desktop.AddOnState.Installed(def.sizeBytes - 700_000L))
          } catch (t: Throwable) { log("addon-card: could not set the state: ${t.javaClass.simpleName}") }
          c.openSettings(); delay(500)
          settingsPages(prefix = "$prefix-addon", waitIndex = false)
          c.closeSettings()
        }
      }
    }
    reset()
  }

  private fun defaultSortFor(browse: Boolean) = trawl.desktop.ui.search.defaultSort(browse)

  /**
   * `TRAWL_DEMO_MODE=privacy`: the privacy and safety screens on a made-up home folder (the pack's files already in `models/private`):
   * blurred pictures, the hidden-count chip, a hidden picture's preview, a sensitive file hidden and then shown, every Settings page (the
   * Privacy and Add-ons sections among them), and the lock screen. The "looks private" scores are made up (see [AppController.demoLooksPrivate]).
   * Names come from `TRAWL_DEMO_HIDE` (`name:score|name:score`), `TRAWL_DEMO_SENSITIVE` (a document's name) and `TRAWL_DEMO_PHOTOS` (a query).
   */
  private suspend fun privacyScenes() {
    val p = System.getenv("TRAWL_DEMO_PREFIX") ?: "privacy"
    LanguagePage.pending = false
    withTimeoutOrNull(5 * 60_000L) { c.index.first { it.phase == Phase.UP_TO_DATE && it.total > 0 } }
    delay(2500)
    c.addOns.refresh()
    suspend fun ask(q: String) {
      c.setQuery(q)
      withTimeoutOrNull(30_000L) { c.results.first { it.query == q && it.rows.isNotEmpty() } }
      delay(3500)   // thumbnails decode in the background
    }
    val hide = (System.getenv("TRAWL_DEMO_HIDE") ?: "").split("|").filter { ':' in it }.map { it.substringBefore(':') to it.substringAfter(':').toFloat() }
    log("pack ready = ${c.privacyPackReady()}")
    ask("")
    shot("$p-01-recent-blur-off", 600)
    c.privacy.setBlurEnabled(true)
    for ((name, score) in hide) log("looks-private $name = ${c.demoLooksPrivate(name, score)}")
    delay(1500)
    ask("")
    shot("$p-02-recent-blurred", 800)
    val photos = System.getenv("TRAWL_DEMO_PHOTOS") ?: "photo"
    ask(photos)
    shot("$p-03-photos-blurred", 800)
    val first = hide.firstOrNull()?.first
    MainUi.current?.let { ui ->
      val row = c.results.value.rows.firstOrNull { it.file.name == first } ?: c.results.value.rows.firstOrNull()
      if (row != null) { ui.selected = row.file.id; delay(900); shot("$p-04-preview-hidden", 800) }
    }
    val sensitive = System.getenv("TRAWL_DEMO_SENSITIVE")
    if (sensitive != null) {
      ask("")
      val id = c.results.value.rows.firstOrNull { it.file.name == sensitive }?.file?.id
      if (id != null) {
        c.markSensitive(listOf(id), true); delay(1500)
        shot("$p-05-sensitive-hidden", 800)
        c.privacy.revealWithoutProof(); delay(1500)
        shot("$p-06-sensitive-shown", 800)
        c.privacy.hideSensitive()
      } else log("no document called $sensitive")
    }
    c.setQuery(""); delay(500)
    settingsPages(prefix = "$p-settings", waitIndex = false)
    c.closeSettings(); delay(800)
    c.privacy.enableLock("a made-up demo passphrase".toCharArray(), useOsPrompt = false)
    c.privacy.onScreenOff(); delay(1500)
    shot("$p-07-lock", 800)
    log("privacy done")
  }

  suspend fun run() {
    if (System.getenv("TRAWL_DEMO_MODE") == "privacy") { privacyScenes(); return }
    if (System.getenv("TRAWL_DEMO_MODE") == "laptop") { laptop(); return }
    if (System.getenv("TRAWL_DEMO_MODE") == "settings") { settingsPages(); return }
    if (System.getenv("TRAWL_DEMO_MODE") == "nomodel") { noModel(); return }
    delay(2500)
    shot("01-first-run")
    // The models: found in Downloads, installed through the phone's signed-bundle code.
    c.model.value.found?.let { zip ->
      c.installModels(zip)
      withTimeoutOrNull(30 * 60_000L) { c.model.first { !it.installing && (it.installed != null || it.failed) } }
      shot("02-models-installed")
    }
    c.start()
    // Indexing in progress: wait for the first files to be understood, then photograph the meter part-way.
    withTimeoutOrNull(10 * 60_000L) { c.index.first { it.phase == Phase.INDEXING && it.understood >= 5 && it.understood < it.total } }
    shot("03-indexing", 400)
    withTimeoutOrNull(60 * 60_000L) { c.index.first { it.phase == Phase.UP_TO_DATE } }
    shot("03b-indexed")
    for ((name, q) in queries) {
      c.setQuery(q)
      withTimeoutOrNull(60_000L) { c.results.first { it.query == q && it.rows.isNotEmpty() } }
      shot(name, 3000)
    }
    c.setQuery("")
    c.openSettings()
    shot("08-settings")
    c.closeSettings()
    watching()
    log("done")
  }
}
