package trawl.desktop.shell

import com.craneai.findr.core.IndexedFile
import com.craneai.findr.core.KindFilter
import com.craneai.findr.core.PrivateLook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import com.craneai.findr.core.DateParser
import com.craneai.findr.core.ResultView
import com.craneai.findr.core.SortOrder
import java.time.ZonedDateTime
import java.time.temporal.WeekFields
import java.util.Locale
import kotlinx.coroutines.flow.flowOn
import trawl.desktop.AppController

/** One row of the quick panel. */
/** [startMs] is where in a video or recording the search matched (null for everything else); the preview shows that frame and plays from it. */
data class QuickHit(val file: IndexedFile, val snippet: String?, val startMs: Long? = null)

/**
 * What the quick panel needs from search, and nothing more. [ControllerQuickSearch] answers it from [trawl.desktop.SearchApi], the one
 * search entry point the main window and this panel share.
 */
interface QuickSearchApi {
  /** Files matching [query] (the most recent files when it is blank), best first. One emission per call; cancelled when the query changes. */
  fun search(query: String): Flow<List<QuickHit>>
  fun open(file: IndexedFile)
  fun showInFolder(file: IndexedFile)
  /** True while the app lock is on: the panel then shows a locked state and [search] answers nothing. */
  val locked: StateFlow<Boolean> get() = NEVER_LOCKED
  /** The blur is on and the Privacy pack can score pictures: the panel covers pictures that look private. Default: no. */
  fun blurOn(): Boolean = false
  /** What the preview draws with. The default draws nothing (tests that do not preview). */
  val preview: QuickPreviewApi get() = QuickPreviewApi.None
  companion object { private val NEVER_LOCKED: StateFlow<Boolean> = MutableStateFlow(false) }
}

/**
 * The quick panel's search, through the same [trawl.desktop.SearchApi] as the main window (the phone's progressive search: words at once,
 * then the fused answer, so the list may update once as the meaning lands), limited to a short list. Nothing here ranks.
 */
class ControllerQuickSearch(
  private val c: AppController, private val limit: Int = 30,
  /** The system's own big preview (macOS Quick Look), or none. */
  full: FullPreview = NoFullPreview,
) : QuickSearchApi {
  override val preview: QuickPreviewApi = ControllerPreview(c, full, c.previewPlayer)

  override val locked: StateFlow<Boolean> get() = c.privacy.locked

  override fun blurOn(): Boolean = c.privacy.settings.value.blurEnabled && c.privacyPackReady()

  /**
   * Locked: nothing at all. Unlocked: never sensitive files, even when the main window has them shown (the panel is a glance over other
   * windows and has no way to reveal them), and no text excerpt for a picture that looks private (the panel's own, lower, bar).
   * A date word in the query ("invoice last week") is read by the core's DateParser, as in the window; the window's chips are not the panel's.
   */
  override fun search(query: String): Flow<List<QuickHit>> = flow {
    if (c.privacy.locked.value) { emit(emptyList()); return@flow }
    val q = query.trim()
    val blur = blurOn()
    val parsed = DateParser.parse(q, ZonedDateTime.now(), WeekFields.of(Locale.getDefault()).firstDayOfWeek, emptySet())
    val text = parsed.text.trim()
    val dates = parsed.date?.filter?.range
    val phrase = parsed.date?.phrase
    if (text.isEmpty()) {
      emit(c.searchApi.recent(KindFilter.ALL, ResultView(SortOrder.NEWEST, dates, phrase), limit, withSensitive = false).files.map { QuickHit(it, null) })
    } else {
      emitAll(
        c.searchApi.search(text, KindFilter.ALL, ResultView(SortOrder.BEST, dates, phrase), limit, withSensitive = false).map { r ->
          r.hits.map { QuickHit(it.file, if (blur && PrivateLook.blurs(it.file.looksPrivate)) null else it.snippet, (it.locator as? com.craneai.findr.core.Locator.Time)?.startMs) }
        },
      )
    }
  }.flowOn(Dispatchers.Default)

  override fun open(file: IndexedFile) = c.open(file)
  override fun showInFolder(file: IndexedFile) = c.showInFolder(file)
}
