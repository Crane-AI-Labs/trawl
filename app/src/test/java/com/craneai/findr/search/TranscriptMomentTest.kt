package com.craneai.findr.search

import com.craneai.findr.core.AudioTranscript
import com.craneai.findr.core.FileKind
import com.craneai.findr.core.KindFilter
import com.craneai.findr.core.Locator
import com.craneai.findr.core.SoundTags
import com.craneai.findr.core.SpeechSegment
import com.craneai.findr.core.SpokenWord
import com.craneai.findr.core.TranscriptMarks
import com.craneai.findr.engine.Piece
import com.craneai.findr.engine.PieceKind
import com.craneai.findr.engine.PieceModality
import com.craneai.findr.engine.TranscriptPieces
import com.craneai.findr.engine.toSegment
import com.craneai.findr.index.CrawlRecord
import com.craneai.findr.index.FileState
import com.craneai.findr.index.IndexStore
import com.craneai.findr.index.Segment
import com.craneai.findr.index.SegmentKind
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * A video or recording hit says WHEN the matched words are said ("at 0:40"), not when the transcript piece starts. A short phone video
 * is one piece from ~0 s, so every match used to read "at 0:00".
 */
class TranscriptMomentTest {
  private lateinit var dir: File
  private lateinit var store: IndexStore
  private lateinit var searcher: Searcher
  private var nextId = 1L

  @Before fun setUp() {
    dir = Files.createTempDirectory("findr-moment").toFile()
    store = IndexStore.open(dir, log = {})
    searcher = Searcher(store, VectorIndex(parallelism = 1))
  }

  @After fun tearDown() {
    store.close()
    dir.deleteRecursively()
  }

  private fun say(startS: Double, endS: Double, text: String) = SpeechSegment((startS * 1000).toLong(), (endS * 1000).toLong(), text)

  private fun transcriptSegments(speech: List<SpeechSegment>) =
    TranscriptPieces.speech(AudioTranscript(speech, emptyList(), 0.0))
      .map { Piece(PieceKind.TRANSCRIPT, PieceModality.TEXT, it.startMs, it.endMs, it.text, null, it.marks).toSegment(false) }

  private fun addFile(name: String, kind: FileKind, segments: List<Segment>) {
    val id = nextId++
    store.upsertFromCrawl(listOf(CrawlRecord("external_primary", id, "content://media/external/file/$id", "DCIM/Camera/", name, kind, null, 50_000, 1_000)))
    val fileId = store.knownMediaStoreIds("external_primary").getValue(id)
    store.saveSegments(fileId, segments, FileState.UNDERSTOOD)
  }

  private fun momentOf(query: String): Long {
    val hit = searcher.search(query, null, KindFilter.ALL, 10).hits.single()
    val locator = hit.locator
    assertTrue("expected a time, got $locator", locator is Locator.Time)
    return (locator as Locator.Time).startMs
  }

  /** The short clip: the whole transcript is one piece starting at ~0 s, the phrase is said at 40 s. */
  @Test fun aShortClipPointsAtTheSecondTheWordsAreSaid() {
    val speech = listOf(
      say(0.4, 4.0, "Hello everyone and welcome to the little demo of my new phone"),
      say(8.0, 13.0, "First I will show you the camera and how it handles the evening light"),
      say(20.0, 26.0, "Then we look at the battery which easily lasts a full day of heavy use"),
      say(40.0, 44.0, "Remember the quarterly budget meeting is moved to Thursday"),
      say(50.0, 55.0, "Thanks for watching and see you in the next one"),
    )
    val segments = transcriptSegments(speech)
    assertEquals("the whole clip is one piece", 1, segments.size)
    assertEquals(400L, segments[0].locStart)
    addFile("demo.mp4", FileKind.VIDEO, segments)
    val at = momentOf("budget meeting")
    assertTrue("said at 40-44 s, badge says ${at / 1000.0} s", at in 40_000..44_000)
    // a word from the first and from the last sentence
    assertTrue(momentOf("welcome") in 400..4_000)
    assertTrue(momentOf("watching") in 50_000..55_000)
  }

  /**
   * Parakeet times its words: a word in a long segment is placed at the word, not at its share of the segment's characters. Said here
   * in a 15 s segment after a long pause, where the share puts it seconds early.
   */
  @Test fun aTimedWordIsPlacedAtTheWordNotItsShareOfTheSegment() {
    val early = listOf("alpha", "bravo", "charlie", "delta", "echo", "foxtrot").mapIndexed { i, t -> SpokenWord(t, 10_000L + i * 400, 10_000L + i * 400 + 300) }
    val late = listOf(SpokenWord("zulu", 22_000, 22_400), SpokenWord("yankee", 22_500, 22_900), SpokenWord("whiskey", 23_000, 23_500))
    val words = early + late
    val text = words.joinToString(" ") { it.text }
    addFile("timed.mp4", FileKind.VIDEO, transcriptSegments(listOf(SpeechSegment(10_000, 25_000, text, words))))
    assertEquals(22_000L, momentOf("zulu"))
    // a pair's second word sits at its share of the pair's span: within a word of where it was said
    assertTrue(kotlin.math.abs(momentOf("yankee") - 22_500) < 300)
    addFile("untimed.mp4", FileKind.VIDEO, transcriptSegments(listOf(SpeechSegment(10_000, 25_000, text))))
    val byShare = searcher.search("yankee", null, KindFilter.ALL, 10).hits.single { it.file.name == "untimed.mp4" }.locator as Locator.Time
    assertTrue("the segment mark places it by character share, seconds off: ${byShare.startMs}", kotlin.math.abs(byShare.startMs - 22_500) > 1_000)
  }

  /** A stemmed match ("meetings" for "meeting") and a word in the middle of a long segment are placed inside it. */
  @Test fun aWordInsideALongSegmentIsPlacedInsideIt() {
    val speech = listOf(
      say(0.0, 30.0, "alpha bravo charlie delta echo foxtrot golf hotel india juliet kilo lima mike november oscar papa quebec romeo sierra tango"),
    )
    addFile("long.m4a", FileKind.AUDIO, transcriptSegments(speech))
    val at = momentOf("sierra")
    assertTrue("late in the segment, got $at", at in 24_000..30_000)
    assertTrue(momentOf("alpha") < 3_000)
  }

  /** A long recording is read in 60 s windows (each stamped with its own offset): the hit lands in the right window. */
  @Test fun aLongRecordingReadInWindowsPointsIntoTheRightWindow() {
    val windows = listOf(
      listOf(say(2.0, 9.0, "Good morning team let us start with the weekly status"), say(30.0, 38.0, "Sales are up three percent against last month")),
      listOf(say(61.0, 70.0, "Next the engineering update on the release candidate"), say(95.0, 104.0, "Support tickets dropped after the hotfix went out")),
      listOf(say(125.0, 131.0, "Finally the offsite is booked for the lake house"), say(150.0, 158.0, "The password rotation deadline is the fifteenth of March")),
    )
    addFile("standup.m4a", FileKind.AUDIO, windows.flatMap(::transcriptSegments))
    assertTrue(momentOf("hotfix") in 95_000..104_000)
    assertTrue(momentOf("lake house") in 125_000..131_000)
    assertTrue(momentOf("password rotation") in 150_000..158_000)
    assertTrue(momentOf("weekly status") in 2_000..9_000)
  }

  /** The language gate's partial English runs, and the sounds line, can't be placed: no time, never a wrong 0:00. */
  @Test fun partialRunsAndSoundLabelsShowNoTime() {
    val runs = TranscriptPieces.ofText("see you at the budget meeting tomorrow morning")
    assertTrue(runs.all { it.startMs == null && it.endMs == null && it.marks == null })
    val sounds = TranscriptPieces.sounds(AudioTranscript(emptyList(), listOf(SoundTags(0, 10_000, listOf("Dog", "Bark"))), 10.0))
    assertNotNull(sounds)
    assertNull(sounds!!.startMs)
    val segments = runs.map { Piece(PieceKind.TRANSCRIPT, PieceModality.TEXT, it.startMs, it.endMs, it.text).toSegment(false) } +
      Piece(PieceKind.SOUND, PieceModality.SOUND, sounds.startMs, sounds.endMs, sounds.text).toSegment(false)
    addFile("mixed.m4a", FileKind.AUDIO, segments)
    val hit = searcher.search("budget meeting", null, KindFilter.ALL, 10).hits.single()
    assertNull(hit.locator)
    // An old index may hold a sounds row stamped with times: still no moment.
    addFile("barks.m4a", FileKind.AUDIO, listOf(Segment(SegmentKind.SOUND, locStart = 0, locEnd = 9_000, text = "Sounds: Dog, Bark")))
    assertNull(searcher.search("Dog", null, KindFilter.ALL, 10).hits.single { it.file.name == "barks.m4a" }.locator)
  }

  /** A row written before the marks existed: placed by character position between its start and end, not at its start. */
  @Test fun anOldPieceWithoutMarksIsInterpolatedByCharacterPosition() {
    val text = "one two three four five six seven eight nine ten eleven twelve thirteen fourteen fifteen sixteen seventeen eighteen nineteen twenty"
    addFile("old.mp4", FileKind.VIDEO, listOf(Segment(SegmentKind.TRANSCRIPT, locStart = 0, locEnd = 60_000, text = text)))
    val late = momentOf("nineteen")
    assertTrue("near the end, got $late", late in 45_000..60_000)
    assertTrue(momentOf("one") < 5_000)
  }

  @Test fun marksNameEachSegmentOffsetInThePieceText() {
    val pieces = TranscriptPieces.speech(AudioTranscript(listOf(say(1.0, 2.0, " hi there "), say(5.0, 6.0, "you all")), emptyList(), 0.0))
    assertEquals(1, pieces.size)
    assertEquals("hi there you all", pieces[0].text)
    assertEquals("0:1000:2000,9:5000:6000", pieces[0].marks)
    assertEquals(1_000L, pieces[0].startMs)
    assertEquals(6_000L, pieces[0].endMs)
  }

  @Test fun marksRoundTripAndBadMarksFallBack() {
    val marks = listOf(TranscriptMarks.Mark(0, 100, 900), TranscriptMarks.Mark(12, 4_000, 6_000))
    val encoded = TranscriptMarks.encode(marks)
    assertEquals("0:100:900,12:4000:6000", encoded)
    assertEquals(listOf(0, 12), TranscriptMarks.decode(encoded).map { it.offset })
    assertNull(TranscriptMarks.encode(emptyList()))
    assertTrue(TranscriptMarks.decode(null).isEmpty())
    assertTrue(TranscriptMarks.decode("garbage").isEmpty())
  }

  @Test fun timeAtUsesTheSegmentThatHoldsTheCharacter() {
    val marks = listOf(TranscriptMarks.Mark(0, 1_000, 3_000), TranscriptMarks.Mark(11, 40_000, 44_000))   // "hello there" + "budget talk"
    assertEquals(1_000L, TranscriptMarks.timeAt(marks, 22, 1_000, 44_000, 0))
    assertEquals(40_000L, TranscriptMarks.timeAt(marks, 22, 1_000, 44_000, 11))
    assertEquals(41_818L, TranscriptMarks.timeAt(marks, 22, 1_000, 44_000, 16))   // 5 of the segment's 11 characters in
    assertEquals(44_000L, TranscriptMarks.timeAt(marks, 22, 1_000, 44_000, 99))
    // no marks: character position between the piece's start and end; no times: no answer
    assertEquals(30_000L, TranscriptMarks.timeAt(emptyList(), 100, 0, 60_000, 50))
    assertEquals(5_000L, TranscriptMarks.timeAt(emptyList(), 100, 5_000, null, 50))
    assertNull(TranscriptMarks.timeAt(emptyList(), 100, null, null, 50))
  }

  @Test fun theMatchIsFoundFromTheSnippetOrTheQueryWords() {
    val body = "the cat sat on the mat while the budget committee argued about meetings"
    // the quoted stretch carries the match: its place in the body, not the first "the"
    assertEquals(body.indexOf("budget"), TranscriptMarks.matchOffset(body, "…while the «budget» committee argued about…", listOf("budget")))
    // no snippet from this piece: the first word that begins like a query word, with Porter's endings tolerated
    assertEquals(body.indexOf("meetings"), TranscriptMarks.matchOffset(body, null, listOf("meeting")))
    assertEquals(body.indexOf("cat"), TranscriptMarks.matchOffset(body, null, listOf("category", "cat")))
    assertNull(TranscriptMarks.matchOffset(body, null, listOf("zebra")))
    assertNull(TranscriptMarks.matchOffset(body, null, emptyList()))
  }
}
