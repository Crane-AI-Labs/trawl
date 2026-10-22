package trawl.desktop

import com.craneai.findr.core.FileKind
import com.craneai.findr.core.IndexedFile
import com.craneai.findr.index.FileState
import com.craneai.findr.index.IndexStore
import com.craneai.findr.index.Modality
import com.craneai.findr.index.Segment
import com.craneai.findr.index.SegmentKind
import java.nio.file.Paths

/**
 * The reading Trawl does with no model: the words of a document and the text of a picture go into the keyword index, with no vectors,
 * so a file is found by what it says before the models are installed. This is the desktop's side of the phone's words pass
 * (`FindrEngine.wordsPass`, `FileProcessor.words`): the same queue ([IndexStore.nextWithoutWords], ordered by `Schema.WORDS_ORDER`),
 * the same loop (`WordsPassLoop`), the same segment shapes (a CHUNK per ~400 characters, an OCR segment per picture) and the same
 * end state: SEARCHABLE with detail [IndexStore.WORDS_DONE], never UNDERSTOOD. When the models arrive, the meaning pass takes those
 * files from the same queue and puts vectors on top of the words ([Indexer.processStored] embeds the stored chunks, no re-reading).
 */
class WordsReader(private val store: IndexStore, private val ocr: () -> Ocr?) {

  /** Reads [file]'s words and saves them. A file that cannot be read still leaves the queue (findable by name); the meaning pass tries it again. */
  fun read(file: IndexedFile) {
    val segments: Pair<List<Segment>, Int?> = try { words(file) } catch (t: Throwable) {
      if (t is InterruptedException) throw t
      // Damaged, password-protected or too big: failed with the reason (the detail panel says it); still found by its name.
      if (t is Unreadable) { store.setState(file.id, FileState.FAILED, t.detail); return }
      emptyList<Segment>() to null
    }
    // keepQuick as on the phone: this pass adds no vectors, so none that a quick pass saved meanwhile may be dropped.
    store.saveSegments(file.id, segments.first, FileState.SEARCHABLE, IndexStore.WORDS_DONE, pageCount = segments.second, keepQuick = true)
  }

  private fun words(file: IndexedFile): Pair<List<Segment>, Int?> {
    val path = Paths.get(java.net.URI(file.uri))
    return when (file.kind) {
      FileKind.DOCUMENT, FileKind.PDF -> {
        val isPdf = file.name.lowercase().endsWith(".pdf")
        // A document nobody reads stays findable by name; every format the phone reads is read here too (DocFormats).
        if (!isPdf && !DocFormats.canRead(path.toFile())) return emptyList<Segment>() to null
        val read = DocFormats.pages(path.toFile(), isPdf)
        read.failure?.let { throw Unreadable(it) }
        val segs = ArrayList<Segment>()
        read.chunks.forEachIndexed { page, chunks ->
          for (c in chunks) segs += Segment(SegmentKind.CHUNK, Modality.NONE, page + 1L, page + 1L, c)
        }
        segs to read.chunks.size.takeIf { read.pdf }
      }
      FileKind.IMAGE, FileKind.SCREENSHOT -> {
        val o = ocr() ?: return emptyList<Segment>() to null   // no text reader on this computer: the picture is found by its name
        val text = Indexer.readable(o.read(path.toFile()))
        listOfNotNull(text?.let { Segment(SegmentKind.OCR, Modality.NONE, text = it) }) to null
      }
      else -> emptyList<Segment>() to null
    }
  }

  private class Unreadable(val detail: String) : Exception(detail)

  companion object {
    /** The kinds the words pass reads, as on the phone (`FindrEngine.WORDS_PASS_KINDS`). */
    val KINDS = setOf(FileKind.DOCUMENT, FileKind.PDF, FileKind.SCREENSHOT, FileKind.IMAGE)
  }
}
