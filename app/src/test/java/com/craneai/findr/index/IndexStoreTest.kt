package com.craneai.findr.index

import com.craneai.findr.core.FileKind
import com.craneai.findr.engine.IndexPolicy
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

class IndexStoreTest {
  private lateinit var dir: File
  private lateinit var store: IndexStore
  private val logs = ArrayList<String>()

  @Before fun setUp() {
    dir = Files.createTempDirectory("findr-index").toFile()
    store = IndexStore.open(dir, log = { logs += it })
  }

  @After fun tearDown() {
    store.close()
    dir.deleteRecursively()
  }

  private var nextId = 1L
  private fun record(
    name: String, kind: FileKind = FileKind.IMAGE, mtime: Long = 1_000, size: Long = 50_000, path: String = "DCIM/Camera/",
    id: Long = nextId++, skip: String? = null,
  ) = CrawlRecord("external_primary", id, "content://media/external/file/$id", path, name, kind, null, size, mtime, skipReason = skip)

  private fun vec(seed: Int, dim: Int = Fp16.DIM) = FloatArray(dim) { if (it == seed % dim) 1f else 0f }

  private fun ftsHits(match: String): List<Long> = store.read { db ->
    val out = ArrayList<Long>()
    db.query("SELECT rowid FROM fts WHERE fts MATCH ?", match) { out += it.getLong(0) }
    out
  }

  @Test fun trawlGoFinishesTheKindsItCannotReadByTheirNamesAndLeavesTheRest() {
    store.upsertFromCrawl(listOf(
      record("clip.mp4", FileKind.VIDEO), record("song.m4a", FileKind.AUDIO), record("notes.docx", FileKind.DOCUMENT),
      record("photo.jpg", FileKind.IMAGE), record("scan.pdf", FileKind.PDF), record("shot.png", FileKind.SCREENSHOT),
    ))
    val readable = com.craneai.findr.engine.FindrEngine.WORDS_PASS_KINDS
    assertEquals("the video and the sound file", 2, store.finishNameOnly(readable))
    val s = store.stats()
    assertEquals(1, s.count(FileKind.VIDEO, FileState.UNDERSTOOD))
    assertEquals(1, s.count(FileKind.AUDIO, FileState.UNDERSTOOD))
    for (k in readable) assertEquals("$k still waits for its words", 1, s.count(k, FileState.SEARCHABLE))
    assertEquals("done files are not touched again", 0, store.finishNameOnly(readable))
    assertEquals("their names stay searchable", 1, ftsHits("clip").size)
    assertEquals("a done file is not queued for words", setOf("notes.docx", "photo.jpg", "scan.pdf", "shot.png"),
      store.nextWithoutWords(10, FileKind.entries.toSet()).map { it.file.name }.toSet())
  }

  @Test fun schemaIsCreatedWithWalAndFts5() {
    val mode = store.read { it.text("PRAGMA journal_mode") }
    assertEquals("wal", mode)
    assertEquals(Schema.VERSION, store.read { it.long("PRAGMA user_version") })
    // FTS5 with the porter tokenizer: "receipts" finds "receipt".
    store.upsertFromCrawl(listOf(record("receipt_2026.pdf", FileKind.PDF)))
    assertEquals(1, ftsHits("receipts").size)
  }

  @Test fun upsertIsIdempotentAndNamesAreSearchableAtOnce() {
    val rs = listOf(record("IMG_001.jpg"), record("Screenshot_20261001.png", FileKind.SCREENSHOT))
    val first = store.upsertFromCrawl(rs)
    assertEquals(2, first.added)
    val again = store.upsertFromCrawl(rs)
    assertEquals(0, again.added)
    assertEquals(2, again.unchanged)
    assertEquals(2, store.stats().filesKnown)
    assertEquals(1, ftsHits("screenshot_20261001").size.coerceAtLeast(ftsHits("screenshot").size))
    // The folder words are searchable too, at low weight.
    assertEquals(2, ftsHits("camera").size)
  }

  @Test fun changedFileLosesWhatWasReadAndIsQueuedAgain() {
    store.upsertFromCrawl(listOf(record("a.jpg", id = 7)))
    val id = store.knownMediaStoreIds("external_primary")[7]!!
    store.saveSegments(id, listOf(Segment(SegmentKind.IMAGE, Modality.IMAGE, vector = vec(1))), FileState.UNDERSTOOD)
    assertEquals(1, store.stats().vectors)
    val r = store.upsertFromCrawl(listOf(record("a.jpg", id = 7, mtime = 2_000)))
    assertEquals(1, r.changed)
    assertEquals(0, store.stats().vectors)
    assertEquals(FileState.SEARCHABLE, store.stateOf(id)!!.first)
  }

  @Test fun aFileTheRulesNowCallADocumentIsQueuedToBeReadAndKeepsItsName() {
    // Before the office readers an xlsx was a name-only OTHER file, "understood" with nothing read.
    store.upsertFromCrawl(listOf(record("budget.xlsx", FileKind.OTHER, id = 9, path = "Download/")))
    val id = store.knownMediaStoreIds("external_primary")[9]!!
    store.setState(id, FileState.UNDERSTOOD, "not readable")
    val r = store.upsertFromCrawl(listOf(record("budget.xlsx", FileKind.DOCUMENT, id = 9, path = "Download/")))
    assertEquals(1, r.changed)
    assertEquals(FileKind.DOCUMENT, store.fileById(id)!!.kind)
    assertEquals(FileState.SEARCHABLE, store.stateOf(id)!!.first)
    assertEquals(1, ftsHits("budget").size)
    assertEquals(listOf(id), store.nextPending(10, setOf(FileKind.DOCUMENT)).map { it.file.id })
    // Crawled again with nothing new: left alone.
    assertEquals(1, store.upsertFromCrawl(listOf(record("budget.xlsx", FileKind.DOCUMENT, id = 9, path = "Download/"))).unchanged)
  }

  @Test fun renameKeepsVectors() {
    store.upsertFromCrawl(listOf(record("a.jpg", id = 7)))
    val id = store.knownMediaStoreIds("external_primary")[7]!!
    store.saveSegments(id, listOf(Segment(SegmentKind.IMAGE, Modality.IMAGE, vector = vec(1))), FileState.UNDERSTOOD)
    store.upsertFromCrawl(listOf(record("holiday.jpg", id = 7, path = "Pictures/")))
    assertEquals(1, store.stats().vectors)
    assertEquals(FileState.UNDERSTOOD, store.stateOf(id)!!.first)
    assertEquals("holiday.jpg", store.fileById(id)!!.name)
    assertEquals(1, ftsHits("holiday").size)
    assertEquals(0, ftsHits("a").size + ftsHits("camera").size)
  }

  @Test fun waitingIsWhatStatsSaysIsWaiting() {
    store.upsertFromCrawl(listOf(record("a.jpg", id = 1), record("b.pdf", FileKind.PDF, id = 2), record("c.png", skip = "icon (too small)", id = 3)))
    val kinds = setOf(FileKind.IMAGE, FileKind.PDF)
    assertEquals(2, store.waiting(kinds))
    assertEquals(store.stats().waiting(kinds), store.waiting(kinds))
    assertEquals(store.stats().waiting(setOf(FileKind.PDF)), store.waiting(setOf(FileKind.PDF)))
    assertEquals(0, store.waiting(emptySet()))
  }

  @Test fun skippedFilesAreRecordedButNotSearchable() {
    store.upsertFromCrawl(listOf(record("icon.png", skip = "icon (too small)", id = 3)))
    val stats = store.stats()
    assertEquals(1, stats.count(FileState.SKIPPED))
    assertEquals(0, stats.filesKnown)
    assertEquals(0, ftsHits("icon").size)
    assertNull(store.fileById(store.knownMediaStoreIds("external_primary")[3]!!))
    assertEquals("icon (too small)", store.read { it.text("SELECT detail FROM files WHERE mediastore_id = 3") })
  }

  @Test fun saveSegmentsIsOneTransaction() {
    store.upsertFromCrawl(listOf(record("doc.pdf", FileKind.PDF, id = 1)))
    val id = store.knownMediaStoreIds("external_primary")[1]!!
    val good = Segment(SegmentKind.PAGE, Modality.TEXT, 1, text = "quarterly budget", vector = vec(1))
    val bad = Segment(SegmentKind.PAGE, Modality.TEXT, 2, text = "second page", vector = FloatArray(5))   // wrong dimension
    val failure = runCatching { store.saveSegments(id, listOf(good, bad), FileState.UNDERSTOOD) }
    assertTrue(failure.isFailure)
    assertEquals(0, store.stats().segments)           // nothing partial
    assertEquals(0, ftsHits("quarterly").size)
    assertEquals(FileState.SEARCHABLE, store.stateOf(id)!!.first)
    // And the same file saves cleanly afterwards.
    val ids = store.saveSegments(id, listOf(good), FileState.UNDERSTOOD, "ok")
    assertEquals(1, ids.size)
    assertEquals(listOf(ids[0]), ftsHits("quarterly"))
    assertEquals("quarterly budget", store.textOf(ids[0]))
    assertEquals(FileState.UNDERSTOOD to "ok", store.stateOf(id))
  }

  @Test fun saveSegmentsReplacesOrAppends() {
    store.upsertFromCrawl(listOf(record("book.pdf", FileKind.PDF, id = 1)))
    val id = store.knownMediaStoreIds("external_primary")[1]!!
    store.saveSegments(id, listOf(Segment(SegmentKind.PAGE, Modality.TEXT, 1, text = "alpha", vector = vec(1))), FileState.PARTIAL, "read to page 1")
    store.saveSegments(id, listOf(Segment(SegmentKind.PAGE, Modality.TEXT, 2, text = "beta", vector = vec(2), late = true)), FileState.UNDERSTOOD, replace = false)
    assertEquals(2, store.stats().vectors)
    store.saveSegments(id, listOf(Segment(SegmentKind.PAGE, Modality.TEXT, 1, text = "gamma", vector = vec(3))), FileState.UNDERSTOOD)
    assertEquals(1, store.stats().vectors)
    assertEquals(0, ftsHits("alpha").size)
    assertEquals(1, ftsHits("gamma").size)
    assertEquals(1, ftsHits("book").size)   // the name survives re-reading
  }

  @Test fun filesReadWithoutSpeechModelsAreQueuedAgainOnceTheyAreIn() {
    store.upsertFromCrawl(listOf(record("meme.mp4", FileKind.VIDEO, id = 3), record("cat.jpg", id = 4)))
    val video = store.knownMediaStoreIds("external_primary")[3]!!
    val photo = store.knownMediaStoreIds("external_primary")[4]!!
    store.saveSegments(video, listOf(Segment(SegmentKind.FRAME, Modality.IMAGE, 0, 5_000, vector = vec(1))), FileState.UNDERSTOOD,
      "4 frames, 1 distinct, ${IndexStore.SPEECH_MISSING}")
    store.saveSegments(photo, listOf(Segment(SegmentKind.IMAGE, Modality.IMAGE, vector = vec(2))), FileState.UNDERSTOOD)
    assertEquals(1, store.retryMissingSpeech())
    assertEquals(FileState.SEARCHABLE, store.stateOf(video)!!.first)
    assertEquals(FileState.UNDERSTOOD, store.stateOf(photo)!!.first)
    assertEquals(2, store.stats().vectors)   // the frames stay searchable until the file is read again
    assertEquals(0, store.retryMissingSpeech())
  }

  @Test fun speechJudgedByAnOlderGateIsJudgedAgain() {
    store.upsertFromCrawl(listOf(
      record("talk.mp4", FileKind.VIDEO, id = 11), record("junk.mp4", FileKind.VIDEO, id = 12), record("clip.mp4", FileKind.VIDEO, id = 13),
      record("note.m4a", FileKind.AUDIO, id = 14), record("cat.jpg", id = 15)))
    val ids = (11..15).map { store.knownMediaStoreIds("external_primary")[it.toLong()]!! }
    store.saveSegments(ids[0], listOf(Segment(SegmentKind.FRAME, Modality.IMAGE, 0, 5_000, vector = vec(1))), FileState.UNDERSTOOD, "4 frames, 3 speech pieces (speech 2.0 s text 0.1 s)")
    store.saveSegments(ids[1], listOf(Segment(SegmentKind.FRAME, Modality.IMAGE, 0, 5_000, vector = vec(2))), FileState.UNDERSTOOD, "4 frames, speech not English (40% common words): audio vector")
    store.saveSegments(ids[2], listOf(Segment(SegmentKind.FRAME, Modality.IMAGE, 0, 5_000, vector = vec(3))), FileState.UNDERSTOOD, "4 frames, ${IndexStore.SPEECH_MISSING}")
    store.saveSegments(ids[3], listOf(Segment(SegmentKind.AUDIO, Modality.AUDIO, 0, 10_000, vector = vec(4))), FileState.UNDERSTOOD, "0 speech pieces (speech 1.0 s text 0.0 s)")
    store.saveSegments(ids[4], listOf(Segment(SegmentKind.IMAGE, Modality.IMAGE, vector = vec(5))), FileState.UNDERSTOOD)
    assertEquals(3, store.requeueSpeechJudgedAgain())          // judged either way, video or recording
    assertEquals(FileState.SEARCHABLE, store.stateOf(ids[0])!!.first)
    assertEquals(FileState.SEARCHABLE, store.stateOf(ids[1])!!.first)
    assertEquals(FileState.UNDERSTOOD, store.stateOf(ids[2])!!.first)
    assertEquals(FileState.SEARCHABLE, store.stateOf(ids[3])!!.first)
    assertEquals(FileState.UNDERSTOOD, store.stateOf(ids[4])!!.first)
    assertEquals(5, store.stats().vectors)                     // searchable until read again
    assertEquals(0, store.requeueSpeechJudgedAgain())          // and only once: the requeued files have no detail now
  }

  @Test fun longRecordingsLeftWithOnlyTheirNameAreQueuedAgainWhenDeeperSoundSearchTurnsOn() {
    store.upsertFromCrawl(listOf(record("song.mp3", FileKind.AUDIO, id = 6), record("note.m4a", FileKind.AUDIO, id = 7), record("meme.mp4", FileKind.VIDEO, id = 8)))
    val song = store.knownMediaStoreIds("external_primary")[6]!!
    val note = store.knownMediaStoreIds("external_primary")[7]!!
    val video = store.knownMediaStoreIds("external_primary")[8]!!
    store.saveSegments(song, emptyList(), FileState.UNDERSTOOD, IndexStore.AUDIO_OFF)
    store.saveSegments(note, listOf(Segment(SegmentKind.AUDIO, Modality.AUDIO, 0, 10_000, vector = vec(1))), FileState.UNDERSTOOD, "${IndexStore.SPEECH_MISSING}, 1 sound windows")
    store.saveSegments(video, emptyList(), FileState.UNDERSTOOD, IndexStore.AUDIO_OFF)
    store.upsertFromCrawl(listOf(record("podcast.mp3", FileKind.AUDIO, id = 9)))
    val podcast = store.knownMediaStoreIds("external_primary")[9]!!
    store.saveSegments(podcast, emptyList(), FileState.UNDERSTOOD, IndexStore.SPEECH_MISSING)   // long, speech asked for, no models
    // Read for speech while the speech pack worked: words and sound labels, no audio windows (Infinix window 2: the long file was never re-read).
    store.upsertFromCrawl(listOf(record("talk.mp3", FileKind.AUDIO, id = 10)))
    val talk = store.knownMediaStoreIds("external_primary")[10]!!
    store.saveSegments(talk, listOf(Segment(SegmentKind.SOUND, Modality.SOUND, 0, 10_000, text = "music", vector = vec(4))), FileState.UNDERSTOOD, "0 speech pieces, sounds (speech 12.2 s text 0.5 s)")
    assertEquals(3, store.retryAudioOff())
    assertEquals(FileState.SEARCHABLE, store.stateOf(talk)!!.first)
    assertEquals(FileState.SEARCHABLE, store.stateOf(podcast)!!.first)
    assertEquals(FileState.SEARCHABLE, store.stateOf(song)!!.first)
    assertEquals(FileState.UNDERSTOOD, store.stateOf(note)!!.first)    // already has its vector
    assertEquals(FileState.UNDERSTOOD, store.stateOf(video)!!.first)   // videos are not recordings
    assertEquals(0, store.retryAudioOff())
  }

  @Test fun laterStepsReadALongDocumentsChunksFromTheIndexInOrder() {
    store.upsertFromCrawl(listOf(record("report.pdf", FileKind.PDF, id = 5, path = "Download/")))
    val id = store.knownMediaStoreIds("external_primary")[5]!!
    // First step: chunk 0 embedded, chunks 1-3 words only (as FileProcessor stores them).
    store.saveSegments(id, listOf(
      Segment(SegmentKind.CHUNK, Modality.TEXT, 1, 1, text = "zero", vector = vec(1)),
      Segment(SegmentKind.CHUNK, Modality.TEXT, 1, 1, text = "one"),
      Segment(SegmentKind.CHUNK, Modality.TEXT, 2, 2, text = "two"),
      Segment(SegmentKind.CHUNK, Modality.TEXT, 3, 3, text = "three"),
    ), FileState.PARTIAL, "chunks 1/4 next=1")
    assertEquals(4, store.storedChunkCount(id))
    assertEquals(listOf(1L to "one", 2L to "two"), store.storedChunks(id, 1, 2))
    // A later step adds vectors only (late, no text): it doesn't shift what the next step reads.
    store.saveSegments(id, listOf(Segment(SegmentKind.CHUNK, Modality.TEXT, 1, 1, vector = vec(2), late = true),
      Segment(SegmentKind.CHUNK, Modality.TEXT, 2, 2, vector = vec(3), late = true)), FileState.PARTIAL, "chunks 3/4 next=3", replace = false)
    assertEquals(4, store.storedChunkCount(id))
    assertEquals(listOf(3L to "three"), store.storedChunks(id, 3, 40))
  }

  @Test fun documentsAreQueuedAgainWhenTheReaderChanges() {
    store.upsertFromCrawl(listOf(record("a.pdf", FileKind.PDF, id = 11, path = "Download/"), record("b.txt", FileKind.DOCUMENT, id = 12, path = "Download/"),
      record("c.jpg", id = 13), record("d.pdf", FileKind.PDF, id = 14, path = "Download/")))
    val ids = store.knownMediaStoreIds("external_primary")
    store.saveSegments(ids[11]!!, listOf(Segment(SegmentKind.CHUNK, Modality.TEXT, 1, 1, text = "x", vector = vec(1))), FileState.PARTIAL, "chunks 1/9 next=1")
    store.saveSegments(ids[12]!!, listOf(Segment(SegmentKind.CHUNK, Modality.TEXT, 1, 1, text = "y", vector = vec(2))), FileState.UNDERSTOOD)
    store.saveSegments(ids[13]!!, listOf(Segment(SegmentKind.IMAGE, Modality.IMAGE, vector = vec(3))), FileState.UNDERSTOOD)
    store.setState(ids[14]!!, FileState.FAILED, "took too long")
    assertEquals(2, store.requeueRead(listOf(FileKind.DOCUMENT, FileKind.PDF)))
    assertEquals(FileState.SEARCHABLE, store.stateOf(ids[11]!!)!!.first)
    assertEquals(FileState.SEARCHABLE, store.stateOf(ids[12]!!)!!.first)
    assertEquals(FileState.UNDERSTOOD, store.stateOf(ids[13]!!)!!.first)   // photos aren't touched
    assertEquals(FileState.FAILED, store.stateOf(ids[14]!!)!!.first)       // failures have their own retry
    assertEquals(3, store.stats().vectors)                                  // nothing is lost until read again
    assertEquals(0, store.requeueRead(emptyList()))
  }

  @Test fun videosReadWithoutSpeechCanBeQueuedAgain() {
    store.upsertFromCrawl(listOf(record("old.mp4", FileKind.VIDEO, id = 31), record("new.mp4", FileKind.VIDEO, id = 32),
      record("song.m4a", FileKind.AUDIO, id = 33, path = "Music/")))
    val ids = store.knownMediaStoreIds("external_primary")
    store.saveSegments(ids[31]!!, listOf(Segment(SegmentKind.FRAME, Modality.IMAGE, 0, 5_000, vector = vec(1))), FileState.UNDERSTOOD, "6 frames, 2 distinct (sample 0.5 s embed 8.0 s ocr 0.2 s)")
    store.saveSegments(ids[32]!!, listOf(Segment(SegmentKind.FRAME, Modality.IMAGE, 0, 5_000, vector = vec(2))), FileState.UNDERSTOOD, "3 frames, 1 distinct, 1 speech pieces, sounds (speech 2.0 s text 0.5 s)")
    store.saveSegments(ids[33]!!, listOf(Segment(SegmentKind.AUDIO, Modality.AUDIO, 0, 10_000, vector = vec(3))), FileState.UNDERSTOOD, null)
    assertEquals(2, store.requeueRead(listOf(FileKind.VIDEO, FileKind.AUDIO), detailLacks = "speech"))
    assertEquals(FileState.SEARCHABLE, store.stateOf(ids[31]!!)!!.first)
    assertEquals(FileState.UNDERSTOOD, store.stateOf(ids[32]!!)!!.first)   // already has its words
    assertEquals(FileState.SEARCHABLE, store.stateOf(ids[33]!!)!!.first)
    assertEquals(3, store.stats().vectors)
  }

  @Test fun aReaderChangeCanBeLimitedToTheFilesItAffects() {
    store.upsertFromCrawl(listOf(record("notes.TXT", FileKind.DOCUMENT, id = 21, path = "Download/"),
      record("report.pdf", FileKind.PDF, id = 22, path = "Download/"), record("speech.pdf.txt", FileKind.DOCUMENT, id = 23, path = "Download/")))
    val ids = store.knownMediaStoreIds("external_primary")
    for (k in listOf(21L, 22L, 23L)) store.saveSegments(ids[k]!!, listOf(Segment(SegmentKind.CHUNK, Modality.TEXT, 1, 1, text = "t", vector = vec(k.toInt()))), FileState.UNDERSTOOD)
    assertEquals(2, store.requeueRead(listOf(FileKind.DOCUMENT, FileKind.PDF), listOf("txt", "rtf")))
    assertEquals(FileState.SEARCHABLE, store.stateOf(ids[21]!!)!!.first)
    assertEquals(FileState.UNDERSTOOD, store.stateOf(ids[22]!!)!!.first)   // a PDF isn't read differently by this change
    assertEquals(FileState.SEARCHABLE, store.stateOf(ids[23]!!)!!.first)
  }

  private val day = 24L * 60 * 60 * 1000
  private val now = 100 * day   // a fixed clock, far enough from 0 that "14 days ago" is positive

  @Test fun aRecentVoiceNoteRunsBeforeTheOldPhotoBacklog() {
    store.clock = { now }
    store.upsertFromCrawl(listOf(
      record("old1.jpg", FileKind.IMAGE, mtime = now - 40 * day),
      record("old2.jpg", FileKind.IMAGE, mtime = now - 30 * day),
      record("shot.png", FileKind.SCREENSHOT, mtime = now - 20 * day),
      record("note.m4a", FileKind.AUDIO, mtime = now - 2 * 60 * 60 * 1000),
    ))
    val order = store.nextPending(10, FileKind.entries.toSet()).map { it.file.name }
    assertEquals(listOf("note.m4a", "shot.png", "old2.jpg", "old1.jpg"), order)
  }

  @Test fun recentFilesOfAnyKindRunNewestFirstAndOldOnesKeepKindOrder() {
    store.clock = { now }
    store.upsertFromCrawl(listOf(
      record("old_song.mp3", FileKind.AUDIO, mtime = now - 90 * day),
      record("old_clip.mp4", FileKind.VIDEO, mtime = now - 80 * day),
      record("old_doc.pdf", FileKind.PDF, mtime = now - 70 * day),
      record("old_photo.jpg", FileKind.IMAGE, mtime = now - 60 * day),
      record("old_shot.png", FileKind.SCREENSHOT, mtime = now - 50 * day),
      record("recent_clip.mp4", FileKind.VIDEO, mtime = now - 5 * day),
      record("recent_photo.jpg", FileKind.IMAGE, mtime = now - 1 * day),
      record("recent_note.m4a", FileKind.AUDIO, mtime = now - 3 * day),
      record("edge.jpg", FileKind.IMAGE, mtime = now - Schema.RECENT_MS),          // exactly 14 days old: still recent
      record("just_outside.jpg", FileKind.IMAGE, mtime = now - Schema.RECENT_MS - 1),
    ))
    val order = store.nextPending(20, FileKind.entries.toSet()).map { it.file.name }
    assertEquals(
      listOf("recent_photo.jpg", "recent_note.m4a", "recent_clip.mp4", "edge.jpg",
        "old_shot.png", "just_outside.jpg", "old_photo.jpg", "old_doc.pdf", "old_clip.mp4", "old_song.mp3"),
      order,
    )
  }

  // ---- a wrong phone clock (ClockSense): the 14-day "recent first" window counts back from what the files prove ----------------

  @Test fun aClockBehindItsFilesCountsRecentBackFromTheNewestFiles() {
    store.clock = { now }   // the phone says day 100; its newest files are dated days 148-150
    store.upsertFromCrawl(listOf(
      record("old_song.m4a", FileKind.AUDIO, mtime = now + 30 * day),      // 18 days before the files' "now": old
      record("old_shot.png", FileKind.SCREENSHOT, mtime = now + 10 * day),
      record("new1.jpg", FileKind.IMAGE, mtime = now + 148 * day),
      record("new2.jpg", FileKind.IMAGE, mtime = now + 149 * day),
      record("new3.jpg", FileKind.IMAGE, mtime = now + 150 * day),
    ))
    val order = store.nextPending(10, FileKind.entries.toSet()).map { it.file.name }
    // On the bare clock every file is "after now" and so recent: newest first, the song ahead of the screenshot. Proven, the two old
    // files keep their kind order (a screenshot reads before a song).
    assertEquals(listOf("new3.jpg", "new2.jpg", "new1.jpg", "old_shot.png", "old_song.m4a"), order)
  }

  @Test fun theEvidenceMayHaveArrivedInAnEarlierBatch() {
    store.clock = { now }
    store.upsertFromCrawl(listOf(
      record("new1.jpg", mtime = now + 148 * day), record("new2.jpg", mtime = now + 149 * day), record("new3.jpg", mtime = now + 150 * day),
    ))
    store.upsertFromCrawl(listOf(
      record("old_song.m4a", FileKind.AUDIO, mtime = now + 30 * day),
      record("old_shot.png", FileKind.SCREENSHOT, mtime = now + 10 * day),
    ))
    val order = store.nextPending(10, FileKind.entries.toSet()).map { it.file.name }
    assertEquals(listOf("new3.jpg", "new2.jpg", "new1.jpg", "old_shot.png", "old_song.m4a"), order)
  }

  @Test fun oneOddlyDatedFileDoesNotMoveTheClock() {
    store.clock = { now }
    val odd = record("odd.jpg", mtime = now + 60 * day)
    store.upsertFromCrawl(listOf(
      odd,
      record("old_song.m4a", FileKind.AUDIO, mtime = now - 30 * day),
      record("old_shot.png", FileKind.SCREENSHOT, mtime = now - 20 * day),
      record("recent.jpg", mtime = now - day),
    ))
    // Re-crawled many times, still one file: it never counts as three.
    repeat(3) { store.upsertFromCrawl(listOf(odd)) }
    val order = store.nextPending(10, FileKind.entries.toSet()).map { it.file.name }
    assertEquals(listOf("odd.jpg", "recent.jpg", "old_shot.png", "old_song.m4a"), order)
  }

  @Test fun newestDatesAnswersTheNewestFirstAndLeavesOutJunk() {
    store.clock = { now }
    store.upsertFromCrawl(listOf(
      record("a.jpg", mtime = now - 5 * day), record("b.jpg", mtime = now + 2 * day), record("c.jpg", mtime = now - day),
      record("junk.jpg", mtime = now + 20_000 * day), record("skipped.jpg", mtime = now + day, skip = "hidden"),
    ))
    assertEquals(listOf(now + 2 * day, now - day), store.newestDates(2, now + 365 * day))
    assertEquals(listOf(now + 2 * day, now - day, now - 5 * day), store.newestDates(10, now + 365 * day))
  }

  @Test fun theWordsPassReadsOldDocumentsBeforeOldPhotosAndBigDocumentsLast() {
    // An old document's text costs ~0.05 s, a photo's OCR ~0.7 s: documents must not wait behind every old photo (Schema.WORDS_ORDER).
    store.clock = { now }
    val big = Schema.WORDS_BIG_DOC_BYTES + 1
    store.upsertFromCrawl(listOf(
      record("old_photo.jpg", FileKind.IMAGE, mtime = now - 40 * day),
      record("old_shot.png", FileKind.SCREENSHOT, mtime = now - 50 * day),
      record("guidelines.pdf", FileKind.PDF, mtime = now - 30 * day, size = big),
      record("old_minutes.docx", FileKind.DOCUMENT, mtime = now - 90 * day),
      record("old_bill.pdf", FileKind.PDF, mtime = now - 60 * day),
      record("recent_photo.jpg", FileKind.IMAGE, mtime = now - day),
    ))
    val names = store.nextWithoutWords(10, com.craneai.findr.engine.FindrEngine.WORDS_PASS_KINDS).map { it.file.name }
    assertEquals(listOf("recent_photo.jpg", "old_bill.pdf", "old_minutes.docx", "old_shot.png", "old_photo.jpg", "guidelines.pdf"), names)
  }

  @Test fun theBatterySafeWordsPassNeverTakesVideoOrAudioHoweverRecent() {
    // The hourly light crawl runs the words pass on battery with these kinds; a recent video or voice note must not jump into it.
    assertEquals(setOf(FileKind.DOCUMENT, FileKind.PDF, FileKind.SCREENSHOT, FileKind.IMAGE), com.craneai.findr.engine.FindrEngine.WORDS_PASS_KINDS)
    store.clock = { now }
    store.upsertFromCrawl(listOf(
      record("clip.mp4", FileKind.VIDEO, mtime = now - 1000),
      record("note.m4a", FileKind.AUDIO, mtime = now - 500),
      record("old_photo.jpg", FileKind.IMAGE, mtime = now - 60 * day),
    ))
    val names = store.nextWithoutWords(10, com.craneai.findr.engine.FindrEngine.WORDS_PASS_KINDS).map { it.file.name }
    assertEquals(listOf("old_photo.jpg"), names)
  }

  @Test fun onBatteryTheNewFileSetExcludesVideoAndAudioHoweverRecent() {
    // D12 (IndexPolicy): on battery only NEW_FILE_KINDS get full quality at once. A recent voice note sorts first in the queue,
    // but the kind filter keeps it (and a video) for the charger.
    assertTrue(FileKind.AUDIO !in IndexPolicy.NEW_FILE_KINDS)
    assertTrue(FileKind.VIDEO !in IndexPolicy.NEW_FILE_KINDS)
    store.clock = { now }
    store.upsertFromCrawl(listOf(
      record("old_photo.jpg", FileKind.IMAGE, mtime = now - 60 * day, id = 1),
      record("note.m4a", FileKind.AUDIO, mtime = now - 500, id = 2),
      record("clip.mp4", FileKind.VIDEO, mtime = now - 1000, id = 3),
      record("new_doc.pdf", FileKind.PDF, mtime = now - 2000, id = 4),
    ))
    // Everything here arrived after the backlog mark (id > 0), so only the kind filter is under test.
    val fresh = store.nextNewPending(10, IndexPolicy.NEW_FILE_KINDS, 0L).map { it.file.name }
    assertEquals(emptyList<String>(), fresh)   // afterId <= 0 means "no backlog mark yet": nothing is "new"
    val afterMark = store.nextNewPending(10, IndexPolicy.NEW_FILE_KINDS, 1L).map { it.file.name }
    assertEquals(listOf("new_doc.pdf"), afterMark)   // ids 2 (audio) and 3 (video) are past the mark too, and still left out
    // On the charger the bulk takes everything, recent first: the voice note and the video run before the old photo.
    assertEquals(listOf("note.m4a", "clip.mp4", "new_doc.pdf", "old_photo.jpg"), store.nextPending(10, FileKind.entries.toSet()).map { it.file.name })
  }

  @Test fun onBatteryShortNewVideosAndRecordingsAreTakenAndLongOnesWaitForTheCharger() {
    store.clock = { now }
    val mb = 1_048_576L
    store.upsertFromCrawl(listOf(
      record("old_photo.jpg", FileKind.IMAGE, mtime = now - 60 * day, id = 1),
      record("short.mp4", FileKind.VIDEO, mtime = now - 1000, size = 20 * mb, id = 2),
      record("long.mp4", FileKind.VIDEO, mtime = now - 900, size = 900 * mb, id = 3),
      record("note.m4a", FileKind.AUDIO, mtime = now - 800, size = 3 * mb, id = 4),
      record("lecture.m4a", FileKind.AUDIO, mtime = now - 700, size = 80 * mb, id = 5),
    ))
    val kinds = IndexPolicy.newFileKinds(com.craneai.findr.engine.Tuning.DEFAULT.newMediaOnBattery)
    val fresh = store.nextNewPending(10, kinds, 1L, maxVideoBytes = 40 * mb, maxAudioBytes = 10 * mb).map { it.file.name }
    assertEquals(listOf("note.m4a", "short.mp4"), fresh)   // the long ones are not on battery's list, and do not block it
    // With no caps given nothing is filtered (the callers that do not ask for media never see any anyway).
    assertEquals(4, store.nextNewPending(10, kinds, 1L).size)
  }

  @Test fun quickLookTakesVideosAlongsidePhotosByShareAndLastWithoutOne() {
    store.clock = { now }
    val photos = (1..20).map { record("p$it.jpg", FileKind.IMAGE, mtime = now - it * 1000L) }
    val videos = (1..5).map { record("v$it.mp4", FileKind.VIDEO, mtime = now - 500_000L - it * 1000L) }
    store.upsertFromCrawl(photos + videos)
    val all = FileKind.entries.toSet()
    val mixed = store.nextForQuickLook(10, all, 0.2).map { it.file.name }
    assertEquals(2, mixed.count { it.endsWith(".mp4") })
    assertEquals(listOf("v1.mp4", "v2.mp4"), mixed.filter { it.endsWith(".mp4") })
    assertEquals((1..8).map { "p$it.jpg" }, mixed.filter { it.endsWith(".jpg") })
    // Share 0 is the old queue: every photo first, then the videos.
    assertEquals((1..10).map { "p$it.jpg" }, store.nextForQuickLook(10, all).map { it.file.name })
    assertEquals((1..20).map { "p$it.jpg" } + listOf("v1.mp4", "v2.mp4"), store.nextForQuickLook(22, all).map { it.file.name })
  }

  @Test fun existingRowsAreReorderedOnceAndAUserPriorityIsKept() {
    store.clock = { 1_000_000 }   // the rows are saved with the old kind-only order, as an older build did
    store.upsertFromCrawl(listOf(
      record("old_photo.jpg", FileKind.IMAGE, mtime = 1_000_000 - 60 * day, id = 1),
      record("note.m4a", FileKind.AUDIO, mtime = 1_000_000, id = 2),
      record("picked.pdf", FileKind.PDF, mtime = 1_000_000 - 60 * day, id = 3),
    ))
    store.write { db -> db.run("UPDATE files SET priority = CASE kind WHEN 'IMAGE' THEN 1 WHEN 'AUDIO' THEN 4 ELSE 2 END") }
    store.prioritize(listOf(store.knownMediaStoreIds("external_primary")[3]!!))
    assertEquals(listOf("picked.pdf", "old_photo.jpg", "note.m4a"), store.nextPending(10, FileKind.entries.toSet()).map { it.file.name })
    assertEquals(2, store.reprioritiseOnce())   // the picked file (-1) is left alone
    assertEquals(listOf("picked.pdf", "note.m4a", "old_photo.jpg"), store.nextPending(10, FileKind.entries.toSet()).map { it.file.name })
    assertEquals(0, store.reprioritiseOnce())   // once
  }

  @Test fun nextPendingFollowsPriorityThenNewestFirst() {
    store.upsertFromCrawl(
      listOf(
        record("song.mp3", FileKind.AUDIO, mtime = 9_000),
        record("clip.mp4", FileKind.VIDEO, mtime = 9_000),
        record("old.pdf", FileKind.PDF, mtime = 1_000),
        record("new.docx", FileKind.DOCUMENT, mtime = 5_000),
        record("photo_old.jpg", FileKind.IMAGE, mtime = 1_000),
        record("photo_new.jpg", FileKind.IMAGE, mtime = 8_000),
        record("shot.png", FileKind.SCREENSHOT, mtime = 100),
        record("misc.zip", FileKind.OTHER, mtime = 100),
      ),
    )
    val order = store.nextPending(20, FileKind.entries.toSet()).map { it.file.name }
    assertEquals(
      listOf("shot.png", "photo_new.jpg", "photo_old.jpg", "new.docx", "old.pdf", "clip.mp4", "song.mp3", "misc.zip"),
      order,
    )
    assertEquals(listOf("shot.png"), store.nextPending(5, setOf(FileKind.SCREENSHOT)).map { it.file.name })
    assertEquals(2, store.nextPending(2, FileKind.entries.toSet()).size)
    // Understood files leave the queue; partial ones only show when asked for.
    val shot = store.nextPending(1, setOf(FileKind.SCREENSHOT)).single().file.id
    store.setState(shot, FileState.PARTIAL)
    assertFalse(store.nextPending(20, FileKind.entries.toSet()).any { it.file.id == shot })
    assertTrue(store.nextPending(20, FileKind.entries.toSet(), includePartial = true).any { it.file.id == shot })
    assertEquals(listOf(shot), store.nextPartial(5, FileKind.entries.toSet()).map { it.file.id })
  }

  @Test fun prioritizeMovesFilesToTheFront() {
    store.upsertFromCrawl(listOf(record("shot.png", FileKind.SCREENSHOT, id = 1), record("song.mp3", FileKind.AUDIO, id = 2)))
    val song = store.knownMediaStoreIds("external_primary")[2]!!
    store.prioritize(listOf(song))
    assertEquals("song.mp3", store.nextPending(1, FileKind.entries.toSet()).single().file.name)
  }

  @Test fun deleteCascadesToSegmentsAndWords() {
    store.upsertFromCrawl(listOf(record("keep.pdf", FileKind.PDF, id = 1), record("drop.pdf", FileKind.PDF, id = 2)))
    val ids = store.knownMediaStoreIds("external_primary")
    for ((_, fid) in ids) store.saveSegments(fid, listOf(Segment(SegmentKind.PAGE, Modality.TEXT, 1, text = "shared words", vector = vec(fid.toInt()))), FileState.UNDERSTOOD)
    assertEquals(2, ftsHits("shared").size)
    assertEquals(1, store.markDeleted(listOf(ids[2]!!)))
    assertEquals(1, ftsHits("shared").size)
    assertEquals(0, ftsHits("drop").size)
    assertEquals(1, store.stats().vectors)
    assertEquals(0L, store.read { it.long("SELECT COUNT(*) FROM segments WHERE file = ${ids[2]}") })
  }

  @Test fun deleteMissingDiffsAgainstMediaStore() {
    store.upsertFromCrawl((1L..5L).map { record("f$it.jpg", id = it) })
    val gone = store.deleteMissing("external_primary", setOf(1L, 2L, 5L))
    assertEquals(2, gone.size)
    assertEquals(setOf(1L, 2L, 5L), store.knownMediaStoreIds("external_primary").keys)
  }

  @Test fun changingTheModelRequeuesEverythingButKeepsNames() {
    assertFalse(store.ensureModelFingerprint("model-a"))
    store.upsertFromCrawl(listOf(record("a.jpg", id = 1)))
    val id = store.knownMediaStoreIds("external_primary")[1]!!
    store.saveSegments(id, listOf(Segment(SegmentKind.IMAGE, Modality.IMAGE, vector = vec(1))), FileState.UNDERSTOOD)
    assertFalse(store.ensureModelFingerprint("model-a"))
    assertEquals(1, store.stats().vectors)
    assertTrue(store.ensureModelFingerprint("model-b"))
    assertEquals(0, store.stats().vectors)
    assertEquals(FileState.SEARCHABLE, store.stateOf(id)!!.first)
    assertEquals(1, ftsHits("a").size)
    // A crawl that sees the unchanged file does not treat it as changed.
    assertEquals(1, store.upsertFromCrawl(listOf(record("a.jpg", id = 1))).unchanged)
  }

  @Test fun aFreshInstallDoesNotQueueEverythingAgainOnTheSecondCrawl() {
    // The worker crawls first and names the model after: on a new install the crawl stamps files with no model name yet.
    val records = listOf(record("a.jpg", id = 1), record("b.jpg", id = 2), record("c.pdf", FileKind.PDF, id = 3, path = "Download/"))
    assertEquals(3, store.upsertFromCrawl(records).added)
    val ids = store.knownMediaStoreIds("external_primary")
    store.saveSegments(ids[1]!!, listOf(Segment(SegmentKind.IMAGE, Modality.IMAGE, vector = vec(1))), FileState.UNDERSTOOD)
    assertFalse(store.ensureModelFingerprint("stock:vt70"))   // first run: nothing to re-read
    val second = store.upsertFromCrawl(records)
    assertEquals(0, second.changed)
    assertEquals(3, second.unchanged)
    assertEquals(FileState.UNDERSTOOD, store.stateOf(ids[1]!!)!!.first)
    assertEquals(1, store.stats().vectors)
    // And a real model change afterwards still re-queues, then settles the same way.
    assertTrue(store.ensureModelFingerprint("stock:vt280"))
    assertEquals(3, store.upsertFromCrawl(records).unchanged)
  }

  @Test fun aTextModelChangeRedoesTextAndKeepsImageVectors() {
    assertFalse(store.ensureModelFingerprint("stock:vt70"))
    assertEquals(0 to 0, store.ensureTextFingerprint("stock"))   // first run on an index: recorded, nothing redone
    store.upsertFromCrawl(listOf(record("cat.jpg", id = 1), record("meme.mp4", FileKind.VIDEO, id = 2),
      record("notes.pdf", FileKind.PDF, id = 3, path = "Download/")))
    val (photo, video, doc) = listOf(1L, 2L, 3L).map { store.knownMediaStoreIds("external_primary")[it]!! }
    store.saveSegments(photo, listOf(Segment(SegmentKind.IMAGE, Modality.IMAGE, vector = vec(1))), FileState.UNDERSTOOD)
    val vids = store.saveSegments(video, listOf(
      Segment(SegmentKind.FRAME, Modality.IMAGE, 0, 5_000, vector = vec(2)),
      Segment(SegmentKind.TRANSCRIPT, Modality.TEXT, 0, 5_000, text = "keep your secrets", vector = vec(3)),
      Segment(SegmentKind.SOUND, Modality.SOUND, 0, 5_000, text = "Sounds: music, laughter", vector = vec(4)),
    ), FileState.UNDERSTOOD)
    store.saveSegments(doc, listOf(Segment(SegmentKind.CHUNK, Modality.TEXT, 1, 1, text = "quarterly budget", vector = vec(5))),
      FileState.UNDERSTOOD)
    assertEquals(0 to 0, store.ensureTextFingerprint("stock"))
    assertTrue(store.textToRedo(10).isEmpty())

    // The language-tuned text model arrives; the image space (model fingerprint) is unchanged.
    assertFalse(store.ensureModelFingerprint("stock:vt70"))
    assertEquals(1 to 2, store.ensureTextFingerprint("tuned"))
    assertEquals(FileState.SEARCHABLE, store.stateOf(doc)!!.first)    // documents are read again
    assertEquals(FileState.UNDERSTOOD, store.stateOf(photo)!!.first)  // photos and videos are not
    assertEquals(FileState.UNDERSTOOD, store.stateOf(video)!!.first)
    assertEquals(5, store.stats().vectors)                            // nothing searchable is lost meanwhile

    // A segment written after the change is already the new model's: not in the redo.
    store.saveSegments(video, listOf(Segment(SegmentKind.TRANSCRIPT, Modality.TEXT, 5_000, 9_000, text = "later", vector = vec(9))),
      FileState.UNDERSTOOD, replace = false)
    val first = store.textToRedo(1)
    assertEquals(listOf(TextToRedo(vids[1], video, "keep your secrets")), first)
    store.replaceTextVectors(listOf(vids[1] to vec(7)), through = vids[1])
    val second = store.textToRedo(10)
    assertEquals(listOf(TextToRedo(vids[2], video, "Sounds: music, laughter")), second)
    store.replaceTextVectors(listOf(vids[2] to vec(8)), through = vids[2])
    assertTrue(store.textToRedo(10).isEmpty())
    assertNull(store.meta(Schema.Meta.TEXT_REDO))

    val stored = HashMap<Long, FloatArray>()
    store.forEachVectorOfFile(video) { id, _, _, _, _, blob -> stored[id] = Fp16.blobToFloats(blob) }
    assertEquals(1f, stored[vids[1]]!![7], 0f)   // re-embedded
    assertEquals(1f, stored[vids[2]]!![8], 0f)
    assertEquals(1f, stored[vids[0]]!![2], 0f)   // the frame kept its vector
    assertEquals(0 to 0, store.ensureTextFingerprint("tuned"))
  }

  @Test fun aFullModelChangeLeavesNoTextRedo() {
    store.ensureModelFingerprint("a:vt70")
    store.ensureTextFingerprint("a")
    store.upsertFromCrawl(listOf(record("clip.m4a", FileKind.AUDIO, id = 1)))
    val id = store.knownMediaStoreIds("external_primary")[1]!!
    store.saveSegments(id, listOf(Segment(SegmentKind.TRANSCRIPT, Modality.TEXT, 0, 1_000, text = "hello", vector = vec(1))),
      FileState.UNDERSTOOD)
    assertTrue(store.ensureModelFingerprint("b:vt70"))   // everything is understood again anyway
    assertEquals(0 to 0, store.ensureTextFingerprint("b"))
    assertTrue(store.textToRedo(10).isEmpty())
  }

  /** An index built with model "a": a photo and a recording (both understood), a document, and a photo read in part. */
  private fun upgradeFixture(): List<Long> {
    store.ensureModelFingerprint("a:vt70")
    store.ensureTextFingerprint("a")
    store.upsertFromCrawl(listOf(
      record("new.jpg", id = 1, mtime = 3_000), record("old.jpg", id = 2, mtime = 1_000),
      record("voice.m4a", FileKind.AUDIO, id = 3, mtime = 2_000), record("notes.pdf", FileKind.PDF, id = 4, path = "Download/"),
    ))
    val ids = (1L..4L).map { store.knownMediaStoreIds("external_primary")[it]!! }
    store.saveSegments(ids[0], listOf(Segment(SegmentKind.IMAGE, Modality.IMAGE, vector = vec(1)), Segment(SegmentKind.OCR, text = "invoice total")),
      FileState.UNDERSTOOD)
    store.saveSegments(ids[1], listOf(Segment(SegmentKind.IMAGE, Modality.IMAGE, vector = vec(2))), FileState.UNDERSTOOD)
    store.saveSegments(ids[2], listOf(Segment(SegmentKind.AUDIO, Modality.AUDIO, 0, 1_000, vector = vec(3))), FileState.UNDERSTOOD)
    store.saveSegments(ids[3], listOf(Segment(SegmentKind.CHUNK, Modality.TEXT, 1, 1, text = "budget", vector = vec(4))), FileState.UNDERSTOOD)
    return ids
  }

  private fun lates(): Map<Long, Int> {
    val out = HashMap<Long, Int>()
    store.forEachVector { _, file, _, _, late, _ -> out[file] = late }
    return out
  }

  private val photosAndAudio = setOf(FileKind.IMAGE, FileKind.SCREENSHOT, FileKind.AUDIO)

  @Test fun aModelUpgradeKeepsImageAndAudioVectorsAsStaleAndQueuesOnlyTheirFiles() {
    val (newPhoto, oldPhoto, voice, doc) = upgradeFixture()
    assertEquals(4, store.stats().vectors)
    assertTrue(store.ensureModelFingerprint("b:vt70", keepStale = true))
    assertEquals(4, store.stats().vectors)                                       // nothing is deleted: no gap in search
    assertEquals(mapOf(newPhoto to 2, oldPhoto to 2, voice to 2, doc to 0), lates())
    assertEquals(FileState.SEARCHABLE to IndexStore.WORDS_DONE, store.stateOf(newPhoto))   // queued, words kept
    assertEquals(FileState.UNDERSTOOD, store.stateOf(doc)!!.first)               // text is the text model's business
    assertEquals(1, ftsHits("invoice").size)                                     // the photo's OCR words still find it
    val progress = store.reembedProgress()
    assertEquals(mapOf(FileKind.IMAGE to 2, FileKind.AUDIO to 1), progress.waiting)
    assertEquals(0 to 3, progress.over(photosAndAudio))
    // The queue order is the normal one (here all old: photos by kind, newest first, then the recording).
    assertEquals(listOf(newPhoto, oldPhoto, voice), store.nextPending(10, photosAndAudio).map { it.file.id })
    // A crawl that sees the unchanged files does not re-queue them again.
    assertEquals(4, store.upsertFromCrawl(listOf(
      record("new.jpg", id = 1, mtime = 3_000), record("old.jpg", id = 2, mtime = 1_000),
      record("voice.m4a", FileKind.AUDIO, id = 3, mtime = 2_000), record("notes.pdf", FileKind.PDF, id = 4, path = "Download/"),
    )).unchanged)
  }

  @Test fun readingAFileAgainReplacesItsStaleVectorsInOneStep() {
    val (newPhoto, oldPhoto, voice, _) = upgradeFixture()
    store.ensureModelFingerprint("b:vt70", keepStale = true)
    store.saveSegments(newPhoto, listOf(Segment(SegmentKind.IMAGE, Modality.IMAGE, vector = vec(11)), Segment(SegmentKind.OCR, text = "invoice total")),
      FileState.UNDERSTOOD)
    assertEquals(4, store.stats().vectors)                                       // one vector out, one in
    assertEquals(0, lates()[newPhoto])
    assertEquals(2, lates()[oldPhoto])
    var seen = FloatArray(0)
    store.forEachVectorOfFile(newPhoto) { _, _, _, _, _, blob -> seen = Fp16.blobToFloats(blob) }
    assertEquals(1f, seen[11], 0f)
    assertEquals(1f, seen.sum(), 0f)                                       // only the new vector, no leftover
    assertEquals(1 to 3, store.reembedProgress().over(photosAndAudio))
    store.saveSegments(voice, listOf(Segment(SegmentKind.AUDIO, Modality.AUDIO, 0, 1_000, vector = vec(13))), FileState.UNDERSTOOD)
    store.saveSegments(oldPhoto, listOf(Segment(SegmentKind.IMAGE, Modality.IMAGE, vector = vec(12))), FileState.UNDERSTOOD)
    assertNull(store.reembedProgress().over(photosAndAudio))                     // done: the meter goes away
    assertTrue(lates().values.all { it == 0 })
  }

  @Test fun aReEmbedInterruptedMidwayResumesWhereItStopped() {
    val (newPhoto, oldPhoto, voice, doc) = upgradeFixture()
    store.ensureModelFingerprint("b:vt70", keepStale = true)
    store.saveSegments(newPhoto, listOf(Segment(SegmentKind.IMAGE, Modality.IMAGE, vector = vec(11))), FileState.UNDERSTOOD)
    store.close()                                                                // the process is killed
    store = IndexStore.open(dir, log = { logs += it })
    assertFalse(store.ensureModelFingerprint("b:vt70", keepStale = true))        // same model: nothing marked again
    assertEquals(1 to 3, store.reembedProgress().over(photosAndAudio))
    assertEquals(listOf(oldPhoto, voice), store.nextPending(10, photosAndAudio).map { it.file.id })
    assertEquals(mapOf(newPhoto to 0, oldPhoto to 2, voice to 2, doc to 0), lates())
  }

  @Test fun aSecondUpgradeBeforeTheFirstFinishesMarksTheNewVectorsStaleToo() {
    val (newPhoto, oldPhoto, _, _) = upgradeFixture()
    store.ensureModelFingerprint("b:vt70", keepStale = true)
    store.saveSegments(newPhoto, listOf(Segment(SegmentKind.IMAGE, Modality.IMAGE, vector = vec(11))), FileState.UNDERSTOOD)
    assertTrue(store.ensureModelFingerprint("c:vt70", keepStale = true))
    assertEquals(2, lates()[newPhoto])
    assertEquals(2, lates()[oldPhoto])
    assertEquals(0 to 3, store.reembedProgress().over(photosAndAudio))
  }

  @Test fun filesOfAKindSwitchedOffAreLeftOutOfTheReEmbedMeter() {
    upgradeFixture()
    store.ensureModelFingerprint("b:vt70", keepStale = true)
    assertEquals(0 to 2, store.reembedProgress().over(setOf(FileKind.IMAGE)))    // the recording is not being read
    assertNull(store.reembedProgress().over(setOf(FileKind.VIDEO)))
  }

  @Test fun statsCountByKindAndState() {
    store.upsertFromCrawl(listOf(record("a.jpg", id = 1), record("b.jpg", id = 2), record("c.pdf", FileKind.PDF, id = 3)))
    store.setState(store.knownMediaStoreIds("external_primary")[1]!!, FileState.UNDERSTOOD)
    val stats = store.stats()
    assertEquals(1, stats.count(FileKind.IMAGE, FileState.UNDERSTOOD))
    assertEquals(1, stats.count(FileKind.IMAGE, FileState.SEARCHABLE))
    assertEquals(3, stats.filesKnown)
    assertEquals(1, stats.filesUnderstood)
    assertEquals(2, stats.waiting(setOf(FileKind.IMAGE, FileKind.PDF)))
    assertTrue(stats.bytesOnDisk > 0)
  }

  @Test fun recentIsNewestFirstAndFilteredByKind() {
    store.upsertFromCrawl(listOf(record("old.jpg", mtime = 1), record("new.jpg", mtime = 9), record("doc.pdf", FileKind.PDF, mtime = 99)))
    assertEquals(listOf("new.jpg", "old.jpg"), store.recent(setOf(FileKind.IMAGE), 10).map { it.name })
    assertEquals(1, store.recent(FileKind.entries.toSet(), 1).size)
  }

  @Test fun recentLeavesOutOtherFilesButNameSearchStillFindsThem() {
    store.upsertFromCrawl(listOf(
      record("trawl-1.0.0-test.apk", FileKind.OTHER, mtime = 99), record("bundle.zip", FileKind.OTHER, mtime = 98),
      record("doc.pdf", FileKind.PDF, mtime = 5), record("pic.jpg", mtime = 4),
    ))
    assertEquals(listOf("doc.pdf", "pic.jpg"), store.recent(FileKind.entries.toSet(), 10).map { it.name })
    assertEquals(emptyList<String>(), store.recent(setOf(FileKind.OTHER), 10).map { it.name })
    assertEquals(0, store.recentHiddenCount(setOf(FileKind.OTHER)))
    assertEquals(1, ftsHits("trawl").size)   // an APK stays findable by name
  }

  @Test fun reclassifyingMarksAnAlreadyIndexedChatBackupSkipped() {
    val before = record("msgstore.db.crypt15", FileKind.OTHER, mtime = 7)
    store.upsertFromCrawl(listOf(before))
    assertEquals(1, ftsHits("msgstore").size)
    store.upsertFromCrawl(listOf(before.copy(skipReason = Rules.appDataReason(before.relativePath, before.name))))
    assertEquals(0, ftsHits("msgstore").size)
    assertTrue(store.recent(FileKind.entries.toSet(), 10).none { it.name.startsWith("msgstore") })
  }

  @Test fun corruptFileIsDeletedAndRebuilt() {
    store.upsertFromCrawl(listOf(record("a.jpg")))
    store.close()
    File(dir, IndexStore.FILE_NAME).writeBytes(ByteArray(8192) { (it * 31).toByte() })
    File(dir, IndexStore.FILE_NAME + "-wal").delete()
    File(dir, IndexStore.FILE_NAME + ".corrupt-1700000000000").writeBytes(ByteArray(10))   // left by an older build
    logs.clear()
    store = IndexStore.open(dir, log = { logs += it })
    assertTrue(logs.any { it.contains("deleting it and starting fresh") })
    assertEquals(0, store.stats().filesKnown)
    // No copy of the user's text is kept aside (older builds kept one forever).
    assertNull(dir.listFiles()!!.firstOrNull { it.name.contains(".corrupt-") })
    // And it is usable.
    assertEquals(1, store.upsertFromCrawl(listOf(record("b.jpg"))).added)
  }

  // ---- deleted means gone from disk (the research notes, "Deleted files") ----------------------------

  /** Every byte Findr's index keeps on disk: the database, its write-ahead log and shared memory. */
  private fun bytesOnDisk(): String = listOf("", "-wal", "-shm").joinToString("") { suffix ->
    File(dir, IndexStore.FILE_NAME + suffix).takeIf { it.exists() }?.readBytes()?.toString(Charsets.ISO_8859_1) ?: ""
  }

  private fun fileWithSecrets(msId: Long, name: String, words: String): Long {
    store.upsertFromCrawl(listOf(record(name, FileKind.AUDIO, id = msId, path = "WhatsApp/Media/WhatsApp Voice Notes/")))
    val id = store.knownMediaStoreIds("external_primary")[msId]!!
    store.saveSegments(id, listOf(Segment(SegmentKind.TRANSCRIPT, Modality.TEXT, 0, 9_000, text = words, vector = vec(3))), FileState.UNDERSTOOD)
    return id
  }

  @Test fun aDeletedFilesNameAndWordsAreGoneFromDisk() {
    // Neighbours, so the secret shares pages with live rows (the case plain deletes leave readable).
    for (i in 1L..40L) fileWithSecrets(100 + i, "PTT-2026$i.opus", "the weekly market list number $i tomatoes onions")
    val secret = fileWithSecrets(7, "PTT-quokkazanzibar.opus", "my mobile money pin is quokkapin4471 send it to nobody")
    assertTrue("sanity: the words are on disk before the delete", bytesOnDisk().contains("quokkapin4471"))
    store.deleteMissing("external_primary", (101L..140L).toSet())   // the phone no longer has file 7
    assertNull(store.fileById(secret))
    val disk = bytesOnDisk()
    assertFalse("transcript words left on disk", disk.contains("quokkapin4471"))
    assertFalse("file name left on disk", disk.contains("quokkazanzibar"))
    // The neighbours are still searchable: secure delete removed only the deleted file.
    assertEquals(40, ftsHits("tomatoes").size)
  }

  @Test fun aChangedFilesOldWordsAreGoneFromDiskToo() {
    val id = fileWithSecrets(7, "note.m4a", "old words quokkaold5512")
    store.saveSegments(id, listOf(Segment(SegmentKind.TRANSCRIPT, Modality.TEXT, 0, 9_000, text = "new words only", vector = vec(4))), FileState.UNDERSTOOD)
    store.close()   // a checkpoint, as happens in use
    store = IndexStore.open(dir, log = { logs += it })
    assertFalse(bytesOnDisk().contains("quokkaold5512"))
    assertEquals(1, ftsHits("new").size)
  }

  /** The control: SQLite's defaults keep a deleted row's words on disk, which is why the index turns secure delete on. */
  @Test fun withoutSecureDeleteSqliteKeepsDeletedWords() {
    val plain = File(dir, "plain.db")
    val db = Db(androidx.sqlite.driver.bundled.BundledSQLiteDriver().open(plain.path))
    db.exec("CREATE VIRTUAL TABLE fts USING fts5(name, body)")
    for (i in 1..40) db.run("INSERT INTO fts(rowid, name, body) VALUES(?, ?, ?)", i.toLong(), "n$i", "market list $i")
    db.run("INSERT INTO fts(rowid, name, body) VALUES(99, 'x', 'quokkapin4471')")
    db.run("DELETE FROM fts WHERE rowid = 99")
    db.close()
    assertTrue(plain.readBytes().toString(Charsets.ISO_8859_1).contains("quokkapin4471"))
  }

  @Test fun readersRunWhileTheWriterIsBusy() {
    store.upsertFromCrawl((1L..50L).map { record("f$it.jpg", id = it) })
    val readerDone = java.util.concurrent.CountDownLatch(1)
    val t = Thread {
      repeat(50) { store.recent(FileKind.entries.toSet(), 10) }
      readerDone.countDown()
    }
    t.start()
    repeat(20) { i -> store.upsertFromCrawl(listOf(record("g$i.jpg", id = 100L + i))) }
    assertTrue(readerDone.await(10, java.util.concurrent.TimeUnit.SECONDS))
  }

  @Test fun backlogMarkIsSetOnceAndNewFilesAreThoseAfterIt() {
    assertEquals(0L, store.backlogMark())                       // nothing known yet: nothing stored
    store.upsertFromCrawl(listOf(record("a.jpg"), record("b.jpg", FileKind.SCREENSHOT)))
    val mark = store.backlogMark()
    assertTrue(mark > 0)
    store.upsertFromCrawl(listOf(record("c.jpg", mtime = 5_000), record("d.mp4", FileKind.VIDEO)))
    assertEquals(mark, store.backlogMark())                     // fixed once set
    val kinds = setOf(FileKind.IMAGE, FileKind.SCREENSHOT)
    val fresh = store.nextNewPending(10, kinds, mark)
    assertEquals(listOf("c.jpg"), fresh.map { it.file.name })   // not the backlog, not the video
    assertTrue(store.nextNewPending(10, kinds, 0).isEmpty())    // no mark, no new files
  }

  @Test fun storageTotalsCountBytesAndMinutesPerKindWithoutSkippedFiles() {
    store.upsertFromCrawl(listOf(
      record("a.jpg", size = 1_000), record("b.jpg", size = 2_000),
      record("c.jpg", size = 9_999, skip = "too small"),
      CrawlRecord("external_primary", 90, "content://media/external/file/90", "Movies/", "v.mp4", FileKind.VIDEO, null, 5_000, 1_000, durationMs = 120_000),
    ))
    val totals = store.storageTotals().associateBy { it.kind }
    assertEquals(2, totals.getValue(FileKind.IMAGE).files)
    assertEquals(3_000L, totals.getValue(FileKind.IMAGE).bytes)
    assertEquals(120_000L, totals.getValue(FileKind.VIDEO).durationMs)
  }

  @Test fun trimWalShrinksTheLogOnlyWhenItHasGrown() {
    store.upsertFromCrawl((1L..300L).map { record("f$it.jpg", id = it) })
    val before = store.walBytes()
    assertEquals(0L, store.trimWal(aboveBytes = before + 1))        // below the limit: left alone
    assertEquals(before, store.walBytes())
    if (before > 0) {
      assertTrue(store.trimWal() > 0)
      assertEquals(0L, store.walBytes())
    }
    assertEquals(300, store.stats().filesKnown)                       // nothing lost
  }

  // ---- kill-loop breaker ----------------------------------------------------------------------------------------

  private val both = setOf(FileKind.VIDEO, FileKind.IMAGE)

  private fun twoFiles(): Pair<Long, Long> {
    store.upsertFromCrawl(listOf(record("big.mp4", FileKind.VIDEO), record("small.jpg")))
    val pending = store.nextPending(10, both).associate { it.file.name to it.file.id }
    return pending["big.mp4"]!! to pending["small.jpg"]!!
  }

  /** A process death: the store is closed without the worker clearing its mark, then opened again by the restarted worker. */
  private fun processDies() {
    store.close()
    store = IndexStore.open(dir, log = { logs += it })
  }

  @Test fun aFileThatTookTheProcessDownTwiceIsFailedAndTheQueueMovesOn() {
    val (big, small) = twoFiles()
    store.markInflight(big); processDies()
    assertNull(store.recoverInflight())                                   // first death: another chance
    assertTrue(store.nextPending(10, both).any { it.file.id == big })
    store.markInflight(big); processDies()                                // the same file again
    val r = store.recoverInflight()!!                                     // second death: given up on
    assertEquals(big, r.fileId); assertFalse(r.skippedWindow)
    assertEquals(FileState.FAILED, store.stateOf(big)!!.first)
    assertEquals(IndexStore.TOO_LARGE_DETAIL, store.stateOf(big)!!.second)
    assertEquals(listOf(small), store.nextPending(10, both).map { it.file.id })
    assertEquals(1, store.stats().tooLarge)
    assertNull(store.recoverInflight())                                   // the mark is gone: no third count
  }

  @Test fun aFileTheWorkerFinishedWithIsNeverCounted() {
    val (big, _) = twoFiles()
    repeat(5) { store.markInflight(big); store.clearInflight(); processDies(); assertNull(store.recoverInflight()) }
    assertTrue(store.nextPending(10, both).any { it.file.id == big })
    assertEquals(0, store.stats().tooLarge)
  }

  @Test fun deathsOnDifferentFilesAreNotAddedTogether() {
    val (big, small) = twoFiles()
    store.markInflight(big); processDies(); assertNull(store.recoverInflight())
    store.markInflight(small); processDies(); assertNull(store.recoverInflight())   // another file: its own count
    assertEquals(0, store.stats().tooLarge)
    store.markInflight(small); processDies()
    assertEquals(small, store.recoverInflight()!!.fileId)
  }

  @Test fun theUpdateRetryGivesATooLargeFileOneMoreChance() {
    val (big, _) = twoFiles()
    store.markInflight(big); processDies(); store.recoverInflight()
    store.markInflight(big); processDies(); store.recoverInflight()
    assertEquals(1, store.retryFailed())                                  // a new build may have fixed it
    assertEquals(0, store.stats().tooLarge)
    assertNull(store.recoverInflight())
  }

  @Test fun twoDeathsInOneWindowSkipThatWindowNotTheFile() {
    val (big, _) = twoFiles()
    store.saveSegments(big, listOf(Segment(SegmentKind.FRAME, Modality.IMAGE, 0, 60_000, vector = vec(1))), FileState.PARTIAL, "6 frames next=1")
    store.markInflight(big, step = 3, windowed = true); processDies()
    assertNull(store.recoverInflight())
    store.markInflight(big, step = 3, windowed = true); processDies()     // the same window again
    val r = store.recoverInflight()!!
    assertTrue(r.skippedWindow); assertEquals(3, r.step)
    val (state, detail) = store.stateOf(big)!!
    assertEquals(FileState.PARTIAL, state)                                // the file goes on, from the window after the bad one
    assertEquals("next=4 skipped=1", detail)
    assertEquals(0, store.stats().tooLarge)
    assertTrue(store.nextPartial(10, both).any { it.file.id == big })
  }

  @Test fun theCountRestartsForADifferentWindow() {
    val (big, _) = twoFiles()
    store.markInflight(big, step = 1, windowed = true); processDies(); assertNull(store.recoverInflight())
    store.markInflight(big, step = 2, windowed = true); processDies(); assertNull(store.recoverInflight())   // window 2 has its own count
    assertEquals(0, store.stats().tooLarge)
  }

  @Test fun aSecondSkippedWindowFailsTheWholeFile() {
    val (big, _) = twoFiles()
    store.setState(big, FileState.PARTIAL, "next=4 skipped=1")
    store.markInflight(big, step = 5, windowed = true); processDies(); assertNull(store.recoverInflight())
    store.markInflight(big, step = 5, windowed = true); processDies()
    val r = store.recoverInflight()!!
    assertFalse(r.skippedWindow)                                          // it is the file, not one window
    assertEquals(IndexStore.TOO_LARGE_DETAIL, store.stateOf(big)!!.second)
    assertEquals(1, store.stats().tooLarge)
  }
}
