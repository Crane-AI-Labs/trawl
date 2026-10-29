package com.craneai.findr.search

import com.craneai.findr.core.DateRange
import com.craneai.findr.core.FileKind
import com.craneai.findr.core.KindFilter
import com.craneai.findr.core.Locator
import com.craneai.findr.core.MatchReason
import com.craneai.findr.core.ResultView
import com.craneai.findr.core.SearchResults
import com.craneai.findr.core.SortOrder
import com.craneai.findr.index.CrawlRecord
import com.craneai.findr.index.FileState
import com.craneai.findr.index.Fp16
import com.craneai.findr.index.IndexStore
import com.craneai.findr.index.Modality
import com.craneai.findr.index.Segment
import com.craneai.findr.index.SegmentKind
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * "Find similar": a search whose query is a file's own stored vectors. Synthetic vectors as in SearcherTest: a scene is a unit
 * axis, a picture of it is that axis plus a little noise, the background is random and sits near zero.
 */
class SimilarSearchTest {
  private lateinit var dir: File
  private lateinit var store: IndexStore
  private lateinit var vectors: VectorIndex
  private lateinit var quick: VectorIndex
  private lateinit var searcher: Searcher
  private val random = Random(11)
  private var nextId = 1L
  private val day = 86_400_000L
  private val t0 = 1_700_000_000_000L

  @Before fun setUp() {
    dir = Files.createTempDirectory("findr-similar").toFile()
    store = IndexStore.open(dir, log = {})
    vectors = VectorIndex(parallelism = 1)
    quick = VectorIndex(dim = IndexStore.QUICK_DIM, parallelism = 1, holds = VectorIndex.QUICK_LOOK)
    searcher = Searcher(store, vectors, quick = quick)
  }

  @After fun tearDown() {
    vectors.close(); quick.close(); store.close()
    dir.deleteRecursively()
  }

  // ---- fixtures --------------------------------------------------------------------------------------------------

  private fun unit(raw: FloatArray): FloatArray {
    val n = sqrt(raw.sumOf { (it * it).toDouble() }).toFloat()
    return FloatArray(raw.size) { raw[it] / n }
  }
  private fun noise(dim: Int) = FloatArray(dim) { (random.nextDouble() * 2 - 1).toFloat() }
  private fun background(dim: Int = Fp16.DIM) = unit(noise(dim))
  /** A picture of scene [k]: the axis plus a little noise. */
  private fun scene(k: Int, dim: Int = Fp16.DIM, spread: Float = 0.03f) = unit(FloatArray(dim) { (if (it == k) 1f else 0f) + noise(dim)[it] * spread })

  private fun file(name: String, kind: FileKind, date: Long = t0, size: Long = 1000): Long {
    val id = nextId++
    store.upsertFromCrawl(listOf(CrawlRecord("external_primary", id, "content://media/external/file/$id", "DCIM/", name, kind, null, size, date, dateMillis = date)))
    return store.knownMediaStoreIds("external_primary").getValue(id)
  }

  /** A picture EmbeddingGemma understood: one IMAGE vector. */
  private fun photo(name: String, v: FloatArray, kind: FileKind = FileKind.IMAGE, date: Long = t0, size: Long = 1000): Long {
    val f = file(name, kind, date, size)
    val ids = store.saveSegments(f, listOf(Segment(SegmentKind.IMAGE, Modality.IMAGE, vector = v)), FileState.UNDERSTOOD)
    vectors.add(ids[0], f, kind, Modality.IMAGE, false, v)
    return f
  }

  /** A video: one IMAGE vector per key frame, at 1 s, 2 s, ... */
  private fun video(name: String, frames: List<FloatArray>, date: Long = t0): Long {
    val f = file(name, FileKind.VIDEO, date)
    val segs = frames.mapIndexed { i, v -> Segment(SegmentKind.FRAME, Modality.IMAGE, locStart = (i + 1) * 1000L, locEnd = (i + 2) * 1000L, vector = v) }
    val ids = store.saveSegments(f, segs, FileState.UNDERSTOOD)
    frames.forEachIndexed { i, v -> vectors.add(ids[i], f, FileKind.VIDEO, Modality.IMAGE, false, v) }
    return f
  }

  /** A picture only the quick-look pass has seen. */
  private fun quickOnly(name: String, v: FloatArray, kind: FileKind = FileKind.IMAGE, date: Long = t0): Long {
    val f = file(name, kind, date)
    val id = store.saveQuick(f, SegmentKind.IMAGE, v)
    quick.add(id, f, kind, Modality.CLIP, false, v)
    return f
  }

  private fun text(name: String, v: FloatArray): Long {
    val f = file(name, FileKind.DOCUMENT)
    val ids = store.saveSegments(f, listOf(Segment(SegmentKind.CHUNK, Modality.TEXT, text = "words", vector = v)), FileState.UNDERSTOOD)
    vectors.add(ids[0], f, FileKind.DOCUMENT, Modality.TEXT, false, v)
    return f
  }

  private fun backgroundPhotos(n: Int = 120) = repeat(n) { photo("bg$it.jpg", background()) }

  private fun similar(
    seed: Long, filter: KindFilter = KindFilter.ALL, limit: Int = 30, includeSensitive: Boolean = false, view: ResultView = ResultView.DEFAULT,
  ) = searcher.similar(seed, filter, limit, includeSensitive, view, label = "seed")

  private fun names(r: SearchResults) = r.hits.map { it.file.name }

  // ---- the switch: a sparse EmbeddingGemma space falls back to quick look ------------------------------------------

  private val qd = IndexStore.QUICK_DIM
  private fun quickBackground(n: Int) = repeat(n) { quickOnly("qbg$it.jpg", background(qd)) }

  /** A half-built index: the seed understood by EmbeddingGemma (its quick row replaced), the rest of the pictures quick-look only. */
  private fun halfBuilt(egBackground: Int = 3): Long {
    repeat(egBackground) { photo("egbg$it.jpg", background()) }
    quickBackground(300)
    quickOnly("Q_copy1.jpg", scene(3, qd)); quickOnly("Q_copy2.jpg", scene(3, qd)); quickOnly("Q_other.jpg", scene(9, qd))
    return photo("IMG_seed.jpg", scene(3))
  }

  @Test fun aSparseEmbeddingGemmaSpaceSearchesTheQuickLookSpaceWithAMadeSeed() {
    val seed = halfBuilt()
    assertTrue(searcher.egSparse())
    var made = 0
    val r = searcher.similar(seed, KindFilter.ALL, 30, false, ResultView.DEFAULT, "seed") { made++; scene(3, qd) }
    assertEquals(1, made)                                                   // embedded once
    assertEquals(setOf("Q_copy1.jpg", "Q_copy2.jpg"), names(r).toSet())    // quick-look neighbours, never an EmbeddingGemma row
    assertFalse("IMG_seed.jpg" in names(r))
  }

  @Test fun theSeedIsEmbeddedOnlyWhenTheSwitchNeedsIt() {
    backgroundPhotos(250)                        // 250 understood pictures and no quick-look rows: EmbeddingGemma's space is the whole library
    val seed = photo("IMG_seed.jpg", scene(3)); photo("IMG_copy.jpg", scene(3))
    assertFalse(searcher.egSparse())
    var made = 0
    val r = searcher.similar(seed, KindFilter.ALL, 30, false, ResultView.DEFAULT, "seed") { made++; scene(3, qd) }
    assertEquals(0, made)
    assertEquals(listOf("IMG_copy.jpg"), names(r))
  }

  @Test fun aSmallFullyUnderstoodLibraryStaysInEmbeddingGemmaWhateverTheRowCount() {
    val seed = photo("IMG_seed.jpg", scene(3)); photo("IMG_copy.jpg", scene(3)); repeat(5) { photo("bg$it.jpg", background()) }
    assertFalse(searcher.egSparse())             // 7 rows, but no quick-look rows left: nothing to switch to
    assertEquals(listOf("IMG_copy.jpg"), names(similar(seed)))
  }

  @Test fun theSwitchIsHalfOfThePicturesUnderstood() {
    quickBackground(100)
    repeat(99) { photo("eg$it.jpg", background()) }
    assertTrue(searcher.egSparse())              // 99 against 100
    photo("eg_more.jpg", background()); photo("eg_more2.jpg", background())
    assertFalse(searcher.egSparse())             // 101 against 100
  }

  @Test fun aSeedWithAStoredQuickLookVectorNeedsNoEmbedding() {
    backgroundPhotos(3); quickBackground(300)
    quickOnly("Q_copy.jpg", scene(3, qd))
    val seed = quickOnly("Q_seed.jpg", scene(3, qd))
    assertTrue(searcher.egSparse())
    var made = 0
    val r = searcher.similar(seed, KindFilter.ALL, 30, false, ResultView.DEFAULT, "seed") { made++; null }
    assertEquals(0, made)
    assertEquals(listOf("Q_copy.jpg"), names(r))
  }

  @Test fun aVectorlessSeedGetsAMadeQuickLookVector() {
    backgroundPhotos(3); quickBackground(300)
    quickOnly("Q_copy.jpg", scene(3, qd))
    val seed = file("IMG_new.jpg", FileKind.IMAGE)    // not reached by any pass yet
    val r = searcher.similar(seed, KindFilter.ALL, 30, false, ResultView.DEFAULT, "seed") { scene(3, qd) }
    assertEquals(listOf("Q_copy.jpg"), names(r))
    assertTrue(searcher.similarSeed(seed) { null } == null)   // nothing to embed with: nothing to search
  }

  @Test fun whenNothingCanBeEmbeddedTheSparseSpaceStillAnswers() {
    val seed = halfBuilt(egBackground = 2)
    photo("IMG_twin.jpg", scene(3))
    val r = searcher.similar(seed, KindFilter.ALL, 30, false, ResultView.DEFAULT, "seed") { null }
    assertTrue(searcher.similarSeed(seed) { null }!!.space == Searcher.SimilarSpace.EMBEDDING_GEMMA)
    assertTrue(names(r).toString(), names(r).none { it.startsWith("Q_") || it.startsWith("qbg") })   // never a mix: the EmbeddingGemma space alone
  }

  @Test fun aWrongSizedMadeVectorIsIgnored() {
    val seed = halfBuilt()
    val s = searcher.similarSeed(seed) { FloatArray(7) }
    assertEquals(Searcher.SimilarSpace.EMBEDDING_GEMMA, s!!.space)
    assertFalse(s.embedded)
  }

  // ---- the basics ------------------------------------------------------------------------------------------------

  @Test fun picturesOfTheSameSceneComeBackAndTheSeedDoesNot() {
    backgroundPhotos()
    val seed = photo("IMG_seed.jpg", scene(3))
    photo("IMG_copy1.jpg", scene(3)); photo("IMG_copy2.jpg", scene(3))
    photo("IMG_other.jpg", scene(9))
    val r = similar(seed)
    assertEquals(setOf("IMG_copy1.jpg", "IMG_copy2.jpg"), names(r).toSet())   // the other scene and the background are not near
    assertFalse("the seed is never offered", "IMG_seed.jpg" in names(r))
    assertEquals("seed", r.query)
    assertTrue(r.hits.all { it.reason == MatchReason.MEANING })
    assertEquals(2, r.strongCount)
  }

  @Test fun aPictureLikeNothingElseGetsNothing() {
    backgroundPhotos()
    val seed = photo("lonely.jpg", scene(5))
    val r = similar(seed)
    // As in search, the z gate lets the odd random tail row through (about 2% of rows sit above z 2 by chance); nothing real is there.
    assertTrue(names(r).toString(), r.hits.size <= 5 && r.hits.all { it.file.name.startsWith("bg") })
  }

  @Test fun anUnknownOrVectorlessSeedGetsNothingAndDocumentsAreNotOffered() {
    backgroundPhotos()
    assertTrue(similar(9_999).hits.isEmpty())
    val noVector = file("pending.jpg", FileKind.IMAGE)
    assertTrue(similar(noVector).hits.isEmpty())
    assertNull(searcher.similarSeed(noVector))
    val doc = text("notes.txt", scene(3))
    photo("a.jpg", scene(3))
    assertNull("a document has no picture vector, so it has no similar seed", searcher.similarSeed(doc))
    assertTrue(similar(doc).hits.isEmpty())
  }

  @Test fun onlyPictureVectorsAreComparedNotTextOnes() {
    backgroundPhotos()
    val seed = photo("seed.jpg", scene(3))
    text("about-the-same-axis.txt", scene(3))   // a text vector pointing the same way is another modality: never a match
    photo("twin.jpg", scene(3))
    assertEquals(listOf("twin.jpg"), names(similar(seed)))
  }

  // ---- videos ----------------------------------------------------------------------------------------------------

  @Test fun aVideoSeedUsesAllItsKeyFramesAndAFileScoresByItsBestFrame() {
    backgroundPhotos()
    // a video of two scenes (a beach, then a market); a photo of either is similar to it, and a video of the market is too
    val seed = video("trip.mp4", listOf(scene(3), scene(3), scene(8), scene(8)))
    photo("beach.jpg", scene(3)); photo("market.jpg", scene(8)); photo("sky.jpg", scene(20))
    video("other.mp4", listOf(scene(30), scene(8)))   // only its last frame is the market
    val r = similar(seed)
    assertEquals(setOf("beach.jpg", "market.jpg", "other.mp4"), names(r).toSet())
    assertTrue(r.hits.single { it.file.name == "other.mp4" }.locator is Locator.Time)   // it points at the matching frame
    // the frame's slot is 2000..3000 and these rows carry no keyframe time (marks), so the badge is the slot's middle, where the frame was asked for
    assertEquals(2500L, (r.hits.single { it.file.name == "other.mp4" }.locator as Locator.Time).startMs)
  }

  @Test fun aVideoSeedIsCappedAtEightEvenlySpacedFrames() {
    val frames = (0 until 20).map { scene(it) }
    val seedId = video("long.mp4", frames)
    val seed = searcher.similarSeed(seedId)!!
    assertEquals(Searcher.SimilarSpace.EMBEDDING_GEMMA, seed.space)
    assertEquals(8, seed.vectors.size)
    // first and last included, in time order
    assertTrue(seed.vectors.first()[0] > 0.8f)
    assertTrue(seed.vectors.last()[19] > 0.8f)
  }

  // ---- the two spaces are never mixed ----------------------------------------------------------------------------

  @Test fun aQuickLookOnlySeedSearchesTheQuickLookSpaceOnly() {
    val q = IndexStore.QUICK_DIM
    repeat(80) { quickOnly("qbg$it.jpg", background(q)) }
    backgroundPhotos(40)
    val seed = quickOnly("fresh.jpg", scene(4, q))
    quickOnly("fresh-twin.jpg", scene(4, q))
    photo("understood-twin.jpg", scene(4))   // EmbeddingGemma's axis 4 is a different space: it must not match a CLIP seed
    val r = similar(seed)
    assertEquals(listOf("fresh-twin.jpg"), names(r))
    assertEquals(Searcher.SimilarSpace.QUICK_LOOK, searcher.similarSeed(seed)!!.space)
  }

  @Test fun anUnderstoodSeedDoesNotSeeQuickLookOnlyPictures() {
    val q = IndexStore.QUICK_DIM
    repeat(60) { quickOnly("qbg$it.jpg", background(q)) }
    backgroundPhotos(60)
    val seed = photo("seed.jpg", scene(4))
    photo("understood-twin.jpg", scene(4))
    quickOnly("quick-twin.jpg", scene(4, q))
    assertEquals(listOf("understood-twin.jpg"), names(similar(seed)))
  }

  @Test fun aSeedWithBothKindsOfVectorUsesEmbeddingGemmaOnly() {
    val q = IndexStore.QUICK_DIM
    val seed = photo("both.jpg", scene(4))
    val id = store.saveQuick(seed, SegmentKind.IMAGE, scene(4, q))   // a lingering quick row
    quick.add(id, seed, FileKind.IMAGE, Modality.CLIP, false, scene(4, q))
    assertEquals(Searcher.SimilarSpace.EMBEDDING_GEMMA, searcher.similarSeed(seed)!!.space)
  }

  // ---- filters, sort, dates, hidden ------------------------------------------------------------------------------

  @Test fun theKindFilterIsRespected() {
    backgroundPhotos()
    val seed = photo("seed.jpg", scene(3))
    photo("photo.jpg", scene(3)); photo("shot.png", scene(3), FileKind.SCREENSHOT); video("clip.mp4", listOf(scene(3)))
    assertEquals(setOf("photo.jpg", "shot.png", "clip.mp4"), names(similar(seed)).toSet())
    assertEquals(listOf("photo.jpg"), names(similar(seed, KindFilter.PHOTOS)))
    assertEquals(listOf("shot.png"), names(similar(seed, KindFilter.SCREENSHOTS)))
    assertEquals(listOf("clip.mp4"), names(similar(seed, KindFilter.VIDEOS)))
  }

  @Test fun theDateRangeIsRespectedAndTheSortOrdersTheMatches() {
    backgroundPhotos()
    val seed = photo("seed.jpg", scene(3), date = t0)
    photo("old.jpg", scene(3), date = t0 - 10 * day, size = 300)
    photo("mid.jpg", scene(3), date = t0 - 5 * day, size = 100)
    photo("new.jpg", scene(3), date = t0 - 1 * day, size = 200)
    assertEquals(listOf("new.jpg", "mid.jpg", "old.jpg"), names(similar(seed, view = ResultView(SortOrder.NEWEST))))
    assertEquals(listOf("old.jpg", "mid.jpg", "new.jpg"), names(similar(seed, view = ResultView(SortOrder.OLDEST))))
    assertEquals(listOf("old.jpg", "new.jpg", "mid.jpg"), names(similar(seed, view = ResultView(SortOrder.LARGEST))))
    val lastWeek = ResultView(SortOrder.NEWEST, DateRange(t0 - 7 * day, t0))
    assertEquals(listOf("new.jpg", "mid.jpg"), names(similar(seed, view = lastWeek)))   // the date never moves the scale: old.jpg is only left out
  }

  @Test fun sensitivePicturesAreHiddenAndCountedExactlyAsSearchDoes() {
    backgroundPhotos()
    val seed = photo("seed.jpg", scene(3))
    photo("fine.jpg", scene(3))
    val secret = photo("secret.jpg", scene(3))
    store.setMarked(secret, true)
    val hidden = similar(seed)
    assertEquals(listOf("fine.jpg"), names(hidden))
    assertEquals(1, hidden.hiddenCount)
    val shown = similar(seed, includeSensitive = true)
    assertEquals(setOf("fine.jpg", "secret.jpg"), names(shown).toSet())
    assertEquals(0, shown.hiddenCount)
  }

  @Test fun aHiddenSeedDrawsOutNothing() {
    backgroundPhotos()
    val seed = photo("seed.jpg", scene(3))
    photo("fine.jpg", scene(3))
    store.setMarked(seed, true)
    assertTrue(similar(seed).hits.isEmpty())
    assertEquals(listOf("fine.jpg"), names(similar(seed, includeSensitive = true)))
  }

  @Test fun theLimitIsHonouredAndPicturesStillBeingReadAreSaidSo() {
    backgroundPhotos()
    val seed = photo("seed.jpg", scene(3))
    repeat(6) { photo("twin$it.jpg", scene(3)) }
    assertEquals(3, similar(seed, limit = 3).hits.size)
    assertFalse(similar(seed).indexIncomplete)
    file("later.jpg", FileKind.IMAGE)   // known, not yet understood
    assertTrue(similar(seed).indexIncomplete)
    assertNotNull(similar(seed).hits.firstOrNull())
  }
}
