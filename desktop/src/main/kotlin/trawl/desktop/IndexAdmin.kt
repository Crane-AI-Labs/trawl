package trawl.desktop

import com.craneai.findr.core.FileKind
import com.craneai.findr.core.StorageEstimate
import com.craneai.findr.index.FileState
import com.craneai.findr.index.IndexStats
import com.craneai.findr.index.IndexStore

/**
 * What the detail panel shows, worked out from the index in one pass, and the one-time clean-up that lets files the older build
 * only knew by name be read now that the formats are there. Pure functions over [IndexStore] and its numbers, so each is tested
 * against a real (temporary) index and none of it touches the window.
 */
object IndexAdmin {

  /** Why a file could not be read. [key] is the strings.properties key of the plain sentence. */
  enum class FailReason(val key: String) {
    TOO_BIG("fail_too_big"),
    NOT_TEXT("fail_not_text"),
    DAMAGED("fail_damaged"),
    CANT_OPEN("fail_cant_open"),
    PICTURE("fail_picture"),
    TOO_LONG("fail_too_long"),
    OTHER("fail_other"),
  }

  /**
   * The stored English detail of a failed file, as a reason. The details are the ones [DocFormats.Why], [IndexStore] and the media reader
   * write; anything else (an exception's name) is "could not be read". Never shows the stored text itself: it can name a file.
   */
  fun failReason(detail: String?): FailReason = when (detail?.trim()) {
    DocFormats.Why.TOO_BIG.detail, IndexStore.TOO_LARGE_DETAIL -> FailReason.TOO_BIG
    DocFormats.Why.BINARY.detail -> FailReason.NOT_TEXT
    DocFormats.Why.DAMAGED.detail -> FailReason.DAMAGED
    IndexStore.CANT_OPEN -> FailReason.CANT_OPEN
    "unreadable picture" -> FailReason.PICTURE
    "took too long" -> FailReason.TOO_LONG
    else -> FailReason.OTHER
  }

  /** One kind of file as the panel lists it. [bytes] is the size of the files themselves on disk. */
  data class KindRow(val kind: FileKind, val known: Int, val understood: Int, val waiting: Int, val failed: Int, val bytes: Long)

  /** The files that failed for one [reason]; [example] is one file name, for a person who wants to find it. */
  data class FailGroup(val reason: FailReason, val count: Int, val kinds: Set<FileKind>, val example: String?)

  data class Detail(
    val rows: List<KindRow>,
    val known: Int,
    /** Files whose words are in (the pale level of the meter). */
    val wordsIn: Int,
    val understood: Int,
    val waiting: Int,
    val failed: List<FailGroup>,
    val failedTotal: Int,
    val indexBytes: Long,
    val modelBytes: Long?,
    /** What the index will come to when every file is read, and the memory searching will hold. Null when nothing is known yet. */
    val estimate: StorageEstimate.Result?,
    /** (done, total) while the files read with an older model are being read again, else null. */
    val reembed: Pair<Int, Int>?,
  ) {
    /** How much more room the index still needs. */
    val growthBytes: Long get() = ((estimate?.indexBytes ?: 0L) - indexBytes).coerceAtLeast(0L)
  }

  /** [kinds]: what the person switched on. [withMeaning]: the models are installed, so the estimate counts the vectors. */
  fun detail(store: IndexStore, kinds: Set<FileKind>, modelBytes: Long?, withMeaning: Boolean): Detail {
    val s = store.stats()
    val totals = store.storageTotals().associateBy { it.kind }
    val rows = Crawler.KINDS.sortedBy { it.ordinal }.map { k ->
      val known = s.byKindAndState[k]?.filterKeys { it != FileState.SKIPPED }?.values?.sum() ?: 0
      KindRow(k, known, s.count(k, FileState.UNDERSTOOD), s.waiting(setOf(k)), s.count(k, FileState.FAILED), totals[k]?.bytes ?: 0L)
    }.filter { it.known > 0 }
    val on = rows.filter { it.kind in kinds }
    val failed = failedGroups(store)
    return Detail(
      rows = rows,
      known = on.sumOf { it.known },
      wordsIn = on.sumOf { it.known } - wordsLeft(store, kinds),
      understood = on.sumOf { it.understood },
      waiting = on.sumOf { it.waiting },
      failed = failed,
      failedTotal = failed.sumOf { it.count },
      indexBytes = store.bytesOnDisk(),
      modelBytes = modelBytes,
      estimate = if (totals.isEmpty()) null else StorageEstimate.estimate(totals.values.toList(), kinds, withVectors = withMeaning),
      reembed = store.reembedProgress().over(kinds),
    )
  }

  /** Failed files grouped by reason, biggest first. */
  fun failedGroups(store: IndexStore): List<FailGroup> {
    class Row(val kind: FileKind?, val detail: String?, val n: Int, val name: String?)
    val raw = ArrayList<Row>()
    store.read { db ->
      db.query("SELECT kind, detail, COUNT(*), MIN(CASE WHEN sensitive = 0 THEN name END) FROM files WHERE state = 'failed' GROUP BY kind, detail") {
        raw += Row(runCatching { FileKind.valueOf(it.getText(0)) }.getOrNull(), if (it.isNull(1)) null else it.getText(1), it.getLong(2).toInt(), if (it.isNull(3)) null else it.getText(3))
      }
    }
    return raw.groupBy { failReason(it.detail) }.map { (reason, rs) ->
      FailGroup(reason, rs.sumOf { it.n }, rs.mapNotNull { it.kind }.toSet(), rs.maxByOrNull { it.n }?.name)
    }.sortedByDescending { it.count }
  }

  /** Files whose words the words pass has not read yet, for the kinds that are on. */
  fun wordsLeft(store: IndexStore, on: Set<FileKind>): Int {
    val kindsIn = on.intersect(WordsReader.KINDS).joinToString(",") { "'${it.name}'" }
    if (kindsIn.isEmpty()) return 0
    return store.read { db ->
      db.long("SELECT COUNT(*) FROM files WHERE volume = ? AND state IN ('new','searchable') AND detail IS NULL AND kind IN ($kindsIn)", Crawler.VOLUME) ?: 0L
    }.toInt()
  }

  // ---- the one-time clean-up after the formats arrived -------------------------------------------------------------------------

  const val FORMATS_KEY = "desktop_formats_v1"

  /** Documents the first desktop build knew by name only. (txt, md, csv, tsv and pdf were read from the start and are left alone.) */
  val NEWLY_READ_DOCUMENTS = listOf("json", "log", "html", "htm", "rtf", "svg", "docx", "xlsx", "pptx", "odt", "ods", "odp", "epub")

  /** Pictures the first desktop build could not open. */
  val NEWLY_READ_PICTURES = listOf("webp", "heic", "heif", "heics", "heifs", "avif", "avci", "avcs", "psd", "tga", "pnm", "ppm", "pgm", "pbm", "pcx")

  /**
   * Puts the files the older build only knew by name, and the pictures it could not open, back in the queue so they are read once, in
   * the background, with the formats that are there now. Runs once per index (a note in the index says so); a failure here is not
   * allowed to stop the index opening. Returns how many files went back.
   */
  fun rereadNewFormats(store: IndexStore, log: (String) -> Unit = {}): Int {
    if (store.meta(FORMATS_KEY) != null) return 0
    val n = try {
      store.write { db ->
        fun likes(exts: List<String>) = exts.joinToString(" OR ") { "lower(name) LIKE '%.$it'" }
        val docs = db.long("SELECT COUNT(*) FROM files WHERE kind = 'DOCUMENT' AND state != 'skipped' AND (${likes(NEWLY_READ_DOCUMENTS)})") ?: 0L
        if (docs > 0) db.run("UPDATE files SET state = 'searchable', detail = NULL WHERE kind = 'DOCUMENT' AND state != 'skipped' AND (${likes(NEWLY_READ_DOCUMENTS)})")
        val pics = db.long("SELECT COUNT(*) FROM files WHERE kind IN ('IMAGE','SCREENSHOT') AND state = 'failed' AND (${likes(NEWLY_READ_PICTURES)})") ?: 0L
        if (pics > 0) db.run("UPDATE files SET state = 'searchable', detail = NULL WHERE kind IN ('IMAGE','SCREENSHOT') AND state = 'failed' AND (${likes(NEWLY_READ_PICTURES)})")
        (docs + pics).toInt()
      }
    } catch (t: Throwable) {
      log("formats clean-up skipped: ${t.javaClass.simpleName}")
      return 0
    }
    store.setMeta(FORMATS_KEY, "1")
    if (n > 0) log("$n files go back in the queue: this build reads more formats")
    return n
  }

  /** The estimate over the kinds on, for the Settings storage line, when [stats] says there is anything to count. */
  fun hasFiles(s: IndexStats) = s.filesKnown > 0
}
