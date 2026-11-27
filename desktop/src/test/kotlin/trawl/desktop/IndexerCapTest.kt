package trawl.desktop

import com.craneai.findr.core.FileKind
import com.craneai.findr.core.KindFilter
import com.craneai.findr.index.CrawlRecord
import com.craneai.findr.index.FileState
import com.craneai.findr.index.IndexStore
import com.craneai.findr.search.Searcher
import com.craneai.findr.search.TwoStage
import com.craneai.findr.search.VectorIndex
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Verifies desktop document meaning chunk caps (preserving 100% of FTS5 words) and [TwoStage.DEFAULT] wiring. */
class IndexerCapTest {
  private class CountingEmbedder : IndexEmbedder {
    override val textOnly = false
    var docCalls = 0
    override fun document(text: String, title: String?): FloatArray {
      docCalls++
      return FloatArray(768) { if (it == 0) 1f else 0f }
    }
    override fun image(jpeg: ByteArray): FloatArray = FloatArray(768) { if (it == 0) 1f else 0f }
  }

  private fun tmp(): File = Files.createTempDirectory("trawl-cap-").toFile().canonicalFile

  private fun repeatedParagraphs(paragraphs: Int, tailToken: String): String = buildString {
    repeat(paragraphs) { i ->
      append("Paragraph $i discussing quarterly architecture review notes, budget milestones, and field deployment logistics across regions. ")
      append("Additional narrative context to ensure each paragraph spans roughly one chunk of text for deterministic chunker counts.\n\n")
    }
    append("Final concluding sentence containing the unique search marker $tailToken at the very end of the file.\n")
  }

  private fun repeatedJsonEntries(entries: Int, tailToken: String): String = buildString {
    append("[\n")
    repeat(entries) { i ->
      append("""  {"id": $i, "service": "telemetry-worker", "region": "eu-west", "message": "processed batch $i with status nominal and zero dropped frames"},""" + "\n")
    }
    append("""  {"id": $entries, "marker": "$tailToken"}""" + "\n]\n")
  }

  @Test fun meaningChunkCapDistinguishesProseFromMachineData() {
    for (ext in listOf("log", "svg", "svgz")) {
      assertEquals(Indexer.MACHINE_DATA_MAX_CHUNKS, Indexer.meaningChunkCap("data.$ext"), ext)
      assertEquals(Indexer.MACHINE_DATA_MAX_CHUNKS, Indexer.meaningChunkCap("DATA.${ext.uppercase()}"), ext.uppercase())
    }
    for (name in listOf("report.txt", "notes.md", "contract.pdf", "paper.docx", "slides.pptx", "README", "saved_posts.json", "notes.csv", "archive.tar.md")) {
      assertEquals(Indexer.DOC_MAX_CHUNKS, Indexer.meaningChunkCap(name), name)
    }
  }

  private fun ftsChunkCount(store: IndexStore, fileId: Long): Int = store.read { db ->
    (db.long("SELECT COUNT(*) FROM segments s JOIN fts f ON f.rowid = s.id WHERE s.file = ? AND s.kind = 'chunk' AND f.body != ''", fileId) ?: 0L).toInt()
  }

  @Test fun largeProseDocumentCapsMeaningVectorsAtProseCeilingWhileKeepingAllChunksInFts5() {
    val root = tmp(); val data = tmp()
    try {
      val f = File(root, "handbook.txt").apply { writeText(repeatedParagraphs(1400, "xylophonequark99")) }
      val store = IndexStore.open(File(data, "index"))
      try {
        val vectors = VectorIndex(parallelism = 1)
        val embedder = CountingEmbedder()
        val indexer = Indexer(store, vectors, embedder, ocr = null)
        indexer.indexFolder(root) {}

        assertEquals(Indexer.DOC_MAX_CHUNKS, embedder.docCalls, "prose meaning vectors must be capped at DOC_MAX_CHUNKS")
        assertEquals(Indexer.DOC_MAX_CHUNKS, vectors.size)
        val id = store.knownMediaStoreIds("desktop").values.single()
        val totalFtsChunks = ftsChunkCount(store, id)
        assertTrue(totalFtsChunks > Indexer.DOC_MAX_CHUNKS, "all chunks must remain in FTS5 (got $totalFtsChunks)")

        val searcher = Searcher(store, vectors)
        val hits = searcher.search("xylophonequark99", null, KindFilter.ALL, limit = 10).hits
        assertEquals(listOf(f.name), hits.map { it.file.name }, "word beyond chunk cap must remain FTS5-searchable")
      } finally { store.close() }
    } finally { root.deleteRecursively(); data.deleteRecursively() }
  }

  @Test fun largeMachineDataFilesCapMeaningVectorsAt8WhileKeepingAllChunksInFts5() {
    val root = tmp(); val data = tmp()
    try {
      val logFile = File(root, "server.log").apply { writeText(repeatedJsonEntries(100, "cobaltphoenix88")) }
      val auditFile = File(root, "audit.log").apply { writeText(repeatedParagraphs(60, "ambernebula77")) }
      val store = IndexStore.open(File(data, "index"))
      try {
        store.upsertFromCrawl(
          listOf(
            CrawlRecord("desktop", 1L, logFile.toPath().toUri().toString(), "", logFile.name, FileKind.DOCUMENT, null, logFile.length(), logFile.lastModified()),
            CrawlRecord("desktop", 2L, auditFile.toPath().toUri().toString(), "", auditFile.name, FileKind.DOCUMENT, null, auditFile.length(), auditFile.lastModified()),
          ),
        )
        val vectors = VectorIndex(parallelism = 1)
        val embedder = CountingEmbedder()
        val indexer = Indexer(store, vectors, embedder, ocr = null)
        for (p in store.nextWithoutWords(10, setOf(FileKind.DOCUMENT))) indexer.processStored(p.file, wordsDone = false)

        assertEquals(Indexer.MACHINE_DATA_MAX_CHUNKS * 2, embedder.docCalls, "each machine-data file must cap at MACHINE_DATA_MAX_CHUNKS")
        assertEquals(Indexer.MACHINE_DATA_MAX_CHUNKS * 2, vectors.size)

        val searcher = Searcher(store, vectors)
        assertEquals(listOf(logFile.name), searcher.search("cobaltphoenix88", null, KindFilter.ALL, limit = 10).hits.map { it.file.name })
        assertEquals(listOf(auditFile.name), searcher.search("ambernebula77", null, KindFilter.ALL, limit = 10).hits.map { it.file.name })
      } finally { store.close() }
    } finally { root.deleteRecursively(); data.deleteRecursively() }
  }

  @Test fun wordsPassFollowedByMeaningPassEnforcesCapAndPreservesTailWords() {
    val root = tmp(); val data = tmp()
    try {
      val f = File(root, "trace.log").apply { writeText(repeatedParagraphs(60, "velvetstarlight55")) }
      val store = IndexStore.open(File(data, "index"))
      try {
        val rec = CrawlRecord("desktop", 1L, f.toPath().toUri().toString(), "", f.name, FileKind.DOCUMENT, null, f.length(), f.lastModified())
        store.upsertFromCrawl(listOf(rec))
        val pending = store.nextWithoutWords(10, setOf(FileKind.DOCUMENT)).single()

        // Pass 1: WordsReader stores all chunks with no vectors.
        WordsReader(store) { null }.read(pending.file)
        val before = ftsChunkCount(store, pending.file.id)
        assertTrue(before > Indexer.MACHINE_DATA_MAX_CHUNKS, "words pass should store all chunks (got $before)")

        // Pass 2: Indexer adds meaning vectors up to cap while keeping all stored FTS5 chunks.
        val vectors = VectorIndex(parallelism = 1)
        val embedder = CountingEmbedder()
        Indexer(store, vectors, embedder, ocr = null).processStored(pending.file, wordsDone = true)

        assertEquals(Indexer.MACHINE_DATA_MAX_CHUNKS, embedder.docCalls)
        assertEquals(Indexer.MACHINE_DATA_MAX_CHUNKS, vectors.size)
        assertEquals(FileState.UNDERSTOOD, store.stateOf(pending.file.id)?.first)
        assertEquals(before, ftsChunkCount(store, pending.file.id), "meaning pass must keep 100% of FTS5 chunks")

        val hits = Searcher(store, vectors).search("velvetstarlight55", null, KindFilter.ALL, limit = 10).hits
        assertEquals(listOf(f.name), hits.map { it.file.name })
      } finally { store.close() }
    } finally { root.deleteRecursively(); data.deleteRecursively() }
  }

  @Test fun desktopIndexServiceWiresTwoStageDefaultOnVectorIndex() {
    val data = tmp()
    try {
      IndexService(data).use { svc ->
        val field = IndexService::class.java.getDeclaredField("vectors").apply { isAccessible = true }
        val vectors = field.get(svc) as VectorIndex
        val twoStage = assertNotNull(vectors.twoStage, "Desktop VectorIndex must configure TwoStage.DEFAULT")
        assertEquals(TwoStage.DEFAULT.stageDim, twoStage.stageDim)
        assertEquals(TwoStage.DEFAULT.candidates, twoStage.candidates)
        assertEquals(TwoStage.DEFAULT.minRows, twoStage.minRows)
        assertEquals(TwoStage.DEFAULT.scaleSample, twoStage.scaleSample)
      }
    } finally { data.deleteRecursively() }
  }
}
