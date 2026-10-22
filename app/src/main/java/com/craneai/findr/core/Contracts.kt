package com.craneai.findr.core

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri

// ---- Extraction seams that speak Android types (Context, Uri, Bitmap). The data they return, and SearchEngine, live in :core (core/Contracts.kt). ----

interface TextExtractor {
  fun canRead(mimeType: String?, name: String): Boolean
  /** Reads at most `maxChars`. Pages without a text layer come back empty, for OCR to fill in. */
  suspend fun extract(context: Context, uri: Uri, mimeType: String?, name: String, maxChars: Int = 2_000_000): ExtractedText
}

/** On-device text recognition (screenshots, memes, scanned pages, video frames). */
interface OcrEngine {
  suspend fun read(bitmap: Bitmap): String
}

/** One representative frame per shot. `jpeg` is already downscaled to ≤768 px and ready for the embedder. */
class VideoFrame(val timeMs: Long, val shotStartMs: Long, val shotEndMs: Long, val jpeg: ByteArray, val bitmap: Bitmap?)

interface VideoSampler {
  suspend fun sample(context: Context, uri: Uri, maxFrames: Int = 12): List<VideoFrame>

  /**
   * The same frames, handed to [onFrame] one at a time as each is fetched instead of collected, so a consumer can embed
   * one frame while the next is decoded. [onFrame] runs on the sampling thread and may suspend (a bounded channel's
   * send); the consumer owns each frame's bitmap. [fromMs] and [spanMs] confine the frames to one window of the video (a long
   * video is read a window at a time); times on the frames stay in the whole video's time.
   */
  suspend fun stream(context: Context, uri: Uri, maxFrames: Int, fromMs: Long = 0, spanMs: Long = Long.MAX_VALUE, onFrame: suspend (VideoFrame) -> Unit) =
    sample(context, uri, maxFrames).forEach { onFrame(it) }
}

interface AudioDecoder {
  suspend fun windows(context: Context, uri: Uri, windowMs: Long = 30_000, hopMs: Long = 25_000, maxWindows: Int = 20, startMs: Long = 0): List<AudioWindow>

  /**
   * The same windows, handed to [onWindow] as they are cut instead of collected, so a consumer can work while the rest
   * is still decoding. [onWindow] runs on the decoding thread and may block. [startMs]: begin there (the extractor seeks, nothing
   * before it is decoded); window times stay in the whole file's time.
   */
  suspend fun stream(context: Context, uri: Uri, windowMs: Long, hopMs: Long, maxWindows: Int, startMs: Long = 0, onWindow: (AudioWindow) -> Unit) =
    windows(context, uri, windowMs, hopMs, maxWindows, startMs).forEach(onWindow)
}

/**
 * Speech to words, on the phone (sherpa-onnx: Silero VAD → Moonshine, plus CED-mini sound labels). A soundtrack costs
 * ~0.16 s of CPU per second of audio on the A06 against 0.25–0.36 s for the embedding model's audio encoder, and the
 * words are searchable by their exact text. Null/unavailable when the models or the native library are missing.
 */
interface AsrEngine {
  /** True when the models are installed and the native library loads. Cheap after the first call. */
  fun available(): Boolean
  /** Decodes up to `maxSeconds` of the soundtrack and transcribes it. Empty transcript when there is no audio track. */
  suspend fun transcribe(context: Context, uri: Uri, maxSeconds: Int = 300, startMs: Long = 0): AudioTranscript
  /** Gives the memory back (models are loaded on first use and kept while the indexer runs). */
  fun release() {}
}
