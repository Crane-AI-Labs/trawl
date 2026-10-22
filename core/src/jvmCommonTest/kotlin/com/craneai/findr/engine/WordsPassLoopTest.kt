package com.craneai.findr.engine

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WordsPassLoopTest {
  /** The index's "no words yet" list: next(n) hands out the first n items not yet committed, like nextWithoutWords. */
  private class FakeQueue(n: Int) {
    private val items = (1..n).toList()
    val committed = ConcurrentHashMap.newKeySet<Int>()
    val ocrCalls = ConcurrentHashMap<Int, AtomicInteger>()
    fun next(limit: Int): List<Int> = items.filter { it !in committed }.take(limit)
    suspend fun ocr(item: Int): Boolean {
      ocrCalls.getOrPut(item) { AtomicInteger() }.incrementAndGet()
      delay(1)            // the OCR takes a while: the other caller's batch is fetched meanwhile
      committed += item   // saved per file
      return true
    }
  }

  @Test fun twoConcurrentPassesNeverOcrTheSameFile() = runBlocking {
    val q = FakeQueue(334)
    val lock = Mutex()
    val done = (1..2).map { cap ->
      async(Dispatchers.Default) { WordsPassLoop.run(lock, if (cap == 1) 300 else Int.MAX_VALUE, { false }, q::next, process = q::ocr) }
    }.awaitAll()
    assertEquals(334, q.committed.size)
    assertEquals("no file OCR'd twice", 334, q.ocrCalls.values.sumOf { it.get() })
    assertEquals(334, done.sum())
  }

  @Test fun withoutTheSharedLockTheyDuplicate() = runBlocking {
    // The bug this guards against: two passes with their own locks fetch the same batches.
    val q = FakeQueue(334)
    (1..2).map { async(Dispatchers.Default) { WordsPassLoop.run(Mutex(), Int.MAX_VALUE, { false }, q::next, process = q::ocr) } }.awaitAll()
    assertTrue("duplicates expected without a shared lock", q.ocrCalls.values.sumOf { it.get() } > 334)
  }

  @Test fun stopAndLimitEndThePass() = runBlocking {
    val q = FakeQueue(100)
    assertEquals(40, WordsPassLoop.run(Mutex(), 40, { false }, q::next, process = q::ocr))
    var calls = 0
    assertEquals(0, WordsPassLoop.run(Mutex(), 100, { calls++ > 0 }, q::next, process = q::ocr))
  }

  @Test fun aNonWaitingCallerReturnsAtOnceWhileAPassRuns() = runBlocking {
    val q = FakeQueue(50)
    val lock = Mutex()
    val running = async(Dispatchers.Default) { WordsPassLoop.run(lock, Int.MAX_VALUE, { false }, q::next, process = q::ocr) }
    while (q.committed.isEmpty()) delay(1)
    assertEquals(0, WordsPassLoop.run(lock, Int.MAX_VALUE, { false }, q::next, wait = false, process = q::ocr))
    assertEquals(50, running.await())
    assertEquals("the running pass did every file once", 50, q.ocrCalls.values.sumOf { it.get() })
    assertTrue("the lock is free again", !lock.isLocked)
  }

  @Test fun aFileAddedDuringAPassIsTakenWithinOneBatch() = runBlocking {
    // Newest first, like nextWithoutWords: a note crawled mid-pass jumps ahead of the photos still waiting.
    val order = ArrayList<Int>()
    val waiting = (1..100).toMutableList()
    var added = false
    val next = { n: Int -> synchronized(waiting) { waiting.take(n) } }
    WordsPassLoop.run(Mutex(), Int.MAX_VALUE, { false }, next) { item ->
      synchronized(waiting) { waiting.remove(item) }
      order += item
      if (order.size == 20 && !added) { added = true; synchronized(waiting) { waiting.add(0, 999) } }
      true
    }
    assertTrue("the new file came within ${WordsPassLoop.BATCH} items", order.indexOf(999) - 20 <= WordsPassLoop.BATCH)
  }
}
