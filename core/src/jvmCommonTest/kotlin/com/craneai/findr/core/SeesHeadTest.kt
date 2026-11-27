package com.craneai.findr.core

import com.craneai.findr.index.Fp16
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class SeesHeadTest {
  private fun head(tags: List<String>, biases: List<Float>, coefficients: Map<Pair<Int, Int>, Float> = emptyMap()): SeesHead {
    val vocabulary = List(SeesHead.TAGS) { tags.getOrNull(it) ?: "unused$it" }
    val weights = FloatArray(SeesHead.TAGS * Fp16.DIM)
    for ((position, value) in coefficients) weights[position.first * Fp16.DIM + position.second] = value
    val bias = ByteBuffer.allocate(SeesHead.TAGS * 4).order(ByteOrder.LITTLE_ENDIAN)
    repeat(SeesHead.TAGS) { bias.putFloat(biases.getOrNull(it) ?: -20f) }
    val assets = mapOf(
      "sees/head_vocab.txt" to (vocabulary.joinToString("\n") + "\n").toByteArray(),
      "sees/head_W.f16" to Fp16.toBlob(weights),
      "sees/head_b.f32" to bias.array(),
    )
    return SeesHead.fromAssets(object : AssetSource {
      override fun open(name: String) = ByteArrayInputStream(assets.getValue(name))
    })
  }

  private fun frame(component: Int = 0, value: Float = 1f) = FloatArray(Fp16.DIM) { if (it == component) value else 0f }

  @Test fun thresholdAndNegationFollowTheFrozenRule() {
    val head = head(listOf("no people", "man", "trees", "outdoor", "wrong", "sixth"), listOf(4f, 3f, 2f, -0.84f, -0.86f, -0.87f))
    assertEquals("Sees: man, trees, outdoor", head.line(listOf(frame())))
  }

  @Test fun negatedTagsConsumeTheirTopFiveSlotBeforeTheyAreDropped() {
    val head = head(listOf("no people", "man", "trees", "outdoor", "church", "kitchen"), listOf(6f, 5f, 4f, 3f, 2f, 1f))
    assertEquals("Sees: man, trees, outdoor, church", head.line(listOf(frame())))
  }

  @Test fun allFramesAreMergedDistinctAndCappedAtEight() {
    val tags = List(10) { "visible$it" }
    val coefficients = tags.indices.associate { (it to if (it < 5) 0 else 1) to 5f }
    val head = head(tags, List(10) { -2f }, coefficients)
    assertEquals("Sees: visible0, visible1, visible2, visible3, visible4, visible5, visible6, visible7", head.line(listOf(frame(), frame(1), frame())))
  }

  @Test fun highestProbabilityAcrossFramesDeterminesOrder() {
    val head = head(listOf("trees", "church"), listOf(-2f, -1f), mapOf((0 to 0) to 5f, (1 to 1) to 6f))
    assertEquals("Sees: church, trees", head.line(listOf(frame(), frame(1))))
  }

  @Test fun freshAndStoredVectorsUseTheSameFp16Inputs() {
    val head = head(listOf("trees", "church"), listOf(-2f, -1f), mapOf((0 to 0) to 5f, (1 to 0) to 2f))
    val fresh = frame(value = 0.33333334f)
    assertEquals(head.line(listOf(Fp16.blobToFloats(Fp16.toBlob(fresh)))), head.line(listOf(fresh)))
  }

  @Test fun emptyAndLowConfidenceFramesHaveNoWords() {
    val head = head(listOf("trees"), listOf(-20f))
    assertNull(head.line(emptyList()))
    assertNull(head.line(listOf(frame())))
  }

  @Test fun wrongDimensionsAndNonFiniteInputsAreRejected() {
    val head = head(listOf("trees"), listOf(2f))
    assertThrows(IllegalArgumentException::class.java) { head.line(listOf(FloatArray(512))) }
    assertThrows(IllegalArgumentException::class.java) { head.line(listOf(frame(value = Float.NaN))) }
  }

  @Test fun cancellationIsCheckedForEveryFrame() {
    val head = head(listOf("trees"), listOf(2f))
    var checked = 0
    assertThrows(InterruptedException::class.java) {
      head.line(listOf(frame(), frame())) { if (++checked == 2) throw InterruptedException() }
    }
    assertEquals(2, checked)
  }

  @Test fun malformedAssetsAreRejected() {
    assertThrows(IllegalArgumentException::class.java) {
      SeesHead.fromAssets(object : AssetSource {
        override fun open(name: String) = ByteArrayInputStream("invalid\n".toByteArray())
      })
    }
  }
}
