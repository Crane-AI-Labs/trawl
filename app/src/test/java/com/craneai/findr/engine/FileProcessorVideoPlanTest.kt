package com.craneai.findr.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Locks in the video sample plan and the still-frame dedupe threshold that the research notes (2026-10-16)
 * re-confirmed are already at the measured, zero-retrieval-loss optimum (the research notes's dHash sweep and frame-interval
 * sweeps). Neither constant should move without re-running `scripts/eval_video.py` against the gate in that doc: a wider
 * dHash threshold or fewer frames per span both cost retrieval, even though they were re-tried and rejected only days apart.
 */
class FileProcessorVideoPlanTest {
  @Test fun aLongVideoDoesItsSoundtrackAfterItsFrames() {
    // Formats sweep (TECNO, 3.7 GB): a 5 minute video with speech, frames and soundtrack together, was killed by lmkd at ~2 GB, every time.
    assertEquals(true, FileProcessor.shouldOverlapSoundtrack(0))
    assertEquals(true, FileProcessor.shouldOverlapSoundtrack(60))
    assertEquals(true, FileProcessor.shouldOverlapSoundtrack(FileProcessor.OVERLAP_MAX_SECONDS))
    assertEquals(false, FileProcessor.shouldOverlapSoundtrack(FileProcessor.OVERLAP_MAX_SECONDS + 1))
    assertEquals(false, FileProcessor.shouldOverlapSoundtrack(300))
  }

  @Test fun framesForMatchesTheDocumentedPlan() {
    // A frame every 5 s for the first minute, every 10 s after, 1..24 (FileProcessor.framesFor's own comment).
    assertEquals(1, FileProcessor.framesFor(1))
    assertEquals(1, FileProcessor.framesFor(5))
    assertEquals(2, FileProcessor.framesFor(6))
    assertEquals(2, FileProcessor.framesFor(10))
    assertEquals(3, FileProcessor.framesFor(12))   // the 12.5 s eval memes: seconds truncates to 12
    assertEquals(6, FileProcessor.framesFor(30))
    assertEquals(12, FileProcessor.framesFor(59))
    assertEquals(12, FileProcessor.framesFor(60))  // the last 5 s span
    assertEquals(13, FileProcessor.framesFor(61))  // the first 10 s span after the first minute
    assertEquals(18, FileProcessor.framesFor(120)) // measured in-app: 18 frames sampled (the research notes)
    assertEquals(24, FileProcessor.framesFor(179))
    assertEquals(24, FileProcessor.framesFor(180)) // the cap starts here
    assertEquals(24, FileProcessor.framesFor(240)) // measured in-app: 24 frames sampled, capped (the research notes)
    assertEquals(24, FileProcessor.framesFor(3_600))
  }

  @Test fun stillFrameThresholdIsTheMeasuredZeroLossValue() {
    // the research notes's dHash sweep: <=6 bits is retrieval-identical on the video eval; <=8 and up already cost queries.
    // A regression here (someone "simplifying" the threshold) silently reopens a loss this project already rejected.
    assertEquals(6, FileProcessor.SAME_FRAME_BITS)
  }

  @Test fun longVideosAndRecordingsAreReadAMinuteAtATime() {
    val min = 60_000L
    assertEquals(1, FileProcessor.windowCount(null))               // unknown length: one step, as before
    assertEquals(1, FileProcessor.windowCount(30_000))
    assertEquals(1, FileProcessor.windowCount(2 * min))            // two minutes or less: one step, as before
    assertEquals(3, FileProcessor.windowCount(2 * min + 1))
    assertEquals(5, FileProcessor.windowCount(5 * min))            // the 5-minute MP4 of the formats sweep
    assertEquals(6, FileProcessor.windowCount(5 * min + 1))
    assertEquals(12, FileProcessor.windowCount(12 * min))          // the 12-minute MP3: every minute is read, not the first 45 s
    assertEquals(30, FileProcessor.windowCount(30 * min))
    assertEquals(45, FileProcessor.windowCount(45 * min))          // the 45-minute recording: all of it
    assertEquals(180, FileProcessor.windowCount(3 * 60 * min))     // a three-hour lecture: all of it
    assertEquals(720, FileProcessor.windowCount(100 * 60 * min))   // only a broken length is cut, at 12 hours
    assertEquals(true, FileProcessor.isWindowed(com.craneai.findr.core.FileKind.VIDEO, 5 * min))
    assertEquals(true, FileProcessor.isWindowed(com.craneai.findr.core.FileKind.AUDIO, 30 * min))
    assertEquals(false, FileProcessor.isWindowed(com.craneai.findr.core.FileKind.IMAGE, 30 * min))
    assertEquals(false, FileProcessor.isWindowed(com.craneai.findr.core.FileKind.VIDEO, min))
  }

  @Test fun theWordsGoOnToTheEndButFramesAndTheSoundVectorStopAtHalfAnHour() {
    assertEquals(true, FileProcessor.isDeepWindow(null))           // a file read in one step
    assertEquals(true, FileProcessor.isDeepWindow(0))
    assertEquals(true, FileProcessor.isDeepWindow(29))             // the 30th minute still gets frames and the vector
    assertEquals(false, FileProcessor.isDeepWindow(30))
    assertEquals(false, FileProcessor.isDeepWindow(179))
    assertEquals(30, FileProcessor.DEEP_WINDOWS)
  }

  @Test fun laterMinutesGetOneFrameByDefaultAndThenAsManyAsTheKnobSays() {
    assertEquals(1, Tuning.DEFAULT.deepFramesPerMin)               // one picture a minute past half an hour (0 would be the old soundtrack-only rule)
    assertEquals(6, FileProcessor.framesInWindow(30, 7_200_000))   // the plain call (knob 0) is the old rule's density
    assertEquals(1, FileProcessor.framesInWindow(30, 7_200_000, 1))
    assertEquals(2, FileProcessor.framesInWindow(90, 7_200_000, 2))
    assertEquals(1, FileProcessor.framesInWindow(100, 6_000_000 + 10_000, 2))   // a 10 s tail of 2 a minute: still one, not two
    assertEquals(9, FileProcessor.framesInWindow(0, 7_200_000, 2))              // the first half hour ignores the knob
    assertEquals(6, FileProcessor.framesInWindow(29, 7_200_000, 1))
    assertEquals(6, FileProcessor.framesInWindow(31, 7_200_000, 99))            // clamped to the first-half-hour density
    assertEquals(1, FileProcessor.framesInWindow(35, 7_200_000, Tuning.DEFAULT.deepFramesPerMin))   // the default: one in the middle of the minute
    assertEquals(1, FileProcessor.framesInWindow(119, 7_200_000 - 5_000, Tuning.DEFAULT.deepFramesPerMin))   // a short last window still gets its one
  }

  @Test fun aWindowedVideoGetsAtLeastAsManyFramesAsTheWholeFilePlanGaveALongVideo() {
    // framesFor capped at 24 frames; the windows never give fewer, for any length from just over 2 minutes up to the 30 read.
    for (seconds in listOf(121, 150, 180, 240, 300, 600, 1_200, 1_800)) {
      val ms = seconds * 1000L
      val total = (0 until FileProcessor.windowCount(ms)).sumOf { FileProcessor.framesInWindow(it, ms) }
      assertEquals("$seconds s: $total frames", true, total >= FileProcessor.framesFor(seconds))
    }
    assertEquals(9, FileProcessor.framesInWindow(0, 300_000))
    assertEquals(6, FileProcessor.framesInWindow(4, 300_000))
    assertEquals(1, FileProcessor.framesInWindow(2, 121_000))      // a 1 s tail: one frame, not nine
  }

  @Test fun aHugePictureIsNeverDecodedAtFullSize() {
    val max = FileProcessor.OCR_MAX_SIDE
    fun bytes(w: Int, h: Int): Long {
      val s = FileProcessor.sampleSize(w, h, max)
      return (w / s).toLong() * (h / s) * 4
    }
    assertEquals(8, FileProcessor.sampleSize(20000, 2000, max))      // a 20000x2000 strip: 2500x250
    assertEquals(2, FileProcessor.sampleSize(8165, 6124, max))       // a 50 MP photo: about 12.5 MP
    assertEquals(1, FileProcessor.sampleSize(4000, 3000, max))       // a normal photo is left alone
    assertEquals(1, FileProcessor.sampleSize(-1, -1, max))           // unreadable bounds: no shrink, and decode fails safely
    assertEquals(true, bytes(20000, 2000) < 16L * 1024 * 1024)
    assertEquals(true, bytes(8165, 6124) < 64L * 1024 * 1024)
    assertEquals(true, bytes(12000, 9000) < 64L * 1024 * 1024)       // 108 MP
  }
}

class SoundFileCantOpenTest {
  @Test fun noWindowsAnywhereMeansThePhoneCantOpenTheSoundFile() {
    // Speex in Ogg, or an M4A with its moov lost: the extractor finds no audio track, so no stage is handed anything.
    assertEquals(true, FileProcessor.decodedNothing(0.0, null))
    assertEquals(true, FileProcessor.decodedNothing(null, 0))
    assertEquals(true, FileProcessor.decodedNothing(0.0, 0))
  }

  @Test fun silenceOrShortSoundIsStillAnOpenedFile() {
    assertEquals(false, FileProcessor.decodedNothing(0.3, null))   // a 0.3 s clip: the speech engine saw its window
    assertEquals(false, FileProcessor.decodedNothing(1.0, 0))
    assertEquals(false, FileProcessor.decodedNothing(null, 2))
    assertEquals(false, FileProcessor.decodedNothing(0.0, 0, 300))  // 0.3 s wav: the platform read its length, it is shorter than one window
    assertEquals(true, FileProcessor.decodedNothing(0.0, 0, null))  // no length: the platform could not open it
    assertEquals(false, FileProcessor.decodedNothing(null, null))  // nothing ran (models missing): not a verdict on the file
  }
}
