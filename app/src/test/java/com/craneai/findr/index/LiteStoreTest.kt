package com.craneai.findr.index

import com.craneai.findr.core.FileKind
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** The lite profile's rows in the index: a picture keeps a 512-d `clip` vector and its text's 768-d `ocr_text` vectors for good. */
class LiteStoreTest {
  private lateinit var dir: File
  private lateinit var store: IndexStore

  @Before fun setUp() {
    dir = Files.createTempDirectory("findr-lite").toFile()
    store = IndexStore.open(dir, log = {})
  }

  @After fun tearDown() {
    store.close()
    dir.deleteRecursively()
  }

  private var nextId = 1L
  private fun add(name: String, kind: FileKind = FileKind.IMAGE): Long {
    val id = nextId++
    store.upsertFromCrawl(listOf(CrawlRecord("external_primary", id, "content://media/external/file/$id", "DCIM/", name, kind, null, 50_000, 1_000)))
    return store.knownMediaStoreIds("external_primary").getValue(id)
  }

  private fun quick(seed: Int = 0) = FloatArray(IndexStore.QUICK_DIM) { if (it == seed) 1f else 0f }
  private fun eg(seed: Int = 0) = FloatArray(Fp16.DIM) { if (it == seed) 1f else 0f }
  private fun rows(file: Long, modality: String) =
    store.read { it.long("SELECT COUNT(*) FROM segments WHERE file = ? AND modality = ? AND vec IS NOT NULL", file, modality) }

  /** What the lite worker saves for a screenshot: its text (words + EmbeddingGemma vector) and its quick-look vector. */
  private fun liteScreenshot(name: String, detail: String? = null): Long {
    val f = add(name, FileKind.SCREENSHOT)
    store.saveSegments(f, listOf(
      Segment(SegmentKind.OCR, Modality.OCR_TEXT, text = "airtime top up 5000", vector = eg(1)),
      Segment(SegmentKind.IMAGE, Modality.CLIP, vector = quick(2)),
    ), FileState.UNDERSTOOD, detail)
    return f
  }

  @Test fun bothVectorSizesAreSavedTogetherAndEachLoadsIntoItsOwnIndex() {
    val f = liteScreenshot("a.png")
    assertEquals(1L, rows(f, "ocr_text"))
    assertEquals(1L, rows(f, "clip"))
    val main = com.craneai.findr.search.VectorIndex(parallelism = 1).apply { load(store) }
    val q = com.craneai.findr.search.VectorIndex(dim = IndexStore.QUICK_DIM, parallelism = 1, holds = com.craneai.findr.search.VectorIndex.QUICK_LOOK).apply { load(store) }
    assertEquals(1, main.size)
    assertEquals(1, q.size)
    main.close(); q.close()
    assertEquals(1, store.read { db -> var n = 0; db.query("SELECT rowid FROM fts WHERE fts MATCH 'airtime'") { n++ }; n })
  }

  @Test fun picturesReadWithoutTheQuickModelAreQueuedOnceItIsIn() {
    val missing = liteScreenshot("without.png", IndexStore.QUICK_MISSING)
    val done = liteScreenshot("with.png")
    assertEquals(1, store.retryMissingQuick())
    assertEquals(FileState.SEARCHABLE, store.stateOf(missing)!!.first)
    assertEquals(FileState.UNDERSTOOD, store.stateOf(done)!!.first)
    assertEquals(1L, rows(missing, "ocr_text"))   // its words and text vector stay searchable meanwhile
    assertEquals(0, store.retryMissingQuick())
  }

  @Test fun aTextModelChangeReadsLitePicturesAgain() {
    val shot = liteScreenshot("a.png")
    val photo = add("p.jpg")
    store.saveSegments(photo, listOf(Segment(SegmentKind.IMAGE, Modality.CLIP, vector = quick(3))), FileState.UNDERSTOOD)
    store.ensureTextFingerprint("embeddinggemma-2-text-270m.litertlm")
    assertEquals(0 to 0, store.ensureTextFingerprint("embeddinggemma-2-text-270m.litertlm"))
    val (queued, _) = store.ensureTextFingerprint("findr-eg2-ft-text.litertlm")
    assertEquals(1, queued)
    assertEquals(FileState.SEARCHABLE, store.stateOf(shot)!!.first)   // its text vector came from the old text model
    assertEquals(FileState.UNDERSTOOD, store.stateOf(photo)!!.first)  // a quick-look vector alone doesn't depend on it
  }

  @Test fun aQuickVectorFromTheWordsPhasePassSurvivesTheLiteWorker() {
    // The quick-look pass runs beside the words pass in the lite profile too; when the worker reads that picture later it adds
    // the text's vectors and keeps the pass's picture vector instead of computing it again.
    val f = add("shot.png", FileKind.SCREENSHOT)
    assertTrue(store.saveQuick(f, SegmentKind.IMAGE, quick(5)) > 0)
    assertTrue(store.hasQuickVector(f))
    val read = listOf(Segment(SegmentKind.OCR, Modality.OCR_TEXT, text = "airtime top up", vector = eg(1)))
    val keep = com.craneai.findr.engine.IndexWorker.keepsQuick(read, imagesByEmbeddingGemma = false)
    assertTrue(keep)
    store.saveSegments(f, read, FileState.UNDERSTOOD, keepQuick = keep)
    assertEquals(1L, rows(f, "clip"))
    assertEquals(1L, rows(f, "ocr_text"))
  }

  @Test fun theKeepRuleIsUnchangedForStandardPhones() {
    val text = listOf(Segment(SegmentKind.OCR, Modality.TEXT, text = "words only"))
    val image = listOf(Segment(SegmentKind.IMAGE, Modality.IMAGE, vector = eg(1)))
    val ocrVector = listOf(Segment(SegmentKind.OCR, Modality.OCR_TEXT, vector = eg(1)))
    val picture = listOf(Segment(SegmentKind.IMAGE, Modality.CLIP, vector = quick(1)))
    assertTrue(com.craneai.findr.engine.IndexWorker.keepsQuick(text, true))     // no vectors: the quick one is all there is
    assertFalse(com.craneai.findr.engine.IndexWorker.keepsQuick(image, true))   // EmbeddingGemma's picture vector replaces it
    assertTrue(com.craneai.findr.engine.IndexWorker.keepsQuick(ocrVector, false))   // lite: text vectors don't replace the picture
    assertFalse(com.craneai.findr.engine.IndexWorker.keepsQuick(picture, false))    // lite: a new picture vector does
    assertFalse(store.hasQuickVector(add("none.jpg")))
  }

  @Test fun leavingTheLiteProfileKeepsQuickVectorsAndDropsTextOnes() {
    val f = liteScreenshot("a.png")
    store.ensureModelFingerprint("lite:tinyclip")
    store.ensureModelFingerprint("embeddinggemma-2-740m.litertlm:vt70")
    assertEquals(0L, rows(f, "ocr_text"))
    assertEquals(1L, rows(f, "clip"))   // still searchable by quick look until EmbeddingGemma reads it
    assertEquals(FileState.SEARCHABLE, store.stateOf(f)!!.first)
  }
}
