package com.craneai.findr.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class FixedBlocksTest {
  private fun run(pieces: List<Int>, size: Int): List<FloatArray> {
    val out = ArrayList<FloatArray>(); val blocks = FixedBlocks(size); var next = 0f
    for (p in pieces) blocks.add(FloatArray(p) { next++ }) { out += it }
    blocks.finish { out += it }
    return out
  }

  @Test fun theSameBlocksComeOutWhateverTheArrivalSizes() {
    val a = run(listOf(100), 8)
    val b = run(listOf(3, 5, 1, 91), 8)
    val c = run(List(100) { 1 }, 8)
    assertEquals(a.size, b.size); assertEquals(a.size, c.size)
    for (i in a.indices) { assertArrayEquals(a[i], b[i], 0f); assertArrayEquals(a[i], c[i], 0f) }
  }

  @Test fun everyBlockIsFullExceptTheLast() {
    val out = run(listOf(7, 9, 4), 8)           // 20 samples
    assertEquals(listOf(8, 8, 4), out.map { it.size })
    assertEquals(19f, out.last().last(), 0f)
  }

  @Test fun nothingArrivingGivesNothing() = assertEquals(0, run(emptyList(), 8).size)

  @Test fun aBlockHandedOnIsNotReusedByTheNextOne() {
    val out = run(listOf(16), 8)
    assertEquals(0f, out[0][0], 0f); assertEquals(8f, out[1][0], 0f)
  }
}
