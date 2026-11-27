package trawl.desktop.shell

import androidx.compose.ui.input.key.Key
import com.craneai.findr.core.FileKind
import com.craneai.findr.core.IndexedFile
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import trawl.desktop.Os
import trawl.desktop.Phase
import trawl.desktop.ui.quick.quickKeyFor

fun fakeFile(id: Long, name: String, kind: FileKind = FileKind.DOCUMENT) = IndexedFile(
  id = id, uri = "file:///tmp/$name", relativePath = "Documents/$name", name = name, kind = kind, mimeType = null, sizeBytes = 10, modifiedMillis = 0,
)

/** A search that answers from a fixed list and records what was opened. */
class FakeSearch(private val files: List<IndexedFile>) : QuickSearchApi {
  val asked = java.util.Collections.synchronizedList(mutableListOf<String>())
  val opened = mutableListOf<IndexedFile>()
  val shown = mutableListOf<IndexedFile>()

  override fun search(query: String): Flow<List<QuickHit>> = flow {
    asked += query
    emit(files.filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }.map { QuickHit(it, null) })
  }

  override fun open(file: IndexedFile) { opened += file }
  override fun showInFolder(file: IndexedFile) { shown += file }
}

private fun tmp(): File = Files.createTempDirectory("trawl-shell-").toFile()

/** Waits (up to 3 s) for a condition that a background coroutine will make true. */
private fun eventually(what: String, cond: () -> Boolean) {
  val end = System.currentTimeMillis() + 3000
  while (System.currentTimeMillis() < end) { if (cond()) return; Thread.sleep(10) }
  throw AssertionError("timed out waiting for $what")
}

class ShellPrefsTest {
  @Test fun roundTrips() {
    val d = tmp()
    val s = ShellPrefsStore(d)
    val p = ShellPrefs(hotkeyOn = false, hotkey = "ctrl+alt+j", keepRunning = false, firstIndexNotified = true)
    s.save(p)
    assertEquals(p, ShellPrefsStore(d).load())
  }

  @Test fun missingFileGivesTheDefaults() {
    assertEquals(ShellPrefs(), ShellPrefsStore(tmp()).load())
    assertTrue(ShellPrefs().keepRunning)   // the setting defaults to on
    assertTrue(ShellPrefs().hotkeyOn)
  }

  @Test fun garbageAndUnknownShortcutsAreDropped() {
    val d = tmp()
    File(d, "shell.json").writeText("not json {{{")
    assertEquals(ShellPrefs(), ShellPrefsStore(d).load())
    File(d, "shell.json").writeText("""{"hotkeyOn":true,"hotkey":"banana+k","keepRunning":false}""")
    val p = ShellPrefsStore(d).load()
    assertNull(p.hotkey)
    assertFalse(p.keepRunning)
  }

  @Test fun savingLeavesNoPartFileBehind() {
    val d = tmp()
    ShellPrefsStore(d).save(ShellPrefs())
    assertEquals(listOf("shell.json"), d.list()!!.toList())
  }
}

class FirstIndexWatcherTest {
  @Test fun firesOnceWhenReadingFinishesWithFilesInTheIndex() {
    val w = FirstIndexWatcher(done = false)
    assertFalse(w.onUpdate(Phase.NOT_STARTED, 0))
    assertFalse(w.onUpdate(Phase.SCANNING, 0))
    assertFalse(w.onUpdate(Phase.INDEXING, 10))
    assertTrue(w.onUpdate(Phase.UP_TO_DATE, 40))
    assertFalse(w.onUpdate(Phase.INDEXING, 41))
    assertFalse(w.onUpdate(Phase.UP_TO_DATE, 41))   // never again
  }

  @Test fun staysQuietWhenNothingWasRead() {
    val w = FirstIndexWatcher(done = false)
    assertFalse(w.onUpdate(Phase.SCANNING, 0))
    assertFalse(w.onUpdate(Phase.UP_TO_DATE, 0))    // an empty folder is not "your files are read"
  }

  @Test fun staysQuietWhenThisRunNeverSawReading() {
    // Opening an already-indexed library: it goes straight to "up to date", which is not news.
    val w = FirstIndexWatcher(done = false)
    assertFalse(w.onUpdate(Phase.UP_TO_DATE, 500))
  }

  @Test fun staysQuietWhenAlreadyDone() {
    val w = FirstIndexWatcher(done = true)
    assertFalse(w.onUpdate(Phase.INDEXING, 5))
    assertFalse(w.onUpdate(Phase.UP_TO_DATE, 5))
  }
}

class QuickKeysTest {
  @Test fun arrowsMoveEnterOpensEscCloses() {
    for (os in Os.entries) {
      assertEquals(QuickKey.DOWN, quickKeyFor(Key.DirectionDown, true, false, false, os))
      assertEquals(QuickKey.UP, quickKeyFor(Key.DirectionUp, true, false, false, os))
      assertEquals(QuickKey.OPEN, quickKeyFor(Key.Enter, true, false, false, os))
      assertEquals(QuickKey.OPEN, quickKeyFor(Key.NumPadEnter, true, false, false, os))
      assertEquals(QuickKey.CLOSE, quickKeyFor(Key.Escape, true, false, false, os))
      assertNull(quickKeyFor(Key.A, true, false, false, os))     // typing is left to the text field
      assertNull(quickKeyFor(Key.Enter, false, false, false, os)) // key up does nothing
    }
  }

  @Test fun showInFolderIsCommandEnterOnAMacAndCtrlEnterElsewhere() {
    assertEquals(QuickKey.SHOW_IN_FOLDER, quickKeyFor(Key.Enter, true, ctrl = false, meta = true, os = Os.MAC))
    assertEquals(QuickKey.OPEN, quickKeyFor(Key.Enter, true, ctrl = true, meta = false, os = Os.MAC))
    assertEquals(QuickKey.SHOW_IN_FOLDER, quickKeyFor(Key.Enter, true, ctrl = true, meta = false, os = Os.WINDOWS))
    assertEquals(QuickKey.SHOW_IN_FOLDER, quickKeyFor(Key.Enter, true, ctrl = true, meta = false, os = Os.LINUX))
    assertEquals(QuickKey.OPEN, quickKeyFor(Key.Enter, true, ctrl = false, meta = true, os = Os.WINDOWS))
  }

  @Test fun shiftEnterMapsToOpenTopWithoutConflictingWithShowInFolder() {
    for (os in Os.entries) {
      assertEquals(QuickKey.OPEN_TOP, quickKeyFor(Key.Enter, true, ctrl = false, meta = false, os = os, shift = true))
      assertEquals(QuickKey.OPEN_TOP, quickKeyFor(Key.NumPadEnter, true, ctrl = false, meta = false, os = os, shift = true))
    }
    assertEquals(QuickKey.SHOW_IN_FOLDER, quickKeyFor(Key.Enter, true, ctrl = false, meta = true, os = Os.MAC, shift = true))
    assertEquals(QuickKey.SHOW_IN_FOLDER, quickKeyFor(Key.Enter, true, ctrl = true, meta = false, os = Os.LINUX, shift = true))
  }
}

class QuickPanelStateTest {
  private val files = listOf(fakeFile(1, "invoice-march.pdf", FileKind.PDF), fakeFile(2, "invoice-april.pdf", FileKind.PDF), fakeFile(3, "holiday.jpg", FileKind.IMAGE))

  private fun withPanel(block: suspend (QuickPanelState, FakeSearch, () -> Int) -> Unit) = runBlocking {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val api = FakeSearch(files)
    var closed = 0
    val st = QuickPanelState(api, scope, onClose = { closed++ }, debounceMs = 30)
    try { block(st, api) { closed } } finally { scope.cancel() }
  }

  @Test fun showsRecentFilesAtOnceThenSearchesAsYouType() = withPanel { st, api, _ ->
    eventually("recent files") { st.hits.value.size == 3 }
    assertEquals("", st.answered.value)
    st.setQuery("invoice")
    eventually("search answer") { st.answered.value == "invoice" }
    assertEquals(listOf("invoice-march.pdf", "invoice-april.pdf"), st.hits.value.map { it.file.name })
    assertTrue("invoice" in api.asked)
  }

  @Test fun theRowUnderTheCursorStaysWhenTheMeaningAnswerRefinesTheWordsAnswer() = runBlocking {
    // SEARCH PACKAGE (A): the panel is fed by the progressive search, which answers twice; the selection follows the file, not the index.
    val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
    val api = object : QuickSearchApi {
      override fun search(query: String): Flow<List<QuickHit>> = flow {
        if (query.isBlank()) { emit(emptyList()); return@flow }
        emit(listOf(QuickHit(files[0], null), QuickHit(files[1], null)))   // words first
        gate.await()
        emit(listOf(QuickHit(files[2], null), QuickHit(files[0], null), QuickHit(files[1], null)))   // then words and meaning
      }
      override fun open(file: IndexedFile) {}
      override fun showInFolder(file: IndexedFile) {}
    }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val st = QuickPanelState(api, scope, onClose = {}, debounceMs = 30)
    try {
      st.setQuery("invoice")
      eventually("words answer") { st.hits.value.size == 2 }
      st.select(1)
      gate.complete(Unit)
      eventually("refined answer") { st.hits.value.size == 3 }
      assertEquals("invoice-april.pdf", st.current?.file?.name)
    } finally { scope.cancel() }
  }

  @Test fun typingFastSearchesOnlyTheLastQuery() = withPanel { st, api, _ ->
    eventually("recent files") { st.hits.value.isNotEmpty() }
    for (q in listOf("i", "in", "inv", "invo")) { st.setQuery(q); delay(5) }
    eventually("final answer") { st.answered.value == "invo" }
    assertFalse("i" in api.asked && "in" in api.asked && "inv" in api.asked, "intermediate queries should be skipped by the pause")
  }

  @Test fun arrowsMoveTheSelectionAndStopAtTheEnds() = withPanel { st, _, _ ->
    eventually("recent files") { st.hits.value.size == 3 }
    assertEquals(0, st.selected.value)
    st.onKey(QuickKey.UP); assertEquals(0, st.selected.value)
    st.onKey(QuickKey.DOWN); st.onKey(QuickKey.DOWN); assertEquals(2, st.selected.value)
    st.onKey(QuickKey.DOWN); assertEquals(2, st.selected.value)
    st.onKey(QuickKey.FIRST); assertEquals(0, st.selected.value)
    st.onKey(QuickKey.LAST); assertEquals(2, st.selected.value)
  }

  @Test fun enterOpensTheSelectedFileAndClosesThePanel() = withPanel { st, api, closed ->
    eventually("recent files") { st.hits.value.size == 3 }
    st.onKey(QuickKey.DOWN)
    st.onKey(QuickKey.OPEN)
    assertEquals(listOf(files[1]), api.opened)
    assertEquals(1, closed())
  }

  @Test fun showInFolderRevealsTheSelectedFileAndClosesThePanel() = withPanel { st, api, closed ->
    eventually("recent files") { st.hits.value.size == 3 }
    st.onKey(QuickKey.LAST)
    st.onKey(QuickKey.SHOW_IN_FOLDER)
    assertEquals(listOf(files[2]), api.shown)
    assertTrue(api.opened.isEmpty())
    assertEquals(1, closed())
  }

  @Test fun enterWithNothingToOpenDoesNothing() = withPanel { st, api, closed ->
    st.setQuery("zzz-no-such")
    eventually("empty answer") { st.answered.value == "zzz-no-such" }
    st.onKey(QuickKey.OPEN)
    st.onKey(QuickKey.SHOW_IN_FOLDER)
    assertTrue(api.opened.isEmpty() && api.shown.isEmpty())
    assertEquals(0, closed())
  }

  @Test fun escClosesWithoutOpening() = withPanel { st, api, closed ->
    eventually("recent files") { st.hits.value.isNotEmpty() }
    st.onKey(QuickKey.CLOSE)
    assertEquals(1, closed())
    assertTrue(api.opened.isEmpty())
  }

  @Test fun resetClearsTheQueryAndGoesBackToTheRecentFiles() = withPanel { st, _, _ ->
    st.setQuery("holiday")
    eventually("answer") { st.answered.value == "holiday" }
    assertEquals(1, st.hits.value.size)
    st.reset()
    eventually("recent again") { st.answered.value == "" && st.hits.value.size == 3 }
    assertEquals("", st.query.value)
    assertEquals(0, st.selected.value)
    assertFalse(st.userSelectedRow.value)
  }

  @Test fun enterWithNonBlankUnselectedQueryOpensFullSearchImmediatelyWithoutOpeningStaleRecentFile() = runBlocking {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val api = FakeSearch(files)
    val fullSearch = mutableListOf<String>()
    var closed = 0
    // Long debounce simulates typing quickly and pressing Enter before search returns.
    val st = QuickPanelState(api, scope, onClose = { closed++ }, debounceMs = 2_000, onOpenFullSearch = { fullSearch += it })
    try {
      eventually("recent files") { st.hits.value.size == 3 }
      st.setQuery("  tax return  ")
      assertFalse(st.userSelectedRow.value)
      st.onKey(QuickKey.OPEN)
      assertEquals(listOf("tax return"), fullSearch)
      assertTrue(api.opened.isEmpty(), "must never open a stale recent file during debounce")
      assertEquals(0, closed, "onOpenFullSearch owns closing/hiding the panel")
    } finally { scope.cancel() }
  }

  @Test fun arrowingSelectsRowSoEnterOpensFileAndTypingResetsSelectionFlag() = runBlocking {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val api = FakeSearch(files)
    val fullSearch = mutableListOf<String>()
    var closed = 0
    val st = QuickPanelState(api, scope, onClose = { closed++ }, debounceMs = 20, onOpenFullSearch = { fullSearch += it })
    try {
      st.setQuery("invoice")
      eventually("invoice hits") { st.answered.value == "invoice" && st.hits.value.size == 2 }
      // First Down highlights row 0 cleanly (no implicit highlight before arrowing); second Down moves to row 1.
      st.onKey(QuickKey.DOWN)
      assertTrue(st.userSelectedRow.value)
      assertEquals(0, st.selected.value)
      // Pressing Up from row 0 returns cleanly to the search box (unselected).
      st.onKey(QuickKey.UP)
      assertFalse(st.userSelectedRow.value)
      st.onKey(QuickKey.DOWN)
      st.onKey(QuickKey.DOWN)
      assertTrue(st.userSelectedRow.value)
      assertEquals(1, st.selected.value)
      st.onKey(QuickKey.OPEN)
      assertEquals(listOf(files[1]), api.opened)
      assertTrue(fullSearch.isEmpty())
      assertEquals(1, closed)

      // Typing a new query resets userSelectedRow so Enter goes back to opening full search.
      st.setQuery("holiday")
      assertFalse(st.userSelectedRow.value)
      eventually("holiday hit") { st.answered.value == "holiday" }
      st.onKey(QuickKey.OPEN)
      assertEquals(listOf("holiday"), fullSearch)
    } finally { scope.cancel() }
  }

  @Test fun openTopAndShowInFolderRefuseStaleRowsDuringDebounceAndOpenOnceAnswered() = runBlocking {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val api = FakeSearch(files)
    val fullSearch = mutableListOf<String>()
    var closed = 0
    val st = QuickPanelState(api, scope, onClose = { closed++ }, debounceMs = 120, onOpenFullSearch = { fullSearch += it })
    try {
      eventually("recent files") { st.hits.value.size == 3 }
      st.setQuery("holiday")
      // Still debouncing: answered is "", query is "holiday" -> stale recent row must NOT be opened or revealed.
      st.onKey(QuickKey.OPEN_TOP)
      st.onKey(QuickKey.SHOW_IN_FOLDER)
      st.onKey(QuickKey.DOWN)
      st.onKey(QuickKey.OPEN)
      assertTrue(api.opened.isEmpty() && api.shown.isEmpty(), "stale recent rows must never open during debounce")
      assertEquals(0, closed)

      // Once answered, Shift+Enter (OPEN_TOP) opens the top hit directly without invoking onOpenFullSearch.
      st.setQuery("holiday")
      eventually("holiday answered") { st.answered.value == "holiday" && st.hits.value.size == 1 }
      st.onKey(QuickKey.OPEN_TOP)
      assertEquals(listOf(files[2]), api.opened)
      assertTrue(fullSearch.isEmpty())
      assertEquals(1, closed)
    } finally { scope.cancel() }
  }

  @Test fun blankQueryEnterStillOpensSelectedRecentFileEvenWhenFullSearchWired() = runBlocking {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val api = FakeSearch(files)
    val fullSearch = mutableListOf<String>()
    var closed = 0
    val st = QuickPanelState(api, scope, onClose = { closed++ }, debounceMs = 20, onOpenFullSearch = { fullSearch += it })
    try {
      eventually("recent files") { st.hits.value.size == 3 }
      st.onKey(QuickKey.OPEN)
      assertEquals(listOf(files[0]), api.opened)
      assertTrue(fullSearch.isEmpty())
      assertEquals(1, closed)
    } finally { scope.cancel() }
  }

  @Test fun resetClearsStaleAnsweredAndHitsImmediatelyBeforeRecentReloads() = runBlocking {
    val recentGate = kotlinx.coroutines.CompletableDeferred<Unit>()
    var blankCalls = 0
    val api = object : QuickSearchApi {
      override fun search(query: String): Flow<List<QuickHit>> = flow {
        if (query.isBlank()) {
          val n = ++blankCalls
          if (n > 1) recentGate.await()
          emit(files.map { QuickHit(it, null) })
        } else {
          emit(files.filter { it.name.contains(query, ignoreCase = true) }.map { QuickHit(it, null) })
        }
      }
      override fun open(file: IndexedFile) {}
      override fun showInFolder(file: IndexedFile) {}
    }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val st = QuickPanelState(api, scope, onClose = {}, debounceMs = 15)
    try {
      st.setQuery("holiday")
      eventually("holiday answered") { st.answered.value == "holiday" && st.hits.value.size == 1 }
      st.reset()
      // Immediately after reset(), before recent reload completes, stale hits and answered must already be cleared.
      assertNull(st.answered.value, "reset() must clear stale answered immediately")
      assertTrue(st.hits.value.isEmpty(), "reset() must clear stale hits immediately")
      assertEquals("", st.query.value)
      recentGate.complete(Unit)
      eventually("recent reloaded") { st.answered.value == "" && st.hits.value.size == 3 }
    } finally { scope.cancel() }
  }

  @Test fun midDebounceStaleEmissionIsDroppedWhenQueryAlreadyChanged() = runBlocking {
    val stage2Gate = kotlinx.coroutines.CompletableDeferred<Unit>()
    val stage2Emitted = kotlinx.coroutines.CompletableDeferred<Unit>()
    val api = object : QuickSearchApi {
      override fun search(query: String): Flow<List<QuickHit>> = flow {
        when (query) {
          "invoice" -> {
            emit(listOf(QuickHit(files[0], null)))
            stage2Gate.await()
            emit(listOf(QuickHit(files[0], null), QuickHit(files[1], null)))
            stage2Emitted.complete(Unit)
          }
          "holiday" -> emit(listOf(QuickHit(files[2], null)))
          else -> emit(emptyList())
        }
      }
      override fun open(file: IndexedFile) {}
      override fun showInFolder(file: IndexedFile) {}
    }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    // Long debounce ensures setQuery("holiday") has not yet cancelled the in-flight "invoice" collector when Stage 2 emits.
    val st = QuickPanelState(api, scope, onClose = {}, debounceMs = 250)
    try {
      st.setQuery("invoice")
      eventually("invoice stage 1") { st.answered.value == "invoice" && st.hits.value.size == 1 }
      st.setQuery("holiday")
      stage2Gate.complete(Unit)
      stage2Emitted.await()
      delay(30)
      assertEquals(1, st.hits.value.size, "stale Stage-2 emission for old query must be dropped mid-debounce")
      assertEquals("invoice-march.pdf", st.hits.value.single().file.name)
      eventually("holiday finally answered") { st.answered.value == "holiday" && st.hits.value.singleOrNull()?.file?.name == "holiday.jpg" }
    } finally { scope.cancel() }
  }
}

class SingleInstanceShellTest {
  @Test fun theFirstLaunchRunsAndASecondOneHandsOver() {
    val d = tmp()
    val first = assertIs<SingleInstanceShell.Outcome.First>(SingleInstanceShell.acquire(d))
    val got = java.util.concurrent.LinkedBlockingQueue<String>()
    first.instance.listen { got.add(it) }
    val second = SingleInstanceShell.acquire(d, command = "quick", retries = 5, waitMs = 50)
    assertEquals(SingleInstanceShell.Outcome.Forwarded, second)
    assertEquals("quick", got.poll(3, java.util.concurrent.TimeUnit.SECONDS))
    val third = SingleInstanceShell.acquire(d, command = "show", retries = 5, waitMs = 50)
    assertEquals(SingleInstanceShell.Outcome.Forwarded, third)
    assertEquals("show", got.poll(3, java.util.concurrent.TimeUnit.SECONDS))
    first.instance.close()
  }

  @Test fun anUnknownCommandIsIgnored() {
    val d = tmp()
    val first = assertIs<SingleInstanceShell.Outcome.First>(SingleInstanceShell.acquire(d))
    val got = java.util.concurrent.LinkedBlockingQueue<String>()
    first.instance.listen { got.add(it) }
    // "rm -rf" is not a command: the socket answers but nothing is run.
    SingleInstanceShell.acquire(d, command = "rm -rf", retries = 5, waitMs = 50)
    assertNull(got.poll(300, java.util.concurrent.TimeUnit.MILLISECONDS))
    first.instance.close()
  }

  @Test fun afterTheFirstClosesAnotherCanTakeOver() {
    val d = tmp()
    assertIs<SingleInstanceShell.Outcome.First>(SingleInstanceShell.acquire(d)).instance.close()
    val again = assertIs<SingleInstanceShell.Outcome.First>(SingleInstanceShell.acquire(d))
    again.instance.close()
  }

  @Test fun aHolderThatDoesNotAnswerIsReportedNotReplaced() {
    val d = tmp()
    val first = assertIs<SingleInstanceShell.Outcome.First>(SingleInstanceShell.acquire(d))   // never calls listen()
    val second = SingleInstanceShell.acquire(d, retries = 1, waitMs = 20)
    // The connection waits in the backlog and nobody answers: the second launch gives up after its deadline instead of hanging, and does not run.
    assertEquals(SingleInstanceShell.Outcome.Unreachable, second)
    first.instance.close()
  }

  @Test fun theSocketIsOnlyForThePerson() {
    val d = tmp()
    val first = assertIs<SingleInstanceShell.Outcome.First>(SingleInstanceShell.acquire(d))
    val sock = SingleInstanceShell.socketFor(d)
    if (!System.getProperty("os.name").lowercase().contains("win")) {
      assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(sock.toPath())))
    }
    first.instance.close()
    assertFalse(sock.exists(), "the socket file is removed on close")
  }

  @Test fun aLongDataFolderPathFallsBackToAShortSocketPath() {
    val long = File(tmp(), "a".repeat(120))
    val sock = SingleInstanceShell.socketFor(long)
    assertTrue(sock.absolutePath.toByteArray().size < 104, sock.absolutePath)
  }
}
