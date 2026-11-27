package com.craneai.findr.index

object SeesCatchUp {
  const val KEY = "sees-reading"
  const val VERSION = 1

  fun allowed(videoEnabled: Boolean, imageVectors: Boolean, charging: Boolean): Boolean = videoEnabled && imageVectors && charging

  class Frames(val fileId: Long, val generation: Long, val fingerprint: String, val indexedAt: Long?, val vectors: List<FloatArray>)
}
