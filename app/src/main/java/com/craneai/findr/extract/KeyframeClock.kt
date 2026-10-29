package com.craneai.findr.extract

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.craneai.findr.core.FindrLog as Log

/**
 * When the keyframe nearest a moment really is. `MediaMetadataRetriever` with `OPTION_CLOSEST_SYNC` hands back the keyframe nearest the
 * time it is asked for and says nothing about which time that was; on a camera video keyframes are ~1 s apart, but a forwarded one
 * (re-encoded with x264's defaults, WhatsApp's too) has one every ~8 s, so the picture can be seconds from the moment requested and a
 * result's "at 0:40" sent people to a different scene.
 *
 * `MediaExtractor.seekTo(…, SEEK_TO_CLOSEST_SYNC)` followed by `sampleTime` answers from the container's sample index: the same choice
 * the retriever makes, with nothing decoded, so one extractor opened per video costs about what the retriever's own `setDataSource`
 * does (the index read) plus microseconds a seek. The sampler asks here first and then fetches the picture at the time it was told, so
 * the time kept is the one the picture came from.
 *
 * Where the container can't be read this way the answer is null and the caller keeps the time it asked for, as before.
 */
internal class KeyframeClock private constructor(private val extractor: MediaExtractor) : AutoCloseable {

  /** The time, in ms, of the keyframe nearest [timeMs]; null when the extractor gives none (the stream has no index to seek in). */
  fun nearestMs(timeMs: Long): Long? = try {
    extractor.seekTo(timeMs * 1000, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
    val us = extractor.sampleTime
    if (us < 0) null else us / 1000
  } catch (t: Exception) {
    Log.w(TAG, "keyframe time not read (${Log.brief(t)})")
    null
  }

  override fun close() {
    try {
      extractor.release()
    } catch (_: Exception) {
    }
  }

  companion object {
    private const val TAG = "FindrVideo"

    /** A clock over the first video track of [uri], or null when it can't be opened (the caller then trusts the time it asked for). */
    fun open(context: Context, uri: Uri): KeyframeClock? {
      val extractor = MediaExtractor()
      return try {
        extractor.setDataSource(context, uri, null)
        val track = (0 until extractor.trackCount).firstOrNull {
          extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
        } ?: run { extractor.release(); return null }
        extractor.selectTrack(track)
        KeyframeClock(extractor)
      } catch (t: Exception) {
        Log.w(TAG, "keyframe clock not opened (${Log.brief(t)})")
        try { extractor.release() } catch (_: Exception) { }
        null
      }
    }
  }
}
