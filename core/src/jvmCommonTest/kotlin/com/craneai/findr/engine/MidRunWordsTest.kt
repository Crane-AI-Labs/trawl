package com.craneai.findr.engine

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The worker's meaning pass with files landing mid-run, on a fake clock. [Phone] is the index: files MediaStore holds but the crawl has
 * not seen ([Phone.uncrawled]), crawled files without words ([Phone.noWords], newest first, like nextWithoutWords), and the photos
 * waiting for the meaning pass. [runMeaningPass] is the worker's loop: one photo takes [PHOTO_MS], and between files it either does
 * nothing about new files (the old worker: words only at the start of a run) or calls [MidRunWords.between] as IndexWorker does.
 */
class MidRunWordsTest {
  private class Phone(photos: Int) {
    var now = 0L
    val photosLeft = ArrayDeque((1..photos).map { "photo$it" })
    val uncrawled = ArrayList<String>()
    val noWords = ArrayList<String>()
    val wordsAt = HashMap<String, Long>()      // when a file became word-searchable
    val wordsCalls = HashMap<String, Int>()
    var understood = 0
    var crawls = 0

    fun land(name: String, crawled: Boolean) { if (crawled) noWords.add(0, name) else uncrawled.add(name) }
    fun crawl() { crawls++; uncrawled.reversed().forEach { noWords.add(0, it) }; uncrawled.clear() }
    fun wordsPass(limit: Int): Int {
      var n = 0
      while (n < limit && noWords.isNotEmpty()) {
        val f = noWords.removeAt(0)
        now += if (f.endsWith(".jpg")) OCR_MS else DOC_WORDS_MS
        wordsAt[f] = now; wordsCalls.merge(f, 1, Int::plus); n++
      }
      return n
    }
  }

  private fun Phone.runMeaningPass(midRun: MidRunWords?, until: Long = Long.MAX_VALUE, onFile: (Phone) -> Unit = {}) = runBlocking {
    while (photosLeft.isNotEmpty() && now < until) {
      photosLeft.removeFirst(); now += PHOTO_MS; understood++      // understand one file
      onFile(this@runMeaningPass)
      midRun?.between(crawl = { crawl() }, wordsWaiting = { noWords.isNotEmpty() }, words = { wordsPass(it) })
    }
  }

  @Test fun oldWorkerLeavesAMidRunFileWithoutWordsUntilTheRunEnds() {
    val p = Phone(140)
    var landed = false
    p.runMeaningPass(midRun = null) { if (!landed && p.now >= 100_000) { landed = true; p.land("receipt.txt", crawled = true) } }
    assertNull("no words pass between files: the file waits for the next run", p.wordsAt["receipt.txt"])
    assertEquals(140, p.understood)
  }

  @Test fun aFileThatLandsDuringALongRunIsWordSearchableWithinSeconds() {
    val p = Phone(140)
    val mid = MidRunWords(clock = { p.now })
    var landedAt = -1L
    val docs = listOf("a.txt", "b.txt", "c.txt")
    p.runMeaningPass(mid) {
      if (landedAt < 0 && p.now >= 100_000) { landedAt = p.now; docs.forEach { p.land(it, crawled = true) } }
    }
    val waits = docs.map { (p.wordsAt[it] ?: error("$it never got its words")) - landedAt }
    // The look is due 3 s after the last one, and the file being understood finishes first: never more than that plus the words.
    val bound = MidRunWords.LOOK_EVERY_MS + PHOTO_MS + docs.size * DOC_WORDS_MS
    assertTrue("words within ${bound / 1000} s of landing, took $waits", waits.all { it <= bound })
    assertEquals("the meaning pass still finished every photo", 140, p.understood)
    assertTrue("no file read twice", p.wordsCalls.values.all { it == 1 })
  }

  @Test fun aFileTheCrawlHasNotSeenYetIsCrawledThenWordedWithinOneCrawlInterval() {
    val p = Phone(140)
    val mid = MidRunWords(clock = { p.now })
    var landedAt = -1L
    p.runMeaningPass(mid) { if (landedAt < 0 && p.now >= 100_000) { landedAt = p.now; p.land("late.txt", crawled = false) } }
    val wait = (p.wordsAt["late.txt"] ?: error("never got its words")) - landedAt
    val bound = MidRunWords.CRAWL_EVERY_MS + PHOTO_MS + DOC_WORDS_MS
    assertTrue("crawled and worded within ${bound / 1000} s, took $wait ms", wait <= bound)
    assertTrue("the crawl is incremental and rare, not one per file: ${p.crawls}", p.crawls <= p.now / MidRunWords.CRAWL_EVERY_MS + 1)
  }

  @Test fun aBurstOfPhotosTakesTurnsWithTheMeaningPass() {
    val p = Phone(60)
    val mid = MidRunWords(clock = { p.now })
    var burst = false
    p.runMeaningPass(mid, until = 400_000) {
      if (!burst && p.now >= 50_000) { burst = true; (1..200).forEach { n -> p.land("new$n.jpg", crawled = true) } }
    }
    assertTrue("the meaning pass kept going during the burst, understood ${p.understood}", p.understood >= 25)
    assertTrue("and the burst's words came meanwhile: ${p.wordsAt.size}", p.wordsAt.size > 100)
  }

  @Test fun nothingWaitingCostsOneTimeComparisonAndRareQueries() = runBlocking {
    var now = 0L
    val mid = MidRunWords(clock = { now })
    var queries = 0; var crawls = 0; var passes = 0
    repeat(100) {
      now += 1_000   // a file per second
      mid.between(crawl = { crawls++ }, wordsWaiting = { queries++; false }, words = { passes++; 0 })
    }
    assertEquals(0, passes)
    assertTrue("one query per ${MidRunWords.LOOK_EVERY_MS} ms look at most: $queries", queries in 30..34)
    assertTrue("a crawl every ${MidRunWords.CRAWL_EVERY_MS} ms at most: $crawls", crawls in 1..100_000 / MidRunWords.CRAWL_EVERY_MS.toInt())
  }

  private companion object {
    const val PHOTO_MS = 10_000L      // the A06's understand time for a photo, roughly
    const val OCR_MS = 800L
    const val DOC_WORDS_MS = 50L
  }
}
