package com.craneai.findr.index

import com.craneai.findr.core.AudioTranscript
import com.craneai.findr.core.FileKind
import com.craneai.findr.core.SoundTags
import com.craneai.findr.engine.TranscriptPieces
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The catch-up of videos and recordings whose speech an earlier build read (SoundtrackCatchUp): which files it takes, which it stamps
 * without opening, what it writes, and that it writes nothing else. The reading itself (VAD, Parakeet, the language gate) needs the
 * speech model; it is the same code a fresh read runs, so its output is what a fresh read stores.
 */
class SoundtrackCatchUpTest {
  private lateinit var dir: File
  private lateinit var store: IndexStore

  @Before fun setUp() {
    dir = Files.createTempDirectory("findr-soundtrack").toFile()
    store = IndexStore.open(dir, log = {})
  }

  @After fun tearDown() {
    store.close()
    dir.deleteRecursively()
  }

  private var nextId = 1L
  private fun add(name: String, kind: FileKind, durationMs: Long? = null): Long {
    val id = nextId++
    store.upsertFromCrawl(listOf(CrawlRecord("external_primary", id, "content://media/external/file/$id", "DCIM/", name, kind, null, 50_000, 1_000, durationMs = durationMs)))
    return store.knownMediaStoreIds("external_primary").getValue(id)
  }

  private fun vec(seed: Int) = FloatArray(Fp16.DIM) { if (it == seed % Fp16.DIM) 1f else 0f }
  private val speechNote = "4 frames, 3 speech pieces (speech 2.0 s text 0.1 s), sounds"

  /** A video a build without the soundtrack stamp read: pictures, their words, a transcript, the sounds line, an audio vector. */
  private fun video(name: String, sounds: String? = "Sounds: Speech, Music", info: String? = null, state: FileState = FileState.UNDERSTOOD, detail: String? = speechNote): Long {
    val id = add(name, FileKind.VIDEO, 45_000)
    store.saveSegments(id, listOfNotNull(
      Segment(SegmentKind.FRAME, Modality.IMAGE, 0, 15_000, vector = vec(1)),
      Segment(SegmentKind.OCR, text = "GRAND OPENING"),
      Segment(SegmentKind.TRANSCRIPT, Modality.TEXT, 0, 9_000, "old words of the song", vec(4), marks = "0:0:9000"),
      Segment(SegmentKind.AUDIO, Modality.AUDIO, 0, 10_000, vector = vec(6)),
      sounds?.let { Segment(SegmentKind.SOUND, Modality.SOUND, null, null, it, vec(5)) },
    ), state, detail, info = info)
    return id
  }

  private fun recording(name: String, sounds: String? = "Sounds: Speech", info: String? = null): Long {
    val id = add(name, FileKind.AUDIO, 30_000)
    store.saveSegments(id, listOfNotNull(
      Segment(SegmentKind.TRANSCRIPT, Modality.TEXT, 0, 9_000, "old words", vec(7)),
      sounds?.let { Segment(SegmentKind.SOUND, Modality.SOUND, null, null, it, vec(8)) },
    ), FileState.UNDERSTOOD, "2 speech pieces (speech 1.0 s text 0.1 s), sounds", info = info)
    return id
  }

  private fun queue(kinds: Set<FileKind> = setOf(FileKind.VIDEO, FileKind.AUDIO)) = store.nextOldSoundtracks(50, kinds).map { it.file.id }.toSet()

  /** Every stored byte of a file's segments except the transcript ones (their ids, kinds, vectors, marks, words), and its row's state, detail and dates. */
  private fun untouched(fileId: Long): String = store.read { db ->
    val sb = StringBuilder()
    db.query("SELECT state, detail, indexed_at, mtime, date_ms FROM files WHERE id = ?", fileId) { for (c in 0 until 5) sb.append(it.textOrNull(c)).append('|') }
    db.query("SELECT s.id, s.kind, s.modality, s.loc_start, s.loc_end, s.late, hex(s.vec), s.marks, x.body FROM segments s LEFT JOIN fts x ON x.rowid = s.id WHERE s.file = ? AND s.kind != 'transcript' ORDER BY s.id", fileId) {
      for (c in 0 until 9) sb.append(it.textOrNull(c)).append('|'); sb.append('\n')
    }
    sb.toString()
  }

  private fun transcripts(fileId: Long): List<String> = store.read { db ->
    val out = ArrayList<String>()
    db.query("SELECT x.body FROM segments s JOIN fts x ON x.rowid = s.id WHERE s.file = ? AND s.kind = 'transcript' ORDER BY s.id", fileId) { out += it.getText(0) }
    out
  }

  private fun transcript(text: String, seed: Int, start: Long = 0) = Segment(SegmentKind.TRANSCRIPT, Modality.TEXT, start, start + 9_000, text, vec(seed), marks = "0:0:9000")

  @Test fun stampsAreTokensAndAnOldVideoStampStillReads() {
    assertTrue(ReadingStamps.has("video-reading=1", "video-reading", 1))
    assertEquals("video-reading=1 soundtrack-reading=1", ReadingStamps.put("video-reading=1", "soundtrack-reading", 1))
    assertEquals("video-reading=1 soundtrack-reading=2", ReadingStamps.put("video-reading=1 soundtrack-reading=1", "soundtrack-reading", 2))
    assertFalse(ReadingStamps.has("video-reading=1 soundtrack-reading=1", "soundtrack-reading", 11))   // whole tokens only
    assertFalse(ReadingStamps.has(null, "video-reading", 1))
    assertEquals("soundtrack-reading=1", ReadingStamps.put(null, "soundtrack-reading", 1))
  }

  @Test fun onlyAFileThatMightSingNeedsReading() {
    assertFalse(SoundtrackCatchUp.needsRead("Sounds: Speech"))
    assertFalse(SoundtrackCatchUp.needsRead("Sounds: Speech, Music, Dog"))
    assertTrue(SoundtrackCatchUp.needsRead("Sounds: Music, Male singing"))
    assertTrue(SoundtrackCatchUp.needsRead("Sounds: Choir"))
    assertTrue(SoundtrackCatchUp.needsRead("Sounds: A capella, Speech"))
    assertTrue(SoundtrackCatchUp.needsRead("Sounds: Vocal music"))
    assertTrue(SoundtrackCatchUp.needsRead(null))                                                         // no line: cannot tell
    assertTrue(SoundtrackCatchUp.needsRead("Sounds: " + (1..12).joinToString(", ") { "Label$it" }))      // a full line may have lost its later labels
  }

  @Test fun theQueueIsUnstampedUnderstoodFilesWhoseSpeechWasJudged() {
    val old = video("old.mp4")
    val rec = recording("note.m4a")
    val stamped = video("new.mp4", info = "video-reading=1 soundtrack-reading=1")
    val videoStampOnly = video("v.mp4", info = "video-reading=1")                     // the video stamp says nothing about the soundtrack
    val partial = video("mid.mp4", state = FileState.PARTIAL, detail = "next=2")
    val noSpeech = video("quiet.mp4", detail = "4 frames, speech model is not installed")
    val photo = add("p.jpg", FileKind.IMAGE).also { store.saveSegments(it, listOf(Segment(SegmentKind.IMAGE, Modality.IMAGE, vector = vec(9))), FileState.UNDERSTOOD) }
    assertEquals(setOf(old, rec, videoStampOnly), queue())
    assertEquals(setOf(rec), queue(setOf(FileKind.AUDIO)))
    assertTrue(setOf(stamped, partial, noSpeech, photo).none { it in queue() })
    assertEquals(3 to 120_000L, store.oldSoundtracksLeft())
  }

  @Test fun filesTheChangeCannotAlterAreStampedWithoutBeingOpened() {
    val talk = video("talk.mp4", sounds = "Sounds: Speech, Music")
    val note = recording("note.m4a", sounds = "Sounds: Speech")
    val song = video("song.mp4", sounds = "Sounds: Music, Male singing")
    val unknown = video("nolabels.mp4", sounds = null)
    val full = video("busy.mp4", sounds = "Sounds: " + (1..12).joinToString(", ") { "Label$it" })
    val before = listOf(talk, note, song, unknown, full).associateWith { untouched(it) }
    assertEquals(2, store.stampUnaffectedSoundtracks())
    assertEquals(setOf(song, unknown, full), queue())
    assertEquals(0, store.stampUnaffectedSoundtracks())                                  // and only once
    for ((id, h) in before) assertEquals("a stamp changes nothing but the stamp", h, untouched(id))
    assertEquals("soundtrack-reading=1", store.infoOf(talk))
  }

  @Test fun theCatchUpReplacesTheTranscriptAndNothingElse() {
    val id = video("song.mp4", sounds = "Sounds: Music, Choir", info = "video-reading=1")
    val other = video("other.mp4", sounds = "Sounds: Choir")
    val before = untouched(id); val otherBefore = untouched(other)
    assertTrue(store.saveSoundtrackCatchUp(id, listOf(transcript("new words of the whole song", 40), transcript("and its second window", 41, 60_000))))
    assertEquals(listOf("new words of the whole song", "and its second window"), transcripts(id))
    assertEquals("pictures, their words, the sounds line, the audio vector, state, detail and dates are as they were", before, untouched(id))
    assertEquals("video-reading=1 soundtrack-reading=1", store.infoOf(id))      // the video stamp survives
    assertEquals(otherBefore, untouched(other)); assertEquals(listOf("old words of the song"), transcripts(other))
    assertFalse("stamped, so not queued", id in queue())
    assertFalse("a second save finds the file no longer in the queue", store.saveSoundtrackCatchUp(id, listOf(transcript("again", 42))))
    assertEquals(listOf("new words of the whole song", "and its second window"), transcripts(id))
  }

  @Test fun anEmptyReadingRemovesTheOldTranscriptAndAChangedFileIsLeftAlone() {
    val id = video("song.mp4", sounds = "Sounds: Singing")
    assertTrue(store.saveSoundtrackCatchUp(id, emptyList()))        // the new reading found no English words: that is the reading
    assertEquals(emptyList<String>(), transcripts(id))
    val changed = video("edited.mp4", sounds = "Sounds: Singing")
    store.setState(changed, FileState.SEARCHABLE)                    // changed or read again since it was picked
    assertFalse(store.saveSoundtrackCatchUp(changed, listOf(transcript("x", 43))))
    assertEquals(listOf("old words of the song"), transcripts(changed))
  }

  private fun soundLines(fileId: Long): List<String> = store.read { db ->
    val o = ArrayList<String>(); db.query("SELECT x.body FROM segments s JOIN fts x ON x.rowid = s.id WHERE s.file = ? AND s.kind = 'sound' ORDER BY s.id", fileId) { o += it.getText(0) }; o
  }

  @Test fun theSoundsLineIsReplacedOnlyWhenTheReadingHasOne() {
    val id = video("song.mp4", sounds = "Sounds: Music, Choir")
    assertTrue(store.saveSoundtrackCatchUp(id, listOf(transcript("t", 44)), listOf(Segment(SegmentKind.SOUND, Modality.SOUND, null, null, "Sounds: Singing, Choir", vec(45)))))
    assertEquals(listOf("Sounds: Singing, Choir"), soundLines(id))
    val kept = video("kept.mp4", sounds = "Sounds: Music, Choir")
    assertTrue(store.saveSoundtrackCatchUp(kept, listOf(transcript("t", 46))))     // no line in the new reading: the old one stays
    assertEquals(listOf("Sounds: Music, Choir"), soundLines(kept))
  }

  /** Every stored byte of a file's segments other than its transcript and sounds lines: the pictures, their vectors and words, the audio vector. */
  private fun picturesAndAudio(fileId: Long): String = store.read { db ->
    val sb = StringBuilder()
    db.query("SELECT state, detail, indexed_at, mtime, date_ms FROM files WHERE id = ?", fileId) { for (c in 0 until 5) sb.append(it.textOrNull(c)).append('|') }
    db.query("SELECT s.id, s.kind, s.modality, s.loc_start, s.loc_end, s.late, hex(s.vec), s.marks, x.body FROM segments s LEFT JOIN fts x ON x.rowid = s.id WHERE s.file = ? AND s.kind NOT IN ('transcript', 'sound') ORDER BY s.id", fileId) {
      for (c in 0 until 9) sb.append(it.textOrNull(c)).append('|'); sb.append('\n')
    }
    sb.toString()
  }

  @Test fun anOldSingingVideoGetsThePlainWordsInEveryWindowsSoundsLine() {
    assertTrue(SoundtrackCatchUp.REFRESH_SOUNDS)
    // A long video read by an earlier build: two windows, each with its own sounds line without the plain words.
    val id = add("choir.mp4", FileKind.VIDEO, 150_000)
    store.saveSegments(id, listOf(
      Segment(SegmentKind.FRAME, Modality.IMAGE, 0, 15_000, vector = vec(1)),
      Segment(SegmentKind.OCR, text = "GRAND OPENING"),
      Segment(SegmentKind.TRANSCRIPT, Modality.TEXT, 0, 9_000, "old words of the song", vec(4), marks = "0:0:9000"),
      Segment(SegmentKind.AUDIO, Modality.AUDIO, 0, 10_000, vector = vec(6)),
      Segment(SegmentKind.SOUND, Modality.SOUND, null, null, "Sounds: Music, Male singing", vec(5)),
      Segment(SegmentKind.FRAME, Modality.IMAGE, 70_000, 85_000, vector = vec(2), late = true),
      Segment(SegmentKind.SOUND, Modality.SOUND, null, null, "Sounds: Female singing", vec(9), late = true),
    ), FileState.UNDERSTOOD, speechNote)
    assertTrue(id in queue())
    assertEquals(0, store.stampUnaffectedSoundtracks())                    // it sings: read again, not stamped
    val pictures = picturesAndAudio(id)
    assertTrue(pictures.contains("GRAND OPENING") && pictures.contains("frame"))
    // The catch-up's reading of the same soundtrack: the lines a fresh read writes today, one per window (TranscriptPieces).
    fun line(vararg labels: String) = TranscriptPieces.sounds(AudioTranscript(emptyList(), listOf(SoundTags(0, 10_000, labels.toList())), 10.0))!!.text
    val sounds = listOf(
      Segment(SegmentKind.SOUND, Modality.SOUND, null, null, line("Music", "Male singing"), vec(10)),
      Segment(SegmentKind.SOUND, Modality.SOUND, null, null, line("Female singing"), vec(11), late = true),
    )
    assertTrue(store.saveSoundtrackCatchUp(id, listOf(transcript("good morning to all", 47)), sounds))
    assertEquals(listOf("Sounds: Music, Male singing (man singing)", "Sounds: Female singing (woman singing)"), soundLines(id))
    assertEquals("pictures, their vectors and words, the audio vector, state, detail and dates are as they were", pictures, picturesAndAudio(id))
    assertEquals(listOf("good morning to all"), transcripts(id))
    assertFalse(id in queue())
  }

  @Test fun aFreshReadIsStampedAndAChangedFileIsQueuedAgain() {
    val id = add("fresh.mp4", FileKind.VIDEO, 45_000)
    store.saveSegments(id, listOf(Segment(SegmentKind.TRANSCRIPT, Modality.TEXT, 0, 9_000, "words", vec(4))), FileState.UNDERSTOOD, speechNote,
      info = "video-reading=1 soundtrack-reading=1")
    assertFalse(id in queue())
    assertTrue(id !in store.nextOldVideos(10).map { it.file.id })       // and the video catch-up reads the same token
    assertEquals(0, store.oldSoundtracksLeft().first)
  }

  @Test fun theVideoCatchUpStillSeesItsOwnStampBesideTheNewOne() {
    val both = video("both.mp4", info = "video-reading=1 soundtrack-reading=1")
    val soundOnly = video("s.mp4", info = "soundtrack-reading=1")
    assertEquals(setOf(soundOnly), store.nextOldVideos(10).map { it.file.id }.toSet())
    assertTrue(store.saveVideoCatchUp(soundOnly, emptyMap(), emptyList(), keepOld = true))
    assertEquals("soundtrack-reading=1 video-reading=1", store.infoOf(soundOnly))      // the video catch-up keeps the soundtrack stamp
    assertTrue(both !in store.nextOldVideos(10).map { it.file.id })
  }

  @Test fun aFileThatKillsTheProcessTwiceIsStampedAsItIsAndLeavesTheQueue() {
    val id = video("killer.mp4", sounds = "Sounds: Choir")
    assertTrue(store.beginSoundtrackCatchUp(id))      // first try
    assertTrue(store.beginSoundtrackCatchUp(id))      // the process died inside it once
    assertFalse(store.beginSoundtrackCatchUp(id))     // twice: give up
    assertEquals("soundtrack-reading=1", store.infoOf(id))
    assertFalse(id in queue())
    assertEquals(listOf("old words of the song"), transcripts(id))    // it keeps the reading it has
    assertTrue(store.beginSoundtrackCatchUp(video("fine.mp4", sounds = "Sounds: Choir")))
    store.endSoundtrackCatchUp()
    assertNull(store.meta(SoundtrackCatchUp.INFLIGHT))
  }
}
