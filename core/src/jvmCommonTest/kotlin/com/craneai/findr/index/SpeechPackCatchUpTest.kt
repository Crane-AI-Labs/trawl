package com.craneai.findr.index

import com.craneai.findr.core.FileKind
import com.craneai.findr.engine.SpeechLanguageGate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The speech add-on's catch-up (SoundtrackCatchUp.PACK_KEY): installing a speech pack, or a new version of it, queues the voice notes and
 * videos already in the index whose speech was judged not English and that were not read with it; English files never; nothing at all
 * with no pack in use. A save stamps `<pack_id>@<pack_version>`, a give-up too, and only transcript pieces change (the pictures stay).
 */
class SpeechPackCatchUpTest {
  private lateinit var dir: File
  private lateinit var store: IndexStore

  @Before fun setUp() {
    dir = Files.createTempDirectory("findr-speechpack").toFile()
    store = IndexStore.open(dir, log = {})
  }

  @After fun tearDown() {
    store.close()
    dir.deleteRecursively()
  }

  private var nextId = 1L
  private fun add(name: String, kind: FileKind): Long {
    val id = nextId++
    store.upsertFromCrawl(listOf(CrawlRecord("external_primary", id, "content://media/external/file/$id", "WhatsApp/", name, kind, null, 50_000, 1_000, durationMs = 30_000)))
    return store.knownMediaStoreIds("external_primary").getValue(id)
  }

  private fun vec(seed: Int) = FloatArray(Fp16.DIM) { if (it == seed % Fp16.DIM) 1f else 0f }

  /** Today's reading (the v1 stamp), with whatever pack token [info] adds. */
  private val today = ReadingStamps.put(null, SoundtrackCatchUp.KEY, SoundtrackCatchUp.VERSION)
  private val notEnglish = "${SpeechLanguageGate.NOTE_NOT_ENGLISH} (12% common words): audio vector, sounds (speech 2.0 s text 0.1 s)"
  private val english = "3 ${SpeechLanguageGate.NOTE_ENGLISH}, sounds (speech 2.0 s text 0.1 s)"

  private fun note(name: String, detail: String, info: String? = today, kind: FileKind = FileKind.AUDIO): Long {
    val id = add(name, kind)
    store.saveSegments(id, listOfNotNull(
      if (kind == FileKind.VIDEO) Segment(SegmentKind.FRAME, Modality.IMAGE, 0, 15_000, vector = vec(1)) else null,
      Segment(SegmentKind.TRANSCRIPT, Modality.TEXT, 0, 9_000, "old words", vec(7)),
      Segment(SegmentKind.SOUND, Modality.SOUND, null, null, "Sounds: Speech", vec(8)),
    ), FileState.UNDERSTOOD, detail, info = info)
    return id
  }

  private fun queue() = store.nextOldSoundtracks(50, setOf(FileKind.VIDEO, FileKind.AUDIO)).map { it.file.id }.toSet()
  private fun info(id: Long) = store.read { db -> db.text("SELECT info FROM files WHERE id = ?", id) }

  @Test fun withNoPackOnlyTodaysReadingCounts() {
    note("luganda.opus", notEnglish)
    note("english.opus", english)
    assertEquals(emptySet<Long>(), queue())
    assertEquals(0, store.oldSoundtracksLeft().first)
  }

  @Test fun aPackQueuesTheNotEnglishNotesReadWithoutItAndNeverTheEnglishOnes() {
    val lg = note("luganda.opus", notEnglish)
    val en = note("english.opus", english)
    val video = note("wedding.mp4", notEnglish, kind = FileKind.VIDEO)
    store.speechPackStamp = "lg-student@2026112201"
    assertEquals(setOf(lg, video), queue())
    assertFalse(en in queue())
    assertEquals(2, store.oldSoundtracksLeft().first)
  }

  @Test fun aSaveStampsThePackAndTodaysReadingAndChangesOnlyTheTranscript() {
    val video = note("wedding.mp4", notEnglish, kind = FileKind.VIDEO)
    store.speechPackStamp = "lg-student@2026112201"
    val frames = store.read { db -> db.long("SELECT COUNT(*) FROM segments WHERE file = ? AND kind = 'frame'", video) }
    val saved = store.saveSoundtrackCatchUp(video, listOf(Segment(SegmentKind.TRANSCRIPT, Modality.TEXT, 0, 9_000, "nze njagala okulya", vec(9))))
    assertTrue(saved)
    assertTrue(ReadingStamps.has(info(video), SoundtrackCatchUp.PACK_KEY, "lg-student@2026112201"))
    assertTrue(ReadingStamps.has(info(video), SoundtrackCatchUp.KEY, SoundtrackCatchUp.VERSION))
    assertTrue("the video-reading stamp and others are kept", info(video)!!.split(' ').size >= 2)
    assertEquals(frames, store.read { db -> db.long("SELECT COUNT(*) FROM segments WHERE file = ? AND kind = 'frame'", video) })
    assertEquals(emptySet<Long>(), queue())
  }

  @Test fun aNewVersionOfThePackQueuesThemAgainAndAnotherPackToo() {
    val lg = note("luganda.opus", notEnglish, info = ReadingStamps.put(today, SoundtrackCatchUp.PACK_KEY, "lg-student@2026112201"))
    store.speechPackStamp = "lg-student@2026112201"
    assertEquals(emptySet<Long>(), queue())
    store.speechPackStamp = "lg-student@2026120101"
    assertEquals(setOf(lg), queue())
    store.speechPackStamp = "sw-student@2026112201"
    assertEquals(setOf(lg), queue())
    store.saveSoundtrackCatchUp(lg, emptyList())
    assertEquals("the old token is replaced, not added to", 1, info(lg)!!.split(' ').count { it.startsWith("${SoundtrackCatchUp.PACK_KEY}=") })
  }

  @Test fun givingUpStampsThePackSoTheFileLeavesTheQueue() {
    val lg = note("luganda.opus", notEnglish)
    store.speechPackStamp = "ach-student@2026112201"
    store.stampSoundtrack(lg)
    assertEquals(emptySet<Long>(), queue())
    assertTrue(ReadingStamps.has(info(lg), SoundtrackCatchUp.PACK_KEY, "ach-student@2026112201"))
  }

  @Test fun stampingUnaffectedFilesForV1LeavesThemQueuedForThePack() {
    val lg = note("luganda.opus", notEnglish, info = null)   // read before the v1 stamp, no singing: v1 cannot change it
    store.speechPackStamp = "lg-student@2026112201"
    assertEquals(setOf(lg), queue())
    assertEquals(1, store.stampUnaffectedSoundtracks())
    assertTrue(ReadingStamps.has(info(lg), SoundtrackCatchUp.KEY, SoundtrackCatchUp.VERSION))
    assertFalse(ReadingStamps.has(info(lg), SoundtrackCatchUp.PACK_KEY, "lg-student@2026112201"))
    assertEquals("still waits for the pack", setOf(lg), queue())
  }

  @Test fun aStampThatIsNotOneTokenIsRefused() {
    store.speechPackStamp = "lg' OR 1=1 --"
    assertEquals(null, store.speechPackStamp)
    store.speechPackStamp = "lg-student@2026112201"
    assertEquals("lg-student@2026112201", store.speechPackStamp)
  }
}
