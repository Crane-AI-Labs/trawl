package trawl.desktop.shell

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.SpanStyle
import com.craneai.findr.core.FileKind
import com.craneai.findr.core.IndexedFile
import java.awt.Window
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import trawl.desktop.MediaDecoder
import trawl.desktop.MediaInfo
import trawl.desktop.Os
import trawl.desktop.PcmOut
import trawl.desktop.PlayState
import trawl.desktop.PreviewPlayer
import trawl.desktop.ui.quick.PreviewShape
import trawl.desktop.ui.quick.highlightTerms
import trawl.desktop.ui.quick.quickKeyFor

/** A window object without a screen: the raise logic only passes it to its native steps, which are fakes here. */
@Suppress("DEPRECATION")
internal fun fakeWindow(): Window {
  val rf = sun.reflect.ReflectionFactory.getReflectionFactory()
  val ctor = rf.newConstructorForSerialization(Window::class.java, Any::class.java.getDeclaredConstructor())
  return ctor.newInstance() as Window
}

class QuickPanelSizingTest {
  @Test fun aBigLaptopGetsTheFullSpotlightPanel() {
    val s = QuickPanelSizing.of(1512, 982, preview = false)
    assertEquals(760, s.width)
    assertEquals(620, s.height)
  }

  @Test fun aSmallLaptopGetsASmallerPanelThatStillFits() {
    val s = QuickPanelSizing.of(1280, 800, preview = false)
    assertEquals(760, s.width)
    assertEquals(560, s.height)
    val p = QuickPanelSizing.of(1280, 800, preview = true)
    assertTrue(p.width <= 1280 * 0.86 + 1 && p.height <= 800 * 0.84 + 1, "the preview panel keeps a margin on a 1280 x 800 screen: $p")
  }

  @Test fun theListPanelIsNeverWiderThanNinetyPercentOfASmallScreen() {
    assertTrue(QuickPanelSizing.of(800, 600, false).width <= 720)
    assertTrue(QuickPanelSizing.of(300, 300, false).width >= QuickPanelSizing.MIN_WIDTH)
  }

  @Test fun thePreviewIsWiderAndTallerThanTheList() {
    val l = QuickPanelSizing.of(1512, 982, false)
    val p = QuickPanelSizing.of(1512, 982, true)
    assertTrue(p.width > l.width && p.height > l.height - 1)
    assertEquals(1040, p.width)
    assertEquals(700, p.height)
  }

  @Test fun itIsCentredAndSitsInTheUpperThird() {
    assertEquals(376, QuickPanelSizing.left(0, 1512, 760))
    assertEquals(1920 + 580, QuickPanelSizing.left(1920, 1920, 760))   // a second screen to the right
    assertEquals(157, QuickPanelSizing.top(0, 982, 620))
  }

  @Test fun itMovesUpRatherThanRunOffTheBottom() {
    val top = QuickPanelSizing.top(0, 800, 672)
    assertTrue(top + 672 <= 800, "the card ends on screen: top=$top")
    assertTrue(top >= 0)
  }
}

class RaiseLadderTest {
  @Test fun everySystemGetsFourAttemptsAndEachAttemptDoesSomething() {
    for (os in Os.entries) {
      assertEquals(4, RaiseLadder.ATTEMPTS)
      for (n in 0 until RaiseLadder.ATTEMPTS) {
        assertTrue(RaiseLadder.steps(os, n).isNotEmpty(), "$os attempt $n")
        assertNotNull(RaiseLadder.checkAfterMs(n))
      }
      assertNull(RaiseLadder.checkAfterMs(RaiseLadder.ATTEMPTS))
    }
  }

  @Test fun anOrdinaryWindowIsNeverPutOnTheMacOverlayLevel() {
    assertTrue(RaiseStep.MAC_JOIN_SPACES in RaiseLadder.steps(Os.MAC, 0))
    assertTrue(RaiseStep.MAC_JOIN_SPACES !in RaiseLadder.steps(Os.MAC, 0, overlay = false), "the main window keeps its level and its Space")
    for (n in 0 until RaiseLadder.ATTEMPTS) assertTrue(RaiseLadder.steps(Os.MAC, n, overlay = false).isNotEmpty())
    assertEquals(RaiseLadder.steps(Os.WINDOWS, 1), RaiseLadder.steps(Os.WINDOWS, 1, overlay = false))
  }

  @Test fun theWaitsGrowSoALateSystemAnswerIsStillCaught() {
    val waits = (0 until 4).map { RaiseLadder.checkAfterMs(it)!! }
    assertEquals(waits.sorted(), waits)
    assertTrue(waits.last() <= 600, "the whole ladder is over within about a second: $waits")
  }

  @Test fun theMacLadderActivatesTheAppAndJoinsEverySpaceFirst() {
    val first = RaiseLadder.steps(Os.MAC, 0)
    assertTrue(RaiseStep.MAC_JOIN_SPACES in first && RaiseStep.MAC_ACTIVATE in first && RaiseStep.MAC_ORDER_FRONT in first)
    assertTrue(first.indexOf(RaiseStep.MAC_JOIN_SPACES) < first.indexOf(RaiseStep.MAC_ACTIVATE), "spaces are set before the app is activated, so activation lands on this Space")
    assertTrue(RaiseStep.MAC_ACTIVATE_WORKSPACE in RaiseLadder.steps(Os.MAC, 1))
  }

  @Test fun theWindowsLadderEscalatesFromAllowForegroundToAttachInputToAKeyNudge() {
    assertTrue(RaiseStep.WIN_ALLOW_AND_FOREGROUND in RaiseLadder.steps(Os.WINDOWS, 0))
    assertTrue(RaiseStep.WIN_ATTACH_INPUT in RaiseLadder.steps(Os.WINDOWS, 1))
    assertFalse(RaiseStep.WIN_INPUT_NUDGE in RaiseLadder.steps(Os.WINDOWS, 1))
    assertTrue(RaiseStep.WIN_INPUT_NUDGE in RaiseLadder.steps(Os.WINDOWS, 2))
  }

  @Test fun theX11LadderAsksTheWindowManagerFirstAndFallsBackToRaiseAndFocus() {
    val first = RaiseLadder.steps(Os.LINUX, 0)
    assertEquals(RaiseStep.X11_ACTIVATE_MESSAGE, first.first())
    assertTrue(RaiseStep.X11_RAISE_AND_FOCUS in first)
    assertTrue(RaiseStep.X11_RAISE_AND_FOCUS in RaiseLadder.steps(Os.LINUX, 1))
  }

  @Test fun noStepIsFromAnotherSystem() {
    val mac = RaiseLadder.steps(Os.MAC, 0) + RaiseLadder.steps(Os.MAC, 3)
    assertTrue(mac.none { it.name.startsWith("WIN_") || it.name.startsWith("X11_") })
    val win = (0..3).flatMap { RaiseLadder.steps(Os.WINDOWS, it) }
    assertTrue(win.none { it.name.startsWith("MAC_") || it.name.startsWith("X11_") })
    val lin = (0..3).flatMap { RaiseLadder.steps(Os.LINUX, it) }
    assertTrue(lin.none { it.name.startsWith("MAC_") || it.name.startsWith("WIN_") })
  }
}

class PanelRaiserTest {
  private class Rig(os: Os, private val focusAfterAttempts: Int?) {
    val ran = CopyOnWriteArrayList<RaiseStep>()
    val queue = ArrayDeque<Pair<Long, () -> Unit>>()
    val settled = mutableListOf<Pair<Int, Boolean>>()
    private var scheduled = 0
    private var focused = false
    val raiser = PanelRaiser(
      os,
      RaiseNative { step, _ -> ran += step; true },
      hasFocus = { focused },
      later = { ms, work -> scheduled++; queue.addLast(ms to work) },
      onSettled = { n, ok -> settled += n to ok },
    )

    /** Runs the next scheduled check; the system "answers" (focus arrives) once [focusAfterAttempts] attempts have run. */
    fun tick() {
      val (_, work) = queue.removeFirst()
      if (focusAfterAttempts != null && scheduled >= focusAfterAttempts) focused = true
      work()
    }
  }

  @Test fun thePanelThatTakesFocusAtOnceStopsAfterOneAttempt() {
    val r = Rig(Os.WINDOWS, 1)
    r.raiser.raise(fakeWindow())
    assertEquals(RaiseLadder.steps(Os.WINDOWS, 0), r.ran.toList(), "the first attempt runs at once, before any wait")
    assertEquals(70L, r.queue.first().first)
    r.tick()
    assertEquals(listOf(1 to true), r.settled)
    assertTrue(r.queue.isEmpty(), "no further attempts once focused")
  }

  @Test fun aPanelTheSystemRefusesGetsEveryStrongerAttemptThenSettlesUnfocused() {
    val r = Rig(Os.WINDOWS, null)
    r.raiser.raise(fakeWindow())
    repeat(4) { r.tick() }
    assertEquals(listOf(4 to false), r.settled)
    val expected = (0 until 4).flatMap { RaiseLadder.steps(Os.WINDOWS, it) }
    assertEquals(expected, r.ran.toList())
    assertTrue(r.queue.isEmpty())
  }

  @Test fun aLaterAttemptThatWinsStopsTheLadder() {
    val r = Rig(Os.LINUX, 3)
    r.raiser.raise(fakeWindow())
    repeat(3) { r.tick() }
    assertEquals(listOf(3 to true), r.settled)
  }

  @Test fun pressingTheShortcutAgainSupersedesTheOldSequence() {
    val r = Rig(Os.LINUX, null)
    val w = fakeWindow()
    r.raiser.raise(w)
    r.raiser.raise(w)   // second press: the first sequence's pending check must do nothing
    val first = r.queue.removeFirst()
    first.second()
    assertTrue(r.settled.isEmpty() && r.queue.size == 1, "the stale check neither settled nor scheduled more")
    r.tick()
    assertTrue(r.queue.size == 1, "the live sequence goes on to its next attempt")
  }

  @Test fun hidingThePanelCancelsTheSequence() {
    val r = Rig(Os.MAC, null)
    r.raiser.raise(fakeWindow())
    r.raiser.cancel()
    r.queue.removeFirst().second()
    assertTrue(r.queue.isEmpty() && r.settled.isEmpty())
  }

  @Test fun aStepThatThrowsDoesNotStopTheRest() {
    val ran = mutableListOf<RaiseStep>()
    val r = PanelRaiser(Os.MAC, RaiseNative { s, _ -> ran += s; if (s == RaiseStep.MAC_JOIN_SPACES) error("boom") else true }, { true }, { _, _ -> })
    r.raise(fakeWindow())
    assertEquals(RaiseLadder.steps(Os.MAC, 0), ran)
  }
}

class QuickKeyV2Test {
  @Test fun spaceOnAnEmptyQueryTogglesThePreviewAndOtherwiseTypes() {
    assertEquals(QuickKey.TOGGLE_PREVIEW, quickKeyFor(Key.Spacebar, true, false, false, Os.LINUX, queryBlank = true))
    assertNull(quickKeyFor(Key.Spacebar, true, false, false, Os.LINUX, queryBlank = false), "a space inside a query is a space")
    assertNull(quickKeyFor(Key.Spacebar, false, false, false, Os.LINUX, queryBlank = true), "key up does nothing")
  }

  @Test fun modifierYTogglesThePreviewAnywhereAndWithShiftAsksForTheFullPreview() {
    assertEquals(QuickKey.TOGGLE_PREVIEW, quickKeyFor(Key.Y, true, ctrl = true, meta = false, os = Os.LINUX))
    assertEquals(QuickKey.TOGGLE_PREVIEW, quickKeyFor(Key.Y, true, ctrl = false, meta = true, os = Os.MAC))
    assertEquals(QuickKey.FULL_PREVIEW, quickKeyFor(Key.Y, true, ctrl = false, meta = true, os = Os.MAC, shift = true))
    assertEquals(QuickKey.FULL_PREVIEW, quickKeyFor(Key.Y, true, ctrl = true, meta = false, os = Os.WINDOWS, shift = true))
    assertNull(quickKeyFor(Key.Y, true, ctrl = false, meta = false, os = Os.LINUX), "a plain y is typed")
    assertNull(quickKeyFor(Key.Y, true, ctrl = true, meta = false, os = Os.MAC), "Ctrl+Y is not the Mac's key")
  }

  @Test fun theOldKeysStillMeanWhatTheyDid() {
    assertEquals(QuickKey.OPEN, quickKeyFor(Key.Enter, true, false, false, Os.LINUX))
    assertEquals(QuickKey.SHOW_IN_FOLDER, quickKeyFor(Key.Enter, true, true, false, Os.LINUX))
    assertEquals(QuickKey.SHOW_IN_FOLDER, quickKeyFor(Key.Enter, true, false, true, Os.MAC))
    assertEquals(QuickKey.CLOSE, quickKeyFor(Key.Escape, true, false, false, Os.LINUX))
  }
}

/** A search whose preview and system preview are fakes, so the panel's preview rules are tested without a screen. */
private class PreviewSearch(private val files: List<IndexedFile>, val full: FakeFull = FakeFull(true)) : QuickSearchApi {
  val lock = MutableStateFlow(false)
  override val locked: StateFlow<Boolean> get() = lock
  override fun search(query: String): Flow<List<QuickHit>> = flow { emit(files.map { QuickHit(it, null) }) }
  override fun open(file: IndexedFile) {}
  override fun showInFolder(file: IndexedFile) {}
  override val preview: QuickPreviewApi = object : QuickPreviewApi {
    override suspend fun picture(file: IndexedFile, side: Int, blurred: Boolean): ImageBitmap? = null
    override suspend fun frame(file: IndexedFile, momentMs: Long, side: Int): ImageBitmap? = null
    override suspend fun lead(file: IndexedFile): String? = null
    override fun fileOf(file: IndexedFile): File = File("/tmp/${file.name}")
    override val full: FullPreview get() = this@PreviewSearch.full
  }
}

internal class FakeFull(override val available: Boolean) : FullPreview {
  val opened = mutableListOf<Pair<List<File>, Int>>()
  var closes = 0
  private val _open = MutableStateFlow(false)
  override val open: StateFlow<Boolean> get() = _open
  override fun show(files: List<File>, index: Int): Boolean { opened += files to index; _open.value = true; return true }
  override fun close() { closes++; _open.value = false }
}

class QuickPanelPreviewTest {
  private val files = listOf(
    fakeFile(1, "invoice-march.pdf", FileKind.PDF), fakeFile(2, "secret-selfie.jpg", FileKind.IMAGE), fakeFile(3, "holiday.jpg", FileKind.IMAGE),
  )

  private fun eventually(what: String, cond: () -> Boolean) {
    val end = System.currentTimeMillis() + 3000
    while (System.currentTimeMillis() < end) { if (cond()) return; Thread.sleep(10) }
    throw AssertionError("timed out waiting for $what")
  }

  private fun rig(block: (QuickPanelState, PreviewSearch, IntArray) -> Unit) = runBlocking {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val api = PreviewSearch(files)
    val closed = intArrayOf(0)
    val st = QuickPanelState(api, scope, onClose = { closed[0]++ }, debounceMs = 10)
    try { eventually("recent") { st.hits.value.size == 3 }; block(st, api, closed) } finally { scope.cancel() }
  }

  @Test fun thePreviewOpensAndClosesAndFollowsTheSelection() = rig { st, _, _ ->
    assertFalse(st.previewing.value)
    st.onKey(QuickKey.TOGGLE_PREVIEW)
    assertTrue(st.previewing.value)
    st.onKey(QuickKey.DOWN)
    assertTrue(st.previewing.value, "stepping to the next file keeps the preview open")
    assertEquals("secret-selfie.jpg", st.current?.file?.name)
    st.onKey(QuickKey.TOGGLE_PREVIEW)
    assertFalse(st.previewing.value)
  }

  @Test fun escClosesThePreviewBeforeThePanel() = rig { st, _, closed ->
    st.onKey(QuickKey.TOGGLE_PREVIEW)
    st.onKey(QuickKey.CLOSE)
    assertFalse(st.previewing.value)
    assertEquals(0, closed[0], "the first Esc only closed the preview")
    st.onKey(QuickKey.CLOSE)
    assertEquals(1, closed[0])
  }

  @Test fun escClosesTheSystemPreviewBeforeEverythingElse() = rig { st, api, closed ->
    st.onKey(QuickKey.TOGGLE_PREVIEW)
    assertTrue(st.fullPreview())
    st.onKey(QuickKey.CLOSE)
    assertEquals(1, api.full.closes)
    assertTrue(st.previewing.value, "the in-panel preview was not touched by that Esc")
    assertEquals(0, closed[0])
  }

  @Test fun enterOpensAndClosesTheWholePanelNotJustThePreview() = rig { st, _, closed ->
    st.onKey(QuickKey.TOGGLE_PREVIEW)
    st.onKey(QuickKey.OPEN)
    assertEquals(1, closed[0])
    assertFalse(st.previewing.value)
  }

  @Test fun lockingClosesThePreviewAndTheSystemPreview() = rig { st, api, _ ->
    st.onKey(QuickKey.TOGGLE_PREVIEW)
    st.fullPreview()
    api.lock.value = true
    eventually("preview closed by the lock") { !st.previewing.value }
    eventually("system preview closed by the lock") { api.full.closes >= 1 }
    st.onKey(QuickKey.TOGGLE_PREVIEW)
    assertFalse(st.previewing.value, "nothing opens while locked")
  }

  @Test fun theSystemPreviewNeverGetsAHiddenFileAndStepsThroughTheRest() = rig { st, api, _ ->
    val hides = { f: IndexedFile -> f.name == "secret-selfie.jpg" }
    assertTrue(st.fullPreview(hides))
    val (shown, at) = api.full.opened.single()
    assertEquals(listOf("invoice-march.pdf", "holiday.jpg"), shown.map { it.name })
    assertEquals(0, at)
    api.full.close()
    st.onKey(QuickKey.DOWN)   // now on the hidden one
    assertFalse(st.fullPreview(hides), "the selected file is hidden: nothing is handed to the system")
    assertEquals(1, api.full.opened.size)
    st.onKey(QuickKey.DOWN)
    assertTrue(st.fullPreview(hides))
    assertEquals(1, api.full.opened.last().second, "the index follows the file after the hidden one was dropped")
  }

  @Test fun noSystemPreviewMeansFullPreviewDoesNothing() = runBlocking {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val api = PreviewSearch(files, FakeFull(false))
    val st = QuickPanelState(api, scope, onClose = {}, debounceMs = 10)
    try { eventually("recent") { st.hits.value.size == 3 }; assertFalse(st.fullPreview()); assertTrue(api.full.opened.isEmpty()) } finally { scope.cancel() }
  }

  @Test fun hidingThePanelDismissesBothPreviews() = rig { st, api, _ ->
    st.onKey(QuickKey.TOGGLE_PREVIEW); st.fullPreview()
    st.dismissPreviews()
    assertFalse(st.previewing.value)
    assertTrue(api.full.closes >= 1)
  }
}

class FullPreviewTest {
  private fun tmpFile(name: String): File = Files.createTempDirectory("trawl-ql").toFile().resolve(name).also { it.writeText("x") }

  @Test fun theCommandLineKeepsOnlyRealFilesAndRemapsTheIndex() {
    val a = tmpFile("a.txt"); val b = File("/nonexistent/b.txt"); val c = tmpFile("c.txt")
    val args = QuickLookArgs.build(listOf(a, b, c), 2)!!
    assertEquals(listOf("show", "1", a.absolutePath, c.absolutePath), args)
    assertNull(QuickLookArgs.build(listOf(b), 0))
    assertEquals("0", QuickLookArgs.build(listOf(a, c), 7)!![1], "an index that pointed nowhere starts at the first file")
  }

  @Test fun theCommandLineIsCapped() {
    val f = tmpFile("x.txt")
    val args = QuickLookArgs.build(List(100) { f }, 0)!!
    assertEquals(QuickLookArgs.MAX_FILES + 2, args.size)
  }

  private class Proc(private val lines: java.util.concurrent.LinkedBlockingQueue<String?>) : QuickLookProcess {
    @Volatile var inputClosed = false
    @Volatile var destroyed = false
    override fun readLine(): String? = lines.take()
    override fun closeInput() { inputClosed = true; lines.put("closed") }
    override fun destroy() { destroyed = true }
  }

  @Test fun theFacilityIsOpenFromShowUntilTheHelperSaysClosed() {
    val q = java.util.concurrent.LinkedBlockingQueue<String?>()
    val seen = mutableListOf<List<String>>()
    val ql = QuickLookMac(prepare = { listOf("/helper") }, start = { cmd -> seen += cmd; Proc(q) })
    val f = tmpFile("a.txt")
    assertFalse(ql.open.value)
    assertTrue(ql.show(listOf(f), 0))
    assertEquals(listOf("/helper", "show", "0", f.absolutePath), seen.single())
    assertTrue(ql.open.value)
    q.put("shown")
    Thread.sleep(50)
    assertTrue(ql.open.value)
    q.put("closed")
    val end = System.currentTimeMillis() + 3000
    while (ql.open.value && System.currentTimeMillis() < end) Thread.sleep(10)
    assertFalse(ql.open.value)
  }

  @Test fun closeClosesTheHelpersInput() {
    val q = java.util.concurrent.LinkedBlockingQueue<String?>()
    val p = Proc(q)
    val ql = QuickLookMac(prepare = { listOf("/helper") }, start = { p })
    ql.show(listOf(tmpFile("a.txt")), 0)
    ql.close()
    assertTrue(p.inputClosed)
    val end = System.currentTimeMillis() + 3000
    while (ql.open.value && System.currentTimeMillis() < end) Thread.sleep(10)
    assertFalse(ql.open.value)
  }

  @Test fun anErrorLineEndsIt() {
    val q = java.util.concurrent.LinkedBlockingQueue<String?>()
    val ql = QuickLookMac(prepare = { listOf("/helper") }, start = { Proc(q) })
    ql.show(listOf(tmpFile("a.txt")), 0)
    q.put("error:nopanel")
    val end = System.currentTimeMillis() + 3000
    while (ql.open.value && System.currentTimeMillis() < end) Thread.sleep(10)
    assertFalse(ql.open.value)
  }

  @Test fun showingNothingStartsNothing() {
    var started = 0
    val ql = QuickLookMac(prepare = { listOf("/helper") }, start = { started++; Proc(java.util.concurrent.LinkedBlockingQueue()) })
    assertFalse(ql.show(listOf(File("/nonexistent/z")), 0))
    assertEquals(0, started)
    assertFalse(ql.open.value)
  }

  @Test fun aHelperThatCannotStartIsAFalseNotACrash() {
    val ql = QuickLookMac(prepare = { listOf("/helper") }, start = { error("no such program") })
    assertFalse(ql.show(listOf(tmpFile("a.txt")), 0))
    assertFalse(ql.open.value)
  }

  @Test fun onlyAMacWithTheHelperGetsAFacility() {
    assertFalse(QuickLookMac.forOs(Os.LINUX, hasResource = { true }).available)
    assertFalse(QuickLookMac.forOs(Os.WINDOWS, hasResource = { true }).available)
    assertFalse(QuickLookMac.forOs(Os.MAC, hasResource = { false }).available, "a Mac build made without the helper offers none")
    assertTrue(QuickLookMac.forOs(Os.MAC, hasResource = { true }).available)
  }
}

class PreviewPlayerTest {
  private class FakeOut : PcmOut {
    val written = java.util.concurrent.atomic.AtomicInteger()
    @Volatile var closed = false
    @Volatile var finished = false
    override fun write(bytes: ByteArray, length: Int) { written.addAndGet(length) }
    override fun playedMs(): Long = written.get() / 32L   // 16 kHz s16 mono = 32 bytes per ms
    override fun finish() { finished = true }
    override fun close() { closed = true }
  }

  private class FakeDecoder(val chunks: Int, val block: java.util.concurrent.CountDownLatch? = null, val fail: Boolean = false) : MediaDecoder {
    @Volatile var closed = false
    var startedAt = -1L
    override val name = "fake"
    override fun probe(file: File) = MediaInfo(1000, false, true)
    override fun frame(file: File, timeMs: Long, maxSide: Int): ByteArray? = null
    override fun audio(file: File, startMs: Long, spanMs: Long, sink: (ByteArray, Int) -> Unit) {
      startedAt = startMs
      if (fail) error("cannot decode")
      repeat(chunks) { sink(ByteArray(3200), 3200); if (it == 0) block?.await() }
    }
    override fun close() { closed = true }
  }

  private fun waitFor(p: PreviewPlayer, what: String, cond: (PlayState) -> Boolean) {
    val end = System.currentTimeMillis() + 3000
    while (System.currentTimeMillis() < end) { if (cond(p.state.value)) return; Thread.sleep(10) }
    throw AssertionError("timed out waiting for $what; state=${p.state.value}")
  }

  @Test fun aClipPlaysFromTheMomentAndEndsDone() {
    val out = FakeOut(); val dec = FakeDecoder(5)
    val p = PreviewPlayer({ dec }, { out })
    assertEquals(PlayState.Idle, p.state.value)
    p.play(File("/tmp/a.mp3"), 83_000)
    waitFor(p, "done") { it is PlayState.Done }
    assertEquals(83_000, dec.startedAt)
    assertEquals(3200 * 5, out.written.get())
    assertTrue(out.finished && out.closed && dec.closed, "the output drained and both were closed")
  }

  @Test fun stoppingEndsTheClipAndClosesBoth() {
    val gate = java.util.concurrent.CountDownLatch(1)
    val out = FakeOut(); val dec = FakeDecoder(5, block = gate)
    val p = PreviewPlayer({ dec }, { out })
    p.play(File("/tmp/a.mp3"), 0)
    waitFor(p, "playing") { it is PlayState.Playing }
    p.stop()
    assertEquals(PlayState.Idle, p.state.value)
    gate.countDown()
    val end = System.currentTimeMillis() + 3000
    while (!(out.closed && dec.closed) && System.currentTimeMillis() < end) Thread.sleep(10)
    assertTrue(out.closed && dec.closed)
    assertEquals(PlayState.Idle, p.state.value, "a stopped clip does not report Done afterwards")
  }

  @Test fun noDecoderOrNoSoundOutputIsFailedNotACrash() {
    val a = PreviewPlayer({ null }, { FakeOut() })
    a.play(File("/tmp/a.mp3"), 0); waitFor(a, "failed") { it is PlayState.Failed }
    val b = PreviewPlayer({ FakeDecoder(1) }, { null })
    b.play(File("/tmp/a.mp3"), 0); waitFor(b, "failed") { it is PlayState.Failed }
    val c = PreviewPlayer({ FakeDecoder(1, fail = true) }, { FakeOut() })
    c.play(File("/tmp/a.mp3"), 0); waitFor(c, "failed") { it is PlayState.Failed }
  }

  @Test fun aFileWithNoSoundIsFailed() {
    val p = PreviewPlayer({ FakeDecoder(0) }, { FakeOut() })
    p.play(File("/tmp/silent.mp4"), 0)
    waitFor(p, "failed") { it is PlayState.Failed }
  }

  @Test fun startingAnotherClipEndsTheFirstAndOnlyTheLiveOneReports() {
    val gate = java.util.concurrent.CountDownLatch(1)
    val out1 = FakeOut(); val dec1 = FakeDecoder(5, block = gate)
    val out2 = FakeOut(); val dec2 = FakeDecoder(2)
    val decs = ArrayDeque(listOf(dec1, dec2)); val outs = ArrayDeque(listOf(out1, out2))
    val p = PreviewPlayer({ decs.removeFirst() }, { outs.removeFirst() })
    p.play(File("/tmp/a.mp3"), 0)
    waitFor(p, "first playing") { it is PlayState.Playing }
    p.play(File("/tmp/b.mp3"), 5_000)
    waitFor(p, "second done") { it is PlayState.Done }
    gate.countDown()
    Thread.sleep(100)
    assertEquals(PlayState.Done, p.state.value)
    assertTrue(out1.closed && dec1.closed)
    assertEquals(5_000, dec2.startedAt)
  }
}

class QuickPreviewLogicTest {
  @Test fun theShapeFollowsTheKindAndTheBlur() {
    assertEquals(PreviewShape.PICTURE, PreviewShape.of(FileKind.IMAGE, false))
    assertEquals(PreviewShape.PICTURE, PreviewShape.of(FileKind.SCREENSHOT, false))
    assertEquals(PreviewShape.HIDDEN_PICTURE, PreviewShape.of(FileKind.IMAGE, true))
    assertEquals(PreviewShape.VIDEO, PreviewShape.of(FileKind.VIDEO, false))
    assertEquals(PreviewShape.AUDIO, PreviewShape.of(FileKind.AUDIO, false))
    assertEquals(PreviewShape.DOCUMENT, PreviewShape.of(FileKind.PDF, false))
    assertEquals(PreviewShape.DOCUMENT, PreviewShape.of(FileKind.DOCUMENT, false))
  }

  @Test fun theQuerysWordsAreMarkedWhateverTheirCase() {
    val marked = highlightTerms("The Invoice for March: invoice total", "invoice march", SpanStyle())
    assertEquals("The Invoice for March: invoice total", marked.text)
    assertEquals(listOf("Invoice", "March", "invoice"), marked.spanStyles.map { marked.text.substring(it.start, it.end) })
  }

  @Test fun noWordsMeansNoMarks() {
    assertTrue(highlightTerms("anything", "", SpanStyle()).spanStyles.isEmpty())
    assertTrue(highlightTerms("anything", "a", SpanStyle()).spanStyles.isEmpty(), "one-letter words mark everything and help nobody")
  }

  @Test fun punctuationInTheQueryIsNotARegexSurprise() {
    val m = highlightTerms("a (b) [c] d+e", "(b) d+e", SpanStyle())
    assertEquals(listOf("(b)", "d+e"), m.spanStyles.map { m.text.substring(it.start, it.end) })
  }
}
