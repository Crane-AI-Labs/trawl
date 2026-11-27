package com.craneai.findr.engine

import com.craneai.findr.core.FileKind
import com.craneai.findr.index.FileState
import com.craneai.findr.index.IndexStore
import com.craneai.findr.index.PendingFile
import com.craneai.findr.index.Segment
import com.craneai.findr.index.ReadingStamps
import com.craneai.findr.index.SoundtrackCatchUp
import com.craneai.findr.index.VideoCatchUp
import kotlinx.coroutines.withTimeoutOrNull

internal class UnderstandingStep(private val store: IndexStore, private val processor: FileProcessor) {
  class Outcome(val segments: List<Segment>, val next: Int?, val detail: String?, val finished: Boolean, val timedOut: Boolean, val dbMs: Long)

  suspend fun process(pending: PendingFile, withAudio: Boolean, withSpeech: Boolean, timeoutMs: Long, imagesByEmbeddingGemma: Boolean): Outcome {
    val from = from(pending)
    val progress = DocumentProgress(from)
    val processed = withTimeoutOrNull(timeoutMs) {
      processor.process(pending.file, from, withAudio, withSpeech, progress)
    }
    val timedOut = processed == null
    val result = processed ?: progress.result() ?: Processed(emptyList(), from, null)
    val stalled = if (timedOut && progress.completed == 0) {
      (pending.detail?.let { TIMEOUTS.find(it)?.groupValues?.get(1)?.toIntOrNull() } ?: 0) + 1
    } else 0
    val failed = timedOut && result.nextChunk != null && stalled >= MAX_STALLED_TIMEOUTS
    val state = when {
      failed -> FileState.FAILED
      result.nextChunk != null -> FileState.PARTIAL
      else -> FileState.UNDERSTOOD
    }
    val detail = if (failed) "took too long without progress ($MAX_STALLED_TIMEOUTS attempts)" else listOfNotNull(
      result.detail,
      result.nextChunk?.let { "next=$it" },
      "timeouts=$stalled".takeIf { stalled > 0 && result.nextChunk != null },
      pending.detail?.let { SKIPPED_WINDOWS.find(it)?.value },
    ).joinToString(" ").ifEmpty { null }
    val segments = result.pieces.map { it.toSegment(late = from > 0) }
    val dbStarted = System.nanoTime()
    store.saveSegments(pending.file.id, segments, state, detail, replace = from == 0 && (!timedOut || result.pieces.isNotEmpty()),
      info = readingStamps(pending.file.kind, store.speechPackStamp).takeIf { from == 0 && !timedOut },
      keepQuick = IndexWorker.keepsQuick(segments, imagesByEmbeddingGemma))
    if (!timedOut && state == FileState.UNDERSTOOD && pending.file.kind == FileKind.VIDEO) processor.seesWords(store, pending.file.id)
    val dbMs = (System.nanoTime() - dbStarted) / 1_000_000
    return Outcome(segments, result.nextChunk.takeUnless { timedOut }, detail, state == FileState.UNDERSTOOD, timedOut, dbMs)
  }

  companion object {
    const val MAX_STALLED_TIMEOUTS = 3
    private val NEXT_CHUNK = Regex("next=(\\d+)")
    private val TIMEOUTS = Regex("timeouts=(\\d+)")
    private val SKIPPED_WINDOWS = Regex("skipped=\\d+")

    /**
     * What a fresh read of a [kind] file is made by today ([VideoCatchUp], [SoundtrackCatchUp]): written on its first step, so a file read by
     * an older build, or begun by one, has less and is caught up. [speechPack]: the speech pack in use ([SoundtrackCatchUp.PACK_KEY]), whose
     * reading a fresh read of a soundtrack already has.
     */
    fun readingStamps(kind: FileKind, speechPack: String? = null): String? {
      val sound = when (kind) {
        FileKind.VIDEO -> ReadingStamps.put(ReadingStamps.put(null, VideoCatchUp.KEY, VideoCatchUp.VERSION), SoundtrackCatchUp.KEY, SoundtrackCatchUp.VERSION)
        FileKind.AUDIO -> ReadingStamps.put(null, SoundtrackCatchUp.KEY, SoundtrackCatchUp.VERSION)
        else -> return null
      }
      return if (speechPack == null) sound else ReadingStamps.put(sound, SoundtrackCatchUp.PACK_KEY, speechPack)
    }

    fun from(pending: PendingFile): Int = pending.detail?.let { NEXT_CHUNK.find(it)?.groupValues?.get(1)?.toIntOrNull() } ?: 0
  }
}
