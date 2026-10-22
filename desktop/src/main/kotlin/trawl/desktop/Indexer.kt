package trawl.desktop

import com.craneai.findr.core.FileKind
import com.craneai.findr.index.CrawlRecord
import com.craneai.findr.index.FileState
import com.craneai.findr.index.IndexStore
import com.craneai.findr.index.Modality
import com.craneai.findr.index.Rules
import com.craneai.findr.index.Segment
import com.craneai.findr.index.SegmentKind
import com.craneai.findr.search.VectorIndex
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/** One timing line per stage, in the same buckets the phone's "image stages" log uses. */
class Stats {
  var docs = 0; var docChunks = 0; var docNs = 0L
  var pdfs = 0; var pdfChunks = 0; var pdfNs = 0L
  var images = 0; var imageNs = 0L; var jpegNs = 0L; var embedNs = 0L; var ocrNs = 0L
  var skipped = 0; var failed = 0; var crawlNs = 0L
}

/**
 * The crawl, the readers and the writes of the desktop prototype. The index (IndexStore), the segment layout and the file
 * rules (Rules.classify) are the Android app's own classes, compiled from the same source.
 */
class Indexer(private val store: IndexStore, private val vectors: VectorIndex, private val embedder: DesktopEmbedder, private val ocr: Ocr?) {
  val stats = Stats()
  private val volume = "desktop"


  /** Walks [root] (java.nio, no Spotlight) and records every file the shared rules keep; returns them with their store ids. */
  private fun crawl(root: File): List<Pair<Found, Long>> {
    val t = System.nanoTime()
    val found = ArrayList<Found>(); val records = ArrayList<CrawlRecord>()
    Files.walk(root.toPath()).use { s ->
      s.filter { Files.isRegularFile(it) && !it.fileName.toString().startsWith(".") }.forEach { p ->
        val rel = (root.toPath().relativize(p).parent?.toString()?.let { "$it/" } ?: "")
        val name = p.fileName.toString()
        val kind = Rules.classify(name, null, rel)
        val attrs = Files.readAttributes(p, java.nio.file.attribute.BasicFileAttributes::class.java)
        val f = Found(p, rel, name, kind, attrs.size(), attrs.lastModifiedTime().toMillis(), Crawler.takenOf(p, name, kind))
        found += f
        records += CrawlRecord(volume, fnv64(p.toAbsolutePath().toString()), p.toUri().toString(), rel, name, kind, null, f.size, f.modified, dateMillis = f.taken)
      }
    }
    records.chunked(500).forEach { store.upsertFromCrawl(it) }
    val ids = store.knownMediaStoreIds(volume)
    stats.crawlNs += System.nanoTime() - t
    return found.mapIndexed { i, f -> f to ids.getValue(records[i].mediaStoreId) }
  }

  fun indexFolder(root: File, limit: Int? = null, log: (String) -> Unit = ::println) {
    val files = crawl(root).let { if (limit != null) it.take(limit) else it }
    log("crawl: ${files.size} files in ${stats.crawlNs / 1_000_000} ms")
    for ((f, id) in files) {
      try { process(f, id) } catch (t: Throwable) { stats.failed++; log("failed ${f.name}: ${t.message}") }
    }
  }

  /** Reads one file the store already knows (a pending row) and saves what it holds. Throws when the file cannot be read; the caller marks it failed. */
  fun processStored(file: com.craneai.findr.core.IndexedFile, wordsDone: Boolean = false) {
    val path = java.nio.file.Paths.get(java.net.URI(file.uri))
    process(Found(path, file.relativePath, file.name, file.kind, file.sizeBytes, file.modifiedMillis), file.id, wordsDone)
  }

  /**
   * [wordsDone]: the words pass ([WordsReader]) already read this file with no model, so its words are in the index. The meaning pass
   * puts vectors on top of them: a document's chunks are taken from the index and embedded (the file is not read again), and a
   * picture keeps its stored words (no second OCR) and only gains its picture vector.
   */
  private fun process(f: Found, id: Long, wordsDone: Boolean = false) {
    val file = f.path.toFile()
    when (f.kind) {
      FileKind.DOCUMENT, FileKind.PDF -> {
        // A document nobody reads (legacy .doc, a Pages file) stays findable by name; every format the phone reads is read here too (DocFormats).
        if (f.kind == FileKind.DOCUMENT && !DocFormats.canRead(file)) { save(id, f.kind, emptyList(), null); return }
        val t = System.nanoTime()
        val isPdf = f.name.lowercase().endsWith(".pdf")
        val stored = if (wordsDone) store.storedChunks(id, 0, Int.MAX_VALUE) else emptyList()
        // (page, chunk) in reading order: from the index when the words are in, otherwise from the file.
        val chunks: List<Pair<Long, String>>
        val pageCount: Int?
        if (stored.isNotEmpty()) {
          chunks = stored.map { (page, text) -> (page ?: 1L) to text }
          pageCount = null   // the words pass stored it
        } else {
          val read = DocFormats.pages(file, isPdf)
          // Damaged, password-protected or too big: failed with the reason (the detail panel says it), still found by name.
          if (read.failure != null) { store.setState(id, FileState.FAILED, read.failure); return }
          chunks = read.chunks.flatMapIndexed { page, cs -> cs.map { (page + 1L) to it } }
          pageCount = read.chunks.size.takeIf { read.pdf }
        }
        var n = 0
        val segs = ArrayList<Segment>()
        for ((page, c) in chunks) {
          val vec = embedder.document(c)
          segs += Segment(SegmentKind.CHUNK, Modality.TEXT, page, page, c, vec, late = n >= CHUNKS_PER_STEP)
          n++
        }
        save(id, f.kind, segs, pageCount)
        if (isPdf) { stats.pdfs++; stats.pdfChunks += n; stats.pdfNs += System.nanoTime() - t }
        else { stats.docs++; stats.docChunks += n; stats.docNs += System.nanoTime() - t }
      }
      FileKind.IMAGE, FileKind.SCREENSHOT -> {
        val t = System.nanoTime()
        val tj = System.nanoTime()
        val jpeg = Extract.phoneJpeg(file) ?: run { stats.skipped++; store.setState(id, FileState.FAILED, "unreadable picture"); return }
        stats.jpegNs += System.nanoTime() - tj
        // OCR reads the picture beside the embed, as on the phone (the GPU embed leaves the CPU free).
        var words: String? = null
        // The words are in the index already (the words pass read them): no second OCR, the stored words stay.
        val ocrThread = ocr?.takeIf { !wordsDone }?.let { o -> Thread { val to = System.nanoTime(); words = readable(o.read(file)); stats.ocrNs += System.nanoTime() - to }.also { it.start() } }
        val te = System.nanoTime()
        val vec = embedder.image(jpeg)
        stats.embedNs += System.nanoTime() - te
        ocrThread?.join()
        val segs = listOfNotNull(words?.let { Segment(SegmentKind.OCR, Modality.NONE, text = it) }) + Segment(SegmentKind.IMAGE, Modality.IMAGE, vector = vec)
        save(id, f.kind, segs, null, replace = !wordsDone)
        stats.images++; stats.imageNs += System.nanoTime() - t
      }
      else -> stats.skipped++
    }
  }

  private fun save(id: Long, kind: FileKind, segs: List<Segment>, pages: Int?, replace: Boolean = true) {
    // Read is read: an empty document or a picture with no text and no vector is done too, or it would be queued forever.
    val ids = store.saveSegments(id, segs, FileState.UNDERSTOOD, replace = replace, pageCount = pages)
    segs.forEachIndexed { i, s -> s.vector?.let { vectors.add(ids[i], id, kind, s.modality, s.late, it) } }
  }

  private fun readable(s: String): String? = Companion.readable(s)

  companion object {
    /** Text worth keeping from a picture: stray marks and a few letters are noise, not words. */
    fun readable(s: String): String? = s.trim().takeIf { t -> t.count { it.isLetter() } >= 10 }

    const val CHUNKS_PER_STEP = 40
    val TEXT_EXT = setOf("txt", "text", "md", "markdown", "csv", "tsv")
    /** A stable 63-bit id per path: the desktop's stand-in for MediaStore's row id. */
    fun fnv64(s: String): Long { var h = -3750763034362895579L; for (b in s.toByteArray()) { h = h xor (b.toLong() and 0xff); h *= 1099511628211L }; return h and Long.MAX_VALUE }
  }
}
