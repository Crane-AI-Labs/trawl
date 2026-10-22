package com.craneai.findr.engine

import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Trawl Go's embedder: there is no model, ever. It is the permanent form of the state the full app is in before its models are
 * installed: names and words are searchable, and meaning is "pending" (here: not offered). Nothing in the app loads or waits for
 * a model file when [modelFile] is null, so every embedding call is a bug in the caller and says so, loudly, instead of
 * returning a vector that would poison the index.
 */
class NoModelEmbedder(override val profile: MemoryProfile = MemoryProfile.LITE) : Embedder {
  override val repairing: StateFlow<Boolean> = MutableStateFlow(false)
  override val modelNames: List<String> = emptyList()
  override val modelFile: File? = null
  override val supportsAudio: Boolean = false
  override val imagesByEmbeddingGemma: Boolean = false
  override val imageFingerprint: String? = null
  override val textFingerprint: String? = null

  override suspend fun query(text: String): FloatArray = noModel()
  override suspend fun warm() {}
  override suspend fun document(text: String, title: String?): FloatArray = noModel()
  override suspend fun image(jpeg: ByteArray): FloatArray = noModel()
  override suspend fun audio(wav: ByteArray): FloatArray = noModel()
  override suspend fun release() {}

  override suspend fun dropAudioEncoder() {}

  private fun noModel(): Nothing = throw UnsupportedOperationException("Trawl Go has no embedding model")
}
