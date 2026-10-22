package com.craneai.findr.extract

import android.content.Context
import android.net.Uri
import com.craneai.findr.core.AsrEngine
import com.craneai.findr.core.AudioDecoder
import com.craneai.findr.core.AudioTranscript
import java.io.File

/** Trawl Go reads no speech (sherpa-onnx and its models are the full edition's): an engine that is never available. */
@Suppress("UNUSED_PARAMETER")
class SherpaAsrEngine(
  modelsDir: File,
  decoder: AudioDecoder? = null,
) : AsrEngine {
  override fun available(): Boolean = false
  override suspend fun transcribe(context: Context, uri: Uri, maxSeconds: Int, startMs: Long): AudioTranscript = AudioTranscript(emptyList(), emptyList(), 0.0)

  companion object {
    const val MOONSHINE_DIR = "moonshine-tiny-en-int8"
    const val PARAKEET_DIR = "parakeet-tdt-ctc-110m-int8"
    const val VAD_FILE = "silero_vad.onnx"
    const val CED_DIR = "ced-mini-int8"
  }
}
